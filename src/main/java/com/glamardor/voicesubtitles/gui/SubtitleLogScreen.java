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
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What has been said so far, with a way to find any of it again.
 *
 * <p>Scrolling is fine for the last few minutes and useless for an evening of
 * roleplay, so the list is filtered rather than merely shown: text to look for
 * along the top, and everything else down the right side. The filters are all
 * on one screen and always visible, because a filter you have to go looking for
 * is a filter nobody uses.
 *
 * <p>Unlike the settings screen this one keeps the vanilla backdrop: it is here
 * to be read, and reading a wall of text over a moving world is unkind.
 */
public class SubtitleLogScreen extends Screen {

	private static final int ROW_HEIGHT = 11;
	private static final int PANEL_WIDTH = 128;
	private static final int MARGIN = 8;

	/** Windows offered by the time filter, in minutes; 0 means everything. */
	private static final int[] WINDOWS = {0, 5, 15, 60, 180, 720, 1440, 2880};

	@Nullable
	private final Screen parent;

	private LogList list;
	private TextFieldWidget search;
	private Text status = Text.empty();

	// ── the filters ─────────────────────────────────────────────────────────
	private String query = "";
	/** Whose lines to show, or {@code null} for everybody. */
	private UUID speaker;
	/** Which kind to show, or {@code null} for both. */
	private SubtitleFeed.Source source;
	private int windowIndex;
	private boolean newestFirst;
	/** Set by the first press of "clear"; the second one actually does it. */
	private boolean clearArmed;
	/** The file the last export produced, which the status line then points at. */
	private Path exported;
	/** Where that status line was drawn, so a click on it can be noticed. */
	private int statusLeft;
	private int statusRight;
	private int statusTop;

