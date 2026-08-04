package com.glamardor.voicesubtitles.gui;

import com.glamardor.voicesubtitles.config.ConfigScreenMarker;
import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.speech.SpeechRecognition;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.BooleanToggleBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Cloth Config version of the settings screen. Loaded reflectively-by-classloading
 * only when Cloth is installed – never touch this class without checking first.
 */
final class ClothConfigScreens {

	/** Widgets to read back into the config every tick, so the captions follow the sliders. */
	private static final List<Runnable> LIVE = new ArrayList<>();

	private ClothConfigScreens() {
	}

	static Screen build(@Nullable Screen parent) {
		VoiceSubtitlesConfig config = VoiceSubtitlesConfig.get();
		VoiceSubtitlesConfig defaults = new VoiceSubtitlesConfig();
		LIVE.clear();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Text.translatable("voice_subtitles.config.title"))
				.setSavingRunnable(() -> {
					LivePreview.markSaved();
					VoiceSubtitlesConfig.save();
					// The model or the on/off switch may have changed.
					SpeechRecognition.reload();
				});

		// See the captions while setting them up: a solid menu background would
		// hide the very thing the sliders are adjusting.
		builder.setTransparentBackground(true);
		// One long list with the categories down the side, rather than tabs.
		// Tabbed, the search box only looks inside the tab you are standing in,
		// so finding a setting means knowing which tab it lives in first – which
		// is exactly what a search is for.
		builder.setGlobalized(true);
		builder.setGlobalizedExpanded(true);

		ConfigEntryBuilder entries = builder.entryBuilder();

		// ── General ─────────────────────────────────────────────────────────
		ConfigCategory general = category(builder, "general");
		general.addEntry(toggle(entries, "enabled", config.enabled, defaults.enabled, v -> config.enabled = v));
		general.addEntry(enumSelector(entries, "mode", VoiceSubtitlesConfig.Mode.class,
				config.mode, defaults.mode, v -> config.mode = v, "mode"));
		general.addEntry(enumSelector(entries, "voice_placement", VoiceSubtitlesConfig.Placement.class,
				config.voicePlacement, defaults.voicePlacement, v -> config.voicePlacement = v, "placement"));
		general.addEntry(enumSelector(entries, "chat_placement", VoiceSubtitlesConfig.Placement.class,
				config.chatPlacement, defaults.chatPlacement, v -> config.chatPlacement = v, "placement"));
		general.addEntry(toggle(entries, "show_self", config.showSelf, defaults.showSelf, v -> config.showSelf = v));
		general.addEntry(toggle(entries, "distance_fade", config.distanceFade, defaults.distanceFade,
				v -> config.distanceFade = v));
		general.addEntry(percent(entries, "far_opacity", config.farOpacity, defaults.farOpacity, 10, 100,
				v -> config.farOpacity = v));
		general.addEntry(percent(entries, "whisper_opacity", config.whisperOpacity, defaults.whisperOpacity, 10, 100,
				v -> config.whisperOpacity = v));
		general.addEntry(toggle(entries, "chat_strip_prefix", config.chatStripPrefix, defaults.chatStripPrefix,
				v -> config.chatStripPrefix = v));

		// ── Speech recognition ──────────────────────────────────────────────
		ConfigCategory speech = category(builder, "speech");
		speech.addEntry(toggle(entries, "speech_enabled", config.speechEnabled, defaults.speechEnabled,
				v -> config.speechEnabled = v));
		var modelEntry = entries.startStrField(option("model_id"), config.modelId)
				.setDefaultValue(defaults.modelId)
				.setTooltip(tooltip("model_id"))
				.setSaveConsumer(v -> config.modelId = v)
				.build();
		speech.addEntry(modelEntry);
		LIVE.add(() -> config.modelId = modelEntry.getValue());
		speech.addEntry(toggle(entries, "auto_download", config.autoDownloadModel, defaults.autoDownloadModel,
				v -> config.autoDownloadModel = v));
		speech.addEntry(toggle(entries, "show_partial", config.showPartial, defaults.showPartial,
				v -> config.showPartial = v));
		speech.addEntry(slider(entries, "max_recognizers", config.maxRecognizers, defaults.maxRecognizers, 1, 8,
				v -> config.maxRecognizers = v, v -> Text.literal(String.valueOf(v))));
		speech.addEntry(entries.startTextDescription(Text.translatable("voice_subtitles.option.model.hint")).build());

		// ── Names ───────────────────────────────────────────────────────────
		ConfigCategory names = category(builder, "names");
		names.addEntry(enumSelector(entries, "name_source", VoiceSubtitlesConfig.NameSource.class,
				config.nameSource, defaults.nameSource, v -> config.nameSource = v, "name_source"));
		names.addEntry(toggle(entries, "keep_server_style", config.keepServerNameStyle, defaults.keepServerNameStyle,
				v -> config.keepServerNameStyle = v));
		names.addEntry(toggle(entries, "show_head", config.showHead, defaults.showHead, v -> config.showHead = v));

