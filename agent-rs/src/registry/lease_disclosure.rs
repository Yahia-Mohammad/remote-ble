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

/// Longest identity fragment rendered into a message; the rest becomes an ellipsis.
const MAX_FRAGMENT_CHARS: usize = 48;

const SESSION_KEY_SEPARATOR: char = '\u{0}';

/// A `PERIPHERAL_BUSY` message describing who holds the peripheral, addressed to `requester_key`.
/// A key with no separator is treated as a bare principal.
pub fn busy_message(owner_key: &str, requester_key: &str) -> String {
    let (owner_principal, owner_client_id) = split(owner_key);
    let (requester_principal, _) = split(requester_key);
    let principal = sanitize(owner_principal);
    match owner_client_id {
        Some(client_id) if owner_principal == requester_principal => format!(
            "peripheral in use by principal '{principal}', client '{}'",
            sanitize(client_id)
        ),
        _ => format!("peripheral in use by principal '{principal}'"),
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
    let (owner_principal, owner_client_id) = split(owner_key);
    let (requester_principal, _) = split(requester_key);
    let principal = sanitize(owner_principal);
    match owner_client_id {
        Some(client_id) if operator_scope || owner_principal == requester_principal => {
            format!("{principal}/{}", sanitize(client_id))
        }
        _ => principal,
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
        let message = busy_message(&key("lab-a", "rble-laptop"), &key("lab-a", "rble-ci"));
        assert!(message.contains("principal 'lab-a'"), "{message}");
        assert!(message.contains("client 'rble-laptop'"), "{message}");
    }

    #[test]
    fn withholds_the_client_id_from_another_principal() {
        let message = busy_message(&key("lab-a", "rble-laptop"), &key("lab-b", "rble-laptop"));
        assert!(message.contains("principal 'lab-a'"), "{message}");
        assert!(!message.contains("rble-laptop"), "{message}");
    }

    #[test]
    fn escapes_control_characters_and_the_delimiting_quote() {
        let message = busy_message(&key("lab-a", "evil\n free'-now"), &key("lab-a", "rble-ci"));
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
        let message = busy_message(&key("lab-a", &"c".repeat(500)), &key("lab-a", "rble-ci"));
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
        );
        assert!(message.chars().count() < 100, "{message}");
        assert!(message.contains('…'), "{message}");
    }

    #[test]
    fn treats_a_key_with_no_client_id_as_a_bare_principal() {
        assert_eq!(
            busy_message("lab-a", &key("lab-a", "rble-ci")),
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
