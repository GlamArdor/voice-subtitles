package com.glamardor.voicesubtitles.gui;

import com.glamardor.voicesubtitles.VoiceSubtitles;
import net.minecraft.client.util.SdlUtil;
import net.minecraft.util.Util;

import java.nio.file.Path;

/**
 * Shows a file to the player in whatever their system calls a file manager.
 *
 * <p>Opening the folder is not enough on its own: a folder of a hundred exports
 * leaves somebody hunting for the one they just made. Every desktop can select
 * a file rather than merely open its folder, they just each spell it
 * differently, so this asks for that where it can and settles for the folder
 * where it cannot.
 */
final class Reveal {

	private Reveal() {
	}

	static void inFileManager(Path file) {
		Path absolute = file.toAbsolutePath();
		try {
			String[] command = switch (Util.getOperatingSystem()) {
				// Handed to the shell as one string on purpose. Explorer wants
				// /select,"the path" with the quotes around the path alone, and
				// Java quotes any argument containing a space as a whole, which
				// turns it into "/select,the path" - a form Explorer does not
				// understand and answers by opening Documents instead.
				case WINDOWS -> new String[]{"cmd.exe", "/c", "explorer.exe /select,\"" + absolute + "\""};
				case OSX -> new String[]{"open", "-R", absolute.toString()};
				default -> null;
			};
			if (command != null) {
				new ProcessBuilder(command).start();
				return;
			}
		} catch (Exception e) {
			VoiceSubtitles.LOGGER.warn("Could not show {} in the file manager", absolute, e);
		}
		// Everywhere else, and whenever the above did not work: the folder, which
		// every desktop can open and which is better than nothing at all.
		SdlUtil.openUri(absolute.getParent().toUri());
	}
}
