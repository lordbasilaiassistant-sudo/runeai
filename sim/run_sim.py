"""
RuneAI progression simulator — watch the agent play a simulated account.

  py sim/run_sim.py                 # Anthony's real account, 90 sim days
  py sim/run_sim.py --days 30
  py sim/run_sim.py --fresh         # level-3-ish fresh account instead

Seeded from the real snapshot stats. All world numbers are wiki-approx
pending calibration against the plugin's recorded corpus.
"""
import argparse
import sys

sys.stdout.reconfigure(encoding="utf-8")

from progress import run, boss_report

ANTHONY = dict(
    skills=dict(Attack=73, Strength=71, Defence=72, Hitpoints=77, Prayer=56,
                Mining=49, Fishing=50, Woodcutting=63, Runecraft=53, Hunter=1),
    gp=200_000,           # carried + misc; bank uncalibrated
    weapon="Rune scimitar",
    armour="Full rune",
    members=False,
)

FRESH = dict(
    skills=dict(Attack=40, Strength=40, Defence=40, Hitpoints=40, Prayer=31,
                Mining=15, Fishing=20, Woodcutting=30, Runecraft=10, Hunter=1),
    gp=20_000,
    weapon="Rune scimitar",
    armour="Full rune",
    members=False,
)


def sparkline(vals, width=60):
    bars = "▁▂▃▄▅▆▇█"
    if not vals:
        return ""
    step = max(1, len(vals) // width)
    vals = vals[::step]
    lo, hi = min(vals), max(vals)
    rng = max(hi - lo, 1)
    return "".join(bars[int((v - lo) / rng * 7)] for v in vals)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=90)
    ap.add_argument("--fresh", action="store_true")
    ap.add_argument("--iron", action="store_true", help="ironman: no GE buying")
    ap.add_argument("--p2p-preview", action="store_true", help="boss table as if membered")
    args = ap.parse_args()

    start = FRESH if args.fresh else ANTHONY
    who = "fresh account" if args.fresh else "Anthony's account (real stats)"
    print(f"=== SIMULATING {args.days} DAYS OF PLAY — {who} ===\n")

    p, skills, log, milestones, done, clog = run(start, days=args.days, ironman=args.iron)

    print("\n=== MILESTONES ===")
    for m in milestones:
        print(" ", m)

    print("\n=== GP/HR OVER TIME (each block the agent chose the best it had) ===")
    print(" ", sparkline([l.gp_hr for l in log]))
    print(f"  start {log[0].gp_hr:,} gp/hr  ->  end {log[-1].gp_hr:,} gp/hr")

    print("\n=== WORTH CURVE ===")
    print(" ", sparkline([l.worth for l in log]))
    print(f"  final worth: {p.gp:,} gp   members: {p.members}")
    print(f"  final combat: {skills['Attack']}/{skills['Strength']}/{skills['Defence']} hp {skills['Hitpoints']}")
    print(f"  gear: {p.weapon['name']} + {p.armour['name']} (+{p.bonus_att} att/+{p.bonus_str} str unlock bonuses)")
    print(f"  unlocks: {len(done)} done, ~{clog} collection log slots: {sorted(done)}")

    sample = [l for i, l in enumerate(log) if i % 20 == 0]
    print("\n=== TIMELINE SAMPLE ===")
    for l in sample:
        print(f"  day {l.day:5.1f}  worth {l.worth:>12,}  {l.gp_hr:>8,} gp/hr  "
              f"{'P2P' if l.members else 'F2P'}  {l.levels}  {l.activity}")

    boss_report(p, skills, assume_members=args.p2p_preview)


if __name__ == "__main__":
    main()
