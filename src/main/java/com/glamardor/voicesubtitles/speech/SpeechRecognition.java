package com.glamardor.voicesubtitles.speech;

import com.glamardor.voicesubtitles.SubtitleFeed;
import com.glamardor.voicesubtitles.VoiceSubtitles;
import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Offline speech recognition for incoming voice.
 *
 * <p>One shared model, one recogniser per speaker. Audio arrives on the voice
 * chat's own thread and must never be held up there, so frames are dropped into
 * a bounded queue and a worker per speaker does the actual work: resample 48 kHz
 * down to the 16 kHz the engines want, feed it, and push whatever comes back
 * into the subtitle feed – partial results while the sentence is still forming,
 * a settled one when the utterance ends.
 *
 * <p>If the queue fills up, frames are dropped rather than queued forever. A
 * recogniser that has fallen that far behind will not catch up, and a caption
 * that arrives ten seconds late is worse than one that misses a word.
 *
 * <p>The engine itself lives behind {@link SpeechEngine}; which one runs follows
 * from the model that was chosen. Everything here is the same either way.
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

	private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

	private static volatile State state = State.DISABLED;
	private static volatile String failure = "";
	private static volatile SpeechEngine engine;

	/** Which model is loaded or on its way in, so a reload can skip the work. */
	private static volatile String currentModelId;
	/** When the current load started, for the progress the HUD shows. */
	private static volatile long loadStartedAt;

	private SpeechRecognition() {
	}

	public static State state() {
		return state;
	}

	public static String failure() {
		return failure;
	}

	public static int activeRecognizers() {
		return SESSIONS.size();
	}

	/** Loads the configured model in the background, fetching it first if needed. */
	public static void init() {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.speechEnabled) {
			state = State.DISABLED;
			return;
		}

		String id = resolveModelId(cfg);
		if (id == null) {
			// Too early to know the game language – whoever calls us again later
			// (the first client tick) will get a real answer.
			state = State.NO_MODEL;
			return;
		}
		SpeechModels.Entry entry = SpeechModels.byId(id);
		if (entry == null) {
			state = State.NO_MODEL;
			return;
		}
		if (!SpeechModels.isInstalled(entry)) {
			state = State.NO_MODEL;
			if (cfg.autoDownloadModel) {
				ModelDownload.startOnce(entry, SpeechRecognition::init);
			}
			return;
		}
		// A sherpa model is useless without the library that runs it, and that is
		// a separate download; ask for it before claiming to be loading.
		if (entry.engine() == SpeechModels.Engine.SHERPA && !nativesReady(cfg)) {
			return;
		}

		state = State.LOADING;
		currentModelId = entry.id();
		loadStartedAt = System.currentTimeMillis();
		Thread loader = new Thread(() -> {
			try {
				engine = entry.engine() == SpeechModels.Engine.SHERPA
						? SherpaEngine.load(entry)
						: VoskEngine.load(entry);
				state = State.READY;
				long took = System.currentTimeMillis() - loadStartedAt;
				rememberLoadTime(entry.id(), took);
				VoiceSubtitles.LOGGER.info("Speech model '{}' loaded in {} ms", entry.id(), took);
			} catch (Throwable t) {
				// Throwable, not Exception: a missing or unloadable native
				// library surfaces as UnsatisfiedLinkError, and that must not
				// take the game down with it.
				state = State.FAILED;
				failure = t.getClass().getSimpleName() + ": " + t.getMessage();
				VoiceSubtitles.LOGGER.error("Could not load the speech model", t);
			}
		}, "voice-subtitles-model-loader");
		loader.setDaemon(true);
		loader.start();
	}

	/**
	 * Makes sure the sherpa-onnx library is on disk and in the JVM.
	 *
	 * @return true when recognition can go ahead; otherwise the state has been
	 *         set to explain why and a download may have been started
	 */
	private static boolean nativesReady(VoiceSubtitlesConfig cfg) {
		SherpaNatives.Build build = SherpaNatives.buildForThisMachine();
		if (build == null) {
			state = State.FAILED;
			failure = "sherpa-onnx has no build for this platform; pick a Vosk model instead";
			return false;
		}
		if (!SherpaNatives.isInstalled(build)) {
			state = State.NO_MODEL;
			if (cfg.autoDownloadModel) {
				ModelDownload.startNativesOnce(build, SpeechRecognition::init);
			}
			return false;
		}
		if (!SherpaNatives.load()) {
			state = State.FAILED;
			failure = SherpaNatives.failure();
			return false;
		}
		return true;
	}

	/**
	 * The model to use: whatever is configured, or – on a fresh install – the
	 * one matching the game's own language, since that is very likely the
	 * language being spoken. Falls back to English for languages we have no
	 * small model for.
	 *
	 * @return null while the game options do not exist yet, which is the case
	 *         during mod initialisation – ask again once the client is up
	 */
	public static String resolveModelId(VoiceSubtitlesConfig cfg) {
		if (cfg.modelId != null && !cfg.modelId.isBlank()) {
			return cfg.modelId;
		}
		GameOptions options = MinecraftClient.getInstance().options;
		if (options == null) {
			return null;
		}
		String language = options.language; // e.g. "ru_ru"
		String code = language == null ? "en" : language.split("_")[0];
		String chosen = SpeechModels.byId(code) != null ? code : "en";

		cfg.modelId = chosen;
		VoiceSubtitlesConfig.save();
		VoiceSubtitles.LOGGER.info("No model configured; going with '{}' to match the game language", chosen);
		return chosen;
	}

	/**
	 * Picks up a settings change.
	 *
	 * <p>Reloading is not free: the large Russian model takes the better part of
	 * a minute to read back into memory, and closing the settings screen used to
	 * pay that price whether or not anything about recognition had changed. So
	 * the model is only torn down when it is genuinely a different model – moving
	 * a caption slider now costs nothing at all.
	 */
	public static void reload() {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.speechEnabled) {
			if (state != State.DISABLED) {
				shutdown();
			}
			return;
		}

		String wanted = resolveModelId(cfg);
		boolean alreadyOnIt = wanted != null && wanted.equals(currentModelId)
				&& (state == State.READY || state == State.LOADING);
		if (alreadyOnIt) {
			return;
		}
		shutdown();
		init();
	}

	/** Progress of the current load, 0–1, or -1 when nothing is loading. */
	public static float loadProgress() {
		if (state != State.LOADING) {
			return -1F;
		}
		long elapsed = System.currentTimeMillis() - loadStartedAt;
		Integer expected = VoiceSubtitlesConfig.get().modelLoadMillis.get(currentModelId);
		if (expected == null || expected <= 0) {
			// Never loaded this one before, so there is nothing to measure against.
			return -1F;
		}
		// Never quite full: the bar reaching the end while the game is still busy
		// reads as "stuck", which is worse than reading as "nearly there".
		return Math.min(0.97F, elapsed / (float) expected);
	}

	/** @return the model currently loading or loaded, or {@code null}. */
	public static String currentModelId() {
		return currentModelId;
	}

	/** Seconds the current load has been running. */
	public static long loadElapsedSeconds() {
		return state == State.LOADING ? (System.currentTimeMillis() - loadStartedAt) / 1000L : 0L;
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

	public static void shutdown() {
		for (Session session : SESSIONS.values()) {
			session.close();
		}
		SESSIONS.clear();
		SpeechEngine current = engine;
		engine = null;
		currentModelId = null;
		state = State.DISABLED;
		if (current != null) {
			try {
				current.close();
			} catch (Throwable ignored) {
			}
		}
	}

	/**
	 * Hands one 48 kHz frame to this speaker's recogniser. Called from the voice
	 * chat's audio thread; never blocks.
	 */
	public static void feed(UUID speaker, short[] audio48k) {
		if (state != State.READY || audio48k == null || audio48k.length == 0) {
			return;
		}
		Session session = sessionFor(speaker);
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

	private static Session sessionFor(UUID speaker) {
		Session existing = SESSIONS.get(speaker);
		if (existing != null) {
			return existing;
		}
		int limit = VoiceSubtitlesConfig.get().maxRecognizers;
		if (SESSIONS.size() >= limit) {
			// Everyone else still gets a caption showing they are talking, just
			// without words – better than starving the recognisers that are
			// already running.
			return null;
		}
		SpeechEngine current = engine;
		if (current == null) {
			return null;
		}
		try {
			SpeechEngine.Stream stream = current.open();
			if (stream == null) {
				return null;
			}
			Session session = new Session(speaker, stream);
			Session raced = SESSIONS.putIfAbsent(speaker, session);
			if (raced != null) {
				session.close();
				return raced;
			}
			return session;
		} catch (Throwable t) {
			VoiceSubtitles.LOGGER.error("Could not start a recogniser for {}", speaker, t);
			return null;
		}
	}

	/** One speaker's recogniser, queue and worker thread. */
	private static final class Session {

		private final UUID speaker;
		private final SpeechEngine.Stream stream;
		private final Resampler resampler = new Resampler();
		private final BlockingQueue<short[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
		private final Thread worker;
		private volatile boolean closed;
		private volatile long lastAudioAt = System.currentTimeMillis();
		/** True while the recogniser holds audio that has not been settled yet. */
		private volatile boolean unsettled;
		/** The last settled sentence, to recognise it being reported twice. */
		private String lastSettled = "";

		Session(UUID speaker, SpeechEngine.Stream stream) {
			this.speaker = speaker;
			this.stream = stream;
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
					consume(frame);
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
				SESSIONS.remove(speaker, this);
				try {
					stream.close();
				} catch (Throwable ignored) {
				}
			}
		}

		private void consume(short[] frame) {
			unsettled = true;
			String finished = stream.accept(resampler.process(frame));
			if (finished != null) {
				// The engine found the end of an utterance by itself.
				unsettled = false;
				lastSettled = finished;
				if (!finished.isBlank()) {
					SubtitleFeed.putText(speaker, finished, true);
				}
			} else if (VoiceSubtitlesConfig.get().showPartial) {
				String partial = stream.partial();
				if (!partial.isBlank()) {
					SubtitleFeed.putText(speaker, partial, false);
				}
			}
		}

		/**
		 * Flushes whatever the recogniser is still holding and wipes its state,
		 * so the next sentence starts from nothing rather than inheriting the
		 * tail of this one.
		 */
		private void settle() {
			unsettled = false;
			String text = stream.settle();
			resampler.reset();
			// Engines hand back the whole sentence again when they settle
			// something already reported; repeating it would read as a duplicate.
			if (!text.isBlank() && text.equals(lastSettled)) {
				SubtitleFeed.close(speaker);
				return;
			}
			lastSettled = text;
			if (!text.isBlank()) {
				SubtitleFeed.putText(speaker, text, true);
			} else {
				SubtitleFeed.close(speaker);
			}
		}
	}
}
