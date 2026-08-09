"""
Long-horizon progression: a greedy policy plays the simulated account in
6-hour blocks. It balances THREE currencies — gp, xp, and UNLOCKS — because
OSRS accounts are a dependency DAG, not a level grind: barrows gloves,
fire cape, Piety and friends gate real power behind quests, diaries and
challenge fights the agent must actually win in the Monte Carlo engine.
"""
from dataclasses import dataclass

from osrs_math import level_for_xp, xp_for_level
from data import MONSTERS, WEAPONS, ARMOUR, FOODS, SKILLING, BOND_PRICE
from engine import PlayerState, combat_rates, boss_fight_mc
from unlocks import UNLOCKS, TRIAL_BOSSES, available, blocking_skill

BLOCK_H = 6.0
QUEST_SKILL_XP_HR = 30000   # generic "train an odd skill for a quest gate"
QUEST_SKILL_GP_HR = -5000


@dataclass
class SimLog:
    day: float
    worth: int
    activity: str
    gp_hr: int
    levels: str
    members: bool
    clog: int


def best_food(p):
    usable = [f for f in FOODS if p.members or not f["members"]]
    return usable[-1] if p.gp > 200_000 else usable[min(1, len(usable) - 1)]


def pick_combat(p):
    best_gp, best_xp = None, None
    for name, mon in MONSTERS.items():
        if mon["members"] and not p.members:
            continue
        if mon.get("boss") and mon["hp"] > 150:
            continue
        r = combat_rates(p, mon)
        if not r.get("feasible") or r["p_hit"] < 0.10:
            continue
        r["name"] = name
        if best_gp is None or r["gp_hr"] > best_gp["gp_hr"]:
            best_gp = r
        if best_xp is None or r["xp_hr"] > best_xp["xp_hr"]:
            best_xp = r
    return best_gp, best_xp


def pick_skilling(p, skills, target_skill=None):
    best = None
    for m in SKILLING:
        if m["members"] and not p.members:
            continue
        if skills.get(m["skill"], 1) < m["req"]:
            continue
        if target_skill and m["skill"] != target_skill:
            continue
        key = "xp_hr" if target_skill else "gp_hr"
        if best is None or m[key] > best[key]:
            best = m
    return best


def next_upgrade(p):
    for w in WEAPONS:
        if w["members"] and not p.members:
            continue
        if w["str_"] > p.weapon["str_"]:
            return ("weapon", w)
    for a in ARMOUR:
        if a["members"] and not p.members:
            continue
        if a["def_"] > p.armour["def_"]:
            return ("armour", a)
    return None


def apply_grants(p, grants, note):
    if "att_bonus" in grants:
        p.bonus_att += grants["att_bonus"]
    if "str_bonus" in grants:
        p.bonus_str += grants["str_bonus"]
    if "prayer_att_mult" in grants:
        p.pray_att_mult = max(p.pray_att_mult, grants["prayer_att_mult"])
    if "prayer_str_mult" in grants:
        p.pray_str_mult = max(p.pray_str_mult, grants["prayer_str_mult"])


