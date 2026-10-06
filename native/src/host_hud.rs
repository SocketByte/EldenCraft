//! Reversible suppression of the SDK's documented host status/equipment HUD.
//!
//! The caller supplies the already validated foreground/offline/gameplay gate.
//! Call `update(false)` or `suspend` on every gate-loss/disabled/error path.
//! This touches transient frontend values only, not saved display preferences.
//! `HideAll` is documented to hide HP/FP/stamina. Boss tag visibility is owned
//! only after the paired Minecraft HUD acknowledges drawing that exact source.
//! The typed native Pause permission is reserved while Minecraft owns the
//! composed frame; ESD submenu events and saved HUD preferences are untouched.
//!
//! Source: pinned fromsoftware-rs `cs/fe_man.rs`: CSFeManImp::hud_state,
//! CSFeManHudState::HideAll and FrontEndViewValues::enable_equip_hud.
//! No Drop implementation: restoration must happen on the game task thread.

use crate::boss_hud::SourceIdentity;
use eldenring::cs::{CSFeManHudState, CSFeManImp, CSMenuManImp, ChrMenuFlags, WorldChrMan};
use fromsoftware_shared::FromStatic;
use fromsoftware_shared::program::Program;
use pelite::pe64::PeObject;
use std::sync::OnceLock;

// Polarity is verified in the supported worldwide executable: normal native
// CSPlayerMenuCtrl reset (0x7c2390 -> 0x7c1fa0) sets permissions to9 (bits0+3).
// The getter returns bit3 directly, and the setter assigns only that bit.
// Reserving the permission therefore clears it, rather than setting a guessed
// "disable" flag. The SDK documents TAE54 DISABLE_START_INPUTS as its owner.
const PAUSE_FINGERPRINTS: [(usize, &[u8]); 3] = [
    (0x7c1fa0, &[0xc7, 0x41, 0x08, 0x09, 0, 0, 0, 0xc3]),
    (
        0x7c2620,
        &[0x8b, 0x41, 0x20, 0xc1, 0xe8, 0x03, 0x83, 0xe0, 0x01, 0xc3],
    ),
    (
        0x7c2ab0,
        &[
            0x83, 0x61, 0x20, 0xf7, 0x0f, 0xb6, 0xc2, 0x83, 0xe0, 0x01, 0xc1, 0xe0, 0x03, 0x09,
            0x41, 0x20, 0xc3,
        ],
    ),
];

/// Code-only diagnostic; false leaves the native permission under host control.
pub fn pause_permission_supported() -> bool {
    static VERIFIED: OnceLock<bool> = OnceLock::new();
    *VERIFIED.get_or_init(|| {
        let image = Program::current().image();
        PAUSE_FINGERPRINTS
            .iter()
            .all(|(at, bytes)| image.get(*at..*at + bytes.len()) == Some(*bytes))
    })
}

#[derive(Clone, Copy)]
struct OwnedValue<T> {
    original: T,
    written: T,
}

impl<T: Copy + PartialEq> OwnedValue<T> {
    fn restore(self, current: &mut T) {
        if *current == self.written {
            *current = self.original;
        }
    }
}

struct OwnedWrites {
    identity: usize,
    hud: OwnedValue<CSFeManHudState>,
    equipment: OwnedValue<bool>,
    bosses: Vec<OwnedBossVisibility>,
}

impl OwnedWrites {
    fn restore(&self, identity: usize, hud: &mut CSFeManHudState, equipment: &mut bool) {
        if self.identity == identity {
            self.hud.restore(hud);
            self.equipment.restore(equipment);
        }
    }
}

struct OwnedBossVisibility {
    identity: SourceIdentity,
    visible: OwnedValue<bool>,
}

struct OwnedPausePermission {
    menu: usize,
    flags: usize,
    permission: OwnedValue<bool>,
}

