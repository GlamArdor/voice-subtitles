package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.fabricmc.loader.api.FabricLoader;

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

	/** One finished line. The name is captured as it was shown at the time. */
	public record Entry(long time, UUID speaker, String name, SubtitleFeed.Source source, String text) {
	}

	/**
	 * Takes a line that is about to leave the feed. Lines nobody ever put words
	 * to – somebody talked, the recogniser had nothing – are dropped: an entry
	 * saying only that a voice happened is not worth reading back.
	 */
	public static void record(SubtitleFeed.Line line) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.keepLog || line.placeholder()) {
			return;
		}
		Entry entry = new Entry(line.startedAt(), line.speaker,
				NameResolver.nameOf(line.speaker).getString(), line.source, line.text());

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

	/** @return every entry, oldest first. */
	public static List<Entry> entries() {
		synchronized (ENTRIES) {
			return new ArrayList<>(ENTRIES);
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
			writer.write("# Lines marked [chat] arrived as text and are exact.\n\n");
			for (Entry entry : entries) {
				writer.write("[" + timeOf(entry) + "] "
						+ (entry.source() == SubtitleFeed.Source.CHAT ? "[chat] " : "")
						+ entry.name() + ": " + entry.text() + "\n");
			}
		}
		return file;
	}
}
