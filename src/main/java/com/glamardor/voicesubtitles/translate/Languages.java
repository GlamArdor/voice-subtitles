package com.glamardor.voicesubtitles.translate;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who speaks what, and what you read.
 *
 * <p>Automatic language detection is not attempted, and that is a decision
 * rather than an omission. It cannot be done on the text, because by the time
 * there is text the damage is done: a Russian model handed English speech writes
 * plausible-looking Cyrillic nonsense, and nothing downstream can tell that from
 * a badly recognised Russian sentence. Doing it on the audio means a third model
 * and a second pass over every utterance, to serve the one person in a hundred
 * who speaks something else.
 *
 * <p>So the mod is told instead. Everybody here is assumed to speak the same
 * language, and the exceptions are named – by hand, once, and remembered by
 * UUID so a change of roleplay name does not undo it.
 */
public final class Languages {

	private Languages() {
	}

	/**
	 * The language you read.
	 *
	 * <p>Follows the game's own language unless it has been set by hand, which is
	 * right nearly always and wrong for somebody who plays in English and reads
	 * Russian more comfortably.
	 */
	public static String mine() {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.myLanguage.isBlank()) {
			return cfg.myLanguage;
		}
		GameOptions options = MinecraftClient.getInstance().options;
		String language = options == null ? null : options.language;
		return language == null ? "en" : language.split("_")[0];
	}

	/** What somebody nobody has said anything about is assumed to be speaking. */
	public static String defaultSpoken() {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		return cfg.defaultSpokenLanguage.isBlank() ? mine() : cfg.defaultSpokenLanguage;
	}

	/**
	 * The language this player speaks.
	 *
	 * <p>With routing turned off, everybody speaks the default: there is only one
	 * model loaded, so saying otherwise would only mean sending audio nowhere.
	 */
	public static String spokenBy(UUID speaker) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.translationMode.routes() || speaker == null) {
			return defaultSpoken();
		}
		String named = cfg.speakerLanguages.get(speaker.toString());
		return named == null || named.isBlank() ? defaultSpoken() : named;
	}

	/**
	 * The languages there is a translation for, in the order they are offered.
	 *
	 * <p>Two of them: Russian and English, which is what OPUS-MT has been exported
	 * to ONNX for and what this mod was built for. The recognition catalogue knows
	 * ten, but a language that can be recognised and not translated is a caption in
	 * an alphabet you cannot read, and offering it as a choice is offering that.
	 */
	public static java.util.List<String> translatable() {
		java.util.List<String> codes = new java.util.ArrayList<>();
		for (TranslationModels.Entry entry : TranslationModels.catalogue().values()) {
			if (!codes.contains(entry.from())) {
				codes.add(entry.from());
			}
			if (!codes.contains(entry.to())) {
				codes.add(entry.to());
			}
		}
		return codes;
	}

	/**
	 * The languages a setting may be set to, in the order they are offered.
	 *
	 * <p>The empty string comes first and means "work it out": the game's language
	 * for what you read, and what you read for what everybody here speaks.
	 *
	 * <p>Whatever is already set is kept in the list even when it is neither of the
	 * two, so that a setting somebody chose by hand is not quietly changed to
	 * something else the next time they open the screen.
	 */
	public static String[] choices() {
		java.util.List<String> codes = new java.util.ArrayList<>();
		codes.add("");
		codes.addAll(translatable());
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		for (String set : new String[]{cfg.myLanguage, cfg.defaultSpokenLanguage}) {
			if (set != null && !set.isBlank() && !codes.contains(set)) {
				codes.add(set);
			}
		}
		return codes.toArray(new String[0]);
	}

	/** What a language code is called on screen. */
	public static net.minecraft.text.Text nameOf(String code) {
		if (code == null || code.isBlank()) {
			return net.minecraft.text.Text.translatable("voice_subtitles.language.auto");
		}
		return net.minecraft.text.Text.translatable("voice_subtitles.language." + code);
	}

	/** @return true when this player has been named as speaking something in particular */
	public static boolean isNamed(UUID speaker) {
		return speaker != null && VoiceSubtitlesConfig.get().speakerLanguages.containsKey(speaker.toString());
	}

	/** Marks a player as speaking a language; {@code null} forgets them again. */
	public static void set(UUID speaker, String language) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (language == null || language.isBlank()) {
			cfg.speakerLanguages.remove(speaker.toString());
		} else {
			cfg.speakerLanguages.put(speaker.toString(), language);
		}
		VoiceSubtitlesConfig.save();
	}

	/** @return everyone who has been named, as UUID text to language code */
	public static Map<String, String> named() {
		return new LinkedHashMap<>(VoiceSubtitlesConfig.get().speakerLanguages);
	}

	/**
	 * Every language that might have to be recognised right now.
	 *
	 * <p>The default plus whatever the named people speak. Two, in practice, and
	 * one on nearly every client.
	 */
	public static java.util.Set<String> expected() {
		java.util.Set<String> languages = new java.util.LinkedHashSet<>();
		languages.add(defaultSpoken());
		if (VoiceSubtitlesConfig.get().translationMode.routes()) {
			for (String language : VoiceSubtitlesConfig.get().speakerLanguages.values()) {
				if (language != null && !language.isBlank()) {
					languages.add(language);
				}
			}
		}
		return languages;
	}

	/**
	 * The translation this line needs, or {@code null} when it needs none.
	 *
	 * @return the model that turns what was said into what you read
	 */
	public static TranslationModels.Entry translationFor(String spoken) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.translationMode.translates()) {
			return null;
		}
		return TranslationModels.between(spoken, mine());
	}

	/**
	 * Whether a piece of written text has enough of another language in it to be
	 * worth translating.
	 *
	 * <p>Counted in whole words rather than letters, and asked as "is there a
	 * foreign sentence here" rather than "which alphabet wins". The difference
	 * matters on a roleplay server, where the alphabets are always mixed: a
	 * roleplay action reads
	 *
	 * <pre>Элиандрэль фон Сильверхейм farting and sniffing</pre>
	 *
	 * which is three Cyrillic words against three Latin ones. Counting letters
	 * called that a draw and left it alone, which is exactly how /me and /do went
	 * untranslated while ordinary chat did not: there the server marks who wrote
	 * the line, the name comes off as a prefix, and what is left is plainly one
	 * language.
	 *
	 * <p>One foreign word is enough when nothing native is left beside it: names
	 * have already been taken out by then, so "hello" and "/me smiles" are a
	 * whole foreign line and not a borrowed word. Where native words remain, two
	 * foreign ones are wanted and they must not be outnumbered – that is what
	 * keeps "го на спавн, там криперы" out of the translator.
	 *
	 * @return the language to translate from, or {@code null} when there is
	 *         nothing here that wants translating
	 */
	public static String foreignIn(String text) {
		boolean readCyrillic = usesCyrillic(mine());
		int foreign = 0;
		int native_ = 0;

		for (String word : text.split("[^\\p{L}]+")) {
			int cyrillic = 0;
			int latin = 0;
			for (int i = 0; i < word.length(); i++) {
				char c = word.charAt(i);
				if (c >= 'а' && c <= 'я' || c >= 'А' && c <= 'Я' || c == 'ё' || c == 'Ё') {
					cyrillic++;
				} else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
					latin++;
				}
			}
			if (cyrillic + latin < 2) {
				continue; // an initial, a stray letter: says nothing either way
			}
			// A word belongs to whichever alphabet it is written in, and to
			// neither if it is written in both.
			boolean isCyrillic = cyrillic > 0 && latin == 0;
			boolean isLatin = latin > 0 && cyrillic == 0;
			if (!isCyrillic && !isLatin) {
				continue;
			}
			if (isCyrillic == readCyrillic) {
				native_++;
			} else {
				foreign++;
			}
		}

		if (foreign == 0) {
			return null;
		}
		if (native_ > 0 && (foreign < 2 || foreign < native_)) {
			return null;
		}
		return readCyrillic ? "en" : "ru";
	}

	/** @return true when this language is written in Cyrillic. */
	private static boolean usesCyrillic(String code) {
		return "ru".equals(code) || "uk".equals(code) || "be".equals(code) || "bg".equals(code);
	}
}
