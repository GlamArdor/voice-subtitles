package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.translate.Translation;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns text chat into subtitles, for the modes that ask for it.
 *
 * <p>Only messages that can be attributed to a player become captions. Player
 * chat carries its author directly; on servers that print roleplay chat as
 * system messages the author is instead found through the {@code show_entity}
 * hover the server attaches to the name it prints – the same marker that makes
 * "who wrote this" answerable at all. A message nobody can be pinned to is
 * server chatter, not speech, and is left alone.
 */
public final class ChatSubtitles {

	/** Longest prefix we will look through for the "Name:" separator. */
	private static final int PREFIX_SEARCH_LIMIT = 64;

	/** Separators roleplay servers put between the speaker and what they said. */
	private static final char[] SEPARATORS = {':', '»', '›', '—'};

	/**
	 * Separators that only count directly after the speaker's name.
	 *
	 * <p>{@code >} closes the vanilla {@code <Name>} form and {@code ]} closes the
	 * bracketed one, but both turn up inside ordinary sentences often enough that
	 * cutting at the first one on the line would eat real words. After a name we
	 * know belongs to the author, they are unambiguous.
	 */
	private static final char[] NAME_SEPARATORS = {':', '»', '›', '—', '>', ']', '|'};

	/**
	 * How far into the line the author's name may sit and still count as a prefix.
	 *
	 * <p>Room for the rank or channel a server puts in front of it, and no more:
	 * a name found halfway through a sentence is being talked about, not talking.
	 */
	private static final int NAME_AT_FRONT = 12;

	private static final int MAX_LENGTH = 300;

	private ChatSubtitles() {
	}

