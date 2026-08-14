# FLIP-MATH — the market model behind RuneAI's GE advice

Last updated: 2026-08-14. This is the reference for WHY each estimator exists,
what it measures, and what would make it wrong. Every claim tagged *(measured)*
comes from this player's own logs or a trainer's held-out verdict — everything
else is structure.

## 1. What the GE actually is

A batch-matched limit-order market with a **hidden book**:

- You never see depth. The wiki feed shows the last buyer-initiated print
  (`high`), the last seller-initiated print (`low`), each side's 5-minute
  volume, and nothing else — delayed up to 60s.
- Orders rest until matched; an aggressive order (buy at ≥ the standing ask)
  matches immediately.
- Frictions that define the game: **2% sell tax** (floored per unit, 5M cap,
  nothing under 50gp), **4h buy limit** per item, **8 slots** (3 F2P).

A flip is: buy `q` at `b`, sell at `s`. Profit per unit `= s − tax(s) − b`.
The decision variables are item, `b`, `s`, `q`, and the reprice/abort policy
while offers rest. Everything the plugin coaches is one of those five.

## 2. The quantity that runs the whole business: P(fill)

The single most important unknown is **P(offer fills within t)** as a function
of price placement, side, and liquidity. From it derive:

- expected cycle time → what "quick" honestly means,
- expected gp per **slot-second** → the ranking metric,
- the reprice/abort decision → optimal stopping against opportunity cost.

**The data has a censoring structure and ignoring it is how the old model
lied.** A cancelled offer is not a missing fill time; it is the measurement
"t seconds was not enough". This player's corpus *(measured 2026-08-14)*:
106 timed fills vs 213 usable cancels — a model trained on fills alone saw
one third of the evidence, all of it from the lucky third.

### The survival model (`train/train_fill_survival.py` → `SurvivalFillModel`)

Weibull AFT: `T ~ Weibull(k, λ(x))`, `λ = exp(μ + β·x)`,
`x = [buySide, log10(price)/8, sin(hour), cos(hour)]`.

- median side time `= λ·ln2^(1/k)`; `P(fill ≤ t) = 1 − exp(−(t/λ)^k)`.
- Censored MLE: fills contribute density, cancels contribute survival mass.
- **Verdict, held out (2026-08-14): ADOPT** — C-index 0.628 vs 0.5 coin-flip,
  beats intercept-only on held-out log-likelihood. 319 episodes.
- What the fit says about THIS player *(measured)*:
  - `k = 0.43 < 1` — **decreasing hazard**: an offer that hasn't filled soon
    becomes less likely to fill per minute, not more. Waiting out a stall is
    mathematically wrong; reprice-or-redeploy is right. This single number is
    the licence behind the DEAD/redeploy stamps.
  - `β_buy = +1.13` — buys run ~3× slower than sells for this player.
  - baseline median ≈ 595s **per side** — the honest number behind killing
    the old "~10s cycle" claims.
- v0 carries **no volume feature** (the backfilled logs don't have the book at
  placement), so in `estCycleSecs` it CALIBRATES the level while the volume
  shape prior keeps relative ordering: `cycle = sqrt(shapePrior × survival)`.
  The episode corpus now records the book at placement, so v1 adds
  aggressiveness/volume/OFI covariates and replaces the blend.

## 3. Order flow: who is crossing the spread

The 5m feed splits volume into buyer-initiated (`highPriceVolume`) and
seller-initiated (`lowPriceVolume`). Their sum is liquidity; their **imbalance**
`OFI = (bv − sv)/(bv + sv) ∈ [−1, 1]` is direction — the most standard
microstructure predictor there is, and the ranker used to add the two numbers
together. Positive OFI = demand lifting the book = the regime where YOUR sell
clears. Used as a clamped linear ranking tilt (`flowFactor`, ±25% max) and
shown in the coach ("buyers 62%").

## 4. The two price games, and why they never mix

- **Insta-cross**: buy at `high`, sell at `low`. Profitable iff
  `instaNet = low − tax(low) − high > 0` (crossed/backwardated prints — rising
  markets). Both sides fill on placement: cycle ≈ 30s **by construction**.
  The only flips allowed to be called QUICK without evidence.
