package com.glamardor.voicesubtitles.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.lang.ref.WeakReference;

/**
 * Remembers which screen is ours.
 *
 * <p>Our settings screen is drawn without the usual blur and darkening, which
 * takes a mixin on {@link Screen} – and that mixin fires for every screen in the
 * game. So it has to be able to tell ours apart: anything else, including other
 * mods' Cloth Config screens, must keep the background it always had.
 *
 * <p>Held weakly, so a closed screen is not kept alive by this.
 */
public final class ConfigScreenMarker {

	private static WeakReference<Screen> ours = new WeakReference<>(null);

	private ConfigScreenMarker() {
	}

	public static <T extends Screen> T mark(T screen) {
		ours = new WeakReference<>(screen);
		return screen;
	}

	/** @return whether this screen is our settings screen and is currently open. */
	public static boolean isOurs(Screen screen) {
		Screen marked = ours.get();
		return marked != null && marked == screen && MinecraftClient.getInstance().currentScreen == screen;
	}
}
