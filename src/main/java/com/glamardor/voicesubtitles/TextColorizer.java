package com.glamardor.voicesubtitles;

import net.minecraft.text.MutableText;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

/**
 * Applies a speaker's colour to a name the server may have already styled.
 *
 * <p>Two things fight back here. A tab name is a tree of pieces, each with its
 * own style, and a child's own colour always beats one set on the root – so the
 * colour has to be pushed down the whole tree rather than set once at the top.
 * And servers frequently colour names with legacy section codes embedded in the
 * text itself ({@code §e[4] §fИмя}) instead of styles; those are applied while
 * the string is being drawn, so they override whatever style the piece carries.
 * That is why a plain username recolours happily while a server-formatted name
 * appears to ignore the setting entirely.
 *
 * <p>So a full recolour strips the codes and rebuilds the name as flat text.
 * Keeping the server's formatting instead leaves any piece the server coloured –
 * by style or by code – exactly as it was, and colours only the plain ones.
 */
public final class TextColorizer {

	/** Section codes: colours, styles, reset, and the §x hex prefix. */
	private static final String LEGACY_CODES = "(?i)\\u00a7[0-9A-FK-ORX]";

	private TextColorizer() {
	}

	public static MutableText colorize(Text text, int rgb, boolean keepExisting) {
		if (!keepExisting) {
			// Nothing of the server's formatting survives, so flatten the whole
			// name and drop the codes – otherwise they would repaint it mid-draw.
			return Text.literal(stripLegacy(text.getString())).styled(style -> style.withColor(rgb));
		}
		return colorizePlainParts(text, rgb);
	}

	private static MutableText colorizePlainParts(Text text, int rgb) {
		Style style = text.getStyle();
		boolean serverColoured = style.getColor() != null || hasLegacy(ownText(text));
		Style styled = serverColoured ? style : style.withColor(rgb);

		MutableText copy = MutableText.of(text.getContent()).setStyle(styled);
		for (Text sibling : text.getSiblings()) {
			copy.append(colorizePlainParts(sibling, rgb));
		}
		return copy;
	}

	/** @return the text without any legacy section codes. */
	public static String stripLegacy(String text) {
		return text.replaceAll(LEGACY_CODES, "");
	}

	private static boolean hasLegacy(String text) {
		return text.indexOf('§') >= 0;
	}

	/** This node's own string, without its children's. */
	private static String ownText(Text text) {
		return text.getContent() instanceof PlainTextContent plain ? plain.string() : "";
	}
}
