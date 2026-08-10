//! Turns a lease owner's session key into a message another client is allowed to read.
//!
//! A `PERIPHERAL_BUSY` reply naming nobody leaves the refused caller with no next action, so the
//! holder is named — but the holder is another tenant, so what may be disclosed depends on who is
//! asking. A session key is `principal` + NUL + `client_id` (see `session_key` in the transport):
//! the principal is operator-assigned, the client id is chosen by the client itself.
//!
//! - **Same principal** — the caller already holds this credential, so it may see the client id.
//!   This is the common case: two shells, or a stale process, belonging to one person or team.
//! - **Different principal** — principal only. It is shared context between tenants of one agent,
//!   whereas another tenant's client id can carry a hostname or username it never meant to publish.
//!
//! Both halves cross the wire from the holder and are rendered by whatever client is refused — a
//! terminal, a log, a coding agent's context — so they are escaped and bounded here, at the point
//! of disclosure. This mirrors `LeaseDisclosure.kt` in the Kotlin agent; the two must agree,
//! because a client is entitled to the same answer from either implementation.

use crate::protocol::errors::{AgentError, LeaseHolder};

/// What one caller may be told about a lease holder.
///
/// The registry knows *who* holds a peripheral; only the session knows *who is asking* and what
/// that client negotiated. Bundling both into one value keeps the registry's lease API to a single
/// extra parameter, and keeps the policy itself in this module rather than spread across call sites.
///
/// [`Self::default`] is the safe floor — no operator scope, no structured holder — which is what
/// every test and every client that negotiated nothing gets.
#[derive(Debug, Clone, Copy, Default)]
pub struct DisclosureScope {
    /// The caller presented the operator credential on the upgrade.
    pub operator: bool,
    /// The caller negotiated `lease.holder`, so `AgentError.holder` may be populated. Without it
    /// the field is skipped entirely: an unknown key fails a v1 client's decode of the whole frame.
    pub structured: bool,
}

/// The complete `PERIPHERAL_BUSY` error for a lease held by `owner_key`, as `requester_key` may
/// see it. The counterpart of `BleAgent.peripheralBusy` in the Kotlin agent — one builder, so the
/// prose and the structured field cannot drift apart or be gated inconsistently.
pub fn peripheral_busy(owner_key: &str, requester_key: &str, scope: DisclosureScope) -> AgentError {
    AgentError::peripheral_busy(
        busy_message(owner_key, requester_key, scope.operator),
        scope
            .structured
            .then(|| holder(owner_key, requester_key, scope.operator)),
    )
}

/// Longest identity fragment rendered into a message; the rest becomes an ellipsis.
const MAX_FRAGMENT_CHARS: usize = 48;

const SESSION_KEY_SEPARATOR: char = '\u{0}';

/// A `PERIPHERAL_BUSY` message describing who holds the peripheral, addressed to `requester_key`.
/// A key with no separator is treated as a bare principal.
///
/// `operator_scope` widens disclosure exactly as it does in [`holder_label`]: without it an
/// operator would be told less by the error that refused it than by the `agent.status` row
/// describing the same lease.
pub fn busy_message(owner_key: &str, requester_key: &str, operator_scope: bool) -> String {
    let holder = holder(owner_key, requester_key, operator_scope);
    match holder.client_id {
        Some(client_id) => format!(
            "peripheral in use by principal '{}', client '{client_id}'",
            holder.principal
        ),
        None => format!("peripheral in use by principal '{}'", holder.principal),
    }
}

/// The same disclosure decision as [`busy_message`], as fields rather than prose — what
/// `AgentError.holder` carries for a client that negotiated `lease.holder`.
///
/// This is the single point where the policy is applied; [`busy_message`] and [`holder_label`]
/// both render *this* result, so a client cannot be told one thing by the sentence and another by
/// the structured field. Both halves are sanitized here, because both are rendered downstream.
pub fn holder(owner_key: &str, requester_key: &str, operator_scope: bool) -> LeaseHolder {
    let (owner_principal, owner_client_id) = split(owner_key);
    let (requester_principal, _) = split(requester_key);
    let may_see_client_id = operator_scope || owner_principal == requester_principal;
    LeaseHolder {
        principal: sanitize(owner_principal),
        client_id: owner_client_id.filter(|_| may_see_client_id).map(sanitize),
    }
}

