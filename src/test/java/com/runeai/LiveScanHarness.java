package com.runeai;

import com.google.gson.Gson;
import java.util.List;
import okhttp3.OkHttpClient;

/**
 * Off-client smoke test against the LIVE wiki prices API — {@code ./gradlew liveScan}.
 *
 * <p>Runs the real scanner exactly as the plugin does, then checks the ranking's
 * promises against the book it was derived from: an insta pick's prices really
 * cross the spread at a profit, every net is positive, queue picks never claim
 * sub-minute cycles, the budget is the only affordability gate. Exit 0 = all
 * invariants held; nonzero = a lie was caught before it reached the GE window.
 */
public final class LiveScanHarness
{
	private LiveScanHarness()
	{
	}

	private static int bad;

	private static void check(boolean ok, String what)
	{
		if (!ok)
		{
			bad++;
			System.out.println("  ✗ INVARIANT BROKEN: " + what);
		}
	}

	public static void main(String[] args) throws Exception
	{
		final Gson gson = new Gson();
		final ItemMemory memory = new ItemMemory(gson);
		final FlipService svc = new FlipService(new OkHttpClient(), gson, memory,
			new FillTimeModel(gson), new GeBrain(gson), new Champion(gson));
		svc.setContext(50_000_000L, false);
		svc.setLaneConfig(true, 300, 6, true);
		svc.maybeRefresh();

		List<FlipService.Flip> top = List.of();
		for (int i = 0; i < 90 && top.isEmpty(); i++)
		{
			Thread.sleep(1_000);
			top = svc.getTopFlips();
		}
		if (top.isEmpty())
		{
			System.out.println("✗ no candidates within 90s — API unreachable or filters over-tight");
			System.exit(2);
		}

		System.out.println("== quick lane · insta-only mode · budget 50M ==");
		boolean anyInsta = false;
		for (FlipService.Flip f : top)
		{
			anyInsta |= f.isInsta();
			final long[] book = svc.bookFor(f.getItemId());
			System.out.printf("  %-28s %s %,9d -> %,9d  +%,6d  cyc %ds%n",
				f.getName(), f.isInsta() ? "⚡" : "q", f.getBuyAt(), f.getSellAt(),
				f.getNet(), f.getCycleSecs());
			check(f.getNet() > 0, f.getName() + " suggested with net " + f.getNet());
			if (book != null && f.isInsta())
			{
				check(f.getBuyAt() == book[1], f.getName() + " insta buy is not the instant-buy print");
				check(f.getSellAt() == book[0], f.getName() + " insta sell is not the instant-sell print");
				check(f.getNet() == FlipService.instaNet(book[0], book[1]),
					f.getName() + " insta net does not match the book");
			}
			if (!f.isInsta())
			{
				check(f.getCycleSecs() >= 60, f.getName() + " queue flip claims a " + f.getCycleSecs() + "s cycle");
			}
		}
		if (anyInsta)
		{
			for (FlipService.Flip f : top)
			{
				check(f.isInsta(), "insta-only mode leaked a queue flip: " + f.getName());
			}
		}
		else
		{
			System.out.println("  (no insta margins on the board right now — queue fallback shown, labeled)");
		}

		// budget is the ONLY affordability gate
		svc.setContext(1_000L, false);
		for (FlipService.Flip f : svc.getTopFlips())
		{
			check(f.getBuyAt() <= 1_000, "budget 1k but " + f.getName() + " costs " + f.getBuyAt());
		}
		svc.setContext(2_000_000_000L, false);
		svc.setLaneConfig(true, 300, 6, false);
		long maxBuy = 0;
		for (FlipService.Flip f : svc.getTopFlips())
		{
			maxBuy = Math.max(maxBuy, f.getBuyAt());
		}
		System.out.printf("== max cash: priciest suggestion %,d gp (old trader-level cap would have been ~567) ==%n", maxBuy);

		System.out.println(bad == 0 ? "ALL INVARIANTS HELD" : bad + " INVARIANT(S) BROKEN");
		System.exit(bad == 0 ? 0 : 1);
	}
}
