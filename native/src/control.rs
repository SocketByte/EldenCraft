//! Optional local test controls for the actual Minecraft passthrough.
use serde::Deserialize;

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct Command {
    pub seq: u64,
    pub action: Action,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Action {
    ToggleBuild,
    Place,
    Break,
    Material1,
    Material2,
    Material3,
    Material4,
    Material5,
    Material6,
    Material7,
    Material8,
    Material9,
    ToggleFirstPerson,
    ToggleInventory,
    ToggleHitboxes,
}

impl Command {
    pub fn parse(text: &str) -> Result<Self, String> {
        if text.len() > 1024 {
            return Err("command exceeds 1024 bytes".into());
        }
        let command: Self = serde_json::from_str(text).map_err(|error| error.to_string())?;
        if command.seq == 0 {
            return Err("sequence must be positive".into());
        }
        Ok(command)
    }
    pub fn toggle_index(self) -> Option<usize> {
        match self.action {
            Action::ToggleBuild => Some(0),
            Action::ToggleFirstPerson => Some(1),
            Action::ToggleHitboxes => Some(2),
            _ => None,
        }
    }
    pub fn input_bits(self) -> Option<u32> {
        let bit = match self.action {
            Action::Break => 0,
            Action::Place => 1,
            Action::ToggleInventory => 2,
            Action::Material1 => 4,
            Action::Material2 => 5,
            Action::Material3 => 6,
            Action::Material4 => 7,
            Action::Material5 => 8,
            Action::Material6 => 9,
            Action::Material7 => 10,
            Action::Material8 => 11,
            Action::Material9 => 12,
            _ => return None,
        };
        Some(1 << bit)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn command(action: &str) -> Command {
        Command::parse(&format!(r#"{{"seq":10,"action":"{action}"}}"#)).unwrap()
    }
    #[test]
    fn supported_toggles_follow_the_foreground_function_keys() {
        for (action, index) in [
            ("toggle_build", 0),
            ("toggle_first_person", 1),
            ("toggle_hitboxes", 2),
        ] {
            assert_eq!(command(action).toggle_index(), Some(index));
            assert_eq!(command(action).input_bits(), None);
        }
    }
    #[test]
    fn minecraft_pulses_preserve_the_existing_echs_button_layout() {
        for (action, bit) in [("break", 0), ("place", 1), ("toggle_inventory", 2)] {
            assert_eq!(command(action).input_bits(), Some(1 << bit));
            assert_eq!(command(action).toggle_index(), None);
        }
        for slot in 1..=9 {
            assert_eq!(
                command(&format!("material{slot}")).input_bits(),
                Some(1 << (slot + 3))
            );
        }
    }
    #[test]
    fn retired_actions_arbitrary_input_unknown_fields_and_bad_sequences_are_rejected() {
        for text in [
            r#"{"seq":1,"action":"press_key"}"#,
            r#"{"seq":0,"action":"place"}"#,
            r#"{"seq":-1,"action":"place"}"#,
            r#"{"seq":1,"action":"place","key":"E"}"#,
            r#"{"seq":1,"action":"place""#,
        ] {
            assert!(Command::parse(text).is_err());
        }
        for action in [
            "starter",
            "undo",
            "craft",
            "next_recipe",
            "swap_inventory",
            "cursor_left",
            "cursor_right",
            "cursor_up",
            "cursor_down",
            "toggle_minecraft",
            "mine_start",
            "mine_stop",
        ] {
            assert!(Command::parse(&format!(r#"{{"seq":1,"action":"{action}"}}"#)).is_err());
        }
        assert!(Command::parse(&" ".repeat(1025)).is_err());
    }
}