- **Queue**: buy `low+1`, sell `high−1`. The spread is the payment for waiting,
  and the waiting is what the survival model prices. Labeled `q~Ns`, never
  "quick".

## 5. Ranking: gp per slot-second, three binds

`velocity = net × min(bookThroughput, capital/price, remainingBuyLimit) / cycle`

- **Buy-limit tracking** *(new)*: every filled buy unit enters a rolling 4h
  ledger (`buy-window.json`). Remaining limit bounds every qty suggestion, and
  an item at zero leaves the board entirely — the interface enforces the limit
  silently, so a suggestion that ignores it is a coached dead slot.
- Multiplicative tilts, each clamped: item-memory bandit (exploit/explore/
  cooldown), momentum (falling-knife guard), OFI, brain tie-break. The champion
  genome supplies concentration and the pro-rata ROI floor.

## 6. Selling and the breakeven floor

`breakevenSell(avgCost)` = the exact lowest ask with
`p − tax(p) > avgCost`. Every sell suggestion floors here. A book living under
the player's cost is a **loss decision** and is stated as one (UNDER WATER,
−N/ea), never disguised as a routine reprice. Basis is exact FIFO-average from
delta accounting, survives restarts, books offline fills.

## 7. Reprice/abort as optimal stopping

A resting offer occupies a slot whose opportunity cost is the board's best
velocity `v*`. With decreasing hazard (k<1), expected remaining wait GROWS as
an offer ages, so the rule is: when
`remainingUpside < v* × horizon` → **redeploy** (stamped on DEAD offers,
180s horizon). With the v1 survival model this becomes fully quantitative:
abort when `net × h(t) < v*` where `h` is the fitted hazard.

## 8. Reinforcement learning — the honest roadmap

What exists: the Champion genome (evolution strategies over `sim/self_play.py`,
adopted on held-out P&L) and the residual price brain (`ge_rl_trainer.py`,
which honestly measured **no edge at 60s horizons** — recorded in its own
docstring). What does NOT exist yet is the data volume for deep RL: ~530
episodes total. A DQN fitted to that would memorize noise and the gates would
(rightly) reject it.

The path that compounds instead:

1. **Corpus first** *(shipped)*: every offer outcome appends a rich episode —
   book at placement, OFI, side, price, qty, censoring, realized gp — to
   `episodes.jsonl`. This is the state-action-outcome log RL trains on.
2. **Survival v1** once ~300 rich episodes exist: add aggressiveness
   (price vs book), volume, OFI covariates. Same gate.
3. **Simulator realism**: the sim's fill model becomes the FITTED survival
   model, so evolved/learned policies stop exploiting a fantasy fill rule —
   the single biggest known gap between sim P&L and live P&L.
4. **Policy learning in the sim** (reprice/abort/allocation), evolution or
   fitted Q over the calibrated simulator, adopted only on held-out windows —
   the Champion pipeline, upgraded, not replaced.

Rules that never bend: every learned artifact is gated on a verdict written by
its own out-of-sample evaluation; a missing or losing artifact changes nothing;
ranking may reorder, but every number read back to the player stays anchored to
the live book and its 3× cap.

## 9. Estimator map (what to trust, and why)

| Question | Estimator | Status |
| --- | --- | --- |
| How long will this side take to fill? | `SurvivalFillModel` (censored Weibull AFT) | **adopted** (C 0.628) |
| How long, per item, for me? | `ItemMemory` observed cycles (confidence blend) | always on, per-item |
| Which item first? | velocity × bandit × momentum × OFI × brain | live |
| Is the spread real? | trap predicate (3× cap + thin-volume) | live, pure |
| What price on the sell? | breakeven floor over live book | live, exact |
| Can I even buy it? | 4h buy-limit ledger | live, exact |
| Where's the market going? | momentum + OFI (fast), brain (tie-break), anomaly watch (report-only) | gated/clamped |
| When do I give up a slot? | decreasing hazard + opportunity cost | analytic now, fitted later |
