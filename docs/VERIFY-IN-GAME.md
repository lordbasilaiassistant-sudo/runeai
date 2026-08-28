# Verify in game

Everything here is **code that has landed and passed off-client evidence**, waiting on
the only evidence that actually counts: seeing it behave in a live client.

This is a glance-list, not a work queue. Tick a box the next time you happen to be
playing; nothing here needs a dedicated session. Off-client green is a hypothesis —
these boxes are the measurement.

Landed in `f6c7b63`, `afdd6c0`, `d166f50`, `19ba130` (PR #18).
Consolidated here 2026-08-27 from issues #14, #15, #16, #17.

## Trade-history audit — was reporting a +12.2B ghost discrepancy

The flat widget run arrives as `[direction_N, name_N, sprite_N, price_N]`. The reader
opened a row at each sprite, gluing row N's price to row N+1's direction and name.
Separately the "each" parser took the first number of the price block — the row
**total** — as the unit price. Fixed with measured regrouping, each-price read as the
number before "each", and a ±1gp sell-rounding tolerance.

- [ ] Reopen GE Trade History after a few flips. It should read
      "ledger matches the game on N of M trades" (rows older than the ledger excluded),
      not `LEDGER OFF BY ...`.

## Offer coach — was suggesting guaranteed-loss flips

On Mort myre fungus the coach printed `net -5 each after tax` and still suggested
`qty 3,073 → total -15,365 gp`. The quick lane had picked it as an insta flip minutes
earlier; by setup time the book had moved and the coach quoted queue prices with a
negative net as if they were a plan. A suggestion is now only printed when it makes
money.

- [ ] Open any item with a dead spread in the setup screen. No qty/total suggestion
      should appear — just a red "NO MARGIN — this item pays nothing right now" with
      the per-unit loss stated.
- [ ] Sell coaching never proposes below breakeven from your actual buy basis.
      UNDER WATER is stated as a loss, not dressed up.

## GE overlays — the GE window IS the dashboard

Roughly ten GUI defects in one frame: slot stamps truncated ("NDER WATER · hold (−1"),
stamps bleeding across neighbouring slots, a floating flips panel over the minimap,
`b~?s s~?s` placeholder noise, and most of the information living outside the interface
it described. Both overlays are now dynamic and anchored to widget bounds — empty slots
become pick tiles, a footer strip under the slot grid carries the session line, the
offer coach sits over the item description panel (465:27), and verdict stamps are two
clamped lines inside their own slot. Medians are hidden when unknown.

- [ ] Open the GE. Nothing floating, nothing cut off, nothing clickable covered.
- [ ] No `~?` placeholder text anywhere.

## Tracking overhaul

97 unit tests pass including the `OfferLifecycleHarnessTest` scenarios (watched flip,
offline-completed offer across a restart, loss persisting to lifetime), and
`./gradlew liveScan` held every ranking invariant against the live wiki API.

- [ ] A fill completed while logged out books into P&L and all-time on next login.
- [ ] The session number survives a client relaunch (resume window 8h).
- [ ] Insta picks fill as fast as claimed; queue picks are honestly labeled as queue.

---

When a box is confirmed, tick it here. When one fails, that is a real finding — open an
issue with the frame or the log line, because a failure here beats every green test in
the repo.
