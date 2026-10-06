//! Minecraft-owned interaction menus backed by the original ESD choices/results.
//! Only the pinned SDK's talk vtables and verified MsgRepository function are hooked.
//! Unsupported menus continue through their original handler. UI ownership expires
//! with the guest heartbeat; an owned choice menu is cancelled on every gate loss.
use crate::interaction_wire::{
    self as wire, Choice, Command, Input, Menu, Prompt, State, Subtitle,
};
use eldenring::cs::{
    CSEzStateTalkEnv, CSEzStateTalkEvent, CSLuaEventManImp, CSNpcTalkIns, CSSessionManager,
    FieldInsHandle, GameMan, LobbyState, MenuType, NpcParam, PlayerIns, ProtocolState,
    SoloParamRepository, TalkParam, WorldChrMan,
};
use eldenring::ez_state::{EzStateEnvironmentQuery, EzStateRawValue, EzStateValue};
use fromsoftware_shared::{FromStatic, program::Program};
use ilhook::x64::{HookFlags, Registers, hook_closure_retn};
use pelite::pe64::PeObject;
use std::{
    collections::{BTreeMap, VecDeque},
    fs,
    io::Read,
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const ACTION_FMG: [usize; 3] = [465, 365, 32];
const CHOICE_FMG: [usize; 3] = [466, 366, 33];
const TALK_FMG: [usize; 3] = [460, 360, 1];
const DIALOG_FMG: [usize; 3] = [204, 372, 472];
const ENV_VMT: usize = 0x2c02d68;
const TEXT_LOOKUP: usize = 0x266fc40;
const TEXT_CODE: &[u8] = &[
    0x3b, 0x51, 0x10, 0x73, 0x29, 0x44, 0x3b, 0x41, 0x14, 0x73, 0x23, 0x48, 0x8b, 0x41, 0x08, 0x8b,
    0xd2, 0x48, 0x8b, 0x0c, 0xd0, 0x48, 0x85, 0xc9, 0x74, 0x14, 0x41, 0x8b, 0xc0, 0x48, 0x8b, 0x0c,
    0xc1, 0x48, 0x85, 0xc9, 0x74, 0x08, 0x41, 0x8b, 0xd1, 0xe9, 0xa2, 0x08, 0x00, 0x00, 0x33, 0xc0,
    0xc3,
];
static ACTIVE: AtomicBool = AtomicBool::new(false);
static DEADLINE: AtomicU64 = AtomicU64::new(0);
static TOKENS: AtomicU64 = AtomicU64::new(1);
static LIST_SERIAL: AtomicU64 = AtomicU64::new(1);
static TASK_THREAD: AtomicU64 = AtomicU64::new(0);
/// Epoch deadline of a lease gap that an open replacement menu survives.
/// Talking to an NPC can briefly drop focus/bridge readiness; cancelling there
/// returns -1 (Leave) to the script and the NPC menu disappears.
static HOLD_UNTIL: AtomicU64 = AtomicU64::new(0);
const MENU_HOLD_MS: u64 = 3000;
#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetCurrentThreadId() -> u32;
}
// A missed Clear/Add invalidates the complete list, and a missed Show gives
// ownership back to the native menu. Hooks never wait for the publisher lock.
static LIST_GAP: AtomicU64 = AtomicU64::new(0);
static CAPTURE: Mutex<Capture> = Mutex::new(Capture {
    lists: BTreeMap::new(),
    menu: None,
    dialog: None,
    pending: None,
    subtitle: None,
    suppress_prompt: -1,
    suppress_subtitle: -1,
});
static EMPTY: [u16; 1] = [0];
static DIAGNOSTICS: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
fn diagnostic(message: String) {
    if let Ok(mut events) = DIAGNOSTICS.try_lock() {
        if events.back() != Some(&message) {
            events.push_back(message);
        }
        while events.len() > 64 {
            events.pop_front();
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct Owner {
    npc: usize,
    talk: i32,
    handle: FieldInsHandle,
    chr: usize,
}
struct List {
    owner: Option<Owner>,
    generation: u64,
    rows: Vec<Choice>,
    // Native slot -> localized text ID, so grace rules also apply when the
    // grace context is only proven at Show time.
    text_ids: BTreeMap<i32, i32>,
    complete: bool,
    grace: bool,
}
struct OwnedMenu {
    owner: Owner,
    menu: Menu,
    result: Option<i32>,
    open: bool,
    native_type: MenuType,
}
struct Spoken {
    owner: Owner,
    param: i32,
    msg: i32,
    text: String,
    expires: u64,
}
struct Capture {
    lists: BTreeMap<usize, List>,
    menu: Option<OwnedMenu>,
    dialog: Option<OwnedMenu>,
    pending: Option<PendingShow>,
    subtitle: Option<Spoken>,
    suppress_prompt: i32,
    suppress_subtitle: i32,
}
struct PendingShow {
    owner: Owner,
    menu: Menu,
    rest: crate::grace_reset::Sample,
    expires: u64,
    generation: u64,
}
impl PendingShow {
    fn source_matches(&self, list: &List) -> bool {
        list.complete && list.owner == Some(self.owner) && list.generation == self.generation
    }
    fn matches(&self, now: u64, sample: crate::grace_reset::Sample) -> bool {
        now <= self.expires
            && sample.player == self.rest.player
            && sample.entry == self.rest.entry
            && sample.block == self.rest.block
            && sample.rest.is_some()
            && sample.rest == self.rest.rest
            && sample
                .feet
                .iter()
                .zip(self.rest.feet)
                .map(|(a, b)| (a - b).powi(2))
                .sum::<f32>()
                <= 1.
    }
}

fn capture_gap_mask(id: i32, _menu_type: Option<i32>) -> u64 {
    match id {
        19 | 20 | 149 => 1,
        10 | 76 | 150 => 15,
        17 => 14,
        _ => 0,
    }
}
fn forward_unowned_show(gap: &AtomicU64, id: i32) -> bool {
    if matches!(id, 10 | 17 | 76 | 150) {
        // Queries can deliver a retained result after the lease expires. A new
        // original Show starts another result context, so revoke the old one
        // before forwarding, without reading owners or writing native state.
        gap.fetch_or(capture_gap_mask(id, None), Ordering::AcqRel);
    }
    false
}
fn apply_capture_gap(capture: &mut Capture, gap: u64) {
    if gap & 1 != 0 {
        capture.lists.clear();
        capture.pending = None;
    }
    if gap & 2 != 0 {
        // The missed Show was forwarded to the original handler. Do not write
        // its menu state or let an earlier captured result override its query.
        capture.menu = None;
        capture.pending = None;
    }
    if gap & 4 != 0 {
        capture.dialog = None;
    }
    if gap & 8 != 0 {
        capture.subtitle = None;
        capture.suppress_prompt = -1;
        capture.suppress_subtitle = -1;
    }
}
fn new_unconfirmed_list() -> List {
    List {
        owner: None,
        generation: 0,
        rows: Vec::new(),
        text_ids: BTreeMap::new(),
        complete: false,
        grace: false,
    }
}
/// Every Site of Grace, including the Roundtable and DLC graces, runs the
/// common t000001000 talk script.
const GRACE_TALK: i32 = 1000;
/// "Pass time" is only ever offered by the grace script.
const PASS_TIME: i32 = 15000420;
fn grace_source(talk: i32, text_id: Option<i32>) -> bool {
    talk == GRACE_TALK || text_id == Some(PASS_TIME)
}
/// Merchant "Purchase" runs OpenRegularShop, which the campaign replaces with
/// the Minecraft shop. "Sell" would open the native sell menu under Minecraft.
const PURCHASE: i32 = 20000010;
const SELL: i32 = 20000011;
fn omitted_choice(text_id: i32, grace: bool) -> bool {
    matches!(text_id, 15000540 | 15000370 | 15000371 | 15000510 | SELL)
        || grace && text_id == 15000390
}
/// Apply grace-only omissions and the Ender Chest action to captured rows.
fn grace_choices(list: &List) -> Vec<Choice> {
    list.rows
        .iter()
        .filter_map(|row| match list.text_ids.get(&row.id) {
            Some(&text_id) if omitted_choice(text_id, true) => None,
            Some(&text_id) => Some(captured_choice(row.id, text_id, row.text.clone(), true)),
            None => Some(row.clone()),
        })
        .collect()
}
fn captured_choice(slot: i32, text_id: i32, text: String, grace: bool) -> Choice {
    let chest = grace && text_id == 15000395;
    Choice {
        id: slot,
        text: if chest {
            "Ender Chest".into()
        } else if text_id == PURCHASE {
            "Shop".into()
        } else {
            text
        },
        enabled: true,
        action: chest.then(|| "ender_chest".into()),
    }
}
fn record_choice(
    list: &mut List,
    who: Owner,
    slot: i32,
    text_id: i32,
    value: Option<String>,
    grace: bool,
) {
    if omitted_choice(text_id, grace) {
        return;
    }
    if list.owner.is_some_and(|owner| owner != who) {
        list.complete = false;
        list.rows.clear();
        list.text_ids.clear();
    }
    list.owner = Some(who);
    list.grace |= grace;
    list.generation = LIST_SERIAL.fetch_add(1, Ordering::Relaxed);
    match value.filter(|_| list.rows.len() < wire::MAX_CHOICES) {
        None => list.complete = false,
        Some(value) => {
            list.rows.retain(|r| r.id != slot);
            list.rows.push(captured_choice(slot, text_id, value, grace));
            list.text_ids.insert(slot, text_id);
        }
    }
}
fn generic_choices(
    box_type: i32,
    left_text: i32,
    right_text: i32,
    mut resolve: impl FnMut(i32) -> Option<String>,
) -> Option<Vec<Choice>> {
    // Supported executable: 7b8660 switch at 7b8a08 chooses the number of
    // buttons. 80dc70/77be80 preserve args3/4 as localized button text IDs;
    // 7b6200 category6/d117b0 resolves them in Dialogue FMG, not style enums.
    let count = match box_type {
        1 | 4 | 7 => 1,
        2 | 3 | 5 | 8 => 2,
        _ => return None,
    };
    let mut choices = vec![Choice {
        id: 1,
        text: resolve(left_text)?,
        enabled: true,
        action: None,
    }];
    if count == 2 {
        choices.push(Choice {
            id: 2,
            text: resolve(right_text)?,
            enabled: true,
            action: None,
        });
    }
    Some(choices)
}

fn epoch() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
fn active() -> bool {
    ACTIVE.load(Ordering::Acquire) && epoch() <= DEADLINE.load(Ordering::Acquire)
}
fn token() -> u64 {
    TOKENS.fetch_add(1, Ordering::Relaxed).min(i64::MAX as u64)
}
unsafe fn offline() -> bool {
    (unsafe { menu_session() })
        && unsafe { GameMan::instance() }.is_ok_and(|g| g.save_slot >= 0)
        && unsafe { PlayerIns::local_player() }.is_ok_and(|p| {
            p.current_block_id.0 != -1
                && p.chr_ins.chr_flags1c8.is_active()
                && p.chr_ins.chr_flags1c8.update_tasks_registered()
                && !p.chr_ins.chr_flags1c5.death_flag()
                && p.chr_ins.modules.data.hp > 0
                && unsafe { p.chr_ins.chr_set_entry.as_ref() }
                    .chr_ins
                    .is_some_and(|v| std::ptr::eq(v.as_ptr(), &p.chr_ins))
        })
}
/// Menu ESD is executed while character activity flags can be transient. The
/// fresh task-thread bridge lease already validates the player; the original
/// SDK TalkScript preconditions validate the script's character handle, not
/// local-player physics or registered movement tasks.
unsafe fn menu_session() -> bool {
    unsafe { GameMan::instance() }.is_ok_and(|g| !g.is_in_online_mode && !g.warp_requested)
        && unsafe { CSSessionManager::instance() }.is_ok_and(|s| {
            s.lobby_state == LobbyState::None && s.protocol_state == ProtocolState::None
        })
}
fn menu_admitted(fresh_bridge: bool, offline_session: bool, script_owner: bool) -> bool {
    fresh_bridge && offline_session && script_owner
}
unsafe fn bonfire_menu_context() -> bool {
    unsafe { CSLuaEventManImp::instance() }.is_ok_and(|events| {
        let proxy = &events.lua_event_proxy;
        let flags = proxy.control_flags;
        (flags.bonfire_loop_begin_requested() || flags.bonfire_sitting_loop_active())
            && !flags.bonfire_end_pending()
            && !flags.bonfire_stand_up_in_progress()
            && !flags.return_title_requested()
            && !proxy.is_load_wait
            && !proxy.is_lobby_state_client
            && !proxy.is_net_message
    })
}
unsafe fn owner(npc: &CSNpcTalkIns) -> Option<Owner> {
    let w = unsafe { WorldChrMan::instance() }.ok()?;
    let chr = w.chr_ins_by_handle(&npc.base.field_ins_handle)?;
    Some(Owner {
        npc: npc as *const _ as usize,
        talk: npc.base.talk_id,
        handle: npc.base.field_ins_handle,
        chr: chr as *const _ as usize,
    })
}
unsafe fn live(owner: Owner) -> bool {
    unsafe { WorldChrMan::instance() }
        .ok()
        .and_then(|w| w.chr_ins_by_handle(&owner.handle))
        .is_some_and(|c| c as *const _ as usize == owner.chr)
}
unsafe fn npc_title(who: Owner) -> Option<String> {
    let world = unsafe { WorldChrMan::instance() }.ok()?;
    let chr = world.chr_ins_by_handle(&who.handle)?;
    if chr as *const _ as usize != who.chr || chr.npc_param_id < 0 {
        return None;
    }
    // Both identifiers are typed in the pinned SDK: ChrIns.npc_param_id and
    // NPC_PARAM_ST.name_id. Reuse the established read-only NPC FMG lookup.
    let name = unsafe { SoloParamRepository::instance() }
        .ok()?
        .get::<NpcParam>(chr.npc_param_id as u32)?
        .name_id();
    if name <= 0 {
        return None;
    }
    unsafe { crate::boss_fmg::npc_name(name) }.name
}
fn release_closed_contexts(c: &mut Capture, npc: usize) {
    if c.menu
        .as_ref()
        .is_some_and(|m| m.owner.npc == npc && !m.open)
    {
        c.menu = None;
    }
    if c.dialog
        .as_ref()
        .is_some_and(|m| m.owner.npc == npc && !m.open)
    {
        c.dialog = None;
    }
}
unsafe fn native_menu_fallback(c: &mut Capture, npc: usize) {
    c.pending = None;
    // Original opening follows this hook. Cancel any replacement ownership so
    // the native window can receive controls; old results from this same NPC
    // must not override the newly opened native result context.
    if active() && unsafe { offline() } {
        unsafe {
            close_menu(c, -1);
            close_dialog(c, 0);
        }
    } else {
        c.menu = None;
        c.dialog = None;
    }
    release_closed_contexts(c, npc);
    c.subtitle = None;
    c.suppress_prompt = -1;
    c.suppress_subtitle = -1;
}
#[derive(Debug)]
pub(crate) struct ScriptInvocation {
    pub id: i32,
    pub args: Vec<Option<i32>>,
}
impl ScriptInvocation {
    fn id(&self) -> i32 {
        self.id
    }
    fn arg(&self, index: usize) -> Option<i32> {
        self.args.get(index).copied().flatten()
    }
}
type InvocationId = unsafe extern "system" fn(usize) -> i32;
type InvocationCount = unsafe extern "system" fn(usize) -> u32;
type EventArgument = unsafe extern "system" fn(usize, u32) -> *const EzStateRawValue;
type QueryArgument =
    unsafe extern "system" fn(usize, *mut EzStateRawValue, u32) -> *const EzStateRawValue;

/// The supported game's dispatcher calls an interface, not ExternalEventTemp.
/// Event RVA ea7158 uses slot2 (ID), ea7198 slot4 (argument), ea7181
/// slot3 (arity including ID). Query ea24fc/ea252d/ea2566 use slots1/2/3;
/// its argument getter also receives caller-owned result storage in RDX.
/// Only function addresses in the fully verified executable can be called.
unsafe fn invocation_methods(pointer: usize, slots: [usize; 3]) -> Option<[usize; 3]> {
    if pointer < 0x10000 || !pointer.is_multiple_of(8) {
        return None;
    }
    let table = unsafe { (pointer as *const usize).read() };
    let image = Program::current().image();
    let base = image.as_ptr() as usize;
    let mut methods = [0; 3];
    for (destination, slot) in methods.iter_mut().zip(slots) {
        let offset = table.checked_sub(base)?.checked_add(slot.checked_mul(8)?)?;
        *destination =
            usize::from_le_bytes(image.get(offset..offset.checked_add(8)?)?.try_into().ok()?);
        let target = destination.checked_sub(base)?;
        image.get(target..target.checked_add(1)?)?;
    }
    Some(methods)
}
unsafe fn numeric_script_value(value: *const EzStateRawValue) -> Option<i32> {
    if value.is_null() {
        return None;
    }
    // EzStateRawValue's value union is eight bytes, followed by its numeric tag.
    // Do not interpret unknown/string tags as an SDK enum or follow its pointers.
    let bytes = unsafe { value.cast::<[u8; 16]>().read_unaligned() };
    match u32::from_le_bytes(bytes[8..12].try_into().ok()?) {
        2 => Some(i32::from_le_bytes(bytes[..4].try_into().ok()?)),
        1 => {
            let value = f32::from_le_bytes(bytes[..4].try_into().ok()?);
            (value.is_finite() && (-2147483648. ..2147483648.).contains(&value))
                .then_some(value as i32)
        }
        _ => None,
    }
}
unsafe fn event_from_methods(pointer: usize, methods: [usize; 3]) -> Option<ScriptInvocation> {
    let id: InvocationId = unsafe { std::mem::transmute(methods[0]) };
    let count: InvocationCount = unsafe { std::mem::transmute(methods[1]) };
    let argument: EventArgument = unsafe { std::mem::transmute(methods[2]) };
    let id = unsafe { id(pointer) };
    if !matches!(
        id,
        1 | 8
            | 9
            | 10
            | 12
            | 17
            | 18
            | 19
            | 20
            | 22
            | 31
            | 41
            | 42
            | 67
            | 76
            | 105
            | 112
            | 113
            | 149
            | 150
    ) {
        return Some(ScriptInvocation {
            id,
            args: Vec::new(),
        });
    }
    let count = unsafe { count(pointer) };
    if !(1..=61).contains(&count) {
        return None;
    }
    let args = (1..count)
        .map(|index| unsafe { numeric_script_value(argument(pointer, index)) })
        .collect();
    Some(ScriptInvocation { id, args })
}
pub(crate) unsafe fn script_event(pointer: usize) -> Option<ScriptInvocation> {
    unsafe { event_from_methods(pointer, invocation_methods(pointer, [2, 3, 4])?) }
}
unsafe fn query_from_methods(pointer: usize, methods: [usize; 3]) -> Option<ScriptInvocation> {
    let id: InvocationId = unsafe { std::mem::transmute(methods[0]) };
    let count: InvocationCount = unsafe { std::mem::transmute(methods[1]) };
    let argument: QueryArgument = unsafe { std::mem::transmute(methods[2]) };
    let id = unsafe { id(pointer) };
    if !matches!(id, 21 | 22 | 23 | 25 | 57 | 58 | 59) {
        return Some(ScriptInvocation {
            id,
            args: Vec::new(),
        });
    }
    let count = unsafe { count(pointer) };
    if !(1..=7).contains(&count) {
        return None;
    }
    let args = (1..count)
        .map(|index| {
            let mut result = EzStateRawValue::default();
            unsafe { numeric_script_value(argument(pointer, &mut result, index)) }
        })
        .collect();
    Some(ScriptInvocation { id, args })
}
unsafe fn script_query(pointer: usize) -> Option<ScriptInvocation> {
    unsafe { query_from_methods(pointer, invocation_methods(pointer, [1, 2, 3])?) }
}
fn arg(event: &ScriptInvocation, index: usize) -> Option<i32> {
    event.arg(index)
}
unsafe fn close_menu(c: &mut Capture, result: i32) {
    if let Some(menu) = &mut c.menu {
        if !menu.open && menu.result.is_some() {
            return;
        }
        if menu.open && unsafe { live(menu.owner) } {
            let npc = unsafe { &mut *(menu.owner.npc as *mut CSNpcTalkIns) };
            if npc.base.talk_id == menu.owner.talk
                && npc.menu_state.current_open_menu == menu.native_type
            {
                npc.menu_state.current_open_menu = MenuType::None;
            }
        }
        menu.open = false;
        menu.result = Some(result);
    }
}
unsafe fn close_dialog(c: &mut Capture, result: i32) {
    if let Some(dialog) = &mut c.dialog {
        if !dialog.open && dialog.result.is_some() {
            return;
        }
        dialog.open = false;
        dialog.result = Some(result);
    }
}
/// Decide whether an open menu survives a lease gap at `now`. The ESD keeps
/// observing the open replacement through the query hook meanwhile.
fn hold_open_menu(hold: &AtomicU64, now: u64, open: bool, session: bool) -> HoldDecision {
    if !open {
        hold.store(0, Ordering::Release);
        return HoldDecision::Release;
    }
    let until = hold.load(Ordering::Acquire);
    if !session || until != 0 && now > until {
        hold.store(0, Ordering::Release);
        HoldDecision::Cancel
    } else if until == 0 {
        hold.store(now.saturating_add(MENU_HOLD_MS), Ordering::Release);
        HoldDecision::Started
    } else {
        HoldDecision::Holding
    }
}
#[derive(Debug, PartialEq, Eq)]
enum HoldDecision {
    Release,
    Started,
    Holding,
    Cancel,
}
fn menu_open(c: &Capture) -> bool {
    c.menu.as_ref().is_some_and(|m| m.open) || c.dialog.as_ref().is_some_and(|m| m.open)
}
unsafe fn recover_pending_grace(now: u64) {
    let Some(sample) = (unsafe { crate::grace_reset::sample() }).filter(|s| s.activity_ready)
    else {
        return;
    };
    let pending = CAPTURE.lock().ok().and_then(|mut c| c.pending.take());
    let Some(pending) = pending.filter(|p| p.matches(now, sample) && unsafe { live(p.owner) })
    else {
        return;
    };
    let npc = unsafe { &*(pending.owner.npc as *const CSNpcTalkIns) };
    if npc.base.talk_id != pending.owner.talk
        || npc.base.field_ins_handle != pending.owner.handle
        || npc.menu_state.current_open_menu != MenuType::TalkList
    {
        return;
    }
    if CAPTURE.lock().ok().is_none_or(|c| {
        c.lists
            .get(&pending.owner.npc)
            .is_none_or(|list| !pending.source_matches(list))
    }) {
        return;
    }
    // Original native finalization can invoke callbacks. Never hold CAPTURE here.
    if !unsafe { crate::campaign_runtime::close_original_talk_menu(npc) } {
        return;
    }
    let Some(sample) = (unsafe { crate::grace_reset::sample() }) else {
        return;
    };
    if !pending.matches(epoch(), sample) || !unsafe { live(pending.owner) } {
        return;
    }
    let npc = unsafe { &mut *(pending.owner.npc as *mut CSNpcTalkIns) };
    if npc.base.talk_id != pending.owner.talk
        || npc.base.field_ins_handle != pending.owner.handle
        || npc.menu_state.current_open_menu != MenuType::None
        || npc.menu_state.open_menu_job.finalize_callback_job.is_some()
    {
        return;
    }
    if let Ok(mut c) = CAPTURE.lock() {
        apply_capture_gap(&mut c, LIST_GAP.swap(0, Ordering::AcqRel));
        if c.pending.is_some()
            || c.menu.as_ref().is_some_and(|m| m.open)
            || c.dialog.as_ref().is_some_and(|m| m.open)
            || c.lists
                .get(&pending.owner.npc)
                .is_none_or(|list| !pending.source_matches(list))
        {
            return;
        }
        npc.menu_state.current_open_menu = MenuType::TalkList;
        diagnostic(format!(
            "Interaction grace menu recovered after reset: talk={} rows={}.",
            pending.owner.talk,
            pending.menu.choices.len()
        ));
        c.menu = Some(OwnedMenu {
            owner: pending.owner,
            menu: pending.menu,
            result: None,
            open: true,
            native_type: MenuType::TalkList,
        });
    }
}
/// Called inside the verified ESD event hook; native script side effects proceed.
pub(crate) unsafe fn event_intercept(regs: &Registers, event: &ScriptInvocation) -> bool {
    let id = event.id();
    if !matches!(
        id,
        1 | 8
            | 9
            | 10
            | 12
            | 17
            | 18
            | 19
            | 20
            | 31
            | 41
            | 42
            | 67
            | 76
            | 105
            | 112
            | 113
            | 149
            | 150
    ) {
        return false;
    }
    let fresh_bridge = active();
    let offline_session = fresh_bridge && unsafe { menu_session() };
    let regular_observer = fresh_bridge && offline_session;
    let show = matches!(id, 10 | 17 | 76 | 150);
    // The strict sample is for bounded rest recovery and Show diagnostics. It
    // must not prevent ordinary Clear/Add/Show from observing a live ESD owner.
    let checked =
        (show || !regular_observer).then(|| unsafe { crate::grace_reset::sample_checked() });
    let sample = checked
        .as_ref()
        .and_then(|value| value.as_ref().ok())
        .map(|value| value.sample);
    let rest = sample.filter(|s| s.rest.is_some());
    let passive = matches!(event.id, 19 | 20 | 149) && sample.is_some();
    let pending_show = matches!(event.id, 10 | 150) && rest.is_some();
    if show {
        let sample_diagnostic = checked.as_ref().map_or_else(
            || "not sampled".into(),
            |value| match value {
                Ok(value) => format!("{value}"),
                Err(reason) => format!("rejected={reason:?}"),
            },
        );
        diagnostic(format!(
            "Interaction native menu event={id} args={:?} lease={fresh_bridge} session={offline_session} observer={regular_observer} event_thread={} task_thread={} sample={sample_diagnostic}.",
            &event.args[..event.args.len().min(8)],
            unsafe { GetCurrentThreadId() },
            TASK_THREAD.load(Ordering::Acquire),
        ));
    }
    if regs.rcx == 0 || regs.rdx == 0 || !(regular_observer || passive || pending_show) {
        return forward_unowned_show(&LIST_GAP, id);
    }
    let event_owner = unsafe { &mut *(regs.rcx as *mut CSEzStateTalkEvent) };
    let npc = unsafe { event_owner.npc_talk_ins.as_mut() };
    let Some(who) = (unsafe { owner(npc) }) else {
        if matches!(event.id, 10 | 17 | 76 | 150) {
            diagnostic(format!(
                "Interaction menu retained native: unresolved talk owner talk={} handle={:?}.",
                npc.base.talk_id, npc.base.field_ins_handle
            ));
        }
        return forward_unowned_show(&LIST_GAP, id);
    };
    let admitted = menu_admitted(fresh_bridge, offline_session, true);
    let bonfire = admitted && unsafe { bonfire_menu_context() };
    let Ok(mut c) = CAPTURE.try_lock() else {
        LIST_GAP.fetch_or(capture_gap_mask(id, arg(event, 0)), Ordering::AcqRel);
        return false;
    };
    apply_capture_gap(&mut c, LIST_GAP.swap(0, Ordering::AcqRel));
    match id {
        20 => {
            c.pending = None;
            if c.lists.len() >= 32 {
                c.lists.clear();
            }
            c.lists.insert(
                who.npc,
                List {
                    owner: Some(who),
                    generation: LIST_SERIAL.fetch_add(1, Ordering::Relaxed),
                    rows: Vec::new(),
                    text_ids: BTreeMap::new(),
                    complete: true,
                    grace: grace_source(who.talk, None),
                },
            );
            if c.menu.as_ref().is_some_and(|m| m.owner.npc == who.npc) {
                if c.menu.as_ref().is_some_and(|m| m.open) {
                    diagnostic(format!(
                        "Interaction menu cancelled: talk={} cleared its choices while open.",
                        who.talk
                    ));
                }
                if admitted {
                    unsafe { close_menu(&mut c, -1) };
                }
                c.menu = None;
            }
        }
        19 | 149 => {
            c.pending = None;
            let (Some(slot), Some(text_id)) = (arg(event, 0), arg(event, 1)) else {
                c.lists
                    .entry(who.npc)
                    .or_insert_with(new_unconfirmed_list)
                    .complete = false;
                return false;
            };
            // Primary grace ESD t000001000: Level Up and the flask submenu.
            // Skip only these localized IDs, preserving every remaining native slot.
            let grace = rest.is_some()
                || bonfire
                || grace_source(who.talk, Some(text_id))
                || c.lists.get(&who.npc).is_some_and(|list| list.grace);
            if omitted_choice(text_id, grace) {
                return false;
            }
            let value = unsafe { crate::boss_fmg::localized(&CHOICE_FMG, text_id) };
            let list = c.lists.entry(who.npc).or_insert_with(new_unconfirmed_list);
            record_choice(list, who, slot, text_id, value, grace);
        }
        10 | 76 | 150 => {
            c.pending = None;
            release_closed_contexts(&mut c, who.npc);
            if id == 10 && arg(event, 0) != Some(1) {
                unsafe { native_menu_fallback(&mut c, who.npc) };
                return false;
            }
            // Verified event150 branch eaaa03 joins the same ea0190 handler
            // as event10; ea0238 sets native MenuType1. Its two flags choose
            // list layout/sorting only, preserving the exact captured slot IDs.
            if id == 150
                && !(matches!(arg(event, 0), Some(0 | 1)) && matches!(arg(event, 1), Some(0 | 1)))
            {
                unsafe { native_menu_fallback(&mut c, who.npc) };
                return false;
            }
            if c.menu.as_ref().is_some_and(|m| m.owner.npc == who.npc) {
                // This Show starts a new result context, including native
                // fallback when any source row could not be captured.
                c.menu = None;
            }
            let Some(list) = c.lists.get(&who.npc) else {
                unsafe { native_menu_fallback(&mut c, who.npc) };
                return false;
            };
            if !list.complete || list.rows.is_empty() || list.owner != Some(who) {
                diagnostic(format!(
                    "Interaction menu retained native: event={id} talk={} captured_rows={} complete={}.",
                    who.talk,
                    list.rows.len(),
                    list.complete
                ));
                unsafe { native_menu_fallback(&mut c, who.npc) };
                return false;
            }
            let grace = list.grace || rest.is_some() || bonfire || grace_source(who.talk, None);
            let rows = if grace {
                grace_choices(list)
            } else {
                list.rows.clone()
            };
            let generation = list.generation;
            if rows.is_empty() {
                unsafe { native_menu_fallback(&mut c, who.npc) };
                return false;
            }
            if c.menu
                .as_ref()
                .is_some_and(|m| m.open && m.owner.npc != who.npc)
            {
                unsafe { native_menu_fallback(&mut c, who.npc) };
                return false;
            }
            let menu = Menu {
                token: token(),
                kind: if grace { "grace" } else { "npc" }.into(),
                title: if grace {
                    "Site of Grace".into()
                } else {
                    unsafe { npc_title(who) }.unwrap_or_else(|| "NPC choices".into())
                },
                choices: rows,
            };
            if !wire::menu_fits(&menu) {
                unsafe { native_menu_fallback(&mut c, who.npc) };
                return false;
            }
            if !admitted {
                if let Some(rest) = rest.filter(|_| grace && id != 76) {
                    c.pending = Some(PendingShow {
                        owner: who,
                        menu,
                        rest,
                        expires: epoch().saturating_add(crate::grace_reset::LEASE_MS),
                        generation,
                    });
                    diagnostic(format!(
                        "Interaction grace menu observed during reset/readiness recovery: event={id} talk={} rows={}.",
                        who.talk,
                        c.pending.as_ref().unwrap().menu.choices.len()
                    ));
                }
                return false;
            }
            let native_type = if id == 76 {
                MenuType::ConversationChoices
            } else {
                MenuType::TalkList
            };
            npc.menu_state.current_open_menu = native_type;
            c.menu = Some(OwnedMenu {
                owner: who,
                menu,
                result: None,
                open: true,
                native_type,
            });
            diagnostic(format!(
                "Interaction menu captured: event={id} talk={} kind={} rows={}.",
                who.talk,
                if grace { "grace" } else { "npc" },
                c.menu.as_ref().unwrap().menu.choices.len()
            ));
            return true;
        }
        17 => {
            release_closed_contexts(&mut c, who.npc);
            if c.dialog.as_ref().is_some_and(|d| d.owner.npc == who.npc) {
                c.dialog = None;
            }
            let (Some(box_type), Some(text_id), Some(left), Some(right), Some(_)) = (
                arg(event, 0),
                arg(event, 1),
                arg(event, 2),
                arg(event, 3),
                arg(event, 4),
            ) else {
                unsafe { native_menu_fallback(&mut c, who.npc) };
                return false;
            };
            let Some(title) = (unsafe { crate::boss_fmg::localized(&CHOICE_FMG, text_id) }) else {
                unsafe { native_menu_fallback(&mut c, who.npc) };
                return false;
            };
            let Some(choices) = generic_choices(box_type, left, right, |id| unsafe {
                crate::boss_fmg::localized(&DIALOG_FMG, id)
            }) else {
                unsafe { native_menu_fallback(&mut c, who.npc) };
                return false;
            };
            let menu = Menu {
                token: token(),
                kind: "dialog".into(),
                title,
                choices,
            };
            if !wire::menu_fits(&menu) {
                unsafe { native_menu_fallback(&mut c, who.npc) };
                return false;
            }
            c.dialog = Some(OwnedMenu {
                owner: who,
                menu,
                result: None,
                open: true,
                native_type: MenuType::GenericDialog,
            });
            return true;
        }
        18 => {
            if c.dialog.as_ref().is_some_and(|d| d.owner.npc == who.npc) {
                unsafe { close_dialog(&mut c, 0) };
                return true;
            }
        }
        12 | 67 => {
            c.pending = None;
            if c.menu.as_ref().is_some_and(|m| m.owner.npc == who.npc) {
                if c.menu.as_ref().is_some_and(|m| m.open) {
                    diagnostic(format!(
                        "Interaction menu closed by the native script: event={id} talk={}.",
                        who.talk
                    ));
                }
                unsafe { close_menu(&mut c, -1) };
            }
        }
        41 | 42 | 112 => {
            if let Some(list) = c.lists.get_mut(&who.npc) {
                list.grace = true;
            }
        }
        1 => {
            let Some(param) = arg(event, 0) else {
                return false;
            };
            let info = unsafe { SoloParamRepository::instance() }
                .ok()
                .and_then(|p| p.get::<TalkParam>(param as u32))
                .map(|p| (p.msg_id(), p.timeout()));
            if let Some((msg, timeout)) = info
                && let Some(text) = unsafe { crate::boss_fmg::localized(&TALK_FMG, msg) }
            {
                let duration = if timeout.is_finite() && timeout > 0. {
                    (timeout as f64 * 1000.).clamp(1000., 120000.) as u64
                } else {
                    120000
                };
                c.subtitle = Some(Spoken {
                    owner: who,
                    param,
                    msg,
                    text,
                    expires: epoch().saturating_add(duration),
                });
            }
        }
        8 | 9 => {
            if c.subtitle.as_ref().is_some_and(|s| s.owner.npc == who.npc) {
                c.subtitle = None;
            }
        }
        // User explicitly excludes the native levelling/reallocation and flask menus.
        31 | 105 | 113 => {
            return true;
        }
        _ => {}
    }
    false
}
unsafe fn query_intercept(regs: &Registers, query: &ScriptInvocation) -> Option<i32> {
    if regs.rcx == 0 || regs.rdx == 0 || regs.r8 == 0 {
        return None;
    }
    let env = unsafe { &*(regs.rcx as *const CSEzStateTalkEnv) };
    // After OpenRegularShop the merchant script waits while
    // CheckSpecificPersonMenuIsOpen(RegularShop) holds. The native query does
    // not see the Minecraft replacement and would loop straight back to the
    // talk list, hiding the shop until Leave.
    if crate::campaign_runtime::replacement_shop_open(env.npc_talk_ins.as_ptr() as usize) {
        match query.id() {
            25 | 59 if query.arg(0) == Some(MenuType::RegularShop as i32) => return Some(1),
            58 => return Some(0),
            _ => {}
        }
    }
    let mut c = CAPTURE.try_lock().ok()?;
    apply_capture_gap(&mut c, LIST_GAP.swap(0, Ordering::AcqRel));
    // Expire a held menu even if the bridge task stops ticking entirely.
    let until = HOLD_UNTIL.load(Ordering::Acquire);
    if until != 0 && !active() && epoch() > until && menu_open(&c) {
        HOLD_UNTIL.store(0, Ordering::Release);
        diagnostic("Interaction menu cancelled: Minecraft did not regain the menu in time.".into());
        unsafe {
            close_menu(&mut c, -1);
            close_dialog(&mut c, 0);
        }
    }
    let matched = |m: &OwnedMenu| {
        env.npc_talk_ins.as_ptr() as usize == m.owner.npc
            && env.talk_id == m.owner.talk
            && unsafe { live(m.owner) }
    };
    if let Some(dialog) = c.dialog.as_ref().filter(|m| matched(m)) {
        match query.id() {
            22 => return Some(dialog.result.unwrap_or(0)),
            21 | 58 => return Some(i32::from(dialog.open)),
            25 | 59
                if query
                    .arg(0)
                    .is_some_and(|v| v == MenuType::GenericDialog as i32) =>
            {
                return Some(i32::from(dialog.open));
            }
            _ => {}
        }
    }
    let menu = c.menu.as_ref().filter(|m| matched(m))?;
    match query.id() {
        23 => Some(menu.result.unwrap_or(-1)),
        25 | 59 if query.arg(0).is_some_and(|v| v == menu.native_type as i32) => {
            Some(i32::from(menu.open))
        }
        // The replacement has no nested native generic dialog over its choice list.
        58 if menu.open => Some(0),
        _ => None,
    }
}
unsafe fn observe_query_result(regs: &Registers, id: i32, result: *const EzStateRawValue) {
    if regs.rcx == 0 || regs.r8 == 0 || result.is_null() {
        return;
    }
    // ESDLang's ER metadata: query57 reports the real current voice/talk end.
    if id != 57 || unsafe { numeric_script_value(result) }.is_none_or(|value| value == 0) {
        return;
    }
    let env = unsafe { &*(regs.rcx as *const CSEzStateTalkEnv) };
    if let Ok(mut capture) = CAPTURE.try_lock()
        && capture.subtitle.as_ref().is_some_and(|s| {
            s.owner.npc == env.npc_talk_ins.as_ptr() as usize && s.owner.talk == env.talk_id
        })
    {
        capture.subtitle = None;
        capture.suppress_subtitle = -1;
    }
}
unsafe fn install_hook(rva: usize, kind: u8) -> Result<(), String> {
    let label = if kind == 1 {
        "talk-query"
    } else {
        "text-lookup"
    };
    let image = Program::current().image();
    let address = if kind == 2 {
        image.as_ptr() as usize + rva
    } else {
        usize::from_le_bytes(
            image
                .get(rva + 8..rva + 16)
                .ok_or_else(|| format!("interaction {label} vtable outside image"))?
                .try_into()
                .unwrap(),
        )
    };
    if address < image.as_ptr() as usize || address + 16 > image.as_ptr() as usize + image.len() {
        return Err(format!("interaction {label} target outside image"));
    }
    let target_rva = address - image.as_ptr() as usize;
    if kind == 1
        && (target_rva != 0xea24a0
            || image.get(target_rva..target_rva + 14)
                != Some(&[
                    0x48, 0x8b, 0xc4, 0x55, 0x56, 0x57, 0x41, 0x54, 0x41, 0x55, 0x41, 0x56, 0x41,
                    0x57,
                ]))
    {
        return Err(format!(
            "interaction {label} hook fingerprint mismatch at RVA {target_rva:#x}"
        ));
    }
    let callback = move |registers: *mut Registers, original: usize| -> usize {
        let r = unsafe { &*registers };
        if kind == 1 {
            let query = std::panic::catch_unwind(|| unsafe { script_query(r.r8 as usize) })
                .ok()
                .flatten();
            let result = query
                .as_ref()
                .filter(|query| matches!(query.id, 21 | 22 | 23 | 25 | 58 | 59))
                .and_then(|query| {
                    std::panic::catch_unwind(|| unsafe { query_intercept(r, query) })
                        .ok()
                        .flatten()
                });
            if let Some(result) = result {
                unsafe {
                    (r.rdx as *mut EzStateRawValue).write(EzStateValue::Int32(result).into())
                };
                r.rdx as usize
            } else {
                let call: unsafe extern "system" fn(
                    *mut CSEzStateTalkEnv,
                    *mut EzStateRawValue,
                    *const EzStateEnvironmentQuery,
                ) -> *const EzStateRawValue = unsafe { std::mem::transmute(original) };
                let result = unsafe { call(r.rcx as *mut _, r.rdx as *mut _, r.r8 as *const _) };
                if query.as_ref().is_some_and(|query| query.id == 57) {
                    let _ =
                        std::panic::catch_unwind(|| unsafe { observe_query_result(r, 57, result) });
                }
                result as usize
            }
        } else {
            let hide = active()
                && CAPTURE.try_lock().ok().is_some_and(|c| {
                    (ACTION_FMG.contains(&(r.r8 as usize))
                        && r.r9 as i32 == c.suppress_prompt
                        && c.suppress_prompt >= 0)
                        || (TALK_FMG.contains(&(r.r8 as usize))
                            && r.r9 as i32 == c.suppress_subtitle
                            && c.suppress_subtitle >= 0)
                });
            if hide {
                EMPTY.as_ptr() as usize
            } else {
                let call: unsafe extern "system" fn(usize, u32, u32, i32) -> *const u16 =
                    unsafe { std::mem::transmute(original) };
                (unsafe { call(r.rcx as usize, r.rdx as u32, r.r8 as u32, r.r9 as i32) }) as usize
            }
        }
    };
    let hook = unsafe {
        crate::hosting::hook(address, |option| {
            hook_closure_retn(address, callback, option, HookFlags::empty())
        })
    }
    .map_err(|e| format!("interaction {label} hook at RVA {target_rva:#x}: {e:?}"))?;
    std::mem::forget(hook);
    Ok(())
}

#[derive(Default)]
struct Mailbox {
    state: Option<State>,
    command: Option<Command>,
    ui: Option<Command>,
}
/// Identity of the native prompt on offer: selection, text, parameter,
/// executable and grayed state, plus the owning player and block.
type PromptKey = (usize, i32, Option<i32>, bool, bool, usize, i32);
pub struct Driver {
    pid: u32,
    session: u64,
    seq: u64,
    command_seq: u64,
    ui_seq: u64,
    ui_at: u64,
    guest_open: bool,
    ready: bool,
    interact: bool,
    prompt_key: Option<PromptKey>,
    prompt_token: u64,
    prompt_driver: crate::host_action::Driver,
    mailbox: Arc<Mutex<Mailbox>>,
    running: Arc<AtomicBool>,
    pub events: VecDeque<String>,
}
impl Driver {
    /// Call only after the engine has verified the complete supported executable.
    pub unsafe fn init() -> Result<Self, String> {
        let directory = std::env::var_os("ELDENCRAFT_CAMPAIGN_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                std::env::var_os("ELDENCRAFT_DATA_DIR")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| PathBuf::from("eldencraft-data"))
                    .join("campaign")
            });
        fs::create_dir_all(&directory)
            .map_err(|e| format!("interaction directory {}: {e}", directory.display()))?;
        let image = Program::current().image();
        if image.get(TEXT_LOOKUP..TEXT_LOOKUP + TEXT_CODE.len()) != Some(TEXT_CODE) {
            return Err(format!(
                "interaction text-lookup fingerprint mismatch at RVA {TEXT_LOOKUP:#x}"
            ));
        }
        unsafe {
            crate::campaign_runtime::install_talk_hook()?;
            install_hook(ENV_VMT, 1)?;
            install_hook(TEXT_LOOKUP, 2)?;
        }
        let pid = std::process::id();
        let session = (epoch().saturating_mul(1000) + (pid as u64 % 1000)).min(i64::MAX as u64);
        let mailbox = Arc::new(Mutex::new(Mailbox::default()));
        let ready = format!(
            "Minecraft interaction bridge ready: shared talk-event, talk-query and text-lookup hooks installed; host={}, commands={}, readiness={}",
            directory.join("interaction-host.json").display(),
            directory.join("interaction-guest.json").display(),
            directory.join("interaction-ui.json").display(),
        );
        let running = Arc::new(AtomicBool::new(true));
        let published = mailbox.clone();
        let keep = running.clone();
        std::thread::Builder::new()
            .name("interaction-mailbox".into())
            .spawn(move || {
                while keep.load(Ordering::Acquire) {
                    let command = read_command(directory.join("interaction-guest.json"));
                    let ui = read_command(directory.join("interaction-ui.json"));
                    let state = if let Ok(mut m) = published.lock() {
                        m.command = command;
                        m.ui = ui;
                        m.state.clone()
                    } else {
                        None
                    };
                    if let Some(state) = state
                        && let Some(bytes) = serde_json::to_vec(&state)
                            .ok()
                            .filter(|bytes| bytes.len() <= wire::MAX_HOST)
                    {
                        let tmp = directory.join("interaction-host.json.tmp");
                        if fs::write(&tmp, bytes).is_ok() {
                            let _ = fs::rename(tmp, directory.join("interaction-host.json"));
                        }
                    }
                    std::thread::sleep(Duration::from_millis(40));
                }
            })
            .map_err(|e| format!("interaction worker: {e}"))?;
        Ok(Self {
            pid,
            session,
            seq: 0,
            command_seq: 0,
            ui_seq: 0,
            ui_at: 0,
            guest_open: false,
            ready: false,
            interact: false,
            prompt_key: None,
            prompt_token: 0,
            prompt_driver: Default::default(),
            mailbox,
            running,
            events: VecDeque::from([ready]),
        })
    }
    pub fn guest_ready(&self) -> bool {
        self.ready && epoch().saturating_sub(self.ui_at) <= wire::FRESH_MS
    }
    pub fn blocking(&self) -> bool {
        self.guest_ready()
            && (self.guest_open
                || CAPTURE.lock().ok().is_some_and(|c| {
                    c.menu.as_ref().is_some_and(|m| m.open)
                        || c.dialog.as_ref().is_some_and(|m| m.open)
                        || c.subtitle.is_some() && unsafe { directed_talk() }
                }))
    }
    pub fn supports_blocking(&self) -> bool {
        // A stale guest-open heartbeat after selecting an unsupported submenu
        // must never authorize masking the newly opened native window.
        self.guest_ready()
            && CAPTURE.lock().ok().is_some_and(|c| {
                c.menu.as_ref().is_some_and(|m| m.open)
                    || c.dialog.as_ref().is_some_and(|m| m.open)
                    || c.subtitle.is_some() && unsafe { directed_talk() }
            })
    }
    /// Read-only admission for a still-native grace window during reacquisition.
    /// This grants no replacement input lease; the engine keeps native blocking.
    pub fn pending_grace_recovery(&self) -> bool {
        let Some(sample) = (unsafe { crate::grace_reset::sample() }).filter(|s| s.activity_ready)
        else {
            return false;
        };
        CAPTURE.lock().ok().is_some_and(|c| {
            c.pending.as_ref().is_some_and(|pending| {
                if !pending.matches(epoch(), sample) || !unsafe { live(pending.owner) } {
                    return false;
                }
                let npc = unsafe { &*(pending.owner.npc as *const CSNpcTalkIns) };
                npc.base.talk_id == pending.owner.talk
                    && npc.base.field_ins_handle == pending.owner.handle
                    && npc.menu_state.current_open_menu == MenuType::TalkList
                    && npc.menu_state.open_menu_job.finalize_callback_job.is_some()
                    && npc
                        .menu_state
                        .owner
                        .is_some_and(|owner| owner.as_ptr() as usize == pending.owner.npc)
                    && c.lists.get(&pending.owner.npc).is_some_and(|list| {
                        list.complete
                            && list.owner == Some(pending.owner)
                            && list.generation == pending.generation
                    })
            })
        })
    }
    pub fn take_interact(&mut self) -> bool {
        std::mem::take(&mut self.interact)
    }
    /// `now` is the same monotonic milliseconds used by overlay_input.
    pub unsafe fn tick(&mut self, allowed: bool, now: u64) {
        TASK_THREAD.store(unsafe { GetCurrentThreadId() } as u64, Ordering::Release);
        if let Ok(mut diagnostics) = DIAGNOSTICS.try_lock() {
            self.events.extend(diagnostics.drain(..));
            while self.events.len() > 64 {
                self.events.pop_front();
            }
        }
        let epoch_now = epoch();
        let (command, ui) = self
            .mailbox
            .lock()
            .map(|m| (m.command.clone(), m.ui.clone()))
            .unwrap_or_default();
        if let Some(ui) = ui.filter(|c| {
            wire::valid(c, self.pid, self.session, self.ui_seq, epoch_now) && c.action == "ui_state"
        }) {
            self.ui_seq = ui.seq;
            self.ui_at = ui.timestamp_ms;
            self.ready = ui.ready == Some(true);
            self.guest_open = ui.open == Some(true);
        }
        let permitted = allowed && unsafe { offline() };
        let enabled = permitted && self.guest_ready();
        DEADLINE.store(self.ui_at.saturating_add(wire::FRESH_MS), Ordering::Release);
        ACTIVE.store(enabled, Ordering::Release);
        if !enabled {
            let retain_pending = unsafe { crate::grace_reset::sample() }.is_some_and(|sample| {
                CAPTURE.lock().ok().is_some_and(|c| {
                    c.pending
                        .as_ref()
                        .is_some_and(|p| p.matches(epoch_now, sample))
                })
            });
            if retain_pending {
                unsafe { self.suspend_for_grace_reset() };
            } else {
                unsafe { self.suspend() };
            }
        } else {
            if HOLD_UNTIL.swap(0, Ordering::AcqRel) != 0 {
                self.events
                    .push_back("Interaction menu lease regained; menu kept open.".into());
            }
            unsafe { recover_pending_grace(epoch_now) };
        }
        let input = crate::overlay_input::take_menu(now);
        self.seq = self.seq.saturating_add(1).min(i64::MAX as u64);
        let mut prompt = None;
        if enabled && !self.blocking() {
            if let Some(offer) =
                unsafe { self.prompt_driver.offer() }.filter(|o| o.selected && o.text >= 0)
            {
                let player = unsafe { PlayerIns::local_player() }.ok();
                let key = player.map(|p| {
                    (
                        offer.selection,
                        offer.text,
                        offer.param,
                        offer.can_execute,
                        offer.grayed,
                        p as *const _ as usize,
                        p.current_block_id.0,
                    )
                });
                if self.prompt_key != key {
                    self.prompt_key = key;
                    self.prompt_token = token();
                }
                if let Some(text) = unsafe { crate::boss_fmg::localized(&ACTION_FMG, offer.text) } {
                    prompt = Some(Prompt {
                        token: self.prompt_token,
                        text_id: offer.text,
                        text,
                        enabled: crate::host_action::decide(offer)
                            == crate::host_action::Decision::Press,
                    });
                }
            } else {
                self.prompt_key = None;
            }
        }
        if let Some(command) = command.filter(|c| {
            wire::valid(c, self.pid, self.session, self.command_seq, epoch_now)
                && c.action != "ui_state"
        }) {
            self.command_seq = command.seq;
            if enabled {
                if command.action == "interact"
                    && prompt
                        .as_ref()
                        .is_some_and(|p| p.token == command.token && p.enabled)
                {
                    self.interact = true;
                }
                if let Ok(mut c) = CAPTURE.lock() {
                    if let Some(result) = c
                        .dialog
                        .as_ref()
                        .filter(|m| m.open)
                        .and_then(|m| wire::menu_result(&m.menu, &command))
                    {
                        unsafe {
                            close_dialog(&mut c, if command.action == "close" { 0 } else { result })
                        };
                    }
                    if c.menu
                        .as_ref()
                        .is_some_and(|m| m.open && m.menu.token == command.token)
                        && let Some(result) = c
                            .menu
                            .as_ref()
                            .and_then(|m| wire::menu_result(&m.menu, &command))
                    {
                        unsafe { close_menu(&mut c, result) };
                    }
                }
            }
        }
        let (menu, subtitle) = if let Ok(mut c) = CAPTURE.lock() {
            apply_capture_gap(&mut c, LIST_GAP.swap(0, Ordering::AcqRel));
            if c.menu.as_ref().is_some_and(|m| !unsafe { live(m.owner) }) {
                c.menu = None;
            }
            if c.dialog.as_ref().is_some_and(|m| !unsafe { live(m.owner) }) {
                c.dialog = None;
            }
            if c.subtitle.as_ref().is_some_and(|s| {
                epoch_now > s.expires
                    || !unsafe { live(s.owner) }
                    || unsafe { (*(s.owner.npc as *const CSNpcTalkIns)).base.talk_param_id }
                        != s.param
            }) {
                c.subtitle = None;
            }
            c.suppress_prompt = prompt.as_ref().map_or(-1, |p| p.text_id);
            c.suppress_subtitle = if enabled {
                c.subtitle.as_ref().map_or(-1, |s| s.msg)
            } else {
                -1
            };
            (
                c.dialog
                    .as_ref()
                    .filter(|m| m.open && enabled)
                    .or_else(|| c.menu.as_ref().filter(|m| m.open && enabled))
                    .map(|m| m.menu.clone()),
                c.subtitle.as_ref().filter(|_| enabled).map(|s| Subtitle {
                    text: s.text.clone(),
                }),
            )
        } else {
            (None, None)
        };
        let input = input
            .filter(|_| enabled)
            .map(|i| Input {
                seq: self.seq,
                buttons: i.buttons,
                pressed: i.pressed,
            })
            .unwrap_or(Input {
                seq: self.seq,
                buttons: 0,
                pressed: 0,
            });
        let mut state = State {
            version: 1,
            pid: self.pid,
            session: self.session,
            seq: self.seq,
            timestamp_ms: epoch_now,
            active: permitted,
            blocking: self.blocking(),
            prompt,
            menu,
            subtitle,
            input,
        };
        wire::bound_state(&mut state);
        if let Ok(mut c) = CAPTURE.lock() {
            if state.prompt.is_none() {
                c.suppress_prompt = -1;
            }
            if state.subtitle.is_none() {
                c.suppress_subtitle = -1;
            }
        }
        if let Ok(mut mailbox) = self.mailbox.lock() {
            mailbox.state = Some(state);
        }
    }
    /// Lease loss: an open menu is kept for a bounded gap, then cancelled.
    pub unsafe fn suspend(&mut self) {
        ACTIVE.store(false, Ordering::Release);
        self.interact = false;
        if let Ok(mut c) = CAPTURE.lock() {
            let open = menu_open(&c);
            // Online, lobby and warp transitions still cancel immediately.
            let session = unsafe { menu_session() };
            let decision = hold_open_menu(&HOLD_UNTIL, epoch(), open, session);
            match decision {
                HoldDecision::Started => self
                    .events
                    .push_back("Interaction menu held: Minecraft menu lease interrupted.".into()),
                HoldDecision::Cancel if open => self.events.push_back(
                    "Interaction menu cancelled: Minecraft menu lease was not regained.".into(),
                ),
                _ => {}
            }
            if !matches!(decision, HoldDecision::Started | HoldDecision::Holding) {
                unsafe {
                    close_menu(&mut c, -1);
                    close_dialog(&mut c, 0)
                };
            }
            c.lists.clear();
            c.pending = None;
            c.subtitle = None;
            c.suppress_prompt = -1;
            c.suppress_subtitle = -1;
        }
    }
    /// Retain passive exact ESD rows for the engine's bounded, proven rest reset.
    /// No native menu/camera/input writes occur while activity bits are absent.
    pub unsafe fn suspend_for_grace_reset(&mut self) {
        ACTIVE.store(false, Ordering::Release);
        self.interact = false;
        if let Ok(mut c) = CAPTURE.lock() {
            c.subtitle = None;
            c.suppress_prompt = -1;
            c.suppress_subtitle = -1;
        }
        // Keep the transport timestamp fresh so Java can announce readiness on
        // reacquisition. An inactive heartbeat grants no replacement ownership.
        self.seq = self.seq.saturating_add(1).min(i64::MAX as u64);
        if let Ok(mut mailbox) = self.mailbox.lock()
            && let Some(state) = &mut mailbox.state
        {
            state.seq = self.seq;
            state.timestamp_ms = epoch();
            state.active = false;
            state.blocking = false;
            state.prompt = None;
            state.menu = None;
            state.subtitle = None;
            state.input = Input {
                seq: self.seq,
                buttons: 0,
                pressed: 0,
            };
        }
    }
}
unsafe fn directed_talk() -> bool {
    unsafe { PlayerIns::local_player() }.is_ok_and(|p| p.chr_ins.chr_ctrl.disable_move)
}
impl Drop for Driver {
    fn drop(&mut self) {
        self.running.store(false, Ordering::Release);
        ACTIVE.store(false, Ordering::Release);
    }
}
fn read_command(path: PathBuf) -> Option<Command> {
    let f = fs::File::open(path).ok()?;
    if f.metadata().ok()?.len() > wire::MAX_FILE {
        return None;
    }
    let mut bytes = Vec::new();
    f.take(wire::MAX_FILE + 1).read_to_end(&mut bytes).ok()?;
    if bytes.len() > wire::MAX_FILE as usize {
        return None;
    }
    serde_json::from_slice(&bytes).ok()
}
#[cfg(test)]
mod tests {
    use super::*;
    // Deliberately different from the SDK ExternalEventTemp and query layouts.
    // The live ABI obtains all data through virtual accessors, never these fields.
    #[repr(C)]
    struct PolymorphicFixture {
        vtable: usize,
        unrelated: [u8; 40],
        id: i32,
        arity: u32,
        arguments: [EzStateRawValue; 3],
    }
    unsafe extern "system" fn fixture_id(pointer: usize) -> i32 {
        unsafe { (*(pointer as *const PolymorphicFixture)).id }
    }
    unsafe extern "system" fn fixture_count(pointer: usize) -> u32 {
        unsafe { (*(pointer as *const PolymorphicFixture)).arity }
    }
    unsafe extern "system" fn fixture_event_arg(
        pointer: usize,
        index: u32,
    ) -> *const EzStateRawValue {
        assert!((1..=3).contains(&index));
        unsafe { &(*(pointer as *const PolymorphicFixture)).arguments[index as usize - 1] }
    }
    unsafe extern "system" fn fixture_query_arg(
        pointer: usize,
        result: *mut EzStateRawValue,
        index: u32,
    ) -> *const EzStateRawValue {
        let value = unsafe { numeric_script_value(fixture_event_arg(pointer, index)) }.unwrap();
        unsafe { result.write(EzStateValue::Int32(value).into()) };
        result
    }
    fn fixture(id: i32, arity: u32) -> PolymorphicFixture {
        PolymorphicFixture {
            vtable: 0,
            unrelated: [0xff; 40],
            id,
            arity,
            arguments: [
                EzStateValue::Int32(1).into(),
                EzStateValue::Int32(15000420).into(),
                EzStateValue::Int32(-1).into(),
            ],
        }
    }
    #[test]
    fn live_event_interface_reads_polymorphic_storage_and_one_based_args() {
        let fixture = fixture(19, 4);
        let event = unsafe {
            event_from_methods(
                &fixture as *const _ as usize,
                [
                    fixture_id as *const () as usize,
                    fixture_count as *const () as usize,
                    fixture_event_arg as *const () as usize,
                ],
            )
        }
        .unwrap();
        assert_eq!(event.id, 19);
        assert_eq!(event.args, vec![Some(1), Some(15000420), Some(-1)]);
    }
    #[test]
    fn live_query_interface_uses_caller_owned_output_and_one_based_args() {
        let fixture = fixture(25, 2);
        let query = unsafe {
            query_from_methods(
                &fixture as *const _ as usize,
                [
                    fixture_id as *const () as usize,
                    fixture_count as *const () as usize,
                    fixture_query_arg as *const () as usize,
                ],
            )
        }
        .unwrap();
        assert_eq!(query.id, 25);
        assert_eq!(query.arg(0), Some(1));
        assert_eq!(query.arg(1), None);
    }
    #[test]
    fn polymorphic_interfaces_reject_unbounded_or_missing_id_arity() {
        for arity in [0, 62, u32::MAX] {
            let fixture = fixture(19, arity);
            assert!(
                unsafe {
                    event_from_methods(
                        &fixture as *const _ as usize,
                        [
                            fixture_id as *const () as usize,
                            fixture_count as *const () as usize,
                            fixture_event_arg as *const () as usize,
                        ],
                    )
                }
                .is_none()
            );
        }
        for arity in [0, 8, u32::MAX] {
            let fixture = fixture(25, arity);
            assert!(
                unsafe {
                    query_from_methods(
                        &fixture as *const _ as usize,
                        [
                            fixture_id as *const () as usize,
                            fixture_count as *const () as usize,
                            fixture_query_arg as *const () as usize,
                        ],
                    )
                }
                .is_none()
            );
        }
    }
    #[test]
    fn fresh_live_menu_observer_captures_grace_rows_despite_rejected_physics_sample() {
        let owner = Owner {
            npc: 7,
            talk: 1000,
            handle: FieldInsHandle {
                block_id: eldenring::cs::BlockId::none(),
                selector: eldenring::cs::FieldInsSelector(0),
            },
            chr: 8,
        };
        let strict_sample: Result<crate::grace_reset::Sample, _> =
            Err(crate::grace_reset::Rejection::PhysicsOwnerMismatch {
                player: 11,
                owner: 12,
            });
        assert!(strict_sample.is_err());
        assert!(menu_admitted(true, true, true));
        let mut list = List {
            owner: Some(owner),
            generation: 1,
            rows: Vec::new(),
            text_ids: BTreeMap::new(),
            complete: true,
            grace: false,
        };
        // Real ESD row IDs are decoded through the external interface. A
        // gameplay-sample rejection cannot discard this independently owned
        // source; omitted grace rows and the Ender Chest still retain slot IDs.
        for (slot, id, label) in [
            (1, 15000420, "Pass time"),
            (3, 15000371, "Flasks"),
            (4, 15000390, "Memorize spell"),
            (6, 15000395, "Sort chest"),
            (99, 20000009, "Leave"),
        ] {
            let mut fixture = fixture(19, 4);
            fixture.arguments = [
                EzStateValue::Int32(slot).into(),
                EzStateValue::Int32(id).into(),
                EzStateValue::Int32(-1).into(),
            ];
            let event = unsafe {
                event_from_methods(
                    &fixture as *const _ as usize,
                    [
                        fixture_id as *const () as usize,
                        fixture_count as *const () as usize,
                        fixture_event_arg as *const () as usize,
                    ],
                )
            }
            .unwrap();
            record_choice(
                &mut list,
                owner,
                event.arg(0).unwrap(),
                event.arg(1).unwrap(),
                Some(label.into()),
                true,
            );
        }
        assert!(list.complete);
        assert!(list.grace);
        assert_eq!(
            list.rows.iter().map(|row| row.id).collect::<Vec<_>>(),
            [1, 6, 99]
        );
        assert_eq!(list.rows[1].action.as_deref(), Some("ender_chest"));
        // Focus/heartbeat expiration, offline-session loss and failed script
        // resolution each revoke admission regardless of any rest sample.
        for gates in [
            (false, true, true),
            (true, false, true),
            (true, true, false),
        ] {
            assert!(!menu_admitted(gates.0, gates.1, gates.2));
        }
        // Even a reused NPC allocation cannot join a different character's
        // rows to the complete source needed for a later replacement Show.
        record_choice(
            &mut list,
            Owner { chr: 9, ..owner },
            1,
            15000420,
            Some("Pass time".into()),
            true,
        );
        assert!(!list.complete);
        assert_eq!(list.rows.len(), 1);
    }
    #[test]
    fn pending_grace_choices_survive_reset_but_cannot_cross_context() {
        let owner = Owner {
            npc: 7,
            talk: 1000,
            handle: FieldInsHandle {
                block_id: eldenring::cs::BlockId::none(),
                selector: eldenring::cs::FieldInsSelector(0),
            },
            chr: 8,
        };
        let resting = crate::grace_reset::Sample {
            player: 11,
            entry: 22,
            block: 33,
            feet: [0., 0., 0.],
            activity_ready: false,
            rest: Some(crate::grace_reset::Rest {
                script: 44,
                bonfire: 100001951,
            }),
        };
        let mut list = List {
            owner: Some(owner),
            generation: 55,
            complete: true,
            grace: true,
            rows: Vec::new(),
            text_ids: BTreeMap::new(),
        };
        // Actual main grace source rows retain their native result slots. The
        // live polymorphic getter provides args, even when UI admission is down.
        for (slot, id, label) in [
            (1, 15000420, "Pass time"),
            (2, 15000540, "Level Up"),
            (3, 15000371, "Flasks"),
            (4, 15000390, "Memorize spell"),
            (6, 15000395, "Sort chest"),
            (99, 20000009, "Leave"),
        ] {
            let mut fixture = fixture(19, 4);
            fixture.arguments = [
                EzStateValue::Int32(slot).into(),
                EzStateValue::Int32(id).into(),
                EzStateValue::Int32(-1).into(),
            ];
            let event = unsafe {
                event_from_methods(
                    &fixture as *const _ as usize,
                    [
                        fixture_id as *const () as usize,
                        fixture_count as *const () as usize,
                        fixture_event_arg as *const () as usize,
                    ],
                )
            }
            .unwrap();
            if !omitted_choice(event.arg(1).unwrap(), true) {
                list.rows.push(captured_choice(
                    event.arg(0).unwrap(),
                    event.arg(1).unwrap(),
                    label.into(),
                    true,
                ));
            }
        }
        assert_eq!(
            list.rows.iter().map(|row| row.id).collect::<Vec<_>>(),
            [1, 6, 99]
        );
        assert_eq!(list.rows[1].action.as_deref(), Some("ender_chest"));
        let pending = PendingShow {
            owner,
            menu: Menu {
                token: 66,
                kind: "grace".into(),
                title: "Site of Grace".into(),
                choices: list.rows.clone(),
            },
            rest: resting,
            expires: 2500,
            generation: 55,
        };
        assert!(pending.matches(1100, resting));
        let recovered = crate::grace_reset::Sample {
            activity_ready: true,
            ..resting
        };
        assert!(pending.matches(1400, recovered));
        assert!(pending.source_matches(&list));
        assert!(wire::menu_fits(&pending.menu));
        // Repeated passive observations do not extend the original deadline.
        assert!(!pending.matches(2501, recovered));
        assert!(!pending.matches(
            1400,
            crate::grace_reset::Sample {
                entry: 23,
                ..recovered
            }
        ));
        assert!(!pending.matches(
            1400,
            crate::grace_reset::Sample {
                block: 34,
                ..recovered
            }
        ));
        assert!(!pending.matches(
            1400,
            crate::grace_reset::Sample {
                rest: None,
                ..recovered
            }
        ));
        list.generation += 1;
        assert!(!pending.source_matches(&list));
        list.generation = 55;
        list.complete = false;
        assert!(!pending.source_matches(&list));
        // The grace-only request does not remove an unrelated native row.
        assert!(!omitted_choice(15000390, false));
        assert!(
            captured_choice(6, 15000395, "Quest storage".into(), false)
                .action
                .is_none()
        );
    }
    #[test]
    fn irrelevant_event_and_query_ids_skip_argument_decoding() {
        let event_fixture = fixture(2, u32::MAX);
        let event = unsafe {
            event_from_methods(
                &event_fixture as *const _ as usize,
                [
                    fixture_id as *const () as usize,
                    fixture_count as *const () as usize,
                    fixture_event_arg as *const () as usize,
                ],
            )
        }
        .unwrap();
        assert!(event.args.is_empty());
        let query_fixture = fixture(3, u32::MAX);
        let query = unsafe {
            query_from_methods(
                &query_fixture as *const _ as usize,
                [
                    fixture_id as *const () as usize,
                    fixture_count as *const () as usize,
                    fixture_query_arg as *const () as usize,
                ],
            )
        }
        .unwrap();
        assert!(query.args.is_empty());
    }
    #[test]
    fn generic_dialog_uses_real_localized_buttons_including_common_style_four() {
        let resolve = |id| match id {
            3 => Some("Yes".into()),
            4 => Some("No".into()),
            _ => None,
        };
        // Actual grace/quest script: OpenGenericDialog(8, message, 3, 4, 2).
        let choices = generic_choices(8, 3, 4, resolve).unwrap();
        assert_eq!(
            choices
                .iter()
                .map(|c| (c.id, c.text.as_str()))
                .collect::<Vec<_>>(),
            vec![(1, "Yes"), (2, "No")]
        );
        assert_eq!(generic_choices(7, 3, -1, resolve).unwrap().len(), 1);
        assert!(generic_choices(8, 3, -1, resolve).is_none());
        assert!(generic_choices(6, 3, 4, resolve).is_none());
        assert!(generic_choices(99, 3, 4, resolve).is_none());
    }
    #[test]
    fn grace_script_rows_are_filtered_even_without_rest_or_bonfire_flags() {
        let owner = Owner {
            npc: 7,
            talk: GRACE_TALK,
            handle: FieldInsHandle {
                block_id: eldenring::cs::BlockId::none(),
                selector: eldenring::cs::FieldInsSelector(0),
            },
            chr: 8,
        };
        // Live capture: rest=NetMessage/bonfire=0 left the rows recorded as
        // ordinary NPC choices; the grace talk script still identifies them.
        let mut list = new_unconfirmed_list();
        for (slot, id, label) in [
            (1, PASS_TIME, "Pass time"),
            (4, 15000390, "Memorize spell"),
            (6, 15000395, "Sort chest"),
            (99, 20000009, "Leave"),
        ] {
            record_choice(&mut list, owner, slot, id, Some(label.into()), false);
        }
        assert!(grace_source(owner.talk, None));
        let rows = grace_choices(&list);
        assert_eq!(
            rows.iter()
                .map(|r| (r.id, r.text.as_str()))
                .collect::<Vec<_>>(),
            [(1, "Pass time"), (6, "Ender Chest"), (99, "Leave")]
        );
        assert_eq!(rows[1].action.as_deref(), Some("ender_chest"));
        // Merchants and other NPC scripts are not graces.
        assert!(!grace_source(800006000, Some(20000010)));
        assert!(grace_source(800006000, Some(PASS_TIME)));
    }
    #[test]
    fn merchant_offers_one_shop_row_and_never_the_native_sell_menu() {
        let owner = Owner {
            npc: 7,
            talk: 800006000,
            handle: FieldInsHandle {
                block_id: eldenring::cs::BlockId::none(),
                selector: eldenring::cs::FieldInsSelector(0),
            },
            chr: 8,
        };
        let mut list = new_unconfirmed_list();
        for (slot, id, label) in [
            (1, PURCHASE, "Purchase"),
            (2, SELL, "Sell"),
            (3, 20000000, "Talk"),
            (99, 20000009, "Leave"),
        ] {
            record_choice(&mut list, owner, slot, id, Some(label.into()), false);
        }
        // The Shop row keeps Purchase's slot so the script still opens its shop.
        assert_eq!(
            list.rows
                .iter()
                .map(|r| (r.id, r.text.as_str()))
                .collect::<Vec<_>>(),
            [(1, "Shop"), (3, "Talk"), (99, "Leave")]
        );
        assert!(list.rows.iter().all(|r| r.action.is_none()));
    }
    #[test]
    fn open_menu_survives_a_bounded_lease_gap_then_cancels() {
        let hold = AtomicU64::new(0);
        assert_eq!(
            hold_open_menu(&hold, 1000, true, true),
            HoldDecision::Started
        );
        assert_eq!(
            hold_open_menu(&hold, 1000 + MENU_HOLD_MS, true, true),
            HoldDecision::Holding
        );
        assert_eq!(
            hold_open_menu(&hold, 1001 + MENU_HOLD_MS, true, true),
            HoldDecision::Cancel
        );
        assert_eq!(hold.load(Ordering::Acquire), 0);
        // Online/lobby/warp transitions cancel without a grace period.
        assert_eq!(
            hold_open_menu(&hold, 5000, true, false),
            HoldDecision::Cancel
        );
        assert_eq!(
            hold_open_menu(&hold, 5000, false, true),
            HoldDecision::Release
        );
        assert_eq!(hold.load(Ordering::Acquire), 0);
    }
    #[test]
    fn missed_source_row_invalidates_list_until_a_new_complete_capture() {
        let mut capture = Capture {
            lists: BTreeMap::from([(
                7,
                List {
                    owner: None,
                    generation: 0,
                    rows: vec![Choice {
                        id: 1,
                        text: "Talk".into(),
                        enabled: true,
                        action: None,
                    }],
                    text_ids: BTreeMap::new(),
                    complete: true,
                    grace: false,
                },
            )]),
            menu: None,
            dialog: None,
            pending: None,
            subtitle: None,
            suppress_prompt: -1,
            suppress_subtitle: -1,
        };
        assert_eq!(capture_gap_mask(19, None), 1);
        assert_eq!(capture_gap_mask(149, None), 1);
        assert_eq!(capture_gap_mask(20, None), 1);
        assert_eq!(capture_gap_mask(10, Some(1)), 15);
        assert_eq!(capture_gap_mask(10, Some(3)), 15);
        assert_eq!(capture_gap_mask(150, Some(0)), 15);
        assert_eq!(capture_gap_mask(150, Some(1)), 15);
        assert_eq!(capture_gap_mask(150, Some(2)), 15);
        assert_eq!(capture_gap_mask(17, None), 14);
        assert_eq!(capture_gap_mask(22, None), 0);
        apply_capture_gap(&mut capture, 1);
        assert!(capture.lists.is_empty());
        let rebuilt = capture.lists.entry(7).or_insert_with(new_unconfirmed_list);
        rebuilt.rows.push(Choice {
            id: 2,
            text: "Leave".into(),
            enabled: true,
            action: None,
        });
        assert!(!rebuilt.complete);
    }
    #[test]
    fn cancelled_menu_retains_native_result_until_script_rebuilds_list() {
        let owner = Owner {
            npc: 0,
            talk: 1,
            handle: FieldInsHandle {
                block_id: eldenring::cs::BlockId::none(),
                selector: eldenring::cs::FieldInsSelector(0),
            },
            chr: 0,
        };
        let menu = Menu {
            token: 4,
            kind: "npc".into(),
            title: "NPC".into(),
            choices: Vec::new(),
        };
        let mut capture = Capture {
            lists: BTreeMap::new(),
            menu: Some(OwnedMenu {
                owner,
                menu,
                result: None,
                open: false,
                native_type: MenuType::TalkList,
            }),
            dialog: None,
            pending: None,
            subtitle: None,
            suppress_prompt: -1,
            suppress_subtitle: -1,
        };
        unsafe { close_menu(&mut capture, -1) };
        assert_eq!(capture.menu.as_ref().unwrap().result, Some(-1));
        assert!(!capture.menu.as_ref().unwrap().open);
        // Repeated gate loss cannot turn cancellation into an accepted choice.
        unsafe { close_menu(&mut capture, -1) };
        assert_eq!(capture.menu.as_ref().unwrap().result, Some(-1));
    }
    #[test]
    fn malformed_and_oversized_worker_commands_are_never_queued() {
        let root = std::env::temp_dir().join(format!(
            "eldencraft-interaction-test-{}",
            std::process::id()
        ));
        fs::create_dir_all(&root).unwrap();
        let file = root.join("command.json");
        fs::write(&file, b"{\"action\":\"select\"}").unwrap();
        assert!(read_command(file.clone()).is_none());
        fs::write(&file, vec![b' '; wire::MAX_FILE as usize + 1]).unwrap();
        assert!(read_command(file.clone()).is_none());
        fs::remove_file(file).unwrap();
        fs::remove_dir(root).unwrap();
    }
    #[test]
    fn accepted_choice_survives_focus_or_guest_gate_loss_until_esd_reads_it() {
        let owner = Owner {
            npc: 0,
            talk: 1,
            handle: FieldInsHandle {
                block_id: eldenring::cs::BlockId::none(),
                selector: eldenring::cs::FieldInsSelector(0),
            },
            chr: 0,
        };
        let menu = Menu {
            token: 4,
            kind: "npc".into(),
            title: "NPC".into(),
            choices: Vec::new(),
        };
        let mut capture = Capture {
            lists: BTreeMap::new(),
            menu: Some(OwnedMenu {
                owner,
                menu: menu.clone(),
                result: Some(7),
                open: false,
                native_type: MenuType::TalkList,
            }),
            dialog: Some(OwnedMenu {
                owner,
                menu,
                result: Some(1),
                open: false,
                native_type: MenuType::GenericDialog,
            }),
            pending: None,
            subtitle: None,
            suppress_prompt: -1,
            suppress_subtitle: -1,
        };
        unsafe {
            close_menu(&mut capture, -1);
            close_dialog(&mut capture, 0);
        }
        assert_eq!(capture.menu.as_ref().unwrap().result, Some(7));
        assert_eq!(capture.dialog.as_ref().unwrap().result, Some(1));
        apply_capture_gap(&mut capture, 1);
        assert_eq!(capture.menu.as_ref().unwrap().result, Some(7));
        apply_capture_gap(&mut capture, 3);
        assert!(capture.menu.is_none());
        release_closed_contexts(&mut capture, 9);
        assert!(capture.dialog.is_some());
        release_closed_contexts(&mut capture, 0);
        assert!(capture.dialog.is_none());
    }
    #[test]
    fn unowned_native_show_revokes_accepted_results_before_forwarding() {
        let owner = Owner {
            npc: 0,
            talk: 1,
            handle: FieldInsHandle {
                block_id: eldenring::cs::BlockId::none(),
                selector: eldenring::cs::FieldInsSelector(0),
            },
            chr: 0,
        };
        for id in [10, 17, 76, 150] {
            let menu = Menu {
                token: 4,
                kind: "npc".into(),
                title: "NPC".into(),
                choices: Vec::new(),
            };
            let mut capture = Capture {
                lists: BTreeMap::new(),
                pending: None,
                subtitle: None,
                suppress_prompt: -1,
                suppress_subtitle: -1,
                menu: Some(OwnedMenu {
                    owner,
                    menu: menu.clone(),
                    result: Some(7),
                    open: false,
                    native_type: MenuType::TalkList,
                }),
                dialog: Some(OwnedMenu {
                    owner,
                    menu,
                    result: Some(1),
                    open: false,
                    native_type: MenuType::GenericDialog,
                }),
            };
            let gap = AtomicU64::new(0);
            // No source owner exists here and no SDK/native state may be read.
            // false is the shared dispatcher's instruction to call the original.
            assert!(!forward_unowned_show(&gap, id));
            apply_capture_gap(&mut capture, gap.swap(0, Ordering::AcqRel));
            assert!(capture.menu.is_none());
            assert!(capture.dialog.is_none());
            assert_eq!(gap.load(Ordering::Acquire), 0);
        }
        let gap = AtomicU64::new(0);
        assert!(!forward_unowned_show(&gap, 19));
        assert_eq!(gap.load(Ordering::Acquire), 0);
    }
}
