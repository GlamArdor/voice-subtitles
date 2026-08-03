package com.glamardor.voicesubtitles.speech;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The catalogue of offline recognition models, and where they live on disk.
 *
 * <p>Models are downloaded on demand rather than shipped: the small Russian one
 * alone is bigger than everything else in this mod put together, and most
 * players need exactly one language.
 */
public final class VoskModels {

	/** One downloadable model. */
	public record Entry(String id, String language, String folder, String url, int megabytes) {
	}

	private static final Map<String, Entry> CATALOGUE = new LinkedHashMap<>();

	static {
		add(new Entry("ru", "Русский", "vosk-model-small-ru-0.22",
				"https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 45));
		add(new Entry("en", "English", "vosk-model-small-en-us-0.15",
				"https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40));

		// The heavy ones: markedly more accurate, but they want a couple of
		// gigabytes of memory to themselves on top of the game.
		add(new Entry("ru-large", "Русский (большая)", "vosk-model-ru-0.42",
				"https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip", 1800));
		add(new Entry("en-large", "English (large)", "vosk-model-en-us-0.22",
				"https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip", 1800));
		add(new Entry("ja", "日本語", "vosk-model-small-ja-0.22",
				"https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip", 48));
		add(new Entry("uk", "Українська", "vosk-model-small-uk-v3-small",
				"https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-small.zip", 133));
		add(new Entry("de", "Deutsch", "vosk-model-small-de-0.15",
				"https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", 45));
		add(new Entry("fr", "Français", "vosk-model-small-fr-0.22",
				"https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", 41));
		add(new Entry("es", "Español", "vosk-model-small-es-0.42",
				"https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip", 39));
		add(new Entry("pl", "Polski", "vosk-model-small-pl-0.22",
				"https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip", 50));
		add(new Entry("it", "Italiano", "vosk-model-small-it-0.22",
				"https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip", 48));
	}

	private VoskModels() {
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

	/** A model counts as installed once its acoustic model directory is there. */
	public static boolean isInstalled(Entry entry) {
		Path path = pathOf(entry);
		return Files.isDirectory(path) && Files.isDirectory(path.resolve("am"));
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
		Path archive = models.resolve(entry.folder() + ".zip");

		HttpURLConnection connection = (HttpURLConnection) URI.create(entry.url()).toURL().openConnection();
		connection.setConnectTimeout(15_000);
		connection.setReadTimeout(30_000);
		connection.setRequestProperty("User-Agent", "voice-subtitles-mod");

		long total = connection.getContentLengthLong();
		long read = 0L;
		int lastReported = -1;

		try (InputStream in = connection.getInputStream();
		     OutputStream out = Files.newOutputStream(archive)) {
			byte[] buffer = new byte[64 * 1024];
			int count;
			while ((count = in.read(buffer)) > 0) {
				out.write(buffer, 0, count);
				read += count;
				if (total > 0L) {
					int percent = (int) (read * 100L / total);
					if (percent / 5 != lastReported / 5) {
						lastReported = percent;
						progress.accept(percent);
					}
				}
			}
		}

		unzip(archive, models);
		Files.deleteIfExists(archive);
		VoiceSubtitles.LOGGER.info("Model {} installed at {}", entry.id(), pathOf(entry));
	}

	private static void unzip(Path archive, Path target) throws Exception {
		Path root = target.toAbsolutePath().normalize();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				Path destination = root.resolve(entry.getName()).normalize();
				// Refuse entries that would escape the models directory.
				if (!destination.startsWith(root)) {
					throw new IllegalStateException("Zip entry outside the models directory: " + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(zip, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				zip.closeEntry();
			}
		}
	}
}
