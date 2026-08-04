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
	/** Centre the feed horizontally, Story-Mode style. Off = left aligned. */
	public boolean centered = true;
	/** Distance from the bottom of the screen, in GUI pixels. */
	public int bottomOffset = 58;
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

	// ── Speech recognition ───────────────────────────────────────────────────
	/** Turn recognised words into captions; off leaves only "who is talking". */
	public boolean speechEnabled = true;
	/** Which model to use – see {@code /vsub model list}. Empty means "match the game language". */
	public String modelId = "";
	/** Fetch the model by itself the first time it is needed. */
	public boolean autoDownloadModel = true;
	/** Show words as they are recognised, before the sentence settles. */
	public boolean showPartial = true;
	/** How many people can be recognised at once before the rest get plain captions. */
	public int maxRecognizers = 3;
	/**
	 * How long each model took to load last time, in milliseconds.
	 *
	 * <p>Not a setting – a measurement. Vosk gives no progress while it reads a
	 * model, so the only honest progress bar is one drawn against how long the
	 * same model took on this machine before.
	 */
	public Map<String, Integer> modelLoadMillis = new LinkedHashMap<>();

	// ── Text chat ────────────────────────────────────────────────────────────
	/**
	 * Drop the "Name:" the server already printed in front of a chat message,
	 * since the subtitle prints the name itself.
	 */
	public boolean chatStripPrefix = true;

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

	/** Fills in anything a hand-edited or older config file left null/out of range. */
	private void sanitize() {
		if (mode == null) mode = Mode.VOICE;
		if (voicePlacement == null) voicePlacement = Placement.FEED;
		if (chatPlacement == null) chatPlacement = Placement.FEED;
		if (nameSource == null) nameSource = NameSource.TAB;
		if (colorMode == null) colorMode = ColorMode.BY_UUID;
		if (manualColors == null) manualColors = new LinkedHashMap<>();
		if (modelLoadMillis == null) modelLoadMillis = new LinkedHashMap<>();
		maxLines = clamp(maxLines, 1, 10);
		lineLifetimeMs = clamp(lineLifetimeMs, 500, 30000);
		lifetimePerCharMs = clamp(lifetimePerCharMs, 0, 300);
		maxLifetimeMs = clamp(maxLifetimeMs, lineLifetimeMs, 60000);
		backgroundOpacity = clamp(backgroundOpacity, 0, 100);
		maxWidthPercent = clamp(maxWidthPercent, 20, 100);
		bottomOffset = clamp(bottomOffset, 0, 200);
		textScalePercent = clamp(textScalePercent, 50, 200);
		farOpacity = clamp(farOpacity, 10, 100);
		whisperOpacity = clamp(whisperOpacity, 10, 100);
		overHeadHeight = clamp(overHeadHeight, 0, 200);
		overHeadWidth = clamp(overHeadWidth, 60, 400);
		maxRecognizers = clamp(maxRecognizers, 1, 8);
		logCapacity = clamp(logCapacity, 50, 20000);
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
