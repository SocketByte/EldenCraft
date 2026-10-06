//! Read-only Elden Ring weather sampling for Minecraft's weather mirror.
//!
//! The pinned SDK has no weather type, so this reads the `WorldAreaWeather`
//! singleton the same way the game's own consumer at RVA 0x6a32e3 does: the
//! active weather is the i16 at +0x2a, replaced by the i16 at +0x1c while the
//! byte at +0xf5 is set. Both reads are fingerprinted, including the static's
//! RIP-relative displacement, so another executable reads nothing.
//!
//! Weather IDs are grouped in tens (0/1 sunny, clear sky; 10/11 weak cloud,
//! cloud; 20/21 rain, heavy rain; 30/31 storm, storm for battle; 40/41 snow,
//! heavy snow; 50/51/52 fog, heavy fog, heavy fog rain; 60 sandstorm), in the
//! order of CutsceneGparamWeatherParam's destinations and the game's own
//! weather switch at 0xa8e0f3. 81..88 are newer weathers without names here.

/// What Minecraft can show: its weather is only clear, rain or thunder.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Sky {
    Clear = 0,
    Rain = 1,
    Thunder = 2,
}

/// Map a native weather ID; None for an ID the game itself never switches on.
pub fn classify(weather: i16) -> Option<Sky> {
    match weather {
        20 | 21 | 40 | 41 | 52 => Some(Sky::Rain),
        30 | 31 => Some(Sky::Thunder),
        // Unnamed newer weathers stay clear rather than guessing precipitation.
        0 | 1 | 10 | 11 | 50 | 51 | 60 | 81..=88 => Some(Sky::Clear),
        _ => None,
    }
}

#[cfg(windows)]
const WEATHER_STATIC: usize = 0x3d6d3f0;
#[cfg(windows)]
const FINGERPRINTS: [(usize, &[u8]); 2] = [
    // mov rax,[WorldAreaWeather]; movzx eax,word ptr [rax+2Ah]; mov [rdi+238h],ax
    (
        0x6a32e3,
        &[
            0x48, 0x8b, 0x05, 0x06, 0xa1, 0x6c, 0x03, 0x0f, 0xb7, 0x40, 0x2a, 0x66, 0x89, 0x87,
            0x38, 0x02, 0x00, 0x00,
        ],
    ),
    // mov rax,[WorldAreaWeather]; cmp byte ptr [rax+0F5h],0; je; movzx eax,word ptr [rax+1Ch]
    (
        0x6a3328,
        &[
            0x48, 0x8b, 0x05, 0xc1, 0xa0, 0x6c, 0x03, 0x80, 0xb8, 0xf5, 0x00, 0x00, 0x00, 0x00,
            0x74, 0x0b, 0x0f, 0xb7, 0x40, 0x1c, 0x66, 0x89, 0x87, 0x38, 0x02, 0x00, 0x00,
        ],
    ),
];

/// Code-only check; false means weather is never read.
#[cfg(windows)]
pub fn supported() -> bool {
    use fromsoftware_shared::program::Program;
    use pelite::pe64::PeObject;
    static VERIFIED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *VERIFIED.get_or_init(|| {
        let image = Program::current().image();
        FINGERPRINTS
            .iter()
            .all(|(at, bytes)| image.get(*at..*at + bytes.len()) == Some(*bytes))
    })
}

/// Sample the native weather ID.
///
/// # Safety
/// Game task only, after the supported-executable, offline and live-world
/// gates. Returns None while the singleton is not allocated.
#[cfg(windows)]
pub unsafe fn read() -> Option<i16> {
    use fromsoftware_shared::program::Program;
    use pelite::pe64::PeObject;
    if !supported() {
        return None;
    }
    let image = Program::current().image();
    let slot = unsafe { image.as_ptr().add(WEATHER_STATIC) } as *const *const u8;
    let weather = unsafe { slot.read() };
    if weather.is_null() {
        return None;
    }
    unsafe {
        Some(if weather.add(0xf5).read() != 0 {
            weather.add(0x1c).cast::<i16>().read_unaligned()
        } else {
            weather.add(0x2a).cast::<i16>().read_unaligned()
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_precipitating_weather_reaches_minecraft() {
        for id in [0, 1, 10, 11, 50, 51, 60, 81, 88] {
            assert_eq!(classify(id), Some(Sky::Clear), "{id}");
        }
        for id in [20, 21, 40, 41, 52] {
            assert_eq!(classify(id), Some(Sky::Rain), "{id}");
        }
        assert_eq!(classify(30), Some(Sky::Thunder));
        assert_eq!(classify(31), Some(Sky::Thunder));
        for id in [-1, 2, 9, 12, 59, 61, 80, 89, i16::MAX] {
            assert_eq!(classify(id), None, "{id}");
        }
    }
    #[cfg(windows)]
    #[test]
    fn fingerprints_load_the_static_that_is_read() {
        for (at, bytes) in FINGERPRINTS {
            assert_eq!(&bytes[..3], &[0x48, 0x8b, 0x05]);
            let disp = i32::from_le_bytes(bytes[3..7].try_into().unwrap());
            assert_eq!(at as i64 + 7 + i64::from(disp), WEATHER_STATIC as i64);
        }
    }
}
