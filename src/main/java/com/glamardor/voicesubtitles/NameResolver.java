package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.UUID;

/**
 * Turns a speaker's UUID into the name to print in front of their speech.
 *
 * <p>On roleplay servers the name shown in the player list is the character
 * name ("Имя Фамилия", or "Незнакомец" for someone you have not met). That name
 * is the {@code displayName} the server sends in the player list packet, and the
 * client keeps it in {@link PlayerListEntry} – exactly what vanilla's
 * {@code PlayerListHud.getPlayerName} renders. We reproduce that lookup, with
 * the same fallbacks, so whatever the tab shows is what the subtitles show.
 */
public final class NameResolver {

	private NameResolver() {
	}

	/** @return the name to display for this speaker; never {@code null}. */
	public static Text nameOf(UUID id) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		PlayerListEntry entry = handler == null ? null : handler.getPlayerListEntry(id);

		if (VoiceSubtitlesConfig.get().nameSource == VoiceSubtitlesConfig.NameSource.USERNAME) {
			String username = usernameOf(entry, id);
			if (username != null) {
				return Text.literal(username);
			}
		}

		if (entry != null) {
			// The roleplay name, as the server put it in the player list.
			Text displayName = entry.getDisplayName();
			if (displayName != null) {
				return displayName;
			}
			// No display name: the server may still be decorating the plain name
			// through a scoreboard team prefix/suffix, which is what the tab shows.
			// (getScoreboardTeam reaches through the world, which is gone while
			// disconnecting – hence the guard.)
			Text plain = Text.literal(entry.getProfile().name());
			return client.world == null ? plain : Team.decorateName(entry.getScoreboardTeam(), plain);
		}

		// Not in the player list (vanished, or already gone): fall back to the
		// entity in the world, then to the raw UUID.
		String username = usernameOf(null, id);
		return Text.literal(username != null ? username : shortId(id));
	}

	/** @return the plain Mojang username, or {@code null} if it cannot be found. */
	public static String usernameOf(PlayerListEntry entry, UUID id) {
		if (entry != null) {
			return entry.getProfile().name();
		}
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		PlayerListEntry known = handler == null ? null : handler.getPlayerListEntry(id);
		if (known != null) {
			return known.getProfile().name();
		}
		if (client.world != null) {
			PlayerEntity player = client.world.getPlayerByUuid(id);
			if (player != null) {
				return player.getGameProfile().name();
			}
		}
		return null;
	}

	/** @return the player entity for this speaker, if they are loaded on the client. */
	public static PlayerEntity playerOf(UUID id) {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.world == null ? null : client.world.getPlayerByUuid(id);
	}

	/** @return the player list entry for this speaker, or {@code null}. */
	public static PlayerListEntry entryOf(UUID id) {
		ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
		return handler == null ? null : handler.getPlayerListEntry(id);
	}

	private static String shortId(UUID id) {
		String s = id.toString();
		return s.substring(0, Math.min(8, s.length()));
	}
}
