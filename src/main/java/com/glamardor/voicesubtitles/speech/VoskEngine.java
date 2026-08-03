package com.glamardor.voicesubtitles.speech;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

/**
 * Vosk, the engine the mod shipped with.
 *
 * <p>Kaldi underneath: accurate for its size in the small models, but the good
 * Russian one is 1.8 GB and takes the better part of a minute to read into
 * memory, because loading it means building the whole decoding graph.
 *
 * <p>Vosk has no endpoint detection of its own that we can rely on here, so
 * {@link Stream#accept} only reports a sentence when Vosk itself decides one
 * finished; the silence timeout in {@link SpeechRecognition} does the rest.
 */
final class VoskEngine implements SpeechEngine {

	/** Sample rate the recogniser runs at. */
	private static final float RATE = 16_000F;

	private final String modelId;
	private final Model model;

	private VoskEngine(String modelId, Model model) {
		this.modelId = modelId;
		this.model = model;
	}

	/** Reads the model into memory. Slow for the large models; call off the render thread. */
	static VoskEngine load(SpeechModels.Entry entry) throws java.io.IOException {
		LibVosk.setLogLevel(LogLevel.WARNINGS);
		Model model = new Model(SpeechModels.pathOf(entry).toString());
		return new VoskEngine(entry.id(), model);
	}

	@Override
	public String modelId() {
		return modelId;
	}

	@Override
	public Stream open() {
		try {
			return new VoskStream(new Recognizer(model, RATE));
		} catch (Throwable t) {
			VoiceSubtitles.LOGGER.error("Could not start a Vosk recogniser", t);
			return null;
		}
	}

	@Override
	public void close() {
		try {
			model.close();
		} catch (Throwable ignored) {
		}
	}

	private static final class VoskStream implements Stream {

		private final Recognizer recognizer;

		VoskStream(Recognizer recognizer) {
			this.recognizer = recognizer;
		}

		@Override
		public String accept(short[] pcm16k) {
			byte[] pcm = Resampler.toBytes(pcm16k);
			return recognizer.acceptWaveForm(pcm, pcm.length)
					? field(recognizer.getResult(), "text")
					: null;
		}

		@Override
		public String partial() {
			return field(recognizer.getPartialResult(), "partial");
		}

		@Override
		public String settle() {
			String text = field(recognizer.getFinalResult(), "text");
			reset();
			return text;
		}

		private void reset() {
			try {
				recognizer.reset();
			} catch (Throwable ignored) {
				// An older Vosk without reset() leaves the recogniser as it is;
				// the worst case is a repeated word, not a broken caption.
			}
		}

		@Override
		public void close() {
			try {
				recognizer.close();
			} catch (Throwable ignored) {
			}
		}

		/** Vosk answers in JSON; this pulls one string field out of it. */
		private static String field(String json, String name) {
			try {
				JsonObject object = JsonParser.parseString(json).getAsJsonObject();
				return object.has(name) ? object.get(name).getAsString().trim() : "";
			} catch (Exception e) {
				return "";
			}
		}
	}
}
