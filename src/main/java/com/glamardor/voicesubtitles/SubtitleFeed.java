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
		CHAT,
		/** Something that happened rather than was said; log only, never drawn. */
		EVENT
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
		/**
		 * The language this was said in, as far as the mod was told.
		 *
		 * <p>Set by whichever model recognised it, which is the only place that
		 * knows: the text itself cannot be trusted to say, since a model handed a
		 * language it does not know writes confident nonsense in its own alphabet.
		 */
		public volatile String spokenLanguage = "";
		/** What it says in the language you read, once there is one. Empty otherwise. */
		public volatile String translation = "";
		/** True while a translation has been asked for and has not come back. */
		public volatile boolean translating;
		/** True while the recogniser may still extend or correct {@link #text}. */
		volatile boolean partial = true;
		volatile long startedAt;
		volatile long lastAudioAt;
		/**
		 * When the first words appeared in this line, which is not when the line
		 * did.
		 *
		 * <p>The two used to be the same thing, and that is why nobody ever saw
		 * the text arrive a few characters at a time. A line opens when somebody
		 * starts talking; the words turn up a second later, once the recogniser
		 * has decided what they were. Counting the reveal from the start of the
		 * speech meant a second's worth of characters – ninety of them – were
		 * already allowed by the time there was anything to allow, so every
		 * caption appeared whole.
		 */
		volatile long textSince;
		/** How many characters have been revealed so far, carried between frames. */
		volatile float revealed;
		/** When that was last worked out. */
		volatile long revealedAt;
		/** When the utterance ended, or 0 while it is still going. */
		volatile long closedAt;
		/**
		 * Set once this line has gone into the log, so it goes there once only.
		 */
		volatile boolean logged;
		/**
		 * True while a recogniser is still working on this line.
		 *
		 * <p>Held lines are not thrown away, however long they sit there. A long
		 * utterance takes seconds to decode in full, and without this the line
		 * expired first, went into the log carrying the half of the sentence it
		 * had at that moment, and the finished text arrived to find it already
		 * filed. The story ended after three lines and the rest was simply gone.
		 */
		volatile boolean held;

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

		/** @return when the words appeared, or when the line did if they have not. */
		public long textSince() {
			return textSince == 0L ? startedAt : textSince;
		}

		/**
		 * Moves the reveal on and says how much of the line may be shown.
		 *
		 * <p>The count is carried rather than worked out from the clock, and that
		 * is the whole of it. Worked out from the clock, the allowance outruns the
		 * speaking within a second – nobody talks at fifty-five characters a
		 * second – and from then on every phrase the recogniser hands over is
		 * already allowed in full and lands on screen whole. Which is exactly what
		 * it looked like: two or three words typing themselves, and the rest of
		 * the sentence arriving in slabs.
		 *
		 * <p>Carried and held back to what has actually been said, the count sits
		 * waiting at the end of the text, and each new phrase is typed out from
		 * where the last one stopped.
		 *
		 * @param speed characters per second
		 * @return how many characters may be shown
		 */
		public int reveal(long now, int speed) {
			long last = revealedAt;
			if (last == 0L) {
				revealedAt = now;
				last = now;
			}
			if (now > last) {
				revealed += (now - last) * Math.max(speed, 1) / 1000F;
				revealedAt = now;
			}
			int length = text.length();
			if (revealed > length) {
				// Never further than what has been said, so there is always
				// something left to type when the next words arrive.
				revealed = length;
			}
			// And never much further behind it either. Without this the typing
			// falls a whole sentence behind the speaking: the recogniser settles
			// a long utterance in one go, the counter is somewhere in the middle
			// of it, and the rest is then typed out over the second and a half
			// after the speaker has stopped. Held to about four fifths of a
			// second's worth, the tail arrives while it is still wanted, and
			// there is still enough left to see it arrive.
			float most = Math.max(20F, speed * 0.8F);
			if (length - revealed > most) {
				revealed = length - most;
			}
			return (int) revealed;
		}

		public long closedAt() {
			return closedAt;
		}

		/** @return true while nothing has been recognised yet. */
		public boolean placeholder() {
			return text.isEmpty();
		}

		public String translation() {
			return translation;
		}

		/** @return true when there is a translation to show under the words. */
		public boolean translated() {
			return !translation.isEmpty();
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
		line.textSince = now;
		line.partial = false;
		line.closedAt = now;
		LINES.add(line);
		trim();
		// Written text says what language it is in by its own alphabet, so chat
		// is translated without anybody having to be marked as anything.
		com.glamardor.voicesubtitles.translate.Translation.requestChat(line);
	}

	/**
	 * The line this speaker's words belong in, opening one if they have none.
	 *
	 * <p>Handed to the recogniser so it can keep writing into the same line for
	 * as long as one utterance lasts. It used to look the line up by matching
	 * text instead, which broke as soon as an engine changed its mind: the
	 * whole-utterance models decode the same audio repeatedly and the wording
	 * shifts between passes, so "можно эту модель" became "может, эту модель",
	 * failed to match, and the corrected sentence appeared as a second caption
	 * beside the first.
	 */
	public static Line openOrCreate(UUID speaker) {
		Line open = openLineOf(speaker);
		if (open != null) {
			open.held = true;
			return open;
		}
		Line line = new Line(speaker, Source.VOICE, System.currentTimeMillis());
		line.held = true;
		LINES.add(line);
		trim();
		return line;
	}

	/** The recogniser has finished with this line; it may now expire and be filed. */
	public static void release(Line line) {
		if (line != null) {
			line.held = false;
		}
	}

	/**
	 * Puts recognised text into a line.
	 *
	 * @param finalText true for a settled result, false for one that may still
	 *                  change
	 */
	public static void setText(Line line, String text, boolean finalText) {
		if (line == null || text == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!LINES.contains(line)) {
			// Gone from the feed while the recogniser was still working on it:
			// put it back rather than losing the sentence it was holding.
			LINES.add(line);
			trim();
		}
		if (!line.open()) {
			// Reopened: the recogniser is still refining a sentence it had
			// already settled once.
			line.closedAt = 0L;
		}
		// Only the first words start the clock. A sentence the recogniser goes on
		// refining must not begin revealing itself again on every pass.
		if (line.textSince == 0L && !text.isEmpty()) {
			line.textSince = now;
		}
		line.text = text;
		line.partial = !finalText;
		line.lastAudioAt = now;
		if (finalText) {
			line.closedAt = now;
			line.held = false;
		}
	}

	/** Keeps an open line from timing out, for a caption with no audio behind it. */
	public static void keepAlive(Line line) {
		if (line != null && line.open()) {
			line.lastAudioAt = System.currentTimeMillis();
		}
	}

	/** Ends this line, whatever it holds. */
	public static void close(Line line) {
		if (line != null && line.open()) {
			line.closedAt = System.currentTimeMillis();
			line.partial = false;
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
			// A line still being worked on stays, however long it has been closed.
			// Being translated counts as being worked on: the first translation
			// of an evening waits for its model, and losing the line meanwhile
			// would file the sentence without the half somebody is waiting for.
			if (!line.open() && !line.held && !line.translating
					&& now - line.closedAt > Captions.lifetimeOf(line)) {
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
		for (int i = 0; i < LINES.size() && LINES.size() > keep; i++) {
			Line oldest = LINES.get(i);
			if (!oldest.held && !oldest.translating) {
				retire(oldest);
				i--;
			}
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