		// ── Colours ─────────────────────────────────────────────────────────
		ConfigCategory colors = category(builder, "colors");
		colors.addEntry(enumSelector(entries, "color_mode", VoiceSubtitlesConfig.ColorMode.class,
				config.colorMode, defaults.colorMode, v -> config.colorMode = v, "color_mode"));
		colors.addEntry(toggle(entries, "color_speech", config.colorSpeech, defaults.colorSpeech,
				v -> config.colorSpeech = v));
		colors.addEntry(toggle(entries, "color_names", config.colorNames, defaults.colorNames,
				v -> config.colorNames = v));
		colors.addEntry(entries.startTextDescription(Text.translatable("voice_subtitles.option.manual_colors.hint")).build());

		// ── Layout ──────────────────────────────────────────────────────────
		ConfigCategory layout = category(builder, "layout");
		layout.addEntry(toggle(entries, "centered", config.centered, defaults.centered, v -> config.centered = v));
		layout.addEntry(slider(entries, "bottom_offset", config.bottomOffset, defaults.bottomOffset, 0, 200,
				v -> config.bottomOffset = v, ClothConfigScreens::pixels));
		layout.addEntry(percent(entries, "max_width", config.maxWidthPercent, defaults.maxWidthPercent, 20, 100,
				v -> config.maxWidthPercent = v));
		layout.addEntry(slider(entries, "max_lines", config.maxLines, defaults.maxLines, 1, 10,
				v -> config.maxLines = v, v -> Text.literal(String.valueOf(v))));
		layout.addEntry(slider(entries, "lifetime", config.lineLifetimeMs, defaults.lineLifetimeMs, 500, 15000,
				v -> config.lineLifetimeMs = v, ClothConfigScreens::seconds));
		layout.addEntry(slider(entries, "lifetime_per_char", config.lifetimePerCharMs, defaults.lifetimePerCharMs,
				0, 300, v -> config.lifetimePerCharMs = v, ClothConfigScreens::millis));
		layout.addEntry(slider(entries, "lifetime_max", config.maxLifetimeMs, defaults.maxLifetimeMs, 2000, 60000,
				v -> config.maxLifetimeMs = v, ClothConfigScreens::seconds));
		layout.addEntry(percent(entries, "background", config.backgroundOpacity, defaults.backgroundOpacity, 0, 100,
				v -> config.backgroundOpacity = v));
		layout.addEntry(percent(entries, "text_scale", config.textScalePercent, defaults.textScalePercent, 50, 200,
				v -> config.textScalePercent = v));
		layout.addEntry(toggle(entries, "fade", config.fade, defaults.fade, v -> config.fade = v));
		layout.addEntry(slider(entries, "fade_millis", config.fadeMillis, defaults.fadeMillis, 0, 1000,
				v -> config.fadeMillis = v, ClothConfigScreens::millis));
		layout.addEntry(toggle(entries, "typewriter", config.typewriter, defaults.typewriter,
				v -> config.typewriter = v));
		layout.addEntry(slider(entries, "typewriter_speed", config.typewriterSpeed, defaults.typewriterSpeed,
				10, 400, v -> config.typewriterSpeed = v, v -> Text.literal(String.valueOf(v))));
		layout.addEntry(toggle(entries, "hide_with_hud", config.hideWithHud, defaults.hideWithHud,
				v -> config.hideWithHud = v));
		layout.addEntry(toggle(entries, "show_direction", config.showDirection, defaults.showDirection,
				v -> config.showDirection = v));

		// ── Conversation log ────────────────────────────────────────────────
		ConfigCategory log = category(builder, "log");
		log.addEntry(toggle(entries, "keep_log", config.keepLog, defaults.keepLog, v -> config.keepLog = v));
		log.addEntry(toggle(entries, "log_chat", config.logChat, defaults.logChat, v -> config.logChat = v));
		log.addEntry(toggle(entries, "log_events", config.logEvents, defaults.logEvents, v -> config.logEvents = v));
		log.addEntry(slider(entries, "log_capacity", config.logCapacity, defaults.logCapacity, 50, 20000,
				v -> config.logCapacity = v, v -> Text.literal(String.valueOf(v))));
		log.addEntry(slider(entries, "log_keep_days", config.logKeepDays, defaults.logKeepDays, 0, 365,
				v -> config.logKeepDays = v, ClothConfigScreens::days));
		log.addEntry(entries.startTextDescription(Text.translatable("voice_subtitles.log.hint")).build());

