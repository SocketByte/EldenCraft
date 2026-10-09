//! Campaign data and validation shared with the Minecraft JSON configuration.
//! No game pointers or filesystem work in the pure balance/transaction model.
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub schema_version: u32,
    pub enabled: bool,
    pub progression: Progression,
    pub bosses: Vec<Boss>,
    #[serde(default)]
    pub shops: Vec<Shop>,
    #[serde(default)]
    pub combat: Combat,
    #[serde(default)]
    pub stamina: GuardCosts,
    #[serde(default)]
    pub experience: Experience,
    #[serde(default)]
    pub native_save_path: Option<String>,
}
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Progression {
    pub shares: usize,
    pub start_vigor: f64,
    pub end_vigor: f64,
    pub start_endurance: f64,
    pub end_endurance: f64,
    pub base_minecraft_health: f64,
    pub health_curve: Vec<Point>,
    pub stamina_curve: Vec<Point>,
}
#[derive(Clone, Debug, Deserialize)]
pub struct Point {
    pub level: f64,
    pub value: f64,
}
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Boss {
    pub id: String,
    pub event_flag: u32,
    #[serde(default)]
    pub remembrance: bool,
}
#[derive(Clone, Debug, Deserialize)]
pub struct Shop {
    pub id: String,
    pub title: String,
    pub merchant_ids: Vec<String>,
    pub offers: Vec<Offer>,
}
#[derive(Clone, Debug, Deserialize)]
pub struct Offer {
    pub id: String,
    pub price: u32,
    #[serde(default = "unlimited")]
    pub stock: i32,
    #[serde(default)]
    pub unlock_any: Vec<String>,
    #[serde(default)]
    pub unlock_all: Vec<String>,
    #[serde(default)]
    pub native_item_lot: Option<u32>,
}
fn unlimited() -> i32 {
    -1
}
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Combat {
    pub native_damage_scale: f32,
    pub native_incoming_damage_scale: f64,
    pub native_enemy_damage_multipliers: BTreeMap<String, f64>,
}
impl Default for Combat {
    fn default() -> Self {
        Self {
            native_damage_scale: 25.,
            native_incoming_damage_scale: 1.,
            native_enemy_damage_multipliers: BTreeMap::new(),
        }
    }
}
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct GuardCosts {
    pub guard_base: f64,
    pub guard_per_damage: f64,
}
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Experience {
    pub mob_base: u64,
    pub mob_per_native_hp: f64,
    pub max_per_kill: u64,
}
impl Default for Experience {
    fn default() -> Self {
        Self {
            mob_base: 3,
            mob_per_native_hp: 0.005,
            max_per_kill: 100,
        }
    }
}
impl Default for GuardCosts {
    fn default() -> Self {
        Self {
            guard_base: 4.,
            guard_per_damage: 1.5,
        }
    }
}

