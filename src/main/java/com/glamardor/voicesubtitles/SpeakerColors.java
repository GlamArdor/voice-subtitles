package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.minecraft.util.math.MathHelper;

import java.util.UUID;

/**
 * Picks the colour a speaker's subtitles are drawn in.
 *
 * <p>Three modes, in the order they take effect:
 * <ol>
 *   <li>a colour assigned by hand always wins;</li>
 *   <li>{@link VoiceSubtitlesConfig.ColorMode#BY_UUID} derives one from the
 *       player's UUID – the same function on every client, so everybody running
 *       the mod sees the same person in the same colour;</li>
 *   <li>otherwise white.</li>
 * </ol>
 *
 * <p>The generated colours vary only in hue: saturation and brightness are
 * fixed, low and high respectively, so no speaker ever ends up with dark text
 * that disappears against a night sky.
 */
public final class SpeakerColors {

	public static final int WHITE = 0xFFFFFF;

	private static final float SATURATION = 0.45F;
	private static final float BRIGHTNESS = 1.0F;

	private SpeakerColors() {
	}

	/** @return the RGB colour (no alpha) for this speaker. */
	public static int colorOf(UUID id) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();

		Integer manual = cfg.manualColorOf(id);
		if (manual != null) {
			return manual;
		}
		return switch (cfg.colorMode) {
			case BY_UUID -> fromUuid(id);
			case MANUAL, WHITE -> WHITE;
		};
	}

	/**
	 * A stable hue per player.
	 *
	 * <p>Both halves of the UUID are folded together so that servers handing out
	 * sequential offline-mode UUIDs still spread across the wheel.
	 */
	public static int fromUuid(UUID id) {
		long bits = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
		bits ^= bits >>> 32;
		bits ^= bits >>> 16;
		float hue = Math.floorMod(bits, 360L) / 360F;
		return MathHelper.hsvToRgb(hue, SATURATION, BRIGHTNESS) & 0xFFFFFF;
	}
}
