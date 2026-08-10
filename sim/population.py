"""
Population runs — the "million years in minutes" engine, measured honestly.

Runs fleets of simulated account lifetimes across archetypes (fresh/mid,
iron/regular) and reports the distributions that matter: days to bond,
days to barrows gloves, final worth, final gp/hr — plus actual measured
lifetimes/second so the throughput claim is a number, not a vibe.

Every lifetime gets its OWN seed, so the fleet is a real distribution
(drop luck + exploration diverge the paths) rather than the same run
printed N times. Re-running with the same --seed reproduces the fleet.

  py sim/population.py --n 200 --days 365
  py sim/population.py --n 200 --days 365 --hours 3   # part-time players
"""
import argparse
import statistics
import sys
import time

sys.stdout.reconfigure(encoding="utf-8")

from progress import run, HOURS_PER_DAY, EPSILON

ARCHETYPES = {
    "fresh-regular": dict(
        skills=dict(Attack=40, Strength=40, Defence=40, Hitpoints=40, Prayer=31,
                    Ranged=40, Mining=15, Fishing=20, Woodcutting=30, Runecraft=10, Hunter=1),
        gp=20_000, weapon="Rune scimitar", armour="Full rune", members=False),
    "fresh-iron": None,  # same skills, ironman flag
    "mid-regular": dict(
        skills=dict(Attack=73, Strength=71, Defence=72, Hitpoints=77, Prayer=56,
                    Ranged=78, Mining=49, Fishing=50, Woodcutting=63, Runecraft=53, Hunter=1),
        gp=200_000, weapon="Rune scimitar", armour="Full rune", members=False),
}
ARCHETYPES["fresh-iron"] = ARCHETYPES["fresh-regular"]


def one_lifetime(arch: str, days: int, seed: int, hours: float, epsilon: float):
    start = ARCHETYPES[arch]
    iron = arch.endswith("iron")
    p, skills, log, milestones, done, clog = run(start, days=days,
                                                 verbose=False, ironman=iron,
                                                 hours_per_day=hours, seed=seed,
                                                 epsilon=epsilon)
    def day_of(needle):
        for m in milestones:
            if needle in m:
                return float(m.split(":")[0].replace("day", "").strip())
        return None
    return dict(
        worth=p.gp, members=p.members, clog=clog, unlocks=len(done),
        combat=skills["Attack"] + skills["Strength"] + skills["Defence"],
        bond_day=day_of("BOUGHT BOND"), gloves_day=day_of("Barrows gloves"),
        end_gp_hr=log[-1].gp_hr if log else 0,
    )


def pct(vals, q):
    vals = sorted(v for v in vals if v is not None)
    if not vals:
        return None
    return vals[min(len(vals) - 1, int(q * len(vals)))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=100, help="lifetimes per archetype")
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--hours", type=float, default=HOURS_PER_DAY,
                    help="playable hours per calendar day (default %(default)s)")
    ap.add_argument("--epsilon", type=float, default=EPSILON,
                    help="chance a block explores instead of taking the best option")
    ap.add_argument("--seed", type=int, default=1, help="fleet seed — reproduces the whole run")
    args = ap.parse_args()

    print(f"fleet: {args.hours}h/day played, exploration {args.epsilon:.0%}, seed {args.seed}")
    grand_total, t0 = 0, time.time()
    for ai, arch in enumerate(ARCHETYPES):
        t1 = time.time()
        results = [one_lifetime(arch, args.days, args.seed * 1_000_000 + ai * 10_000 + i,
                                args.hours, args.epsilon)
                   for i in range(args.n)]
        dt = time.time() - t1
        grand_total += args.n
        years = args.n * args.days * args.hours / (365.25 * 24)
        print(f"\n=== {arch} — {args.n} lifetimes x {args.days} days "
              f"({years:,.1f} account-years of PLAYED time in {dt:.1f}s "
              f"= {years/dt:,.0f} played-years/sec) ===")

        worths = [r["worth"] for r in results]
        uniq = len(set(worths))
        print(f"  final worth   p10 {pct(worths, .1):>13,}   median {pct(worths, .5):>13,}   p90 {pct(worths, .9):>13,}")
        print(f"                spread p90/p10 x{pct(worths, .9)/max(1, pct(worths, .1)):.2f}   "
              f"{uniq}/{args.n} distinct outcomes")
        bond = [r["bond_day"] for r in results if r["bond_day"]]
        if bond:
            print(f"  bond bought   {len(bond)}/{args.n} accounts, median day {statistics.median(bond):.1f}")
        gloves = [r["gloves_day"] for r in results if r["gloves_day"]]
        if gloves:
            print(f"  barrows gloves {len(gloves)}/{args.n} accounts, median day {statistics.median(gloves):.1f}")
        print(f"  median end gp/hr {pct([r['end_gp_hr'] for r in results], .5):,}   "
              f"median combat total {pct([r['combat'] for r in results], .5)}   "
              f"median clog {pct([r['clog'] for r in results], .5)}")

    dt = time.time() - t0
    total_years = grand_total * args.days / 365.25
    played_years = total_years * args.hours / 24
    print(f"\n=== TOTAL: {grand_total} lifetimes, {total_years:,.0f} calendar account-years "
          f"({played_years:,.1f} played) in {dt:.1f}s ({total_years/dt:,.0f} years/sec) ===")
    print(f"    at this rate, 1,000,000 account-years ≈ {1_000_000/(total_years/dt)/60:.1f} minutes")


if __name__ == "__main__":
    main()
