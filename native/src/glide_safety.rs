//! Elden Ring owns fall consequences, and it measures a fall from where the
//! character left the ground. A Minecraft elytra glide can descend tens of
//! metres from that point, so landing lower than the takeoff killed the player
//! even after a gentle glide. While the genuine guest glide is active, and until
//! shortly after the following landing, apply the game's own fall-immunity
//! SpEffect states and keep the native fall timer at zero. Normal falls that do
//! not involve a glide keep vanilla Elden Ring fall damage.
pub const DISABLE_FALL_DAMAGE: u16 = 47;
pub const DISABLE_FALL_DEATH: u16 = 266;
/// Remain protected this long after ground contact following a glide.
pub const GROUNDED_GRACE_MS: u64 = 750;
/// A glide that ends high in the air still lands under protection, but never
/// grants indefinite immunity after the glide itself is over.
pub const POST_GLIDE_MAX_MS: u64 = 8000;
/// Choose rows whose other bytes match the overwhelmingly common defaults.
pub const MAX_SCORE: usize = 48;

/// Pure window policy: active from the first gliding sample until the player has
/// been grounded for the grace period (or the post-glide bound expires).
#[derive(Default, Debug, Clone, Copy, PartialEq, Eq)]
pub struct Window {
    active: bool,
    glide_ended: Option<u64>,
    grounded_since: Option<u64>,
}
impl Window {
    pub fn update(&mut self, gliding: bool, grounded: bool, now: u64) -> bool {
        if gliding {
            *self = Self {
                active: true,
                glide_ended: None,
                grounded_since: None,
            };
            return true;
        }
        if !self.active {
            return false;
        }
        let ended = *self.glide_ended.get_or_insert(now);
        if grounded {
            let since = *self.grounded_since.get_or_insert(now);
            if now.saturating_sub(since) >= GROUNDED_GRACE_MS {
                self.active = false;
            }
        } else {
            self.grounded_since = None;
        }
        if now.saturating_sub(ended) >= POST_GLIDE_MAX_MS {
            self.active = false;
        }
        if !self.active {
            *self = Self::default();
        }
        self.active
    }
    pub fn active(&self) -> bool {
        self.active
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Choice {
    pub id: u32,
    pub state: u16,
    pub endurance: f32,
    pub score: usize,
}
impl Choice {
    /// Reapply well inside a finite duration; indefinite rows need no refresh.
    pub fn refresh_ms(&self) -> Option<u64> {
        (self.endurance > 0.0).then(|| ((self.endurance * 500.0) as u64).clamp(50, 500))
    }
}
pub struct Row<'a> {
    pub id: u32,
    pub state: u16,
    pub endurance: f32,
    pub bytes: &'a [u8],
}

/// Score each candidate by how many bytes differ from the per-position most
/// common value across the entire param table (the shipped defaults). Rows that
/// also change damage, stats, speed or visuals differ in many more bytes.
pub fn choose(rows: &[Row], state: u16) -> Option<Choice> {
    let width = rows.first()?.bytes.len();
    if width == 0 || rows.iter().any(|r| r.bytes.len() != width) {
        return None;
    }
    let mut counts = vec![[0u32; 256]; width];
    for row in rows {
        for (i, b) in row.bytes.iter().enumerate() {
            counts[i][*b as usize] += 1;
        }
    }
    let baseline: Vec<u8> = counts
        .iter()
        .map(|c| {
            (0..256)
                .max_by_key(|&v| (c[v], std::cmp::Reverse(v)))
                .unwrap() as u8
        })
        .collect();
    rows.iter()
        .filter(|r| r.state == state && r.endurance.is_finite() && r.endurance != 0.0)
        .map(|r| {
            let score = r
                .bytes
                .iter()
                .zip(&baseline)
                .filter(|(a, b)| a != b)
                .count();
            // Prefer self-expiring rows; an indefinite one only lives while refreshed here.
            (
                r.endurance < 0.0,
                score,
                r.id,
                Choice {
                    id: r.id,
                    state,
                    endurance: r.endurance,
                    score,
                },
            )
        })
        .filter(|(_, score, _, _)| *score <= MAX_SCORE)
        .min_by_key(|(indefinite, score, id, _)| (*indefinite, *score, *id))
        .map(|(_, _, _, c)| c)
}

#[cfg(windows)]
mod live {
    use super::*;
    use eldenring::{
        cs::{ChrInsExt, PlayerIns, SoloParamRepository},
        param::SP_EFFECT_PARAM_ST,
    };
    use fromsoftware_shared::FromStatic;

