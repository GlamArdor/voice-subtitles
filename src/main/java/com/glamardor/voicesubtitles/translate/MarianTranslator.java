package com.glamardor.voicesubtitles.translate;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.glamardor.voicesubtitles.VoiceSubtitles;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One direction of translation, run on this machine.
 *
 * <p>The model is Marian, the shape every OPUS-MT model has: an encoder that
 * reads the whole sentence at once, and a decoder that writes the answer one
 * token at a time, each token seeing everything written before it. The decoder
 * is the merged export, meaning one graph that both starts from nothing and
 * carries on from a cache, chosen by a flag – half the size of shipping the two
 * separately, at the cost of having to hand it empty tensors on the first step.
 *
 * <p>Decoding is greedy: always the likeliest next token, never a search over
 * several possibilities at once. Marian's own settings ask for a beam of four,
 * which would be a little better and four times the work; on a machine that is
 * also running Minecraft, and for a caption that wants to arrive while the
 * speaker is still in the room, that is not the right trade.
 */
final class MarianTranslator implements AutoCloseable {

	/** Sentences longer than this are cut: nobody says 256 tokens in one breath. */
	private static final int MAX_SOURCE_TOKENS = 256;

	/** Ceiling on the answer, in case the decoder never says it is done. */
	private static final int MAX_TARGET_TOKENS = 256;

	private final OrtEnvironment env;
	private final OrtSession encoder;
	private final OrtSession decoder;
	private final Unigram tokenizer;

	private final int layers;
	private final int heads;
	private final int headDim;
	private final long decoderStart;
	private final long eos;

	private MarianTranslator(OrtEnvironment env, OrtSession encoder, OrtSession decoder, Unigram tokenizer,
	                         int layers, int heads, int headDim, long decoderStart, long eos) {
		this.env = env;
		this.encoder = encoder;
		this.decoder = decoder;
		this.tokenizer = tokenizer;
		this.layers = layers;
		this.heads = heads;
		this.headDim = headDim;
		this.decoderStart = decoderStart;
		this.eos = eos;
	}

	/**
	 * Reads a model off disk.
	 *
	 * <p>One thread per session on purpose. Recognition is already running on
	 * this machine, and the game is the thing the player is actually looking at:
	 * a translator that grabs every core to save eighty milliseconds is not worth
	 * the stutter.
	 */
	static MarianTranslator load(Path directory) throws OrtException, java.io.IOException {
		OrtEnvironment env = OrtEnvironment.getEnvironment();

		OrtSession.SessionOptions options = new OrtSession.SessionOptions();
		options.setIntraOpNumThreads(1);
		options.setInterOpNumThreads(1);
		options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

		OrtSession encoder = env.createSession(directory.resolve("encoder.onnx").toString(), options);
		OrtSession decoder = env.createSession(directory.resolve("decoder.onnx").toString(), options);
		Unigram tokenizer = Unigram.read(directory.resolve("tokenizer.json"));

		int layers = 0;
		for (String name : decoder.getInputNames()) {
			if (name.startsWith("past_key_values.") && name.endsWith(".decoder.key")) {
				layers++;
			}
		}
		if (layers == 0) {
			throw new java.io.IOException("The decoder in " + directory + " has no key/value cache inputs");
		}

		// Heads and head width are fixed in the graph, so ask it rather than
		// carrying a third config file around to say the same thing.
		long[] shape = ((TensorInfo) decoder.getInputInfo()
				.get("past_key_values.0.decoder.key").getInfo()).getShape();
		int heads = (int) shape[1];
		int headDim = (int) shape[3];

		long[] special = readSpecialTokens(directory.resolve("generation_config.json"), tokenizer);

		VoiceSubtitles.LOGGER.info("Translation model at {} loaded: {} layers, {} heads of {}, vocabulary {}",
				directory.getFileName(), layers, heads, headDim, tokenizer.size());
		return new MarianTranslator(env, encoder, decoder, tokenizer, layers, heads, headDim, special[0], special[1]);
	}

