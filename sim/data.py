"""
Curated world data for the simulator.

The MATH is exact (osrs_math.py); these NUMBERS are wiki-sourced but
hand-entered and marked for calibration — the plugin's recorded corpus is
the validation set (calibrate.py compares sim vs reality). Treat every
figure as "approx until calibrated", per the never-lie rule.
"""

# ---- monsters: {name: dict} — stats from OSRS Wiki bestiary (VERIFY/calibrate) ----
# avg_drop = mean gp/kill incl. bones/alchables at GE prices (wiki drop tables, approx)
MONSTERS = {
    # F2P trainers
    "Cow":            dict(hp=8,   att=1,  str_=1,  def_=1,   style_def=0,  max_hit=1, speed=4, avg_drop=120,  members=False, find_s=4),
    "Hill Giant":     dict(hp=35,  att=18, str_=22, def_=26,  style_def=0,  max_hit=4, speed=6, avg_drop=350,  members=False, find_s=6),
    "Moss Giant":     dict(hp=60,  att=30, str_=30, def_=30,  style_def=0,  max_hit=7, speed=6, avg_drop=420,  members=False, find_s=6),
    "Ogress Warrior": dict(hp=82,  att=50, str_=53, def_=53,  style_def=10, max_hit=9, speed=6, avg_drop=900,  members=False, find_s=6),
    "Lesser Demon":   dict(hp=79,  att=68, str_=70, def_=71,  style_def=40, max_hit=8, speed=4, avg_drop=450,  members=False, find_s=8),
    # F2P bosses
    "Obor":           dict(hp=106, att=100, str_=118, def_=75, style_def=20, max_hit=14, speed=4, avg_drop=8000, members=False, find_s=90, boss=True, entry_cost=6000),
    "Bryophyta":      dict(hp=160, att=70,  str_=97,  def_=80, style_def=30, max_hit=12, speed=4, avg_drop=9500, members=False, find_s=90, boss=True, entry_cost=1500),
    # P2P trainers / money
    "Sand Crab":      dict(hp=60,  att=1,  str_=1,  def_=1,   style_def=0,  max_hit=1,  speed=4, avg_drop=15,    members=True, find_s=3),
    "Fire Giant":     dict(hp=111, att=65, str_=65, def_=65,  style_def=15, max_hit=11, speed=4, avg_drop=800,   members=True, find_s=6),
    "Green Dragon":   dict(hp=75,  att=68, str_=68, def_=68,  style_def=20, max_hit=8,  speed=4, avg_drop=4200,  members=True, find_s=10),
    # P2P bosses (melee-viable, simplified: prayer/mechanics folded into style_def/max_hit)
    "Scurrius":       dict(hp=425, att=75, str_=75, def_=25, style_def=5, max_hit=8, speed=4, avg_drop=6000, members=True, find_s=30, boss=True,
                           attacks=[dict(style="melee", max_hit=8, speed=4, blockable=True),
                                    dict(style="magic", max_hit=7, speed=8, blockable=True)],
                           mechanics=[dict(name="falling bricks", type="aoe_avoid", dmg=10, period_s=15, base_avoid=0.4, learn_k=15)],
                           pvm_trainer=True),  # THE learning boss — cheap deaths, real mechanics
    "Giant Mole":     dict(hp=200, att=200, str_=200, def_=101, style_def=20, max_hit=21, speed=4, avg_drop=9500,  members=True, find_s=45, boss=True,
                           attacks=[dict(style="melee", max_hit=21, speed=4, blockable=True)]),
    "Sarachnis":      dict(hp=400, att=190, str_=190, def_=98,  style_def=25, max_hit=31, speed=5, avg_drop=18000, members=True, find_s=40, boss=True),
    "Barrows (avg brother)": dict(hp=100, att=100, str_=100, def_=100, style_def=50, max_hit=25, speed=4, avg_drop=11000, members=True, find_s=30, boss=True,
                           attacks=[dict(style="melee", max_hit=25, speed=4, blockable=True)],
                           mechanics=[dict(name="wrong-prayer brother", type="typeless", dmg=6, period_s=20)]),
    "General Graardor": dict(hp=255, att=280, str_=350, def_=250, style_def=90, max_hit=60, speed=6, avg_drop=28000, members=True, find_s=25, boss=True,
                           attacks=[dict(style="melee", max_hit=60, speed=6, blockable=True),      # boss main — pray melee
                                    dict(style="ranged", max_hit=35, speed=6, blockable=False),    # his own ranged slam hits through if praying melee
                                    dict(style="ranged", max_hit=16, speed=5, blockable=False),    # Strongstack/Steelwill/Grimspike crossfire:
                                    dict(style="magic",  max_hit=16, speed=5, blockable=False)],   # different styles at once — one overhead can't cover all
                           mechanics=[dict(name="minion aggro juggling", type="typeless", dmg=5, period_s=10)],
                           req_note="needs 70 str for door; kill count grind not modeled"),
    # Raid stub — aggregate encounter: survival is ~all about correct protect switching,
    # which is exactly what pvm skill models. Numbers are placeholders to shape the curve.
    "TOA (entry, avg room)": dict(hp=800, att=250, str_=250, def_=160, style_def=60, max_hit=30, speed=5, avg_drop=250000, members=True, find_s=120, boss=True,
                           attacks=[dict(style="magic",  max_hit=30, speed=5, blockable=True),
                                    dict(style="ranged", max_hit=28, speed=5, blockable=True)],
                           mechanics=[dict(name="floor aoes + specials", type="aoe_avoid", dmg=22, period_s=8, base_avoid=0.25, learn_k=40),
                                      dict(name="ground poison", type="ground_dot", dmg=8, period_s=12, base_avoid=0.5, learn_k=25)],
                           switch_styles=True),  # boss alternates: MUST switch prayers
}

