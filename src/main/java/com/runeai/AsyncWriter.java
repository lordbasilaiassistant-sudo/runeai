package com.runeai;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;

/**
 * The one place RuneAI's small JSON state files are written, and the reason it
 * exists is the client thread.
 *
 * <p>Every ledger in this plugin used to call {@code Files.write} inline:
 * {@code ItemMemory.recordFill} from a {@code GrandExchangeOfferChanged}
 * handler, {@code SessionHistory.save} from the tick loop, the plugin's own
 * basis/offer/clog/trader saves from the same handler. All of those run on the
 * client thread, where a blocking write is a frame the game does not draw —
 * and on a cold or contended disk that is a visible stutter every time an offer
 * fills.
 *
 * <p>So: serialise on the caller's thread (cheap, and it captures the state as
 * it was), hand the bytes over, and let RuneLite's own executor do the blocking
 * part. Writes to the same path COALESCE — the last state wins and the
 * intermediate ones are dropped unwritten, which is exactly right for a file
 * that is a snapshot rather than a log.
 *
 * <p><b>No thread of our own.</b> A Plugin Hub plugin does not get to start
 * threads, so the writer borrows the injected {@link ScheduledExecutorService}
 * handed to {@link #init}. That executor is a shared POOL, so ordering can no
 * longer come from "one thread does them in order": a single monitor serialises
 * the drain instead, and because the pending map holds only the newest bytes
 * per path, whoever gets the monitor writes the newest state and the losers
 * find nothing left to do.
 *
 * <p>Static and never shut down on purpose: RuneLite reuses one plugin instance
 * across enable/disable. {@link #flush(long)} is what {@code shutDown()} calls
 * to make sure the last snapshot actually reached the disk — it drains on the
 * caller's thread rather than waiting on someone else's.
 */
@Slf4j
final class AsyncWriter
{
	/** RuneLite's executor, handed over at startUp. Null before the plugin starts. */
	private static volatile ScheduledExecutorService io;

	/** Held for the duration of one drain, so two pool threads never write at once. */
	private static final Object DRAIN_LOCK = new Object();

	/** path -> the newest bytes not yet on disk. */
	private static final Map<String, byte[]> PENDING = new ConcurrentHashMap<>();

	private AsyncWriter()
	{
	}

	/** Give the writer RuneLite's executor. Called from {@code startUp()}. */
	static void init(ScheduledExecutorService executor)
	{
		io = executor;
	}

	/** Queue {@code json} to be written to {@code file}, replacing any pending write. */
	static void write(File file, String json)
	{
		if (file == null || json == null)
		{
			return;
		}
		final String path = file.getAbsolutePath();
		PENDING.put(path, json.getBytes(StandardCharsets.UTF_8));
		final ScheduledExecutorService executor = io;
		if (executor == null)
		{
			// before startUp, or in a test with no executor: the bytes stay pending
			// and the next flush() writes them, rather than blocking this thread
			return;
		}
		try
		{
			executor.execute(() -> drain(file, path));
		}
		catch (RejectedExecutionException ex)
		{
			log.warn("state write rejected for {} — flush() will pick it up", path);
		}
	}

	/**
	 * Write one path's newest bytes, if nobody has taken them already.
	 *
	 * <p>The monitor is what keeps two pool threads out of the same file. It is
	 * plain {@code synchronized} rather than a timed lock on purpose: a lock with
	 * a timeout would have to handle being interrupted, and the Plugin Hub does
	 * not allow interruption. The critical section is one small JSON write.
	 */
	private static void drain(File file, String path)
	{
		synchronized (DRAIN_LOCK)
		{
			final byte[] bytes = PENDING.remove(path);
			if (bytes == null)
			{
				return; // a later write already took this path's newest state
			}
			try
			{
				final File parent = file.getParentFile();
				if (parent != null)
				{
					parent.mkdirs();
				}
				writeAtomically(file, bytes);
			}
			catch (Exception ex)
			{
				log.warn("state write failed: {}", path, ex);
			}
		}
	}

	/**
	 * Write via a sibling temp file and rename, never in place.
	 *
	 * <p>{@code Files.write} truncates the target and then fills it, so the file
	 * is legitimately empty for the width of the write. A hard client kill or a
	 * JVM exit lands in the middle of one of these eventually, and the file it
	 * lands in the middle of is the user's ledger — {@code item-memory.json} or
	 * {@code booked-offers.json}. Truncated JSON does not parse, so the failure
	 * is not "lost the last update", it is "lost the whole history".
	 *
	 * <p>A rename is atomic at the filesystem level: the target is either the old
	 * complete file or the new complete file, never a half-written one. Worst
	 * case we leak a {@code .tmp} next to it, which is a cleanup problem rather
	 * than a data-loss one.
	 */
	private static void writeAtomically(File file, byte[] bytes) throws Exception
	{
		final Path target = file.toPath();
		final Path tmp = target.resolveSibling(file.getName() + ".tmp");
		Files.write(tmp, bytes);
		try
		{
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException ex)
		{
			// Some filesystems (and a few network mounts) refuse ATOMIC_MOVE. A
			// plain replace is still strictly better than truncate-then-fill,
			// because the bytes are already fully on disk before we swap.
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Write everything still pending, on the CALLING thread, giving up once
	 * {@code timeoutMs} has passed. Called from {@code shutDown()} so a disable
	 * does not throw away the session's last ledger update.
	 *
	 * <p>Draining here rather than waiting on a barrier task is what lets the
	 * writer own no thread: the caller that needs the bytes on disk is the one
	 * that puts them there, and a pool thread that got to a path first simply
	 * leaves nothing behind for this loop to find.
	 */
	static void flush(long timeoutMs)
	{
		final long deadline = System.nanoTime() + Math.max(0, timeoutMs) * 1_000_000L;
		for (String path : PENDING.keySet().toArray(new String[0]))
		{
			if (System.nanoTime() > deadline)
			{
				log.warn("state flush ran out of time with {} file(s) unwritten", PENDING.size());
				return;
			}
			drain(new File(path), path);
		}
	}
}
