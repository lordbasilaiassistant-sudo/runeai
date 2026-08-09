package com.runeai;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Plays premade voice callouts from bundled WAVs (resources/com/runeai/voice/).
 * Per-line cooldown so guidance never turns into nagging.
 */
@Slf4j
@Singleton
class VoicePlayer
{
	private static final long COOLDOWN_MS = 12_000;

	private final RuneAIConfig config;
	private final Map<String, Long> lastPlayed = new ConcurrentHashMap<>();

	@Inject
	VoicePlayer(RuneAIConfig config)
	{
		this.config = config;
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
		lastPlayed.put(key, now);

		final Thread t = new Thread(() ->
		{
			try (InputStream in = VoicePlayer.class.getResourceAsStream("/com/runeai/voice/" + key + ".wav"))
			{
				if (in == null)
				{
					log.warn("voice clip missing: {}", key);
					return;
				}
				final AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(in));
				final Clip clip = AudioSystem.getClip();
				clip.addLineListener(e ->
				{
					if (e.getType() == LineEvent.Type.STOP)
					{
						clip.close();
					}
				});
				clip.open(ais);
				clip.start();
			}
			catch (Exception ex)
			{
				log.warn("voice playback failed for {}", key, ex);
			}
		}, "runeai-voice");
		t.setDaemon(true);
		t.start();
	}
}
