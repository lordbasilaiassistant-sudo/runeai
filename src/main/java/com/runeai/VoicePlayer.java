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

	/**
	 * What streamer-mode TTS is asked to return: raw signed 16-bit little-endian
	 * mono at 24 kHz — byte-for-byte the shape the bundled Kokoro clips decode
	 * to, which is why the envelope code below needs no second version.
	 */
	static final AudioFormat TTS_PCM = new AudioFormat(24_000f, 16, 1, true, false);

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
			final ByteArrayOutputStream pcmBos = new ByteArrayOutputStream();
			ais.transferTo(pcmBos);
			playPcm(pcmBos.toByteArray(), ais.getFormat(), LINES.getOrDefault(key, ""));
		}
		catch (Exception ex)
		{
			log.warn("voice playback failed for {}", key, ex);
		}
	}

	/**
	 * Play decoded PCM and drive the mouth off its own amplitude. Shared by the
	 * bundled clips and by streamer commentary — one envelope, one mouth, so a
	 * streamed line lip-syncs exactly the way a bundled one does.
	 */
	private void playPcm(byte[] pcm, AudioFormat fmt, String text) throws Exception
	{
		try
		{
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
			speakingText = text;
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
		finally
		{
			mouth = 0f;
			speakingText = null;
		}
	}

	/**
	 * Speak a line that is not one of the bundled clips — streamer commentary.
	 * Parallel to {@link #play(String)} on purpose: it shares the single speech
	 * queue so lines can still never overlap, and it shares {@link #playPcm} so
	 * the mascot lip-syncs to real amplitude, but the text is arbitrary and the
	 * audio arrives as bytes from a TTS service instead of a bundled resource.
	 *
	 * <p><b>No audio is the normal case, not a failure.</b> The bundled Kokoro
	 * clips are pre-rendered for seven fixed lines; there is no local
	 * synthesiser in this plugin, so arbitrary text has no local voice today and
	 * shows in the mascot's bubble instead, held long enough to read.
	 *
	 * <p>Not gated on {@code voiceCallouts} — that toggle governs the coaching
	 * callouts. The caller owns the streamer-mode gate.
	 */
	void say(String text, byte[] audio, String contentType)
	{
		if (text == null || text.isEmpty() || queued.get() >= 2)
		{
			return;
		}
		queued.incrementAndGet();
		speechQueue.submit(() ->
		{
			try
			{
				if (!playAudio(text, audio, contentType))
				{
					showText(text);
				}
			}
			catch (Exception ex)
			{
				log.warn("commentary playback failed", ex);
			}
			finally
			{
				queued.decrementAndGet();
			}
		});
	}

	/** True if the bytes actually became sound. */
	private boolean playAudio(String text, byte[] audio, String contentType)
	{
		if (audio == null || audio.length < 2048)
		{
			return false;
		}
		try
		{
			final AudioInputStream ais = AudioSystem.getAudioInputStream(
				new BufferedInputStream(new ByteArrayInputStream(audio)));
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			ais.transferTo(out);
			playPcm(out.toByteArray(), ais.getFormat(), text);
			return true;
		}
		catch (Exception notAContainer)
		{
			// expected: we ASK the TTS service for raw signed 16-bit mono PCM, which
			// has no header for AudioSystem to recognise
		}
		if (!isRawPcm(contentType, audio))
		{
			log.warn("commentary audio came back as {} and there is no decoder for it — text only",
				contentType == null ? "an unlabelled format" : contentType);
			return false;
		}
		try
		{
			playPcm(audio, TTS_PCM, text);
			return true;
		}
		catch (Exception ex)
		{
			log.warn("commentary audio would not play", ex);
			return false;
		}
	}

	/**
	 * Is this body raw signed 16-bit PCM we can hand straight to a line?
	 *
	 * <p><b>The declared content type decides, not the bytes.</b> An earlier
	 * version sniffed for an mp3 frame sync at byte 0 and rejected any PCM whose
	 * first sample happened to be a small negative number — {@code 0xFF 0xFF} is
	 * both "sample -1" and "mp3 sync", and real speech starts near silence often
	 * enough that this fired on a live ElevenLabs response (measured 2026-08-10).
	 * The response says {@code audio/pcm} or {@code audio/mpeg}; believe it.
	 *
	 * <p>Magic bytes are still checked, but only ones that are unambiguous, and
	 * only to REJECT a mislabelled body — never to accept one. Playing compressed
	 * bytes as samples is a scream in the player's headphones.
	 */
	static boolean isRawPcm(String contentType, byte[] b)
	{
		if (b == null || b.length < 2048 || b.length % 2 != 0)
		{
			return false;
		}
		final String ct = contentType == null ? "" : contentType.toLowerCase();
		final boolean declared = ct.startsWith("audio/pcm") || ct.startsWith("audio/l16")
			|| ct.startsWith("audio/x-pcm") || ct.startsWith("application/octet-stream");
		if (!declared)
		{
			return false;
		}
		if (b[0] == 'I' && b[1] == 'D' && b[2] == '3')
		{
			return false; // mp3 with an id3 tag, mislabelled
		}
		if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g')
		{
			return false; // ogg / opus
		}
		if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C')
		{
			return false;
		}
		// a JSON error body served with a 200
		return b[0] != '{' && b[0] != '[';
	}

	/** No audio: hold the line in the bubble long enough to read it. */
	private void showText(String text)
	{
		speakingText = text;
		try
		{
			Thread.sleep(Math.min(9_000L, 1_500L + text.length() * 55L));
		}
		catch (InterruptedException ie)
		{
			Thread.currentThread().interrupt();
		}
		finally
		{
			speakingText = null;
		}
	}
}
