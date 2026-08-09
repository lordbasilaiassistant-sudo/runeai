"""
Combat engine.

combat_rates(): closed-form expected rates for the planner (fast).
boss_fight_mc(): tick-level Monte Carlo with the real PvM layer —
  * multi-style attack sets (Graardor mains melee, minions crossfire range/mage)
  * protection prayers: block blockable attacks of the chosen style; prayer
    points drain while an overhead is up; P2P restores with prayer potions,
    F2P has NO prayer potions (overheads until points die, then raw tanking)
  * combat potions boost str/att
  * mechanics: avoidable AoE tiles / stand-on-safe / ground poison / typeless
    chip — avoidance follows a learning curve on kill count, so low-level
    bosses genuinely train the account before hard ones stop punishing it
"""
import math
import random
from dataclasses import dataclass, field

from osrs_math import (attack_roll, dps, hit_chance, max_hit,
                       npc_defence_roll, player_defence_roll,
                       MELEE_XP_PER_DMG, HP_XP_PER_DMG, TICK)
from data import BANK_TRIP_S, PRAYER_DRAIN_S


@dataclass
class PlayerState:
    att: int = 60
    str_: int = 60
    def_: int = 60
    hp: int = 60
    prayer: int = 43
    xp: dict = field(default_factory=dict)
    weapon: dict = None
    armour: dict = None
    food: dict = None
    gp: int = 0
    members: bool = False
    ironman: bool = False
    bonus_att: int = 0        # from unlocks: barrows gloves, capes...
    bonus_str: int = 0
    pray_att_mult: float = 1.0  # Piety-style prayers once unlocked
    pray_str_mult: float = 1.0
    ranged: int = 1
    rweapon: dict = None      # owned ranged setup, or None


def _boosted(p: PlayerState):
    """Potion boosts (super combat P2P / str pot F2P) + prayer multipliers."""
    if p.members:
        a = p.att + 5 + int(p.att * 0.15)
        s_ = p.str_ + 5 + int(p.str_ * 0.15)
    else:
        a, s_ = p.att, p.str_ + 3 + int(p.str_ * 0.10)
    return int(a * p.pray_att_mult), int(s_ * p.pray_str_mult)


def _watt(p):
    return p.weapon["att"] + p.bonus_att


def _wstr(p):
    return p.weapon["str_"] + p.bonus_str


def best_offense(p: PlayerState, mon: dict):
    """Pick melee vs ranged per target: (max_hit, hit_chance, speed, style)."""
    b_att, b_str = _boosted(p)
    m_h = max_hit(b_str, _wstr(p))
    m_c = hit_chance(attack_roll(b_att, _watt(p)),
                     npc_defence_roll(mon["def_"], mon["style_def"]))
    best = (m_h, m_c, p.weapon["speed"], "melee")
    if p.rweapon:
        r_h = max_hit(p.ranged, p.rweapon["r_str"])
        r_c = hit_chance(attack_roll(p.ranged, p.rweapon["r_att"] + p.bonus_att),
                         npc_defence_roll(mon["def_"], mon.get("style_def_range", mon["style_def"])))
        r_dps = r_c * r_h / p.rweapon["speed"]
        m_dps = m_c * m_h / p.weapon["speed"]
        if r_dps > m_dps:
            best = (r_h, r_c, p.rweapon["speed"], "ranged")
    return best


def combat_rates(p: PlayerState, mon: dict, food_slots: int = 24) -> dict:
    mhit, p_hit, spd, style = best_offense(p, mon)
    out_dps = dps(p_hit, mhit, spd)
    if out_dps <= 0:
        return dict(feasible=False)
    kill_s = mon["hp"] / out_dps + mon["find_s"]

    m_chance = hit_chance(attack_roll(mon["att"], 0),
                          player_defence_roll(p.def_, p.armour["def_"]))
    in_dps = dps(m_chance, mon["max_hit"], mon["speed"])
    net_in = max(in_dps - 0.017, 0.001)
    trip_s = min((food_slots * p.food["heal"] + p.hp * 0.7) / net_in, 3 * 3600)
    eff = trip_s / (trip_s + BANK_TRIP_S)
    kills_hr = 3600 * eff / kill_s
    food_hr = 3600 * eff * net_in / p.food["heal"]
    entry = mon.get("entry_cost", 0)
    gp_hr = kills_hr * (mon["avg_drop"] - entry) - food_hr * p.food["cost"]
    dmg_hr = kills_hr * mon["hp"]
    return dict(feasible=True, p_hit=p_hit, max_hit=mhit, kill_s=kill_s,
                kills_hr=kills_hr, gp_hr=gp_hr, xp_hr=dmg_hr * MELEE_XP_PER_DMG,
                hp_xp_hr=dmg_hr * HP_XP_PER_DMG, food_hr=food_hr)


def _avoid_prob(mech: dict, kills: int) -> float:
    base = mech.get("base_avoid", 0.0)
    k = mech.get("learn_k", 25)
    return 1 - (1 - base) * math.exp(-kills / k)


