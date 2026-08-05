package com.glamardor.voicesubtitles.speech;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The native library Vosk runs on, fetched on demand.
 *
 * <p>Vosk publishes its Java side and its native side in one 25 MB jar, of which
 * five small classes are Java and everything else is the same library built for
 * Windows, Linux and macOS. Shipping that inside the mod meant every player
 * carried three platforms' worth of a library most of them will never load: the
 * mod was 27 MB, and 24 of those were this.
 *
 * <p>So the five classes are kept (see {@code src/main/java/org/vosk}) and the
 * library is downloaded when a Vosk model is actually chosen, the same way the
 * sherpa-onnx one is. The archive is checked against a hash pinned here first,
 * because this is executable code rather than data.
 */
public final class VoskNatives {

	public static final String VERSION = "0.3.45";

	private static final String URL =
			"https://repo1.maven.org/maven2/com/alphacephei/vosk/" + VERSION + "/vosk-" + VERSION + ".jar";

	/** Taken from Maven Central; the archive is refused if it does not match. */
	private static final String SHA256 = "9e38e96e4448a41d889bb254b2f8424945554945c6e77e1cd97a3d8633fac2ba";

	/** About what the jar weighs, for telling the player what they are in for. */
	public static final int MEGABYTES = 25;

	/**
	 * What to pull out of the jar for each platform.
	 *
	 * <p>Windows needs three more than the library itself: it is built with GCC
	 * and carries its runtime with it.
	 */
	private static final List<String> WINDOWS = List.of(
			"win32-x86-64/libwinpthread-1.dll",
			"win32-x86-64/libgcc_s_seh-1.dll",
			"win32-x86-64/libstdc++-6.dll",
			"win32-x86-64/libvosk.dll");
	private static final List<String> LINUX = List.of("linux-x86-64/libvosk.so");
	private static final List<String> MACOS = List.of("darwin/libvosk.dylib");

	private static volatile boolean loaded;
	private static volatile String failure = "";

	private VoskNatives() {
	}

	/** Where the library is unpacked: {@code config/voice_subtitles/native/vosk}. */
	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve("voice_subtitles").resolve("native").resolve("vosk");
	}

	/** @return the files this platform needs, or {@code null} if it has none */
	private static List<String> filesForThisMachine() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean sixtyFour = arch.contains("64");
		if (!sixtyFour) {
			return null;
		}
		if (os.contains("win")) {
			return WINDOWS;
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return MACOS;
		}
		if (os.contains("nux") || os.contains("nix")) {
			return arch.contains("aarch64") || arch.contains("arm") ? null : LINUX;
		}
		return null;
	}

	public static boolean isSupported() {
		return filesForThisMachine() != null;
	}

	public static boolean isInstalled() {
		List<String> files = filesForThisMachine();
		if (files == null) {
			return false;
		}
		Path dir = directory();
		return files.stream().allMatch(entry -> Files.isRegularFile(dir.resolve(nameOf(entry))));
	}

	public static boolean isLoaded() {
		return loaded;
	}

	public static String failure() {
		return failure;
	}

	private static String nameOf(String entry) {
		return entry.substring(entry.lastIndexOf('/') + 1);
	}

	/** Downloads the jar and keeps only the pieces this platform will load. */
	public static void download(Archives.Progress progress) throws Exception {
		List<String> files = filesForThisMachine();
		if (files == null) {
			throw new IOException("Vosk has no build for this platform");
		}
		Path dir = directory();
		Files.createDirectories(dir);
		Path archive = dir.resolve("vosk-" + VERSION + ".jar");

		Archives.download(URL, archive, progress);
		DownloadState.installing();
		String actual = Archives.sha256(archive);
		if (!actual.equalsIgnoreCase(SHA256)) {
			Files.deleteIfExists(archive);
			throw new IOException("Checksum mismatch for vosk-" + VERSION + ".jar: expected " + SHA256
					+ ", got " + actual);
		}

		long unpacked = 0L;
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			for (String wanted : files) {
				ZipEntry entry = zip.getEntry(wanted);
				if (entry == null) {
					throw new IOException("No " + wanted + " inside the Vosk archive");
				}
				try (InputStream in = zip.getInputStream(entry);
				     OutputStream out = Files.newOutputStream(dir.resolve(nameOf(wanted)))) {
					unpacked += in.transferTo(out);
					progress.update(unpacked, -1L);
				}
			}
		}
		// The other two platforms' copies are of no use here; keeping them would
		// put back most of the weight this whole arrangement exists to avoid.
		Files.deleteIfExists(archive);
		VoiceSubtitles.LOGGER.info("Vosk {} native library installed at {}", VERSION, dir);
	}

	/**
	 * Loads the library into the JVM.
	 *
	 * @return true when Vosk can be used
	 */
	public static synchronized boolean load() {
		if (loaded) {
			return true;
		}
		if (!failure.isEmpty()) {
			return false;
		}
		List<String> files = filesForThisMachine();
		if (files == null) {
			failure = "Vosk has no build for " + System.getProperty("os.name") + " / " + System.getProperty("os.arch");
			return false;
		}
		if (!isInstalled()) {
			failure = "the Vosk native library is not installed";
			return false;
		}

		Path dir = directory();
		try {
			// In the order given, because on Windows the library is built against
			// the three that come before it and the loader will not go looking.
			for (String entry : files) {
				System.load(dir.resolve(nameOf(entry)).toAbsolutePath().toString());
			}
			// Told where they are as well, since JNA binds by name rather than by
			// the handle we have just opened.
			String existing = System.getProperty("jna.library.path", "");
			String path = dir.toAbsolutePath().toString();
			if (!existing.contains(path)) {
				System.setProperty("jna.library.path", existing.isEmpty() ? path : existing + java.io.File.pathSeparator + path);
			}
			loaded = true;
			VoiceSubtitles.LOGGER.info("Vosk {} loaded from {}", VERSION, dir);
			return true;
		} catch (Throwable t) {
			failure = t.getClass().getSimpleName() + ": " + t.getMessage();
			VoiceSubtitles.LOGGER.error("Could not load the Vosk native library", t);
			return false;
		}
	}
}
