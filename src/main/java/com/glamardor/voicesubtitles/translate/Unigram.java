package com.glamardor.voicesubtitles.translate;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The SentencePiece side of a Marian translation model.
 *
 * <p>Text goes in, token numbers come out, and the other way round. The pieces
 * and their scores are read from the {@code tokenizer.json} that ships with the
 * model; the algorithm is Unigram, which means picking, out of every possible way
 * of cutting a word into known pieces, the one whose scores add up highest.
 *
 * <p>Written out rather than pulled in from a library because there is no such
 * library for the JVM that does not arrive with a native binding of its own, and
 * this is the whole of what these models need: no byte fallback, no character
 * map (this one is empty), just whitespace splitting and Viterbi.
 */
final class Unigram {

	/** SentencePiece's stand-in for a space. */
	private static final char SPACE = '▁';

	/** How much worse an unknown character is than the worst known piece. */
	private static final float UNK_PENALTY = 10F;

	private final String[] pieces;
	private final float[] scores;
	private final Map<String, Integer> ids;
	private final int longestPiece;
	private final int unkId;
	private final float unkScore;

	private Unigram(List<String> pieces, List<Float> scores, int unkId) {
		this.pieces = pieces.toArray(new String[0]);
		this.scores = new float[scores.size()];
		this.ids = new HashMap<>(pieces.size() * 2);

		int longest = 1;
		float worst = 0F;
		for (int i = 0; i < this.pieces.length; i++) {
			this.scores[i] = scores.get(i);
			this.ids.putIfAbsent(this.pieces[i], i);
			longest = Math.max(longest, this.pieces[i].length());
			worst = Math.min(worst, this.scores[i]);
		}
		this.longestPiece = longest;
		this.unkId = unkId;
		this.unkScore = worst - UNK_PENALTY;
	}

	/** Reads a HuggingFace {@code tokenizer.json}, keeping only the vocabulary. */
	static Unigram read(Path tokenizerJson) throws IOException {
		List<String> pieces = new ArrayList<>(64000);
		List<Float> scores = new ArrayList<>(64000);
		int unkId = -1;

		try (BufferedReader in = Files.newBufferedReader(tokenizerJson, StandardCharsets.UTF_8);
		     JsonReader json = new JsonReader(in)) {
			json.beginObject();
			while (json.hasNext()) {
				if (!"model".equals(json.nextName())) {
					json.skipValue();
					continue;
				}
				json.beginObject();
				while (json.hasNext()) {
					String field = json.nextName();
					if ("vocab".equals(field)) {
						json.beginArray();
						while (json.hasNext()) {
							json.beginArray();
							pieces.add(json.nextString());
							scores.add((float) json.nextDouble());
							json.endArray();
						}
						json.endArray();
					} else if ("unk_id".equals(field) && json.peek() == JsonToken.NUMBER) {
						unkId = json.nextInt();
					} else {
						json.skipValue();
					}
				}
				json.endObject();
			}
		}
		if (pieces.isEmpty()) {
			throw new IOException("No vocabulary in " + tokenizerJson);
		}

		// The piece called <unk> wins over the declared index: these files do not
		// always agree with themselves, and the name is the thing that is certain.
		int named = pieces.indexOf("<unk>");
		if (named >= 0) {
			unkId = named;
		} else if (unkId < 0 || unkId >= pieces.size()) {
			unkId = 0;
		}
		return new Unigram(pieces, scores, unkId);
	}

	int size() {
		return pieces.length;
	}

	/** @return the number of the piece with this exact text, or -1 */
	int idOf(String piece) {
		Integer id = ids.get(piece);
		return id == null ? -1 : id;
	}

	/**
	 * Turns text into token numbers.
	 *
	 * <p>Split on whitespace, each word prefixed with the space mark, each word
	 * cut up on its own. Nothing is added at the end: what goes there is the
	 * model's business, not the tokenizer's.
	 */
	long[] encode(String text) {
		List<Long> out = new ArrayList<>(text.length() / 3 + 8);
		for (String word : text.trim().split("\\s+")) {
			if (word.isEmpty()) {
				continue;
			}
			viterbi(SPACE + word, out);
		}
		long[] ids = new long[out.size()];
		for (int i = 0; i < ids.length; i++) {
			ids[i] = out.get(i);
		}
		return ids;
	}

	/**
	 * The best way of cutting one word into known pieces.
	 *
	 * <p>Best meaning highest total score, which is what Unigram means by best:
	 * every piece carries a log probability, and the cut that adds up highest is
	 * the one the model was trained to expect.
	 */
	private void viterbi(String word, List<Long> out) {
		int n = word.length();
		float[] best = new float[n + 1];
		int[] from = new int[n + 1];
		int[] token = new int[n + 1];
		java.util.Arrays.fill(best, Float.NEGATIVE_INFINITY);
		java.util.Arrays.fill(from, -1);
		best[0] = 0F;

		for (int i = 0; i < n; i++) {
			if (best[i] == Float.NEGATIVE_INFINITY) {
				continue;
			}
			boolean matched = false;
			int limit = Math.min(n, i + longestPiece);
			for (int end = i + 1; end <= limit; end++) {
				Integer id = ids.get(word.substring(i, end));
				if (id == null) {
					continue;
				}
				matched = true;
				float score = best[i] + scores[id];
				if (score > best[end]) {
					best[end] = score;
					from[end] = i;
					token[end] = id;
				}
			}
			if (!matched) {
				// A character no piece covers. One token of unknown, and on we go,
				// taking a surrogate pair together so a rare glyph is not split
				// down the middle into two halves that mean nothing.
				int end = i + (Character.isHighSurrogate(word.charAt(i)) && i + 1 < n ? 2 : 1);
				float score = best[i] + unkScore;
				if (score > best[end]) {
					best[end] = score;
					from[end] = i;
					token[end] = unkId;
				}
			}
		}

		if (best[n] == Float.NEGATIVE_INFINITY) {
			out.add((long) unkId);
			return;
		}
		// Walk the trail back to the start, then lay it out forwards.
		List<Integer> reversed = new ArrayList<>();
		for (int at = n; at > 0; at = from[at]) {
			reversed.add(token[at]);
		}
		for (int i = reversed.size() - 1; i >= 0; i--) {
			out.add((long) (int) reversed.get(i));
		}
	}

	/**
	 * Turns token numbers back into text.
	 *
	 * <p>Special tokens are dropped rather than printed: nobody wants to read
	 * {@code </s>} at the end of a sentence.
	 */
	String decode(List<Integer> tokens) {
		StringBuilder text = new StringBuilder();
		for (int id : tokens) {
			if (id < 0 || id >= pieces.length) {
				continue;
			}
			String piece = pieces[id];
			if (piece.length() > 2 && piece.charAt(0) == '<' && piece.charAt(piece.length() - 1) == '>') {
				continue;
			}
			text.append(piece);
		}
		// The space mark back into spaces, and the one the encoder added at the
		// front back off again.
		String out = text.toString().replace(SPACE, ' ');
		return out.startsWith(" ") ? out.substring(1) : out;
	}
}
