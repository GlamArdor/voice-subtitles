package com.glamardor.voicesubtitles;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Which way a speaker is, relative to where you are looking.
 *
 * <p>A caption carries the words but not the direction the voice came from, and
 * that is the part somebody reading instead of listening has no other way to
 * get. So the feed puts a small arrow in front of the line: up for straight
 * ahead, down for behind you, and the diagonals in between.
 */
public final class SpeakerDirection {

	/**
	 * Clockwise from "straight ahead", one per 45°.
	 *
	 * <p>The four cardinals come out of Minecraft's own font and the diagonals
	 * out of the Unifont fallback, so the diagonals are drawn a good deal
	 * thinner. Kept anyway: knowing that somebody is behind you and to the left
	 * is worth more than four arrows of matching weight.
	 */
	private static final String[] ARROWS = {
			"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"
	};

	/** Widest of the eight, so the arrow column never changes width. */
	public static final String WIDEST = "↗";

	private SpeakerDirection() {
	}

	/**
	 * @return the arrow for this speaker, or {@code null} when there is nothing
	 *         to point at – yourself, or somebody the client cannot see (out of
	 *         render distance, or talking over a radio or group channel)
	 */
	public static String arrowFor(UUID speaker) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return null;
		}
		PlayerEntity target = NameResolver.playerOf(speaker);
		if (target == null || target == client.player) {
			return null;
		}

		Vec3d relative = target.getEntityPos().subtract(client.player.getEntityPos());
		if (relative.horizontalLengthSquared() < 0.5D) {
			// Close enough to be standing on top of you; an arrow would just spin.
			return null;
		}

		// Minecraft's yaw grows clockwise from due south, so the angle to the
		// target is atan2(-dx, dz); subtracting our own yaw makes it relative to
		// where the camera is pointing, negative to the left.
		double toTarget = Math.toDegrees(Math.atan2(-relative.x, relative.z));
		double bearing = MathHelper.wrapDegrees(toTarget - client.player.getYaw());

		int sector = Math.floorMod((int) Math.round(bearing / 45D), 8);
		return ARROWS[sector];
	}
}
