package com.glamardor.voicesubtitles.hud;

import com.glamardor.voicesubtitles.speech.DownloadState;
import com.glamardor.voicesubtitles.speech.SpeechRecognition;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.ColorHelper;

/**
 * "Something is happening, and here is how far along it is."
 *
 * <p>Two things can keep the mod from producing captions for a while: a model
 * being downloaded, which is minutes on a modest connection, and a model being
 * read into memory, which is up to a minute for the large ones. Both used to
 * happen in silence, which is indistinguishable from being broken, and the
 * natural response to that is to restart the game and throw the work away.
 *
 * <p>Drawn on the HUD in game, and on menus when there is no world, because the
 * first download usually starts at the title screen where there is no HUD.
 */
public final class ProgressOverlay {

	private static final int BAR_WIDTH = 160;
	private static final int BAR_HEIGHT = 3;
	private static final int GAP = 3;
	private static final int TEXT_HEIGHT = 9;

	private ProgressOverlay() {
	}

	/** @return the height it drew, so a caller can move whatever sits above it */
	public static int render(DrawContext context, TextRenderer textRenderer, int centerX, int bottom) {
		DownloadState.Snapshot download = DownloadState.snapshot();
		if (download != null) {
			return draw(context, textRenderer, centerX, bottom, describe(download), download.fraction());
		}
		if (SpeechRecognition.state() == SpeechRecognition.State.LOADING) {
			String model = SpeechRecognition.currentModelId();
			float progress = SpeechRecognition.loadProgress();
			Text label = progress < 0F
					? Text.translatable("voice_subtitles.loading.elapsed", model, SpeechRecognition.loadElapsedSeconds())
					: Text.translatable("voice_subtitles.loading.percent", model, Math.round(progress * 100F));
			return draw(context, textRenderer, centerX, bottom, label, progress);
		}
		return 0;
	}

	/**
	 * The line above the bar: what, how far, how fast, how much longer.
	 *
	 * <p>The remaining time is only shown once a reading is worth trusting, so
	 * the first thing a player sees is not "45 minutes left" from a connection
	 * that had not got going yet.
	 */
	private static Text describe(DownloadState.Snapshot download) {
		String done = megabytes(download.read());
		if (download.unpacking()) {
			// Nothing is being fetched: this is the full build putting what it
			// carries on disk, and it is over in seconds rather than minutes.
			return Text.translatable("voice_subtitles.bundle.progress",
					done, megabytes(download.total()), download.percent());
		}
		if (download.installing()) {
			// The bytes are in; what is left is checking and unpacking them, and
			// for a large model that is a wait of its own.
			return Text.translatable("voice_subtitles.download.installing", download.label(), done);
		}
		if (download.total() <= 0L) {
			// The server did not say how big it is; showing what has arrived is
			// still better than a bar pretending to know.
			return Text.translatable("voice_subtitles.download.unknown", download.label(), done);
		}
		String total = megabytes(download.total());
		long left = download.secondsLeft();
		if (left < 0L || download.elapsedMillis() < 3000L) {
			return Text.translatable("voice_subtitles.download.progress",
					download.label(), done, total, download.percent());
		}
		return Text.translatable("voice_subtitles.download.progress_eta",
				download.label(), done, total, download.percent(), time(left));
	}

	private static String megabytes(long bytes) {
		return String.format("%.1f", bytes / 1024F / 1024F);
	}

	private static Text time(long seconds) {
		return seconds < 60L
				? Text.translatable("voice_subtitles.time.seconds", seconds)
				: Text.translatable("voice_subtitles.time.minutes", (seconds + 30L) / 60L);
	}

	private static int draw(DrawContext context, TextRenderer textRenderer, int centerX, int bottom,
	                        Text label, float progress) {
		int textWidth = textRenderer.getWidth(label);
		int width = Math.max(textWidth, BAR_WIDTH);
		int left = centerX - width / 2;
		int top = bottom - TEXT_HEIGHT - GAP - BAR_HEIGHT;

		context.fill(left - 4, top - 3, left + width + 4, bottom + 1, ColorHelper.getArgb(150, 0, 0, 0));
		context.drawTextWithShadow(textRenderer, label, centerX - textWidth / 2, top, 0xFFE0C070);

		int barLeft = centerX - BAR_WIDTH / 2;
		int barTop = bottom - BAR_HEIGHT;
		context.fill(barLeft, barTop, barLeft + BAR_WIDTH, barTop + BAR_HEIGHT, ColorHelper.getArgb(160, 40, 40, 40));

		if (progress < 0F) {
			// Nothing to measure against, so a strip walks the bar rather than
			// claiming a position it does not know.
			int strip = BAR_WIDTH / 5;
			int x = barLeft + (int) ((System.currentTimeMillis() / 12L) % (BAR_WIDTH - strip));
			context.fill(x, barTop, x + strip, barTop + BAR_HEIGHT, ColorHelper.getArgb(220, 224, 192, 112));
		} else {
			int filled = Math.max(1, Math.round(BAR_WIDTH * Math.min(1F, progress)));
			context.fill(barLeft, barTop, barLeft + filled, barTop + BAR_HEIGHT,
					ColorHelper.getArgb(220, 224, 192, 112));
		}

		return TEXT_HEIGHT + GAP + BAR_HEIGHT + 6;
	}
}