	public SubtitleLogScreen(@Nullable Screen parent) {
		super(Text.translatable("voice_subtitles.log.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int listWidth = this.width - PANEL_WIDTH - MARGIN * 3;
		// Title, then the disclaimer, then the search box, then the list: each
		// with room of its own, since the first two are drawn rather than laid
		// out and will happily sit on top of a widget that assumes otherwise.
		int searchY = 40;
		int top = searchY + 24;
		int listHeight = this.height - top - 56;

		search = new TextFieldWidget(this.textRenderer, MARGIN, searchY, listWidth, 18,
				Text.translatable("voice_subtitles.log.search"));
		search.setMaxLength(80);
		search.setText(query);
		search.setPlaceholder(Text.translatable("voice_subtitles.log.search").formatted(Formatting.DARK_GRAY));
		search.setChangedListener(value -> {
			query = value;
			rebuild();
		});
		addDrawableChild(search);
		setInitialFocus(search);

		list = new LogList(this.client, listWidth, listHeight, top);
		list.setX(MARGIN);
		addDrawableChild(list);

		buildPanel(top, listHeight);

		int buttonWidth = 100;
		int spacing = 4;
		int total = buttonWidth * 3 + spacing * 2;
		int x = (this.width - total) / 2;
		int y = this.height - 30;

		ButtonWidget export = ButtonWidget.builder(Text.translatable("voice_subtitles.log.export"), b -> export())
				.dimensions(x, y, buttonWidth, 20).build();
		export.setTooltip(Tooltip.of(Text.translatable("voice_subtitles.log.export.tooltip")));
		addDrawableChild(export);

		// Asked twice, because this now deletes the files as well as the list.
		addDrawableChild(ButtonWidget.builder(Text.translatable("voice_subtitles.log.clear"), b -> {
			if (!clearArmed) {
				clearArmed = true;
				status = Text.translatable("voice_subtitles.log.clear.confirm").formatted(Formatting.YELLOW);
				return;
			}
			SubtitleLog.clear();
			clearArmed = false;
			status = Text.translatable("voice_subtitles.log.cleared").formatted(Formatting.GRAY);
			clearAndInit();
		}).dimensions(x + buttonWidth + spacing, y, buttonWidth, 20).build());

		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
				.dimensions(x + (buttonWidth + spacing) * 2, y, buttonWidth, 20).build());

		if (SubtitleLog.wasTampered()) {
			status = Text.translatable("voice_subtitles.log.tampered").formatted(Formatting.RED);
		}
		rebuild();
	}

	/** The filter column down the right side. */
	private void buildPanel(int top, int listHeight) {
		int x = this.width - PANEL_WIDTH - MARGIN;
		int y = top;
		int gap = 22;

		addDrawableChild(ButtonWidget.builder(speakerLabel(), b -> {
			cycleSpeaker();
			b.setMessage(speakerLabel());
			rebuild();
		}).dimensions(x, y, PANEL_WIDTH, 20).build());
		y += gap;

		addDrawableChild(ButtonWidget.builder(sourceLabel(), b -> {
			source = switch (source == null ? "all" : source.name()) {
				case "all" -> SubtitleFeed.Source.VOICE;
				case "VOICE" -> SubtitleFeed.Source.CHAT;
				case "CHAT" -> SubtitleFeed.Source.EVENT;
				default -> null;
			};
			b.setMessage(sourceLabel());
			rebuild();
		}).dimensions(x, y, PANEL_WIDTH, 20).build());
		y += gap;

		addDrawableChild(ButtonWidget.builder(windowLabel(), b -> {
			windowIndex = (windowIndex + 1) % WINDOWS.length;
			b.setMessage(windowLabel());
			rebuild();
		}).dimensions(x, y, PANEL_WIDTH, 20).build());
		y += gap;

		addDrawableChild(ButtonWidget.builder(orderLabel(), b -> {
			newestFirst = !newestFirst;
			b.setMessage(orderLabel());
			rebuild();
		}).dimensions(x, y, PANEL_WIDTH, 20).build());
		y += gap + 4;

		addDrawableChild(ButtonWidget.builder(Text.translatable("voice_subtitles.log.filters.reset"), b -> {
			query = "";
			speaker = null;
			source = null;
			windowIndex = 0;
			newestFirst = false;
			clearAndInit();
		}).dimensions(x, y, PANEL_WIDTH, 20).build());
	}

	// ── filter labels ───────────────────────────────────────────────────────

	private Text speakerLabel() {
		Text who = speaker == null
				? Text.translatable("voice_subtitles.log.filter.everyone")
				: Text.literal(shorten(nameOf(speaker), 14));
		return Text.translatable("voice_subtitles.log.filter.speaker", who);
	}

	private Text sourceLabel() {
		String key = source == null ? "any" : switch (source) {
			case VOICE -> "voice";
			case CHAT -> "chat";
			case EVENT -> "event";
		};
		return Text.translatable("voice_subtitles.log.filter.source",
				Text.translatable("voice_subtitles.log.filter.source." + key));
	}

	private Text windowLabel() {
		int minutes = WINDOWS[windowIndex];
		Text value;
		if (minutes == 0) {
			value = Text.translatable("voice_subtitles.log.filter.always");
		} else if (minutes < 60) {
			value = Text.translatable("voice_subtitles.time.minutes", minutes);
		} else if (minutes < 1440) {
			value = Text.translatable("voice_subtitles.time.hours", minutes / 60);
		} else {
			value = Text.translatable("voice_subtitles.time.days", minutes / 1440);
		}
		return Text.translatable("voice_subtitles.log.filter.window", value);
	}

	private Text orderLabel() {
		return Text.translatable("voice_subtitles.log.filter.order",
				Text.translatable(newestFirst ? "voice_subtitles.log.filter.newest" : "voice_subtitles.log.filter.oldest"));
	}

	/** Walks through everybody who has actually said something, then back to all. */
	private void cycleSpeaker() {
		List<UUID> speakers = new ArrayList<>(speakers().keySet());
		if (speakers.isEmpty()) {
			speaker = null;
			return;
		}
		int at = speaker == null ? -1 : speakers.indexOf(speaker);
		speaker = at + 1 >= speakers.size() ? null : speakers.get(at + 1);
	}

	/** Everybody in the log, by first appearance, with the name they had then. */
	private Map<UUID, String> speakers() {
		Map<UUID, String> found = new LinkedHashMap<>();
		for (SubtitleLog.Entry entry : SubtitleLog.entries()) {
			found.putIfAbsent(entry.speaker(), entry.name());
		}
		return found;
	}

	private String nameOf(UUID id) {
		return speakers().getOrDefault(id, id.toString().substring(0, 8));
	}

	private static String shorten(String text, int limit) {
		return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
	}

	// ── the list itself ─────────────────────────────────────────────────────

	/** @return the entries that pass every filter, in the chosen order */
	private List<SubtitleLog.Entry> filtered() {
		String needle = query.trim().toLowerCase(Locale.ROOT);
		long since = WINDOWS[windowIndex] == 0
				? Long.MIN_VALUE
				: System.currentTimeMillis() - WINDOWS[windowIndex] * 60_000L;

		List<SubtitleLog.Entry> out = new ArrayList<>();
		for (SubtitleLog.Entry entry : SubtitleLog.entries()) {
			if (speaker != null && !speaker.equals(entry.speaker()) && !entry.isEvent()) {
				continue;
			}
			if (source != null && entry.source() != source) {
				continue;
			}
			if (entry.time() < since) {
				continue;
			}
			if (!needle.isEmpty()
					&& !entry.text().toLowerCase(Locale.ROOT).contains(needle)
					&& !entry.translation().toLowerCase(Locale.ROOT).contains(needle)
					&& !entry.name().toLowerCase(Locale.ROOT).contains(needle)) {
				continue;
			}
			out.add(entry);
		}
		if (newestFirst) {
			java.util.Collections.reverse(out);
		}
		return out;
	}

	private void rebuild() {
		if (list == null) {
			return;
		}
		List<SubtitleLog.Entry> entries = filtered();
		list.show(entries, query.trim());
		if (!newestFirst) {
			list.scrollToBottom();
		}
		if (SubtitleLog.wasTampered()) {
			return;
		}
		int total = SubtitleLog.size();
		status = entries.size() == total
				? Text.translatable("voice_subtitles.log.count", total)
				: Text.translatable("voice_subtitles.log.count.filtered", entries.size(), total);
	}

	private void export() {
		List<SubtitleLog.Entry> entries = filtered();
		if (entries.isEmpty()) {
			status = Text.translatable("voice_subtitles.log.empty").formatted(Formatting.GRAY);
			return;
		}
		try {
			// Whatever the filters are showing is what gets written: seeing the
			// file differ from the screen would be its own small betrayal.
			Path file = SubtitleLog.export(entries);
			exported = file;
			// Underlined, because it can be clicked and nothing else here can.
			status = Text.translatable("voice_subtitles.log.exported", file.getFileName().toString())
					.formatted(Formatting.GREEN, Formatting.UNDERLINE);
		} catch (IOException e) {
			VoiceSubtitles.LOGGER.warn("could not export the conversation log", e);
			status = Text.translatable("voice_subtitles.log.export_failed").formatted(Formatting.RED);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFFFF);
		// Said once, up front: half of this came out of a recogniser.
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("voice_subtitles.log.disclaimer"), this.width / 2, 24, 0xFF9A9A9A);
		if (!status.getString().isEmpty()) {
			int width = this.textRenderer.getWidth(status);
			statusLeft = this.width / 2 - width / 2;
			statusRight = statusLeft + width;
			statusTop = this.height - 44;
			context.drawCenteredTextWithShadow(this.textRenderer, status, this.width / 2, statusTop, 0xFFFFFFFF);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (exported != null && button == 0
				&& mouseX >= statusLeft && mouseX <= statusRight
				&& mouseY >= statusTop && mouseY <= statusTop + 9) {
			Reveal.inFileManager(exported);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
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

		private final int rowWidth;
		/** True while the little bar on the right is being dragged. */
		private boolean draggingBar;

		LogList(MinecraftClient client, int width, int height, int y) {
			super(client, width, height, y, ROW_HEIGHT);
			// Room for the bar. Minecraft puts it at the right edge of the rows
			// plus eight, and centres the rows in the widget, so rows any wider
			// than this push the bar past the widget's own edge and under the
			// filter panel, where nothing can reach it. That is exactly where it
			// was, which is why dragging it did nothing at all.
			this.rowWidth = width - 32;
		}

		void show(List<SubtitleLog.Entry> entries, String highlight) {
			List<Row> rows = new ArrayList<>();
			MinecraftClient client = MinecraftClient.getInstance();
			for (SubtitleLog.Entry entry : entries) {
				MutableText full;
				if (entry.isEvent()) {
					full = Text.literal(SubtitleLog.timeOf(entry) + "  ").formatted(Formatting.DARK_GRAY)
							.append(Text.literal("– ").formatted(Formatting.DARK_GRAY))
							.append(body(entry.text(), highlight).formatted(Formatting.GRAY, Formatting.ITALIC));
					for (OrderedText row : client.textRenderer.wrapLines(full, rowWidth)) {
						rows.add(new Row(row));
					}
					continue;
				}
				if (entry.source() == SubtitleFeed.Source.CHAT && entry.name().isEmpty()) {
					// Straight from the chat: no name of its own, so it is marked
					// as chat instead of borrowing somebody's.
					full = Text.literal(SubtitleLog.timeOf(entry) + " ").formatted(Formatting.DARK_GRAY)
							.append(Text.literal("[CHAT] ").formatted(Formatting.DARK_AQUA))
							.append(body(entry.text(), highlight));
					for (OrderedText row : client.textRenderer.wrapLines(full, rowWidth)) {
						rows.add(new Row(row));
					}
					addTranslation(rows, entry, highlight, client);
					continue;
				}
				int color = SpeakerColors.colorOf(entry.speaker());
				full = Text.literal(SubtitleLog.timeOf(entry) + " ").formatted(Formatting.DARK_GRAY)
						.append(Text.literal(entry.name()).styled(style -> style.withColor(color)))
						.append(Text.literal(entry.source() == SubtitleFeed.Source.CHAT ? " [chat]: " : ": ")
								.formatted(Formatting.DARK_GRAY))
						.append(body(entry.text(), highlight));
				for (OrderedText row : client.textRenderer.wrapLines(full, rowWidth)) {
					rows.add(new Row(row));
				}
				addTranslation(rows, entry, highlight, client);
			}
			replaceEntries(rows);
		}

		/**
		 * The translation under the line it belongs to, indented and dimmed.
		 *
		 * <p>Under rather than instead: what was said is the record, and what it
		 * means is a reading of it.
		 */
		private void addTranslation(List<Row> rows, SubtitleLog.Entry entry, String highlight,
		                            MinecraftClient client) {
			if (!entry.translated()) {
				return;
			}
			MutableText line = Text.literal("        ↳ ").formatted(Formatting.DARK_GRAY)
					.append(body(entry.translation(), highlight).formatted(Formatting.GRAY));
			for (OrderedText row : client.textRenderer.wrapLines(line, rowWidth)) {
				rows.add(new Row(row));
			}
		}

		/** The text, with what was searched for picked out of it. */
		private static MutableText body(String text, String highlight) {
			if (highlight.isEmpty()) {
				return Text.literal(text).formatted(Formatting.WHITE);
			}
			MutableText out = Text.empty();
			String haystack = text.toLowerCase(Locale.ROOT);
			String needle = highlight.toLowerCase(Locale.ROOT);
			int from = 0;
			int at;
			while ((at = haystack.indexOf(needle, from)) >= 0) {
				out.append(Text.literal(text.substring(from, at)).formatted(Formatting.WHITE));
				out.append(Text.literal(text.substring(at, at + needle.length()))
						.formatted(Formatting.YELLOW, Formatting.BOLD));
				from = at + needle.length();
			}
			return out.append(Text.literal(text.substring(from)).formatted(Formatting.WHITE));
		}

		/**
		 * Takes the click even when no entry wanted it.
		 *
		 * <p>Minecraft only sends drag events to whichever widget accepted the
		 * press, and the rows here are plain text that accepts nothing, so the
		 * list was never asked about the drag that followed. Saying yes to a
		 * press inside the list is what makes the pulling below possible.
		 */
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			// The bar is claimed before anything else is asked. Handing the press
			// to the widget first meant it answered "mine" and then moved
			// nothing, and our own handling never got its turn.
			if (button == 0 && overScrollbar(mouseX, mouseY)) {
				draggingBar = true;
				scrollTo(mouseY);
				return true;
			}
			if (super.mouseClicked(mouseX, mouseY, button)) {
				return true;
			}
			// Grabbing the text is dragging too, just of the other thing. Taken
			// here because the list is never asked otherwise: rows of plain text
			// accept no clicks for a drag to start from.
			return button == 0 && isMouseOver(mouseX, mouseY);
		}

		/**
		 * True when the pointer is on the little bar.
		 *
		 * <p>Tested against the bar's own position rather than against the list's,
		 * because the two are not the same: the bar is drawn at the right edge of
		 * the rows plus eight pixels, which lands just outside the widget itself.
		 * Asking whether the pointer was inside the list therefore answered no
		 * for every press on the bar, which is why dragging it did nothing.
		 */
		private boolean overScrollbar(double mouseX, double mouseY) {
			int barX = getScrollbarX();
			return mouseX >= barX - 1 && mouseX <= barX + 7
					&& mouseY >= getY() && mouseY <= getY() + getHeight();
		}

		/** Puts the view where the pointer is, proportionally. */
		private void scrollTo(double mouseY) {
			double fraction = (mouseY - getY()) / (double) Math.max(getHeight(), 1);
			setScrollY(Math.max(0D, Math.min(1D, fraction)) * getMaxScrollY());
		}

		@Override
		public boolean mouseReleased(double mouseX, double mouseY, int button) {
			draggingBar = false;
			return super.mouseReleased(mouseX, mouseY, button);
		}

		/**
		 * Lets the list be pulled about with the mouse, not only nudged with the
		 * wheel: a scrollbar six pixels wide is a poor thing to have to aim at.
		 */
		@Override
		public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
			// Ours first, for the same reason as above.
			if (button == 0 && draggingBar) {
				scrollTo(mouseY);
				return true;
			}
			if (super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
				return true;
			}
			if (button != 0) {
				return false;
			}
			setScrollY(getScrollY() - deltaY);
			return true;
		}

		void scrollToBottom() {
			setScrollY(getMaxScrollY());
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
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
