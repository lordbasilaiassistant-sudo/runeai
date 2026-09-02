package com.runeai;

import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Streamer mode is the only part of RuneAI that can talk to a machine that is
 * not the player's, so the tests that matter most here are the ones that pin it
 * SHUT: nothing collected and nothing sent while the toggle is off, and nothing
 * sent even when it is on until the player has supplied an endpoint and a key.
 *
 * <p>The rest pins the two pure halves — what an event turns into, and when the
 * co-host is allowed to open its mouth.
 */
public class StreamerServiceTest
{
	private static final IntFunction<String> NAMES = id ->
	{
		switch (id)
		{
			case 1333:
				return "Rune scimitar";
			case 440:
				return "Iron ore";
			default:
				return null;
		}
	};

	private static Map<String, Object> map(Object... kv)
	{
		final Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2)
		{
			m.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		return m;
	}

	/** A config with streamer mode wired however a test needs it. */
	private static RuneAIConfig cfg(boolean on, String url, String key)
	{
		return new RuneAIConfig()
		{
			@Override
			public boolean streamerMode()
			{
				return on;
			}

			@Override
			public String streamerBaseUrl()
			{
				return url;
			}

			@Override
			public String streamerKey()
			{
				return key;
			}
		};
	}

	private static StreamerService service(RuneAIConfig config)
	{
		// a plugin must never construct its own OkHttpClient; a test has no injector
		return new StreamerService(config, new Gson(), new okhttp3.OkHttpClient(),
			new VoicePlayer(config, new net.runelite.client.audio.AudioPlayer(),
				java.util.concurrent.Executors.newSingleThreadScheduledExecutor()));
	}

	// ================= the off switch =================

	@Test
	public void offMeansNothingIsEvenCollected()
	{
		final StreamerService s = service(cfg(false, "https://api.groq.com/openai/v1", "sk-live"));
		for (int i = 0; i < 50; i++)
		{
			s.observe("levelUp", map("skill", "Fishing", "level", 62), NAMES);
			s.observe("death", map("actor", map("local", true)), NAMES);
			s.observe("pnl", map("total", 5_000_000L), NAMES);
		}
		assertEquals("a switched-off co-host must not even accumulate material",
			0, s.pendingBeats());

		// and tick() cannot build a request out of nothing
		s.tick(System.currentTimeMillis());
		assertEquals(0, s.spokenLines());
		assertEquals("off", s.status());
		assertNull(s.pollChatLine());
		assertFalse(s.configured());
	}

	@Test
	public void onButUnconfiguredCollectsAndStillSendsNothing()
	{
		final StreamerService s = service(cfg(true, "", ""));
		s.start();
		s.observe("levelUp", map("skill", "Fishing", "level", 62), NAMES);
		assertEquals(1, s.pendingBeats());
		assertFalse("blank endpoint must never be treated as configured", s.configured());

		s.tick(System.currentTimeMillis());
		assertEquals(0, s.spokenLines());
		assertTrue(s.status(), s.status().contains("inert"));
		assertEquals("an unsent beat is kept, not consumed", 1, s.pendingBeats());
	}

	@Test
	public void aPlaintextEndpointIsRefusedAndTheReelIsKept()
	{
		// every request carries the user's API key in an Authorization header;
		// http:// would put it, and the session text, on the wire in the clear
		final StreamerService s = service(cfg(true, "http://api.groq.com/openai/v1", "sk-live"));
		s.start();
		s.observe("levelUp", map("skill", "Fishing", "level", 62), NAMES);
		assertEquals(1, s.pendingBeats());

		s.tick(System.currentTimeMillis());
		assertEquals("nothing may be sent over plaintext", 0, s.spokenLines());
		assertTrue(s.status(), s.status().contains("https"));
		assertEquals("a refused beat is kept, not consumed", 1, s.pendingBeats());
	}

	@Test
	public void aKeyWithoutAnEndpointIsStillUnconfigured()
	{
		assertFalse(service(cfg(true, "   ", "sk-live")).configured());
		assertFalse(service(cfg(true, "https://api.groq.com/openai/v1", "  ")).configured());
		assertTrue(service(cfg(true, "https://api.groq.com/openai/v1", "sk-live")).configured());
	}

