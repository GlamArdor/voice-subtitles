package com.glamardor.voicesubtitles.speech;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import com.glamardor.voicesubtitles.translate.TranslationModels;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Whatever the mod was built with already inside it.
 *
 * <p>The ordinary jar is three megabytes and fetches the rest when it is first
 * needed, which is the right trade for most people: a recognition engine and a
 * model are a couple of hundred megabytes and nobody needs all of them. It stops
 * being the right trade where the download itself is the problem. Two of the
 * players this was written for spent the better part of an evening watching a
 * bar crawl across the screen, because GitHub and Hugging Face are reached
 * slowly, or not at all, from where they sit.
 *
 * <p>So there is a second jar with the engine and the Russian model packed into
 * it. It is the same mod – same code, same version, same everything – and this
 * class is the whole of the difference: on the first run it copies what it is
 * carrying into {@code config/voice_subtitles} and the rest of the mod finds it
 * already there, exactly as though it had been downloaded. Nothing is contacted,
 * nothing is checked against a hash, because nothing arrived over a wire.
 *
 * <p>In the ordinary jar this finds nothing and does nothing.
 */
public final class BundledAssets {

	/** Where a full build keeps its cargo, inside the jar. */
	private static final String ROOT = "bundled";

	private BundledAssets() {
	}

	/** True when this jar is carrying anything at all. */
	public static boolean present() {
		return root() != null;
	}

	/**
	 * Unpacks whatever is bundled, then carries on.
	 *
	 * @param next run once the cargo is in place – or straight away, on the
	 *             calling thread, when there is none
	 */
	public static void installThen(Runnable next) {
		Path root = root();
		if (root == null) {
			next.run();
			return;
		}
		List<Job> jobs = plan(root);
		if (jobs.isEmpty()) {
			// Everything it carries is already on disk: the usual case from the
			// second run onwards.
			next.run();
			return;
		}

		Thread thread = new Thread(() -> {
			try {
				install(jobs);
			} catch (Throwable t) {
				// A jar that cannot unpack itself is a mod that downloads its
				// model like any other, which is a slow evening rather than a
				// broken game.
				VoiceSubtitles.LOGGER.error("Could not unpack what the mod was built with", t);
			} finally {
				DownloadState.end();
			}
			try {
				next.run();
			} catch (Throwable t) {
				VoiceSubtitles.LOGGER.error("Speech recognition would not start", t);
			}
		}, "voice-subtitles-bundle");
		thread.setDaemon(true);
		thread.start();
	}

	/** One directory to copy out, and what it weighs. */
	private record Job(String what, Path from, Path to, long bytes, SpeechModels.Entry model) {
	}

	/**
	 * What still needs unpacking.
	 *
	 * <p>Read before anything is copied, so the bar knows how far it has to go.
	 */
	private static List<Job> plan(Path root) {
		List<Job> jobs = new ArrayList<>();
		try {
			// ── The engine, if the build in here is the one this machine wants ──
			SherpaNatives.Build build = SherpaNatives.buildForThisMachine();
			if (build != null && !SherpaNatives.isInstalled(build)) {
				Path source = root.resolve("native").resolve(build.folder());
				if (Files.isDirectory(source)) {
					jobs.add(new Job("sherpa-onnx " + build.platform(), source,
							SherpaNatives.directory().resolve(build.folder()), sizeOf(source), null));
				}
			}

			// ── Recognition models, named by the folder they unpack into ────────
			each(root.resolve("models"), folder -> {
				SpeechModels.Entry entry = SpeechModels.byFolder(folder.getFileName().toString());
				if (entry != null && !SpeechModels.isInstalled(entry)) {
					jobs.add(new Job("model " + entry.id(), folder,
							SpeechModels.pathOf(entry), sizeOf(folder), entry));
				}
			});

			// ── Translation models, named the same way: en-ru, ru-en ────────────
			each(root.resolve("translate"), folder -> {
				String id = folder.getFileName().toString();
				TranslationModels.Entry entry = TranslationModels.catalogue().get(id);
				if (entry != null && !TranslationModels.isInstalled(entry)) {
					jobs.add(new Job("translation " + id, folder,
							TranslationModels.pathOf(entry), sizeOf(folder), null));
				}
			});
		} catch (IOException e) {
			VoiceSubtitles.LOGGER.error("Could not read what the mod was built with", e);
		}
		return jobs;
	}