# ---- melee gear ladder: weapon defines dps; armour folded into a def bonus tier ----
# (slash weapons; att = slash attack bonus, str_ = strength bonus; cost = GE approx)
WEAPONS = [
    dict(name="Rune scimitar",     att=45, str_=44, speed=4, req_att=40, cost=15000,   members=False),
    dict(name="Rune 2h sword",     att=69, str_=70, speed=7, req_att=40, cost=38000,   members=False),
    dict(name="Dragon scimitar",   att=67, str_=66, speed=4, req_att=60, cost=60000,   members=True),
    dict(name="Sarachnis cudgel",  att=79, str_=88, speed=4, req_att=65, cost=350000,  members=True),  # crush; approx as ladder step
    dict(name="Abyssal whip",      att=82, str_=82, speed=4, req_att=70, cost=1500000, members=True),
    dict(name="Abyssal tentacle",  att=90, str_=86, speed=4, req_att=75, cost=2600000, members=True),
]

ARMOUR = [
    dict(name="Full rune",      def_=200, req_def=40, cost=35000,   members=False),
    dict(name="Granite/Obby",   def_=240, req_def=50, cost=250000,  members=True),
    dict(name="Barrows melee",  def_=330, req_def=70, cost=1100000, members=True),
]

FOODS = [
    dict(name="Trout",     heal=7,  cost=60,  members=False),
    dict(name="Lobster",   heal=12, cost=180, members=False),
    dict(name="Swordfish", heal=14, cost=350, members=False),
    dict(name="Monkfish",  heal=16, cost=450, members=True),
    dict(name="Shark",     heal=20, cost=800, members=True),
]

# ---- skilling methods: coarse per-level-band rates (wiki, approx; gp net of costs) ----
SKILLING = [
    dict(name="Mine iron (F2P bank)",   skill="Mining",      req=15, xp_hr=45000,  gp_hr=25000,  members=False),
    dict(name="Chop yews (F2P)",        skill="Woodcutting", req=60, xp_hr=30000,  gp_hr=25000,  members=False),
    dict(name="Fish lobsters (F2P)",    skill="Fishing",     req=40, xp_hr=25000,  gp_hr=20000,  members=False),
    dict(name="Runecraft f2p (bodies)", skill="Runecraft",   req=20, xp_hr=12000,  gp_hr=15000,  members=False),
    dict(name="Fish monkfish",          skill="Fishing",     req=62, xp_hr=35000,  gp_hr=45000,  members=True),
    dict(name="Mine granite",           skill="Mining",      req=45, xp_hr=50000,  gp_hr=30000,  members=True),
    dict(name="Chop magics",            skill="Woodcutting", req=75, xp_hr=17000,  gp_hr=95000,  members=True),
    dict(name="Hunt red chins",         skill="Hunter",      req=63, xp_hr=90000,  gp_hr=180000, members=True),
]

BOND_PRICE = 11_500_000  # calibrated 2026-08-09 vs live wiki price API (was 14M guess — measurement wins)
BANK_TRIP_S = 120        # bank round-trip overhead, seconds

# ---- prayers & potions ----
# protect prayers: block "blockable" attacks of their style entirely (non-raid bosses).
# F2P reality Anthony called out: overheads exist (43+ prayer) but NO prayer potions
# in F2P — so overheads run until points die, then it's raw tanking. Points drain
# ~1 per 3s with an overhead up (gear-dependent; approx).
PRAYER_DRAIN_S = 3.0
POTIONS = [
    # boost = flat + pct of level; re-sip modeled at trip start (decay folded in ~ -25%)
    dict(name="Strength potion",  skill="str", flat=3, pct=0.10, cost=250,  members=False),
    dict(name="Super combat",     skill="all", flat=5, pct=0.15, cost=9000, members=True),
    dict(name="Prayer potion",    skill="prayer", restore=29,    cost=8000, members=True),
]

# ---- mechanics: the OSRS core patterns, abstracted ----
# type: "aoe_avoid"   — bad tiles appear, move off them (Zulrah/Vorkath/raids pattern)
#       "safe_tile"   — stand ON the marked safe spot (Yama pattern)
#       "ground_dot"  — poison/fire on ground, dodgeable damage-over-time
#       "typeless"    — unavoidable chip (minion crossfire, recoil)
# dmg = expected damage per proc if failed; period_s = how often it procs.
# Avoidance = pvm skill learning curve: p(avoid) = 1 - (1-base)*exp(-kills/learn_k)
# — "low level bosses = better training for people learning how to pvm."

