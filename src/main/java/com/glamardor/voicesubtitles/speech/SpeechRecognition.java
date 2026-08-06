package com.glamardor.voicesubtitles.speech;

import com.glamardor.voicesubtitles.SubtitleFeed;
import com.glamardor.voicesubtitles.VoiceSubtitles;
import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.translate.Languages;
import com.glamardor.voicesubtitles.translate.Translation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Offline speech recognition for incoming voice.
 *
 * <p>One model per language, one recogniser per speaker. Audio arrives on the
 * voice chat's own thread and must never be held up there, so frames are dropped
 * into a bounded queue and a worker per speaker does the actual work: resample
 * 48 kHz down to the 16 kHz the engines want, feed it, and push whatever comes
 * back into the subtitle feed – partial results while the sentence is still
 * forming, a settled one when the utterance ends.
 *
 * <p>Which model a speaker's audio goes to follows from the language they are
 * taken to be speaking; see {@link Languages}. Nearly always there is one model
 * and it takes everybody. When somebody has been marked as speaking something
 * else, a second model is loaded for them, and on a quiet evening it is let go
 * again.
 *
 * <p>If a queue fills up, frames are dropped rather than queued forever. A
 * recogniser that has fallen that far behind will not catch up, and a caption
 * that arrives ten seconds late is worse than one that misses a word. The one
 * exception is a model that has not finished loading: that audio is held, so the
 * first thing somebody says while their model is still coming up is not lost.
 *
 * <p>Everything degrades quietly: no model, a native library that will not load,
 * a machine too slow to keep up – the mod keeps showing who is talking, just
 * without the words.
 */
public final class SpeechRecognition {

	public enum State {
		/** Turned off in the settings. */
		DISABLED,
		/** No model downloaded yet. */
		NO_MODEL,
		/** Model is being read into memory. */
		LOADING,
		/** Recognising. */
		READY,
		/** Something went wrong; see {@link #failure()}. */
		FAILED
	}

	/** Two seconds of 20 ms frames – beyond that we are not catching up anyway. */
	private static final int QUEUE_CAPACITY = 100;

	/**
	 * How much speech is held back while a model is still loading, in 16 kHz
	 * samples. Ten seconds: long enough for the slowest model here to come up
	 * mid-sentence without the sentence being lost, short enough that a download
	 * running in the background does not quietly eat memory.
	 */
	private static final int PENDING_SAMPLES = 16_000 * 10;

	/** Marker frame meaning "the speaker stopped, settle whatever you have". */
	private static final short[] END_OF_SPEECH = new short[0];

	/**
	 * Silence this long ends the utterance by itself.
	 *
	 * <p>Simple Voice Chat does not reliably mark the end of a transmission, and
	 * waiting for a marker that may never come leaves the last words sitting
	 * inside the recogniser: a short "привет" would never surface, and whatever
	 * was left over would be glued to the front of the next sentence. Shorter
	 * than the gap the feed uses to split utterances, so the words arrive before
	 * their line closes.
	 */
	private static final long SILENCE_SETTLE_MS = 650L;

	/** A session with no audio for this long is closed and its slot freed. */
	private static final long IDLE_TIMEOUT_MS = 15_000L;

	/**
	 * A model nobody has needed for this long is unloaded, unless it is the one
	 * everybody here speaks or the settings say to keep it.
	 */
	private static final long IDLE_UNLOAD_MS = 5L * 60_000L;

	private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
	/** Language code to the model loaded for it. */
	private static final Map<String, Slot> SLOTS = new ConcurrentHashMap<>();

	private SpeechRecognition() {
	}

	/** One language's model: what it is, whether it is up, and when it was last wanted. */
	private static final class Slot {

		final String language;
		volatile String modelId;
		volatile SpeechEngine engine;
		volatile State state = State.NO_MODEL;
		volatile String failure = "";
		volatile long loadStartedAt;
		volatile long lastUsedAt = System.currentTimeMillis();

		Slot(String language) {
			this.language = language;
		}

		void close() {
			SpeechEngine current = engine;
			engine = null;
			state = State.DISABLED;
			if (current != null) {
				try {
					current.close();
				} catch (Throwable ignored) {
				}
			}
		}
	}

	// ── What the rest of the mod asks about ──────────────────────────────────