	private interface Each {
		void accept(Path folder) throws IOException;
	}

	private static void each(Path parent, Each action) throws IOException {
		if (!Files.isDirectory(parent)) {
			return;
		}
		try (Stream<Path> folders = Files.list(parent)) {
			for (Path folder : folders.toList()) {
				if (Files.isDirectory(folder)) {
					action.accept(folder);
				}
			}
		}
	}

	private static void install(List<Job> jobs) throws IOException {
		long total = jobs.stream().mapToLong(Job::bytes).sum();
		VoiceSubtitles.LOGGER.info("Unpacking {} MB the mod was built with: {}",
				total / 1048576L, jobs.stream().map(Job::what).toList());
		toast(Text.translatable("toast.voice_subtitles.bundle.installing"),
				Text.translatable("toast.voice_subtitles.bundle.size", total / 1048576L));

		// The same bar the downloads use, because from where the player sits it is
		// the same wait: the model is not ready yet and the game is working on it.
		DownloadState.unpacking(Text.translatable("voice_subtitles.bundle.unpacking"), total);

		long done = 0L;
		for (Job job : jobs) {
			done = copy(job.from(), job.to(), done, total);
			if (job.model() != null) {
				// The marker the rest of the mod reads as "all of it is there",
				// written last, exactly as the download path writes it.
				SpeechModels.markInstalled(job.model());
			}
			VoiceSubtitles.LOGGER.info("Unpacked {} into {}", job.what(), job.to());
		}
		toast(Text.translatable("toast.voice_subtitles.bundle.ready"),
				Text.translatable("toast.voice_subtitles.bundle.ready.detail"));
	}

	/**
	 * Copies a directory out of the jar.
	 *
	 * <p>Written beside the final name and moved into place at the end of each
	 * file, so a game closed halfway through cannot leave a half-written model
	 * that the next run mistakes for a whole one.
	 *
	 * @return how many bytes have been written in total, for the bar
	 */
	private static long copy(Path from, Path to, long done, long total) throws IOException {
		Files.createDirectories(to);
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path source : walk.toList()) {
				// Across two file systems – the jar's and the disk's – so the
				// relative path is turned back into text before it is resolved.
				Path target = to;
				for (Path part : from.relativize(source)) {
					target = target.resolve(part.toString());
				}
				if (Files.isDirectory(source)) {
					Files.createDirectories(target);
					continue;
				}
				Path partial = target.resolveSibling(target.getFileName() + ".part");
				Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
				Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
				done += Files.size(target);
				DownloadState.update(done, total);
			}
		}
		return done;
	}

	private static long sizeOf(Path folder) throws IOException {
		try (Stream<Path> walk = Files.walk(folder)) {
			return walk.filter(Files::isRegularFile).mapToLong(file -> {
				try {
					return Files.size(file);
				} catch (IOException e) {
					return 0L;
				}
			}).sum();
		}
	}

	/** The cargo hold inside this jar, or {@code null} in the ordinary build. */
	private static Path root() {
		return FabricLoader.getInstance().getModContainer(VoiceSubtitles.MOD_ID)
				.flatMap(container -> container.findPath(ROOT))
				.filter(Files::isDirectory)
				.orElse(null);
	}

	private static void toast(Text title, Text description) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> client.getToastManager().add(
				new SystemToast(SystemToast.Type.PERIODIC_NOTIFICATION, title, description)));
	}
}
