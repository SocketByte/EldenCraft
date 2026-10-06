//! Bounded, context-bound interaction mailbox. No game pointers cross this wire.
use serde::{Deserialize, Serialize};

pub const VERSION: u32 = 1;
pub const FRESH_MS: u64 = 500;
pub const MAX_CHOICES: usize = 64;
pub const MAX_TEXT: usize = 4096;
pub const MAX_FILE: u64 = 65536;
pub const MAX_HOST: usize = 128 * 1024;
pub const MAX_MENU: usize = 32 * 1024;

#[derive(Clone, Debug, Default, Serialize)]
pub struct Input {
    pub seq: u64,
    pub buttons: u32,
    pub pressed: u32,
}
#[derive(Clone, Debug, Serialize)]
pub struct Prompt {
    pub token: u64,
    pub text_id: i32,
    pub text: String,
    pub enabled: bool,
}
#[derive(Clone, Debug, Serialize)]
pub struct Choice {
    pub id: i32,
    pub text: String,
    pub enabled: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub action: Option<String>,
}
#[derive(Clone, Debug, Serialize)]
pub struct Menu {
    pub token: u64,
    pub kind: String,
    pub title: String,
    pub choices: Vec<Choice>,
}
#[derive(Clone, Debug, Serialize)]
pub struct Subtitle {
    pub text: String,
}
#[derive(Clone, Debug, Serialize)]
pub struct State {
    pub version: u32,
    pub pid: u32,
    pub session: u64,
    pub seq: u64,
    pub timestamp_ms: u64,
    pub active: bool,
    pub blocking: bool,
    pub prompt: Option<Prompt>,
    pub menu: Option<Menu>,
    pub subtitle: Option<Subtitle>,
    pub input: Input,
}