	/** The state of the model most of the speech goes through. */
	public static State state() {
		if (!VoiceSubtitlesConfig.get().speechEnabled) {
			return State.DISABLED;
		}
		Slot slot = SLOTS.get(Languages.defaultSpoken());
		return slot == null ? State.NO_MODEL : slot.state;
	}

	public static String failure() {
		Slot loading = anyLoading();
		if (loading != null && !loading.failure.isEmpty()) {
			return loading.failure;
		}
		Slot slot = SLOTS.get(Languages.defaultSpoken());
		return slot == null ? "" : slot.failure;
	}

	public static int activeRecognizers() {
		return SESSIONS.size();
	}

	/** @return the model currently loading or loaded, or {@code null}. */
	public static String currentModelId() {
		Slot slot = anyLoading();
		if (slot == null) {
			slot = SLOTS.get(Languages.defaultSpoken());
		}
		return slot == null ? null : slot.modelId;
	}

	/** Progress of the current load, 0–1, or -1 when nothing is loading. */
	public static float loadProgress() {
		Slot slot = anyLoading();
		if (slot == null) {
			return -1F;
		}
		long elapsed = System.currentTimeMillis() - slot.loadStartedAt;
		Integer expected = VoiceSubtitlesConfig.get().modelLoadMillis.get(slot.modelId);
		if (expected == null || expected <= 0) {
			// Never loaded this one before, so there is nothing to measure against.
			return -1F;
		}
		// Never quite full: the bar reaching the end while the game is still busy
		// reads as "stuck", which is worse than reading as "nearly there".
		return Math.min(0.97F, elapsed / (float) expected);
	}

	/** Seconds the current load has been running. */
	public static long loadElapsedSeconds() {
		Slot slot = anyLoading();
		return slot == null ? 0L : (System.currentTimeMillis() - slot.loadStartedAt) / 1000L;
	}

	private static Slot anyLoading() {
		Slot primary = SLOTS.get(Languages.defaultSpoken());
		if (primary != null && primary.state == State.LOADING) {
			return primary;
		}
		for (Slot slot : SLOTS.values()) {
			if (slot.state == State.LOADING) {
				return slot;
			}
		}
		return null;
	}

	// ── Loading and unloading ────────────────────────────────────────────────

