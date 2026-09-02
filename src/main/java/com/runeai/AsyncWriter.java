package com.runeai;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * it was), hand the bytes over, and let one daemon thread do the blocking part.
 * Writes to the same path COALESCE — the last state wins and the intermediate
 * ones are dropped unwritten, which is exactly right for a file that is a
 * snapshot rather than a log.
 *
 * <p>Static and never shut down on purpose: RuneLite reuses one plugin instance
 * across enable/disable, so a per-startUp executor would leak a thread per
 * cycle. {@link #flush(long)} is what {@code shutDown()} calls to make sure the
 * last snapshot actually reached the disk.
 */
@Slf4j
final class AsyncWriter
{
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r ->
	{
		final Thread t = new Thread(r, "runeai-io");
		t.setDaemon(true);
		return t;
	});

	/** path -> the newest bytes not yet on disk. */
	private static final Map<String, byte[]> PENDING = new ConcurrentHashMap<>();

	private AsyncWriter()
	{
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
		try
		{
			IO.execute(() -> drain(file, path));
		}
		catch (java.util.concurrent.RejectedExecutionException ex)
		{
			log.warn("state write rejected for {}", path, ex);
		}
	}

	private static void drain(File file, String path)
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

	/**
	 * Write via a sibling temp file and rename, never in place.
	 *
	 * <p>{@code Files.write} truncates the target and then fills it, so the file
	 * is legitimately empty for the width of the write. This thread is a daemon:
	 * a hard client kill or a JVM exit lands there eventually, and the file it
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
	 * Block until everything queued so far has been written, or the timeout
	 * expires. Called from {@code shutDown()} so a disable does not throw away
	 * the session's last ledger update.
	 */
	static void flush(long timeoutMs)
	{
		try
		{
			final Future<?> barrier = IO.submit(() ->
			{
			});
			barrier.get(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (Exception ex)
		{
			log.warn("state flush did not finish in time", ex);
		}
	}
}