def run(start: dict, days: int = 60, verbose=True, ironman: bool = False):
    skills = dict(start["skills"])
    xp = {k: xp_for_level(v) for k, v in skills.items()}
    p = PlayerState(
        att=skills["Attack"], str_=skills["Strength"], def_=skills["Defence"],
        hp=skills["Hitpoints"], gp=start["gp"], members=start.get("members", False),
        weapon=next(w for w in WEAPONS if w["name"] == start["weapon"]),
        armour=next(a for a in ARMOUR if a["name"] == start["armour"]),
    )
    p.ironman = ironman
    p.prayer = skills.get("Prayer", 43)
    p.food = best_food(p)
    done, clog, pvm_kc = set(), 0, {}
    shelved = {}  # trial name -> combat-power snapshot when we gave up
    log, milestones = [], []
    hours = 0.0

    def note(msg):
        milestones.append(f"day {hours/24:5.1f}: {msg}")
        if verbose:
            print(f"  ** {msg}")

    while hours < days * 24:
        p.food = best_food(p)

        # 1. bond
        if not p.members and p.gp >= BOND_PRICE * 1.05 and not p.ironman:
            p.gp -= BOND_PRICE
            p.members = True
            note(f"BOUGHT BOND -> MEMBERS (worth left {p.gp:,})")

        # 2. progression DAG — unlocks beat raw gp (they compound forever)
        u = available(done, skills, p.members)
        if u and u.get("trial_boss") in shelved:
            # shelved wall: only retry once meaningfully stronger
            power = skills["Attack"] + skills["Strength"] + skills["Defence"] + p.bonus_str * 2
            if power < shelved[u["trial_boss"]] + 8:
                u = None
        if u:
            trial = u.get("trial_boss")
            if trial:
                kc = pvm_kc.get(trial, 0)
                r = boss_fight_mc(p, TRIAL_BOSSES[trial], kills_so_far=kc, trials=120)
                if r["win_rate"] >= 0.30:
                    done.add(u["name"])
                    clog += u["clog"]
                    apply_grants(p, u["grants"], note)
                    hours += u["time_h"]
                    note(f"UNLOCKED {u['name']} (win {r['win_rate']:.0%} after {kc} practice)")
                    continue
                else:
                    if kc >= 32:  # enough wipes — this is a WALL, not a learning curve
                        shelved[trial] = (skills["Attack"] + skills["Strength"]
                                          + skills["Defence"] + p.bonus_str * 2)
                        note(f"SHELVED {trial} after {kc} attempts (win {r['win_rate']:.0%}) — needs better gear/stats")
                        continue
                    # practice attempts: burn a block learning the fight
                    pvm_kc[trial] = kc + 8
                    hours += BLOCK_H
                    p.gp -= 40_000  # supplies for wipes
                    log.append(SimLog(hours / 24, p.gp, f"practicing {trial} (kc {kc})",
                                      -7000, f"{skills['Attack']}/{skills['Strength']}/{skills['Defence']}",
                                      p.members, clog))
                    continue
            else:
                done.add(u["name"])
                clog += u["clog"]
                apply_grants(p, u["grants"], note)
                hours += u["time_h"]
                note(f"completed {u['name']} (+{u['clog']} clog)")
                continue

        # 3. gear (GE path; irons grind drops instead — not yet modeled)
        up = next_upgrade(p)
        train_target = None
        if up and not p.ironman:
            kind, item = up
            req_skill = "Attack" if kind == "weapon" else "Defence"
            req = item.get("req_att", item.get("req_def", 1))
            if skills[req_skill] < req:
                train_target = req_skill
            elif p.gp >= item["cost"] * 1.2:
                p.gp -= item["cost"]
                if kind == "weapon":
                    p.weapon = item
                else:
                    p.armour = item
                note(f"bought {item['name']} ({item['cost']:,} gp)")
                continue

        # 3b. progression skill gate (quest reqs like Cooking 70, Prayer 70)
        gate = blocking_skill(done, skills, p.members)

        # 4. choose the block's activity
        cam_gp, cam_xp = pick_combat(p)
        sk = pick_skilling(p, skills)
        if train_target and cam_xp:
            act, gp_hr, xp_hr, gain = (f"train {train_target} @ {cam_xp['name']}",
                                       cam_xp["gp_hr"], cam_xp["xp_hr"], train_target)
        elif gate and gate[0] in ("Attack", "Strength", "Defence", "Hitpoints") and cam_xp:
            act, gp_hr, xp_hr, gain = (f"train {gate[0]} for {gate[2]} @ {cam_xp['name']}",
                                       cam_xp["gp_hr"], cam_xp["xp_hr"], gate[0])
        elif gate:
            sk_t = pick_skilling(p, skills, target_skill=gate[0])
            if sk_t:
                act, gp_hr, xp_hr, gain = (f"{sk_t['name']} for {gate[2]}",
                                           sk_t["gp_hr"], sk_t["xp_hr"], gate[0])
            else:
                act, gp_hr, xp_hr, gain = (f"quest-skill {gate[0]} for {gate[2]}",
                                           QUEST_SKILL_GP_HR, QUEST_SKILL_XP_HR, gate[0])
        elif cam_gp and (not sk or cam_gp["gp_hr"] >= sk["gp_hr"]):
            act, gp_hr, xp_hr = f"camp {cam_gp['name']}", cam_gp["gp_hr"], cam_gp["xp_hr"]
            gain = min(("Attack", "Strength", "Defence"), key=lambda s: skills[s])
        elif sk:
            act, gp_hr, xp_hr, gain = sk["name"], sk["gp_hr"], sk["xp_hr"], sk["skill"]
        else:
            act, gp_hr, xp_hr, gain = "idle", 0, 0, None

        p.gp += int(gp_hr * BLOCK_H)
        if gain:
            xp.setdefault(gain, xp_for_level(skills.get(gain, 1)))
            xp[gain] += int(xp_hr * BLOCK_H)
            new = level_for_xp(xp[gain])
            if new > skills.get(gain, 1):
                skills[gain] = new
                if gain in ("Attack", "Strength", "Defence", "Hitpoints"):
                    setattr(p, {"Attack": "att", "Strength": "str_",
                                "Defence": "def_", "Hitpoints": "hp"}[gain], new)
                if gain == "Prayer":
                    p.prayer = new
                if new % 10 == 0 or new >= 90:
                    note(f"{gain} -> {new}")
            if gain in ("Attack", "Strength", "Defence"):
                xp.setdefault("Hitpoints", xp_for_level(skills["Hitpoints"]))
                xp["Hitpoints"] += int(xp_hr * BLOCK_H / 3)
                nh = level_for_xp(xp["Hitpoints"])
                if nh > skills["Hitpoints"]:
                    skills["Hitpoints"] = nh
                    p.hp = nh

        hours += BLOCK_H
        log.append(SimLog(hours / 24, p.gp, act, int(gp_hr),
                          f"{skills['Attack']}/{skills['Strength']}/{skills['Defence']}",
                          p.members, clog))

    return p, skills, log, milestones, done, clog


