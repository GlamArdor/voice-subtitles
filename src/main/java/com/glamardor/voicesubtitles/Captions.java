package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.minecraft.util.math.MathHelper;

/**
 * What a caption says and how solid it looks – shared by the bottom feed and
 * the captions floating above heads, so the two never disagree.
 */
public final class Captions {

	private static final long FADE_IN_MS = 160L;
	private static final long FADE_OUT_MS = 600L;

	private Captions() {
	}

	/**
	 * How long this line stays up after it is finished.
	 *
	 * <p>Scaled by length: "привет" is gone almost at once, a long sentence stays
	 * long enough to read to the end. Speech gets the same treatment, so a
	 * caption lingers just past the moment the speaker stops talking.
	 */
	public static long lifetimeOf(SubtitleFeed.Line line) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		long ms = cfg.lineLifetimeMs + (long) line.text().length() * cfg.lifetimePerCharMs;
		return Math.min(ms, cfg.maxLifetimeMs);
	}

	/** The speech itself, or an animated placeholder while nothing is recognised yet. */
	public static String bodyOf(SubtitleFeed.Line line, long now) {
		if (!line.placeholder()) {
			return line.partial() ? line.text() + "…" : line.text();
		}
		int dots = (int) ((now - line.startedAt()) / 400L % 3L) + 1;
		return ".".repeat(dots);
	}

	/**
	 * Opacity 0–255: the line's own fade in/out, dimmed by how far away and how
	 * quietly it was spoken.
	 */
	public static int alphaOf(SubtitleFeed.Line line, long now) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();

		long lifetime = lifetimeOf(line);

		float fade = 1F;
		if (cfg.fade) {
			long age = now - line.startedAt();
			float in = age >= FADE_IN_MS ? 1F : age / (float) FADE_IN_MS;

			float out = 1F;
			if (!line.open()) {
				long remaining = lifetime - (now - line.closedAt());
				if (remaining <= 0L) {
					return 0;
				}
				if (remaining < FADE_OUT_MS) {
					out = remaining / (float) FADE_OUT_MS;
				}
			}
			fade = Math.min(in, out);
		} else if (!line.open() && now - line.closedAt() > lifetime) {
			return 0;
		}

		float alpha = MathHelper.clamp(fade, 0F, 1F) * line.rangeAlpha();
		return Math.round(MathHelper.clamp(alpha, 0F, 1F) * 255F);
	}
}
