package com.glamardor.voicesubtitles.hud;

import com.glamardor.voicesubtitles.speech.SpeechRecognition;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.ColorHelper;

/**
 * "The recogniser is still waking up" – shown above the feed while a model is
 * being read into memory.
 *
 * <p>The large models take the better part of a minute, and without this the
 * mod simply says nothing for that whole time, which is indistinguishable from
 * being broken. Vosk reports no progress of its own, so the bar is drawn
 * against how long the same model took to load on this machine before; the
 * first time round there is nothing to measure against and it counts seconds
 * instead of pretending to know better.
 */
public final class LoadingIndicator {

	private static final int BAR_WIDTH = 120;
	private static final int BAR_HEIGHT = 3;
	private static final int GAP = 3;

	private LoadingIndicator() {
	}

	/** @return the height it drew, so the feed can sit above it */
	public static int render(DrawContext context, TextRenderer textRenderer, int centerX, int bottom) {
		if (SpeechRecognition.state() != SpeechRecognition.State.LOADING) {
			return 0;
		}

		float progress = SpeechRecognition.loadProgress();
		String model = SpeechRecognition.currentModelId();
		Text label = progress < 0F
				? Text.translatable("voice_subtitles.loading.elapsed", model, SpeechRecognition.loadElapsedSeconds())
				: Text.translatable("voice_subtitles.loading.percent", model, Math.round(progress * 100F));

		int textWidth = textRenderer.getWidth(label);
		int width = Math.max(textWidth, BAR_WIDTH);
		int left = centerX - width / 2;
		int top = bottom - 9 - GAP - BAR_HEIGHT;

		context.fill(left - 4, top - 3, left + width + 4, bottom + 1, ColorHelper.getArgb(120, 0, 0, 0));
		context.drawTextWithShadow(textRenderer, label, centerX - textWidth / 2, top, 0xFFE0C070);

		int barLeft = centerX - BAR_WIDTH / 2;
		int barTop = bottom - BAR_HEIGHT;
		context.fill(barLeft, barTop, barLeft + BAR_WIDTH, barTop + BAR_HEIGHT, ColorHelper.getArgb(160, 40, 40, 40));

		int filled = progress < 0F
				// Nothing to measure against yet, so a strip walks the bar rather
				// than claiming a position it does not know.
				? BAR_WIDTH / 5
				: Math.max(1, Math.round(BAR_WIDTH * progress));
		int barX = progress < 0F
				? barLeft + (int) ((System.currentTimeMillis() / 12L) % (BAR_WIDTH - filled))
				: barLeft;
		context.fill(barX, barTop, barX + filled, barTop + BAR_HEIGHT, ColorHelper.getArgb(220, 224, 192, 112));

		return 9 + GAP + BAR_HEIGHT + 6;
	}
}
