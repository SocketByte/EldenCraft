//! `eldencraft_native.dll`: persistent loader for the hot-reloadable core.
//!
//! me3 loads this DLL; it never changes during a session. It loads a private
//! copy of `eldencraft_core.dll` (so the build output can be overwritten while
//! the game runs), owns the three game tasks and forwards the compositor's
//! exports to the current core. With `ELDENCRAFT_HOT_RELOAD=1`, a changed
//! `eldencraft_core.dll` next to this DLL is reloaded in place: on the game
//! thread the old core restores the camera, input, HUD and every code patch and
//! drops its state, then a fresh copy starts. Old copies stay loaded (never
//! freed), so no thread can return into unmapped code.
//!
//! Like the core, this module stays passive outside eldenring.exe.
#[cfg(windows)]
mod windows;
#[cfg(windows)]
pub use windows::*;

/// Pure reload policy: reload once the core file changed and then stayed
/// unchanged for one more poll (the build or copy finished writing it).
#[derive(Default, Debug)]
pub struct Watch {
    loaded: Option<(u64, u64)>,
    candidate: Option<(u64, u64)>,
}
impl Watch {
    /// `stamp`: (modified time, size) of the core file. Returns true to reload.
    pub fn observe(&mut self, stamp: Option<(u64, u64)>) -> bool {
        let Some(stamp) = stamp else {
            self.candidate = None;
            return false;
        };
        if self.loaded.is_none() {
            self.loaded = Some(stamp);
            return false;
        }
        if self.loaded == Some(stamp) {
            self.candidate = None;
            return false;
        }
        if self.candidate == Some(stamp) {
            self.loaded = Some(stamp);
            self.candidate = None;
            return true;
        }
        self.candidate = Some(stamp);
        false
    }
}

#[cfg(test)]
mod tests {
    use super::Watch;
    #[test]
    fn reloads_once_after_a_stable_change() {
        let mut watch = Watch::default();
        assert!(
            !watch.observe(Some((1, 10))),
            "first observation is the loaded core"
        );
        assert!(!watch.observe(Some((1, 10))));
        assert!(!watch.observe(Some((2, 5))), "still being written");
        assert!(!watch.observe(Some((3, 12))), "still being written");
        assert!(watch.observe(Some((3, 12))), "stable for one poll");
        assert!(!watch.observe(Some((3, 12))), "only once");
        assert!(!watch.observe(None), "missing file never reloads");
        assert!(!watch.observe(Some((3, 12))));
    }
}
