package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * What has already been said, kept so it can be read back after it has scrolled
 * off the feed.
 *
 * <p>Worth being honest about what this is: the spoken half of it comes out of
 * an offline recogniser that mishears things, so the log is a reminder of a
 * conversation rather than a record of it. Text chat lines are exact – they
 * arrived as text – and are marked as such on export.
 */
public final class SubtitleLog {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private static final List<Entry> ENTRIES = Collections.synchronizedList(new ArrayList<>());

	private SubtitleLog() {
	}

	/**
	 * Brings back what was said before the game was last closed, and drops day
	 * files older than the player asked to keep.
	 */
	public static void init() {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		LogStore.prune(cfg.logKeepDays);
		LogStore.resumeChain();
		if (!cfg.keepLog) {
			return;
		}
		List<Entry> previous = LogStore.load(Math.max(cfg.logCapacity, 1));
		if (previous.isEmpty()) {
			return;
		}
		synchronized (ENTRIES) {
			ENTRIES.addAll(0, previous);
		}
		VoiceSubtitles.LOGGER.info("Conversation log: {} lines carried over", previous.size());
	}

	/** Events belong to nobody, so they are filed under this. */
	public static final UUID NOBODY = new UUID(0L, 0L);

	/**
	 * One finished line. The name is captured as it was shown at the time.
	 *
	 * @param translation what it says in the language you read, or empty. Kept
	 *                    beside the original rather than instead of it: on a
	 *                    roleplay server the wording is the evidence, and a
	 *                    translation is a paraphrase of a transcript.
	 */
	public record Entry(long time, UUID speaker, String name, SubtitleFeed.Source source, String text,
	                    String translation) {

		public Entry(long time, UUID speaker, String name, SubtitleFeed.Source source, String text) {
			this(time, speaker, name, source, text, "");
		}

		/** True for a happening rather than something somebody said. */
		public boolean isEvent() {
			return source == SubtitleFeed.Source.EVENT;
		}

		public boolean translated() {
			return translation != null && !translation.isEmpty();
		}
	}

	/**
	 * Files something that happened rather than something that was said.
	 *
	 * <p>Kept out of the feed on purpose: these belong in the record, not on
	 * screen, where the game already announces them in its own way.
	 */
	public static void recordEvent(Text what) {
		if (!LogEvents.wanted()) {
			return;
		}
		Entry entry = new Entry(System.currentTimeMillis(), NOBODY, "",
				SubtitleFeed.Source.EVENT, what.getString());
		synchronized (ENTRIES) {
			ENTRIES.add(entry);
		}
		LogStore.append(entry);
	}

	/**
	 * Takes a line that is about to leave the feed. Lines nobody ever put words
	 * to – somebody talked, the recogniser had nothing – are dropped: an entry
	 * saying only that a voice happened is not worth reading back.
	 */
	public static void record(SubtitleFeed.Line line) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		// Chat lines are filed straight from the chat, whole and unattributed;
		// filing them here as well would put every message in twice.
		if (!cfg.keepLog || line.placeholder() || line.logged
				|| line.source == SubtitleFeed.Source.CHAT) {
			return;
		}
		line.logged = true;
		Entry entry = new Entry(line.startedAt(), line.speaker,
				NameResolver.nameOf(line.speaker).getString(), line.source, line.text(), line.translation());

