package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.speech.SpeechRecognition;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.minecraft.client.MinecraftClient;

import java.util.UUID;

/**
 * Registers this mod as a Simple Voice Chat plugin and turns incoming voice
 * into subtitle lines.
 *
 * <p>Three kinds of incoming audio are handled:
 * <ul>
 *   <li>{@link ClientReceiveSoundEvent.EntitySound} – proximity chat, carries
 *       the speaker's entity UUID plus the distance their voice reaches;</li>
 *   <li>{@link ClientReceiveSoundEvent.LocationalSound} and
 *       {@link ClientReceiveSoundEvent.StaticSound} – groups and radios, where
 *       only the sender's id is known;</li>
 *   <li>{@link ClientSoundEvent} – our own microphone, before it is sent.</li>
 * </ul>
 *
 * <p>All of these fire on the voice chat's audio thread. Nothing here touches
 * the game state directly – it only writes into {@link SpeakerTracker} and
 * {@link SubtitleFeed}, both of which are safe to write from any thread.
 */
public class VoicechatSubtitlePlugin implements VoicechatPlugin {

	public static final String PLUGIN_ID = "voice_subtitles";

	/** Set on the client thread once we know who we are; the audio thread only reads it. */
	private static volatile UUID selfId;

	@Override
	public String getPluginId() {
		return PLUGIN_ID;
	}

	@Override
	public void initialize(VoicechatApi api) {
		VoiceSubtitles.LOGGER.info("Simple Voice Chat plugin initialised");
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, this::onEntitySound);
		registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, this::onOtherSound);
		registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, this::onOtherSound);
		registration.registerEvent(ClientSoundEvent.class, this::onOwnSound);
		registration.registerEvent(ClientVoicechatConnectionEvent.class, this::onConnection);
	}

	/** Proximity voice: we know the speaker and how far their voice carries. */
	private void onEntitySound(ClientReceiveSoundEvent.EntitySound event) {
		UUID id = event.getEntityId();
		if (id == null) {
			return;
		}
		accept(id, event.getRawAudio(), event.isWhispering(), event.getDistance());
	}

	/** Groups, radios and anything else without an entity behind it. */
	private void onOtherSound(ClientReceiveSoundEvent event) {
		UUID id = event.getId();
		if (id == null) {
			return;
		}
		accept(id, event.getRawAudio(), false, -1F);
	}

	/** Our own microphone, captured before it goes out to the server. */
	private void onOwnSound(ClientSoundEvent event) {
		UUID id = selfId;
		if (id == null) {
			return;
		}
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.showSelf) {
			return;
		}
		accept(id, event.getRawAudio(), event.isWhispering(), -1F);
	}

	private void onConnection(ClientVoicechatConnectionEvent event) {
		if (!event.isConnected()) {
			SpeakerTracker.clear();
			SubtitleFeed.clear();
		}
	}

	/**
	 * The single funnel every audio frame goes through.
	 *
	 * <p>An empty frame is Simple Voice Chat's end-of-transmission marker: the
	 * speaker stopped, so their line is closed instead of extended.
	 */
	static void accept(UUID speaker, short[] audio, boolean whisper, float distance) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.enabled || !cfg.voiceEnabled()) {
			return;
		}
		if (audio == null || audio.length == 0) {
			// End of transmission: let the recogniser settle the sentence, and
			// close the line if it has nothing to add.
			SpeechRecognition.end(speaker);
			if (SpeechRecognition.state() != SpeechRecognition.State.READY) {
				SubtitleFeed.close(speaker);
			}
			return;
		}
		SpeakerTracker.onAudioFrame(speaker, audio, whisper, distance);
		SubtitleFeed.onAudio(speaker, whisper);
		SpeechRecognition.feed(speaker, audio);
	}

	/** Called from the client thread when we join a world. */
	public static void setSelfId(UUID id) {
		selfId = id;
	}

	/** @return our own UUID as far as the plugin knows it. */
	public static UUID selfId() {
		UUID id = selfId;
		if (id != null) {
			return id;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player == null ? null : client.player.getUuid();
	}
}
