package com.glamardor.voicesubtitles;

import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared constants for the mod. */
public final class VoiceSubtitles {

	public static final String MOD_ID = "voice_subtitles";
	public static final Logger LOGGER = LoggerFactory.getLogger("Voice Subtitles");

	private VoiceSubtitles() {
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}