		synchronized (ENTRIES) {
			ENTRIES.add(entry);
			int over = ENTRIES.size() - Math.max(cfg.logCapacity, 1);
			for (int i = 0; i < over; i++) {
				ENTRIES.remove(0);
			}
		}
		// Written straight away rather than at shutdown: a game that crashes
		// still ought to leave the conversation behind.
		LogStore.append(entry);
	}

	/**
	 * Files a line of chat exactly as it appeared.
	 *
	 * <p>Everything the chat window showed, not only what could be pinned to a
	 * player: other people's commands, server announcements, the results of
	 * roleplay actions. Kept whole, with whatever prefix the server put on it,
	 * because in a record the wording is the evidence.
	 */
	public static void recordChat(Text message) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.keepLog || !cfg.logChat || message == null) {
			return;
		}
		String text = message.getString().trim();
		if (text.isEmpty()) {
			return;
		}
		// Stamped now, filed when the translation is in. A chat line goes into the
		// log whole, so it never becomes one of the captions the translator writes
		// into, and putting it in the record untranslated while the caption beside
		// it carries a translation is exactly the gap this closes. The wait is a
		// fraction of a second, and the entry keeps the time it arrived.
		//
		// Only the words are translated, not the channel and rank the server
		// printed in front of them: on a Russian server those are Russian, and a
		// translator that decides the language by counting letters called an
		// English sentence Russian on the strength of its preamble.
		long time = System.currentTimeMillis();
		String body = ChatSubtitles.bodyOf(message, ChatSubtitles.findAuthor(message));
		com.glamardor.voicesubtitles.translate.Translation.ofText(body, translation ->
				file(new Entry(time, NOBODY, "", SubtitleFeed.Source.CHAT, text, translation)));
	}

	/** Adds an entry to the log in memory and on disk. Safe from any thread. */
	private static void file(Entry entry) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		synchronized (ENTRIES) {
			ENTRIES.add(entry);
			int over = ENTRIES.size() - Math.max(cfg.logCapacity, 1);
			for (int i = 0; i < over; i++) {
				ENTRIES.remove(0);
			}
		}
		LogStore.append(entry);
	}

	/**
	 * @return every entry, oldest first
	 *
	 * <p>Sorted by when the line was spoken rather than by when it happened to
	 * be filed. A long utterance is filed only once it has finished, by which
	 * time shorter ones that started after it have come and gone.
	 */
	public static List<Entry> entries() {
		synchronized (ENTRIES) {
			List<Entry> copy = new ArrayList<>(ENTRIES);
			copy.sort(java.util.Comparator.comparingLong(Entry::time));
			return copy;
		}
	}

	public static int size() {
		return ENTRIES.size();
	}

	/** Empties the log, on screen and on disk. */
	public static void clear() {
		ENTRIES.clear();
		LogStore.deleteAll();
	}

	/** Gives the writer a moment to catch up, for use when the game is closing. */
	public static void flush() {
		LogStore.flush();
	}

	/** True when a day file was found altered since it was written. */
	public static boolean wasTampered() {
		return LogStore.wasTampered();
	}

	/** @return the time of day an entry was spoken, {@code HH:mm:ss}. */
	public static String timeOf(Entry entry) {
		return STAMP.format(LocalDateTime.ofInstant(
				java.time.Instant.ofEpochMilli(entry.time()), java.time.ZoneId.systemDefault()));
	}

	/**
	 * Writes the whole log out as plain text.
	 *
	 * @return the file written
	 */
	public static Path export() throws IOException {
		return export(entries());
	}

	/**
	 * Writes these entries out as plain text.
	 *
	 * <p>Takes a list rather than always writing everything, so the log screen
	 * can save exactly what its filters are showing.
	 *
	 * @return the file written
	 */
	public static Path export(List<Entry> entries) throws IOException {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("voice_subtitles").resolve("logs");
		Files.createDirectories(dir);
		Path file = dir.resolve("conversation-" + FILE_STAMP.format(LocalDateTime.now()) + ".txt");

		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			writer.write("# Voice Subtitles – conversation log\n");
			writer.write("# Spoken lines come from offline speech recognition and can be wrong.\n");
			writer.write("# Lines marked [chat] arrived as text and are exact.\n");
			writer.write("# A line beginning with -> is a translation, not something anybody said.\n\n");
			for (Entry entry : entries) {
				writer.write("[" + timeOf(entry) + "] "
						+ (entry.source() == SubtitleFeed.Source.CHAT ? "[chat] " : "")
						+ entry.name() + ": " + entry.text() + "\n");
				// Under the line rather than beside it, and marked as what it is:
				// a saved log is read by people who were not there, and a
				// paraphrase passed off as a quotation is worse than no log.
				if (entry.translated()) {
					writer.write("           -> " + entry.translation() + "\n");
				}
			}
		}
		return file;
	}
}