	/** Loads whatever should be loaded right now, fetching it first if needed. */
	public static void init() {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.speechEnabled) {
			return;
		}
		if (com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig.get().translationMode
				== VoiceSubtitlesConfig.TranslationMode.ALWAYS) {
			// Somebody who hears another language all evening should not wait for
			// the first sentence of it to start the download.
			for (String language : Languages.expected()) {
				ensure(language);
			}
			Translation.warmUp();
		} else {
			ensure(Languages.defaultSpoken());
		}
	}

	/**
	 * Picks up a settings change.
	 *
	 * <p>Reloading is not free: the large Russian model takes the better part of
	 * a minute to read back into memory, and closing the settings screen used to
	 * pay that price whether or not anything about recognition had changed. So a
	 * model is only torn down when it is genuinely a different model, or when
	 * nobody is expected to speak its language any more.
	 */
	public static void reload() {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.speechEnabled) {
			shutdown();
			return;
		}

		Set<String> wanted = Languages.expected();
		for (Slot slot : SLOTS.values()) {
			String nowWants = modelIdFor(slot.language, cfg);
			if (!wanted.contains(slot.language) || nowWants == null || !nowWants.equals(slot.modelId)) {
				unload(slot);
			}
		}
		init();
		Translation.reload();
	}

	/** Lets go of a model nobody has needed in a while, and of idle recognisers. */
	public static void tick() {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (cfg.translationMode == VoiceSubtitlesConfig.TranslationMode.ALWAYS) {
			return;
		}
		long now = System.currentTimeMillis();
		String primary = Languages.defaultSpoken();
		for (Slot slot : SLOTS.values()) {
			if (slot.language.equals(primary) || slot.state == State.NO_MODEL) {
				continue;
			}
			if (now - slot.lastUsedAt > IDLE_UNLOAD_MS) {
				VoiceSubtitles.LOGGER.info("Letting go of the {} model; nobody has spoken it for a while",
						slot.language);
				unload(slot);
			}
		}
	}

	public static void shutdown() {
		for (Session session : SESSIONS.values()) {
			session.close();
		}
		SESSIONS.clear();
		for (Slot slot : SLOTS.values()) {
			slot.close();
		}
		SLOTS.clear();
	}

	private static void unload(Slot slot) {
		SLOTS.remove(slot.language, slot);
		for (Session session : SESSIONS.values()) {
			if (session.slot == slot) {
				session.close();
			}
		}
		slot.close();
	}

	/**
	 * The slot for a language, starting its model if it is not up yet.
	 *
	 * @return the slot, or {@code null} when recognition is off entirely
	 */
	private static Slot ensure(String language) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.speechEnabled) {
			return null;
		}
		Slot slot = SLOTS.computeIfAbsent(language, Slot::new);
		slot.lastUsedAt = System.currentTimeMillis();
		if (slot.state == State.READY || slot.state == State.LOADING || slot.state == State.FAILED) {
			return slot;
		}
		startLoading(slot, cfg);
		return slot;
	}

	/** The model that covers a language: the chosen one for the main one, the catalogue's pick otherwise. */
	private static String modelIdFor(String language, VoiceSubtitlesConfig cfg) {
		if (language.equals(Languages.defaultSpoken())) {
			return resolveModelId(cfg);
		}
		String id = SpeechModels.defaultFor(language);
		return SpeechModels.byId(id) == null ? null : id;
	}

	private static void startLoading(Slot slot, VoiceSubtitlesConfig cfg) {
		String id = modelIdFor(slot.language, cfg);
		if (id == null) {
			slot.state = State.NO_MODEL;
			slot.failure = "no model in the catalogue for " + slot.language;
			return;
		}
		SpeechModels.Entry entry = SpeechModels.byId(id);
		if (entry == null) {
			slot.state = State.NO_MODEL;
			return;
		}
		slot.modelId = id;

		// This model is what the game was opening when it died last time. It is
		// not opened again until somebody says so: opening it is what killed the
		// game, and doing that a second time to see whether it still does is not
		// a diagnosis, it is another dead game.
		String tag = "model:" + entry.id();
		if (CrashGuard.isSuspect(tag)) {
			slot.state = State.FAILED;
			slot.failure = "this model took the game down last time; /vsub model repair fetches it again";
			VoiceSubtitles.LOGGER.warn("Model '{}' is being left alone after it took the game down. "
					+ "Run /vsub model repair to throw it away and fetch it again.", entry.id());
			return;
		}

		if (!SpeechModels.isInstalled(entry)) {
			slot.state = State.NO_MODEL;
			if (cfg.autoDownloadModel) {
				ModelDownload.startOnce(entry, SpeechRecognition::init);
			}
			return;
		}
		// A model is useless without the library that runs it, and that is a
		// separate download for either engine; ask for it before claiming to be
		// loading anything.
		if (!nativesReady(slot, entry, cfg)) {
			return;
		}

		slot.state = State.LOADING;
		slot.loadStartedAt = System.currentTimeMillis();
		Thread loader = new Thread(() -> {
			try {
				// Noted on disk first: what happens next is inside a native
				// library, and if it goes wrong there is no coming back to write
				// anything down afterwards.
				CrashGuard.begin(tag);
				SpeechEngine engine = switch (entry.engine()) {
					case SHERPA_STREAMING -> SherpaEngine.load(entry);
					case SHERPA_OFFLINE -> SherpaOfflineEngine.load(entry);
					case VOSK -> VoskEngine.load(entry);
				};
				CrashGuard.end();
				slot.engine = engine;
				slot.state = State.READY;
				long took = System.currentTimeMillis() - slot.loadStartedAt;
				rememberLoadTime(entry.id(), took);
				VoiceSubtitles.LOGGER.info("Speech model '{}' loaded in {} ms", entry.id(), took);
			} catch (Throwable t) {
				// Throwable, not Exception: a missing or unloadable native
				// library surfaces as UnsatisfiedLinkError, and that must not
				// take the game down with it.
				CrashGuard.end();
				slot.state = State.FAILED;
				slot.failure = t.getClass().getSimpleName() + ": " + t.getMessage();
				VoiceSubtitles.LOGGER.error("Could not load the speech model", t);
			}
		}, "voice-subtitles-model-loader-" + slot.language);
		loader.setDaemon(true);
		loader.start();
	}

	/**
	 * Makes sure the library this model runs on is on disk and in the JVM.
	 *
	 * @return true when recognition can go ahead; otherwise the slot has been set
	 *         to explain why and a download may have been started
	 */
	private static boolean nativesReady(Slot slot, SpeechModels.Entry entry, VoiceSubtitlesConfig cfg) {
		if (entry.engine() == SpeechModels.Engine.VOSK) {
			return voskReady(slot, cfg);
		}
		SherpaNatives.Build build = SherpaNatives.buildForThisMachine();
		if (build == null) {
			slot.state = State.FAILED;
			slot.failure = "sherpa-onnx has no build for this platform; pick a Vosk model instead";
			return false;
		}
		if (!SherpaNatives.isInstalled(build)) {
			slot.state = State.NO_MODEL;
			if (cfg.autoDownloadModel) {
				ModelDownload.startNativesOnce(build, SpeechRecognition::init);
			}
			return false;
		}
		if (!SherpaNatives.load()) {
			slot.state = State.FAILED;
			slot.failure = SherpaNatives.failure();
			return false;
		}
		return true;
	}

	/** The same for Vosk, whose library is a download of its own. */
	private static boolean voskReady(Slot slot, VoiceSubtitlesConfig cfg) {
		if (!VoskNatives.isSupported()) {
			slot.state = State.FAILED;
			slot.failure = "Vosk has no build for this platform; pick a sherpa-onnx model instead";
			return false;
		}
		if (!VoskNatives.isInstalled()) {
			slot.state = State.NO_MODEL;
			if (cfg.autoDownloadModel) {
				ModelDownload.startVoskNativesOnce(SpeechRecognition::init);
			}
			return false;
		}
		if (!VoskNatives.load()) {
			slot.state = State.FAILED;
			slot.failure = VoskNatives.failure();
			return false;
		}
		return true;
	}

	/**
	 * The model for the language spoken here: whatever is configured, or – on a
	 * fresh install – the one matching that language, which on nearly every
	 * client is the language the game itself is in.
	 *
	 * @return null while the game options do not exist yet, which is the case
	 *         during mod initialisation – ask again once the client is up
	 */
	public static String resolveModelId(VoiceSubtitlesConfig cfg) {
		if (net.minecraft.client.MinecraftClient.getInstance().options == null) {
			return null;
		}
		String code = Languages.defaultSpoken();

		if (cfg.modelId != null && !cfg.modelId.isBlank()) {
			// A model belongs to a language, and the language spoken here is a
			// setting of its own. Changing one and not the other used to leave the
			// Russian everybody was speaking going into an English model, quietly,
			// with nothing on screen to say so – so the model follows the language.
			// A model already of the right language is left exactly as chosen: it
			// is the choice between GigaAM and Vosk, and that is the player's.
			if (cfg.modelId.split("-")[0].equals(code)) {
				return cfg.modelId;
			}
			VoiceSubtitles.LOGGER.info("Model '{}' is not for {}; switching to the one that is",
					cfg.modelId, code);
		}
		String chosen = SpeechModels.byId(code) != null ? SpeechModels.defaultFor(code) : "en";

		cfg.modelId = chosen;
		VoiceSubtitlesConfig.save();
		VoiceSubtitles.LOGGER.info("Speech model for {} is now '{}'", code, chosen);
		return chosen;
	}

	/**
	 * Files how long a model took to load, so the next load can show a real
	 * progress bar instead of a spinner.
	 */
	private static void rememberLoadTime(String id, long millis) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		Integer previous = cfg.modelLoadMillis.get(id);
		// Averaged with what we saw last time: the first load of a big model is
		// slower than the ones after it, once the file is in the disk cache.
		int value = previous == null ? (int) millis : (int) ((previous + millis) / 2);
		cfg.modelLoadMillis.put(id, value);
		VoiceSubtitlesConfig.save();
	}

	// ── Audio in ─────────────────────────────────────────────────────────────

	/**
	 * Hands one 48 kHz frame to this speaker's recogniser. Called from the voice
	 * chat's audio thread; never blocks.
	 */
	public static void feed(UUID speaker, short[] audio48k) {
		if (audio48k == null || audio48k.length == 0 || !VoiceSubtitlesConfig.get().speechEnabled) {
			return;
		}
		Slot slot = ensure(Languages.spokenBy(speaker));
		if (slot == null || slot.state == State.FAILED || slot.state == State.NO_MODEL) {
			return;
		}
		Session session = sessionFor(speaker, slot);
		if (session != null) {
			session.offer(audio48k.clone());
		}
	}

	/** The speaker stopped: settle their current sentence. */
	public static void end(UUID speaker) {
		Session session = SESSIONS.get(speaker);
		if (session != null) {
			session.offer(END_OF_SPEECH);
		}
	}

	private static Session sessionFor(UUID speaker, Slot slot) {
		Session existing = SESSIONS.get(speaker);
		if (existing != null) {
			if (existing.slot == slot) {
				return existing;
			}
			// Somebody's language was changed under them: their old recogniser is
			// holding half a sentence in the wrong language, and it can go.
			existing.close();
			SESSIONS.remove(speaker, existing);
		}
		int limit = VoiceSubtitlesConfig.get().maxRecognizers;
		if (SESSIONS.size() >= limit) {
			// Everyone else still gets a caption showing they are talking, just
			// without words – better than starving the recognisers that are
			// already running.
			return null;
		}
		Session session = new Session(speaker, slot);
		Session raced = SESSIONS.putIfAbsent(speaker, session);
		if (raced != null) {
			session.close();
			return raced;
		}
		return session;
	}

	/** One speaker's recogniser, queue and worker thread. */
	private static final class Session {

		private final UUID speaker;
		final Slot slot;
		private final Resampler resampler = new Resampler();
		private final BlockingQueue<short[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
		private final Thread worker;
		private volatile boolean closed;
		private volatile long lastAudioAt = System.currentTimeMillis();
		/** True while the recogniser holds audio that has not been settled yet. */
		private volatile boolean unsettled;
		/** The last settled sentence, to recognise it being reported twice. */
		private String lastSettled = "";

		/**
		 * Opened the first time there is audio and an engine to give it to.
		 *
		 * <p>Not in the constructor, because a session now starts before its model
		 * is necessarily up: somebody marked as speaking English says the first
		 * word, that is what starts the English model loading, and the word itself
		 * has to wait somewhere in the meantime.
		 */
		private SpeechEngine.Stream stream;

		/** Speech held back while the model is still loading, already at 16 kHz. */
		private final Deque<short[]> pending = new ArrayDeque<>();
		private int pendingSamples;

		/**
		 * The feed line this utterance is writing into.
		 *
		 * <p>Held rather than looked up, because the whole-utterance engines
		 * decode the same audio again and again and the wording moves between
		 * passes. Matching the line by its text meant a reworded sentence landed
		 * in a second caption next to the first.
		 */
		private SubtitleFeed.Line line;

		Session(UUID speaker, Slot slot) {
			this.speaker = speaker;
			this.slot = slot;
			this.worker = new Thread(this::run, "voice-subtitles-asr-" + speaker);
			this.worker.setDaemon(true);
			// Recognition is background work; the game comes first.
			this.worker.setPriority(Thread.MIN_PRIORITY);
			this.worker.start();
		}

		void offer(short[] frame) {
			lastAudioAt = System.currentTimeMillis();
			queue.offer(frame);
		}

		void close() {
			closed = true;
			worker.interrupt();
		}

		private void run() {
			try {
				while (!closed) {
					short[] frame = queue.poll(100L, TimeUnit.MILLISECONDS);
					if (frame == null) {
						long silence = System.currentTimeMillis() - lastAudioAt;
						if (unsettled && silence > SILENCE_SETTLE_MS) {
							settle();
						}
						if (silence > IDLE_TIMEOUT_MS) {
							break;
						}
						continue;
					}
					if (frame.length == 0) {
						settle();
						continue;
					}
					consume(resampler.process(frame));
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Throwable t) {
				VoiceSubtitles.LOGGER.error("Recogniser for {} stopped", speaker, t);
			} finally {
				// Do not walk off with the last words still inside.
				if (unsettled) {
					try {
						settle();
					} catch (Throwable ignored) {
					}
				}
				// Whatever happened, stop holding the line: a held line never
				// expires, and one held by a dead worker would sit there forever.
				SubtitleFeed.release(line);
				line = null;
				SESSIONS.remove(speaker, this);
				if (stream != null) {
					try {
						stream.close();
					} catch (Throwable ignored) {
					}
				}
			}
		}

		/**
		 * The engine's stream, opened once the model is up.
		 *
		 * @return null while there is still nothing to talk to
		 */
		private SpeechEngine.Stream stream() {
			if (stream != null) {
				return stream;
			}
			SpeechEngine engine = slot.engine;
			if (engine == null) {
				return null;
			}
			try {
				stream = engine.open();
			} catch (Throwable t) {
				VoiceSubtitles.LOGGER.error("Could not start a recogniser for {}", speaker, t);
				closed = true;
			}
			return stream;
		}

		private void consume(short[] audio16k) {
			SpeechEngine.Stream open = stream();
			if (open == null) {
				hold(audio16k);
				return;
			}
			drainPending(open);
			unsettled = true;
			String finished = open.accept(audio16k);
			if (finished != null) {
				// The engine found the end of an utterance by itself.
				unsettled = false;
				lastSettled = finished;
				if (finished.isBlank()) {
					SubtitleFeed.close(line);
					SubtitleFeed.release(line);
				} else {
					SubtitleFeed.Line target = lineToWriteTo();
					SubtitleFeed.setText(target, finished, true);
					Translation.request(target);
				}
				line = null;
			} else if (VoiceSubtitlesConfig.get().showPartial) {
				String partial = open.partial();
				if (!partial.isBlank()) {
					SubtitleFeed.setText(lineToWriteTo(), partial, false);
				}
			}
		}

		/** Keeps speech that arrived before the model did, oldest dropped first. */
		private void hold(short[] audio16k) {
			pending.addLast(audio16k);
			pendingSamples += audio16k.length;
			while (pendingSamples > PENDING_SAMPLES && !pending.isEmpty()) {
				pendingSamples -= pending.removeFirst().length;
			}
			// The line has to stay open, or the caption expires while its words
			// are still waiting for a model to arrive.
			SubtitleFeed.keepAlive(lineToWriteTo());
		}

		private void drainPending(SpeechEngine.Stream open) {
			if (pending.isEmpty()) {
				return;
			}
			unsettled = true;
			while (!pending.isEmpty()) {
				open.accept(pending.removeFirst());
			}
			pendingSamples = 0;
		}

		/**
		 * This utterance's line, adopting the one the feed opened for the audio.
		 *
		 * <p>Kept even after the feed has closed it, which is the whole point.
		 * An utterance ends after 650 ms of silence, but decoding it in full
		 * takes seconds for a long one, and the feed closes a line after 900 ms
		 * without audio. So by the time the finished sentence arrives its line is
		 * usually shut, and treating that as "no line" is what put the sentence
		 * in a second caption beside the running one. Writing to it reopens it.
		 */
		private SubtitleFeed.Line lineToWriteTo() {
			if (line == null) {
				line = SubtitleFeed.openOrCreate(speaker);
				line.spokenLanguage = slot.language;
			}
			return line;
		}

		/**
		 * Flushes whatever the recogniser is still holding and wipes its state,
		 * so the next sentence starts from nothing rather than inheriting the
		 * tail of this one.
		 */
		private void settle() {
			SpeechEngine.Stream open = stream();
			if (open == null) {
				// Still waiting for a model. Whatever is held stays held; it will
				// go in as soon as there is something to put it in.
				return;
			}
			drainPending(open);
			unsettled = false;
			String text = open.settle();
			resampler.reset();

			if (text.isBlank()) {
				SubtitleFeed.close(line);
				SubtitleFeed.release(line);
				line = null;
				return;
			}
			// A line of our own means this is still the same utterance, however
			// much the wording moved between passes: write it there. With no
			// line, the engine is handing back a sentence it already settled,
			// and repeating it would read as a duplicate.
			if (line == null && isSameAgain(lastSettled, text)) {
				lastSettled = text;
				return;
			}
			SubtitleFeed.Line target = lineToWriteTo();
			SubtitleFeed.setText(target, text, true);
			Translation.request(target);
			lastSettled = text;
			line = null;
		}

		/** True when one text is just the other carried a little further. */
		private static boolean isSameAgain(String previous, String next) {
			String a = previous.trim().toLowerCase();
			String b = next.trim().toLowerCase();
			return !a.isEmpty() && (b.startsWith(a) || a.startsWith(b));
		}
	}
}