	/**
	 * Which token starts the answer and which ends it.
	 *
	 * <p>Read from the model's own generation settings, falling back to the names
	 * in the vocabulary. Marian starts its decoder on the padding token, which
	 * looks like a mistake and is not.
	 */
	private static long[] readSpecialTokens(Path generationConfig, Unigram tokenizer) {
		long start = tokenizer.idOf("<pad>");
		long end = tokenizer.idOf("</s>");
		try {
			if (Files.isRegularFile(generationConfig)) {
				com.google.gson.JsonObject json = com.google.gson.JsonParser
						.parseString(Files.readString(generationConfig)).getAsJsonObject();
				if (json.has("decoder_start_token_id")) {
					start = json.get("decoder_start_token_id").getAsLong();
				}
				if (json.has("eos_token_id")) {
					end = json.get("eos_token_id").getAsLong();
				}
			}
		} catch (Exception e) {
			VoiceSubtitles.LOGGER.warn("Could not read {}; going with the vocabulary's own tokens", generationConfig, e);
		}
		return new long[]{Math.max(start, 0L), Math.max(end, 0L)};
	}

	/**
	 * Translates one sentence.
	 *
	 * <p>Synchronised because a session is cheap to share and expensive to
	 * duplicate: two translations at once would double the memory for no gain on
	 * a single-threaded session.
	 */
	synchronized String translate(String text) throws OrtException {
		long[] source = source(text);
		if (source.length <= 1) {
			return "";
		}
		long[] ones = new long[source.length];
		java.util.Arrays.fill(ones, 1L);
		long[] shape = {1, source.length};

		try (OnnxTensor sourceIds = OnnxTensor.createTensor(env, LongBuffer.wrap(source), shape);
		     OnnxTensor mask = OnnxTensor.createTensor(env, LongBuffer.wrap(ones), shape);
		     OrtSession.Result encoded = encoder.run(Map.of("input_ids", sourceIds, "attention_mask", mask))) {

			OnnxTensor hidden = (OnnxTensor) encoded.get("last_hidden_state")
					.orElseThrow(() -> new OrtException("The encoder produced no last_hidden_state"));
			return decode(hidden, mask, source.length);
		}
	}

	/** The sentence as token numbers, cut to length and closed with the end marker. */
	private long[] source(String text) {
		long[] tokens = tokenizer.encode(text);
		int length = Math.min(tokens.length, MAX_SOURCE_TOKENS);
		long[] source = java.util.Arrays.copyOf(tokens, length + 1);
		source[length] = eos;
		return source;
	}

	/** The token-at-a-time half: run the decoder until it says the sentence is done. */
	private String decode(OnnxTensor hidden, OnnxTensor mask, int sourceLength) throws OrtException {
		List<Integer> produced = new ArrayList<>();
		List<OnnxTensor> ours = new ArrayList<>();
		OrtSession.Result previous = null;

		// One tensor each for "start from nothing" and "carry on", made once and
		// handed to every step: the flag is what picks the branch inside the graph.
		OnnxTensor fromScratch = OnnxTensor.createTensor(env, new boolean[]{false});
		OnnxTensor carryOn = OnnxTensor.createTensor(env, new boolean[]{true});
		ours.add(fromScratch);
		ours.add(carryOn);

		// The empty cache the first step is fed. Its length is zero, which is the
		// whole point: nothing has been written yet. From a buffer rather than a
		// Java array, since an array cannot have a dimension of nothing.
		OnnxTensor emptyCache = OnnxTensor.createTensor(env, FloatBuffer.allocate(0),
				new long[]{1, heads, 0, headDim});
		ours.add(emptyCache);

		// What the decoder made of the encoder's output, kept for the whole
		// sentence. It has to be our own copy: on every step after the first the
		// graph hands these straight back rather than recomputing them, and what
		// it hands back points into the previous step's memory. Freeing that – as
		// the loop below does, one step behind – then leaves the step after it
		// reading something that is no longer there.
		Map<String, OnnxTensor> crossAttention = new LinkedHashMap<>();

		try {
			long token = decoderStart;
			int limit = Math.min(MAX_TARGET_TOKENS, sourceLength * 2 + 16);

			for (int step = 0; step < limit; step++) {
				OnnxTensor stepIds = OnnxTensor.createTensor(env,
						LongBuffer.wrap(new long[]{token}), new long[]{1, 1});

				Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
				inputs.put("input_ids", stepIds);
				inputs.put("encoder_attention_mask", mask);
				inputs.put("encoder_hidden_states", hidden);
				inputs.put("use_cache_branch", previous == null ? fromScratch : carryOn);
				for (int layer = 0; layer < layers; layer++) {
					for (String half : new String[]{"key", "value"}) {
						String decoderSide = layer + ".decoder." + half;
						String encoderSide = layer + ".encoder." + half;
						inputs.put("past_key_values." + decoderSide,
								previous == null ? emptyCache : cacheOf(previous, "present." + decoderSide));
						inputs.put("past_key_values." + encoderSide,
								previous == null ? emptyCache : crossAttention.get(encoderSide));
					}
				}

				OrtSession.Result current = decoder.run(inputs);
				stepIds.close();
				if (previous == null) {
					keepCrossAttention(current, crossAttention, ours);
				} else {
					// Only now: its tensors were this step's cache inputs.
					previous.close();
				}
				previous = current;

				token = likeliest(current);
				if (token == eos) {
					break;
				}
				produced.add((int) token);
				if (looping(produced)) {
					break;
				}
			}
			return tokenizer.decode(produced).trim();
		} finally {
			if (previous != null) {
				previous.close();
			}
			for (OnnxTensor tensor : ours) {
				tensor.close();
			}
		}
	}

