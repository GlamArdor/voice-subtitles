package com.glamardor.voicesubtitles.hud;

import com.glamardor.voicesubtitles.Captions;
import com.glamardor.voicesubtitles.NameResolver;
import com.glamardor.voicesubtitles.SpeakerColors;
import com.glamardor.voicesubtitles.SpeakerDirection;
import com.glamardor.voicesubtitles.SubtitleFeed;
import com.glamardor.voicesubtitles.TextColorizer;
import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.ColorHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The subtitle feed at the bottom of the screen.
 *
 * <p>Laid out in the spirit of Minecraft: Story Mode – a centred stack of
 * captions sitting above the hotbar, each one a dim plate carrying the
 * speaker's head, their name and what they said, fading in as they start
 * talking and out once they are done.
 */
public final class SubtitleHud {

	/** Head icon size, in GUI pixels. */
	private static final int HEAD_SIZE = 8;
	/** Gap between the head and the text. */
	private static final int HEAD_GAP = 4;
	/** Gap between the direction arrow and whatever follows it. */
	private static final int ARROW_GAP = 3;
	/** Padding inside a caption plate. */
	private static final int PADDING = 4;
	/** Vertical gap between two captions. */
	private static final int LINE_GAP = 3;
	/** Height of one text line. */
	private static final int TEXT_HEIGHT = 9;

	/** How much of the screen the feed is pushed up when the chat is open. */
	private static final int CHAT_OFFSET = 14;

	/** How solid a translation is next to the words it translates. */
	private static final float TRANSLATION_ALPHA = 0.72F;

	/**
	 * The gap left under the feed in cinema mode.
	 *
	 * <p>The ordinary offset is there to clear the hotbar, and cinema mode has no
	 * hotbar to clear, so keeping it would leave the captions floating in the
	 * middle of the picture. This is the small breathing space above the matte
	 * instead. A player who set an even smaller offset keeps theirs.
	 */
	private static final int CINEMA_BOTTOM_OFFSET = 8;

	/** The same, for a feed hanging from the top. */
	private static final int CINEMA_TOP_OFFSET = 8;

	private SubtitleHud() {
	}

