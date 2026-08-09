"""
Exact OSRS mechanics — the published, deterministic core.
Formulas per OSRS Wiki ("Combat formula", "Experience"): stable for years.
Everything here is a pure function; data.py holds the numbers that need
calibration, this file holds the math that doesn't.
"""
import math

TICK = 0.6  # seconds

# ---- xp curve ----
_XP_TABLE = [0]
_acc = 0
for _n in range(1, 127):
    _acc += math.floor(_n + 300 * 2 ** (_n / 7.0))
    _XP_TABLE.append(_acc // 4)


def level_for_xp(xp: int) -> int:
    lvl = 1
    for l in range(2, 127):
        if xp >= _XP_TABLE[l - 1]:
            lvl = l
        else:
            break
    return min(lvl, 99)


def xp_for_level(level: int) -> int:
    return _XP_TABLE[level - 1]


# ---- melee combat (accurate style unless noted) ----
def effective_str(str_level: int, style_bonus: int = 0, prayer_mult: float = 1.0) -> int:
    return math.floor(str_level * prayer_mult) + style_bonus + 8


def effective_att(att_level: int, style_bonus: int = 0, prayer_mult: float = 1.0) -> int:
    return math.floor(att_level * prayer_mult) + style_bonus + 8


def max_hit(str_level: int, str_equip_bonus: int, style_bonus: int = 0,
            prayer_mult: float = 1.0) -> int:
    es = effective_str(str_level, style_bonus, prayer_mult)
    return math.floor(0.5 + es * (str_equip_bonus + 64) / 640)


def attack_roll(att_level: int, att_equip_bonus: int, style_bonus: int = 0,
                prayer_mult: float = 1.0) -> int:
    ea = effective_att(att_level, style_bonus, prayer_mult)
    return ea * (att_equip_bonus + 64)


def npc_defence_roll(def_level: int, style_def_bonus: int) -> int:
    return (def_level + 9) * (style_def_bonus + 64)


def player_defence_roll(def_level: int, def_equip_bonus: int, style_bonus: int = 0) -> int:
    ed = math.floor(def_level) + style_bonus + 8
    return ed * (def_equip_bonus + 64)


def hit_chance(att_roll: int, def_roll: int) -> float:
    if att_roll > def_roll:
        return 1 - (def_roll + 2) / (2 * (att_roll + 1))
    return att_roll / (2 * (def_roll + 1))


def dps(chance: float, mhit: int, speed_ticks: int) -> float:
    """Expected damage/second: uniform 0..max on success."""
    return chance * (mhit / 2.0) / (speed_ticks * TICK)


# ---- xp rates ----
MELEE_XP_PER_DMG = 4.0
HP_XP_PER_DMG = 4.0 / 3.0