	@Test
	public void onlyAShortlistOfEventTypesIsEvenLookedAt()
	{
		final StreamerService s = service(cfg(true, "https://x/v1", "k"));
		// the recorder writes all of these every session; none is commentary
		for (String noise : new String[]{"varbit", "animation", "container", "click",
			"npcSpawn", "playerSpawn", "projectile", "heartbeat", "chat", "graphic",
			"interacting", "hitsplat", "stat", "gameState", "flipScan"})
		{
			s.observe(noise, map("anything", 1), NAMES);
		}
		assertEquals(0, s.pendingBeats());
		assertFalse(StreamerService.INTERESTING.contains("chat"));
		assertFalse("player positions are recorded but never narrated",
			StreamerService.INTERESTING.contains("heartbeat"));
	}

	// ================= what an event becomes =================

	@Test
	public void beatsReadLikeCommentaryMaterial()
	{
		assertEquals("levelled Fishing to 62",
			StreamerService.beatOf("levelUp", map("skill", "Fishing", "level", 62), NAMES));

		assertEquals("GE sell filled: 137 x Iron ore for 47,320 gp (3m to fill)",
			StreamerService.beatOf("geOffer", map("state", "SOLD", "item", 440,
				"qtySold", 137, "spent", 47_320L, "fillSecs", 195L), NAMES));

		assertEquals("GE buy filled: 5 x Rune scimitar for 76,000 gp (45s to fill)",
			StreamerService.beatOf("geOffer", map("state", "BOUGHT", "item", 1333,
				"qtySold", 5, "spent", 76_000L, "fillSecs", 45L), NAMES));

		assertEquals("market: Dragon scimitar moved +41% over 6 min on real volume",
			StreamerService.beatOf("anomaly", map("name", "Dragon scimitar",
				"movePct", 41.3, "spanMins", 6L), NAMES));

		assertEquals("close call: down to 7/50 hp under attack "
				+ "(danger model had already raised the alarm)",
			StreamerService.beatOf("guidance", map("kind", "eat", "hp", 7, "max", 50,
				"elevated", true), NAMES));

		assertEquals("DIED", StreamerService.beatOf("death", map("actor", map("local", true)), NAMES));

		assertEquals("drop: Rune scimitar worth 60,000 gp",
			StreamerService.beatOf("itemSpawn", map("id", 1333, "value", 60_000L,
				"worthRatio", 0.004), NAMES));
	}

	@Test
	public void theQuietMajorityOfEventsBecomesNothing()
	{
		// an offer being PLACED is not a result
		assertNull(StreamerService.beatOf("geOffer",
			map("state", "BUYING", "item", 440, "qtySold", 0), NAMES));
		// a cancelled offer that never filled has nothing to report either
		assertNull(StreamerService.beatOf("geOffer",
			map("state", "SOLD", "item", 440, "qtySold", 0), NAMES));
		// an npc dying is a kill, and kills are not rare
		assertNull(StreamerService.beatOf("death", map("actor", map("local", false)), NAMES));
		assertNull(StreamerService.beatOf("death", map("actor", "not a map"), NAMES));
		// junk on the floor
		assertNull(StreamerService.beatOf("itemSpawn",
			map("id", 440, "value", 300L, "worthRatio", 0.00001), NAMES));
		// a guidance event that is not the eat warning
		assertNull(StreamerService.beatOf("guidance", map("kind", "idle"), NAMES));
		assertNull(StreamerService.beatOf("varbit", map("id", 1), NAMES));
		assertNull(StreamerService.beatOf("levelUp", null, NAMES));
	}

	@Test
	public void aDropThatIsBigForTHISPlayerStillCounts()
	{
		// 12k gp is nothing in the abstract, and everything at 400k total worth
		assertEquals("drop: Iron ore worth 12,000 gp",
			StreamerService.beatOf("itemSpawn", map("id", 440, "value", 12_000L,
				"worthRatio", 0.03), NAMES));
	}

	@Test
	public void anUnknownItemIdNeverBecomesNull()
	{
		assertEquals("drop: item 99999 worth 90,000 gp",
			StreamerService.beatOf("itemSpawn", map("id", 99999, "value", 90_000L,
				"worthRatio", 0.5), NAMES));
		assertEquals("drop: item 1333 worth 90,000 gp",
			StreamerService.beatOf("itemSpawn", map("id", 1333, "value", 90_000L,
				"worthRatio", 0.5), null));
	}

	@Test
	public void profitOnlySpeaksWhenItCrossesARoundNumber()
	{
		final StreamerService s = service(cfg(true, "https://x/v1", "k"));
		s.start();
		s.observe("pnl", map("total", 40_000L), NAMES);     // below the first bucket
		assertEquals(0, s.pendingBeats());
		s.observe("pnl", map("total", 260_000L), NAMES);    // crosses 250k
		assertEquals(1, s.pendingBeats());
		s.observe("pnl", map("total", 300_000L), NAMES);    // same bucket, no second line
		s.observe("pnl", map("total", 480_000L), NAMES);
		assertEquals(1, s.pendingBeats());
		s.observe("pnl", map("total", 501_000L), NAMES);    // crosses 500k
		assertEquals(2, s.pendingBeats());
	}

