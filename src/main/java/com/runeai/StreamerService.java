package com.runeai;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Streamer mode: an LLM co-host that narrates the run while you play, so the
 * RuneLite window can be the only source in OBS.
 *
 * <p><b>Default OFF, and inert by construction when off.</b> {@link #observe}
 * and {@link #tick} return on their first line unless {@code streamerMode} is
 * on, so no beat is ever collected and no request is ever built; the plugin
 * additionally does not call them. With no base URL or no key it stays inert one
 * step further in: beats accumulate locally and nothing is ever sent.
 *
 * <p><b>Where the material comes from.</b> The co-host reads the event stream
 * the recorder already writes — {@code RuneAIPlugin.emit()} hands every event to
 * {@link #observe}, which keeps a handful of types and throws the rest away.
 * There is no second event system, and there is no state here that the plugin
 * does not already have.
 *
 * <p><b>What leaves the machine, when it is switched on.</b> One short text
 * prompt per interval containing the beats below and a one-line session summary:
 * item names, gp amounts, skill levels, hitpoints. Not your account name, not
 * your position, not the recordings, not the event log. Nothing is sent while
 * the feature is off, and the config item carries the third-party warning.
 *
 * <p>Every call is asynchronous on OkHttp's dispatcher with a hard call timeout,
 * one request in flight at a time, and a configurable floor on how often the
 * co-host is allowed to open its mouth.
 */
@Slf4j
@Singleton
class StreamerService
{
	/**
	 * The warning the config item has to carry, because this is the only feature
	 * in RuneAI that talks to anything off the player's machine.
	 */
	static final String THIRD_PARTY_WARNING =
		"Streamer mode sends a short text summary of what just happened in your session "
			+ "(item names, gp amounts, levels, hitpoints) to whichever LLM endpoint you "
			+ "configure, and optionally to ElevenLabs for speech. Both are third-party "
			+ "servers that are not run by RuneAI or RuneLite, and you supply your own API "
			+ "keys. Nothing is sent while this is off. Your recordings are never sent at all.";

	private static final String UA =
		"RuneAI RuneLite plugin (github.com/lordbasilaiassistant-sudo/runeai)";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String TTS_URL = "https://api.elevenlabs.io/v1/text-to-speech/";

	/** Never let a stalled endpoint hold a connection, and never retry into a wall. */
	private static final long CHAT_TIMEOUT_S = 12;
	private static final long TTS_TIMEOUT_S = 20;

	/** Beats older than this are stale colour commentary — drop them unspoken. */
	static final long BEAT_TTL_MS = 4 * 60_000;
	/** A prompt is a highlight reel, not a log. */
	static final int MAX_BEATS = 10;
	/** One line of overhead text, not a paragraph. */
	static final int MAX_LINE = 180;
	/** Floor on the interval, whatever the config says — this costs the player money. */
	static final int MIN_SECS = 15;

	/** Ground loot worth talking about: a big absolute number, or big for this player. */
	private static final long LOOT_BEAT_GP = 50_000;
	private static final double LOOT_BEAT_RATIO = 0.02;
	/** Session P&L is only a beat when it crosses a round number. */
	private static final long PNL_BUCKET = 250_000;

	/**
	 * The event types the co-host may see. Everything else the recorder writes —
	 * positions, varbits, animations, containers, clicks, spawns — never reaches
	 * a prompt, which is most of why the prompt stays small and dull.
	 */
	static final Set<String> INTERESTING = Set.of(
		"levelUp", "geOffer", "anomaly", "guidance", "death", "itemSpawn", "pnl");

	private final RuneAIConfig config;
	private final Gson gson;
	private final OkHttpClient chatHttp;
	private final OkHttpClient ttsHttp;
	private final VoicePlayer voice;

	private final ConcurrentLinkedDeque<long[]> beatStamps = new ConcurrentLinkedDeque<>();
	private final ConcurrentLinkedDeque<String> beats = new ConcurrentLinkedDeque<>();
	private final Queue<String> chatOut = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean inFlight = new AtomicBoolean();

	private volatile long lastSpokeMs;
	private volatile long pnlBucket;
	private volatile String context = "";
	private volatile String status = "off";
	private volatile int spoken;

	@Inject
	StreamerService(RuneAIConfig config, Gson gson, OkHttpClient http, VoicePlayer voice)
	{
		this.config = config;
		this.gson = gson;
		this.voice = voice;
		// derived from the injected client so the connection pool and dispatcher are
		// shared — a plugin must never stand up its own OkHttpClient, but it does
		// have to bound its own calls
		this.chatHttp = http.newBuilder()
			.callTimeout(CHAT_TIMEOUT_S, TimeUnit.SECONDS)
			.build();
		this.ttsHttp = http.newBuilder()
			.callTimeout(TTS_TIMEOUT_S, TimeUnit.SECONDS)
			.build();
	}

	// ================= lifecycle =================

	/** Called only when the master toggle is on. Nothing here starts a thread. */
	void start()
	{
		reset();
		status = configured() ? "ready" : "no endpoint or key — commentary is inert";
		log.info("streamer mode on: {}", status);
	}

	void stop()
	{
		reset();
		status = "off";
	}

	private void reset()
	{
		beats.clear();
		beatStamps.clear();
		chatOut.clear();
		lastSpokeMs = 0;
		pnlBucket = 0;
		spoken = 0;
	}

	String status()
	{
		return status;
	}

	int spokenLines()
	{
		return spoken;
	}

	/** Beats waiting to be narrated. Zero while the feature is off, by construction. */
	int pendingBeats()
	{
		return beats.size();
	}

	/** True only when there is somewhere to send a prompt AND a key to send it with. */
	boolean configured()
	{
		return config.streamerMode()
			&& !blank(config.streamerBaseUrl())
			&& !blank(config.streamerKey());
	}

	private static boolean blank(String s)
	{
		return s == null || s.trim().isEmpty();
	}

	// ================= the event tap =================

	/**
	 * One event off the recorder's stream. Client thread, so this does nothing
	 * but a set lookup, a string build and an append — {@code itemName} is
	 * resolved here precisely because here is the only place it is safe to.
	 */
	void observe(String type, Map<String, Object> data, IntFunction<String> itemName)
	{
		if (!config.streamerMode() || type == null || !INTERESTING.contains(type))
		{
			return;
		}
		try
		{
			collect(type, data, itemName);
		}
		catch (Exception ex)
		{
			// this runs inside emit(), which runs inside an event handler. A cosmetic
			// feature does not get to abort the recorder or a @Subscribe mid-flight.
			log.warn("streamer could not read a {} event", type, ex);
		}
	}

	private void collect(String type, Map<String, Object> data, IntFunction<String> itemName)
	{
		final String beat;
		if ("pnl".equals(type))
		{
			// only when the running total crosses a round number, or every tick of
			// a good streak becomes a beat and the reel becomes a spreadsheet
			final long total = asLong(data.get("total"));
			final long bucket = total / PNL_BUCKET;
			if (bucket == pnlBucket)
			{
				return;
			}
			pnlBucket = bucket;
			beat = bucket == 0 ? null : String.format("session profit crossed %,d gp", total);
		}
		else
		{
			beat = beatOf(type, data, itemName);
		}
		if (beat == null)
		{
			return;
		}
		beats.addLast(beat);
		beatStamps.addLast(new long[]{System.currentTimeMillis()});
		while (beats.size() > MAX_BEATS)
		{
			beats.pollFirst();
			beatStamps.pollFirst();
		}
	}

	/**
	 * The whole vocabulary, as a pure function of one recorded event. Returns
	 * null for anything not worth a sentence — which is most of what arrives.
	 */
	static String beatOf(String type, Map<String, Object> d, IntFunction<String> itemName)
	{
		if (d == null)
		{
			return null;
		}
		switch (type)
		{
			case "levelUp":
				return String.format("levelled %s to %d",
					d.get("skill"), asLong(d.get("level")));

			case "geOffer":
			{
				final String state = String.valueOf(d.get("state"));
				if (!"BOUGHT".equals(state) && !"SOLD".equals(state))
				{
					return null; // an offer being placed is not a result
				}
				final long qty = asLong(d.get("qtySold"));
				if (qty <= 0)
				{
					return null;
				}
				final long secs = asLong(d.get("fillSecs"));
				return String.format("GE %s %,d x %s for %,d gp%s",
					"BOUGHT".equals(state) ? "buy filled:" : "sell filled:",
					qty, name(itemName, d.get("item")), asLong(d.get("spent")),
					secs > 0 ? " (" + humanSecs(secs) + " to fill)" : "");
			}

			case "anomaly":
				return String.format("market: %s moved %+.0f%% over %d min on real volume",
					d.get("name"), asDouble(d.get("movePct")), asLong(d.get("spanMins")));

			case "guidance":
			{
				if (!"eat".equals(d.get("kind")))
				{
					return null;
				}
				return String.format("close call: down to %d/%d hp under attack%s",
					asLong(d.get("hp")), asLong(d.get("max")),
					Boolean.TRUE.equals(d.get("elevated")) ? " (danger model had already raised the alarm)" : "");
			}

			case "death":
			{
				final Object actor = d.get("actor");
				if (!(actor instanceof Map) || !Boolean.TRUE.equals(((Map<?, ?>) actor).get("local")))
				{
					return null; // an npc dying is a kill, and kills are not rare enough to narrate
				}
				return "DIED";
			}

			case "itemSpawn":
			{
				final long value = asLong(d.get("value"));
				final double ratio = asDouble(d.get("worthRatio"));
				if (value < LOOT_BEAT_GP && ratio < LOOT_BEAT_RATIO)
				{
					return null;
				}
				return String.format("drop: %s worth %,d gp",
					name(itemName, d.get("id")), value);
			}

			default:
				return null;
		}
	}

	private static String name(IntFunction<String> itemName, Object id)
	{
		final int i = (int) asLong(id);
		if (itemName == null)
		{
			return "item " + i;
		}
		final String n = itemName.apply(i);
		return n == null || n.isEmpty() ? "item " + i : n;
	}

	static String humanSecs(long secs)
	{
		return secs < 90 ? secs + "s" : (secs / 60) + "m";
	}

	private static long asLong(Object o)
	{
		return o instanceof Number ? ((Number) o).longValue() : 0;
	}

	private static double asDouble(Object o)
	{
		return o instanceof Number ? ((Number) o).doubleValue() : 0;
	}

	// ================= the co-host =================

	/** One line of session context, refreshed by the plugin. Never a player name. */
	void setContext(String ctx)
	{
		context = ctx == null ? "" : ctx;
	}

	/**
	 * Called from the game tick. Cheap: a clock comparison and, at most, one
	 * non-blocking {@code enqueue}. The request itself runs on OkHttp's threads.
	 */
	void tick(long nowMs)
	{
		if (!config.streamerMode())
		{
			return;
		}
		expire(nowMs);
		if (!configured())
		{
			status = "no endpoint or key — commentary is inert";
			return;
		}
		if (!due(nowMs, lastSpokeMs, config.streamerSecs(), !beats.isEmpty(), inFlight.get()))
		{
			return;
		}
		final List<String> reel = new ArrayList<>(beats);
		beats.clear();
		beatStamps.clear();
		lastSpokeMs = nowMs;
		dispatch(reel);
	}

	/** Stale beats are dropped rather than narrated late. */
	private void expire(long nowMs)
	{
		while (!beatStamps.isEmpty() && nowMs - beatStamps.peekFirst()[0] > BEAT_TTL_MS)
		{
			beatStamps.pollFirst();
			beats.pollFirst();
		}
	}

	/**
	 * Rate limit, isolated so it can be tested: something to say, nothing already
	 * in the air, and at least the configured interval since the last line.
	 */
	static boolean due(long nowMs, long lastMs, int everySecs, boolean hasBeats, boolean inFlight)
	{
		if (!hasBeats || inFlight)
		{
			return false;
		}
		return nowMs - lastMs >= Math.max(MIN_SECS, everySecs) * 1000L;
	}

	/**
	 * {@code baseUrl} is whatever the player pasted. Accept a bare root, a
	 * trailing slash, or the full endpoint they copied out of someone's docs.
	 */
	static String chatUrl(String baseUrl)
	{
		String b = baseUrl == null ? "" : baseUrl.trim();
		while (b.endsWith("/"))
		{
			b = b.substring(0, b.length() - 1);
		}
		return b.endsWith("/chat/completions") ? b : b + "/chat/completions";
	}

	/**
	 * The co-host's brief. Everything in here is a constraint, because the
	 * failure mode of a game commentator is not silence, it is confidently
	 * inventing a drop that never happened.
	 */
	static String systemPrompt(String persona)
	{
		final String p = blank(persona)
			? "a dry, quick Old School RuneScape co-host who has watched this player for hours"
			: persona.trim();
		return "You are " + p + ". You are commentating a live Old School RuneScape stream "
			+ "next to the player, who is doing the playing.\n"
			+ "Rules:\n"
			+ "- Reply with ONE sentence of commentary, under 25 words. No preamble, no quotes, no emoji.\n"
			+ "- Use ONLY the events listed. Never invent a drop, a number, a level or a death.\n"
			+ "- If the events are dull, say something dry about them being dull. Do not embellish.\n"
			+ "- Never tell the player what to click, where to go, or what to buy. You are a "
			+ "commentator, not a coach — the player makes every decision.\n"
			+ "- Talk about the player in the third person, the way a caster does, and always "
			+ "as \"they\". You do not know who they are, and guessing wrong goes out on stream.";
	}

	/** The reel itself. Deliberately plain text: it is also what gets logged. */
	static String userPrompt(String context, List<String> reel)
	{
		final StringBuilder sb = new StringBuilder();
		if (!blank(context))
		{
			sb.append("Session so far: ").append(context.trim()).append('\n');
		}
		sb.append("Just happened:\n");
		for (String b : reel)
		{
			sb.append("- ").append(b).append('\n');
		}
		return sb.append("Call it.").toString();
	}

	/** Model output is text from a stranger: one line, bounded, unquoted. */
	static String cleanLine(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String s = raw.trim().replaceAll("\\s+", " ");
		while (s.length() > 1
			&& ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
			|| (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'')))
		{
			s = s.substring(1, s.length() - 1).trim();
		}
		if (s.length() > MAX_LINE)
		{
			s = s.substring(0, MAX_LINE - 1).trim() + "…";
		}
		return s.isEmpty() ? null : s;
	}

	// ---- request/response shapes for gson ----

	private static class ChatRequest
	{
		String model;
		List<Msg> messages;
		int max_tokens;
		double temperature;
	}

	private static class Msg
	{
		String role;
		String content;

		Msg(String role, String content)
		{
			this.role = role;
			this.content = content;
		}
	}

	private static class ChatResponse
	{
		List<Choice> choices;
	}

	private static class Choice
	{
		Msg message;
	}

	private static class TtsRequest
	{
		String text;
		String model_id;
	}

	/** Build the chat body. Static and gson-fed so a test can read the JSON. */
	static String chatBody(Gson gson, String model, String system, String user)
	{
		final ChatRequest r = new ChatRequest();
		r.model = blank(model) ? "gpt-4o-mini" : model.trim();
		r.messages = List.of(new Msg("system", system), new Msg("user", user));
		r.max_tokens = 90;
		r.temperature = 0.9;
		return gson.toJson(r);
	}

	private void dispatch(List<String> reel)
	{
		final String user = userPrompt(context, reel);
		final String body = chatBody(gson, config.streamerModel(),
			systemPrompt(config.streamerPersona()), user);
		final Request req;
		try
		{
			req = new Request.Builder()
				.url(chatUrl(config.streamerBaseUrl()))
				.header("Authorization", "Bearer " + config.streamerKey().trim())
				.header("User-Agent", UA)
				.post(RequestBody.create(JSON, body))
				.build();
		}
		catch (IllegalArgumentException bad)
		{
			// whatever the player pasted is not a URL; this runs on the client
			// thread, so say so in the status instead of throwing every interval
			status = "endpoint is not a valid URL";
			return;
		}

		inFlight.set(true);
		log.debug("streamer prompt:\n{}", user);
		chatHttp.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				inFlight.set(false);
				status = "chat request failed: " + e.getMessage();
				log.warn("streamer chat call failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					final String payload = r.body() == null ? "" : r.body().string();
					if (!r.isSuccessful())
					{
						status = "endpoint returned HTTP " + r.code();
						log.warn("streamer chat HTTP {}: {}", r.code(),
							payload.length() > 300 ? payload.substring(0, 300) : payload);
						return;
					}
					final ChatResponse cr = gson.fromJson(payload, ChatResponse.class);
					final String line = cleanLine(cr == null || cr.choices == null
						|| cr.choices.isEmpty() || cr.choices.get(0).message == null
						? null : cr.choices.get(0).message.content);
					if (line == null)
					{
						status = "endpoint returned no commentary";
						return;
					}
					spoken++;
					status = "live · " + spoken + " lines";
					chatOut.offer(line);
					speak(line);
				}
				catch (Exception ex)
				{
					status = "chat response unreadable";
					log.warn("streamer chat response failed", ex);
				}
				finally
				{
					inFlight.set(false);
				}
			}
		});
	}

	/**
	 * Say the line. With a TTS key we fetch audio first and hand it to the voice
	 * player, which lip-syncs the mascot off its amplitude exactly as it does for
	 * the bundled clips; without one the line still shows in the mascot's bubble.
	 *
	 * <p><b>The bundled Kokoro clips cannot do this.</b> They are pre-rendered
	 * WAVs for a fixed set of coaching lines — there is no local synthesiser in the plugin,
	 * so arbitrary commentary has no local voice today. Text-only is the honest
	 * default and BYO-key TTS is the opt-in upgrade.
	 */
	private void speak(String line)
	{
		final String key = config.streamerTtsKey();
		if (blank(key))
		{
			voice.say(line, null, null);
			return;
		}
		final TtsRequest t = new TtsRequest();
		t.text = line;
		t.model_id = "eleven_flash_v2_5";
		final String voiceId = blank(config.streamerVoiceId())
			? "21m00Tcm4TlvDq8ikWAM" : config.streamerVoiceId().trim();
		final Request req = new Request.Builder()
			// raw signed 16-bit mono PCM: the same shape the bundled clips decode
			// to, so the envelope and the lip-sync need no second code path
			.url(TTS_URL + voiceId + "?output_format=pcm_24000")
			.header("xi-api-key", key.trim())
			.header("accept", "audio/pcm")
			.header("User-Agent", UA)
			.post(RequestBody.create(JSON, gson.toJson(t)))
			.build();
		ttsHttp.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("streamer tts failed, falling back to text", e);
				voice.say(line, null, null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				byte[] audio = null;
				String type = null;
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						// the declared type is what tells the player whether these bytes
						// are samples or a compressed stream it has no decoder for
						type = r.header("content-type");
						audio = r.body().bytes();
					}
					else
					{
						status = r.code() == 402
							// measured: a free ElevenLabs account is refused LIBRARY voices,
							// and this is the failure a streamer will actually hit
							? "tts HTTP 402 — that voice id needs a paid ElevenLabs plan"
							: "tts returned HTTP " + r.code();
						log.warn("streamer tts HTTP {}", r.code());
					}
				}
				catch (Exception ex)
				{
					log.warn("streamer tts body unreadable", ex);
				}
				voice.say(line, audio, type);
			}
		});
	}

	/**
	 * A commentary line waiting to be printed in chat, or null. Drained by the
	 * plugin on the client thread, because {@code addChatMessage} lives there.
	 */
	String pollChatLine()
	{
		return chatOut.poll();
	}
}