	/**
	 * Draws the feed when the interface around it is not being drawn.
	 *
	 * <p>Called before anything else has a chance to skip or cancel the HUD:
	 * vanilla skips its layers when the player hides the interface, and Absolute
	 * Cinema Camera cancels the lot for its film mode. Either way the feed is the
	 * one thing that should stay, since somebody reading the conversation rather
	 * than hearing it loses the conversation with it.
	 */
	public static void renderWhileHidden(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		boolean cinema = CinemaCompat.hidingHud();
		if (!client.guiManager.inGameHud.isGuiHidden() && !cinema) {
			return; // the ordinary HUD layer is drawing it
		}
		if (client.guiManager.inGameHud.isGuiHidden() && VoiceSubtitlesConfig.get().hideWithHud && !cinema) {
			return; // asked to go with the rest of the interface
		}
		if (!cinema) {
			render(context, tickCounter);
			return;
		}
		// Sat just clear of the matte on whichever side it is on: lifted off the
		// black, and without the hotbar allowance that has nothing to clear in
		// cinema mode. A feed along the top clears the top matte the same way,
		// which is downwards.
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		boolean top = cfg.feedAnchor == VoiceSubtitlesConfig.Anchor.TOP;
		int lift = CinemaCompat.barHeight(context);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(0F, top ? lift : -lift);
		render(context, tickCounter, top
				? Math.min(cfg.topOffset, CINEMA_TOP_OFFSET)
				: Math.min(cfg.bottomOffset, CINEMA_BOTTOM_OFFSET));
		context.getMatrices().popMatrix();
	}

	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		render(context, tickCounter,
				cfg.feedAnchor == VoiceSubtitlesConfig.Anchor.TOP ? cfg.topOffset : cfg.bottomOffset);
	}

	/**
	 * @param edgeOffset how far the feed sits from the edge it hangs off, which is the bottom of the
	 *                   screen or the top of it depending on the setting
	 */
	private static void render(DrawContext context, RenderTickCounter tickCounter, int edgeOffset) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.enabled) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		if (client.guiManager.inGameHud.isGuiHidden() && cfg.hideWithHud && !CinemaCompat.hidingHud()) {
			return;
		}

		List<SubtitleFeed.Line> lines = SubtitleFeed.all();
		TextRenderer textRenderer = client.textRenderer;

		// Drawn whether or not anybody is talking: a download that takes minutes
		// and a model that takes a minute to load both need to say so, and they
		// need to say it before the first caption fails to appear, not after.
		boolean fromTop = cfg.feedAnchor == VoiceSubtitlesConfig.Anchor.TOP;
		// Downloads and models say so at the bottom of the screen whether or not
		// the feed is down there: it is a notice about the mod rather than part
		// of the conversation, and it has always sat above the hotbar.
		int loadingHeight = ProgressOverlay.render(context, textRenderer,
				context.getScaledWindowWidth() / 2,
				context.getScaledWindowHeight() - (fromTop ? cfg.bottomOffset : edgeOffset));

		if (lines.isEmpty()) {
			return;
		}
		float scale = cfg.textScalePercent / 100F;
		int screenWidth = (int) (context.getScaledWindowWidth() / scale);
		int screenHeight = (int) (context.getScaledWindowHeight() / scale);

		int headOffset = cfg.showHead ? HEAD_SIZE + HEAD_GAP : 0;
		// The arrow column is reserved whenever the setting is on, even for lines
		// that have no arrow: it keeps the feed from jittering sideways as people
		// walk in and out of view.
		int arrowOffset = cfg.showDirection
				? textRenderer.getWidth(SpeakerDirection.WIDEST) + ARROW_GAP
				: 0;
		int maxTotalWidth = screenWidth * cfg.maxWidthPercent / 100;
		int maxTextWidth = Math.max(60, maxTotalWidth - headOffset - arrowOffset - PADDING * 2);

		// Wall-clock, because that is what the feed stamps its lines with. Mixing
		// this with Util.getMeasuringTimeMs() (monotonic since JVM start) makes
		// every line look decades old and fades the whole feed to nothing.
		long now = System.currentTimeMillis();
		List<Caption> captions = new ArrayList<>(lines.size());

		for (SubtitleFeed.Line line : lines) {
			// Voice and chat can be placed independently; a line whose kind lives
			// above heads only is not part of the feed.
			if (!cfg.placementOf(line.source == SubtitleFeed.Source.CHAT).inFeed()) {
				continue;
			}
			Caption caption = build(line, cfg, textRenderer, maxTextWidth, now);
			if (caption != null) {
				captions.add(caption);
			}
		}
		if (captions.isEmpty()) {
			return;
		}
		// The line limit applies to what actually reaches the feed, so sending
		// chat above heads does not leave gaps here.
		if (captions.size() > cfg.maxLines) {
			captions = captions.subList(captions.size() - cfg.maxLines, captions.size());
		}

		// Where the stack starts, on whichever edge it hangs off. Anchored to the
		// bottom it also gets out of the way of the chat box and of anything the
		// download notice has taken; neither of those is anywhere near the top.
		int bottom = screenHeight - edgeOffset - Math.round(loadingHeight / scale);
		if (client.guiManager.getCurrentScreen() instanceof ChatScreen) {
			bottom -= CHAT_OFFSET;
		}
		int start = edgeOffset + PADDING;

		context.getMatrices().pushMatrix();
		context.getMatrices().scale(scale, scale);

		// Oldest first, reading downwards, either way round: at the bottom the
		// stack grows upwards from the newest caption, at the top it grows
		// downwards from the oldest, and both put the newest words nearest the
		// middle of the screen where the eye already is.
		int y = fromTop ? start : bottom;
		for (int step = 0; step < captions.size(); step++) {
			Caption caption = captions.get(fromTop ? step : captions.size() - 1 - step);
			int blockHeight = (caption.rows.size() + caption.translation.size()) * TEXT_HEIGHT;
			int blockWidth = arrowOffset + headOffset + caption.width;
			int top = fromTop ? y : y - blockHeight;

			int x = switch (cfg.feedAlignment) {
				case LEFT -> 8 + PADDING;
				case CENTER -> (screenWidth - blockWidth) / 2;
				case RIGHT -> screenWidth - blockWidth - 8 - PADDING;
			};

			drawCaption(context, textRenderer, caption, x, top, blockWidth, blockHeight,
					arrowOffset, headOffset, cfg);

			if (fromTop) {
				y = top + blockHeight + LINE_GAP + PADDING * 2;
				if (y > screenHeight) {
					break;
				}
			} else {
				y = top - LINE_GAP - PADDING * 2;
				if (y < 0) {
					break;
				}
			}
		}

		context.getMatrices().popMatrix();
	}

	private static void drawCaption(DrawContext context, TextRenderer textRenderer, Caption caption,
	                                int x, int top, int blockWidth, int blockHeight, int arrowOffset,
	                                int headOffset, VoiceSubtitlesConfig cfg) {
		int alpha = caption.alpha;

		// Backing plate.
		int plateAlpha = Math.round(cfg.backgroundOpacity / 100F * 255F * (alpha / 255F));
		if (plateAlpha > 2) {
			context.fill(x - PADDING, top - PADDING, x + blockWidth + PADDING, top + blockHeight + PADDING - 1,
					ColorHelper.getArgb(plateAlpha, 0, 0, 0));
		}

		// Where the voice is coming from, aligned with the first row.
		if (caption.arrow != null) {
			context.drawTextWithShadow(textRenderer, caption.arrow, x, top,
					ColorHelper.withAlpha(alpha, caption.color));
		}

		// Speaker head, aligned with the first row.
		if (cfg.showHead && caption.skin != null) {
			PlayerSkinDrawer.draw(context, caption.skin, x + arrowOffset, top, HEAD_SIZE,
					ColorHelper.getArgb(alpha, 255, 255, 255));
		}

		int textX = x + arrowOffset + headOffset;
		int rowY = top;
		for (OrderedText row : caption.rows) {
			context.drawTextWithShadow(textRenderer, row, textX, rowY, ColorHelper.withAlpha(alpha, 0xFFFFFF));
			rowY += TEXT_HEIGHT;
		}
		// The translation under what was said, dimmer, so which is which is
		// obvious without reading either.
		int quieter = Math.round(alpha * TRANSLATION_ALPHA);
		for (OrderedText row : caption.translation) {
			context.drawTextWithShadow(textRenderer, row, textX, rowY, ColorHelper.withAlpha(quieter, 0xFFFFFF));
			rowY += TEXT_HEIGHT;
		}
	}

	/** Turns one feed line into laid-out rows, or {@code null} if it should not be shown. */
	private static Caption build(SubtitleFeed.Line line, VoiceSubtitlesConfig cfg,
	                             TextRenderer textRenderer, int maxTextWidth, long now) {
		int alpha = Captions.alphaOf(line, now);
		if (alpha <= 4) {
			return null;
		}

		int color = SpeakerColors.colorOf(line.speaker);

		// Names keep the server's formatting unless asked otherwise. When they
		// are coloured, the colour is pushed through the whole name rather than
		// set on its root, since each piece carries its own style – and with
		// "keep server formatting" on, only the plain pieces take it.
		MutableText name = cfg.colorNames
				? TextColorizer.colorize(NameResolver.nameOf(line.speaker), color, cfg.keepServerNameStyle)
				: NameResolver.nameOf(line.speaker).copy();

		int bodyColor = cfg.colorSpeech ? color : SpeakerColors.WHITE;
		// With the original turned off, the translation takes its place entirely:
		// somebody who cannot read the language has no use for a line of it.
		boolean replace = line.translated() && !cfg.showOriginal;
		String spoken = replace ? line.translation() : Captions.bodyOf(line, now);

		MutableText full = Text.empty()
				.append(name)
				.append(Text.literal(": ").styled(style -> style.withColor(bodyColor)))
				.append(Text.literal(spoken).styled(style -> style.withColor(bodyColor)));

		// Sized to what is on screen, not to what is coming. Sizing it to the
		// whole sentence was an attempt to hold the plate still while the text
		// typed itself into it, and it made things worse: every phrase the
		// recogniser handed over widened the plate in one step, and a centred
		// feed jumps sideways when its plate widens. Grown a character at a time
		// with the text, it does not jump at all.
		List<OrderedText> rows = textRenderer.wrapLines(full, maxTextWidth);
		int width = 0;
		for (OrderedText row : rows) {
			width = Math.max(width, textRenderer.getWidth(row));
		}

		List<OrderedText> translation = List.of();
		if (line.translated() && !replace) {
			translation = textRenderer.wrapLines(
					Text.literal(line.translation()).styled(style -> style.withColor(SpeakerColors.WHITE)),
					maxTextWidth);
			for (OrderedText row : translation) {
				width = Math.max(width, textRenderer.getWidth(row));
			}
		}

		String arrow = cfg.showDirection ? SpeakerDirection.arrowFor(line.speaker) : null;

		return new Caption(rows, translation, width, alpha, skinOf(line, cfg), arrow, color);
	}

	private static SkinTextures skinOf(SubtitleFeed.Line line, VoiceSubtitlesConfig cfg) {
		if (!cfg.showHead) {
			return null;
		}
		PlayerListEntry entry = NameResolver.entryOf(line.speaker);
		if (entry != null) {
			return entry.getSkinTextures();
		}
		return DefaultSkinHelper.getSkinTextures(line.speaker);
	}

	/** One caption, ready to draw. */
	private record Caption(List<OrderedText> rows, List<OrderedText> translation, int width, int alpha,
	                       SkinTextures skin, String arrow, int color) {
	}
}