	public static void register() {
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) -> {
			if (sender != null) {
				push(sender.getId(), message.getString());
			}
			echoTranslation(message, sender == null ? null : sender.getId());
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return;
			}
			UUID author = findAuthor(message);
			if (author != null) {
				push(author, message.getString());
			}
			echoTranslation(message, author);
		});
	}

	/**
	 * Writes the translation of a chat line into the chat, under the line it
	 * translates.
	 *
	 * <p>Because the feed cannot be relied on to show it. A caption is only made
	 * for a message that can be pinned to a player, and the roleplay commands –
	 * /me, /do, /try – come through as system messages that many servers send
	 * without any marker saying who wrote them. Their translation had nowhere to
	 * go. In the chat it always has somewhere to go, and it sits where anybody
	 * would look for it: on the next line.
	 */
	private static void echoTranslation(Text message, UUID author) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.enabled || !cfg.translateChat || !cfg.translationMode.translates()) {
			return;
		}
		String body = bodyOf(message, author);
		if (body.isBlank()) {
			return;
		}
		Translation.ofText(body, translation -> {
			if (translation.isBlank()) {
				return;
			}
			MinecraftClient client = MinecraftClient.getInstance();
			client.execute(() -> client.inGameHud.getChatHud().addMessage(
					Text.literal("↳ ").formatted(Formatting.DARK_GRAY)
							.append(Text.literal(translation).formatted(Formatting.GRAY))));
		});
	}

	/**
	 * The part of a chat line worth translating: the words, not the furniture.
	 *
	 * <p>A server prints a channel, a rank and a name in front of what somebody
	 * said, and on a Russian server all of that is Russian. Handing the whole
	 * line to a translator that decides what language it is in by counting
	 * letters means an English sentence with a Russian preamble counts as
	 * Russian and is left alone – which is exactly why the log had no English
	 * translations in it while the captions, which see the words on their own,
	 * had them all along.
	 */
	public static String bodyOf(Text message, UUID author) {
		String raw = message.getString().trim();
		if (raw.isEmpty()) {
			return "";
		}
		if (author != null) {
			return VoiceSubtitlesConfig.get().chatStripPrefix ? stripPrefix(raw, author).trim() : raw;
		}
		// Nobody to match a name against, so cut at the first separator instead.
		int limit = Math.min(raw.length(), PREFIX_SEARCH_LIMIT);
		for (int i = 0; i < limit; i++) {
			if (contains(SEPARATORS, raw.charAt(i))) {
				String rest = raw.substring(i + 1).trim();
				if (!rest.isEmpty()) {
					return rest;
				}
			}
		}
		return raw;
	}

	private static void push(UUID speaker, String text) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.enabled || !cfg.chatEnabled() || text == null || text.isBlank()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		boolean self = client.player != null && speaker.equals(client.player.getUuid());
		if (!cfg.showSelf && self) {
			return;
		}
		// A dice roll answering our own /try is not speech, and reading it back
		// as a caption helps nobody: it is already in the chat and in the log.
		if (self && cfg.hideCommandResults && LogEvents.answeringRoleplayCommand()) {
			return;
		}

		String body = cfg.chatStripPrefix ? stripPrefix(text, speaker) : text;
		body = body.trim();
		if (body.isEmpty()) {
			return;
		}
		if (body.length() > MAX_LENGTH) {
			body = body.substring(0, MAX_LENGTH) + "…";
		}
		SubtitleFeed.pushChat(speaker, body);
	}

	/**
	 * Drops the name the server already printed in front of the message, so the
	 * caption does not say it twice.
	 *
	 * <p>Two passes. First it looks for the author's own name near the start of
	 * the line and cuts just after whatever closes it – that catches the vanilla
	 * {@code <Name> words} as surely as {@code [Town] Name » words}, and knowing
	 * the name makes {@code >} safe to cut at, which it would not be on its own.
	 * Failing that it falls back to the first plain separator on the line.
	 *
	 * <p>Deliberately conservative throughout: it only cuts early in the line and
	 * only when something is left afterwards. A message that looks like neither
	 * form is passed through untouched, which is the right failure – a duplicated
	 * name reads worse than an odd caption, but a truncated sentence reads worst
	 * of all.
	 */
	static String stripPrefix(String text, UUID speaker) {
		for (String name : namesOf(speaker)) {
			int at = text.indexOf(name);
			if (at < 0 || at > PREFIX_SEARCH_LIMIT || name.isBlank()) {
				continue;
			}
			// Whatever closes the name comes right after it, give or take the odd
			// bracket or space the server padded it with.
			int from = at + name.length();
			int window = Math.min(text.length(), from + 4);
			for (int i = from; i < window; i++) {
				if (contains(NAME_SEPARATORS, text.charAt(i))) {
					String rest = text.substring(i + 1).trim();
					if (!rest.isEmpty()) {
						return rest;
					}
				}
			}
		}

		// No separator at all, which is how dice rolls and action results come
		// out: "[4] Name 1... [Fail]". If the name is right at the front, cutting
		// after it is still the right thing, since the caption prints it anyway.
		for (String name : namesOf(speaker)) {
			int at = text.indexOf(name);
			if (at < 0 || at > NAME_AT_FRONT || name.isBlank()) {
				continue;
			}
			String rest = text.substring(at + name.length()).trim();
			if (!rest.isEmpty()) {
				return rest;
			}
		}

		int limit = Math.min(text.length(), PREFIX_SEARCH_LIMIT);
		for (int i = 0; i < limit; i++) {
			if (contains(SEPARATORS, text.charAt(i))) {
				String rest = text.substring(i + 1).trim();
				return rest.isEmpty() ? text : rest;
			}
		}
		return text;
	}

	/** Every name this player could be printed under, longest first. */
	private static List<String> namesOf(UUID speaker) {
		List<String> names = new ArrayList<>(2);
		PlayerListEntry entry = NameResolver.entryOf(speaker);
		if (entry != null && entry.getProfile().getName() != null) {
			names.add(entry.getProfile().getName());
		}
		String shown = NameResolver.nameOf(speaker).getString().replaceAll("(?i)\\u00a7[0-9A-FK-ORX]", "").trim();
		if (!shown.isEmpty()) {
			names.add(shown);
		}
		// Longest first, so "Name the Elder" wins over the "Name" inside it.
		names.sort((a, b) -> b.length() - a.length());
		return names;
	}

	private static boolean contains(char[] haystack, char needle) {
		for (char c : haystack) {
			if (c == needle) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The first player named by a {@code show_entity} hover, depth first – the
	 * server tags the name it prints at the front of the line, so the author
	 * comes before anybody the message happens to mention.
	 */
	static UUID findAuthor(Text node) {
		if (node.getStyle().getHoverEvent() instanceof HoverEvent.ShowEntity show && isPlayer(show.entity().uuid)) {
			return show.entity().uuid;
		}
		for (Text child : node.getSiblings()) {
			UUID found = findAuthor(child);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static boolean isPlayer(UUID uuid) {
		ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
		return handler != null && handler.getPlayerListEntry(uuid) != null;
	}
}
