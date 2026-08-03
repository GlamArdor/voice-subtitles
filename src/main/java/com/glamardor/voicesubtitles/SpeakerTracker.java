package com.glamardor.voicesubtitles;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is talking right now, how loud, and from how far away.
 *
 * <p>Fed from Simple Voice Chat's audio thread, read from the render thread, so
 * everything in here is concurrent and cheap – no allocation per audio frame
 * beyond the per-player state object.
 */
public final class SpeakerTracker {

	/** A player is considered talking for this long after their last audio frame. */
	private static final long TALK_HOLD_MS = 300L;

	/** Loudness scaling, so normal speech lands somewhere near 1.0. */
	private static final float VOLUME_GAIN = 6.0F;

	private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

	private SpeakerTracker() {
	}

	private static final class State {
		volatile long lastAudioAt;
		volatile float volume;
		volatile boolean whispering;
		/** Distance at which the sound can still be heard; -1 when not applicable. */
		volatile float audibleDistance = -1F;
	}

	/**
	 * Records one audio frame.
	 *
	 * @param id       the speaker
	 * @param audio    the raw 16 bit PCM frame
	 * @param whisper  whether the speaker is whispering
	 * @param distance audible distance in blocks, or -1 if unknown (static channels)
	 */
	public static void onAudioFrame(UUID id, short[] audio, boolean whisper, float distance) {
		State state = STATES.computeIfAbsent(id, k -> new State());
		state.lastAudioAt = System.currentTimeMillis();
		state.volume = loudness(audio);
		state.whispering = whisper;
		state.audibleDistance = distance;
	}

	public static boolean isTalking(UUID id) {
		State state = STATES.get(id);
		return state != null && System.currentTimeMillis() - state.lastAudioAt < TALK_HOLD_MS;
	}

	public static float volumeOf(UUID id) {
		State state = STATES.get(id);
		return state == null ? 0F : state.volume;
	}

	public static boolean isWhispering(UUID id) {
		State state = STATES.get(id);
		return state != null && state.whispering;
	}

	/** @return audible distance in blocks, or {@code null} when the channel does not report one. */
	public static Float audibleDistanceOf(UUID id) {
		State state = STATES.get(id);
		if (state == null || state.audibleDistance < 0F) {
			return null;
		}
		return state.audibleDistance;
	}

	/** Drops state for players who stopped talking a long time ago. */
	public static void prune() {
		long cutoff = System.currentTimeMillis() - 60_000L;
		STATES.entrySet().removeIf(e -> e.getValue().lastAudioAt < cutoff);
	}

	public static void clear() {
		STATES.clear();
	}

	/** RMS amplitude of a frame, normalised into a rough 0–1 loudness. */
	private static float loudness(short[] audio) {
		if (audio == null || audio.length == 0) {
			return 0F;
		}
		double sum = 0.0;
		for (short s : audio) {
			sum += (double) s * (double) s;
		}
		double rms = Math.sqrt(sum / audio.length) / 32768.0;
		return (float) Math.min(1.0, rms * VOLUME_GAIN);
	}
}
