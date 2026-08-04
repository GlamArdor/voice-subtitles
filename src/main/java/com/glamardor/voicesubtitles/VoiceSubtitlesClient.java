package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.gui.LivePreview;
import com.glamardor.voicesubtitles.hud.OverHeadSubtitles;
import com.glamardor.voicesubtitles.hud.ProgressOverlay;
import com.glamardor.voicesubtitles.hud.SubtitleHud;
import com.glamardor.voicesubtitles.speech.DownloadState;
import com.glamardor.voicesubtitles.speech.SpeechRecognition;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Client entry point: loads the config, puts the subtitle feed on the HUD and
 * keeps the feed ticking.
 */
public class VoiceSubtitlesClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		VoiceSubtitlesConfig.get(); // load (and write defaults on first run)

		HudElementRegistry.addLast(VoiceSubtitles.id("subtitle_feed"), SubtitleHud::render);
		OverHeadSubtitles.init();
		ChatSubtitles.register();

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (client.player != null) {
				VoicechatSubtitlePlugin.setSelfId(client.player.getUuid());
			}
			SubtitleFeed.clear();
			SpeakerTracker.clear();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SubtitleFeed.clear();
			SpeakerTracker.clear();
		});

		SubtitleCommands.register();
		Keybinds.register();
		registerMenuProgress();

		VoiceSubtitles.LOGGER.info("Voice Subtitles ready");
	}

	/**
	 * Draws the download bar over menus.
	 *
	 * <p>The first download starts as soon as the client is up, which is at the
	 * title screen, where there is no HUD to draw on. Only when there is no world
	 * loaded: in game the HUD already draws it, and doing both would stack two
	 * translucent plates on the same pixels.
	 */
	private void registerMenuProgress() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
				ScreenEvents.afterRender(screen).register((rendered, context, mouseX, mouseY, delta) -> {
					if (client.world == null && DownloadState.isActive()) {
						ProgressOverlay.render(context, client.textRenderer,
								context.getScaledWindowWidth() / 2,
								context.getScaledWindowHeight() - 12);
					}
				}));
	}

	/**
	 * Speech recognition starts on the first client tick, not at mod init: the
	 * game options – and with them the language the model is picked from – do
	 * not exist yet while entrypoints are running.
	 */
	private boolean speechStarted;

	private void tick(MinecraftClient client) {
		if (!speechStarted && client.options != null) {
			speechStarted = true;
			SpeechRecognition.init();
		}

		SubtitleFeed.tick();
		DistanceFade.tick();
		LivePreview.tick(client);
		Keybinds.tick(client);
		if (client.world != null && client.world.getTime() % 100L == 0L) {
			SpeakerTracker.prune();
		}
	}
}
