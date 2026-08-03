package com.glamardor.voicesubtitles.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Hooks the settings screen into Mod Menu. Only loaded through the "modmenu"
 * entrypoint, so the mod runs fine when Mod Menu is absent.
 */
public class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return com.glamardor.voicesubtitles.gui.ConfigScreenFactory::create;
	}
}
