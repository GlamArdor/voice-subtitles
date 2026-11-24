package com.glamardor.voicesubtitles.translate;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import com.glamardor.voicesubtitles.speech.DownloadState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetching a translation model, in the background, once per direction.
 *
 * <p>Kept apart from the speech model download for the same reason that one is
 * kept apart from the library it runs on: they are different things, they happen
 * at different times, and folding them together would mean a bar that says
 * "downloading a model" for eight minutes without ever saying which.
 */
final class TranslationDownload {

	private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

	private TranslationDownload() {
	}

	/**
	 * Starts a download unless this direction is already on its way.
	 *
	 * @return false if it was already running
	 */
	static boolean startOnce(TranslationModels.Entry entry) {
		if (!RUNNING.add(entry.id())) {
			return false;
		}

		toast(Text.translatable("toast.voice_subtitles.translation.downloading"),
				Text.translatable("toast.voice_subtitles.translation.size", entry.id(), entry.megabytes()));
		VoiceSubtitles.LOGGER.info("Downloading the {} translation model ({} MB)", entry.id(), entry.megabytes());

		Thread thread = new Thread(() -> {
			try {
				DownloadState.begin(Text.translatable("voice_subtitles.download.translation", entry.id()));
				TranslationModels.download(entry, DownloadState::update);
				toast(Text.translatable("toast.voice_subtitles.translation.ready"),
						Text.translatable("toast.voice_subtitles.translation.ready.detail", entry.id()));
			} catch (Exception e) {
				VoiceSubtitles.LOGGER.error("Could not download the {} translation model", entry.id(), e);
				toast(Text.translatable("toast.voice_subtitles.translation.failed"),
						Text.literal(String.valueOf(e.getMessage())).formatted(Formatting.RED));
			} finally {
				DownloadState.end();
				RUNNING.remove(entry.id());
			}
		}, "voice-subtitles-translation-download");
		thread.setDaemon(true);
		thread.start();
		return true;
	}

	private static void toast(Text title, Text description) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> client.guiManager.getToastManager().add(
				new SystemToast(SystemToast.Type.PERIODIC_NOTIFICATION, title, description)));
	}
}