def boss_report(p: PlayerState, skills, assume_members=False):
    was = p.members
    if assume_members:
        p.members = True
    print("\n=== BOSS FEASIBILITY — can we kill it, is it worth it? (400 MC fights) ===")
    print(f"    gear: {p.weapon['name']} + {p.armour['name']} "
          f"(+{p.bonus_att} att/+{p.bonus_str} str from unlocks), "
          f"{skills['Attack']}/{skills['Strength']}/{skills['Defence']} "
          f"hp {skills['Hitpoints']} pray {p.prayer}")
    everything = dict(MONSTERS)
    everything.update(TRIAL_BOSSES)
    for name, mon in everything.items():
        if not mon.get("boss"):
            continue
        if mon["members"] and not p.members:
            print(f"  {name:24s} locked (members)")
            continue
        fresh = boss_fight_mc(p, mon, kills_so_far=0)
        exp = boss_fight_mc(p, mon, kills_so_far=100)
        pr = fresh.get("protected") or exp.get("protected")
        if fresh["win_rate"] < 0.05 and exp["win_rate"] < 0.05:
            print(f"  {name:24s} NO — dies even with 100 kc of practice")
        else:
            print(f"  {name:24s} kc0: win {fresh['win_rate']:.0%} {fresh.get('gp_hr', 0):>9,.0f} gp/hr"
                  f"  | kc100: win {exp['win_rate']:.0%} {exp.get('gp_hr', 0):>9,.0f} gp/hr"
                  f"  (pray {pr})")
    p.members = was
