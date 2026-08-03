package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The lines currently on screen.
 *
 * <p>One {@link Line} is one continuous utterance by one speaker: it opens when
 * their audio starts arriving, grows as speech is recognised, and closes when
 * they fall silent. Written from the voice chat's audio thread, read from the
 * render thread.
 *
 * <p>A line carries no text until the recogniser has something to say about it;
 * the HUD draws an animated "speaking" placeholder in the meantime. The entry
 * point for the recogniser is {@link #putText}.
 */
public final class SubtitleFeed {

	/** Silence longer than this ends the current utterance. */
	private static final long UTTERANCE_GAP_MS = 900L;

	/** How long a closed line can still absorb a late refinement of itself. */
	private static final long MERGE_WINDOW_MS = 2500L;

	private static final CopyOnWriteArrayList<Line> LINES = new CopyOnWriteArrayList<>();

	private SubtitleFeed() {
	}

	/** Where a line came from. */
	public enum Source {
		VOICE,
		CHAT
	}

	/** One utterance by one speaker. */
	public static final class Line {
		public final UUID speaker;
		public final Source source;
		/** Opacity from distance and whispering, 0–1; kept current while the line is open. */
		volatile float rangeAlpha = 1F;
		volatile boolean whisper;
		/** Recognised text so far; empty while we only know that somebody is talking. */
		volatile String text = "";
		/** True while the recogniser may still extend or correct {@link #text}. */
		volatile boolean partial = true;
		volatile long startedAt;
		volatile long lastAudioAt;
		/** When the utterance ended, or 0 while it is still going. */
		volatile long closedAt;

		Line(UUID speaker, Source source, long now) {
			this.speaker = speaker;
			this.source = source;
			this.startedAt = now;
			this.lastAudioAt = now;
		}

		public float rangeAlpha() {
			return rangeAlpha;
		}

		public void setRangeAlpha(float alpha) {
			this.rangeAlpha = alpha;
		}

		public boolean whisper() {
			return whisper;
		}

		public String text() {
			return text;
		}

		public boolean partial() {
			return partial;
		}

		public boolean open() {
			return closedAt == 0L;
		}

		public long startedAt() {
			return startedAt;
		}

		public long closedAt() {
			return closedAt;
		}

		/** @return true while nothing has been recognised yet. */
		public boolean placeholder() {
			return text.isEmpty();
		}
	}

	/**
	 * Called for every audio frame received from a speaker: opens a line if they
	 * were silent, keeps it alive otherwise.
	 */
	public static void onAudio(UUID speaker, boolean whisper) {
		long now = System.currentTimeMillis();
		Line open = openLineOf(speaker);
		if (open != null && now - open.lastAudioAt <= UTTERANCE_GAP_MS) {
			open.lastAudioAt = now;
			open.whisper = whisper;
			return;
		}
		if (open != null) {
			open.closedAt = now;
			open.partial = false;
		}
		Line line = new Line(speaker, Source.VOICE, now);
		line.whisper = whisper;
		LINES.add(line);
		trim();
	}

	/** Adds a finished line straight from text chat. */
	public static void pushChat(UUID speaker, String text) {
		long now = System.currentTimeMillis();
		Line line = new Line(speaker, Source.CHAT, now);
		line.text = text;
		line.partial = false;
		line.closedAt = now;
		LINES.add(line);
		trim();
	}

	/**
	 * Feeds recognised text into the speaker's current line – the hook the
	 * speech recogniser will use.
	 *
	 * @param finalText true for a settled result, false for a partial one that
	 *                  may still change
	 */
	public static void putText(UUID speaker, String text, boolean finalText) {
		if (text == null) {
			return;
		}
		long now = System.currentTimeMillis();
		Line line = openLineOf(speaker);
		if (line == null) {
			line = mergeableLineOf(speaker, text, now);
		}
		if (line == null) {
			line = new Line(speaker, Source.VOICE, now);
			LINES.add(line);
			trim();
		} else if (!line.open()) {
			// Reopened: the recogniser is still refining a sentence it had
			// already settled once.
			line.closedAt = 0L;
		}
		line.text = text;
		line.partial = !finalText;
		line.lastAudioAt = now;
		if (finalText) {
			line.closedAt = now;
		}
	}

	/** Ends the speaker's current utterance (silence, or end of transmission). */
	public static void close(UUID speaker) {
		Line line = openLineOf(speaker);
		if (line != null) {
			line.closedAt = System.currentTimeMillis();
			line.partial = false;
		}
	}

	/** Drops expired lines and closes ones whose audio stopped arriving. */
	public static void tick() {
		long now = System.currentTimeMillis();
		for (Iterator<Line> it = LINES.iterator(); it.hasNext(); ) {
			Line line = it.next();
			if (line.open() && line.source == Source.VOICE && now - line.lastAudioAt > UTTERANCE_GAP_MS) {
				line.closedAt = now;
				line.partial = false;
			}
			if (!line.open() && now - line.closedAt > Captions.lifetimeOf(line)) {
				retire(line);
			}
		}
	}

	/** @return the visible lines, oldest first. */
	public static List<Line> visible() {
		if (LINES.isEmpty()) {
			return Collections.emptyList();
		}
		List<Line> all = new ArrayList<>(LINES);
		int max = VoiceSubtitlesConfig.get().maxLines;
		return all.size() <= max ? all : all.subList(all.size() - max, all.size());
	}

	/** Empties the feed, filing whatever was still on screen. */
	public static void clear() {
		for (Line line : LINES) {
			SubtitleLog.record(line);
		}
		LINES.clear();
	}

	/** @return every live line, oldest first – ignores the on-screen line limit. */
	public static List<Line> all() {
		return LINES.isEmpty() ? Collections.emptyList() : new ArrayList<>(LINES);
	}

	/** @return this speaker's most recent line, or {@code null} if they have none. */
	public static Line lastLineOf(UUID speaker) {
		return lastLineOf(speaker, line -> true);
	}

	/** @return this speaker's most recent line matching {@code filter}, or {@code null}. */
	public static Line lastLineOf(UUID speaker, Predicate<Line> filter) {
		for (int i = LINES.size() - 1; i >= 0; i--) {
			Line line = LINES.get(i);
			if (line.speaker.equals(speaker) && filter.test(line)) {
				return line;
			}
		}
		return null;
	}

	/**
	 * The speaker's just-closed line, when the incoming text is plainly the same
	 * sentence rather than a new one.
	 *
	 * <p>Recognisers settle a sentence and then keep refining it: the final
	 * result often arrives a moment after the line was closed, and repeats
	 * everything already shown plus the last word or two. Treated as new speech,
	 * that reads as the whole sentence being said twice. So if one text is a
	 * prefix of the other and the line closed a moment ago, they are the same
	 * utterance and the existing line is updated in place.
	 */
	private static Line mergeableLineOf(UUID speaker, String text, long now) {
		Line last = lastLineOf(speaker);
		if (last == null || last.source != Source.VOICE || last.open()) {
			return null;
		}
		if (now - last.closedAt > MERGE_WINDOW_MS) {
			return null;
		}
		String existing = normalise(last.text);
		String incoming = normalise(text);
		if (existing.isEmpty() || incoming.isEmpty()) {
			return null;
		}
		return incoming.startsWith(existing) || existing.startsWith(incoming) ? last : null;
	}

	private static String normalise(String text) {
		return text.trim().toLowerCase().replaceAll("\\s+", " ");
	}

	private static Line openLineOf(UUID speaker) {
		for (int i = LINES.size() - 1; i >= 0; i--) {
			Line line = LINES.get(i);
			if (line.speaker.equals(speaker)) {
				return line.open() ? line : null;
			}
		}
		return null;
	}

	/** Keeps the backing list from growing without bound. */
	private static void trim() {
		int keep = Math.max(VoiceSubtitlesConfig.get().maxLines * 3, 12);
		while (LINES.size() > keep) {
			retire(LINES.get(0));
		}
	}

	/**
	 * Drops a line from the feed and hands it to the log.
	 *
	 * <p>Every line leaves through here, and only once – which is the reason the
	 * log is written on removal rather than when a line closes: a closed line can
	 * still be reopened by a late correction from the recogniser, and logging on
	 * close would file both versions.
	 */
	private static void retire(Line line) {
		if (LINES.remove(line)) {
			SubtitleLog.record(line);
		}
	}
}
