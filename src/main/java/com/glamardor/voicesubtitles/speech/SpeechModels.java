package com.glamardor.voicesubtitles.speech;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The catalogue of offline recognition models, and where they live on disk.
 *
 * <p>Models are downloaded on demand rather than shipped: the small Russian one
 * alone is bigger than everything else in this mod put together, and most
 * players need exactly one.
 *
 * <p>Each entry names the engine that can run it. There is no separate engine
 * setting on purpose: choosing a model is choosing an engine, and a setting that
 * let the two be picked apart would let them be picked wrong.
 */
public final class SpeechModels {

	/** Which back end a model runs on. */
	public enum Engine {
		VOSK,
		SHERPA
	}

	/**
	 * One downloadable model.
	 *
	 * @param folder    the directory the archive unpacks into
	 * @param megabytes download size, for telling the player what they are in for
	 */
	public record Entry(String id, String language, String folder, String url, int megabytes, Engine engine) {

		boolean isTarBz2() {
			return url.endsWith(".tar.bz2");
		}
	}

	private static final Map<String, Entry> CATALOGUE = new LinkedHashMap<>();

	private static final String VOSK = "https://alphacephei.com/vosk/models/";
	private static final String SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/";

	static {
		// ── Vosk: one small model per language, and the heavy ones ──────────
		vosk("ru", "Русский", "vosk-model-small-ru-0.22", 45);
		vosk("en", "English", "vosk-model-small-en-us-0.15", 40);
		vosk("ru-large", "Русский (большая)", "vosk-model-ru-0.42", 1800);
		vosk("en-large", "English (large)", "vosk-model-en-us-0.22", 1800);
		vosk("uk", "Українська", "vosk-model-small-uk-v3-small", 133);
		vosk("de", "Deutsch", "vosk-model-small-de-0.15", 45);
		vosk("fr", "Français", "vosk-model-small-fr-0.22", 41);
		vosk("es", "Español", "vosk-model-small-es-0.42", 39);
		vosk("pl", "Polski", "vosk-model-small-pl-0.22", 50);
		vosk("it", "Italiano", "vosk-model-small-it-0.22", 48);
		vosk("ja", "日本語", "vosk-model-small-ja-0.22", 48);

		// ── sherpa-onnx: streaming, and far quicker to load ──────────────────
		sherpa("ru-fast", "Русский (быстрая)", "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16", 23);
		sherpa("ru-onnx", "Русский (точная)", "sherpa-onnx-streaming-zipformer-small-ru-vosk-2025-08-16", 85);
		sherpa("ru-tone", "Русский (T-one)", "sherpa-onnx-streaming-t-one-russian-2025-09-08", 123);
	}

	private SpeechModels() {
	}

	private static void vosk(String id, String language, String folder, int megabytes) {
		add(new Entry(id, language, folder, VOSK + folder + ".zip", megabytes, Engine.VOSK));
	}

	private static void sherpa(String id, String language, String folder, int megabytes) {
		add(new Entry(id, language, folder, SHERPA + folder + ".tar.bz2", megabytes, Engine.SHERPA));
	}

	private static void add(Entry entry) {
		CATALOGUE.put(entry.id(), entry);
	}

	public static Map<String, Entry> catalogue() {
		return CATALOGUE;
	}

	public static Entry byId(String id) {
		return CATALOGUE.get(id);
	}

	/** Where models are unpacked: {@code config/voice_subtitles/models}. */
	public static Path modelsDirectory() {
		return FabricLoader.getInstance().getConfigDir().resolve("voice_subtitles").resolve("models");
	}

	public static Path pathOf(Entry entry) {
		return modelsDirectory().resolve(entry.folder());
	}

	/**
	 * A model counts as installed once the file the engine actually opens is
	 * there: an interrupted download leaves a directory behind, and a directory
	 * on its own proves nothing.
	 */
	public static boolean isInstalled(Entry entry) {
		Path path = pathOf(entry);
		if (!Files.isDirectory(path)) {
			return false;
		}
		return entry.engine() == Engine.VOSK
				? Files.isDirectory(path.resolve("am"))
				: Files.isRegularFile(path.resolve("tokens.txt"));
	}

	/**
	 * Downloads and unpacks a model.
	 *
	 * @param progress called with 0–100 as the download proceeds
	 * @throws Exception if the download or the unpacking fails; the partially
	 *                   written directory is left for inspection rather than
	 *                   silently deleted
	 */
	public static void download(Entry entry, Consumer<Integer> progress) throws Exception {
		Path models = modelsDirectory();
		Files.createDirectories(models);
		Path archive = models.resolve(entry.folder() + (entry.isTarBz2() ? ".tar.bz2" : ".zip"));

		Archives.download(entry.url(), archive, progress);
		if (entry.isTarBz2()) {
			Archives.extractTarBz2(archive, models);
		} else {
			Archives.extractZip(archive, models);
		}
		Files.deleteIfExists(archive);
		VoiceSubtitles.LOGGER.info("Model {} installed at {}", entry.id(), pathOf(entry));
	}
}