impl OwnedPausePermission {
    fn restore(&self, menu: usize, flags_identity: usize, flags: &mut ChrMenuFlags) {
        if self.menu != menu || self.flags != flags_identity {
            return;
        }
        let mut permission = flags.pause_menu_state();
        self.permission.restore(&mut permission);
        // Preserve every other flag, including new native animation state.
        flags.set_pause_menu_state(permission);
    }
}

impl OwnedBossVisibility {
    fn restore(&self, current: Option<&SourceIdentity>, visible: &mut bool) {
        if current == Some(&self.identity) {
            self.visible.restore(visible);
        }
    }
}

/// Resolve the slot anew rather than retain pointers to any frontend/source.
fn current_source(
    frontend: &CSFeManImp,
    world: &WorldChrMan,
    expected: &SourceIdentity,
) -> Option<SourceIdentity> {
    let display = frontend.boss_health_displays.get(expected.display_slot)?;
    let tag = frontend
        .frontend_values
        .boss_list_tag_data
        .get(expected.gauge_slot)?;
    if display.fmg_id <= 0
        || display.field_ins_handle.is_empty()
        || tag.field_ins_handle != display.field_ins_handle
    {
        return None;
    }
    let source = world.chr_ins_by_handle(&display.field_ins_handle)?;
    if source.field_ins_handle != display.field_ins_handle {
        return None;
    }
    Some(SourceIdentity {
        frontend: frontend as *const CSFeManImp as usize,
        display_slot: expected.display_slot,
        gauge_slot: expected.gauge_slot,
        fmg_id: display.fmg_id,
        handle: display.field_ins_handle,
        source: source as *const _ as usize,
    })
}

#[derive(Default)]
pub struct Controller {
    writes: Option<OwnedWrites>,
    pause: Option<OwnedPausePermission>,
}

impl Controller {
    pub fn new() -> Self {
        Self::default()
    }
    #[cfg(test)]
    fn is_active(&self) -> bool {
        self.writes.is_some() || self.pause.is_some()
    }

