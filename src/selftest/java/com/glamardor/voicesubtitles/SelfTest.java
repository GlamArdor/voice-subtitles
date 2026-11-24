package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.gui.ConfigScreenFactory;
import com.glamardor.voicesubtitles.gui.FallbackConfigScreen;
import com.glamardor.voicesubtitles.gui.SubtitleLogScreen;
import com.glamardor.voicesubtitles.speech.SpeechRecognition;
import com.glamardor.voicesubtitles.translate.Translation;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dev-only scripted run through the mod, never part of the released jar.
 *
 * <p>Waits for a world, then feeds recorded speech through the same entry point
 * Simple Voice Chat uses, drives the commands and screens, takes screenshots and
 * writes what it saw to selftest-report.txt before closing the game.
 */
public class SelfTest implements ClientModInitializer {

	private static final UUID STRANGER = UUID.fromString("5b0a1f6e-3f6c-4b7e-9a51-0d2c6f1e7a11");

	private final List<String> report = new ArrayList<>();
	private int tick;
	private int step;
	private int wait;
	private int inWorld;

	@Override
	public void onInitializeClient() {
		if (!Boolean.getBoolean("voice_subtitles.selftest")) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void note(String line) {
		String stamped = LocalTime.now().withNano(0) + " " + line;
		report.add(stamped);
		VoiceSubtitles.LOGGER.info("[selftest] {}", line);
		try {
			Files.write(Path.of("selftest-report.txt"), report);
		} catch (IOException ignored) {
		}
	}

	private void shot(MinecraftClient client, String what) {
		ScreenshotRecorder.saveScreenshot(client, false);
		note("screenshot: " + what);
	}

	private static short[] pcm(String property) throws IOException {
		byte[] bytes = Files.readAllBytes(Path.of(System.getProperty(property)));
		short[] out = new short[bytes.length / 2];
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
		return out;
	}

	/** Plays the clip into the mod at real time, 20 ms frames, like Simple Voice Chat does. */
	private void speak(UUID speaker, String property, float distance) {
		Thread thread = new Thread(() -> {
			try {
				short[] all = pcm(property);
				for (int i = 0; i + 960 <= all.length; i += 960) {
					short[] frame = new short[960];
					System.arraycopy(all, i, frame, 0, 960);
					VoicechatSubtitlePlugin.accept(speaker, frame, false, distance);
					Thread.sleep(20);
				}
				// A moment of silence, then the end of the transmission.
				for (int i = 0; i < 25; i++) {
					VoicechatSubtitlePlugin.accept(speaker, new short[960], false, distance);
					Thread.sleep(20);
				}
				VoicechatSubtitlePlugin.accept(speaker, new short[0], false, distance);
			} catch (Exception e) {
				note("speak failed: " + e);
			}
		}, "vsub-selftest-speaker");
		thread.setDaemon(true);
		thread.start();
	}

	private void dumpFeed(String label) {
		for (SubtitleFeed.Line line : SubtitleFeed.all()) {
			note(label + " line speaker=" + line.speaker + " source=" + line.source
					+ " text=\"" + line.text + "\" translation=\"" + line.translation + "\"");
		}
	}

	private void tick(MinecraftClient client) {
		tick++;
		if (wait > 0) {
			wait--;
			return;
		}
		if (client.player == null || client.world == null) {
			if (tick % 200 == 0) {
				note("waiting for world, screen=" + client.guiManager.getCurrentScreen());
			}
			return;
		}
		inWorld++;
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		try {
			switch (step++) {
				case 0 -> {
					note("in world; speech state=" + SpeechRecognition.state()
							+ " model=" + SpeechRecognition.currentModelId());
					cfg.voicePlacement = VoiceSubtitlesConfig.Placement.FEED;
					cfg.chatPlacement = VoiceSubtitlesConfig.Placement.FEED;
					cfg.translationMode = VoiceSubtitlesConfig.TranslationMode.ALWAYS;
					cfg.myLanguage = "ru";
					cfg.speakerLanguages.put(STRANGER.toString(), "en");
					VoiceSubtitlesConfig.save();
					wait = 40;
				}
				case 1 -> {
					if (SpeechRecognition.state() != SpeechRecognition.State.READY && inWorld < 2400) {
						step--;
						wait = 20;
						return;
					}
					note("speech state=" + SpeechRecognition.state() + " model=" + SpeechRecognition.currentModelId()
							+ " failure=" + SpeechRecognition.failure());
					speak(client.player.getUuid(), "voice_subtitles.selftest.ru", -1F);
					wait = 90;
				}
				case 2 -> {
					shot(client, "feed while Russian speech is still coming in");
					wait = 120;
				}
				case 3 -> {
					dumpFeed("after ru");
					shot(client, "feed after Russian speech");
					speak(STRANGER, "voice_subtitles.selftest.en", 6F);
					wait = 170;
				}
				case 4 -> {
					dumpFeed("after en");
					shot(client, "feed with English speaker routed to en model + translation");
					wait = 130;
				}
				case 5 -> {
					note("translation ready=" + Translation.isReady() + " failure=" + Translation.failure());
					Translation.ofText("Good evening, my friend. Where is the market?",
							result -> note("Translation.ofText -> \"" + result + "\""));
					client.player.networkHandler.sendChatMessage("Проверка чата: субтитры работают");
					wait = 60;
				}
				case 6 -> {
					dumpFeed("after chat");
					shot(client, "chat line in feed");
					cfg.voicePlacement = VoiceSubtitlesConfig.Placement.OVER_HEAD;
					cfg.chatPlacement = VoiceSubtitlesConfig.Placement.OVER_HEAD;
					VoiceSubtitlesConfig.save();
					client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
					speak(client.player.getUuid(), "voice_subtitles.selftest.ru", -1F);
					wait = 120;
				}
				case 7 -> {
					shot(client, "over-head caption, third person front");
					wait = 160;
				}
				case 8 -> {
					client.options.setPerspective(Perspective.FIRST_PERSON);
					cfg.voicePlacement = VoiceSubtitlesConfig.Placement.FEED;
					cfg.chatPlacement = VoiceSubtitlesConfig.Placement.FEED;
					cfg.feedAnchor = VoiceSubtitlesConfig.Anchor.TOP;
					cfg.showDirection = true;
					VoiceSubtitlesConfig.save();
					speak(STRANGER, "voice_subtitles.selftest.en", 6F);
					wait = 100;
				}
				case 9 -> {
					shot(client, "feed anchored to the top, direction arrow on");
					client.guiManager.inGameHud.toggleGui();
					wait = 10;
				}
				case 10 -> {
					note("hud hidden=" + client.guiManager.inGameHud.isGuiHidden());
					shot(client, "F1: HUD hidden, feed should stay");
					client.guiManager.inGameHud.toggleGui();
					cfg.feedAnchor = VoiceSubtitlesConfig.Anchor.BOTTOM;
					cfg.showDirection = false;
					VoiceSubtitlesConfig.save();
					wait = 200;
				}
				case 11 -> {
					client.player.networkHandler.sendChatCommand("vsub preview 5");
					wait = 30;
				}
				case 12 -> {
					shot(client, "/vsub preview");
					client.player.networkHandler.sendChatCommand("vsub model list");
					client.player.networkHandler.sendChatCommand("vsub model status");
					client.guiManager.setScreen(new ChatScreen("", false));
					wait = 20;
				}
				case 13 -> {
					shot(client, "chat open: /vsub model list + status output, feed above chat");
					client.guiManager.setScreen(null);
					client.player.networkHandler.sendChatCommand("vsub log");
					wait = 30;
				}
				case 14 -> {
					note("screen after /vsub log = " + client.guiManager.getCurrentScreen());
					note("log entries=" + SubtitleLog.size());
					shot(client, "conversation log screen");
					client.guiManager.setScreen(ConfigScreenFactory.create(null));
					wait = 30;
				}
				case 15 -> {
					note("config screen = " + client.guiManager.getCurrentScreen());
					shot(client, "settings screen (Cloth)");
					client.guiManager.setScreen(new FallbackConfigScreen(null));
					wait = 30;
				}
				case 16 -> {
					shot(client, "built-in settings screen");
					client.guiManager.setScreen(new SubtitleLogScreen(null));
					wait = 10;
				}
				case 17 -> {
					client.guiManager.setScreen(null);
					try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of("selftest-log-entries.txt")))) {
						for (SubtitleLog.Entry entry : SubtitleLog.entries()) {
							out.println(entry);
						}
					}
					cfg.translationMode = VoiceSubtitlesConfig.TranslationMode.OFF;
					cfg.speakerLanguages.remove(STRANGER.toString());
					VoiceSubtitlesConfig.save();
					note("done");
					wait = 40;
				}
				default -> client.scheduleStop();
			}
		} catch (Throwable t) {
			note("step " + (step - 1) + " threw " + t);
			VoiceSubtitles.LOGGER.error("[selftest]", t);
		}
	}
}