		// ── Above the head ──────────────────────────────────────────────────
		ConfigCategory overHead = category(builder, "over_head");
		overHead.addEntry(slider(entries, "over_head_height", config.overHeadHeight, defaults.overHeadHeight, 0, 200,
				v -> config.overHeadHeight = v, v -> Text.literal(String.format("%.2f", v / 100.0))));
		overHead.addEntry(slider(entries, "over_head_width", config.overHeadWidth, defaults.overHeadWidth, 60, 400,
				v -> config.overHeadWidth = v, ClothConfigScreens::pixels));
		overHead.addEntry(toggle(entries, "over_head_walls", config.overHeadThroughWalls,
				defaults.overHeadThroughWalls, v -> config.overHeadThroughWalls = v));
		overHead.addEntry(percent(entries, "over_head_scale", config.overHeadScalePercent,
				defaults.overHeadScalePercent, 50, 250, v -> config.overHeadScalePercent = v));
		overHead.addEntry(slider(entries, "over_head_rows", config.overHeadMaxRows, defaults.overHeadMaxRows,
				1, 10, v -> config.overHeadMaxRows = v, v -> Text.literal(String.valueOf(v))));
		overHead.addEntry(percent(entries, "over_head_background", config.overHeadBackground,
				defaults.overHeadBackground, 0, 100, v -> config.overHeadBackground = v));
		overHead.addEntry(toggle(entries, "over_head_name", config.overHeadShowName, defaults.overHeadShowName,
				v -> config.overHeadShowName = v));

		Screen screen = ConfigScreenMarker.mark(builder.build());
		LivePreview.start(screen, LIVE);
		return screen;
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private static ConfigCategory category(ConfigBuilder builder, String name) {
		return builder.getOrCreateCategory(Text.translatable("voice_subtitles.category." + name));
	}

	private static Text option(String key) {
		return Text.translatable("voice_subtitles.option." + key);
	}

	private static Text tooltip(String key) {
		return Text.translatable("voice_subtitles.option." + key + ".tooltip");
	}

	private static Text percentText(int value) {
		return Text.translatable("voice_subtitles.unit.percent", value);
	}

	private static Text pixels(int value) {
		return Text.translatable("voice_subtitles.unit.pixels", value);
	}

	private static Text millis(int value) {
		return Text.translatable("voice_subtitles.unit.millis", value);
	}

	private static Text days(int value) {
		return value == 0
				? Text.translatable("voice_subtitles.unit.forever")
				: Text.translatable("voice_subtitles.unit.days", value);
	}

	private static Text seconds(int millis) {
		return Text.translatable("voice_subtitles.unit.seconds", String.format("%.1f", millis / 1000F));
	}

	private static me.shedaniel.clothconfig2.gui.entries.BooleanListEntry toggle(
			ConfigEntryBuilder entries, String key, boolean current, boolean fallback, Consumer<Boolean> setter) {
		BooleanToggleBuilder builder = entries.startBooleanToggle(option(key), current)
				.setDefaultValue(fallback)
				.setTooltip(tooltip(key))
				.setSaveConsumer(setter);
		var entry = builder.build();
		LIVE.add(() -> setter.accept(entry.getValue()));
		return entry;
	}

	private static me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry percent(
			ConfigEntryBuilder entries, String key, int current, int fallback, int min, int max,
			Consumer<Integer> setter) {
		return slider(entries, key, current, fallback, min, max, setter, ClothConfigScreens::percentText);
	}

	private static me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry slider(
			ConfigEntryBuilder entries, String key, int current, int fallback, int min, int max,
			Consumer<Integer> setter, Function<Integer, Text> display) {
		var entry = entries.startIntSlider(option(key), current, min, max)
				.setDefaultValue(fallback)
				.setTextGetter(display::apply)
				.setTooltip(tooltip(key))
				.setSaveConsumer(setter)
				.build();
		LIVE.add(() -> setter.accept(entry.getValue()));
		return entry;
	}

	private static <T extends Enum<T>> me.shedaniel.clothconfig2.gui.entries.EnumListEntry<T> enumSelector(
			ConfigEntryBuilder entries, String key, Class<T> type, T current, T fallback,
			Consumer<T> setter, String valuePrefix) {
		var entry = entries.startEnumSelector(option(key), type, current)
				.setDefaultValue(fallback)
				.setEnumNameProvider(v -> Text.translatable(
						"voice_subtitles.value." + valuePrefix + "." + v.name().toLowerCase()))
				.setTooltip(tooltip(key))
				.setSaveConsumer(setter)
				.build();
		LIVE.add(() -> setter.accept(entry.getValue()));
		return entry;
	}

	/** Kept for the odd place a supplier reads better than a lambda in the list above. */
	private static <T> Supplier<T> constant(T value) {
		return () -> value;
	}
}
