package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.gui.SubtitleLogScreen;
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

	private SubtitleCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommandManager.literal("vsub")
						.then(ModelCommands.build())
						.then(ClientCommandManager.literal("log")
								.executes(ctx -> openLog()))
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
		source.sendFeedback(Text.translatable("commands.voice_subtitles.help.color").formatted(Formatting.GRAY));
		source.sendFeedback(Text.translatable("commands.voice_subtitles.help.model").formatted(Formatting.GRAY));
		source.sendFeedback(Text.translatable("commands.voice_subtitles.help.log").formatted(Formatting.GRAY));
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
