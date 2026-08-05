package com.glamardor.voicesubtitles.hud;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;

import java.lang.reflect.Method;

/**
 * Getting on with Absolute Cinema Camera.
 *
 * <p>That mod puts the game in a film: black bars top and bottom, and the whole
 * interface out of the way. It does that by cancelling the HUD outright, and
 * draws the chat back in by hand afterwards, on the grounds that on a roleplay
 * server half the scene happens in text and losing it would make the mode
 * unusable. Exactly the same argument applies to subtitles, and rather more
 * strongly to somebody who reads the conversation instead of hearing it.
 *
 * <p>So while cinema mode is hiding the interface, the feed is drawn here, and
 * sat on top of the bottom bar rather than left where the hotbar used to be.
 *
 * <p>Reached by reflection and only when the mod is actually installed: this is
 * a courtesy between two mods, not a dependency, and Voice Subtitles must build
 * and run with no knowledge of it.
 */
final class CinemaCompat {

	private static final String MOD_ID = "absolutecinema";
	private static final String OVERLAY = "com.glamardor.absolutecinema.render.CinemaOverlay";

	private static boolean resolved;
	private static Method hidingHud;
	private static Method barHeight;

	private CinemaCompat() {
	}

	/** @return true while cinema mode is hiding the interface */
	static boolean hidingHud() {
		resolve();
		if (hidingHud == null) {
			return false;
		}
		try {
			return (boolean) hidingHud.invoke(null);
		} catch (Throwable t) {
			// A version that renamed it: stop asking rather than throwing every frame.
			hidingHud = null;
			return false;
		}
	}

	/** @return how far the bottom bar reaches up, so the feed can sit above it */
	static int barHeight(DrawContext context) {
		if (barHeight == null) {
			return 0;
		}
		try {
			return (int) barHeight.invoke(null, context);
		} catch (Throwable t) {
			barHeight = null;
			return 0;
		}
	}

	private static synchronized void resolve() {
		if (resolved) {
			return;
		}
		resolved = true;
		if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
			return;
		}
		try {
			Class<?> overlay = Class.forName(OVERLAY);
			hidingHud = overlay.getMethod("shouldHideHud");
			barHeight = overlay.getMethod("barHeight", DrawContext.class);
			VoiceSubtitles.LOGGER.info("Absolute Cinema Camera found; subtitles will stay visible in cinema mode");
		} catch (Throwable t) {
			VoiceSubtitles.LOGGER.warn("Absolute Cinema Camera is installed but its overlay could not be read; "
					+ "subtitles will hide with the interface in cinema mode", t);
			hidingHud = null;
			barHeight = null;
		}
	}
}