def boss_fight_mc(p: PlayerState, mon: dict, kills_so_far: int = 0,
                  trials: int = 400, food_slots: int = 22,
                  pray_pots: int = 4) -> dict:
    """One fight per trial. Prayer policy: protect vs the biggest blockable
    threat (switching bosses handled via switch_styles: correct-switch odds
    follow the same learning curve). F2P: no prayer potions, ever."""
    attacks = mon.get("attacks") or [dict(style="melee", max_hit=mon["max_hit"],
                                          speed=mon["speed"], blockable=True)]
    mechanics = mon.get("mechanics", [])
    mhit, p_chance, pspeed, pstyle = best_offense(p, mon)
    # protect the style with the highest blockable expected dps
    def exp_dps(a):
        ch = hit_chance(attack_roll(mon["att"], 0),
                        player_defence_roll(p.def_, p.armour["def_"]))
        return ch * a["max_hit"] / 2 / (a["speed"] * TICK)
    blockables = [a for a in attacks if a["blockable"]]
    protected_style = max(blockables, key=exp_dps)["style"] if blockables else None
    switch_ok = _avoid_prob(dict(base_avoid=0.3, learn_k=30), kills_so_far) \
        if mon.get("switch_styles") else 1.0

    wins, deaths, times, foods = 0, 0, [], []
    for _ in range(trials):
        php, bhp = p.hp, mon["hp"]
        ppoints = p.prayer  # prayer points
        food = food_slots
        ppots = pray_pots if p.members else 0   # F2P: no prayer potions exist
        cds = [0] * len(attacks)
        mech_t = [random.uniform(0, m["period_s"]) for m in mechanics]
        p_cd, t = 0, 0
        while php > 0 and bhp > 0 and t < 4000:
            sec = t * TICK
            praying = ppoints > 0
            # player attack
            if p_cd <= 0:
                if random.random() < p_chance:
                    bhp -= random.randint(0, mhit)
                p_cd = pspeed
            # boss/minion attacks
            for i, a in enumerate(attacks):
                if cds[i] <= 0:
                    blocked = (praying and a["blockable"]
                               and a["style"] == protected_style
                               and random.random() < switch_ok)
                    if not blocked:
                        ch = hit_chance(attack_roll(mon["att"], 0),
                                        player_defence_roll(p.def_, p.armour["def_"]))
                        if random.random() < ch:
                            php -= random.randint(0, a["max_hit"])
                    cds[i] = a["speed"]
                cds[i] -= 1
            # mechanics
            for i, m in enumerate(mechanics):
                if sec >= mech_t[i]:
                    mech_t[i] += m["period_s"]
                    if m["type"] == "typeless" or random.random() > _avoid_prob(m, kills_so_far):
                        php -= m["dmg"]
            # prayer drain / restore
            if praying:
                ppoints -= TICK / PRAYER_DRAIN_S
                if ppoints <= 6 and ppots > 0:
                    ppoints += 29
                    ppots -= 1
            # eat
            if php <= p.hp // 2 and food > 0:
                php = min(php + p.food["heal"], p.hp)
                food -= 1
                p_cd += 3
            p_cd -= 1
            t += 1
        if bhp <= 0 and php > 0:
            wins += 1
            times.append(t * TICK)
            foods.append(food_slots - food)
        elif php <= 0:
            deaths += 1
    if not wins:
        return dict(win_rate=0.0, deaths=deaths, trials=trials)
    avg_t, avg_food = sum(times) / len(times), sum(foods) / len(foods)
    cycle = avg_t + mon["find_s"]
    kpt = max(1, int(food_slots // max(avg_food, 0.5)))
    kills_hr = 3600 * kpt / (kpt * cycle + BANK_TRIP_S)
    entry = mon.get("entry_cost", 0)
    supplies = avg_food * p.food["cost"] + (600 if p.members else 250)  # pots
    death_cost_hr = (deaths / trials) * kills_hr * 15000  # reclaim fees etc, approx
    gp_hr = kills_hr * (mon["avg_drop"] - entry) - kills_hr * supplies - death_cost_hr
    return dict(win_rate=wins / trials, avg_kill_s=avg_t, avg_food=avg_food,
                kills_hr=kills_hr, gp_hr=gp_hr, protected=protected_style,
                deaths=deaths, trials=trials, style=pstyle)


# ---- memoized MC for population-scale runs: state rounded into bands ----
_MC_CACHE = {}


def boss_fight_cached(p: PlayerState, mon_name: str, mon: dict, kills_so_far: int,
                      trials: int = 200) -> dict:
    key = (mon_name, p.weapon["name"], p.rweapon["name"] if p.rweapon else None,
           p.armour["name"], p.att // 3, p.str_ // 3, p.def_ // 3, p.hp // 5,
           int(p.prayer) // 10, min(kills_so_far // 15, 8), p.members,
           p.bonus_str, round(p.pray_str_mult, 2))
    if key not in _MC_CACHE:
        _MC_CACHE[key] = boss_fight_mc(p, mon, kills_so_far, trials=trials)
    return _MC_CACHE[key]
