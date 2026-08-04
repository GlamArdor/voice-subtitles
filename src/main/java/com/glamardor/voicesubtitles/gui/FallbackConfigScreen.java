package com.glamardor.voicesubtitles.gui;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.speech.SpeechRecognition;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The settings screen used when Cloth Config is not installed. Plain vanilla
 * widgets, one scrolling list, same options as the Cloth version.
 */
public class FallbackConfigScreen extends Screen {

	private static final int ROW_WIDTH = 310;

	@Nullable
	private final Screen parent;
	private final VoiceSubtitlesConfig config = VoiceSubtitlesConfig.get();
	private OptionList list;

	public FallbackConfigScreen(@Nullable Screen parent) {
		super(Text.translatable("voice_subtitles.config.title"));
		this.parent = parent;
	}

	/**
	 * Leaves the world visible behind this screen.
	 *
	 * <p>A screen opened in game lays a dark gradient over the whole window,
	 * which is a different mechanism from the blur it applies over menus – and
	 * it would cover the captions these settings are adjusting.
	 */
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		// Deliberately not calling super: no panorama, no blur, no darkening.
	}

	@Override
	protected void init() {
		list = new OptionList(this.client, this.width, this.height - 96, 40, 25);

		list.addHeader(category("general"));
		list.addWidget(toggle("enabled", () -> config.enabled, v -> config.enabled = v));
		list.addWidget(cycle("mode", () -> valueName("mode", config.mode),
				() -> config.mode = next(VoiceSubtitlesConfig.Mode.values(), config.mode)));
		list.addWidget(cycle("voice_placement", () -> valueName("placement", config.voicePlacement),
				() -> config.voicePlacement = next(VoiceSubtitlesConfig.Placement.values(), config.voicePlacement)));
		list.addWidget(cycle("chat_placement", () -> valueName("placement", config.chatPlacement),
				() -> config.chatPlacement = next(VoiceSubtitlesConfig.Placement.values(), config.chatPlacement)));
		list.addWidget(toggle("show_self", () -> config.showSelf, v -> config.showSelf = v));
		list.addWidget(toggle("distance_fade", () -> config.distanceFade, v -> config.distanceFade = v));
		list.addWidget(percentSlider("far_opacity", config.farOpacity, 10, 100, v -> config.farOpacity = v));
		list.addWidget(percentSlider("whisper_opacity", config.whisperOpacity, 10, 100, v -> config.whisperOpacity = v));
		list.addWidget(toggle("chat_strip_prefix", () -> config.chatStripPrefix, v -> config.chatStripPrefix = v));

		list.addHeader(category("speech"));
		list.addWidget(toggle("speech_enabled", () -> config.speechEnabled, v -> config.speechEnabled = v));
		list.addWidget(textField("model_id", config.modelId, v -> config.modelId = v));
		list.addWidget(toggle("auto_download", () -> config.autoDownloadModel, v -> config.autoDownloadModel = v));
		list.addWidget(toggle("show_partial", () -> config.showPartial, v -> config.showPartial = v));
		list.addWidget(plainSlider("max_recognizers", config.maxRecognizers, 1, 8, v -> config.maxRecognizers = v));

		list.addHeader(category("names"));
		list.addWidget(cycle("name_source", () -> valueName("name_source", config.nameSource),
				() -> config.nameSource = next(VoiceSubtitlesConfig.NameSource.values(), config.nameSource)));
		list.addWidget(toggle("keep_server_style", () -> config.keepServerNameStyle,
				v -> config.keepServerNameStyle = v));
		list.addWidget(toggle("show_head", () -> config.showHead, v -> config.showHead = v));

		list.addHeader(category("colors"));
		list.addWidget(cycle("color_mode", () -> valueName("color_mode", config.colorMode),
				() -> config.colorMode = next(VoiceSubtitlesConfig.ColorMode.values(), config.colorMode)));
		list.addWidget(toggle("color_speech", () -> config.colorSpeech, v -> config.colorSpeech = v));
		list.addWidget(toggle("color_names", () -> config.colorNames, v -> config.colorNames = v));

		list.addHeader(category("layout"));
		list.addWidget(toggle("centered", () -> config.centered, v -> config.centered = v));
		list.addWidget(pixelSlider("bottom_offset", config.bottomOffset, 0, 200, v -> config.bottomOffset = v));
		list.addWidget(percentSlider("max_width", config.maxWidthPercent, 20, 100, v -> config.maxWidthPercent = v));
		list.addWidget(plainSlider("max_lines", config.maxLines, 1, 10, v -> config.maxLines = v));
		list.addWidget(secondsSlider("lifetime", config.lineLifetimeMs, 500, 15000, v -> config.lineLifetimeMs = v));
		list.addWidget(millisSlider("lifetime_per_char", config.lifetimePerCharMs, 0, 300,
				v -> config.lifetimePerCharMs = v));
		list.addWidget(secondsSlider("lifetime_max", config.maxLifetimeMs, 2000, 60000, v -> config.maxLifetimeMs = v));
		list.addWidget(percentSlider("background", config.backgroundOpacity, 0, 100, v -> config.backgroundOpacity = v));
		list.addWidget(percentSlider("text_scale", config.textScalePercent, 50, 200, v -> config.textScalePercent = v));
		list.addWidget(toggle("fade", () -> config.fade, v -> config.fade = v));
		list.addWidget(millisSlider("fade_millis", config.fadeMillis, 0, 1000, v -> config.fadeMillis = v));
		list.addWidget(toggle("typewriter", () -> config.typewriter, v -> config.typewriter = v));
		list.addWidget(plainSlider("typewriter_speed", config.typewriterSpeed, 10, 400,
				v -> config.typewriterSpeed = v));
		list.addWidget(toggle("hide_with_hud", () -> config.hideWithHud, v -> config.hideWithHud = v));
		list.addWidget(toggle("show_direction", () -> config.showDirection, v -> config.showDirection = v));

		list.addHeader(category("log"));
		list.addWidget(toggle("keep_log", () -> config.keepLog, v -> config.keepLog = v));
		list.addWidget(toggle("log_chat", () -> config.logChat, v -> config.logChat = v));
		list.addWidget(toggle("log_events", () -> config.logEvents, v -> config.logEvents = v));
		list.addWidget(plainSlider("log_capacity", config.logCapacity, 50, 20000, v -> config.logCapacity = v));
		list.addWidget(new OptionSlider("log_keep_days", config.logKeepDays, 0, 365,
				v -> config.logKeepDays = v,
				v -> v == 0 ? Text.translatable("voice_subtitles.unit.forever")
						: Text.translatable("voice_subtitles.unit.days", v)));
		list.addWidget(ButtonWidget.builder(Text.translatable("voice_subtitles.log.open"),
						button -> MinecraftClient.getInstance().setScreen(new SubtitleLogScreen(this)))
				.dimensions(0, 0, ROW_WIDTH, 20).build());

		list.addHeader(category("over_head"));
		list.addWidget(plainSlider("over_head_height", config.overHeadHeight, 0, 200, v -> config.overHeadHeight = v));
		list.addWidget(pixelSlider("over_head_width", config.overHeadWidth, 60, 400, v -> config.overHeadWidth = v));
		list.addWidget(toggle("over_head_walls", () -> config.overHeadThroughWalls,
				v -> config.overHeadThroughWalls = v));
		list.addWidget(percentSlider("over_head_scale", config.overHeadScalePercent, 50, 250,
				v -> config.overHeadScalePercent = v));
		list.addWidget(plainSlider("over_head_rows", config.overHeadMaxRows, 1, 10,
				v -> config.overHeadMaxRows = v));
		list.addWidget(percentSlider("over_head_background", config.overHeadBackground, 0, 100,
				v -> config.overHeadBackground = v));
		list.addWidget(toggle("over_head_name", () -> config.overHeadShowName,
				v -> config.overHeadShowName = v));

		addDrawableChild(list);

		ButtonWidget reset = ButtonWidget.builder(Text.translatable("voice_subtitles.config.reset"), button -> {
			config.resetToDefaults();
			SpeechRecognition.reload();
			// Rebuild so every widget shows the value it was just reset to.
			clearAndInit();
		}).dimensions(this.width / 2 - 154, this.height - 30, 150, 20).build();
		reset.setTooltip(Tooltip.of(Text.translatable("voice_subtitles.config.reset.tooltip")));
		addDrawableChild(reset);

		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
				.dimensions(this.width / 2 + 4, this.height - 30, 150, 20)
				.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFFFF);
		if (!ConfigScreenFactory.isClothPresent()) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("voice_subtitles.config.no_cloth"), this.width / 2, this.height - 44, 0xFF9A9A9A);
		}
	}

	@Override
	public void close() {
		VoiceSubtitlesConfig.save();
		SpeechRecognition.reload();
		MinecraftClient.getInstance().setScreen(parent);
	}

	// ── widgets ─────────────────────────────────────────────────────────────

	private static Text category(String name) {
		return Text.translatable("voice_subtitles.category." + name);
	}

	private static <T extends Enum<T>> Text valueName(String prefix, T value) {
		return Text.translatable("voice_subtitles.value." + prefix + "." + value.name().toLowerCase());
	}

	private static <T extends Enum<T>> T next(T[] values, T current) {
		return values[(current.ordinal() + 1) % values.length];
	}

	private ClickableWidget toggle(String key, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return cycle(key,
				() -> getter.get() ? ScreenTexts.ON : ScreenTexts.OFF,
				() -> setter.accept(!getter.get()));
	}

	private ClickableWidget cycle(String key, Supplier<Text> value, Runnable onClick) {
		ButtonWidget button = ButtonWidget.builder(label(key, value.get()), b -> {
			onClick.run();
			b.setMessage(label(key, value.get()));
		}).dimensions(0, 0, ROW_WIDTH, 20).build();
		button.setTooltip(Tooltip.of(Text.translatable("voice_subtitles.option." + key + ".tooltip")));
		return button;
	}

	/**
	 * A plain text box, for the one setting that is a word rather than a number.
	 *
	 * <p>Written back on every keystroke, like everything else on this screen:
	 * there is no save button here, and a field that only committed on enter
	 * would silently lose what was typed.
	 */
	private ClickableWidget textField(String key, String current, Consumer<String> setter) {
		TextFieldWidget field = new TextFieldWidget(this.textRenderer, 0, 0, ROW_WIDTH, 20,
				Text.translatable("voice_subtitles.option." + key));
		field.setMaxLength(64);
		field.setText(current);
		field.setChangedListener(setter);
		field.setTooltip(Tooltip.of(Text.translatable("voice_subtitles.option." + key + ".tooltip")));
		return field;
	}

	private ClickableWidget percentSlider(String key, int current, int min, int max, Consumer<Integer> setter) {
		return new OptionSlider(key, current, min, max, setter,
				value -> Text.translatable("voice_subtitles.unit.percent", value));
	}

	private ClickableWidget pixelSlider(String key, int current, int min, int max, Consumer<Integer> setter) {
		return new OptionSlider(key, current, min, max, setter,
				value -> Text.translatable("voice_subtitles.unit.pixels", value));
	}

	private ClickableWidget millisSlider(String key, int current, int min, int max, Consumer<Integer> setter) {
		return new OptionSlider(key, current, min, max, setter,
				value -> Text.translatable("voice_subtitles.unit.millis", value));
	}

	private ClickableWidget secondsSlider(String key, int current, int min, int max, Consumer<Integer> setter) {
		return new OptionSlider(key, current, min, max, setter,
				value -> Text.translatable("voice_subtitles.unit.seconds", String.format("%.1f", value / 1000F)));
	}

	private ClickableWidget plainSlider(String key, int current, int min, int max, Consumer<Integer> setter) {
		return new OptionSlider(key, current, min, max, setter, value -> Text.literal(String.valueOf(value)));
	}

	private static Text label(String key, Text value) {
		return Text.translatable("voice_subtitles.option." + key).append(": ").append(value);
	}

	/** Slider over an integer range that writes straight back into the config object. */
	private static class OptionSlider extends SliderWidget {

		private final String key;
		private final int min;
		private final int max;
		private final Consumer<Integer> setter;
		private final Function<Integer, Text> display;

		OptionSlider(String key, int current, int min, int max, Consumer<Integer> setter,
		             Function<Integer, Text> display) {
			super(0, 0, ROW_WIDTH, 20, Text.empty(),
					MathHelper.clamp((current - min) / (double) (max - min), 0.0, 1.0));
			this.key = key;
			this.min = min;
			this.max = max;
			this.setter = setter;
			this.display = display;
			setTooltip(Tooltip.of(Text.translatable("voice_subtitles.option." + key + ".tooltip")));
			updateMessage();
		}

		private int currentValue() {
			return (int) Math.round(min + (max - min) * this.value);
		}

		@Override
		protected void updateMessage() {
			setMessage(label(key, display.apply(currentValue())));
		}

		@Override
		protected void applyValue() {
			setter.accept(currentValue());
		}
	}

	private static class OptionList extends ElementListWidget<OptionList.Entry> {

		OptionList(MinecraftClient client, int width, int height, int y, int itemHeight) {
			super(client, width, height, y, itemHeight);
		}

		void addWidget(ClickableWidget widget) {
			addEntry(new WidgetEntry(widget));
		}

		void addHeader(Text text) {
			addEntry(new HeaderEntry(text));
		}

		@Override
		public int getRowWidth() {
			return ROW_WIDTH;
		}

		abstract static class Entry extends ElementListWidget.Entry<Entry> {
		}

		static class WidgetEntry extends Entry {

			private final ClickableWidget widget;

			WidgetEntry(ClickableWidget widget) {
				this.widget = widget;
			}

			@Override
			public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
			                   int mouseX, int mouseY, boolean hovered, float tickDelta) {
				widget.setX(x);
				widget.setY(y);
				widget.setWidth(entryWidth);
				widget.render(context, mouseX, mouseY, tickDelta);
			}

			@Override
			public List<? extends Element> children() {
				return List.of(widget);
			}

			@Override
			public List<? extends Selectable> selectableChildren() {
				return List.of(widget);
			}
		}

		static class HeaderEntry extends Entry {

			private final Text text;

			HeaderEntry(Text text) {
				this.text = text;
			}

			@Override
			public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
			                   int mouseX, int mouseY, boolean hovered, float tickDelta) {
				MinecraftClient client = MinecraftClient.getInstance();
				context.drawCenteredTextWithShadow(client.textRenderer, text,
						x + entryWidth / 2, y + 8, 0xFFE0C070);
			}

			@Override
			public List<? extends Element> children() {
				return List.of();
			}

			@Override
			public List<? extends Selectable> selectableChildren() {
				return List.of();
			}
		}
	}
}