    /// Apply transient HUD hiding while the caller's passthrough gate is open.
    /// Returns true when the documented fields have been written. Host frontend
    /// updates may overwrite these values later; visual verification determines
    /// the appropriate task phase. Each call preserves the newest host values.
    ///
    /// # Safety
    /// Game task thread only, after the exact executable guard. `enabled` must
    /// only be true for verified offline gameplay with the replacement HUD
    /// available. No references into CSFeManImp may be held during this call.
    pub unsafe fn update(&mut self, enabled: bool) -> Result<bool, &'static str> {
        unsafe { self.suspend()? };
        if !enabled {
            return Ok(false);
        }
        let drawn = crate::campaign_runtime::boss_hud_ids();
        let sources = if drawn.is_empty() {
            Vec::new()
        } else {
            unsafe { crate::boss_hud::sample() }.sources
        };
        let frontend = unsafe { CSFeManImp::instance_mut() }
            .map_err(|_| "host HUD waiting for frontend manager")?;
        let mut writes = OwnedWrites {
            identity: frontend as *mut CSFeManImp as usize,
            hud: OwnedValue {
                original: frontend.hud_state,
                written: CSFeManHudState::HideAll,
            },
            equipment: OwnedValue {
                original: frontend.frontend_values.enable_equip_hud,
                written: false,
            },
            bosses: Vec::new(),
        };
        if let Ok(world) = unsafe { WorldChrMan::instance() } {
            for source in sources {
                if !drawn.contains(&source.id)
                    || writes
                        .bosses
                        .iter()
                        .any(|boss| boss.identity.gauge_slot == source.identity.gauge_slot)
                    || current_source(frontend, world, &source.identity).as_ref()
                        != Some(&source.identity)
                {
                    continue;
                }
                let tag =
                    &mut frontend.frontend_values.boss_list_tag_data[source.identity.gauge_slot];
                writes.bosses.push(OwnedBossVisibility {
                    identity: source.identity,
                    visible: OwnedValue {
                        original: tag.is_visible,
                        written: false,
                    },
                });
                tag.is_visible = false;
            }
        }
        frontend.hud_state = writes.hud.written;
        frontend.frontend_values.enable_equip_hud = writes.equipment.written;
        self.writes = Some(writes);
        if pause_permission_supported()
            && let Ok(menu) = unsafe { CSMenuManImp::instance_mut() }
        {
            let menu_identity = menu as *mut CSMenuManImp as usize;
            let flags = &mut menu.player_menu_ctrl.chr_menu_flags.flags;
            self.pause = Some(OwnedPausePermission {
                menu: menu_identity,
                flags: flags as *mut ChrMenuFlags as usize,
                permission: OwnedValue {
                    original: flags.pause_menu_state(),
                    written: false,
                },
            });
            flags.set_pause_menu_state(false);
        }
        Ok(true)
    }

    /// Reacquire the current singleton and restore each field only if it still
    /// equals our last write and the object has the same identity. Replaced or
    /// unavailable objects release ownership without touching an old pointer.
    ///
    /// # Safety
    /// Same game-thread/version/reference contract as `update`. This may be
    /// called after gameplay/focus gates close so the original HUD can return.
    pub unsafe fn suspend(&mut self) -> Result<(), &'static str> {
        // Pause ownership is independent of frontend availability, so a HUD
        // singleton replacement cannot leave the host's permission reserved.
        if let Some(pause) = self.pause.take()
            && let Ok(menu) = unsafe { CSMenuManImp::instance_mut() }
        {
            let identity = menu as *mut CSMenuManImp as usize;
            let flags = &mut menu.player_menu_ctrl.chr_menu_flags.flags;
            pause.restore(identity, flags as *mut ChrMenuFlags as usize, flags);
        }
        let Some(writes) = self.writes.take() else {
            return Ok(());
        };
        let frontend = unsafe { CSFeManImp::instance_mut() }
            .map_err(|_| "host HUD released: frontend manager unavailable")?;
        let identity = frontend as *mut CSFeManImp as usize;
        writes.restore(
            identity,
            &mut frontend.hud_state,
            &mut frontend.frontend_values.enable_equip_hud,
        );
        if identity == writes.identity
            && let Ok(world) = unsafe { WorldChrMan::instance() }
        {
            for boss in writes.bosses {
                let current = current_source(frontend, world, &boss.identity);
                if let Some(tag) = frontend
                    .frontend_values
                    .boss_list_tag_data
                    .get_mut(boss.identity.gauge_slot)
                {
                    boss.restore(current.as_ref(), &mut tag.is_visible);
                }
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn writes(identity: usize) -> OwnedWrites {
        OwnedWrites {
            identity,
            hud: OwnedValue {
                original: CSFeManHudState::Default,
                written: CSFeManHudState::HideAll,
            },
            equipment: OwnedValue {
                original: true,
                written: false,
            },
            bosses: Vec::new(),
        }
    }

    #[test]
    fn restores_unchanged_owned_fields() {
        let mut hud = CSFeManHudState::HideAll;
        let mut equipment = false;
        writes(1).restore(1, &mut hud, &mut equipment);
        assert_eq!(hud, CSFeManHudState::Default);
        assert!(equipment);
    }

    #[test]
    fn preserves_new_host_menu_state_while_restoring_equipment() {
        let mut hud = CSFeManHudState::PopupMenu;
        let mut equipment = false;
        writes(1).restore(1, &mut hud, &mut equipment);
        assert_eq!(hud, CSFeManHudState::PopupMenu);
        assert!(equipment);
    }

    #[test]
    fn replacement_singleton_is_never_restored() {
        let mut hud = CSFeManHudState::HideAll;
        let mut equipment = false;
        writes(1).restore(2, &mut hud, &mut equipment);
        assert_eq!(hud, CSFeManHudState::HideAll);
        assert!(!equipment);
    }

    fn boss_write(original: bool) -> OwnedBossVisibility {
        use eldenring::cs::{BlockId, FieldInsHandle, FieldInsSelector};
        OwnedBossVisibility {
            identity: SourceIdentity {
                frontend: 1,
                display_slot: 0,
                gauge_slot: 1,
                fmg_id: 2,
                handle: FieldInsHandle {
                    block_id: BlockId::none(),
                    selector: FieldInsSelector(0x10000001),
                },
                source: 3,
            },
            visible: OwnedValue {
                original,
                written: false,
            },
        }
    }

    #[test]
    fn boss_visibility_returns_only_to_its_exact_native_source() {
        let write = boss_write(true);
        let mut visible = false;
        write.restore(Some(&write.identity), &mut visible);
        assert!(visible);

        let mut changed = Vec::new();
        let mut identity = write.identity.clone();
        identity.frontend += 1;
        changed.push(identity);
        let mut identity = write.identity.clone();
        identity.source += 1;
        changed.push(identity);
        let mut identity = write.identity.clone();
        identity.handle.selector.0 += 1;
        changed.push(identity);
        let mut identity = write.identity.clone();
        identity.fmg_id += 1;
        changed.push(identity);
        let mut identity = write.identity.clone();
        identity.display_slot += 1;
        changed.push(identity);
        let mut identity = write.identity.clone();
        identity.gauge_slot += 1;
        changed.push(identity);
        for identity in changed {
            visible = false;
            write.restore(Some(&identity), &mut visible);
            assert!(!visible, "new slot/source must not inherit old visibility");
        }
        write.restore(None, &mut visible);
        assert!(!visible);
    }

    #[test]
    fn boss_release_preserves_new_host_visibility_and_original_hidden_state() {
        let write = boss_write(false);
        let mut visible = true;
        write.restore(Some(&write.identity), &mut visible);
        assert!(visible, "host changes after our write retain ownership");
        visible = false;
        write.restore(Some(&write.identity), &mut visible);
        assert!(!visible, "an originally hidden tag must remain hidden");
    }

    fn pause_write(original: bool) -> OwnedPausePermission {
        OwnedPausePermission {
            menu: 1,
            flags: 2,
            permission: OwnedValue {
                original,
                written: false,
            },
        }
    }

    #[test]
    fn pause_reservation_preserves_all_other_flags_during_write_and_restore() {
        for original in [false, true] {
            let mut flags = ChrMenuFlags(0xa5a5_fff7);
            flags.set_pause_menu_state(original);
            let before = flags.0;
            let write = pause_write(original);
            flags.set_pause_menu_state(false);
            assert!(!flags.pause_menu_state());
            assert_eq!(flags.0 & !8, before & !8);
            // A new animation may update unrelated flags while we own bit3.
            flags.0 ^= 1 << 18;
            write.restore(1, 2, &mut flags);
            assert_eq!(flags.pause_menu_state(), original);
            assert_eq!(flags.0 & !8, (before ^ (1 << 18)) & !8);
        }
    }

    #[test]
    fn pause_release_preserves_host_permission_and_replacement_identities() {
        let write = pause_write(false);
        let mut flags = ChrMenuFlags(0x5a5a_0008);
        write.restore(1, 2, &mut flags);
        assert!(flags.pause_menu_state(), "new host permission must survive");
        let write = pause_write(true);
        for (menu, identity) in [(2, 2), (1, 3)] {
            flags.set_pause_menu_state(false);
            let before = flags.0;
            write.restore(menu, identity, &mut flags);
            assert_eq!(
                flags.0, before,
                "replacement object must not inherit old flags"
            );
        }
    }

    #[test]
    fn inactive_release_does_not_access_game_objects() {
        let mut controller = Controller::new();
        assert_eq!(unsafe { controller.update(false) }, Ok(false));
        assert_eq!(unsafe { controller.suspend() }, Ok(()));
        assert!(!controller.is_active());
    }
}
