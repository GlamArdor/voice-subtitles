package com.glamardor.voicesubtitles.translate;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import com.glamardor.voicesubtitles.speech.SherpaNatives;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;

/**
 * Getting ONNX Runtime's Java binding to run, without downloading it.
 *
 * <p>Translation needs the same runtime the speech models already use. The
 * published Java package carries that runtime built for five platforms, 41 MB of
 * it, and the mod would be downloading a second copy of a library sitting on
 * disk. So instead:
 *
 * <ul>
 *   <li>the Java API is copied into the mod at build time, without its natives;
 *   <li>the JNI shim – 90 KB, the piece that binds the runtime to Java and the
 *       only thing sherpa-onnx does not already provide – rides along inside the
 *       mod, one per platform, and is unpacked on first use;
 *   <li>the runtime itself is the one sherpa-onnx downloaded.
 * </ul>
 *
 * <p>That last point is why {@code onnxruntime_version} in the build has to stay
 * in step with {@link SherpaNatives#VERSION}: the shim is built against a
 * particular runtime version and will not bind to one that has moved on.
 *
 * <p>Two platforms have no build at all, because ONNX Runtime publishes none:
 * Windows on ARM, and Intel Macs. Recognition still works there; translation
 * says so and stops.
 */
public final class OrtNatives {

	/** Platform key to the shim's file name, as the build task laid them out. */
	private static final Map<String, String> SHIMS = Map.of(
			"win-x64", "onnxruntime4j_jni.dll",
			"linux-x64", "libonnxruntime4j_jni.so",
			"linux-aarch64", "libonnxruntime4j_jni.so",
			"osx-aarch64", "libonnxruntime4j_jni.dylib");

	private static volatile boolean loaded;
	private static volatile String failure = "";

	private OrtNatives() {
	}

	public static boolean isLoaded() {
		return loaded;
	}

	public static String failure() {
		return failure;
	}

	/** @return the platform key for this machine, or {@code null} if there is none */
	public static String platform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm64");

		if (os.contains("win")) {
			return arm ? null : "win-x64";
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return arm ? "osx-aarch64" : null;
		}
		if (os.contains("nux") || os.contains("nix")) {
			return arm ? "linux-aarch64" : "linux-x64";
		}
		return null;
	}

	public static boolean isSupported() {
		return platform() != null;
	}

	/** Where the unpacked shim lives: {@code config/voice_subtitles/native/onnxruntime4j}. */
	private static Path directory() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve("voice_subtitles").resolve("native").resolve("onnxruntime4j");
	}

	/**
	 * Brings ONNX Runtime up. Once only: a JVM cannot unload a native library, so
	 * a second attempt after a failure would fail the same way.
	 *
	 * @return true when translation models can be run
	 */
	public static synchronized boolean load() {
		if (loaded) {
			return true;
		}
		if (!failure.isEmpty()) {
			return false;
		}

		String platform = platform();
		if (platform == null) {
			failure = "ONNX Runtime has no build for " + System.getProperty("os.name")
					+ " / " + System.getProperty("os.arch");
			return false;
		}

		// The runtime itself. sherpa-onnx has to be installed and loaded first,
		// both because that is where the file comes from and because loading it
		// twice from two places is asking for trouble.
		if (!SherpaNatives.load()) {
			failure = "the speech engine's library is not loaded: " + SherpaNatives.failure();
			return false;
		}
		Path runtime = SherpaNatives.onnxRuntimeLibrary();
		if (runtime == null) {
			failure = "onnxruntime is not on disk; download a sherpa-onnx model first";
			return false;
		}

		try {
			Path shim = unpackShim(platform);

			// Told exactly where both libraries are, ONNX Runtime's loader skips
			// looking for them on the classpath, which is where they are not.
			System.setProperty("onnxruntime.native.onnxruntime.path", runtime.toAbsolutePath().toString());
			System.setProperty("onnxruntime.native.onnxruntime4j_jni.path", shim.toAbsolutePath().toString());

			// Touching the environment is what forces the load, and the only way
			// to find out whether any of the above was right.
			ai.onnxruntime.OrtEnvironment.getEnvironment();

			loaded = true;
			VoiceSubtitles.LOGGER.info("ONNX Runtime loaded: runtime {}, binding {}", runtime, shim);
			return true;
		} catch (Throwable t) {
			// UnsatisfiedLinkError as much as anything else: a runtime a version
			// out of step, a CPU without the instructions, an antivirus that ate
			// the file. None of it may take the game down.
			failure = t.getClass().getSimpleName() + ": " + t.getMessage();
			VoiceSubtitles.LOGGER.error("Could not load ONNX Runtime; translation is off", t);
			return false;
		}
	}

	/**
	 * Puts this platform's JNI shim on disk, where a native loader can reach it.
	 *
	 * <p>Written next to the other libraries rather than into a temporary folder,
	 * so it survives a restart and can be seen by anybody who wants to know what
	 * the mod has put on their machine.
	 */
	private static Path unpackShim(String platform) throws Exception {
		String name = SHIMS.get(platform);
		Path target = directory().resolve(platform).resolve(name);
		if (Files.isRegularFile(target) && Files.size(target) > 0L) {
			return target;
		}
		Files.createDirectories(target.getParent());

		String resource = "/assets/voice_subtitles/onnxruntime/" + platform + "/" + name;
		try (InputStream in = OrtNatives.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("The mod was built without the ONNX Runtime binding for " + platform);
			}
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}
}
