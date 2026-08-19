package com.glamardor.voicesubtitles.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Plain settings holder, persisted as JSON in the game's config directory.
 * Deliberately free of any Cloth Config / Mod Menu types so the mod keeps
 * working even if those mods are not installed (the config screen is optional).
 */
public class VoiceSubtitlesConfig {

	/** Where a kind of subtitle is drawn. Set separately for voice and for chat. */
	public enum Placement {
		/** Story-Mode style feed at the bottom of the screen (default for both). */
		FEED,
		/** Floating above the speaker's head in the world. */
		OVER_HEAD,
		/** Both at once. */
		BOTH,
		/**
		 * Nowhere on screen, but still written to the conversation log.
		 *
		 * <p>For somebody who wants the record without the captions: text chat
		 * is already readable in the chat window, and repeating it in the feed
		 * is clutter, yet it still belongs in the log beside the speech.
		 */
		LOG_ONLY;

		public boolean inFeed() {
			return this == FEED || this == BOTH;
		}

		public boolean overHead() {
			return this == OVER_HEAD || this == BOTH;
		}
	}

	/** What gets turned into subtitles. */
	public enum Mode {
		/** Voice chat only (default). */
		VOICE,
		/** Voice chat plus text chat. */
		VOICE_AND_CHAT,
		/** Text chat only. */
		CHAT
	}

	/** Which name is printed in front of the speech. */
	public enum NameSource {
		/** Whatever the server shows in the player list – the roleplay name. */
		TAB,
		/** The plain Mojang username. */
		USERNAME
	}

	/**
	 * How much the mod does about people speaking a language you do not read.
	 *
	 * <p>The order is deliberate: each state does everything the one before it
	 * does, and one thing more.
	 */
	public enum TranslationMode {
		/**
		 * Nothing. Everybody goes through your own model, which is what a Russian
		 * model does to English speech: writes it out in Cyrillic, phonetically,
		 * and it reads as nonsense.
		 */
		OFF,
		/**
		 * A second recognition model for the people marked as speaking something
		 * else, so at least their words come out in their own alphabet. No
		 * translation, and no translation model to download.
		 */
		RECOGNIZE_ONLY,
		/**
		 * Recognise and translate, loading both the moment one of those people is
		 * heard and letting them go again once they have been quiet for a while.
		 * For a server where they are the exception.
		 */
		AUTO,
		/**
		 * The same, kept loaded from the moment you join. For somebody who is the
		 * exception themselves and hears another language all evening.
		 */
		ALWAYS;

		/** @return true when speech in another language gets its own model */
		public boolean routes() {
			return this != OFF;
		}

		/** @return true when what that model produces is then translated */
		public boolean translates() {
			return this == AUTO || this == ALWAYS;
		}
	}

	/** Which side of the screen the feed sits on. */
	public enum Alignment {
		LEFT,
		/** Centred, Story-Mode style. */
		CENTER,
		RIGHT
	}

	/** Which edge of the screen the feed is anchored to. */
	public enum Anchor {
		/** Above the hotbar, where subtitles belong (default). */
		BOTTOM,
		/** Along the top, out of the way of everything the bottom of the screen is already full of. */
		TOP
	}

	/** How a speaker's text is coloured. */
	public enum ColorMode {
		/** Everybody white (default). */
		WHITE,
		/** A stable colour derived from the player's UUID – identical on every client. */
		BY_UUID,
		/** Colours assigned by hand, falling back to white. */
		MANUAL
	}

	/**
	 * Config format version, for migrations.
	 *
	 * <p>Deliberately 0 rather than the current version: Gson runs the field
	 * initialisers and then overwrites only what the file actually contains, so
	 * a default of "current" would mark exactly the old files that need
	 * migrating as already up to date.
	 */
	public int configVersion = 0;

	// ── General ──────────────────────────────────────────────────────────────
	public boolean enabled = true;
	public Mode mode = Mode.VOICE;
	/** Where spoken subtitles go. */
	public Placement voicePlacement = Placement.FEED;
	/** Where chat subtitles go – independent of voice, so chat can stay out of the world. */
	public Placement chatPlacement = Placement.FEED;
	/** Also subtitle your own voice. */
	public boolean showSelf = true;

