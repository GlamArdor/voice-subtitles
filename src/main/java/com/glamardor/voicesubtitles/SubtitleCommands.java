package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.gui.SubtitleLogScreen;
import com.glamardor.voicesubtitles.speech.SpeechModels;
import com.glamardor.voicesubtitles.speech.SpeechRecognition;
import com.glamardor.voicesubtitles.translate.Languages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The two things the settings screen cannot express, both under {@code /vsub}:
 * assigning a colour to a particular player, and managing the recognition
 * models.
 */
public final class SubtitleCommands {

	/** Everyone in the tab list, so a name never has to be typed out. */
	private static final SuggestionProvider<FabricClientCommandSource> PLAYER_SUGGESTIONS = (ctx, builder) -> {
		ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
		if (handler != null) {
			for (PlayerListEntry entry : handler.getPlayerList()) {
				String name = entry.getProfile().getName();
				if (name != null && name.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
					// The roleplay name as the tooltip, since that is what the
					// subtitle will actually show for this player.
					builder.suggest(name, NameResolver.nameOf(entry.getProfile().getId()));
				}
			}
		}
		return builder.buildFuture();
	};

	/**
	 * A handful of colours plus "reset" – mostly so the reset is discoverable at
	 * all, since nothing else in the command hints that it exists.
	 */
	private static final SuggestionProvider<FabricClientCommandSource> COLOR_SUGGESTIONS = (ctx, builder) -> {
		String remaining = builder.getRemainingLowerCase();
		if ("reset".startsWith(remaining)) {
			builder.suggest("reset", Text.translatable("commands.voice_subtitles.color.reset"));
		}
		for (String hex : new String[]{"#FFFFFF", "#FF8844", "#88CCFF", "#AAFF88", "#FFDD66", "#FF99CC"}) {
			if (hex.toLowerCase().startsWith(remaining)) {
				builder.suggest(hex);
			}
		}
		return builder.buildFuture();
	};

	/**
	 * The languages there is a translation for, plus "reset".
	 *
	 * <p>Two of them, and each offered once. It used to be the whole model
	 * catalogue, which meant a list reading "en, en, en, en, ru, ru, ru…" – one
	 * line per model rather than per language, with the four English models and the
	 * nine Russian ones all shortening to the same two words. What a language is
	 * called goes in the tooltip beside it.
	 *
	 * <p>A language that is only in the recognition catalogue is still accepted if
	 * it is typed out: marking a German speaker gets their words written in German
	 * letters, which is worth having even where nothing can translate them. It is
	 * simply not suggested, because for nearly everybody it is the wrong answer.
	 */
	private static final SuggestionProvider<FabricClientCommandSource> LANGUAGE_SUGGESTIONS = (ctx, builder) -> {
		String remaining = builder.getRemainingLowerCase();
		if ("reset".startsWith(remaining)) {
			builder.suggest("reset", Text.translatable("commands.voice_subtitles.lang.reset"));
		}
		for (String code : Languages.translatable()) {
			if (code.startsWith(remaining)) {
				builder.suggest(code, Languages.nameOf(code));
			}
		}
		return builder.buildFuture();
	};