	/**
	 * observe() runs inside emit(), which runs inside a @Subscribe handler. A
	 * malformed payload or a name lookup that blows up must cost a beat, never
	 * the event that was being recorded when it happened.
	 */
	@Test
	public void aBadEventCannotAbortTheHandlerItArrivedIn()
	{
		final StreamerService s = service(cfg(true, "https://x/v1", "k"));
		s.start();
		s.observe("geOffer", map("state", "SOLD", "item", "not a number",
			"qtySold", "nor this"), NAMES);
		s.observe("anomaly", map("name", "Dragon scimitar"), NAMES);
		s.observe("itemSpawn", map("id", 1333, "value", 60_000L, "worthRatio", 0.5),
			id ->
			{
				throw new IllegalStateException("client says no");
			});
		// still alive, still collecting
		s.observe("levelUp", map("skill", "Fishing", "level", 62), NAMES);
		assertTrue("a live beat still lands after a bad one", s.pendingBeats() >= 1);
	}

	@Test
	public void theReelIsBoundedSoAPromptCannotGrowWithTheSession()
	{
		final StreamerService s = service(cfg(true, "https://x/v1", "k"));
		s.start();
		for (int i = 0; i < 100; i++)
		{
			s.observe("levelUp", map("skill", "Fishing", "level", i), NAMES);
		}
		assertEquals(StreamerService.MAX_BEATS, s.pendingBeats());
	}

	@Test
	public void staleBeatsAreDroppedRatherThanNarratedLate()
	{
		final StreamerService s = service(cfg(true, "https://x/v1", "k"));
		s.start();
		s.observe("levelUp", map("skill", "Fishing", "level", 62), NAMES);
		assertEquals(1, s.pendingBeats());
		// nobody wants "he levelled fishing" ten minutes after he levelled fishing
		s.tick(System.currentTimeMillis() + StreamerService.BEAT_TTL_MS + 1_000);
		assertEquals(0, s.pendingBeats());
	}

	// ================= when it may speak =================

	@Test
	public void rateLimitHoldsTheFloorTheConfigAsksFor()
	{
		final long t = 1_000_000L;
		assertTrue(StreamerService.due(t, t - 46_000, 45, true, false));
		assertFalse("too soon", StreamerService.due(t, t - 44_000, 45, true, false));
		assertFalse("nothing to say", StreamerService.due(t, t - 90_000, 45, false, false));
		assertFalse("one in the air already",
			StreamerService.due(t, t - 90_000, 45, true, true));
		// the very first line does not have to wait for a previous one
		assertTrue(StreamerService.due(t, 0, 45, true, false));
	}

	@Test
	public void theIntervalHasAFloorTheConfigCannotUndercut()
	{
		final long t = 1_000_000L;
		// asking for one line per second would be a paid request every second
		assertFalse(StreamerService.due(t, t - 5_000, 1, true, false));
		assertTrue(StreamerService.due(t, t - StreamerService.MIN_SECS * 1000L, 0, true, false));
	}

	// ================= what gets sent =================

	@Test
	public void baseUrlIsNormalisedHoweverThePlayerPastedIt()
	{
		final String want = "https://api.groq.com/openai/v1/chat/completions";
		assertEquals(want, StreamerService.chatUrl("https://api.groq.com/openai/v1"));
		assertEquals(want, StreamerService.chatUrl("https://api.groq.com/openai/v1/"));
		assertEquals(want, StreamerService.chatUrl("  https://api.groq.com/openai/v1//  "));
		assertEquals(want, StreamerService.chatUrl(want));
	}

	@Test
	public void thePromptCarriesOnlyTheBeatsAndForbidsInventingMore()
	{
		final String sys = StreamerService.systemPrompt(null);
		assertTrue("the model must be told not to invent drops", sys.contains("Never invent"));
		assertTrue("a commentator is not a coach — this is the bot line",
			sys.contains("Never tell the player what to click"));
		// a live model called the player "she" on the first real run; the stream is
		// not the place to find out the caster guessed wrong
		assertTrue("the co-host must not guess the player's gender", sys.contains("\"they\""));
		assertTrue(StreamerService.systemPrompt("a gremlin").contains("a gremlin"));

		final String user = StreamerService.userPrompt("Fishing · 12 min in",
			List.of("levelled Fishing to 62", "DIED"));
		assertTrue(user.contains("Session so far: Fishing · 12 min in"));
		assertTrue(user.contains("- levelled Fishing to 62"));
		assertTrue(user.contains("- DIED"));
		// no context is not an empty label
		assertFalse(StreamerService.userPrompt("", List.of("DIED")).contains("Session so far"));
	}