	// ── Naming ───────────────────────────────────────────────────────────────
	public NameSource nameSource = NameSource.TAB;
	/** Keep the colours/formatting the server put on the tab name. */
	public boolean keepServerNameStyle = true;
	/** Draw the speaker's 8×8 head in front of the name. */
	public boolean showHead = true;

	// ── Colours ──────────────────────────────────────────────────────────────
	public ColorMode colorMode = ColorMode.BY_UUID;
	/** Colour the speech itself. */
	public boolean colorSpeech = true;
	/**
	 * Colour the name too. Off by default: names carry the server's own
	 * formatting, and leaving that alone looks tidier next to coloured speech.
	 */
	public boolean colorNames = false;
	/** UUID (string) → #RRGGBB, used by {@link ColorMode#MANUAL}. */
	public Map<String, String> manualColors = new LinkedHashMap<>();

	// ── Feed layout ──────────────────────────────────────────────────────────
	/**
	 * Kept only to carry old settings across: it said centred or left, and there
	 * are three sides now. Read once, into {@link #feedAlignment}.
	 */
	public boolean centered = true;
	/**
	 * Which side of the screen the feed sits on.
	 *
	 * <p>Given a value here rather than left to be filled in later, because the
	 * settings screen builds its widgets from a fresh instance of this class to
	 * know what the defaults are, and that instance never goes through
	 * {@link #sanitize()}. Left null, it took the Cloth Config screen down with
	 * it every time it was opened.
	 */
	public Alignment feedAlignment = Alignment.CENTER;
	/**
	 * Which edge of the screen the feed hangs from.
	 *
	 * <p>Given a value here for the same reason {@link #feedAlignment} is: the settings screen reads
	 * its defaults off a fresh instance, which never goes through {@link #sanitize()}.
	 */
	public Anchor feedAnchor = Anchor.BOTTOM;
	/** Distance from the bottom of the screen, in GUI pixels. Used when anchored to the bottom. */
	public int bottomOffset = 58;
	/**
	 * Distance from the top of the screen, in GUI pixels. Used when anchored to the top.
	 *
	 * <p>Its own setting rather than the one above: the bottom of the screen has a hotbar to clear
	 * and the top has nothing, so the two want quite different numbers and a player who moves the
	 * feed from one to the other should not have to set the distance again each time.
	 */
	public int topOffset = 12;
	/** Maximum feed width, as a percentage of the screen width. */
	public int maxWidthPercent = 62;
	/** How many speech lines are visible at once. */
	public int maxLines = 4;
	/**
	 * How long a finished line stays on screen before its length is taken into
	 * account, in milliseconds – the reading time a bare "привет" gets.
	 */
	public int lineLifetimeMs = 2200;
	/** Extra time per character, so a long sentence stays long enough to read. */
	public int lifetimePerCharMs = 42;
	/** Upper bound, however long the sentence is. */
	public int maxLifetimeMs = 11000;
	/** Darkness of the plate behind the text, 0–100 %. */
	public int backgroundOpacity = 40;
	/** Fade lines in and out instead of popping them. */
	public boolean fade = true;
	/**
	 * Hide the subtitles along with the rest of the interface on F1. Off by
	 * default: someone who reads the conversation instead of hearing it should
	 * not lose it the moment they hide the HUD for a screenshot.
	 */
	public boolean hideWithHud = false;
	/** Text scale in percent (100 = vanilla font size). */
	public int textScalePercent = 100;
	/**
	 * Draw a small arrow showing where the speaker is standing relative to where
	 * you are looking. Subtitles carry the words but not where the voice came
	 * from, which is exactly what somebody reading instead of listening loses –
	 * but it is an extra mark in front of every line, so it is off by default.
	 */
	public boolean showDirection = false;

	// ── Conversation log ─────────────────────────────────────────────────────
	/** Keep what has been said in memory, so it can be read back and exported. */
	public boolean keepLog = true;
	/** How many lines the log holds before the oldest ones are dropped. */
	public int logCapacity = 2000;
	/**
	 * Note the comings and goings in the log: joining and leaving a server,
	 * deaths, closing the game.
	 *
	 * <p>Without them a log is a wall of speech with no idea where or when any
	 * of it happened. With them, a line has a place: this was said on that
	 * server, that evening, before somebody died.
	 */
	public boolean logEvents = true;
	/**
	 * Put the whole chat in the log, not only what a player can be pinned to.
	 *
	 * <p>Server announcements, other people's commands, the results of roleplay
	 * actions: in a record, the things nobody said are as much part of the
	 * evening as the things somebody did.
	 */
	public boolean logChat = true;
	/**
	 * How many days of conversation are kept on disk.
	 *
	 * <p>The log is written out as it happens so it survives closing the game,
	 * which is the only way it is any use: what somebody wants to look up is
	 * what was said a while ago, and "a while" routinely spans a restart.
	 */
	public int logKeepDays = 14;

