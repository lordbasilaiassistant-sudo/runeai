package com.runeai;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Data;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Per-session results, kept forever, so "was that a good session?" stops being a
 * feeling. Every session writes one summary row to
 * {@code ~/.runelite/runeai/session-history.json}; the panel reads back this
 * session against the last one and against the best one on record.
 *
 * <p>The honesty rules are in the arithmetic, not the presentation:
 * <ul>
 *   <li>gp/h is measured over ACTIVE time — first offer placed to now — not over
 *       wall-clock, so leaving the client parked overnight cannot dilute a good
 *       hour into a bad one, and a 90-second session cannot claim 40M/h either
 *       (the same one-minute floor the GE overlay already uses).</li>
 *   <li>A session with nothing in it is never written. A scoreboard padded with
 *       empty sessions is a scoreboard you beat by doing nothing.</li>
 *   <li>The comparison is against completed sessions only. The row still being
 *       written is never allowed to also be its own competition.</li>
 * </ul>
 *
 * <p>All the comparison math is static and takes its inputs as arguments, which
 * is what makes it testable without a game client.
 */
@Slf4j
@Singleton
class SessionHistory
{
	private static final File FILE = new File(new File(RuneLite.RUNELITE_DIR, "runeai"), "session-history.json");
	/** Rows kept on disk. Sixty sessions is months of play and a few KB of JSON. */
	private static final int KEEP = 60;
	private static final long SAVE_EVERY_MS = 60_000;
	/**
	 * Same floor the flip gp/h already uses: below a minute of active trading the
	 * rate is an extrapolation from noise, so we refuse to extrapolate.
	 */
	private static final long MIN_ACTIVE_MS = 60_000;

	/** One finished (or in-progress) session's results. */
	@Data
	static class Session
	{
		long startMs;
		long endMs;
		long flipPnl;      // realized GE profit, post-tax, this session only
		long ledgerPnl;    // the inventory/equipment delta ledger
		int unitsBought;
		int unitsSold;
		int fills;         // offers that completed
		long activeMs;     // first offer placed -> last update
	}

	/** What the panel draws: this session against the record book. */
	@Value
	static class Scoreboard
	{
		long curPnl;
		long curGpHr;
		long lastPnl;
		long lastGpHr;
		long bestPnl;
		long bestGpHr;
		/** Completed sessions this one is currently out-earning. */
		int beaten;
		/** Completed sessions on record. */
		int ranked;
		/** gp still needed to pass the last session; 0 once it is passed. */
		long toBeatLast;
		/** gp still needed to pass the best session ever; 0 once it is passed. */
		long toBeatBest;
	}

	private final Gson gson;
	private final List<Session> past = new ArrayList<>();
	private Session current;
	private long lastSaveMs;

	@Inject
	SessionHistory(Gson gson)
	{
		this.gson = gson;
		load();
	}

	/**
	 * Active-time gp/h. Anything under {@link #MIN_ACTIVE_MS} is measured AS a
	 * minute rather than scaled up from it — the number is meant to be compared
	 * across sessions, and a rate computed over four seconds is not comparable
	 * to anything.
	 */
	static long gpPerHour(long pnl, long activeMs)
	{
		if (activeMs <= 0)
		{
			return 0;
		}
		return pnl * 3_600_000L / Math.max(MIN_ACTIVE_MS, activeMs);
	}

	/** A session worth remembering actually traded something. */
	static boolean worthKeeping(Session s)
	{
		return s != null && (s.flipPnl != 0 || s.unitsSold > 0 || s.unitsBought > 0 || s.fills > 0);
	}

	/**
	 * Rank the live session against the completed ones.
	 *
	 * <p>Pure: everything it knows arrives as an argument. {@code past} must not
	 * contain {@code cur}, or the session would be competing with itself and
	 * "beat your last session" would always read as a tie.
	 */
	static Scoreboard score(Session cur, List<Session> past)
	{
		final long curPnl = cur == null ? 0 : cur.flipPnl;
		final long curGpHr = cur == null ? 0 : gpPerHour(cur.flipPnl, cur.activeMs);
		long lastPnl = 0;
		long lastGpHr = 0;
		long bestPnl = 0;
		long bestGpHr = 0;
		int beaten = 0;
		int ranked = 0;
		for (Session s : past)
		{
			if (!worthKeeping(s))
			{
				continue;
			}
			ranked++;
			if (curPnl > s.flipPnl)
			{
				beaten++;
			}
			if (ranked == 1 || s.flipPnl > bestPnl)
			{
				bestPnl = s.flipPnl;
				bestGpHr = gpPerHour(s.flipPnl, s.activeMs);
			}
			lastPnl = s.flipPnl;
			lastGpHr = gpPerHour(s.flipPnl, s.activeMs);
		}
		return new Scoreboard(curPnl, curGpHr, lastPnl, lastGpHr, bestPnl, bestGpHr,
			beaten, ranked,
			ranked == 0 ? 0 : Math.max(0, lastPnl - curPnl + 1),
			ranked == 0 ? 0 : Math.max(0, bestPnl - curPnl + 1));
	}

	/** Open this session's row. Called once, at plugin start-up. */
	void begin(long startMs)
	{
		current = new Session();
		current.startMs = startMs;
		current.endMs = startMs;
	}

	/**
	 * Refresh the live row and hand back the scoreboard. Cheap enough to call on
	 * the tick loop; the disk write behind it is throttled to once a minute so a
	 * client that dies without a clean shutdown still loses at most a minute.
	 */
	Scoreboard update(long flipPnl, long ledgerPnl, int unitsBought, int unitsSold,
		int fills, long activeMs)
	{
		if (current == null)
		{
			begin(System.currentTimeMillis());
		}
		current.endMs = System.currentTimeMillis();
		current.flipPnl = flipPnl;
		current.ledgerPnl = ledgerPnl;
		current.unitsBought = unitsBought;
		current.unitsSold = unitsSold;
		current.fills = fills;
		current.activeMs = activeMs;
		if (current.endMs - lastSaveMs > SAVE_EVERY_MS)
		{
			lastSaveMs = current.endMs;
			save();
		}
		return score(current, past);
	}

	/** Close the row and flush it. Called from {@code shutDown()}. */
	void finish()
	{
		if (current == null)
		{
			return;
		}
		current.endMs = System.currentTimeMillis();
		save();
	}

	List<Session> completed()
	{
		return java.util.Collections.unmodifiableList(past);
	}

	private void load()
	{
		try
		{
			if (!FILE.exists())
			{
				return;
			}
			final List<Session> raw = gson.fromJson(
				new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8),
				new TypeToken<List<Session>>(){}.getType());
			if (raw != null)
			{
				for (Session s : raw)
				{
					if (worthKeeping(s))
					{
						past.add(s);
					}
				}
			}
			log.info("session history loaded: {} sessions", past.size());
		}
		catch (Exception ex)
		{
			log.warn("session history load failed", ex);
		}
	}

	private synchronized void save()
	{
		if (!worthKeeping(current))
		{
			return; // an empty session is not a session
		}
		try
		{
			FILE.getParentFile().mkdirs();
			final List<Session> out = new ArrayList<>(past);
			out.add(current);
			while (out.size() > KEEP)
			{
				out.remove(0);
			}
			Files.write(FILE.toPath(), gson.toJson(out).getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			log.warn("session history save failed", ex);
		}
	}
}
