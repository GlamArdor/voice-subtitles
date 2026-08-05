package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.Locale;
import java.util.Set;

/**
 * The things that happen around a conversation, filed alongside it.
 *
 * <p>A log of nothing but speech is a wall of sentences with no idea where or
 * when any of it happened. Joining a server, leaving it, somebody dying, the
 * game closing: each of those is one line in the log, and between them every
 * caption acquires a place and a time.
 */
public final class LogEvents {

	/** Two deaths cannot happen this close together, so the second one is an echo. */
	private static final long DEATH_ECHO_MS = 3000L;

	/** Roleplay commands, whose answer is a dice roll rather than something said. */
	private static final Set<String> ROLEPLAY = Set.of("me", "try", "do", "todo", "ame", "ado");

	/**
	 * Commands never written down, whatever else is.
	 *
	 * <p>Login and registration carry a password in plain sight. A log that
	 * quietly kept those would be a worse thing to have than not having a log,
	 * and no amount of sealing the file would make it acceptable.
	 */
	private static final Set<String> SECRET = Set.of("login", "reg", "register");

	/**
	 * Commands that are noise rather than record.
	 *
	 * <p>{@code /talking} is sent by another mod every time a chat box opens and
	 * closes. Nobody typed it and nobody wants to read it back.
	 */
	private static final Set<String> NOISE = Set.of("talking");

	/** When a roleplay command was last sent, so its answer can be told apart. */
	private static volatile long lastRoleplayAt;

	private static long lastDeathAt;
	private static boolean sawDeathScreen;
	/** Remembered so leaving can name the place, by which time it is already gone. */
	private static String currentPlace = "";
	/** And whether that place was a world of our own rather than somebody's server. */
	private static boolean currentWorld;

	private LogEvents() {
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			currentPlace = placeOf(client);
			// A world and a server are not the same kind of place, and a log that
			// calls both "server" makes an evening at home look like an evening
			// out.
			currentWorld = client.isInSingleplayer();
			SubtitleLog.recordEvent(Text.translatable(
					currentWorld ? "voice_subtitles.event.join.world" : "voice_subtitles.event.join.server",
					currentPlace));
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SubtitleLog.recordEvent(Text.translatable(
					currentWorld ? "voice_subtitles.event.leave.world" : "voice_subtitles.event.leave.server",
					currentPlace.isEmpty() ? placeOf(client) : currentPlace));
			currentPlace = "";
			sawDeathScreen = false;
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			SubtitleLog.recordEvent(Text.translatable("voice_subtitles.event.quit"));
			// Flushed here as well as by the client: these handlers run in the
			// order they were registered, and the client's flush has already
			// been and gone by the time this line is written.
			SubtitleLog.flush();
		});

		// Deaths and kills arrive as ordinary chat: vanilla builds them from
		// translation keys beginning with "death.", which holds whatever language
		// the game is in. A server writing its own death messages will not match,
		// and then only the player's own death is noticed, below.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return;
			}
			if (isDeathMessage(message)) {
				lastDeathAt = System.currentTimeMillis();
			}
			// Everything the chat shows goes in as it appeared, deaths included.
			// No guessing which line answered which command: whatever the server
			// printed is in the log, in the order it arrived.
			SubtitleLog.recordChat(message);
		});

		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) ->
				SubtitleLog.recordChat(message));

		// Commands are not taken from a Fabric event but from the method they all
		// go through; see ChatCommandMixin and onCommandSent below.
		ClientTickEvents.END_CLIENT_TICK.register(LogEvents::tick);
	}

	/**
	 * Files a command the player sent.
	 *
	 * <p>Called from the one method every chat command goes through, rather than
	 * from Fabric's send event, which was quietly letting most of them past.
	 *
	 * @param command as typed, without its leading slash
	 */
	public static void onCommandSent(String command) {
		if (command == null || command.isBlank()) {
			return;
		}
		String name = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		if (SECRET.contains(name) || NOISE.contains(name)) {
			return;
		}
		if (ROLEPLAY.contains(name)) {
			lastRoleplayAt = System.currentTimeMillis();
		}
		SubtitleLog.recordEvent(Text.translatable("voice_subtitles.event.command", "/" + command));
	}

	/**
	 * Catches the player's own death from the screen rather than from a message,
	 * so it is noticed even where the server words it its own way. Skipped when a
	 * death message just went past, which would be the same death twice.
	 */
	private static void tick(MinecraftClient client) {
		boolean dead = client.currentScreen instanceof DeathScreen;
		if (dead && !sawDeathScreen) {
			if (System.currentTimeMillis() - lastDeathAt > DEATH_ECHO_MS) {
				SubtitleLog.recordEvent(Text.translatable("voice_subtitles.event.death"));
			}
		}
		sawDeathScreen = dead;
	}

	private static boolean isDeathMessage(Text message) {
		return message.getContent() instanceof TranslatableTextContent translatable
				&& translatable.getKey().startsWith("death.");
	}

	/** Where we are: the server's address, or the name of the world in single player. */
	private static String placeOf(MinecraftClient client) {
		ServerInfo server = client.getCurrentServerEntry();
		if (server != null && server.address != null && !server.address.isBlank()) {
			return server.address;
		}
		if (client.isInSingleplayer() && client.getServer() != null) {
			return client.getServer().getSaveProperties().getLevelName();
		}
		return "?";
	}

	/**
	 * @return true if a roleplay command of ours was sent a moment ago, and this
	 *         message is therefore likely to be the server answering it
	 */
	static boolean answeringRoleplayCommand() {
		return System.currentTimeMillis() - lastRoleplayAt < 3000L;
	}

	/** @return true when events are wanted at all. */
	static boolean wanted() {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		return cfg.keepLog && cfg.logEvents;
	}
}
