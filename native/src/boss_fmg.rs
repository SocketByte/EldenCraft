//! Read-only localized NPC-name lookup for registered native boss gauges.
//! MsgRepository's singleton comes from the version-pinned SDK. Its table
//! indirection/indexes are documented by the primary GetThingName_code.cea:
//! https://github.com/The-Grand-Archives/Elden-Ring-CT-TGA/tree/main/CheatTable/CheatEntries
//! FMG v2 header/groups match SoulsFormats/Formats/FMG.cs; no new game RVA.

use serde::Serialize;

#[derive(Clone, Debug, Default, Serialize)]
pub struct LookupDiagnostic {
    pub status: String,
    pub archives: Vec<ArchiveDiagnostic>,
}
#[derive(Clone, Debug, Serialize)]
pub struct ArchiveDiagnostic {
    pub index: usize,
    pub status: String,
    pub header: Option<String>,
    pub file_size: Option<u32>,
    pub groups: Option<i32>,
    pub strings: Option<i32>,
}
pub struct NameLookup {
    pub name: Option<String>,
    pub diagnostic: LookupDiagnostic,
}

fn offset_index(id: i32, group: [i32; 4], strings: i32) -> Option<usize> {
    let [offset, first, last, _] = group;
    if offset < 0 || first < 0 || first > last || id < first || id > last {
        return None;
    }
    let index = i64::from(offset) + i64::from(id) - i64::from(first);
    (index >= 0 && index < i64::from(strings)).then_some(index as usize)
}

#[cfg(windows)]
mod runtime {
    use super::{ArchiveDiagnostic, LookupDiagnostic, NameLookup, offset_index};
    use eldenring::cs::MsgRepositoryImp;
    use fromsoftware_shared::FromStatic;
    use std::{
        collections::BTreeMap,
        ffi::c_void,
        mem::{MaybeUninit, size_of},
        sync::{Mutex, OnceLock},
    };

