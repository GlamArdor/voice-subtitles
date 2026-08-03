package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.speech.ModelDownload;
import com.glamardor.voicesubtitles.speech.SpeechRecognition;
import com.glamardor.voicesubtitles.speech.SpeechModels;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * {@code /vsub model …} – the recognition models: what exists, what is
 * installed, and which one is in use.
 */
public final class ModelCommands {

	private static final SuggestionProvider<FabricClientCommandSource> MODEL_SUGGESTIONS = (ctx, builder) -> {
		String remaining = builder.getRemainingLowerCase();
		for (SpeechModels.Entry entry : SpeechModels.catalogue().values()) {
			if (entry.id().startsWith(remaining)) {
				builder.suggest(entry.id(), Text.literal(entry.language()
						+ (SpeechModels.isInstalled(entry) ? " – installed" : " – " + entry.megabytes() + " MB")));
			}
		}
		return builder.buildFuture();
	};

	private ModelCommands() {
	}

	public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
		return ClientCommandManager.literal("model")
				.executes(ctx -> status(ctx.getSource()))
				.then(ClientCommandManager.literal("list")
						.executes(ctx -> list(ctx.getSource())))
				.then(ClientCommandManager.literal("status")
						.executes(ctx -> status(ctx.getSource())))
				.then(ClientCommandManager.literal("download")
						.then(ClientCommandManager.argument("id", StringArgumentType.word())
								.suggests(MODEL_SUGGESTIONS)
								.executes(ctx -> download(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
				.then(ClientCommandManager.literal("use")
						.then(ClientCommandManager.argument("id", StringArgumentType.word())
								.suggests(MODEL_SUGGESTIONS)
								.executes(ctx -> use(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
	}

	private static int list(FabricClientCommandSource source) {
		String current = VoiceSubtitlesConfig.get().modelId;
		source.sendFeedback(Text.translatable("commands.voice_subtitles.model.list").formatted(Formatting.GOLD));
		for (SpeechModels.Entry entry : SpeechModels.catalogue().values()) {
			boolean installed = SpeechModels.isInstalled(entry);
			Text mark = Text.literal(entry.id().equals(current) ? " ◄" : "").formatted(Formatting.GREEN);
			source.sendFeedback(Text.literal("  " + entry.id() + " – " + entry.language() + " ")
					.formatted(Formatting.WHITE)
					.append(installed
							? Text.translatable("commands.voice_subtitles.model.installed").formatted(Formatting.GREEN)
							: Text.literal(entry.megabytes() + " MB").formatted(Formatting.GRAY))
					.append(mark));
		}
		source.sendFeedback(Text.translatable("commands.voice_subtitles.model.hint").formatted(Formatting.GRAY));
		return 1;
	}

	private static int status(FabricClientCommandSource source) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		SpeechRecognition.State state = SpeechRecognition.state();

		if (ModelDownload.isRunning()) {
			source.sendFeedback(Text.translatable("commands.voice_subtitles.model.busy").formatted(Formatting.YELLOW));
		}

		Formatting colour = switch (state) {
			case READY -> Formatting.GREEN;
			case LOADING -> Formatting.YELLOW;
			case FAILED -> Formatting.RED;
			case NO_MODEL, DISABLED -> Formatting.GRAY;
		};
		source.sendFeedback(Text.literal("Speech recognition: ").formatted(Formatting.GRAY)
				.append(Text.literal(state.name()).formatted(colour)));
		source.sendFeedback(Text.literal("  model=" + cfg.modelId
				+ ", partial=" + cfg.showPartial
				+ ", limit=" + cfg.maxRecognizers
				+ ", active=" + SpeechRecognition.activeRecognizers()).formatted(Formatting.GRAY));

		if (state == SpeechRecognition.State.FAILED && !SpeechRecognition.failure().isEmpty()) {
			source.sendFeedback(Text.literal("  " + SpeechRecognition.failure()).formatted(Formatting.RED));
		}
		if (state == SpeechRecognition.State.NO_MODEL) {
			source.sendFeedback(Text.translatable("commands.voice_subtitles.model.missing", cfg.modelId)
					.formatted(Formatting.YELLOW));
		}
		return 1;
	}

	private static int download(FabricClientCommandSource source, String id) {
		SpeechModels.Entry entry = SpeechModels.byId(id);
		if (entry == null) {
			source.sendFeedback(Text.translatable("commands.voice_subtitles.model.unknown", id).formatted(Formatting.RED));
			return 0;
		}
		if (SpeechModels.isInstalled(entry)) {
			source.sendFeedback(Text.translatable("commands.voice_subtitles.model.already", entry.id())
					.formatted(Formatting.GRAY));
			return 1;
		}
		boolean started = ModelDownload.startOnce(entry, () -> {
			VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
			cfg.modelId = entry.id();
			VoiceSubtitlesConfig.save();
			SpeechRecognition.reload();
			tell(Text.translatable("commands.voice_subtitles.model.done", entry.id()).formatted(Formatting.GREEN));
		});

		if (!started) {
			source.sendFeedback(Text.translatable("commands.voice_subtitles.model.busy").formatted(Formatting.RED));
			return 0;
		}
		source.sendFeedback(Text.translatable("commands.voice_subtitles.model.downloading",
				entry.id(), entry.megabytes()).formatted(Formatting.YELLOW));
		return 1;
	}

	private static int use(FabricClientCommandSource source, String id) {
		SpeechModels.Entry entry = SpeechModels.byId(id);
		if (entry == null) {
			source.sendFeedback(Text.translatable("commands.voice_subtitles.model.unknown", id).formatted(Formatting.RED));
			return 0;
		}
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		cfg.modelId = entry.id();
		VoiceSubtitlesConfig.save();
		SpeechRecognition.reload();

		source.sendFeedback(SpeechModels.isInstalled(entry)
				? Text.translatable("commands.voice_subtitles.model.using", entry.language()).formatted(Formatting.GREEN)
				: Text.translatable("commands.voice_subtitles.model.missing", entry.id()).formatted(Formatting.YELLOW));
		return 1;
	}

	/** Progress lands in chat from a background thread, so hop back to the client one. */
	private static void tell(Text message) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> {
			if (client.player != null) {
				client.player.sendMessage(message, false);
			}
		});
	}
}