	// ── Distance ─────────────────────────────────────────────────────────────
	/**
	 * Fade subtitles out with distance, so a barely audible voice reads as a
	 * barely visible caption. Nothing is ever hidden outright – if the client
	 * hears it, it is subtitled.
	 */
	public boolean distanceFade = true;
	/** Opacity at the very edge of a speaker's range, 10–100 %. */
	public int farOpacity = 35;
	/** Opacity multiplier applied to whispers, 10–100 %. */
	public int whisperOpacity = 70;

	// ── Above the head ───────────────────────────────────────────────────────
	/** Height above the speaker's head, in hundredths of a block. */
	public int overHeadHeight = 60;
	/** Maximum caption width above a head, in text pixels. */
	public int overHeadWidth = 170;
	/** Draw the caption through walls, like the voice it belongs to. */
	public boolean overHeadThroughWalls = true;
	/** Text size above a head, in percent of the usual. */
	public int overHeadScalePercent = 100;
	/** How many wrapped rows a caption above a head may take. */
	public int overHeadMaxRows = 4;
	/** Darkness of the plate behind it, 0–100 %. */
	public int overHeadBackground = 25;
	/** Show the speaker's name above their head as well as their words. */
	public boolean overHeadShowName = true;

	// ── Motion ───────────────────────────────────────────────────────────────
	/**
	 * How long a caption takes to fade in and out, in milliseconds.
	 *
	 * <p>Captions used to appear and vanish between one frame and the next, which
	 * reads as a flicker at the edge of vision rather than as something arriving.
	 *
	 * <p>Fading out takes two and a half times as long again, so this is a full
	 * second of leaving. Two hundred milliseconds was the first guess and it read
	 * as a snap rather than a fade; at four hundred a caption is seen to arrive.
	 */
	public int fadeMillis = 400;
	/**
	 * Let recognised text appear a few characters at a time rather than in whole
	 * jumps, so a caption grows the way speech does.
	 */
	public boolean typewriter = true;
	/**
	 * How fast those characters appear, per second.
	 *
	 * <p>Fast enough that it never delays the words: an ordinary sentence is
	 * whole in about a second, well inside the several seconds it then stays on
	 * screen. Ninety was too quick to see, which rather defeated the point.
	 */
	public int typewriterSpeed = 55;

	// ── Speech recognition ───────────────────────────────────────────────────
	/** Turn recognised words into captions; off leaves only "who is talking". */
	public boolean speechEnabled = true;
	/** Which model to use – see {@code /vsub model list}. Empty means "match the game language". */
	public String modelId = "";
	/** Fetch the model by itself the first time it is needed. */
	public boolean autoDownloadModel = true;
	/** Show words as they are recognised, before the sentence settles. */
	public boolean showPartial = true;
	/**
	 * How many people can be recognised at once before the rest get plain
	 * captions.
	 *
	 * <p>Each one is a thread and a decoder of its own, so this is the setting
	 * that decides how much of the machine the mod may take when a whole square
	 * is talking. Five by default, up to thirty-two for a crowd and a machine
	 * that can carry it.
	 */
	public int maxRecognizers = 5;
	/**
	 * How long each model took to load last time, in milliseconds.
	 *
	 * <p>Not a setting – a measurement. Vosk gives no progress while it reads a
	 * model, so the only honest progress bar is one drawn against how long the
	 * same model took on this machine before.
	 */
	public Map<String, Integer> modelLoadMillis = new LinkedHashMap<>();

