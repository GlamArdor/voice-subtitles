package com.glamardor.voicesubtitles;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * A caption of one's own, for looking at.
 *
 * <p>Every setting here is about how speech looks: how quickly a line fades in,
 * how the words appear, how long it lingers, how wide it wraps. All of it is
 * invisible until somebody talks, which leaves anybody adjusting it either
 * guessing or asking a friend to say something. This puts a line on screen that
 * behaves exactly like a real one, so the settings can be seen rather than
 * imagined.
 *
 * <p>Not a debugging leftover: it is as much a feature as the settings it shows.
 */
public final class SubtitlePreview {

	/** Long enough to see the words appear, short enough not to be in the way. */
	private static final long DEFAULT_SECONDS = 6L;

	/**
	 * A handful of samples rather than one.
	 *
	 * <p>Asked for again while one is still on screen, the previous line closes
	 * and fades out as the new one arrives, which is how both halves of the
	 * motion get seen at once. Identical text would make that look like a
	 * flicker rather than like a conversation.
	 */
	private static final int SAMPLES = 4;

	private static SubtitleFeed.Line line;
	private static long until;
	private static int next;
	/** The text of the line currently showing, so settling it does not change it. */
	private static String sample = "";

	private SubtitlePreview() {
	}

	/** Puts a sample caption on screen for a few seconds. */
	public static void start(long seconds) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		stop();
		sample = Text.translatable("voice_subtitles.preview.text." + (next % SAMPLES + 1)).getString();
		next++;
		line = SubtitleFeed.openOrCreate(client.player.getUuid());
		SubtitleFeed.setText(line, sample, false);
		until = System.currentTimeMillis() + Math.max(seconds, 1L) * 1000L;
	}

	public static void start() {
		start(DEFAULT_SECONDS);
	}

	/**
	 * Keeps the line from being closed by the silence that follows it, since no
	 * audio is arriving to keep it alive, then lets it finish the way a real one
	 * does: settled, held for its reading time, faded out.
	 */
	public static void tick(MinecraftClient client) {
		if (line == null) {
			return;
		}
		if (System.currentTimeMillis() >= until) {
			SubtitleFeed.setText(line, sample, true);
			SubtitleFeed.release(line);
			line = null;
			return;
		}
		SubtitleFeed.keepAlive(line);
	}

	private static void stop() {
		if (line != null) {
			SubtitleFeed.close(line);
			SubtitleFeed.release(line);
			line = null;
		}
	}
}
