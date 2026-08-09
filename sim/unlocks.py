"""
The account progression DAG — OSRS's real long game.

Quests, diaries, and challenge unlocks gate the items that define an
account (barrows gloves, fire cape, Ava's, Piety...). Each node:
  reqs      — skill minimums
  prereqs   — other unlock names
  time_h    — hours of sim time to complete it
  grants    — modeled effect: flat att/str/def equip bonuses, prayer_mult,
              or "trial" (must WIN the Monte Carlo fight vs `trial_boss`)
  clog      — collection log slots it roughly represents (tracked metric)

Effects are wiki-approx and VERIFY-tagged by design; the DAG structure is
the point — power is gated behind requirements, not gp.
"""

UNLOCKS = [
    # --- F2P-reachable prep ---
    dict(name="Dragon Slayer I", members=False, time_h=3,
         reqs={"Attack": 30}, prereqs=[],
         grants=dict(note="rune platebody access"), clog=1),
    # --- early members questing ---
    dict(name="Lost City + fairy prep", members=True, time_h=3,
         reqs={"Woodcutting": 36, "Crafting": 31}, prereqs=[],
         grants=dict(note="dramen; access web"), clog=1),
    dict(name="Animal Magnetism", members=True, time_h=2,
         reqs={"Crafting": 19, "Ranged": 30, "Woodcutting": 35}, prereqs=[],
         grants=dict(ranged_bonus=4, note="Ava's attractor/accumulator line"), clog=2),
    dict(name="Quest grind -> RFD subquests", members=True, time_h=25,
         reqs={"Cooking": 70, "Attack": 65}, prereqs=["Lost City + fairy prep"],
         grants=dict(note="RFD chain compressed"), clog=6),
    dict(name="Barrows gloves", members=True, time_h=4,
         reqs={"Defence": 65}, prereqs=["Quest grind -> RFD subquests"],
         grants=dict(att_bonus=12, str_bonus=12), clog=1),          # VERIFY exact stats
    dict(name="Knight's Trials -> Piety", members=True, time_h=6,
         reqs={"Prayer": 70, "Defence": 70}, prereqs=[],
         grants=dict(prayer_att_mult=1.20, prayer_str_mult=1.23), clog=0),  # VERIFY mults
    dict(name="Ardougne diary (med)", members=True, time_h=8,
         reqs={"Thieving": 38}, prereqs=[],
         grants=dict(note="cloak teleports; stall gp"), clog=3),
    # --- challenge unlocks: must WIN the fight, not pay for it ---
    dict(name="Fire cape (TzHaar Fight Cave)", members=True, time_h=2,
         reqs={"Ranged": 61}, prereqs=["Barrows gloves"],
         grants=dict(str_bonus=4, att_bonus=1), trial_boss="TzTok-Jad", clog=2),
    dict(name="Infernal cape (Inferno)", members=True, time_h=6,
         reqs={"Ranged": 80, "Defence": 75}, prereqs=["Fire cape (TzHaar Fight Cave)"],
         grants=dict(str_bonus=8, att_bonus=4), trial_boss="TzKal-Zuk", clog=3),
]

# challenge encounters — pure prayer-switch skill checks (the pvm learning
# curve IS the gate: fresh accounts wipe, practiced ones don't)
TRIAL_BOSSES = {
    "TzTok-Jad": dict(hp=250, att=480, str_=480, def_=100, style_def=60,
                      max_hit=97, speed=8, avg_drop=0, members=True, find_s=0, boss=True,
                      attacks=[dict(style="ranged", max_hit=97, speed=8, blockable=True),
                               dict(style="magic", max_hit=97, speed=8, blockable=True)],
                      mechanics=[dict(name="healers at 50%", type="typeless", dmg=6, period_s=15)],
                      switch_styles=True),
    "TzKal-Zuk": dict(hp=1200, att=560, str_=560, def_=260, style_def=90,
                      max_hit=251, speed=10, avg_drop=0, members=True, find_s=0, boss=True,
                      attacks=[dict(style="ranged", max_hit=60, speed=10, blockable=True),
                               dict(style="magic", max_hit=60, speed=10, blockable=True)],
                      mechanics=[dict(name="waves+safespot shield", type="safe_tile",
                                      dmg=45, period_s=6, base_avoid=0.15, learn_k=60)],
                      switch_styles=True),
}


def available(done: set, skills: dict, members: bool):
    """Next unlock whose gates are all open (priority = list order)."""
    for u in UNLOCKS:
        if u["name"] in done:
            continue
        if u["members"] and not members:
            continue
        if any(p not in done for p in u["prereqs"]):
            continue
        if any(skills.get(k, 1) < v for k, v in u["reqs"].items()):
            continue
        return u
    return None


def blocking_skill(done: set, skills: dict, members: bool):
    """The skill gate blocking the next locked unlock — tells the planner
    what to train for PROGRESSION rather than raw gp."""
    for u in UNLOCKS:
        if u["name"] in done or (u["members"] and not members):
            continue
        if any(p not in done for p in u["prereqs"]):
            continue
        for k, v in u["reqs"].items():
            if skills.get(k, 1) < v:
                return k, v, u["name"]
    return None