	// ── Translation ──────────────────────────────────────────────────────────
	/** How much is done about people speaking a language you do not read. */
	public TranslationMode translationMode = TranslationMode.OFF;
	/** The language you read. Empty means "the language the game is in". */
	public String myLanguage = "";
	/**
	 * What everybody here is assumed to be speaking. Empty means "the language you
	 * read", which is right for anybody playing on a server in their own language.
	 *
	 * <p>The one setting that makes the exception list work from both sides. On a
	 * Russian server the Russians leave this alone and mark the one Englishman;
	 * the Englishman leaves it as Russian and marks nobody, and everything he
	 * hears is translated for him.
	 */
	public String defaultSpokenLanguage = "";
	/** UUID (string) → language code, for the people who speak something else. */
	public Map<String, String> speakerLanguages = new LinkedHashMap<>();
	/**
	 * Show what was actually said under the translation.
	 *
	 * <p>On by default: on a roleplay server it matters that somebody said a thing
	 * in a particular way, and a translation is a paraphrase of a transcript.
	 */
	public boolean showOriginal = true;
	/** Fetch a translation model by itself the first time one is needed. */
	public boolean autoDownloadTranslation = true;
	/**
	 * Write the translation of a chat line into the chat, under the line itself.
	 *
	 * <p>On by default, because the feed cannot be relied on to carry it. A
	 * caption is only made for a message that can be pinned to a player, and the
	 * roleplay commands come through as system messages that many servers send
	 * without saying who wrote them. In the chat the translation always lands
	 * somewhere, and it lands where anybody would look.
	 */
	public boolean translateChat = true;

	// ── Text chat ────────────────────────────────────────────────────────────
	/**
	 * Drop the "Name:" the server already printed in front of a chat message,
	 * since the subtitle prints the name itself.
	 */
	public boolean chatStripPrefix = true;
	/**
	 * Keep the server's answer to your own roleplay command out of the feed.
	 *
	 * <p>A dice roll is not speech. It is in the chat window where it was asked
	 * for and in the log where it belongs, and repeating it as a caption over
	 * the world is noise.
	 */
	public boolean hideCommandResults = true;

	// ─────────────────────────────────────────────────────────────────────────

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve("voice_subtitles.json");

	private static VoiceSubtitlesConfig instance;

	public static VoiceSubtitlesConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	public static void load() {
		VoiceSubtitlesConfig loaded = null;
		try {
			if (Files.exists(PATH)) {
				try (Reader reader = Files.newBufferedReader(PATH)) {
					loaded = GSON.fromJson(reader, VoiceSubtitlesConfig.class);
				}
			}
		} catch (Exception e) {
			loaded = null;
		}
		instance = loaded != null ? loaded : new VoiceSubtitlesConfig();
		instance.sanitize();
		save();
	}

