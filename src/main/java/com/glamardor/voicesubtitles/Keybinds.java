package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.gui.SubtitleLogScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Keys for the three things worth doing mid-conversation without opening a menu.
 *
 * <p>All three ship unbound. Anything this mod could grab is a key somebody
 * already uses, and a subtitle mod that steals a key on install is a subtitle
 * mod that gets uninstalled.
 */
public final class Keybinds {

	private static final String CATEGORY = "category.voice_subtitles";

	private static KeyBinding toggle;
	private static KeyBinding placement;
	private static KeyBinding log;

	private Keybinds() {
	}

	public static void register() {
		toggle = bind("key.voice_subtitles.toggle");
		placement = bind("key.voice_subtitles.placement");
		log = bind("key.voice_subtitles.log");
	}

	private static KeyBinding bind(String translationKey) {
		return KeyBindingHelper.registerKeyBinding(new KeyBinding(
				translationKey, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
	}

	/** Called every client tick; a key may have been pressed more than once. */
	public static void tick(MinecraftClient client) {
		if (toggle == null) {
			return;
		}
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();

		while (toggle.wasPressed()) {
			cfg.enabled = !cfg.enabled;
			VoiceSubtitlesConfig.save();
			say(client, Text.translatable(cfg.enabled
					? "voice_subtitles.message.on"
					: "voice_subtitles.message.off"));
		}

		while (placement.wasPressed()) {
			// Voice and chat move together here. They can be set apart in the
			// settings, but a key that changes only half of what is on screen
			// reads as a key that did nothing.
			VoiceSubtitlesConfig.Placement next = nextPlacement(cfg.voicePlacement);
			cfg.voicePlacement = next;
			cfg.chatPlacement = next;
			VoiceSubtitlesConfig.save();
			say(client, Text.translatable("voice_subtitles.message.placement",
					Text.translatable("voice_subtitles.value.placement." + next.name().toLowerCase())));
		}

		while (log.wasPressed()) {
			if (client.currentScreen == null) {
				client.setScreen(new SubtitleLogScreen(null));
			}
		}
	}

	private static VoiceSubtitlesConfig.Placement nextPlacement(VoiceSubtitlesConfig.Placement current) {
		VoiceSubtitlesConfig.Placement[] values = VoiceSubtitlesConfig.Placement.values();
		return values[(current.ordinal() + 1) % values.length];
	}

	private static void say(MinecraftClient client, Text message) {
		if (client.player != null) {
			client.player.sendMessage(message, true);
		}
	}
}
