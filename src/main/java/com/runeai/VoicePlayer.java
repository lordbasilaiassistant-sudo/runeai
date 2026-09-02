package com.runeai;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

/**
 * Plays premade Kokoro voice lines from bundled WAVs and exposes a live
 * mouth-openness envelope so the mascot can lip-sync to real playback.
 *
 * <p><b>Nothing here touches {@code javax.sound}.</b> The Plugin Hub forbids it
 * and requires {@link AudioPlayer}, so this class parses the WAV container
 * itself ({@link #parseWav}) and hands {@code AudioPlayer} a stream it will
 * accept. That costs us the two things a raw {@code Clip} gave us for free:
 *
 * <ul>
 *   <li>Playback position. The mouth is now driven off wall-clock time from the
 *       moment playback starts, against a duration computed from the sample
 *       count. Same envelope, same result, one indirection further from truth.</li>
 *   <li>Stopping mid-line. {@code AudioPlayer}'s line is self-closing and it
 *       hands back no handle, so {@link #shutdown()} can stop the NEXT line but
 *       cannot cut the one already sounding; a coaching clip is ~2s, so a
 *       disabled plugin can still be heard finishing a sentence.</li>
 * </ul>
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
	static final int TTS_RATE = 24_000;
	static final int TTS_CHANNELS = 1;
	static final int TTS_BITS = 16;

	/**
	 * Signed little-endian PCM samples plus the three numbers needed to time
	 * them. Stands in for {@code javax.sound.sampled.AudioFormat}, which the
	 * Plugin Hub does not allow us to name.
	 */
	static final class Pcm
	{
		final byte[] data;
		final int rate;
		final int channels;
		final int bits;

		Pcm(byte[] data, int rate, int channels, int bits)
		{
			this.data = data;
			this.rate = rate;
			this.channels = channels;
			this.bits = bits;
		}

		int frameSize()
		{
			return Math.max(1, channels * bits / 8);
		}

		int frames()
		{
			return data.length / frameSize();
		}

		long durationMs()
		{
			return rate <= 0 ? 0 : (long) (1000.0 * frames() / rate);
		}
	}

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
	private final AudioPlayer audioPlayer;
	private final Map<String, Long> lastPlayed = new ConcurrentHashMap<>();

	// one mouth: a single-thread queue so lines can NEVER overlap
	private volatile java.util.concurrent.ExecutorService speechQueue = newQueue();
	private final java.util.concurrent.atomic.AtomicInteger queued =
		new java.util.concurrent.atomic.AtomicInteger();
	/** False between {@code shutdown()} and the next {@code start()} — nothing speaks. */
	private volatile boolean running = true;

	private static java.util.concurrent.ExecutorService newQueue()
	{
		return java.util.concurrent.Executors.newSingleThreadExecutor(r ->
		{
			final Thread t = new Thread(r, "runeai-voice");
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * Arm the voice. RuneLite reuses one plugin instance across enable/disable,
	 * and so it reuses this singleton — so the queue has to be re-created when
	 * {@link #shutdown()} has torn it down, or a re-enabled plugin is mute.
	 */
	synchronized void start()
	{
		if (speechQueue.isShutdown())
		{
			speechQueue = newQueue();
		}
		queued.set(0); // tasks shutdownNow() dropped never ran their decrement
		running = true;
	}

	/**
	 * Stop talking, now. Called from {@code shutDown()}: without it a line that
	 * was queued or mid-playback when the player disabled RuneAI kept speaking
	 * out of a plugin that no longer exists, and the mascot's bubble kept the
	 * text alive with nothing left to clear it.
	 */
	synchronized void shutdown()
	{
		running = false;
		speechQueue.shutdownNow(); // interrupts the playback loop's sleep
		queued.set(0);
		lastPlayed.clear();
		mouth = 0f;
		speakingText = null;
	}

	private volatile float mouth;
	private volatile String speakingText;

	@Inject
	VoicePlayer(RuneAIConfig config, AudioPlayer audioPlayer)
	{
		this.config = config;
		this.audioPlayer = audioPlayer;
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
		if (!running || !config.voiceCallouts())
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
		submit(() -> speak(key));
	}

	/**
	 * Hand a line to the queue, keeping {@link #queued} honest even when the
	 * queue has been shut down under us by {@link #shutdown()}.
	 */
	private void submit(Runnable line)
	{
		try
		{
			speechQueue.submit(() ->
			{
				try
				{
					line.run();
				}
				finally
				{
					release();
				}
			});
		}
		catch (java.util.concurrent.RejectedExecutionException stopped)
		{
			release();
		}
	}

	/** Give a queue slot back, never below zero — shutdown() resets the counter too. */
	private void release()
	{
		queued.updateAndGet(v -> Math.max(0, v - 1));
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

			final Pcm pcm = parseWav(bos.toByteArray());
			if (pcm == null)
			{
				log.warn("voice clip is not a PCM wav: {}", key);
				return;
			}
			playPcm(pcm, LINES.getOrDefault(key, ""));
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
	private void playPcm(Pcm pcm, String text) throws Exception
	{
		try
		{
			final int hopFrames = Math.max(1, pcm.rate / 30);
			final float[] env = envelope(pcm, hopFrames);
			final int hops = env.length;
			final long durationMs = pcm.durationMs();

			// AudioPlayer only accepts a stream it can recognise, so the samples go
			// back into a WAV container on the way out. It returns as soon as the
			// line is started and closes the line itself when the clip ends.
			speakingText = text;
			audioPlayer.play(new ByteArrayInputStream(toWav(pcm)), 0f);

			// no getFramePosition() to read any more: step the envelope on the
			// clock instead, from the instant play() returned. The hop rate and
			// the duration both come from the sample count, so the mouth still
			// tracks this clip's own amplitude rather than a generic flap.
			final double hopMs = 1000.0 * hopFrames / pcm.rate;
			final long startNs = System.nanoTime();
			try
			{
				long elapsedMs;
				while ((elapsedMs = (System.nanoTime() - startNs) / 1_000_000L) < durationMs)
				{
					final int hop = (int) (elapsedMs / hopMs);
					mouth = hop < hops ? env[hop] : 0f;
					Thread.sleep(33);
				}
				Thread.sleep(400); // breath between queued lines
			}
			catch (InterruptedException interrupted)
			{
				// the line is self-closing and AudioPlayer kept no handle to it, so
				// the tail of this clip will finish sounding regardless; all we can
				// do is stop driving the mouth and let the queue drain
				Thread.currentThread().interrupt();
			}
		}
		finally
		{
			mouth = 0f;
			speakingText = null;
		}
	}

	/** Per-hop RMS amplitude, normalized to the clip's own peak. 0..1. */
	static float[] envelope(Pcm pcm, int hopFrames)
	{
		final int frameBytes = pcm.frameSize();
		final int hops = Math.max(1, pcm.data.length / frameBytes / hopFrames);
		final float[] env = new float[hops];
		if (pcm.bits != 16)
		{
			// the bundled clips and the TTS contract are both 16-bit; anything else
			// still plays, it just gets a steady mouth instead of a synced one
			java.util.Arrays.fill(env, 0.5f);
			return env;
		}
		float peak = 1;
		for (int h = 0; h < hops; h++)
		{
			double sum = 0;
			final int start = h * hopFrames;
			for (int i = 0; i < hopFrames; i++)
			{
				final int off = (start + i) * frameBytes;
				if (off + 1 >= pcm.data.length)
				{
					break;
				}
				final short s = (short) ((pcm.data[off] & 0xff) | (pcm.data[off + 1] << 8));
				sum += (double) s * s;
			}
			env[h] = (float) Math.sqrt(sum / hopFrames);
			peak = Math.max(peak, env[h]);
		}
		for (int h = 0; h < hops; h++)
		{
			env[h] /= peak;
		}
		return env;
	}

	/**
	 * Read a RIFF/WAVE container into samples. Returns null for anything that is
	 * not uncompressed PCM — a compressed body played as samples is a scream in
	 * the player's headphones, so "I don't recognise this" must never fall
	 * through to "play it anyway".
	 */
	static Pcm parseWav(byte[] wav)
	{
		if (wav == null || wav.length < 44
			|| wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F'
			|| wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E')
		{
			return null;
		}
		final ByteBuffer bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
		int rate = 0;
		int channels = 0;
		int bits = 0;
		boolean sawFmt = false;
		int p = 12;
		while (p + 8 <= wav.length)
		{
			final String id = "" + (char) wav[p] + (char) wav[p + 1] + (char) wav[p + 2] + (char) wav[p + 3];
			final int size = bb.getInt(p + 4);
			final int body = p + 8;
			if (size < 0 || body + size > wav.length)
			{
				// a truncated or hostile chunk length: take the data we can see
				if ("data".equals(id) && sawFmt && body < wav.length)
				{
					return pcmOf(wav, body, wav.length - body, rate, channels, bits);
				}
				return null;
			}
			if ("fmt ".equals(id) && size >= 16)
			{
				if (bb.getShort(body) != 1) // 1 == WAVE_FORMAT_PCM
				{
					return null;
				}
				channels = bb.getShort(body + 2);
				rate = bb.getInt(body + 4);
				bits = bb.getShort(body + 14);
				sawFmt = true;
			}
			else if ("data".equals(id) && sawFmt)
			{
				return pcmOf(wav, body, size, rate, channels, bits);
			}
			p = body + size + (size & 1); // chunks are word-aligned
		}
		return null;
	}

	private static Pcm pcmOf(byte[] wav, int off, int len, int rate, int channels, int bits)
	{
		if (rate <= 0 || channels <= 0 || bits <= 0 || len <= 0)
		{
			return null;
		}
		final byte[] data = new byte[len];
		System.arraycopy(wav, off, data, 0, len);
		return new Pcm(data, rate, channels, bits);
	}

	/** Wrap samples in a 44-byte canonical WAV header so AudioPlayer accepts them. */
	static byte[] toWav(Pcm pcm)
	{
		final int frameSize = pcm.frameSize();
		final int byteRate = pcm.rate * frameSize;
		final ByteBuffer bb = ByteBuffer.allocate(44 + pcm.data.length).order(ByteOrder.LITTLE_ENDIAN);
		bb.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		bb.putInt(36 + pcm.data.length);
		bb.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		bb.putInt(16);
		bb.putShort((short) 1); // PCM
		bb.putShort((short) pcm.channels);
		bb.putInt(pcm.rate);
		bb.putInt(byteRate);
		bb.putShort((short) frameSize);
		bb.putShort((short) pcm.bits);
		bb.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		bb.putInt(pcm.data.length);
		bb.put(pcm.data);
		return bb.array();
	}

	/**
	 * Speak a line that is not one of the bundled clips — streamer commentary.
	 * Parallel to {@link #play(String)} on purpose: it shares the single speech
	 * queue so lines can still never overlap, and it shares {@link #playPcm} so
	 * the mascot lip-syncs to real amplitude, but the text is arbitrary and the
	 * audio arrives as bytes from a TTS service instead of a bundled resource.
	 *
	 * <p><b>No audio is the normal case, not a failure.</b> The bundled Kokoro
	 * clips are pre-rendered for a fixed set of coaching lines; there is no local
	 * synthesiser in this plugin, so arbitrary text has no local voice today and
	 * shows in the mascot's bubble instead, held long enough to read.
	 *
	 * <p>Not gated on {@code voiceCallouts} — that toggle governs the coaching
	 * callouts. The caller owns the streamer-mode gate.
	 */
	void say(String text, byte[] audio, String contentType)
	{
		if (!running || text == null || text.isEmpty() || queued.get() >= 2)
		{
			return;
		}
		queued.incrementAndGet();
		submit(() ->
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
		});
	}

	/** True if the bytes actually became sound. */
	private boolean playAudio(String text, byte[] audio, String contentType)
	{
		if (audio == null || audio.length < 2048)
		{
			return false;
		}
		// a service that ignored the format we asked for and sent a real WAV back
		final Pcm container = parseWav(audio);
		if (container != null)
		{
			try
			{
				playPcm(container, text);
				return true;
			}
			catch (Exception ex)
			{
				log.warn("commentary wav would not play", ex);
				return false;
			}
		}
		if (!isRawPcm(contentType, audio))
		{
			log.warn("commentary audio came back as {} and there is no decoder for it — text only",
				contentType == null ? "an unlabelled format" : contentType);
			return false;
		}
		try
		{
			playPcm(new Pcm(audio, TTS_RATE, TTS_CHANNELS, TTS_BITS), text);
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
