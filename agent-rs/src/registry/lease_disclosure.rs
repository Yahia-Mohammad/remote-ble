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
    let mut rendered = 0usize;
    for character in value.chars() {
        if rendered >= MAX_FRAGMENT_CHARS {
            out.push('…');
            break;
        }
        if character.is_ascii_graphic() && character != '\'' || character == ' ' {
            out.push(character);
        } else {
            out.push_str(&format!("\\u{:04x}", character as u32));
        }
        rendered += 1;
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
        let message = busy_message(
            &key("lab-a", "evil\n free'-now"),
            &key("lab-a", "rble-ci"),
        );
        assert!(!message.contains('\n'), "{message}");
        assert!(message.contains("\\u000a"), "{message}");
        // Only the four quotes the message itself supplies.
        assert_eq!(message.chars().filter(|c| *c == '\'').count(), 4, "{message}");
    }

    #[test]
    fn bounds_an_overlong_identity() {
        let message = busy_message(&key("lab-a", &"c".repeat(500)), &key("lab-a", "rble-ci"));
        assert!(message.chars().count() < 200, "{message}");
        assert!(message.contains('…'), "{message}");
    }

    #[test]
    fn treats_a_key_with_no_client_id_as_a_bare_principal() {
        assert_eq!(
            busy_message("lab-a", &key("lab-a", "rble-ci")),
            "peripheral in use by principal 'lab-a'"
        );
    }
}
