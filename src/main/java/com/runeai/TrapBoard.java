package com.runeai;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Data;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The patience-order playbook: which items have caught a whale, what the whale
 * paid, and the price to park an ask at so the next one crosses it.
 *
 * <p>The plugin already sees every item's live book each scan, so trap DETECTION
 * needs no file (that lives in {@link FlipService}). What one snapshot can never
 * show is history — how often an item has caught a whale and at what number. That
 * comes from {@code ~/.runelite/runeai/trap-board.json}, written by
 * {@code sim/whale_trap_report.py} from the local anomaly log. Absent file, absent
 * board: everything else keeps working.
 */
@Slf4j
@Singleton
class TrapBoard
{
	private static final File FILE = new File(new File(RuneLite.RUNELITE_DIR, "runeai"), "trap-board.json");
	private static final long RELOAD_MS = 5 * 60_000;

	private final Gson gson;
	private final AtomicLong lastCheck = new AtomicLong();
	private volatile long loadedStamp = -1;

	private volatile List<Ticket> tickets = List.of();
	private volatile Set<Integer> ids = Set.of();
	private volatile int prints;
	private volatile long totalOverpay;

	/** One historical whale strike, distilled. Gson fills these from the board file. */
	@Data
	static class Ticket
	{
		int id;
		String name;
		int strikes;
		long real;         // what sellers accepted at the time of the print
		long medPaid;      // what the whale paid
		long listAt;       // ask parked just under the number they typed
		String corridor;
		double payoffX;
		double ageDays;
	}

	/** A board row after joining history against today's live book. */
	@Value
	static class Pick
	{
		int itemId;
		String name;
		long buyAt;
		long qty;
		long listAt;
		double payoffX;
		String corridor;
	}

	private static class Board
	{
		int prints;
		long totalOverpay;
		List<Ticket> items;
	}

	@Inject
	TrapBoard(Gson gson)
	{
		this.gson = gson;
	}

	List<Ticket> getTickets()
	{
		return tickets;
	}

	/** Items with a whale history — never grind the spread on these. */
	Set<Integer> tradedIds()
	{
		return ids;
	}

	int getPrints()
	{
		return prints;
	}

	long getTotalOverpay()
	{
		return totalOverpay;
	}

	/** Re-read the board at most every 5 minutes, always off the client thread. */
	void maybeReload()
	{
		final long now = System.currentTimeMillis();
		final long prev = lastCheck.get();
		if (now - prev < RELOAD_MS || !lastCheck.compareAndSet(prev, now))
		{
			return;
		}
		final Thread t = new Thread(this::load, "runeai-trapboard");
		t.setDaemon(true);
		t.start();
	}

	private void load()
	{
		try
		{
			if (!FILE.exists())
			{
				return;
			}
			final long stamp = FILE.lastModified();
			if (stamp == loadedStamp)
			{
				return;
			}
			final Board b = gson.fromJson(
				new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8), Board.class);
			if (b == null || b.items == null)
			{
				return;
			}
			final java.util.Set<Integer> seen = new java.util.HashSet<>();
			for (Ticket t : b.items)
			{
				seen.add(t.id);
			}
			tickets = List.copyOf(b.items);
			ids = Set.copyOf(seen);
			prints = b.prints;
			totalOverpay = b.totalOverpay;
			loadedStamp = stamp;
			log.info("trap board loaded: {} tickets from {} prints ({} gp overpay seen)",
				tickets.size(), prints, totalOverpay);
		}
		catch (Exception ex)
		{
			log.warn("trap board load failed", ex);
		}
	}
}
