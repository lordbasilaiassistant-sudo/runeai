package com.runeai;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Plugin Hub forbids {@code javax.sound}, so RuneAI parses the WAV
 * container itself. These tests run against the clips that actually ship —
 * a hand-rolled parser that works on a synthetic fixture and not on the real
 * Kokoro output would be a mute plugin with a green build.
 */
public class VoicePlayerTest
{
	private static byte[] clip(String key) throws Exception
	{
		try (InputStream in = VoicePlayer.class.getResourceAsStream("/com/runeai/voice/" + key + ".wav"))
		{
			assertNotNull("bundled clip missing: " + key, in);
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			in.transferTo(bos);
			return bos.toByteArray();
		}
	}

	@Test
	public void everyBundledClipParses() throws Exception
	{
		for (String key : VoicePlayer.LINES.keySet())
		{
			final VoicePlayer.Pcm pcm = VoicePlayer.parseWav(clip(key));
			assertNotNull(key + " did not parse as PCM wav", pcm);
			assertTrue(key + " has no samples", pcm.data.length > 0);
			assertEquals(key + " is not 16-bit", 16, pcm.bits);
			assertTrue(key + " has an implausible sample rate: " + pcm.rate,
				pcm.rate >= 8_000 && pcm.rate <= 48_000);
			// the mouth is driven off this number now that there is no
			// getFramePosition() to read, so a wrong one desyncs the mascot
			assertTrue(key + " has an implausible duration: " + pcm.durationMs() + "ms",
				pcm.durationMs() > 300 && pcm.durationMs() < 15_000);
		}
	}

	@Test
	public void containerRoundTripsThroughOurOwnHeader() throws Exception
	{
		final VoicePlayer.Pcm original = VoicePlayer.parseWav(clip("eat"));
		final VoicePlayer.Pcm reparsed = VoicePlayer.parseWav(VoicePlayer.toWav(original));
		assertNotNull("our own header must be readable by our own parser", reparsed);
		assertEquals(original.rate, reparsed.rate);
		assertEquals(original.channels, reparsed.channels);
		assertEquals(original.bits, reparsed.bits);
		assertEquals("samples must survive re-containering byte for byte",
			original.data.length, reparsed.data.length);
		for (int i = 0; i < original.data.length; i += 997) // sparse: 8 clips of speech is a lot of bytes
		{
			assertEquals("sample " + i + " changed", original.data[i], reparsed.data[i]);
		}
	}

	@Test
	public void rawPcmIsWrappedIntoSomethingAudioPlayerWillAccept()
	{
		// what streamer-mode TTS returns: headerless samples
		final byte[] raw = new byte[4_800]; // 0.1s at 24kHz mono 16-bit
		final VoicePlayer.Pcm pcm =
			new VoicePlayer.Pcm(raw, VoicePlayer.TTS_RATE, VoicePlayer.TTS_CHANNELS, VoicePlayer.TTS_BITS);
		assertNull("raw samples have no container", VoicePlayer.parseWav(raw));

		final VoicePlayer.Pcm wrapped = VoicePlayer.parseWav(VoicePlayer.toWav(pcm));
		assertNotNull("wrapping is what makes AudioPlayer accept them", wrapped);
		assertEquals(VoicePlayer.TTS_RATE, wrapped.rate);
		assertEquals(100, wrapped.durationMs());
	}

	@Test
	public void compressedOrJunkBodiesAreRefusedRatherThanPlayedAsSamples()
	{
		assertNull(VoicePlayer.parseWav(null));
		assertNull(VoicePlayer.parseWav(new byte[0]));
		assertNull("too short to hold a header", VoicePlayer.parseWav(new byte[40]));

		final byte[] mp3 = new byte[2048];
		mp3[0] = 'I';
		mp3[1] = 'D';
		mp3[2] = '3';
		assertNull(VoicePlayer.parseWav(mp3));

		final byte[] ogg = new byte[2048];
		ogg[0] = 'O';
		ogg[1] = 'g';
		ogg[2] = 'g';
		assertNull(VoicePlayer.parseWav(ogg));

		// RIFF/WAVE that declares a compressed codec: playing this as samples
		// would be a scream in the player's headphones
		final byte[] real = new byte[64];
		System.arraycopy("RIFF".getBytes(), 0, real, 0, 4);
		System.arraycopy("WAVEfmt ".getBytes(), 0, real, 8, 8);
		real[16] = 16; // fmt chunk size
		real[20] = 85; // MPEG layer 3, not PCM
		assertNull("a non-PCM codec must not fall through to 'play it anyway'",
			VoicePlayer.parseWav(real));
	}

	@Test
	public void theEnvelopeTracksRealSpeechAndStaysInRange() throws Exception
	{
		final VoicePlayer.Pcm pcm = VoicePlayer.parseWav(clip("levelup"));
		final float[] env = VoicePlayer.envelope(pcm, Math.max(1, pcm.rate / 30));

		assertTrue("an envelope needs enough hops to animate a mouth", env.length > 10);
		float min = Float.MAX_VALUE;
		float max = 0;
		for (float v : env)
		{
			assertTrue("envelope escaped 0..1: " + v, v >= 0f && v <= 1f);
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		assertEquals("normalised to the clip's own peak", 1f, max, 1e-6);
		assertTrue("real speech is not a flat mouth", max - min > 0.3f);
	}

	/** Read the private queue-depth counter — the thing that jams if a line never finishes. */
	private static int depth(VoicePlayer v) throws Exception
	{
		final java.lang.reflect.Field f = VoicePlayer.class.getDeclaredField("queued");
		f.setAccessible(true);
		return ((java.util.concurrent.atomic.AtomicInteger) f.get(v)).get();
	}

	/** Wait for the queue to empty, failing rather than hanging if it never does. */
	private static void awaitDrain(VoicePlayer v) throws Exception
	{
		final long deadline = System.nanoTime() + 30_000_000_000L;
		while (depth(v) > 0)
		{
			if (System.nanoTime() > deadline)
			{
				throw new AssertionError("speech queue never drained; depth=" + depth(v));
			}
			java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L);
		}
	}

	/**
	 * The playback loop no longer sleeps or gets interrupted — the Plugin Hub
	 * allows neither — so the thing that can now break is the completion path:
	 * a line that starts and never reports itself done leaves the mouth latched
	 * and every later callout silently dropped. This drives real clips through
	 * the queue twice and asserts it comes back to empty both times.
	 */
	@Test
	public void everyQueuedLineReleasesTheMouth() throws Exception
	{
		final java.util.concurrent.ScheduledExecutorService exec =
			java.util.concurrent.Executors.newScheduledThreadPool(2);
		final VoicePlayer voice = new VoicePlayer(new RuneAIConfig()
		{
			@Override
			public boolean voiceCallouts()
			{
				return true;
			}
		}, new net.runelite.client.audio.AudioPlayer(), exec);
		try
		{
			voice.start();
			voice.play("eat");
			voice.play("bank");
			awaitDrain(voice);
			assertNull("the bubble must clear when the line ends", voice.getSpeakingText());
			assertEquals("the mouth must close when the line ends", 0f, voice.getMouth(), 1e-6);

			// and the queue is still usable afterwards, which a stuck latch would break
			voice.play("loot");
			awaitDrain(voice);
			assertNull(voice.getSpeakingText());
		}
		finally
		{
			voice.shutdown();
			exec.shutdown();
		}
	}
}