	/**
	 * Takes our own copy of the cross-attention cache from the first step.
	 *
	 * <p>Half a megabyte for a long sentence, and it saves recomputing the whole
	 * of the encoder side on every token.
	 */
	private void keepCrossAttention(OrtSession.Result first, Map<String, OnnxTensor> into,
	                                List<OnnxTensor> ours) throws OrtException {
		for (int layer = 0; layer < layers; layer++) {
			for (String half : new String[]{"key", "value"}) {
				String tail = layer + ".encoder." + half;
				OnnxTensor source = cacheOf(first, "present." + tail);
				FloatBuffer from = source.getFloatBuffer();
				FloatBuffer copy = FloatBuffer.allocate(from.remaining());
				copy.put(from);
				copy.rewind();
				OnnxTensor own = OnnxTensor.createTensor(env, copy, source.getInfo().getShape());
				into.put(tail, own);
				ours.add(own);
			}
		}
	}

	private static OnnxTensor cacheOf(OrtSession.Result result, String name) throws OrtException {
		Optional<OnnxValue> value = result.get(name);
		if (value.isEmpty()) {
			throw new OrtException("The decoder produced no " + name);
		}
		return (OnnxTensor) value.get();
	}

	/** The likeliest next token, out of the sixty thousand the model could pick. */
	private static long likeliest(OrtSession.Result result) throws OrtException {
		FloatBuffer logits = ((OnnxTensor) result.get("logits")
				.orElseThrow(() -> new OrtException("The decoder produced no logits"))).getFloatBuffer();
		int best = 0;
		float bestScore = Float.NEGATIVE_INFINITY;
		for (int i = 0, n = logits.remaining(); i < n; i++) {
			float score = logits.get(i);
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	/**
	 * True when the decoder has started going round in circles.
	 *
	 * <p>Greedy decoding does this now and then: it picks the likeliest token,
	 * which brings back the state that made it likely, and the same three words
	 * repeat until the length limit. Better to stop and show what there is.
	 */
	private static boolean looping(List<Integer> produced) {
		int n = produced.size();
		if (n < 12) {
			return false;
		}
		for (int i = 0; i < 4; i++) {
			if (!produced.get(n - 1 - i).equals(produced.get(n - 5 - i))) {
				return false;
			}
		}
		return true;
	}

	@Override
	public void close() {
		try {
			encoder.close();
		} catch (Exception ignored) {
		}
		try {
			decoder.close();
		} catch (Exception ignored) {
		}
	}
}
