package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.minecraft.util.math.MathHelper;

/**
 * What a caption says and how solid it looks – shared by the bottom feed and
 * the captions floating above heads, so the two never disagree.
 */
public final class Captions {

	/** Fading out takes longer than fading in: arriving should be quicker than leaving. */
	private static final float FADE_OUT_FACTOR = 2.5F;

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

	/**
	 * The speech itself, or an animated placeholder while nothing is recognised
	 * yet.
	 *
	 * <p>Text arrives in jumps, because a recogniser thinks in whole phrases, and
	 * a caption that grows in jumps reads as flickering. So it is revealed a few
	 * characters at a time instead, fast enough that it is never what anybody is
	 * waiting on: a sentence of sixty characters is whole in under a second.
	 *
	 * <p>Measured from when the words appeared rather than from when the line
	 * did. Those are a second apart – the line opens on the first sound, the
	 * words arrive once the recogniser has decided what they were – and counting
	 * from the wrong one meant the whole allowance was spent before there was
	 * anything to spend it on, so nothing was ever seen to arrive. A later pass
	 * over the same sentence does not restart it: the clock belongs to the line,
	 * not to the text.
	 */
	public static String bodyOf(SubtitleFeed.Line line, long now) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (line.placeholder()) {
			int dots = (int) ((now - line.startedAt()) / 400L % 3L) + 1;
			return ".".repeat(dots);
		}

		String text = line.text();
		if (cfg.typewriter) {
			// Letter by letter. It used to stop at the last space instead, so
			// that no half-written word was ever on screen, and the result was
			// that a caption arrived in four or five jumps – which is the thing
			// this was added to avoid. A letter every twenty milliseconds reads
			// as writing; a word every quarter of a second reads as flashing.
			int shown = line.reveal(now, cfg.typewriterSpeed);
			if (shown < text.length()) {
				text = text.substring(0, Math.max(shown, 0));
			}
		}
		return line.partial() ? text + "…" : text;
	}

	/**
	 * Opacity 0–255: the line's own fade in/out, dimmed by how far away and how
	 * quietly it was spoken.
	 */
	public static int alphaOf(SubtitleFeed.Line line, long now) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();

		long lifetime = lifetimeOf(line);

		float fade = 1F;
		if (cfg.fade && cfg.fadeMillis > 0) {
			long fadeIn = cfg.fadeMillis;
			long fadeOut = (long) (cfg.fadeMillis * FADE_OUT_FACTOR);
			long age = now - line.startedAt();
			float in = age >= fadeIn ? 1F : age / (float) fadeIn;

			float out = 1F;
			if (!line.open()) {
				long remaining = lifetime - (now - line.closedAt());
				if (remaining <= 0L) {
					return 0;
				}
				if (remaining < fadeOut) {
					out = remaining / (float) fadeOut;
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
