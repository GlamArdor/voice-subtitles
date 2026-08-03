package com.glamardor.voicesubtitles.speech;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * sherpa-onnx, the streaming alternative to Vosk.
 *
 * <p>ONNX Runtime underneath, so a model is a few hundred megabytes at most and
 * is ready seconds after it is asked for rather than the better part of a
 * minute. Streaming like Vosk, so words still appear as they are said.
 *
 * <p>Two kinds of model are understood, told apart by what is in the folder: a
 * zipformer transducer, which is an encoder, a decoder and a joiner, or a T-one
 * CTC model, which is a single file. Both come with a {@code tokens.txt}.
 */
final class SherpaEngine implements SpeechEngine {

	/** Recognition runs at 16 kHz, like everything else here. */
	private static final int RATE = 16_000;

	private final String modelId;
	private final OnlineRecognizer recognizer;

	private SherpaEngine(String modelId, OnlineRecognizer recognizer) {
		this.modelId = modelId;
		this.recognizer = recognizer;
	}

	/**
	 * Loads a model. Quick by Vosk standards, but still not render-thread work.
	 *
	 * @throws IllegalStateException if the native library is not loaded, which
	 *                               the caller is expected to have arranged
	 */
	static SherpaEngine load(SpeechModels.Entry entry) throws IOException {
		if (!SherpaNatives.load()) {
			throw new IllegalStateException("sherpa-onnx native library unavailable: " + SherpaNatives.failure());
		}
		Path dir = SpeechModels.pathOf(entry);
		Path tokens = dir.resolve("tokens.txt");
		if (!Files.isRegularFile(tokens)) {
			throw new IOException("No tokens.txt in " + dir);
		}

		OnlineModelConfig.Builder model = OnlineModelConfig.builder()
				.setTokens(tokens.toString())
				// One thread per speaker already; more would fight the game for
				// cores it needs to draw frames.
				.setNumThreads(1)
				.setDebug(false);

		Path single = find(dir, "model");
		if (single != null) {
			model.setToneCtc(OnlineToneCtcModelConfig.builder().setModel(single.toString()).build());
		} else {
			Path encoder = require(dir, "encoder");
			Path decoder = require(dir, "decoder");
			Path joiner = require(dir, "joiner");
			model.setTransducer(OnlineTransducerModelConfig.builder()
					.setEncoder(encoder.toString())
					.setDecoder(decoder.toString())
					.setJoiner(joiner.toString())
					.build());
		}

		OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
				.setOnlineModelConfig(model.build())
				.setFeatureConfig(FeatureConfig.builder().setSampleRate(RATE).setFeatureDim(80).build())
				// Its own endpoint detection is better than a timer: it knows the
				// difference between a pause for breath and the end of a thought.
				// The silence timeout upstream stays as a backstop.
				.setEndpointConfig(EndpointConfig.builder().build())
				.setEnableEndpoint(true)
				.setDecodingMethod("greedy_search")
				.build();

		OnlineRecognizer recognizer = new OnlineRecognizer(config);
		VoiceSubtitles.LOGGER.info("sherpa-onnx model '{}' loaded from {}", entry.id(), dir);
		return new SherpaEngine(entry.id(), recognizer);
	}

	/**
	 * The one {@code .onnx} file whose name starts with {@code prefix}.
	 *
	 * <p>Found by looking rather than by exact name: the same model ships as
	 * {@code encoder.onnx} and {@code encoder.int8.onnx} depending on whether it
	 * is the quantised build.
	 */
	private static Path find(Path dir, String prefix) throws IOException {
		// Fully qualified: this class inherits SpeechEngine.Stream, which would
		// otherwise shadow the one from java.util.stream.
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			List<Path> matches = files
					.filter(p -> {
						String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
						return name.startsWith(prefix) && name.endsWith(".onnx");
					})
					.sorted()
					.toList();
			return matches.isEmpty() ? null : matches.get(0);
		}
	}

	private static Path require(Path dir, String prefix) throws IOException {
		Path found = find(dir, prefix);
		if (found == null) {
			throw new IOException("No " + prefix + "*.onnx in " + dir);
		}
		return found;
	}

	@Override
	public String modelId() {
		return modelId;
	}

	@Override
	public SpeechEngine.Stream open() {
		try {
			return new SherpaStream(recognizer.createStream());
		} catch (Throwable t) {
			VoiceSubtitles.LOGGER.error("Could not start a sherpa-onnx stream", t);
			return null;
		}
	}

	@Override
	public void close() {
		try {
			recognizer.release();
		} catch (Throwable ignored) {
		}
	}

	private final class SherpaStream implements SpeechEngine.Stream {

		private final com.k2fsa.sherpa.onnx.OnlineStream stream;

		SherpaStream(com.k2fsa.sherpa.onnx.OnlineStream stream) {
			this.stream = stream;
		}

		@Override
		public String accept(short[] pcm16k) {
			stream.acceptWaveform(toFloat(pcm16k), RATE);
			while (recognizer.isReady(stream)) {
				recognizer.decode(stream);
			}
			if (!recognizer.isEndpoint(stream)) {
				return null;
			}
			// An endpoint means the speaker finished a thought, not that they
			// stopped talking; the stream carries straight on into the next one.
			String text = recognizer.getResult(stream).getText().trim();
			recognizer.reset(stream);
			return text;
		}

		@Override
		public String partial() {
			return recognizer.getResult(stream).getText().trim();
		}

		@Override
		public String settle() {
			try {
				// A little silence first: the encoder needs audio past the last
				// word before it will commit to it, and without this the final
				// word of a sentence is regularly the one that goes missing.
				stream.acceptWaveform(new float[RATE / 4], RATE);
				while (recognizer.isReady(stream)) {
					recognizer.decode(stream);
				}
				String text = recognizer.getResult(stream).getText().trim();
				recognizer.reset(stream);
				return text;
			} catch (Throwable t) {
				return "";
			}
		}

		@Override
		public void close() {
			try {
				stream.release();
			} catch (Throwable ignored) {
			}
		}

		/** sherpa-onnx wants samples as floats between -1 and 1. */
		private static float[] toFloat(short[] pcm) {
			float[] out = new float[pcm.length];
			for (int i = 0; i < pcm.length; i++) {
				out[i] = pcm[i] / 32768F;
			}
			return out;
		}
	}
}