	public static void save() {
		if (instance == null) {
			instance = new VoiceSubtitlesConfig();
		}
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(instance, writer);
			}
		} catch (Exception ignored) {
		}
	}

	/** The settings version this build writes; see {@link #migrate()}. */
	private static final int CURRENT_VERSION = 2;

	/**
	 * Brings an older settings file up to date.
	 *
	 * <p>Only values still sitting on a default that has since changed are
	 * touched. Somebody who chose a number is left with the number they chose,
	 * and somebody who never opened the settings gets the timings this version
	 * thinks are right rather than the ones the first version guessed.
	 */
	private void migrate() {
		if (configVersion >= CURRENT_VERSION) {
			return;
		}
		if (configVersion < 1) {
			// Both of these read as a snap rather than a fade, which is the whole
			// thing they were added for.
			if (fadeMillis == 220) fadeMillis = 400;
			if (typewriterSpeed == 90) typewriterSpeed = 55;
		}
		if (configVersion < 2) {
			// Three was chosen when a crowded square was a guess rather than a
			// thing that had happened.
			if (maxRecognizers == 3) maxRecognizers = 5;
			// Written before there were three sides to choose from, so it says
			// what the old two-way switch said.
			if (!centered) feedAlignment = Alignment.LEFT;
		}
		configVersion = CURRENT_VERSION;
	}

	/** Fills in anything a hand-edited or older config file left null/out of range. */
	private void sanitize() {
		migrate();
		if (mode == null) mode = Mode.VOICE;
		if (voicePlacement == null) voicePlacement = Placement.FEED;
		if (chatPlacement == null) chatPlacement = Placement.FEED;
		if (nameSource == null) nameSource = NameSource.TAB;
		if (colorMode == null) colorMode = ColorMode.BY_UUID;
		if (manualColors == null) manualColors = new LinkedHashMap<>();
		if (modelLoadMillis == null) modelLoadMillis = new LinkedHashMap<>();
		if (translationMode == null) translationMode = TranslationMode.OFF;
		if (feedAlignment == null) feedAlignment = Alignment.CENTER;
		if (feedAnchor == null) feedAnchor = Anchor.BOTTOM;
		if (speakerLanguages == null) speakerLanguages = new LinkedHashMap<>();
		if (myLanguage == null) myLanguage = "";
		if (defaultSpokenLanguage == null) defaultSpokenLanguage = "";
		maxLines = clamp(maxLines, 1, 32);
		lineLifetimeMs = clamp(lineLifetimeMs, 500, 30000);
		lifetimePerCharMs = clamp(lifetimePerCharMs, 0, 300);
		maxLifetimeMs = clamp(maxLifetimeMs, lineLifetimeMs, 60000);
		backgroundOpacity = clamp(backgroundOpacity, 0, 100);
		maxWidthPercent = clamp(maxWidthPercent, 20, 100);
		bottomOffset = clamp(bottomOffset, 0, 200);
		topOffset = clamp(topOffset, 0, 200);
		textScalePercent = clamp(textScalePercent, 50, 200);
		farOpacity = clamp(farOpacity, 10, 100);
		whisperOpacity = clamp(whisperOpacity, 10, 100);
		overHeadHeight = clamp(overHeadHeight, 0, 200);
		overHeadWidth = clamp(overHeadWidth, 60, 400);
		maxRecognizers = clamp(maxRecognizers, 1, 32);
		logCapacity = clamp(logCapacity, 50, 20000);
		overHeadScalePercent = clamp(overHeadScalePercent, 50, 250);
		overHeadMaxRows = clamp(overHeadMaxRows, 1, 10);
		overHeadBackground = clamp(overHeadBackground, 0, 100);
		fadeMillis = clamp(fadeMillis, 0, 1000);
		typewriterSpeed = clamp(typewriterSpeed, 10, 400);
		logKeepDays = clamp(logKeepDays, 0, 365);
		if (modelId == null) modelId = "";
	}

	/** @return true when voice chat should be subtitled. */
	public boolean voiceEnabled() {
		return mode != Mode.CHAT;
	}

	/** @return true when text chat should be subtitled. */
	public boolean chatEnabled() {
		return mode != Mode.VOICE;
	}

	/** @return where subtitles of this kind belong. */
	public Placement placementOf(boolean fromChat) {
		return fromChat ? chatPlacement : voicePlacement;
	}

	private static int clamp(int v, int min, int max) {
		return v < min ? min : (v > max ? max : v);
	}

	/** Puts every setting back to its default, in place. */
	public void resetToDefaults() {
		// Load times survive a reset: they are something this machine measured,
		// not something the user chose, and throwing them away would only mean
		// the next progress bar has nothing to draw against.
		Map<String, Integer> measured = new LinkedHashMap<>(modelLoadMillis);
		copyInto(new VoiceSubtitlesConfig(), this);
		modelLoadMillis = measured;
		save();
	}

	/** Copies every public field across, duplicating collections rather than sharing them. */
	public static void copyInto(VoiceSubtitlesConfig from, VoiceSubtitlesConfig to) {
		for (java.lang.reflect.Field field : VoiceSubtitlesConfig.class.getFields()) {
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			try {
				Object value = field.get(from);
				if (value instanceof Map<?, ?> map) {
					value = new LinkedHashMap<>(map);
				}
				field.set(to, value);
			} catch (IllegalAccessException ignored) {
			}
		}
	}

	/** @return a detached copy, for previewing edits that can still be discarded. */
	public VoiceSubtitlesConfig copy() {
		VoiceSubtitlesConfig copy = new VoiceSubtitlesConfig();
		copyInto(this, copy);
		return copy;
	}

	/** @return the hand-assigned colour for this player, or {@code null} if there is none. */
	public Integer manualColorOf(UUID id) {
		String hex = manualColors.get(id.toString());
		if (hex == null) {
			return null;
		}
		try {
			return Integer.parseInt(hex.replace("#", "").trim(), 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public void setManualColor(UUID id, Integer rgb) {
		if (rgb == null) {
			manualColors.remove(id.toString());
		} else {
			manualColors.put(id.toString(), String.format("#%06X", rgb & 0xFFFFFF));
		}
		save();
	}
}
