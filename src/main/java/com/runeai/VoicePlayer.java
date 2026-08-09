package com.runeai;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Plays premade Kokoro voice lines from bundled WAVs and exposes a live
 * mouth-openness envelope so the mascot can lip-sync to real playback.
 */
@Slf4j
@Singleton
class VoicePlayer
{
	private static final long COOLDOWN_MS = 12_000;

	static final Map<String, String> LINES = Map.of(
		"idle", "You're idle. Click the highlighted tile.",
		"eat", "Low health. Eat now.",
		"bank", "Inventory full. Bank your items.",
		"loot", "Good drop. Grab it.",
		"attacked", "You're under attack.",
		"pot", "Pot up. Drink your potion.",
		"bond", "You can afford a bond. Time to go members.",
		"levelup", "Nice one. Level up!");

	private final RuneAIConfig config;
	private final Map<String, Long> lastPlayed = new ConcurrentHashMap<>();

	// one mouth: a single-thread queue so lines can NEVER overlap
	private final java.util.concurrent.ExecutorService speechQueue =
		java.util.concurrent.Executors.newSingleThreadExecutor(r ->
		{
			final Thread t = new Thread(r, "runeai-voice");
			t.setDaemon(true);
			return t;
		});
	private final java.util.concurrent.atomic.AtomicInteger queued =
		new java.util.concurrent.atomic.AtomicInteger();

	private volatile float mouth;
	private volatile String speakingText;

	@Inject
	VoicePlayer(RuneAIConfig config)
	{
		this.config = config;
	}

	/** 0..1 — how open the mascot's mouth should be right now. */
	float getMouth()
	{
		return mouth;
	}

	/** The line currently being spoken, or null. */
	String getSpeakingText()
	{
		return speakingText;
	}

	void play(String key)
	{
		if (!config.voiceCallouts())
		{
			return;
		}
		final long now = System.currentTimeMillis();
		final Long last = lastPlayed.get(key);
		if (last != null && now - last < COOLDOWN_MS)
		{
			return;
		}
		// coaching, not a backlog: if a line is playing and one is waiting, drop this one
		if (queued.get() >= 2)
		{
			return;
		}
		lastPlayed.put(key, now);
		queued.incrementAndGet();
		speechQueue.submit(() ->
		{
			try
			{
				speak(key);
			}
			finally
			{
				queued.decrementAndGet();
			}
		});
	}

	private void speak(String key)
	{
		try (InputStream in = VoicePlayer.class.getResourceAsStream("/com/runeai/voice/" + key + ".wav"))
		{
			if (in == null)
			{
				log.warn("voice clip missing: {}", key);
				return;
			}
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			in.transferTo(bos);
			final byte[] wav = bos.toByteArray();

			final AudioInputStream ais = AudioSystem.getAudioInputStream(
				new BufferedInputStream(new ByteArrayInputStream(wav)));
			final AudioFormat fmt = ais.getFormat();
			final ByteArrayOutputStream pcmBos = new ByteArrayOutputStream();
			ais.transferTo(pcmBos);
			final byte[] pcm = pcmBos.toByteArray();

			// amplitude envelope: one value per ~33ms hop, normalized to peak
			final int frameBytes = fmt.getFrameSize();
			final int hopFrames = (int) (fmt.getFrameRate() / 30);
			final int hops = Math.max(1, pcm.length / frameBytes / hopFrames);
			final float[] env = new float[hops];
			float peak = 1;
			for (int h = 0; h < hops; h++)
			{
				double sum = 0;
				final int start = h * hopFrames;
				for (int i = 0; i < hopFrames; i++)
				{
					final int off = (start + i) * frameBytes;
					if (off + 1 >= pcm.length)
					{
						break;
					}
					final short s = (short) ((pcm[off] & 0xff) | (pcm[off + 1] << 8));
					sum += (double) s * s;
				}
				env[h] = (float) Math.sqrt(sum / hopFrames);
				peak = Math.max(peak, env[h]);
			}
			for (int h = 0; h < hops; h++)
			{
				env[h] /= peak;
			}

			final Clip clip = AudioSystem.getClip();
			clip.addLineListener(e ->
			{
				if (e.getType() == LineEvent.Type.STOP)
				{
					clip.close();
				}
			});
			clip.open(fmt, pcm, 0, pcm.length);
			speakingText = LINES.getOrDefault(key, "");
			clip.start();

			// drive the mouth from actual playback position
			while (clip.isOpen())
			{
				final int hop = clip.getFramePosition() / hopFrames;
				mouth = hop < hops ? env[hop] : 0f;
				Thread.sleep(33);
			}
			Thread.sleep(400); // breath between queued lines
		}
		catch (Exception ex)
		{
			log.warn("voice playback failed for {}", key, ex);
		}
		finally
		{
			mouth = 0f;
			speakingText = null;
		}
	}
}
