package com.glamardor.voicesubtitles.translate;

import com.glamardor.voicesubtitles.NameResolver;
import com.glamardor.voicesubtitles.SubtitleFeed;
import com.glamardor.voicesubtitles.VoiceSubtitles;
import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Turning what was said into what you read.
 *
 * <p>Everything here runs on one background thread. Translation is not on the
 * path of anything: the caption is already on screen in the language it was
 * spoken in by the time this starts, and the translation is written underneath
 * it a moment later. If it never arrives – no model, a machine too slow, a
 * language pair nobody has built – the caption is simply the original, which is
 * what it would have been anyway.
 *
 * <p>Models are loaded the first time they are wanted and let go when nobody has
 * wanted them for a while, unless the settings say to keep them. On a Russian
 * server with one Englishman, that means a hundred clients carry the machinery
 * and never load it.
 */
public final class Translation {

	/** Sentences waiting to be translated; beyond this, the oldest are dropped. */
	private static final int QUEUE_LIMIT = 32;

	/** How many finished translations are remembered, keyed by what went in. */
	private static final int CACHE_SIZE = 512;

	/** A model nobody has used for this long is let go. */
	private static final long IDLE_UNLOAD_MS = 5L * 60_000L;

	/**
	 * What a name is replaced by while the sentence goes through the model.
	 *
	 * <p>Chosen by trying several. Anything the model has never seen – №1№, and
	 * the like – makes it give up and return nothing at all. A word like NAME1
	 * gets translated away. A bare Latin letter and a digit comes out the other
	 * side untouched, in both directions, which is the whole requirement.
	 */
	private static final String NAME_MARK = "X";