    pub struct Driver {
        window: Window,
        choices: Option<Result<Vec<Choice>, &'static str>>,
        applied: Vec<(Choice, u64)>,
        pub events: Vec<String>,
    }
    impl Default for Driver {
        fn default() -> Self {
            Self::new()
        }
    }
    impl Driver {
        pub fn new() -> Self {
            Self {
                window: Window::default(),
                choices: None,
                applied: Vec::new(),
                events: Vec::new(),
            }
        }
        unsafe fn resolve() -> Result<Vec<Choice>, &'static str> {
            let repo = unsafe { SoloParamRepository::instance() }
                .map_err(|_| "param repository unavailable")?;
            let file = repo
                .params()
                .find(|p| p.struct_name() == "SP_EFFECT_PARAM_ST")
                .ok_or("SpEffectParam unavailable")?;
            let width = std::mem::size_of::<SP_EFFECT_PARAM_ST>();
            let rows: Vec<Row> = unsafe { file.data.rows::<SP_EFFECT_PARAM_ST>() }
                .map(|(id, row)| Row {
                    id,
                    state: row.state_info(),
                    endurance: row.effect_endurance(),
                    bytes: unsafe {
                        std::slice::from_raw_parts(
                            (row as *const SP_EFFECT_PARAM_ST).cast::<u8>(),
                            width,
                        )
                    },
                })
                .collect();
            let chosen: Vec<Choice> = [DISABLE_FALL_DAMAGE, DISABLE_FALL_DEATH]
                .into_iter()
                .filter_map(|s| choose(&rows, s))
                .collect();
            if chosen.is_empty() {
                Err("no neutral fall-immunity SpEffect row")
            } else {
                Ok(chosen)
            }
        }
        /// # Safety
        /// PostPhysics task only, after the passthrough gates passed.
        pub unsafe fn tick(&mut self, gliding: bool, grounded: bool, now: u64) {
            let was = self.window.active();
            let active = self.window.update(gliding, grounded, now);
            if active && !was {
                self.events.push(format!(
                    "Glide fall safety engaged (gliding={gliding}, grounded={grounded})."
                ));
            }
            if !active {
                if was {
                    unsafe { self.release(now) };
                    self.events
                        .push("Glide fall safety released after landing.".into());
                }
                return;
            }
            let Ok(player) = (unsafe { PlayerIns::local_player_mut() }) else {
                return;
            };
            // The fall module timer drives the native long-fall/fall-death path.
            if gliding {
                player.chr_ins.modules.fall.fall_timer = 0.0;
            }
            if self.choices.is_none() {
                let resolved = unsafe { Self::resolve() };
                self.events.push(match &resolved {
                    Ok(c) => format!(
                        "Glide fall safety SpEffects: {}",
                        c.iter()
                            .map(|c| format!(
                                "id={} state={} endurance={} score={}",
                                c.id, c.state, c.endurance, c.score
                            ))
                            .collect::<Vec<_>>()
                            .join("; ")
                    ),
                    Err(e) => format!(
                        "Glide fall safety SpEffect unavailable: {e}; fall timer reset only."
                    ),
                });
                self.choices = Some(resolved);
            }
            let Some(Ok(choices)) = self.choices.as_ref() else {
                return;
            };
            for choice in choices {
                let due = match self.applied.iter().find(|(c, _)| c.id == choice.id) {
                    None => true,
                    Some((c, at)) => c
                        .refresh_ms()
                        .is_some_and(|ms| now.saturating_sub(*at) >= ms),
                };
                if due {
                    player.apply_speffect(choice.id as i32, true);
                    self.applied.retain(|(c, _)| c.id != choice.id);
                    self.applied.push((*choice, now));
                }
            }
        }
        unsafe fn release(&mut self, _now: u64) {
            if self.applied.is_empty() {
                return;
            }
            if let Ok(player) = unsafe { PlayerIns::local_player_mut() } {
                for (choice, _) in self.applied.drain(..) {
                    player.remove_speffect(choice.id as i32);
                }
            } else {
                self.applied.clear();
            }
        }
        /// Gate loss keeps an in-progress landing protected; only an indefinite
        /// row is removed, because nothing would refresh or expire it later.
        /// # Safety
        /// Game task only.
        pub unsafe fn suspend(&mut self) {
            if self.applied.iter().any(|(c, _)| c.endurance < 0.0) {
                unsafe { self.release(0) };
                self.window = Window::default();
            }
        }
    }
}
#[cfg(windows)]
pub use live::Driver;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn window_covers_glide_landing_and_bounded_post_glide_fall() {
        let mut w = Window::default();
        assert!(
            !w.update(false, false, 0),
            "ordinary falls keep vanilla fall damage"
        );
        assert!(w.update(true, false, 100));
        assert!(
            w.update(false, false, 200),
            "glide ended mid-air: still protected"
        );
        assert!(w.update(false, true, 300));
        assert!(w.update(false, false, 500), "bounce resets grounded time");
        assert!(w.update(false, true, 600));
        assert!(w.update(false, true, 600 + GROUNDED_GRACE_MS - 1));
        assert!(!w.update(false, true, 600 + GROUNDED_GRACE_MS));
        assert!(!w.update(false, false, 10_000));
        assert!(w.update(true, false, 20_000));
        assert!(w.update(false, false, 20_001));
        assert!(
            !w.update(false, false, 20_001 + POST_GLIDE_MAX_MS),
            "post-glide immunity is bounded"
        );
    }
    fn row(id: u32, state: u16, endurance: f32, bytes: &[u8]) -> Row<'_> {
        Row {
            id,
            state,
            endurance,
            bytes,
        }
    }
    #[test]
    fn choose_prefers_neutral_finite_rows_and_rejects_loaded_effects() {
        let base = [0u8; 80];
        let mut plain = base;
        plain[0] = 47;
        let mut loaded = base;
        loaded[0] = 47;
        for b in loaded.iter_mut().skip(10) {
            *b = 9;
        }
        let defaults = [
            row(1, 0, 0.0, &base),
            row(2, 0, 0.0, &base),
            row(3, 0, 0.0, &base),
            row(4, 0, 0.0, &base),
            row(5, 0, 0.0, &base),
        ];
        let mut rows = defaults
            .iter()
            .map(|r| row(r.id, r.state, r.endurance, r.bytes))
            .collect::<Vec<_>>();
        rows.extend([
            row(10, 47, -1.0, &plain),
            row(11, 47, 2.0, &plain),
            row(12, 47, 2.0, &loaded),
            row(13, 47, 0.0, &plain),
        ]);
        let c = choose(&rows, 47).unwrap();
        assert_eq!(
            c.id, 11,
            "finite neutral row beats indefinite and loaded rows"
        );
        assert_eq!(c.score, 1);
        assert_eq!(c.refresh_ms(), Some(500));
        assert!(
            choose(&rows, 266).is_none(),
            "absent state is never substituted"
        );
        let mut only_loaded = defaults
            .iter()
            .map(|r| row(r.id, r.state, r.endurance, r.bytes))
            .collect::<Vec<_>>();
        only_loaded.push(row(12, 47, 2.0, &loaded));
        assert!(
            choose(&only_loaded, 47).is_none(),
            "a row that changes many other fields is refused"
        );
        assert_eq!(
            Choice {
                id: 1,
                state: 47,
                endurance: 0.05,
                score: 0
            }
            .refresh_ms(),
            Some(50)
        );
        assert_eq!(
            Choice {
                id: 1,
                state: 47,
                endurance: -1.0,
                score: 0
            }
            .refresh_ms(),
            None
        );
    }
}