	private SubtitleCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommandManager.literal("vsub")
						.then(ModelCommands.build())
						.then(ClientCommandManager.literal("log")
								.executes(ctx -> openLog()))
						.then(ClientCommandManager.literal("preview")
								.executes(ctx -> preview(6))
								.then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(1, 60))
										.executes(ctx -> preview(IntegerArgumentType.getInteger(ctx, "seconds")))))
						.then(ClientCommandManager.literal("lang")
								.executes(ctx -> listLanguages(ctx.getSource()))
								.then(ClientCommandManager.argument("player", StringArgumentType.word())
										.suggests(PLAYER_SUGGESTIONS)
										.then(ClientCommandManager.argument("language", StringArgumentType.word())
												.suggests(LANGUAGE_SUGGESTIONS)
												.executes(ctx -> setLanguage(ctx.getSource(),
														StringArgumentType.getString(ctx, "player"),
														StringArgumentType.getString(ctx, "language"))))))
						.then(ClientCommandManager.literal("color")
								.then(ClientCommandManager.argument("player", StringArgumentType.word())
										.suggests(PLAYER_SUGGESTIONS)
										.then(ClientCommandManager.argument("hex", StringArgumentType.word())
												.suggests(COLOR_SUGGESTIONS)
												.executes(ctx -> setColor(ctx.getSource(),
														StringArgumentType.getString(ctx, "player"),
														StringArgumentType.getString(ctx, "hex"))))))
						.executes(ctx -> help(ctx.getSource()))));
	}

	private static int help(FabricClientCommandSource source) {
		source.sendFeedback(Text.translatable("commands.voice_subtitles.help.preview").formatted(Formatting.GRAY));
		source.sendFeedback(Text.translatable("commands.voice_subtitles.help.lang").formatted(Formatting.GRAY));
		source.sendFeedback(Text.translatable("commands.voice_subtitles.help.color").formatted(Formatting.GRAY));
		source.sendFeedback(Text.translatable("commands.voice_subtitles.help.model").formatted(Formatting.GRAY));
		source.sendFeedback(Text.translatable("commands.voice_subtitles.help.repair").formatted(Formatting.GRAY));
		source.sendFeedback(Text.translatable("commands.voice_subtitles.help.log").formatted(Formatting.GRAY));
		return 1;
	}

	/** Puts a sample caption on screen, so the look of them can be adjusted. */
	private static int preview(int seconds) {
		SubtitlePreview.start(seconds);
		return 1;
	}

	/**
	 * Opens the conversation log – on the next tick, since the chat screen the
	 * command was typed into closes itself right after this runs and would take
	 * the log screen with it.
	 */
	private static int openLog() {
		MinecraftClient client = MinecraftClient.getInstance();
		client.send(() -> client.setScreen(new SubtitleLogScreen(null)));
		return 1;
	}

	/**
	 * Marks a player as speaking a particular language.
	 *
	 * <p>Remembered by UUID rather than by name: on a roleplay server a name is a
	 * costume, and the person underneath goes on speaking the same language.
	 */
	private static int setLanguage(FabricClientCommandSource source, String playerName, String language) {
		ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
		PlayerListEntry entry = handler == null ? null : handler.getPlayerListEntry(playerName);
		if (entry == null) {
			source.sendFeedback(Text.translatable("commands.voice_subtitles.color.no_player", playerName)
					.formatted(Formatting.RED));
			return 0;
		}
		UUID id = entry.getProfile().getId();

		if ("reset".equalsIgnoreCase(language)) {
			Languages.set(id, null);
			SpeechRecognition.reload();
			source.sendFeedback(Text.translatable("commands.voice_subtitles.lang.cleared", playerName)
					.formatted(Formatting.GRAY));
			return 1;
		}
		String code = language.toLowerCase(Locale.ROOT).split("[-_]")[0];
		if (SpeechModels.byId(SpeechModels.defaultFor(code)) == null) {
			source.sendFeedback(Text.translatable("commands.voice_subtitles.lang.unknown", code)
					.formatted(Formatting.RED));
			return 0;
		}
		Languages.set(id, code);
		SpeechRecognition.reload();

		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		// The name rather than the code: "speaks English" is an answer, "speaks en"
		// is a receipt for what was typed.
		source.sendFeedback(Text.translatable("commands.voice_subtitles.lang.set", playerName, Languages.nameOf(code)));
		if (!cfg.translationMode.routes()) {
			// Marking somebody without turning routing on does nothing at all,
			// and saying so beats leaving them to wonder why.
			source.sendFeedback(Text.translatable("commands.voice_subtitles.lang.mode_off")
					.formatted(Formatting.YELLOW));
		}
		return 1;
	}

	/** Everybody who has been marked, and what the rest are taken to speak. */
	private static int listLanguages(FabricClientCommandSource source) {
		source.sendFeedback(Text.translatable("commands.voice_subtitles.lang.default",
				Languages.nameOf(Languages.defaultSpoken()), Languages.nameOf(Languages.mine()))
				.formatted(Formatting.GRAY));

		Map<String, String> named = Languages.named();
		if (named.isEmpty()) {
			source.sendFeedback(Text.translatable("commands.voice_subtitles.lang.none").formatted(Formatting.GRAY));
			return 1;
		}
		for (Map.Entry<String, String> marked : named.entrySet()) {
			try {
				UUID id = UUID.fromString(marked.getKey());
				source.sendFeedback(Text.literal("  ")
						.append(NameResolver.nameOf(id))
						.append(Text.literal(" – ").formatted(Formatting.GRAY))
						.append(Languages.nameOf(marked.getValue()).copy().formatted(Formatting.GRAY)));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return 1;
	}

	private static int setColor(FabricClientCommandSource source, String playerName, String hex) {
		ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
		PlayerListEntry entry = handler == null ? null : handler.getPlayerListEntry(playerName);
		if (entry == null) {
			source.sendFeedback(Text.translatable("commands.voice_subtitles.color.no_player", playerName)
					.formatted(Formatting.RED));
			return 0;
		}
		UUID id = entry.getProfile().getId();
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();

		if ("reset".equalsIgnoreCase(hex)) {
			cfg.setManualColor(id, null);
			source.sendFeedback(Text.translatable("commands.voice_subtitles.color.cleared", playerName)
					.formatted(Formatting.GRAY));
			return 1;
		}
		try {
			int rgb = Integer.parseInt(hex.replace("#", ""), 16) & 0xFFFFFF;
			cfg.setManualColor(id, rgb);
			source.sendFeedback(Text.translatable("commands.voice_subtitles.color.set", playerName)
					.styled(style -> style.withColor(rgb)));
			return 1;
		} catch (NumberFormatException e) {
			source.sendFeedback(Text.translatable("commands.voice_subtitles.color.bad_hex", hex)
					.formatted(Formatting.RED));
			return 0;
		}
	}
}
