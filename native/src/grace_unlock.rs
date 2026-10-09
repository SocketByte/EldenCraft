//! Explicit /unlockall debug request for grace discovery and map reveal flags.
use crate::campaign::Request;
use std::collections::BTreeSet;

const MAX_ROWS: usize = 4096;
const REQUEST_AGE_MS: u64 = 5000;

pub(crate) fn fresh_request(request: &Request, now: u64) -> bool {
    request.version == 1
        && request.id.len() == 36
        && request
            .id
            .bytes()
            .all(|b| b.is_ascii_hexdigit() || b == b'-')
        && request.timestamp_ms > 0
        && now
            .checked_sub(request.timestamp_ms)
            .is_some_and(|age| age < REQUEST_AGE_MS)
}

/// The pinned SDK's private BonfireWarpParam prefix: package bits/padding,
/// eventflagId (discovery), bonfireEntityId. clearedEventFlagId and story flags
/// lie outside this prefix and are never selected.
fn grace_flag(prefix: [u8; 12]) -> Option<u32> {
    let flag = u32::from_le_bytes(prefix[4..8].try_into().unwrap());
    let entity = u32::from_le_bytes(prefix[8..12].try_into().unwrap());
    (flag != 0 && flag != u32::MAX && entity != 0 && entity != u32::MAX).then_some(flag)
}

fn unlock_flags(rows: impl IntoIterator<Item = [u8; 12]>) -> Result<BTreeSet<u32>, &'static str> {
    let mut flags = BTreeSet::new();
    for (index, row) in rows.into_iter().take(MAX_ROWS + 1).enumerate() {
        if index == MAX_ROWS {
            return Err("Site of Grace table exceeds the debug limit");
        }
        if let Some(flag) = grace_flag(row) {
            flags.insert(flag);
        }
    }
    if flags.is_empty() {
        return Err("Site of Grace table is not loaded");
    }
    Ok(flags)
}

#[cfg(windows)]
pub(crate) struct Unlocked {
    pub total: u32,
    pub remaining: u32,
    pub graces: u32,
}

/// Called only inside the campaign's current-character, loaded offline game task.
#[cfg(windows)]
pub(crate) unsafe fn unlock_all() -> Result<Unlocked, &'static str> {
    use eldenring::cs::{BonfireWarpParam, CSEventFlagMan, SoloParamRepository, WorldMapPieceParam};
    use fromsoftware_shared::FromStatic;
    let params = unsafe { SoloParamRepository::instance() }
        .map_err(|_| "Site of Grace parameters are unavailable")?;
    let mut flags = unlock_flags(params.rows::<BonfireWarpParam>().map(|(_, row)| {
        // BONFIRE_WARP_PARAM_ST is repr(C); its first 12 bytes are the pinned
        // u8/u32 prefix above, also documented by Paramdex ER/Defs/BonfireWarpParam.xml.
        unsafe { std::ptr::from_ref(row).cast::<[u8; 12]>().read_unaligned() }
    }))?;
    let grace_flags = flags.clone();
    let mut map_flags = BTreeSet::new();
    for (index, (_, row)) in params.rows::<WorldMapPieceParam>().take(MAX_ROWS + 1).enumerate() {
        if index == MAX_ROWS {
            return Err("Map reveal table exceeds the debug limit");
        }
        // This condition reveals the map piece and expands its travel area.
        // Acquisition animation flags are intentionally left alone.
        let flag = row.open_event_flag_id();
        if flag != 0 && flag != u32::MAX {
            map_flags.insert(flag);
        }
    }
    if map_flags.is_empty() {
        return Err("Map reveal parameters are unavailable");
    }
    flags.extend(map_flags);
    let manager = unsafe { CSEventFlagMan::instance_mut() }
        .map_err(|_| "Site of Grace event flags are unavailable")?;
    let mut remaining = 0;
    let mut graces = 0;
    for &flag in &flags {
        if !manager.virtual_memory_flag.get_flag(flag) {
            manager.virtual_memory_flag.set_flag(flag, true);
        }
        // The SDK setter can skip an unavailable flag block. Never acknowledge
        // all graces as unlocked without reading every selected flag back.
        if !manager.virtual_memory_flag.get_flag(flag) {
            remaining += 1;
        } else if grace_flags.contains(&flag) {
            graces += 1;
        }
    }
    Ok(Unlocked {
        total: flags.len() as u32,
        remaining,
        graces,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn row(flag: u32, entity: u32) -> [u8; 12] {
        let mut bytes = [0; 12];
        bytes[4..8].copy_from_slice(&flag.to_le_bytes());
        bytes[8..12].copy_from_slice(&entity.to_le_bytes());
        bytes
    }
    #[test]
    fn selects_only_discovery_flags_including_dlc_and_deduplicates_graces() {
        let rows = [
            row(71000, 10001950),
            row(2043480000, 2043481950),
            row(71000, 10001951),
            row(0, 10001952),
            row(u32::MAX, 10001953),
            row(10000800, 0),
            row(10000850, u32::MAX),
        ];
        assert_eq!(unlock_flags(rows), Ok(BTreeSet::from([71000, 2043480000])));
        assert!(unlock_flags([]).is_err());
        assert!(unlock_flags(std::iter::repeat_n(rows[0], MAX_ROWS + 1)).is_err());
    }
    #[test]
    fn stale_future_or_old_guest_requests_cannot_unlock_a_later_load() {
        let mut request: Request = serde_json::from_value(serde_json::json!({
            "version":1, "session":7, "character":"slot-0", "timestamp_ms":1000,
            "id":"12345678-1234-1234-1234-123456789abc", "action":"unlock_all_graces"
        }))
        .unwrap();
        assert!(fresh_request(&request, 1000));
        assert!(fresh_request(&request, 5999));
        assert!(!fresh_request(&request, 6000));
        assert!(!fresh_request(&request, 999));
        request.timestamp_ms = 0;
        assert!(!fresh_request(&request, 1000));
        request.timestamp_ms = 1000;
        request.version = 2;
        assert!(!fresh_request(&request, 1000));
    }
}