impl Config {
    pub fn validate(&self) -> Result<(), String> {
        if self.schema_version != 1 {
            return Err("campaign schemaVersion must be 1".into());
        }
        if self.progression.shares == 0 || self.progression.shares > 1024 {
            return Err("progression shares outside 1..1024".into());
        }
        if self.bosses.iter().filter(|b| b.remembrance).count() != self.progression.shares {
            return Err("Remembrance bosses must match progression.shares".into());
        }
        if !(1. ..=1024.).contains(&self.progression.base_minecraft_health) {
            return Err("invalid baseMinecraftHealth".into());
        }
        for (curve, a, b) in [
            (
                &self.progression.health_curve,
                self.progression.start_vigor,
                self.progression.end_vigor,
            ),
            (
                &self.progression.stamina_curve,
                self.progression.start_endurance,
                self.progression.end_endurance,
            ),
        ] {
            if curve.len() < 2
                || curve.len() > 200
                || !a.is_finite()
                || !b.is_finite()
                || a > b
                || !(1. ..=99.).contains(&a)
                || !(1. ..=99.).contains(&b)
                || a < curve[0].level
                || b > curve[curve.len() - 1].level
            {
                return Err("capacity curve does not cover configured range".into());
            }
            if curve.iter().any(|p| {
                !p.level.is_finite() || !p.value.is_finite() || !(1. ..=100000.).contains(&p.value)
            }) || curve
                .windows(2)
                .any(|p| p[0].level >= p[1].level || p[0].value > p[1].value)
            {
                return Err(
                    "capacity curves must have increasing levels and nondecreasing positive values"
                        .into(),
                );
            }
        }
        if !self.combat.native_damage_scale.is_finite()
            || !(0.1..=100000.).contains(&self.combat.native_damage_scale)
        {
            return Err("nativeDamageScale outside 0.1..100000".into());
        }
        if !self.combat.native_incoming_damage_scale.is_finite()
            || !(0.1..=1000.).contains(&self.combat.native_incoming_damage_scale)
        {
            return Err("invalid nativeIncomingDamageScale".into());
        }
        if self.combat.native_enemy_damage_multipliers.len() > 512
            || self
                .combat
                .native_enemy_damage_multipliers
                .iter()
                .any(|(id, factor)| {
                    !id.parse::<i32>()
                        .is_ok_and(|n| n >= 0 && n.to_string() == *id)
                        || !factor.is_finite()
                        || !(0.01..=1000.).contains(factor)
                })
        {
            return Err("nativeEnemyDamageMultipliers requires nonnegative NPC param IDs and factors 0.01..1000".into());
        }
        if !self.stamina.guard_base.is_finite()
            || !(0. ..=10000.).contains(&self.stamina.guard_base)
            || !self.stamina.guard_per_damage.is_finite()
            || !(0. ..=10000.).contains(&self.stamina.guard_per_damage)
        {
            return Err("invalid guard costs".into());
        }
        if self.experience.mob_base > 1000000
            || !self.experience.mob_per_native_hp.is_finite()
            || !(0. ..=1000.).contains(&self.experience.mob_per_native_hp)
            || self.experience.max_per_kill > 1000000
        {
            return Err("invalid native experience rewards".into());
        }
        let ids: BTreeSet<_> = self.bosses.iter().map(|b| b.id.as_str()).collect();
        let flags: BTreeSet<_> = self.bosses.iter().map(|b| b.event_flag).collect();
        if ids.len() != self.bosses.len()
            || flags.len() != self.bosses.len()
            || self
                .bosses
                .iter()
                .any(|b| b.id.is_empty() || b.event_flag == 0)
        {
            return Err("boss ids and eventFlag values must be unique and nonzero".into());
        }
        if interpolate(&self.progression.health_curve, self.progression.end_vigor)
            / self.native_hp_per_minecraft_hp()
            > 1024.
        {
            return Err("final Minecraft health exceeds 1024".into());
        }
        let mut all_offers = BTreeSet::new();
        let mut shop_ids = BTreeSet::new();
        for shop in &self.shops {
            if shop.id.is_empty() || shop.merchant_ids.is_empty() {
                return Err("shop requires id and merchant_ids".into());
            }
            if !shop_ids.insert(shop.id.as_str()) {
                return Err("duplicate shop id".into());
            }
            let offers: BTreeSet<_> = shop.offers.iter().map(|o| o.id.as_str()).collect();
            if offers.len() != shop.offers.len() {
                return Err("duplicate shop offer id".into());
            }
            for offer in &shop.offers {
                if !all_offers.insert(offer.id.as_str()) {
                    return Err("offer ids must be globally unique".into());
                }
                if offer.price > 999999999 || offer.stock > 1000000 {
                    return Err("offer price or stock outside supported bounds".into());
                }
                if offer
                    .native_item_lot
                    .is_some_and(|n| n == 0 || n > i32::MAX as u32)
                {
                    return Err("native_item_lot must name a positive existing native lot".into());
                }
                if offer.id.is_empty()
                    || offer.stock < -1
                    || offer
                        .unlock_all
                        .iter()
                        .chain(&offer.unlock_any)
                        .any(|id| !ids.contains(id.as_str()))
                {
                    return Err("invalid offer stock, id, or boss gate".into());
                }
            }
        }
        Ok(())
    }
    pub fn shop(&self, merchant: &str) -> Option<&Shop> {
        self.shops
            .iter()
            .find(|s| s.merchant_ids.iter().any(|id| id == merchant))
            .or_else(|| {
                self.shops
                    .iter()
                    .find(|s| s.merchant_ids.iter().any(|id| id == "*"))
            })
    }
    pub fn native_hp_per_minecraft_hp(&self) -> f64 {
        interpolate(&self.progression.health_curve, self.progression.start_vigor)
            / self.progression.base_minecraft_health
    }
    pub fn enemy_damage_multiplier(&self, npc_param_id: i32) -> f64 {
        self.combat
            .native_enemy_damage_multipliers
            .get(&npc_param_id.to_string())
            .copied()
            .unwrap_or(1.)
    }
    pub fn capacities(&self, defeated: &BTreeSet<String>) -> (i32, i32) {
        let n = self
            .bosses
            .iter()
            .filter(|b| b.remembrance && defeated.contains(&b.id))
            .count()
            .min(self.progression.shares);
        let p = n as f64 / self.progression.shares as f64;
        let hp = interpolate(
            &self.progression.health_curve,
            self.progression.start_vigor
                + p * (self.progression.end_vigor - self.progression.start_vigor),
        );
        let stamina = interpolate(
            &self.progression.stamina_curve,
            self.progression.start_endurance
                + p * (self.progression.end_endurance - self.progression.start_endurance),
        );
        (hp.round() as i32, stamina.round() as i32)
    }
}
pub fn interpolate(curve: &[Point], level: f64) -> f64 {
    if level <= curve[0].level {
        return curve[0].value;
    }
    for pair in curve.windows(2) {
        if level <= pair[1].level {
            return pair[0].value
                + (pair[1].value - pair[0].value) * (level - pair[0].level)
                    / (pair[1].level - pair[0].level);
        }
    }
    curve.last().unwrap().value
}
/// Equipped armor contributes additive percentage points of damage reduction.
/// The reduction is independent of hit size, toughness and maximum health.
pub fn damage_after_armor(raw: f64, reduction_percent: f64) -> f64 {
    raw * (1. - reduction_percent.clamp(0., 100.) / 100.)
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Merchant {
    pub id: String,
    pub name: String,
    pub token: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Ack {
    pub id: String,
    pub status: String,
    pub amount: u32,
    #[serde(default)]
    pub reason: String,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Request {
    pub version: u32,
    pub session: u64,
    pub character: String,
    #[serde(default)]
    pub timestamp_ms: u64,
    pub id: String,
    pub action: String,
    #[serde(default, alias = "merchant")]
    pub merchant_token: String,
    #[serde(default)]
    pub offer: String,
    #[serde(default)]
    pub quantity: u32,
    #[serde(default)]
    pub amount: u32,
}
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct Character {
    #[serde(default)]
    pub defeated: BTreeSet<String>,
    #[serde(default)]
    pub stock: BTreeMap<String, u32>,
}
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct Ledger {
    #[serde(default)]
    pub characters: BTreeMap<String, Character>,
    #[serde(default)]
    pub transactions: BTreeMap<String, Ack>,
    #[serde(default)]
    pub requests: BTreeMap<String, Request>,
}

pub fn purchase(
    config: &Config,
    req: &Request,
    character: &Character,
    merchant: &Merchant,
    runes: u32,
) -> Result<u32, &'static str> {
    if req.version != 1
        || req.id.len() != 36
        || !req.id.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')
        || req.quantity == 0
        || req.quantity > 64
        || req.merchant_token != merchant.token
    {
        return Err("invalid transaction or merchant token");
    }
    let shop = config.shop(&merchant.id).ok_or("unknown merchant")?;
    let offer = shop
        .offers
        .iter()
        .find(|o| o.id == req.offer)
        .ok_or("unknown offer")?;
    if offer.native_item_lot.is_some() && req.quantity != 1 {
        return Err("native lots require quantity 1");
    }
    if (!offer.unlock_any.is_empty()
        && !offer
            .unlock_any
            .iter()
            .any(|id| character.defeated.contains(id)))
        || !offer
            .unlock_all
            .iter()
            .all(|id| character.defeated.contains(id))
    {
        return Err("boss requirement not met");
    }
    let key = format!("{}/{}", shop.id, offer.id);
    if offer.stock >= 0
        && character
            .stock
            .get(&key)
            .copied()
            .unwrap_or(0)
            .saturating_add(req.quantity)
            > offer.stock as u32
    {
        return Err("sold out");
    }
    let amount = offer
        .price
        .checked_mul(req.quantity)
        .ok_or("price overflow")?;
    if req.amount != amount || amount > runes {
        return Err("price mismatch or insufficient runes");
    }
    Ok(amount)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn config() -> Config {
        let mut result:Config=serde_json::from_str(r#"{"schemaVersion":1,"enabled":true,"progression":{"shares":15,"startVigor":10,"endVigor":60,"startEndurance":10,"endEndurance":30,"baseMinecraftHealth":20,"healthCurve":[{"level":10,"value":414},{"level":60,"value":1900}],"staminaCurve":[{"level":10,"value":96},{"level":30,"value":130}]},"bosses":[{"id":"godrick","eventFlag":9100,"remembrance":true}],"shops":[{"id":"kale","title":"Kale","merchant_ids":["100500"],"offers":[{"id":"food","price":100,"stock":2,"unlock_all":["godrick"]}]}]}"#).unwrap();
        for n in 1..15 {
            result.bosses.push(Boss {
                id: format!("boss{n}"),
                event_flag: 9100 + n,
                remembrance: true,
            });
        }
        result
    }
    #[test]
    fn stormveil_encounters_have_distinct_completion_flags_and_capacity_credit() {
        let c: Config = serde_json::from_str(include_str!("../../config/campaign.json")).unwrap();
        c.validate().unwrap();
        let margit = c.bosses.iter().find(|b| b.id == "margit").unwrap();
        let godrick = c.bosses.iter().find(|b| b.id == "godrick").unwrap();
        assert_eq!(margit.event_flag, 10000850);
        assert_eq!(godrick.event_flag, 10000800);
        assert!(!margit.remembrance);
        assert!(godrick.remembrance);
        let defeated = BTreeSet::from(["margit".into()]);
        assert_eq!(c.capacities(&defeated), c.capacities(&BTreeSet::new()));
        let both = BTreeSet::from(["margit".into(), "godrick".into()]);
        assert!(c.capacities(&both).0 > c.capacities(&defeated).0);
    }
    #[test]
    fn proportional_capacity_and_fixed_units() {
        let c = config();
        c.validate().unwrap();
        assert_eq!(c.capacities(&BTreeSet::new()), (414, 96));
        assert_eq!(c.capacities(&BTreeSet::from(["godrick".into()])), (513, 98));
        assert_eq!(c.native_hp_per_minecraft_hp(), 20.7);
    }
    #[test]
    fn authoritative_price_boss_and_stock() {
        let c = config();
        let m = Merchant {
            id: "100500".into(),
            name: "Kale".into(),
            token: "lease".into(),
        };
        let mut r = Request {
            version: 1,
            session: 1,
            character: "slot".into(),
            timestamp_ms: 0,
            id: "12345678-1234-1234-1234-123456789abc".into(),
            action: "purchase".into(),
            merchant_token: "lease".into(),
            offer: "food".into(),
            quantity: 1,
            amount: 100,
        };
        let mut ch = Character::default();
        assert!(purchase(&c, &r, &ch, &m, 1000).is_err());
        ch.defeated.insert("godrick".into());
        assert_eq!(purchase(&c, &r, &ch, &m, 1000), Ok(100));
        r.amount = 1;
        assert!(purchase(&c, &r, &ch, &m, 1000).is_err());
        r.amount = 100;
        ch.stock.insert("kale/food".into(), 2);
        assert!(purchase(&c, &r, &ch, &m, 1000).is_err());
    }
    #[test]
    fn malformed_curve_rejected() {
        let mut c = config();
        c.progression.health_curve[1].level = 10.;
        assert!(c.validate().is_err());
    }
    #[test]
    fn exact_merchant_overrides_an_earlier_wildcard_shop() {
        let mut c = config();
        let fallback = Shop {
            id: "fallback".into(),
            title: "Travel Supplies".into(),
            merchant_ids: vec!["*".into()],
            offers: Vec::new(),
        };
        c.shops.insert(0, fallback);
        c.validate().unwrap();
        assert_eq!(c.shop("100500").unwrap().id, "kale");
        assert_eq!(c.shop("unconfigured-merchant").unwrap().id, "fallback");
    }
    #[test]
    fn ambiguous_offer_ids_and_incomplete_remembrance_rosters_are_rejected() {
        let mut c = config();
        let mut second = c.shops[0].clone();
        second.id = "second".into();
        second.merchant_ids = vec!["100600".into()];
        c.shops.push(second);
        assert_eq!(
            c.validate().unwrap_err(),
            "offer ids must be globally unique"
        );
        c.shops.pop();
        c.bosses.pop();
        assert_eq!(
            c.validate().unwrap_err(),
            "Remembrance bosses must match progression.shares"
        );
    }
    #[test]
    fn bundled_campaign_reaches_exactly_the_requested_endpoints() {
        let c: Config = serde_json::from_str(include_str!("../../config/campaign.json")).unwrap();
        c.validate().unwrap();
        let defeated: BTreeSet<_> = c
            .bosses
            .iter()
            .filter(|b| b.remembrance)
            .map(|b| b.id.clone())
            .collect();
        assert_eq!(defeated.len(), 15);
        assert_eq!(c.capacities(&defeated), (2314, 90));
        assert_eq!(c.capacities(&BTreeSet::new()), (828, 50));
        let ordinary: BTreeSet<_> = c
            .bosses
            .iter()
            .filter(|b| !b.remembrance)
            .map(|b| b.id.clone())
            .collect();
        assert_eq!(c.capacities(&ordinary), (828, 50));
    }
    #[test]
    fn armor_percentages_reduce_small_and_large_hits_equally() {
        assert_eq!(damage_after_armor(100., 10. + 5.), 85.);
        assert_eq!(damage_after_armor(10., 15.), 8.5);
        assert_eq!(damage_after_armor(1000., 15.), 850.);
        assert_eq!(damage_after_armor(100., 15.5), 84.5);
        assert_eq!(damage_after_armor(100., 0.), 100.);
        assert_eq!(damage_after_armor(100., 100.), 0.);
        assert_eq!(damage_after_armor(100., 140.), 0.);
        assert_eq!(damage_after_armor(100., -5.), 100.);
    }
    #[test]
    fn per_enemy_damage_tuning_requires_exact_valid_npc_ids_and_bounded_factors() {
        let mut c = config();
        c.combat
            .native_enemy_damage_multipliers
            .insert("100000".into(), 3.);
        c.validate().unwrap();
        assert_eq!(c.enemy_damage_multiplier(100000), 3.);
        assert_eq!(c.enemy_damage_multiplier(100001), 1.);
        c.combat
            .native_enemy_damage_multipliers
            .insert("100000".into(), 0.009);
        assert!(c.validate().is_err());
        c.combat.native_enemy_damage_multipliers.clear();
        c.combat
            .native_enemy_damage_multipliers
            .insert("100000.0".into(), 3.);
        assert!(c.validate().is_err());
    }
}