	@Test
	public void theRequestBodyIsWhatAnOpenAiEndpointExpects()
	{
		final String json = StreamerService.chatBody(new Gson(), "llama-3.3-70b-versatile",
			"SYS", "USR");
		assertTrue(json, json.contains("\"model\":\"llama-3.3-70b-versatile\""));
		assertTrue(json, json.contains("\"role\":\"system\""));
		assertTrue(json, json.contains("\"content\":\"SYS\""));
		assertTrue(json, json.contains("\"role\":\"user\""));
		assertTrue(json, json.contains("\"content\":\"USR\""));
		assertTrue(json, json.contains("\"max_tokens\""));
	}

	@Test
	public void modelOutputIsTreatedAsTextFromAStranger()
	{
		assertEquals("Third scimitar this hour.",
			StreamerService.cleanLine("  \"Third scimitar this hour.\"  "));
		assertEquals("Third scimitar this hour.",
			StreamerService.cleanLine("'Third scimitar this hour.'"));
		assertEquals("one line now", StreamerService.cleanLine("one\n  line\tnow"));
		assertNull(StreamerService.cleanLine(null));
		assertNull(StreamerService.cleanLine("   "));

		final StringBuilder rant = new StringBuilder();
		while (rant.length() < 900)
		{
			rant.append("and another thing ");
		}
		final String capped = StreamerService.cleanLine(rant.toString());
		assertEquals(StreamerService.MAX_LINE, capped.length());
		assertTrue(capped.endsWith("…"));
	}

	/** Compressed audio played as samples is a scream in the player's headphones. */
	@Test
	public void onlyBytesDeclaredAsPcmAreEverPlayedAsPcm()
	{
		final byte[] pcm = new byte[4096];
		for (int i = 0; i < pcm.length; i += 2)
		{
			pcm[i] = (byte) (i % 97);
			pcm[i + 1] = (byte) (i % 13);
		}
		assertTrue(VoicePlayer.isRawPcm("audio/pcm", pcm));
		assertTrue(VoicePlayer.isRawPcm("audio/L16; rate=24000", pcm));
		assertTrue(VoicePlayer.isRawPcm("application/octet-stream", pcm));

		// mp3 is a real answer from a real service and we have no decoder for it
		assertFalse(VoicePlayer.isRawPcm("audio/mpeg", pcm));
		assertFalse(VoicePlayer.isRawPcm("audio/ogg", pcm));
		assertFalse("unlabelled bytes are never assumed to be samples",
			VoicePlayer.isRawPcm(null, pcm));

		/*
		 * The regression this replaced: 0xFF 0xFF is BOTH an mp3 frame sync and the
		 * sample value -1, and real speech starts near silence. Sniffing for the
		 * sync rejected live ElevenLabs PCM (measured 2026-08-10). Declared type
		 * wins; a leading -1 sample is just audio.
		 */
		final byte[] startsAtMinusOne = pcm.clone();
		startsAtMinusOne[0] = (byte) 0xFF;
		startsAtMinusOne[1] = (byte) 0xFF;
		assertTrue("a leading -1 sample is audio, not an mp3 header",
			VoicePlayer.isRawPcm("audio/pcm", startsAtMinusOne));

		// magic bytes may still REJECT a mislabelled body, never accept one
		final byte[] mislabelled = pcm.clone();
		mislabelled[0] = 'I';
		mislabelled[1] = 'D';
		mislabelled[2] = '3';
		assertFalse(VoicePlayer.isRawPcm("audio/pcm", mislabelled));

		final byte[] jsonError = pcm.clone();
		jsonError[0] = '{';
		assertFalse("a JSON error body served with a 200 is not audio",
			VoicePlayer.isRawPcm("application/octet-stream", jsonError));

		assertFalse(VoicePlayer.isRawPcm("audio/pcm", null));
		assertFalse(VoicePlayer.isRawPcm("audio/pcm", new byte[16]));
		assertFalse("16-bit samples cannot come in an odd number of bytes",
			VoicePlayer.isRawPcm("audio/pcm", new byte[4097]));
	}
}