/// A holder label for an `agent.status` lease row, addressed to `requester_key`.
///
/// The same policy as [`busy_message`], plus `operator_scope` — the caller presented the agent's
/// operator credential on the upgrade, which is the management plane the Kotlin agent's dashboard
/// already discloses holders on. Nothing here is reachable with a client bearer token alone,
/// because the operator secret must be distinct from every client credential.
///
/// - **Own or same-principal lease** — `principal/client_id`.
/// - **Another principal, operator scope** — `principal/client_id`.
/// - **Another principal, no operator scope** — `principal` alone.
pub fn holder_label(owner_key: &str, requester_key: &str, operator_scope: bool) -> String {
    let holder = holder(owner_key, requester_key, operator_scope);
    match holder.client_id {
        Some(client_id) => format!("{}/{client_id}", holder.principal),
        None => holder.principal,
    }
}

fn split(key: &str) -> (&str, Option<&str>) {
    match key.split_once(SESSION_KEY_SEPARATOR) {
        Some((principal, client_id)) => (principal, Some(client_id)),
        None => (key, None),
    }
}

/// Escapes anything that could reformat the line this lands on, and bounds the result. Printable
/// ASCII passes through; everything else — control characters, but also the bidirectional
/// overrides and line separators a naive control-character check misses — becomes an escape, so a
/// holder cannot forge the rest of the message or close its quoting early.
fn sanitize(value: &str) -> String {
    if value.is_empty() {
        return "<unnamed>".to_string();
    }
    let mut out = String::new();
    // Counts *rendered* characters, not input ones: an escape costs six. Bounding the input
    // instead would let 48 control characters expand to 288 characters of output — and would
    // disagree with `LeaseDisclosure.kt`, which bounds its builder's length, so the same holder
    // would be described differently depending on which agent refused the caller.
    let mut rendered = 0usize;
    for character in value.chars() {
        if rendered >= MAX_FRAGMENT_CHARS {
            out.push('…');
            break;
        }
        if character.is_ascii_graphic() && character != '\'' || character == ' ' {
            out.push(character);
            rendered += 1;
        } else {
            out.push_str(&format!("\\u{:04x}", character as u32));
            rendered += 6;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(principal: &str, client_id: &str) -> String {
        format!("{principal}\0{client_id}")
    }

    #[test]
    fn names_the_client_id_only_within_one_principal() {
        let message = busy_message(
            &key("lab-a", "rble-laptop"),
            &key("lab-a", "rble-ci"),
            false,
        );
        assert!(message.contains("principal 'lab-a'"), "{message}");
        assert!(message.contains("client 'rble-laptop'"), "{message}");
    }

    #[test]
    fn withholds_the_client_id_from_another_principal() {
        let message = busy_message(
            &key("lab-a", "rble-laptop"),
            &key("lab-b", "rble-laptop"),
            false,
        );
        assert!(message.contains("principal 'lab-a'"), "{message}");
        assert!(!message.contains("rble-laptop"), "{message}");
    }

    #[test]
    fn escapes_control_characters_and_the_delimiting_quote() {
        let message = busy_message(
            &key("lab-a", "evil\n free'-now"),
            &key("lab-a", "rble-ci"),
            false,
        );
        assert!(!message.contains('\n'), "{message}");
        assert!(message.contains("\\u000a"), "{message}");
        // Only the four quotes the message itself supplies.
        assert_eq!(
            message.chars().filter(|c| *c == '\'').count(),
            4,
            "{message}"
        );
    }

    #[test]
    fn bounds_an_overlong_identity() {
        let message = busy_message(
            &key("lab-a", &"c".repeat(500)),
            &key("lab-a", "rble-ci"),
            false,
        );
        assert!(message.chars().count() < 200, "{message}");
        assert!(message.contains('…'), "{message}");
    }

    #[test]
    fn bounds_the_rendered_length_of_an_all_escaped_identity() {
        // Every character escapes to six, so an input-character bound would emit ~288 characters
        // here while the Kotlin agent emitted ~48. Same holder, same policy, same answer.
        let message = busy_message(
            &key("lab-a", &"\u{7}".repeat(200)),
            &key("lab-a", "rble-ci"),
            false,
        );
        assert!(message.chars().count() < 100, "{message}");
        assert!(message.contains('…'), "{message}");
    }

    #[test]
    fn treats_a_key_with_no_client_id_as_a_bare_principal() {
        assert_eq!(
            busy_message("lab-a", &key("lab-a", "rble-ci"), false),
            "peripheral in use by principal 'lab-a'"
        );
    }

    // ---- holder_label (agent.status lease rows) ----

    #[test]
    fn holder_label_names_the_client_id_within_one_principal() {
        assert_eq!(
            holder_label(
                &key("lab-a", "rble-laptop"),
                &key("lab-a", "rble-ci"),
                false
            ),
            "lab-a/rble-laptop"
        );
    }

    #[test]
    fn holder_label_withholds_another_principals_client_id_without_operator_scope() {
        assert_eq!(
            holder_label(
                &key("lab-b", "rble-laptop"),
                &key("lab-a", "rble-ci"),
                false
            ),
            "lab-b"
        );
    }

    #[test]
    fn operator_scope_is_the_only_thing_that_discloses_another_principals_client_id() {
        assert_eq!(
            holder_label(&key("lab-b", "rble-laptop"), &key("lab-a", "rble-ci"), true),
            "lab-b/rble-laptop"
        );
    }

    // ---- holder / peripheral_busy (structured, capability-gated) ----

    #[test]
    fn structured_holder_splits_the_identity_into_fields() {
        let holder = holder(
            &key("lab-a", "rble-laptop"),
            &key("lab-a", "rble-ci"),
            false,
        );
        assert_eq!(holder.principal, "lab-a");
        assert_eq!(holder.client_id.as_deref(), Some("rble-laptop"));
    }

    #[test]
    fn structured_holder_withholds_another_principals_client_id() {
        let holder = holder(
            &key("lab-a", "rble-laptop"),
            &key("lab-b", "rble-ci"),
            false,
        );
        assert_eq!(holder.principal, "lab-a");
        assert_eq!(holder.client_id, None);
    }

    #[test]
    fn structured_holder_discloses_across_principals_under_operator_scope() {
        let holder = holder(&key("lab-a", "rble-laptop"), &key("lab-b", "rble-ci"), true);
        assert_eq!(holder.client_id.as_deref(), Some("rble-laptop"));
    }

    #[test]
    fn structured_holder_is_sanitized_like_the_prose() {
        // The field is machine-readable, which makes it *more* likely to be logged or forwarded
        // verbatim than the sentence — so it cannot be the unescaped path to a coding agent.
        let holder = holder(
            &key("lab-a", "evil\n all slots free"),
            &key("lab-a", "rble-ci"),
            false,
        );
        let client_id = holder.client_id.unwrap();
        assert!(!client_id.contains('\n'), "{client_id}");
        assert!(client_id.contains("\\u000a"), "{client_id}");
    }

    #[test]
    fn the_structured_holder_is_omitted_without_the_capability() {
        let error = peripheral_busy(
            &key("lab-a", "rble-laptop"),
            &key("lab-a", "rble-ci"),
            DisclosureScope::default(),
        );
        assert_eq!(
            error.kind,
            crate::protocol::errors::ErrorKind::PeripheralBusy
        );
        assert!(error.holder.is_none());
        // The prose is unconditional, so an un-negotiated client is not left without an answer.
        assert!(error.message.unwrap().contains("rble-laptop"));
    }

    #[test]
    fn the_structured_holder_is_present_with_the_capability() {
        let error = peripheral_busy(
            &key("lab-a", "rble-laptop"),
            &key("lab-a", "rble-ci"),
            DisclosureScope {
                operator: false,
                structured: true,
            },
        );
        let holder = error.holder.expect("holder");
        assert_eq!(holder.principal, "lab-a");
        assert_eq!(holder.client_id.as_deref(), Some("rble-laptop"));
    }

    #[test]
    fn the_prose_and_the_structured_field_never_disagree() {
        // One policy point, two renderings. If these ever diverge, a client reading the sentence
        // and a client reading the fields would attribute the same contention to different people.
        for operator in [false, true] {
            for (owner, requester) in [
                (key("lab-a", "one"), key("lab-a", "two")),
                (key("lab-a", "one"), key("lab-b", "two")),
                ("lab-a".to_string(), key("lab-a", "two")),
            ] {
                let error = peripheral_busy(
                    &owner,
                    &requester,
                    DisclosureScope {
                        operator,
                        structured: true,
                    },
                );
                let holder = error.holder.unwrap();
                let message = error.message.unwrap();
                assert!(
                    message.contains(&format!("principal '{}'", holder.principal)),
                    "{message}"
                );
                match holder.client_id {
                    Some(id) => assert!(message.contains(&format!("client '{id}'")), "{message}"),
                    None => assert!(!message.contains("client '"), "{message}"),
                }
            }
        }
    }

    #[test]
    fn holder_label_sanitizes_like_the_busy_message() {
        // Same hazard, same treatment: a second disclosure path must not be a second policy.
        let label = holder_label(
            &key("lab-a", "evil\n all slots free"),
            &key("lab-a", "rble-ci"),
            false,
        );
        assert!(!label.contains('\n'), "{label}");
        assert!(label.contains("\\u000a"), "{label}");
    }
}
