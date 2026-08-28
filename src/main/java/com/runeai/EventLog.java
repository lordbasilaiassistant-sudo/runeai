package com.runeai;

import com.google.gson.Gson;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Append-only JSONL stream of live game events.
 * One JSON object per line: {"t": iso-time, "tick": n, "e": type, ...data}
 */
@Slf4j
class EventLog implements AutoCloseable
{
	private final Gson gson;
	private final BufferedWriter writer;
	private final boolean flushEach;
	private long lines;

	EventLog(File file, Gson gson) throws IOException
	{
		this(file, gson, false, false);
	}

	/**
	 * @param append    open the file in append mode — for logs that accumulate
	 *                  across sessions (the episode corpus) instead of starting
	 *                  fresh each run
	 * @param flushEach flush after every line — for rare, high-value rows where
	 *                  losing the last buffered ones to a crash would cost data
	 *                  a model trains on
	 */
	EventLog(File file, Gson gson, boolean append, boolean flushEach) throws IOException
	{
		this.gson = gson;
		this.flushEach = flushEach;
		this.writer = append
			? Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
			: Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
	}

	synchronized void log(String type, int tick, Map<String, Object> data)
	{
		try
		{
			final Map<String, Object> line = new LinkedHashMap<>();
			line.put("t", Instant.now().toString());
			line.put("tick", tick);
			line.put("e", type);
			if (data != null)
			{
				line.putAll(data);
			}
			writer.write(gson.toJson(line));
			writer.newLine();
			lines++;
			if (flushEach || lines % 50 == 0)
			{
				writer.flush();
			}
		}
		catch (IOException ex)
		{
			log.warn("event log write failed", ex);
		}
	}

	synchronized long getLines()
	{
		return lines;
	}

	@Override
	public synchronized void close()
	{
		try
		{
			// no explicit flush() first: close() flushes, and a flush that threw
			// (disk full) skipped the close and leaked the file handle — once per
			// log per enable/disable cycle
			writer.close();
		}
		catch (IOException ex)
		{
			log.warn("event log close failed", ex);
		}
	}
}