/// Test eligibility before suppressing any native window: JSON escaping counts.
pub fn menu_fits(menu: &Menu) -> bool {
    serde_json::to_vec(menu).is_ok_and(|bytes| bytes.len() <= MAX_MENU)
}
/// Optional HUD data may shrink; an already owned native menu is retained.
pub fn bound_state(state: &mut State) {
    let fits = |state: &State| serde_json::to_vec(state).is_ok_and(|v| v.len() <= MAX_HOST);
    if !fits(state) {
        state.subtitle = None;
    }
    if !fits(state) {
        state.prompt = None;
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Command {
    pub version: u32,
    pub pid: u32,
    pub session: u64,
    pub seq: u64,
    pub timestamp_ms: u64,
    pub token: u64,
    pub action: String,
    pub choice: Option<i32>,
    pub open: Option<bool>,
    pub ready: Option<bool>,
}

pub fn valid(command: &Command, pid: u32, session: u64, last_seq: u64, now: u64) -> bool {
    command.version == VERSION
        && command.pid == pid
        && command.session == session
        && command.seq > last_seq
        && command.seq <= i64::MAX as u64
        && command.timestamp_ms <= now.saturating_add(100)
        && now.saturating_sub(command.timestamp_ms) <= FRESH_MS
        && match command.action.as_str() {
            "ui_state" => command.token == 0 && command.open.is_some() && command.ready.is_some(),
            "select" => command.token > 0 && command.choice.is_some(),
            "close" | "interact" => command.token > 0,
            _ => false,
        }
}

/// A button is authoritative only for this exact current menu and enabled row.
pub fn menu_result(menu: &Menu, command: &Command) -> Option<i32> {
    if menu.token != command.token {
        return None;
    }
    match command.action.as_str() {
        "close" => Some(-1),
        "select" => menu
            .choices
            .iter()
            .find(|row| Some(row.id) == command.choice && row.enabled && row.action.is_none())
            .map(|row| row.id),
        _ => None,
    }
}

/// Keep all Unicode while dropping scaleform tags and control characters.
pub fn text(value: &str) -> Option<String> {
    let mut in_tag = false;
    let mut result = String::new();
    for character in value.chars().take(MAX_TEXT) {
        if character == '<' {
            in_tag = true;
        } else if character == '>' && in_tag {
            in_tag = false;
        } else if !in_tag && (!character.is_control() || matches!(character, '\n' | '\t')) {
            result.push(character);
        }
    }
    let result = result.trim().to_owned();
    (!result.is_empty()).then_some(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn escaped_menu_and_total_host_output_respect_guest_transport_limits() {
        let mut menu = Menu {
            token: 4,
            kind: "npc".into(),
            title: "Talk".into(),
            choices: vec![Choice {
                id: 1,
                text: "\"".repeat(MAX_TEXT),
                enabled: true,
                action: None,
            }],
        };
        assert!(menu_fits(&menu));
        menu.choices = (0..MAX_CHOICES)
            .map(|id| Choice {
                id: id as i32,
                text: "\"".repeat(MAX_TEXT),
                enabled: true,
                action: None,
            })
            .collect();
        assert!(!menu_fits(&menu));
        menu.choices.truncate(1);
        let mut state = State {
            version: VERSION,
            pid: 1,
            session: 2,
            seq: 3,
            timestamp_ms: 1000,
            active: true,
            blocking: true,
            prompt: None,
            menu: Some(menu),
            subtitle: Some(Subtitle {
                text: "😀".repeat(MAX_TEXT),
            }),
            input: Input {
                seq: 3,
                buttons: 0,
                pressed: 0,
            },
        };
        bound_state(&mut state);
        assert!(serde_json::to_vec(&state).unwrap().len() <= MAX_HOST);
        assert_eq!(state.menu.as_ref().unwrap().token, 4);
    }
    fn command() -> Command {
        Command {
            version: 1,
            pid: 1,
            session: 2,
            seq: 3,
            timestamp_ms: 1000,
            token: 4,
            action: "select".into(),
            choice: Some(7),
            open: None,
            ready: None,
        }
    }
    #[test]
    fn commands_require_exact_identity_new_sequence_and_freshness() {
        let c = command();
        assert!(valid(&c, 1, 2, 2, 1000));
        assert!(valid(&c, 1, 2, 2, 1500));
        for (pid, session, last, now) in [
            (2, 2, 2, 1000),
            (1, 3, 2, 1000),
            (1, 2, 3, 1000),
            (1, 2, 2, 1501),
            (1, 2, 2, 899),
        ] {
            assert!(!valid(&c, pid, session, last, now));
        }
    }
    #[test]
    fn heartbeat_and_actions_have_distinct_context_contracts() {
        let mut c = command();
        c.action = "ui_state".into();
        c.token = 0;
        assert!(!valid(&c, 1, 2, 2, 1000));
        c.open = Some(true);
        c.ready = Some(true);
        assert!(valid(&c, 1, 2, 2, 1000));
        c.action = "select".into();
        assert!(!valid(&c, 1, 2, 2, 1000));
        c.action = "warp_anywhere".into();
        assert!(!valid(&c, 1, 2, 2, 1000));
    }
    #[test]
    fn untrusted_mailbox_fields_and_unbounded_sequences_fail_closed() {
        let mut c = command();
        c.seq = u64::MAX;
        assert!(!valid(&c, 1, 2, 2, 1000));
        assert!(serde_json::from_str::<Command>(r#"{"version":1,"pid":1,"session":2,"seq":3,"timestamp_ms":1000,"token":4,"action":"close","address":1234}"#).is_err());
    }
    #[test]
    fn display_text_preserves_localization_and_bounds_markup() {
        assert_eq!(
            text("<font>呪いの王</font>\0\nTalk"),
            Some("呪いの王\nTalk".into())
        );
        assert_eq!(text("<b>\0</b>"), None);
        assert_eq!(text(&"x".repeat(MAX_TEXT + 100)).unwrap().len(), MAX_TEXT);
    }
    #[test]
    fn choices_are_bound_to_current_context_and_enabled_actual_rows() {
        let menu = Menu {
            token: 4,
            kind: "npc".into(),
            title: "Talk".into(),
            choices: vec![
                Choice {
                    id: 7,
                    text: "Purchase".into(),
                    enabled: true,
                    action: None,
                },
                Choice {
                    id: 8,
                    text: "Unavailable".into(),
                    enabled: false,
                    action: None,
                },
            ],
        };
        let mut c = command();
        assert_eq!(menu_result(&menu, &c), Some(7));
        c.choice = Some(8);
        assert_eq!(menu_result(&menu, &c), None);
        c.choice = Some(99);
        assert_eq!(menu_result(&menu, &c), None);
        c.action = "close".into();
        assert_eq!(menu_result(&menu, &c), Some(-1));
        c.token = 5;
        assert_eq!(menu_result(&menu, &c), None);
    }
    #[test]
    fn minecraft_chest_action_never_selects_native_repository_menu() {
        let menu = Menu {
            token: 4,
            kind: "grace".into(),
            title: "Site of Grace".into(),
            choices: vec![Choice {
                id: 6,
                text: "Ender Chest".into(),
                enabled: true,
                action: Some("ender_chest".into()),
            }],
        };
        let mut c = command();
        c.choice = Some(6);
        assert_eq!(menu_result(&menu, &c), None);
        assert!(
            serde_json::to_string(&menu)
                .unwrap()
                .contains("\"action\":\"ender_chest\"")
        );
        c.action = "close".into();
        assert_eq!(menu_result(&menu, &c), Some(-1));
    }
}
