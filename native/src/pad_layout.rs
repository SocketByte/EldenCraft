//! Integer views of the pinned SDK's C++ pad maps.
//!
//! `UserInputKey` is a non-exhaustive reverse-engineered Rust enum in practice:
//! the live table contains additional integer keys (e.g. 268). Reading those as
//! enum values is invalid Rust. DLMap is a transparent MSVC Map/RbTree wrapper;
//! its Pair and node are repr(C), and the key is repr(i32). Use integer keys and
//! raw u32 polling kinds without ever constructing unknown enum discriminants.
use eldenring::{
    DLMap,
    cs::{CSInGamePad, UserInputKey},
    fd4::InputTypeGroup,
};

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RawInputTypeGroup {
    pub mapped_input_list: [i32; 4],
    pub input_type_list: [u32; 4],
}
impl RawInputTypeGroup {
    pub fn iter(&self) -> impl Iterator<Item = (usize, i32, u32)> + '_ {
        self.mapped_input_list
            .iter()
            .copied()
            .zip(self.input_type_list.iter().copied())
            .enumerate()
            .filter_map(|(slot, (code, kind))| (code != -1).then_some((slot, code, kind)))
    }
}
pub type GroupMap = DLMap<i32, RawInputTypeGroup>;
pub type ForcedMap = DLMap<i32, bool>;
const _: () = {
    assert!(std::mem::size_of::<UserInputKey>() == std::mem::size_of::<i32>());
    assert!(std::mem::align_of::<UserInputKey>() == std::mem::align_of::<i32>());
    assert!(std::mem::size_of::<RawInputTypeGroup>() == std::mem::size_of::<InputTypeGroup>());
    assert!(std::mem::align_of::<RawInputTypeGroup>() == std::mem::align_of::<InputTypeGroup>());
    assert!(
        std::mem::offset_of!(RawInputTypeGroup, input_type_list)
            == std::mem::offset_of!(InputTypeGroup, input_type_list)
    );
    assert!(
        std::mem::size_of::<GroupMap>()
            == std::mem::size_of::<DLMap<UserInputKey, InputTypeGroup>>()
    );
    assert!(
        std::mem::align_of::<GroupMap>()
            == std::mem::align_of::<DLMap<UserInputKey, InputTypeGroup>>()
    );
    assert!(std::mem::size_of::<ForcedMap>() == std::mem::size_of::<DLMap<UserInputKey, bool>>());
};

/// # Safety
/// Exact-build game thread, live pad, no concurrent map mutation.
pub unsafe fn groups(pad: &CSInGamePad) -> &GroupMap {
    unsafe { &*pad.input_type_group.as_ptr().cast::<GroupMap>() }
}
/// # Safety
/// Exact-build game thread, live pad, no concurrent map mutation.
pub unsafe fn forced(pad: &CSInGamePad, key: UserInputKey) -> bool {
    unsafe { forced_key(pad, key as i32) }
}
/// # Safety
/// Same contract as `forced`; the key is an arbitrary C++ integer, not an enum.
pub unsafe fn forced_key(pad: &CSInGamePad, key: i32) -> bool {
    let map = unsafe { &*std::ptr::addr_of!(pad.unused_input_map).cast::<ForcedMap>() };
    map.find(&key).copied().unwrap_or(false)
}

pub fn key_name(key: i32) -> &'static str {
    match key {
        4 => "MouseMovementX",
        5 => "MouseMovementY",
        6 => "MovementControl",
        7 => "Attack",
        8 => "StrongAttack",
        9 => "Guard",
        10 => "Skill",
        11 => "EventAction",
        12 => "Backstep",
        13 => "BackstepTapped",
        14 => "Jump",
        15 => "UseItem",
        16 => "SwitchSpell",
        17 => "SwitchRightHandArmament",
        18 => "SwitchLeftHandArmament",
        19 => "SwitchItem",
        20 => "ResetCamera",
        21 => "Crouch",
        24 => "SwitchSpell2",
        25 => "SwitchItem2",
        26 => "ResetCameraTapped",
        27 => "EventActionPouch",
        300 => "Map",
        417 => "MoveForwards",
        418 => "MoveBackwards",
        419 => "MoveLeft",
        420 => "MoveRight",
        424 => "MoveCameraUp",
        425 => "MoveCameraDown",
        426 => "MoveCameraLeft",
        427 => "MoveCameraRight",
        _ => "UnknownKey",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unmodeled_live_keys_are_never_materialized_as_enums() {
        assert_eq!(key_name(268), "UnknownKey");
        assert_eq!(key_name(300), "Map");
        let raw = RawInputTypeGroup {
            mapped_input_list: [827, -1, -1, -1],
            input_type_list: [0, 999, 999, 999],
        };
        assert_eq!(raw.iter().collect::<Vec<_>>(), vec![(0, 827, 0)]);
    }
}