    // The primary table looks up these NPC-name archives in this order,
    // covering the base-game patch text and the two DLC patch archives.
    const NPC_NAME_ARCHIVES: [usize; 4] = [119, 18, 328, 428];
    type NameCache = BTreeMap<(usize, i32), String>;
    static CACHE: OnceLock<Mutex<NameCache>> = OnceLock::new();
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetCurrentProcess() -> *mut c_void;
        fn ReadProcessMemory(
            process: *mut c_void,
            address: *const c_void,
            buffer: *mut c_void,
            bytes: usize,
            read: *mut usize,
        ) -> i32;
    }
    // Reading through the OS avoids dereferencing an unavailable archive,
    // malformed pointer or page. Only copied integers/bytes leave this helper.
    fn bytes(address: usize, destination: &mut [u8]) -> Option<()> {
        if address < 0x10000 || address.checked_add(destination.len()).is_none() {
            return None;
        }
        let mut copied = 0;
        let success = unsafe {
            ReadProcessMemory(
                GetCurrentProcess(),
                address as *const c_void,
                destination.as_mut_ptr().cast(),
                destination.len(),
                &mut copied,
            )
        };
        (success != 0 && copied == destination.len()).then_some(())
    }
    fn read<T: Copy>(address: usize) -> Option<T> {
        let mut value = MaybeUninit::<T>::uninit();
        let buffer = unsafe {
            std::slice::from_raw_parts_mut(value.as_mut_ptr().cast::<u8>(), size_of::<T>())
        };
        bytes(address, buffer)?;
        Some(unsafe { value.assume_init() })
    }
    fn archive_table(repository: usize) -> Option<(usize, usize)> {
        // The version-pinned executable's MsgRepository lookup (2.7.1.0 RVA
        // 0x266fc40) verifies both indices, then follows repository+8 to the
        // language table, language[0] to the archive table, and archive[index]
        // to FMG data. The primary CEA's bracketed read has this same extra
        // language dereference. Never interpret the outer table as an FMG.
        let languages = read::<u32>(repository.checked_add(0x10)?)?;
        let archives = read::<u32>(repository.checked_add(0x14)?)?;
        if !(1..=64).contains(&languages) || !(1..=512).contains(&archives) {
            return None;
        }
        let language_table = read::<usize>(repository.checked_add(8)?)?;
        let table = read::<usize>(language_table)?;
        (table >= 0x10000).then_some((table, archives as usize))
    }
    fn from_archive(base: usize, id: i32) -> Option<String> {
        let value = from_archive_bounded(base, id, 256)?;
        crate::boss_hud::name_from_units(&value.encode_utf16().collect::<Vec<_>>())
    }
    fn from_archive_bounded(base: usize, id: i32, max_units: usize) -> Option<String> {
        let header = read::<[u8; 0x28]>(base)?;
        if header[0..4] != [0, 0, 2, 0] || header[8] != 1 {
            return None;
        }
        let file_size = u32::from_le_bytes(header[4..8].try_into().ok()?) as usize;
        let groups = i32::from_le_bytes(header[0xc..0x10].try_into().ok()?);
        let strings = i32::from_le_bytes(header[0x10..0x14].try_into().ok()?);
        if !(0x38..=16777216).contains(&file_size)
            || !(1..=65536).contains(&groups)
            || !(1..=1000000).contains(&strings)
            || 0x28 + groups as usize * 16 > file_size
        {
            return None;
        }
        let encoded_offsets = u64::from_le_bytes(header[0x18..0x20].try_into().ok()?) as usize;
        // Loaded FMGs relocate this offset to a pointer; accept the documented
        // standard file offset too, while retaining the exact same group layout.
        let offsets = if encoded_offsets < file_size {
            base.checked_add(encoded_offsets)?
        } else {
            encoded_offsets
        };
        let mut left = 0usize;
        let mut right = groups as usize;
        while left < right {
            let middle = left + (right - left) / 2;
            let group = read::<[i32; 4]>(base.checked_add(0x28 + middle * 16)?)?;
            if id < group[1] {
                right = middle;
                continue;
            }
            if id > group[2] {
                left = middle + 1;
                continue;
            }
            let index = offset_index(id, group, strings)?;
            let offset = read::<u64>(offsets.checked_add(index * 8)?)? as usize;
            if offset == 0 || offset >= file_size || !offset.is_multiple_of(2) {
                return None;
            }
            let amount = (file_size - offset).min((max_units + 1) * 2) & !1;
            let mut copied = vec![0; amount];
            bytes(base.checked_add(offset)?, &mut copied)?;
            let units: Vec<_> = copied
                .as_chunks::<2>()
                .0
                .iter()
                .map(|pair| u16::from_le_bytes(*pair))
                .collect();
            let end = units.iter().position(|unit| *unit == 0)?;
            let decoded = String::from_utf16(&units[..end]).ok()?;
            return crate::interaction_wire::text(&decoded);
        }
        None
    }
    /// General localized interaction text from primary Smithbox FMG identifiers.
    /// The same bounded OS reads used by NPC boss names; no game calls or writes.
    pub unsafe fn localized(archives: &[usize], id: i32) -> Option<String> {
        if id < 0 {
            return None;
        }
        let repository = MsgRepositoryImp::instance_ptr().ok()? as usize;
        let (table, count) = archive_table(repository)?;
        for &archive in archives {
            if archive >= count {
                continue;
            }
            let Some(base) = table.checked_add(archive * 8).and_then(read::<usize>) else {
                continue;
            };
            if let Some(value) = from_archive_bounded(base, id, crate::interaction_wire::MAX_TEXT) {
                return Some(value);
            }
        }
        None
    }
    /// Game-task only, after the engine's complete executable/offline guard.
    pub unsafe fn npc_name(id: i32) -> NameLookup {
        let mut result = NameLookup {
            name: None,
            diagnostic: LookupDiagnostic {
                status: "unavailable".into(),
                archives: Vec::new(),
            },
        };
        if id <= 0 {
            result.diagnostic.status = "invalid_fmg_id".into();
            return result;
        }
        let Ok(repository) = MsgRepositoryImp::instance_ptr() else {
            result.diagnostic.status = "repository_unavailable".into();
            return result;
        };
        let Some((table, archive_count)) = archive_table(repository as usize) else {
            result.diagnostic.status = "repository_tables_unavailable".into();
            return result;
        };
        for archive in NPC_NAME_ARCHIVES {
            if archive >= archive_count {
                continue;
            }
            let Some(base) = table.checked_add(archive * 8).and_then(read::<usize>) else {
                continue;
            };
            if let Some(name) = CACHE
                .get_or_init(|| Mutex::new(BTreeMap::new()))
                .lock()
                .ok()
                .and_then(|cache| cache.get(&(base, id)).cloned())
            {
                result.name = Some(name);
                result.diagnostic.status = "cached".into();
                return result;
            }
            let name = from_archive(base, id);
            let header = read::<[u8; 0x28]>(base);
            result.diagnostic.archives.push(ArchiveDiagnostic {
                index: archive,
                status: if name.is_some() {
                    "found"
                } else if header.is_none() {
                    "unavailable"
                } else {
                    "not_found_or_invalid"
                }
                .into(),
                header: header.map(|data| {
                    data[..12]
                        .iter()
                        .map(|byte| format!("{byte:02x}"))
                        .collect()
                }),
                file_size: header.map(|data| u32::from_le_bytes(data[4..8].try_into().unwrap())),
                groups: header.map(|data| i32::from_le_bytes(data[0xc..0x10].try_into().unwrap())),
                strings: header
                    .map(|data| i32::from_le_bytes(data[0x10..0x14].try_into().unwrap())),
            });
            if let Some(name) = name {
                if let Ok(mut cache) = CACHE.get().unwrap().lock() {
                    if cache.len() >= 512 {
                        cache.clear();
                    }
                    cache.insert((base, id), name.clone());
                }
                result.name = Some(name);
                result.diagnostic.status = "found".into();
                return result;
            }
        }
        result.diagnostic.status = "name_unavailable".into();
        result
    }
    #[cfg(test)]
    mod tests {
        use super::*;
        #[test]
        fn follows_repository_language_and_archive_tables_before_reading_fmg() {
            let mut data = archive(true);
            const TREE_SENTINEL_NAME_ID: i32 = 903251600;
            data[0x2c..0x30].copy_from_slice(&TREE_SENTINEL_NAME_ID.to_le_bytes());
            data[0x30..0x34].copy_from_slice(&TREE_SENTINEL_NAME_ID.to_le_bytes());
            let mut archives = vec![0usize; 512];
            archives[119] = data.as_ptr() as usize;
            let languages = [archives.as_ptr() as usize];
            let mut repository = [0u8; 0x18];
            repository[8..16].copy_from_slice(&(languages.as_ptr() as usize).to_le_bytes());
            repository[0x10..0x14].copy_from_slice(&1u32.to_le_bytes());
            repository[0x14..0x18].copy_from_slice(&512u32.to_le_bytes());
            let (table, count) = archive_table(repository.as_ptr() as usize).unwrap();
            assert_eq!(table, archives.as_ptr() as usize);
            assert_eq!(count, 512);
            let base = read::<usize>(table + 119 * 8).unwrap();
            assert_eq!(
                from_archive(base, TREE_SENTINEL_NAME_ID).as_deref(),
                Some("Tree Sentinel")
            );
            repository[0x10..0x14].copy_from_slice(&0u32.to_le_bytes());
            assert!(archive_table(repository.as_ptr() as usize).is_none());
            assert!(archive_table(0).is_none());
        }
        fn archive(relocated: bool) -> Vec<u8> {
            let mut data = vec![0u8; 512];
            data[0..4].copy_from_slice(&[0, 0, 2, 0]);
            data[4..8].copy_from_slice(&512u32.to_le_bytes());
            data[8] = 1;
            data[0xc..0x10].copy_from_slice(&1i32.to_le_bytes());
            data[0x10..0x14].copy_from_slice(&3i32.to_le_bytes());
            data[0x14..0x18].copy_from_slice(&255i32.to_le_bytes());
            let offsets = if relocated {
                data.as_ptr() as u64 + 0x38
            } else {
                0x38
            };
            data[0x18..0x20].copy_from_slice(&offsets.to_le_bytes());
            data[0x2c..0x30].copy_from_slice(&100i32.to_le_bytes());
            data[0x30..0x34].copy_from_slice(&102i32.to_le_bytes());
            data[0x38..0x40].copy_from_slice(&0x80u64.to_le_bytes());
            data[0x40..0x48].copy_from_slice(&0xb0u64.to_le_bytes());
            for (start, name) in [(0x80, "Tree Sentinel"), (0xb0, "呪いの王")] {
                for (index, unit) in name.encode_utf16().chain([0]).enumerate() {
                    data[start + index * 2..start + index * 2 + 2]
                        .copy_from_slice(&unit.to_le_bytes());
                }
            }
            data
        }
        #[test]
        fn reads_standard_and_relocated_native_fmg_names_through_bounded_os_reads() {
            for relocated in [false, true] {
                let data = archive(relocated);
                assert_eq!(
                    from_archive(data.as_ptr() as usize, 100).as_deref(),
                    Some("Tree Sentinel")
                );
                assert_eq!(
                    from_archive(data.as_ptr() as usize, 101).as_deref(),
                    Some("呪いの王")
                );
                assert!(from_archive(data.as_ptr() as usize, 102).is_none());
                assert!(from_archive(data.as_ptr() as usize, 103).is_none());
            }
        }
        #[test]
        fn unavailable_memory_and_malformed_archive_fail_without_native_dereferences() {
            assert!(from_archive(0, 100).is_none());
            let mut data = archive(false);
            data[0xc..0x10].copy_from_slice(&i32::MAX.to_le_bytes());
            assert!(from_archive(data.as_ptr() as usize, 100).is_none());
        }
    }
}
#[cfg(windows)]
pub use runtime::localized;
#[cfg(windows)]
pub use runtime::npc_name;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fmg_range_lookup_covers_first_last_and_offset_indices() {
        assert_eq!(offset_index(100, [4, 100, 102, 0], 8), Some(4));
        assert_eq!(offset_index(102, [4, 100, 102, 0], 8), Some(6));
        assert_eq!(offset_index(99, [4, 100, 102, 0], 8), None);
        assert_eq!(offset_index(103, [4, 100, 102, 0], 8), None);
    }
    #[test]
    fn malformed_fmg_ranges_and_out_of_bounds_indices_fail_closed() {
        assert_eq!(offset_index(100, [-1, 100, 102, 0], 8), None);
        assert_eq!(offset_index(100, [0, 102, 100, 0], 8), None);
        assert_eq!(offset_index(102, [7, 100, 102, 0], 8), None);
        assert_eq!(offset_index(i32::MAX, [i32::MAX, 0, i32::MAX, 0], 8), None);
    }
}
