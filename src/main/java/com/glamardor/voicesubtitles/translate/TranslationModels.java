package com.glamardor.voicesubtitles.translate;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import com.glamardor.voicesubtitles.speech.Archives;
import com.glamardor.voicesubtitles.speech.DownloadState;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The translation models, and where they live on disk.
 *
 * <p>One model per direction. Everybody needs exactly one: what you read is what
 * you read, and the direction is decided by which language the other person is
 * speaking. On a Russian server an English player fetches Russian into English
 * and nothing else.
 *
 * <p>These are OPUS-MT by the University of Helsinki, exported to ONNX and
 * quantised to eight bits. A hundred and thirteen megabytes each, which is a
 * quarter of what the speech model beside them costs.
 *
 * <p>Pinned to a particular revision of each repository rather than to whatever
 * is there today: a model that quietly changes underneath the mod is a model
 * that quietly starts producing different captions.
 */
public final class TranslationModels {

	/**
	 * One direction.
	 *
	 * @param from      language spoken
	 * @param to        language read
	 * @param revision  the commit these files were taken from
	 * @param megabytes total download, for telling the player what they are in for
	 */
	public record Entry(String from, String to, String repository, String revision, int megabytes) {

		/** {@code en-ru}: the id used in settings, on disk and in commands. */
		public String id() {
			return from + "-" + to;
		}

		String urlOf(String file) {
			return "https://huggingface.co/" + repository + "/resolve/" + revision + "/" + file;
		}
	}

	/**
	 * What each file is called once it is unpacked.
	 *
	 * <p>Renamed on the way in. The published names say how they were exported,
	 * which stops being interesting the moment they are on disk, and a folder that
	 * reads {@code encoder.onnx / decoder.onnx / tokenizer.json} is one anybody can
	 * look at and understand.
	 */
	private static final Map<String, String> FILES = new LinkedHashMap<>();

	static {
		FILES.put("onnx/encoder_model_quantized.onnx", "encoder.onnx");
		FILES.put("onnx/decoder_model_merged_quantized.onnx", "decoder.onnx");
		FILES.put("tokenizer.json", "tokenizer.json");
		FILES.put("generation_config.json", "generation_config.json");
	}

	private static final Map<String, Entry> CATALOGUE = new LinkedHashMap<>();

	static {
		add(new Entry("en", "ru", "Xenova/opus-mt-en-ru", "e050376e960175e8ddc5cff85025dc8436cddd68", 113));
		add(new Entry("ru", "en", "Xenova/opus-mt-ru-en", "afe8c6c738ec81b6d033fd8f44f9678a639a7c67", 113));
	}

	private TranslationModels() {
	}

	private static void add(Entry entry) {
		CATALOGUE.put(entry.id(), entry);
	}

	public static Map<String, Entry> catalogue() {
		return CATALOGUE;
	}

	/** @return the model that turns {@code from} into {@code to}, or {@code null} if there is none */
	public static Entry between(String from, String to) {
		if (from == null || to == null || from.equals(to)) {
			return null;
		}
		return CATALOGUE.get(from + "-" + to);
	}

	/** Where models are unpacked: {@code config/voice_subtitles/translate}. */
	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve("voice_subtitles").resolve("translate");
	}

	public static Path pathOf(Entry entry) {
		return directory().resolve(entry.id());
	}

	/** Installed means every file is there and none of them is empty. */
	public static boolean isInstalled(Entry entry) {
		if (entry == null) {
			return false;
		}
		Path path = pathOf(entry);
		for (String name : FILES.values()) {
			Path file = path.resolve(name);
			try {
				if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
					return false;
				}
			} catch (IOException e) {
				return false;
			}
		}
		return true;
	}

	/** Throws a model away, so the next attempt fetches a whole one. */
	public static void discard(Entry entry) {
		Path path = pathOf(entry);
		try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
				try {
					Files.deleteIfExists(file);
				} catch (IOException ignored) {
				}
			});
			VoiceSubtitles.LOGGER.warn("Threw away the translation model at {}", path);
		} catch (IOException e) {
			VoiceSubtitles.LOGGER.warn("Could not throw away the translation model at {}", path, e);
		}
	}

	/**
	 * Fetches one direction.
	 *
	 * <p>Downloaded beside the final name and moved into place at the end, so an
	 * interrupted download cannot leave behind a half a model that looks complete
	 * enough to load and then fails in the middle of a conversation.
	 */
	public static void download(Entry entry, Archives.Progress progress) throws Exception {
		Path path = pathOf(entry);
		Files.createDirectories(path);

		// The four files as one bar: four bars in a row, each starting at zero,
		// reads as three false finishes.
		long expected = (long) entry.megabytes() * 1024L * 1024L;
		long done = 0L;

		for (Map.Entry<String, String> file : FILES.entrySet()) {
			Path partial = path.resolve(file.getValue() + ".part");
			long base = done;
			Archives.download(entry.urlOf(file.getKey()), partial,
					(read, total) -> progress.update(base + read, expected));
			done += Files.size(partial);
			Files.move(partial, path.resolve(file.getValue()),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}

		DownloadState.installing();
		VoiceSubtitles.LOGGER.info("Translation model {} installed at {}", entry.id(), path);
	}
}
