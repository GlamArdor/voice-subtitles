package com.glamardor.voicesubtitles.gui;

import com.glamardor.voicesubtitles.SpeakerColors;
import com.glamardor.voicesubtitles.SubtitleFeed;
import com.glamardor.voicesubtitles.SubtitleLog;
import com.glamardor.voicesubtitles.VoiceSubtitles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * What has been said so far, in one scrollable list.
 *
 * <p>Unlike the settings screen this one keeps the vanilla backdrop: it is here
 * to be read, and reading a wall of text over a moving world is unkind.
 */
public class SubtitleLogScreen extends Screen {

	private static final int ROW_WIDTH = 340;
	private static final int ROW_HEIGHT = 11;

	@Nullable
	private final Screen parent;
	private Text status = Text.empty();

	public SubtitleLogScreen(@Nullable Screen parent) {
		super(Text.translatable("voice_subtitles.log.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		LogList list = new LogList(this.client, this.width, this.height - 92, 44);
		for (SubtitleLog.Entry entry : SubtitleLog.entries()) {
			list.addEntry(entry);
		}
		list.scrollToBottom();
		addDrawableChild(list);

		int buttonWidth = 100;
		int spacing = 4;
		int totalWidth = buttonWidth * 3 + spacing * 2;
		int x = (this.width - totalWidth) / 2;
		int y = this.height - 30;

		addDrawableChild(ButtonWidget.builder(Text.translatable("voice_subtitles.log.export"), button -> export())
				.dimensions(x, y, buttonWidth, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("voice_subtitles.log.clear"), button -> {
			SubtitleLog.clear();
			status = Text.translatable("voice_subtitles.log.cleared").formatted(Formatting.GRAY);
			clearAndInit();
		}).dimensions(x + buttonWidth + spacing, y, buttonWidth, 20).build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
				.dimensions(x + (buttonWidth + spacing) * 2, y, buttonWidth, 20).build());
	}

	private void export() {
		if (SubtitleLog.size() == 0) {
			status = Text.translatable("voice_subtitles.log.empty").formatted(Formatting.GRAY);
			return;
		}
		try {
			Path file = SubtitleLog.export();
			status = Text.translatable("voice_subtitles.log.exported", file.getFileName().toString())
					.formatted(Formatting.GREEN);
		} catch (IOException e) {
			VoiceSubtitles.LOGGER.warn("could not export the conversation log", e);
			status = Text.translatable("voice_subtitles.log.export_failed").formatted(Formatting.RED);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 0xFFFFFFFF);
		// Said once, up front: half of this came out of a recogniser.
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("voice_subtitles.log.disclaimer"), this.width / 2, 30, 0xFF9A9A9A);
		if (!status.getString().isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, status, this.width / 2, this.height - 44, 0xFFFFFFFF);
		}
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}

	/**
	 * The log, one drawn row per wrapped text row.
	 *
	 * <p>Entries are pre-wrapped into single-height rows rather than given a row
	 * each: a list of fixed-height entries cannot hold a three-line sentence, and
	 * long sentences are exactly what this screen is for.
	 */
	private static class LogList extends ElementListWidget<LogList.Row> {

		LogList(MinecraftClient client, int width, int height, int y) {
			super(client, width, height, y, ROW_HEIGHT);
		}

		void addEntry(SubtitleLog.Entry entry) {
			MinecraftClient client = MinecraftClient.getInstance();
			int color = SpeakerColors.colorOf(entry.speaker());

			MutableText full = Text.literal(SubtitleLog.timeOf(entry) + " ").formatted(Formatting.DARK_GRAY)
					.append(Text.literal(entry.name()).styled(style -> style.withColor(color)))
					.append(Text.literal(entry.source() == SubtitleFeed.Source.CHAT ? " [chat]: " : ": ")
							.formatted(Formatting.DARK_GRAY))
					.append(Text.literal(entry.text()).formatted(Formatting.WHITE));

			for (OrderedText row : client.textRenderer.wrapLines(full, ROW_WIDTH)) {
				addEntry(new Row(row));
			}
		}

		void scrollToBottom() {
			setScrollY(getMaxScrollY());
		}

		@Override
		public int getRowWidth() {
			return ROW_WIDTH;
		}

		static class Row extends ElementListWidget.Entry<Row> {

			private final OrderedText text;

			Row(OrderedText text) {
				this.text = text;
			}

			@Override
			public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
			                   int mouseX, int mouseY, boolean hovered, float tickDelta) {
				context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, text, x, y + 1, 0xFFFFFFFF);
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
