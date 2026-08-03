package com.glamardor.voicesubtitles;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Makes a caption as faint as the voice behind it.
 *
 * <p>Simple Voice Chat reports, per audio packet, the distance at which that
 * voice can still be heard – a whisper reports a much smaller range than normal
 * speech. Comparing that against how far the speaker actually is gives a decent
 * proxy for how loud they sound: right next to you, fully opaque; at the very
 * edge of their range, barely there.
 *
 * <p>Nothing is ever hidden because of distance. If the client received the
 * audio at all, the caption is drawn – only its opacity changes.
 *
 * <p>Recomputed on the client thread while a line is open, then frozen: once
 * somebody stops talking, their caption should not fade further just because
 * they walked away while it was still on screen.
 */
public final class DistanceFade {

	private DistanceFade() {
	}

	/** Updates the opacity of every line that is still receiving audio. */
	public static void tick() {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		for (SubtitleFeed.Line line : SubtitleFeed.all()) {
			if (!line.open()) {
				continue;
			}
			line.setRangeAlpha(computeFor(line, cfg, client));
		}
	}

	private static float computeFor(SubtitleFeed.Line line, VoiceSubtitlesConfig cfg, MinecraftClient client) {
		if (!cfg.distanceFade) {
			return 1F;
		}

		float alpha = 1F;

		// Distance falloff, but only when we know both the range and where the
		// speaker is. Group and radio channels report neither, and they should
		// not be dimmed – they are equally loud wherever the speaker stands.
		Float audible = SpeakerTracker.audibleDistanceOf(line.speaker);
		PlayerEntity speaker = NameResolver.playerOf(line.speaker);

		if (audible != null && audible > 0F && speaker != null && speaker != client.player) {
			double distance = Math.sqrt(speaker.squaredDistanceTo(client.player));
			float ratio = MathHelper.clamp((float) (distance / audible), 0F, 1F);
			// Ease the falloff so the caption stays readable through most of the
			// range and only thins out near the edge of hearing.
			float eased = ratio * ratio;
			alpha = MathHelper.lerp(eased, 1F, cfg.farOpacity / 100F);
		}

		if (line.whisper()) {
			alpha *= cfg.whisperOpacity / 100F;
		}
		return MathHelper.clamp(alpha, 0.05F, 1F);
	}
}