	private static final LinkedBlockingQueue<Job> QUEUE = new LinkedBlockingQueue<>();
	private static final Map<String, Loaded> MODELS = new ConcurrentHashMap<>();
	private static final Map<String, String> CACHE = java.util.Collections.synchronizedMap(
			new LinkedHashMap<>(64, 0.75F, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
					return size() > CACHE_SIZE;
				}
			});

	/**
	 * The names in the player list, refreshed on the client thread.
	 *
	 * <p>Kept as a copy rather than read where it is needed: the worker thread has
	 * no business walking a list the game is allowed to change underneath it.
	 */
	private static volatile List<String> names = List.of();

	private static volatile Thread worker;
	private static volatile String failure = "";

	private Translation() {
	}

	/**
	 * One sentence on its way through.
	 *
	 * @param sink given the translation when there is one and an empty string
	 *             when there is not – always exactly once, whatever happens, so
	 *             nothing is left waiting on a translation that failed
	 */
	private record Job(String text, TranslationModels.Entry model, java.util.function.Consumer<String> sink,
	                   int attempt) {
	}

	/** How many times a sentence waits for its model before giving up: six seconds. */
	private static final int LOAD_ATTEMPTS = 30;

	/** One direction, in memory. */
	private static final class Loaded {
		final TranslationModels.Entry entry;
		volatile MarianTranslator translator;
		volatile boolean loading;
		volatile long lastUsedAt = System.currentTimeMillis();

		Loaded(TranslationModels.Entry entry) {
			this.entry = entry;
		}
	}

	public static String failure() {
		return failure;
	}

	/** @return true when a model is in memory and ready to translate */
	public static boolean isReady() {
		for (Loaded loaded : MODELS.values()) {
			if (loaded.translator != null) {
				return true;
			}
		}
		return false;
	}

	// ── Asking for a translation ─────────────────────────────────────────────

	/**
	 * Translates a spoken line, if it needs it.
	 *
	 * <p>Which language it was spoken in is not guessed from the text: the line
	 * carries the language of the model that recognised it, and that is the only
	 * thing that knows.
	 */
	public static void request(SubtitleFeed.Line line) {
		if (line == null || line.text().isBlank()) {
			return;
		}
		into(line, line.text(), Languages.translationFor(line.spokenLanguage));
	}

	/**
	 * Translates a line of text chat, if it needs it.
	 *
	 * <p>Written text says what language it is in by its own alphabet, which
	 * speech never does, so chat needs nobody to be marked as anything.
	 */
	public static void requestChat(SubtitleFeed.Line line) {
		if (line == null || line.text().isBlank()) {
			return;
		}
		String spoken = languageOf(line.text());
		if (spoken == null) {
			return;
		}
		line.spokenLanguage = spoken;
		into(line, line.text(), Languages.translationFor(spoken));
	}

	/**
	 * Translates a piece of text that is not a caption, for whoever wants it.
	 *
	 * <p>Used by the conversation log, which files whole chat lines rather than
	 * the captions made out of them.
	 *
	 * @param whenDone given the translation, or an empty string when the text
	 *                 needs none. Called on this thread when the answer is known
	 *                 already, on the translator's thread otherwise, and exactly
	 *                 once either way.
	 */
	public static void ofText(String text, java.util.function.Consumer<String> whenDone) {
		if (text == null || text.isBlank()) {
			whenDone.accept("");
			return;
		}
		String spoken = languageOf(text);
		TranslationModels.Entry model = spoken == null ? null : Languages.translationFor(spoken);
		if (model == null) {
			whenDone.accept("");
			return;
		}
		submit(text, model, whenDone);
	}

	/** Sends a caption's text through, and writes the answer into the caption. */
	private static void into(SubtitleFeed.Line line, String text, TranslationModels.Entry model) {
		if (model == null) {
			return; // same language, no model for the pair, or translation turned off
		}
		line.translating = true;
		submit(text, model, translation -> {
			if (!translation.isEmpty()) {
				line.translation = translation;
			}
			line.translating = false;
		});
	}

	private static void submit(String text, TranslationModels.Entry model,
	                           java.util.function.Consumer<String> sink) {
		String cached = CACHE.get(key(model, text));
		if (cached != null) {
			sink.accept(cached);
			return;
		}
		start();
		while (QUEUE.size() >= QUEUE_LIMIT) {
			// A backlog means the machine cannot keep up; the oldest waiting
			// sentence is also the one most likely to be off screen already.
			Job dropped = QUEUE.poll();
			if (dropped == null) {
				break;
			}
			dropped.sink().accept("");
		}
		QUEUE.offer(new Job(text, model, sink, 0));
	}

	/**
	 * Whether this job's model is in memory, starting it if it is not.
	 *
	 * <p>Kicking off the load is a side effect on purpose: the first sentence in
	 * a language is what tells the mod that language is being spoken at all.
	 */
	private static boolean ready(Job job) {
		try {
			return translatorFor(job.model()) != null;
		} catch (Throwable t) {
			failure = t.getClass().getSimpleName() + ": " + t.getMessage();
			VoiceSubtitles.LOGGER.error("Could not load the {} translation", job.model().id(), t);
			return false;
		}
	}

	/**
	 * What language a piece of written text is in.
	 *
	 * <p>Player names are taken out before the alphabets are counted. On a
	 * roleplay server they are the one part of a sentence written in whatever
	 * alphabet the character's name happens to use, and an English line about
	 * Элиандрэль counted letter by letter comes out as neither language, which
	 * is exactly how "/me" and "/do" ended up untranslated.
	 */
	private static String languageOf(String text) {
		// Judged on exactly what the model would be given, marks and all. The
		// marks are a single letter each and count as no language, so what is
		// weighed is only what somebody actually said – which is the point: a
		// one-word "/try climb" reads as "[4] Имя climb... [Провал]", and until
		// the brackets came out of it too, that was one English word against one
		// Russian one and went untranslated.
		return Languages.foreignIn(protectNames(text).text());
	}

	// ── Running ──────────────────────────────────────────────────────────────

	private static synchronized void start() {
		if (worker != null) {
			return;
		}
		Thread thread = new Thread(Translation::run, "voice-subtitles-translator");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
		worker = thread;
	}

	private static void run() {
		while (true) {
			Job job;
			try {
				job = QUEUE.poll(1L, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			if (job == null) {
				continue;
			}
			// The model may still be coming up: on disk, not yet in memory, which
			// is where the first sentence of an evening lands. Wait for it rather
			// than throw the sentence away, but only while it is a matter of
			// seconds – a download takes minutes and nobody is reading by then.
			if (!ready(job)) {
				if (job.attempt() < LOAD_ATTEMPTS && TranslationModels.isInstalled(job.model())) {
					try {
						Thread.sleep(200L);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					QUEUE.offer(new Job(job.text(), job.model(), job.sink(), job.attempt() + 1));
				} else {
					job.sink().accept("");
				}
				continue;
			}
			String translated = "";
			try {
				translated = translate(job);
			} catch (Throwable t) {
				// A translation that fails leaves the original caption alone,
				// which is exactly what it would have been without any of this.
				failure = t.getClass().getSimpleName() + ": " + t.getMessage();
				VoiceSubtitles.LOGGER.error("Could not translate a line", t);
			}
			try {
				job.sink().accept(translated);
			} catch (Throwable t) {
				VoiceSubtitles.LOGGER.error("Could not deliver a translation", t);
			}
		}
	}

	/** @return the translation, or an empty string when there is none to be had */
	private static String translate(Job job) throws Exception {
		MarianTranslator translator = translatorFor(job.model());
		if (translator == null) {
			return "";
		}
		Protected guarded = protectNames(job.text());
		String translated = restoreNames(translator.translate(guarded.text()), guarded);
		if (translated.isBlank()) {
			return "";
		}
		CACHE.put(key(job.model(), job.text()), translated);
		return translated;
	}

	private static String key(TranslationModels.Entry model, String text) {
		return model.id() + ' ' + text;
	}

	/**
	 * The model for a direction, loading it if it is not in memory.
	 *
	 * @return null when it is not on disk, cannot be run, or is still coming up
	 */
	private static MarianTranslator translatorFor(TranslationModels.Entry entry) throws Exception {
		Loaded loaded = MODELS.computeIfAbsent(entry.id(), id -> new Loaded(entry));
		loaded.lastUsedAt = System.currentTimeMillis();
		if (loaded.translator != null) {
			return loaded.translator;
		}
		if (loaded.loading) {
			return null;
		}

		// The same protection the speech models get: opening one of these happens
		// inside a native library, and a native library that gives up takes the
		// game with it.
		String tag = "translate:" + entry.id();
		if (com.glamardor.voicesubtitles.speech.CrashGuard.isSuspect(tag)) {
			failure = "this translation took the game down last time; /vsub model repair fetches it again";
			return null;
		}
		if (!TranslationModels.isInstalled(entry)) {
			if (VoiceSubtitlesConfig.get().autoDownloadTranslation) {
				TranslationDownload.startOnce(entry);
			}
			return null;
		}
		if (!OrtNatives.load()) {
			failure = OrtNatives.failure();
			return null;
		}

		loaded.loading = true;
		try {
			long began = System.currentTimeMillis();
			com.glamardor.voicesubtitles.speech.CrashGuard.begin(tag);
			loaded.translator = MarianTranslator.load(TranslationModels.pathOf(entry));
			com.glamardor.voicesubtitles.speech.CrashGuard.end();
			VoiceSubtitles.LOGGER.info("Translation {} ready in {} ms",
					entry.id(), System.currentTimeMillis() - began);
			failure = "";
			return loaded.translator;
		} finally {
			com.glamardor.voicesubtitles.speech.CrashGuard.end();
			loaded.loading = false;
		}
	}

	// ── Names ────────────────────────────────────────────────────────────────

	/** A sentence with its names taken out, and the names to put back. */
	record Protected(String text, List<String> taken) {
	}

	/**
	 * The things a server puts in brackets: a channel, a rank, a roll, the actor
	 * of a roleplay action. None of it is speech and all of it is safer left
	 * exactly as it was – "[Провал]" came back from the model as "[10.51]".
	 */
	private static final java.util.regex.Pattern BRACKETED =
			java.util.regex.Pattern.compile("\\(\\([^()]{1,60}\\)\\)|\\[[^\\[\\]]{1,40}]");

	/**
	 * Hides player names from the model.
	 *
	 * <p>A translator has never seen "Элиандрэль фон Сильверхейм" and will do
	 * something regrettable with it: transliterate it into something else, decide
	 * it is three ordinary words, or drop it. Replacing each name with a mark it
	 * has no opinion about, and putting the name back afterwards, costs nothing
	 * and keeps the one word in the sentence that had to survive intact.
	 */
	static Protected protectNames(String text) {
		List<String> taken = new ArrayList<>();
		String out = text;

		// A sentence that already reads "X1" cannot be marked at all: its own X1
		// and ours would be indistinguishable coming back. Asked once, of the
		// original, since everything below adds marks of exactly that shape.
		if (text.matches(".*\\b[XxХх]\\d.*")) {
			return new Protected(text, taken);
		}

		// Brackets first: they often hold a name, and one mark over the lot is
		// tidier than a mark inside a mark.
		StringBuilder masked = new StringBuilder();
		java.util.regex.Matcher matcher = BRACKETED.matcher(out);
		while (matcher.find()) {
			taken.add(matcher.group());
			matcher.appendReplacement(masked, NAME_MARK + taken.size());
		}
		matcher.appendTail(masked);
		out = masked.toString();

		for (String name : names) {
			// Two letters is not a name, it is a word somebody used.
			if (name.length() < 3 || !out.contains(name)) {
				continue;
			}
			taken.add(name);
			out = out.replace(name, NAME_MARK + taken.size());
		}
		return new Protected(out, taken);
	}

	/**
	 * Puts the names back.
	 *
	 * <p>Later marks first, so X10 is not read as X1 with a nought after it. The
	 * Cyrillic Х is looked for as well as the Latin X: translating into Russian
	 * sometimes brings the mark back in the local alphabet, the two letters
	 * being indistinguishable on screen.
	 */
	static String restoreNames(String text, Protected guarded) {
		String out = text;
		for (int i = guarded.taken().size(); i >= 1; i--) {
			String name = guarded.taken().get(i - 1);
			for (String letter : new String[]{"X", "x", "Х", "х"}) {
				out = out.replace(letter + i, name);
			}
		}
		return out.trim();
	}

	// ── Housekeeping ─────────────────────────────────────────────────────────

	/**
	 * Called every client tick: refreshes the names and lets go of models nobody
	 * is using.
	 */
	public static void tick(MinecraftClient client) {
		refreshNames(client);

		if (VoiceSubtitlesConfig.get().translationMode == VoiceSubtitlesConfig.TranslationMode.ALWAYS) {
			return;
		}
		long now = System.currentTimeMillis();
		for (Loaded loaded : MODELS.values()) {
			if (loaded.translator != null && now - loaded.lastUsedAt > IDLE_UNLOAD_MS) {
				VoiceSubtitles.LOGGER.info("Letting go of the {} translation; nobody has needed it for a while",
						loaded.entry.id());
				MODELS.remove(loaded.entry.id(), loaded);
				loaded.translator.close();
			}
		}
	}

	private static int nameRefreshTicks;

	private static void refreshNames(MinecraftClient client) {
		// Twice a second is plenty: this is a tab list, not a stock ticker.
		if (++nameRefreshTicks < 10) {
			return;
		}
		nameRefreshTicks = 0;
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		if (handler == null) {
			names = List.of();
			return;
		}
		List<String> found = new ArrayList<>();
		for (PlayerListEntry entry : handler.getPlayerList()) {
			java.util.UUID id = entry.getProfile().id();
			add(found, entry.getProfile().name());
			// Through NameResolver rather than straight off the entry: a server
			// that leaves the display name empty and decorates the plain one
			// through a scoreboard team still shows a roleplay name in the tab,
			// and that is the name it prints in the chat. Reading only the
			// display name missed it, the name went into the translator
			// unprotected, and "Элиандрэль фон Сильверхейм" came back out as
			// "Элиандрэль-фон-Сильверхэйм-Эль-Эн-Эн-Эн".
			add(found, NameResolver.nameOf(id).getString());
			if (entry.getDisplayName() != null) {
				add(found, entry.getDisplayName().getString());
			}
		}
		// Longest first, so "Glam_Ardor" is taken before "Glam".
		found.sort((a, b) -> b.length() - a.length());
		names = List.copyOf(found);
	}

	/**
	 * Adds a name and, where a server put a rank in front of it, the name on its
	 * own as well: the tab may read "[4] Имя Фамилия" where the chat reads only
	 * "Имя Фамилия".
	 */
	private static void add(List<String> into, String name) {
		if (name == null) {
			return;
		}
		String cleaned = name.replaceAll("§[0-9A-Fa-fK-Ok-oRr]", "").trim();
		if (cleaned.length() >= 3 && !into.contains(cleaned)) {
			into.add(cleaned);
		}
		int closing = cleaned.indexOf(']');
		if (closing > 0 && closing < 10) {
			String bare = cleaned.substring(closing + 1).trim();
			if (bare.length() >= 3 && !into.contains(bare)) {
				into.add(bare);
			}
		}
	}

	/** Loads what will be needed, before anybody has said anything. */
	public static void warmUp() {
		if (!VoiceSubtitlesConfig.get().translationMode.translates()) {
			return;
		}
		for (String language : Languages.expected()) {
			TranslationModels.Entry entry = Languages.translationFor(language);
			if (entry == null) {
				continue;
			}
			start();
			Thread thread = new Thread(() -> {
				try {
					translatorFor(entry);
				} catch (Throwable t) {
					VoiceSubtitles.LOGGER.error("Could not load the {} translation", entry.id(), t);
				}
			}, "voice-subtitles-translation-loader");
			thread.setDaemon(true);
			thread.start();
		}
	}

	/** Drops everything that the settings no longer call for. */
	public static void reload() {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.translationMode.translates()) {
			for (Loaded loaded : MODELS.values()) {
				MODELS.remove(loaded.entry.id(), loaded);
				if (loaded.translator != null) {
					loaded.translator.close();
				}
			}
			QUEUE.clear();
			CACHE.clear();
			return;
		}
		warmUp();
	}
}
