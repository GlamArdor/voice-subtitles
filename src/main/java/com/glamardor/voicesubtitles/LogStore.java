package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The conversation log on disk.
 *
 * <p>A log that vanishes when the game closes is a log nobody trusts: the whole
 * reason to look one up is to find something said a while ago, and "a while"
 * routinely spans a restart. So every line is written out as it is filed, one
 * file per day, and the recent ones are read back at start-up.
 *
 * <p>One line of JSON per entry rather than the pretty format the export
 * produces: this file is for the mod to read back, and appending to it must
 * stay cheap. Writing happens on a thread of its own, because filing a caption
 * must never be something the game waits for.
 */
final class LogStore {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private static final BlockingQueue<SubtitleLog.Entry> PENDING = new LinkedBlockingQueue<>();
	private static volatile Thread writer;

	private LogStore() {
	}

	/** Where the day files live: {@code config/voice_subtitles/history}. */
	static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve("voice_subtitles").resolve("history");
	}

	private static Path fileFor(long time) {
		LocalDate day = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate();
		return directory().resolve(DAY.format(day) + ".jsonl");
	}

	/** Queues an entry to be written. Returns at once. */
	static void append(SubtitleLog.Entry entry) {
		PENDING.add(entry);
		start();
	}

	private static synchronized void start() {
		if (writer != null) {
			return;
		}
		Thread thread = new Thread(LogStore::run, "voice-subtitles-log-writer");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
		writer = thread;
	}

	private static void run() {
		while (true) {
			try {
				SubtitleLog.Entry first = PENDING.take();
				List<SubtitleLog.Entry> batch = new ArrayList<>();
				batch.add(first);
				// Whatever else has piled up goes out in the same open-write-close,
				// which matters during a busy conversation.
				PENDING.drainTo(batch);
				write(batch);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Throwable t) {
				VoiceSubtitles.LOGGER.warn("Could not write the conversation log", t);
			}
		}
	}

	private static void write(List<SubtitleLog.Entry> batch) throws IOException {
		Files.createDirectories(directory());
		// Grouped by day, because a conversation running past midnight belongs to
		// both files rather than to whichever one it started in.
		Path current = null;
		BufferedWriter out = null;
		try {
			for (SubtitleLog.Entry entry : batch) {
				Path file = fileFor(entry.time());
				if (!file.equals(current)) {
					if (out != null) {
						out.close();
					}
					out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
							StandardOpenOption.CREATE, StandardOpenOption.APPEND);
					current = file;
				}
				out.write(encode(entry));
				out.newLine();
			}
		} finally {
			if (out != null) {
				out.close();
			}
		}
	}

	/**
	 * Reads the most recent entries back, newest days first, up to {@code limit}.
	 *
	 * @return the entries in the order they were spoken
	 */
	static List<SubtitleLog.Entry> load(int limit) {
		Path dir = directory();
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		List<Path> days = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
					.sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
					.forEach(days::add);
		} catch (IOException e) {
			VoiceSubtitles.LOGGER.warn("Could not list the conversation log", e);
			return List.of();
		}

		List<SubtitleLog.Entry> collected = new ArrayList<>();
		for (Path day : days) {
			if (collected.size() >= limit) {
				break;
			}
			List<SubtitleLog.Entry> ofDay = new ArrayList<>();
			try {
				for (String line : Files.readAllLines(day, StandardCharsets.UTF_8)) {
					SubtitleLog.Entry entry = decode(line);
					if (entry != null) {
						ofDay.add(entry);
					}
				}
			} catch (Exception e) {
				VoiceSubtitles.LOGGER.warn("Could not read {}", day.getFileName(), e);
				continue;
			}
			// Newest days are read first, so each day goes in front of the rest.
			int room = limit - collected.size();
			if (ofDay.size() > room) {
				ofDay = ofDay.subList(ofDay.size() - room, ofDay.size());
			}
			collected.addAll(0, ofDay);
		}
		return collected;
	}

	/** Deletes day files older than {@code keepDays}. */
	static void prune(int keepDays) {
		Path dir = directory();
		if (!Files.isDirectory(dir) || keepDays <= 0) {
			return;
		}
		String oldest = DAY.format(LocalDate.now().minusDays(keepDays));
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				if (name.endsWith(".jsonl") && name.substring(0, name.length() - 6).compareTo(oldest) < 0) {
					Files.deleteIfExists(file);
					VoiceSubtitles.LOGGER.info("Dropped the conversation log for {}", name);
				}
			}
		} catch (IOException e) {
			VoiceSubtitles.LOGGER.warn("Could not prune the conversation log", e);
		}
	}

	/** Removes every day file. Used when the player empties the log by hand. */
	static void deleteAll() {
		PENDING.clear();
		Path dir = directory();
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.toList()) {
				if (file.getFileName().toString().endsWith(".jsonl")) {
					Files.deleteIfExists(file);
				}
			}
		} catch (IOException e) {
			VoiceSubtitles.LOGGER.warn("Could not delete the conversation log", e);
		}
	}

	/** Waits briefly for anything still queued, so a quit does not lose the last words. */
	static void flush() {
		for (int i = 0; i < 20 && !PENDING.isEmpty(); i++) {
			try {
				TimeUnit.MILLISECONDS.sleep(25L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	// ── the on-disk form ────────────────────────────────────────────────────

	private static String encode(SubtitleLog.Entry entry) {
		return "{\"t\":" + entry.time()
				+ ",\"id\":\"" + entry.speaker()
				+ "\",\"n\":\"" + escape(entry.name())
				+ "\",\"s\":\"" + switch (entry.source()) {
					case CHAT -> "chat";
					case EVENT -> "event";
					case VOICE -> "voice";
				}
				+ "\",\"x\":\"" + escape(entry.text()) + "\"}";
	}

	private static SubtitleLog.Entry decode(String line) {
		try {
			long time = Long.parseLong(field(line, "\"t\":", ','));
			UUID id = UUID.fromString(quoted(line, "\"id\":"));
			String name = unescape(quoted(line, "\"n\":"));
			String source = quoted(line, "\"s\":");
			String text = unescape(quoted(line, "\"x\":"));
			SubtitleFeed.Source kind = switch (source) {
				case "chat" -> SubtitleFeed.Source.CHAT;
				case "event" -> SubtitleFeed.Source.EVENT;
				default -> SubtitleFeed.Source.VOICE;
			};
			return new SubtitleLog.Entry(time, id, name, kind, text);
		} catch (Exception e) {
			// A half-written last line after a crash is not worth a stack trace.
			return null;
		}
	}

	private static String field(String line, String key, char end) {
		int at = line.indexOf(key) + key.length();
		int to = line.indexOf(end, at);
		return line.substring(at, to);
	}

	private static String quoted(String line, String key) {
		int at = line.indexOf(key) + key.length() + 1;
		StringBuilder out = new StringBuilder();
		for (int i = at; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '\\') {
				out.append(c).append(line.charAt(++i));
			} else if (c == '"') {
				break;
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
	}

	private static String unescape(String text) {
		return text.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
	}
}
