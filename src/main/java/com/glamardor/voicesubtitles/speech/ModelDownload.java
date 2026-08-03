package com.glamardor.voicesubtitles.speech;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetching a recognition model, in the background, at most one at a time.
 *
 * <p>Shared by the automatic first-run download and the {@code /vsub model
 * download} command, so the two can never race each other into the same folder.
 *
 * <p>Progress is reported wherever the player can actually see it: a toast in
 * the corner (the download usually starts at the main menu, where there is no
 * chat yet) and the log.
 */
public final class ModelDownload {

	private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

	private ModelDownload() {
	}

	public static boolean isRunning() {
		return RUNNING.get();
	}

	/**
	 * Starts a download unless one is already going.
	 *
	 * @param onFinished run on the client thread once the model is in place
	 * @return false if another download is already running
	 */
	public static boolean startOnce(SpeechModels.Entry entry, Runnable onFinished) {
		if (!RUNNING.compareAndSet(false, true)) {
			return false;
		}

		toast(Text.translatable("toast.voice_subtitles.model.downloading"),
				Text.translatable("toast.voice_subtitles.model.size", entry.language(), entry.megabytes()));
		VoiceSubtitles.LOGGER.info("Downloading speech model '{}' ({} MB)", entry.id(), entry.megabytes());

		Thread thread = new Thread(() -> {
			try {
				SpeechModels.download(entry, percent -> {
					if (percent % 25 == 0 && percent > 0 && percent < 100) {
						VoiceSubtitles.LOGGER.info("Model '{}': {}%", entry.id(), percent);
					}
				});
				toast(Text.translatable("toast.voice_subtitles.model.ready"),
						Text.translatable("toast.voice_subtitles.model.ready.detail", entry.language()));
				MinecraftClient.getInstance().execute(onFinished);
			} catch (Exception e) {
				VoiceSubtitles.LOGGER.error("Could not download the speech model", e);
				toast(Text.translatable("toast.voice_subtitles.model.failed"),
						Text.literal(String.valueOf(e.getMessage())).formatted(Formatting.RED));
			} finally {
				RUNNING.set(false);
			}
		}, "voice-subtitles-model-download");
		thread.setDaemon(true);
		thread.start();
		return true;
	}

	/**
	 * Fetches the sherpa-onnx native library, once.
	 *
	 * <p>Kept apart from the model download and announced separately, because it
	 * is a different kind of thing: this is executable code, and a player has a
	 * right to see it said plainly rather than folded into "downloading a model".
	 */
	public static boolean startNativesOnce(SherpaNatives.Build build, Runnable onFinished) {
		if (!RUNNING.compareAndSet(false, true)) {
			return false;
		}

		toast(Text.translatable("toast.voice_subtitles.native.downloading"),
				Text.translatable("toast.voice_subtitles.native.size", build.platform(), build.megabytes()));
		VoiceSubtitles.LOGGER.info("Downloading the sherpa-onnx native library for {} ({} MB)",
				build.platform(), build.megabytes());

		Thread thread = new Thread(() -> {
			try {
				SherpaNatives.download(build, percent -> {
					if (percent % 25 == 0 && percent > 0 && percent < 100) {
						VoiceSubtitles.LOGGER.info("sherpa-onnx library: {}%", percent);
					}
				});
				toast(Text.translatable("toast.voice_subtitles.native.ready"),
						Text.translatable("toast.voice_subtitles.native.ready.detail"));
				MinecraftClient.getInstance().execute(onFinished);
			} catch (Exception e) {
				VoiceSubtitles.LOGGER.error("Could not download the sherpa-onnx native library", e);
				toast(Text.translatable("toast.voice_subtitles.native.failed"),
						Text.literal(String.valueOf(e.getMessage())).formatted(Formatting.RED));
			} finally {
				RUNNING.set(false);
			}
		}, "voice-subtitles-native-download");
		thread.setDaemon(true);
		thread.start();
		return true;
	}

	private static void toast(Text title, Text description) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> client.getToastManager().add(
				new SystemToast(SystemToast.Type.PERIODIC_NOTIFICATION, title, description)));
	}
}
