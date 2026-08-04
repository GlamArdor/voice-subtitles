package com.glamardor.voicesubtitles.hud;

import com.glamardor.voicesubtitles.Captions;
import com.glamardor.voicesubtitles.NameResolver;
import com.glamardor.voicesubtitles.SpeakerColors;
import com.glamardor.voicesubtitles.SubtitleFeed;
import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Captions floating above the speaker, as an alternative to the bottom feed.
 *
 * <p>Drawn during world rendering rather than attached to the name tag, so they
 * still show up on servers that hide name tags – the same reason the microphone
 * indicator is drawn this way.
 */
public final class OverHeadSubtitles {

	/** One "text pixel" in blocks – the scale vanilla name tags use. */
	private static final float TEXT_SCALE = 0.025F;

	/** Packed lightmap coordinate for full brightness. */
	private static final int FULL_BRIGHT = 0xF000F0;

	private static final int LINE_HEIGHT = 10;

	private OverHeadSubtitles() {
	}

	public static void init() {
		WorldRenderEvents.LAST.register(OverHeadSubtitles::render);
	}

	private static void render(WorldRenderContext context) {
		VoiceSubtitlesConfig cfg = VoiceSubtitlesConfig.get();
		if (!cfg.enabled || (!cfg.voicePlacement.overHead() && !cfg.chatPlacement.overHead())) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null || (client.options.hudHidden && cfg.hideWithHud)) {
			return;
		}

		MatrixStack matrices = context.matrixStack();
		if (matrices == null) {
			return;
		}
		VertexConsumerProvider consumers = context.consumers();
		if (consumers == null) {
			consumers = client.getBufferBuilders().getEntityVertexConsumers();
		}
		if (consumers == null) {
			return;
		}

		Camera camera = context.camera();
		Vec3d cameraPos = camera.getPos();
		float tickDelta = context.tickCounter().getTickProgress(false);
		boolean firstPerson = client.options.getPerspective().isFirstPerson();
		TextRenderer textRenderer = client.textRenderer;
		long now = System.currentTimeMillis();
		int drawn = 0;

		for (PlayerEntity player : client.world.getPlayers()) {
			boolean self = player == client.player;
			if (self && firstPerson) {
				continue; // it would sit in our own face
			}
			if (player.isInvisibleTo(client.player)) {
				continue;
			}

			// Only lines of a kind that belongs above heads – chat can be kept
			// out of the world while voice floats there, or the other way round.
			SubtitleFeed.Line line = SubtitleFeed.lastLineOf(player.getUuid(),
					candidate -> cfg.placementOf(candidate.source == SubtitleFeed.Source.CHAT).overHead());
			if (line == null) {
				continue;
			}
			int alpha = Captions.alphaOf(line, now);
			if (alpha <= 4) {
				continue;
			}

			int color = cfg.colorSpeech ? SpeakerColors.colorOf(line.speaker) : SpeakerColors.WHITE;
			MutableText content = Text.empty();
			if (cfg.overHeadShowName) {
				content.append(NameResolver.nameOf(line.speaker).copy()).append(Text.literal(": "));
			}
			content.append(Text.literal(Captions.bodyOf(line, now)));

			List<OrderedText> rows = textRenderer.wrapLines(content, cfg.overHeadWidth);
			if (rows.isEmpty()) {
				continue;
			}
			// Long speech is trimmed to the most recent rows: a caption growing
			// down somebody's body until it reaches the floor helps nobody.
			if (rows.size() > cfg.overHeadMaxRows) {
				rows = rows.subList(rows.size() - cfg.overHeadMaxRows, rows.size());
			}

			Vec3d pos = player.getLerpedPos(tickDelta);
			double y = pos.y + player.getHeight() + cfg.overHeadHeight / 100.0D;

			draw(matrices, consumers, camera, textRenderer, rows,
					pos.x - cameraPos.x, y - cameraPos.y, pos.z - cameraPos.z,
					ColorHelper.withAlpha(alpha, color), alpha, cfg.overHeadThroughWalls,
					cfg.overHeadScalePercent / 100F, cfg.overHeadBackground / 100F);
			drawn++;
		}

		if (drawn > 0 && consumers instanceof VertexConsumerProvider.Immediate immediate) {
			immediate.draw();
		}
	}

	/** Billboards the caption at the given camera-relative position, rows stacked upwards. */
	private static void draw(MatrixStack matrices, VertexConsumerProvider consumers, Camera camera,
	                         TextRenderer textRenderer, List<OrderedText> rows,
	                         double x, double y, double z, int color, int alpha, boolean throughWalls,
	                         float scale, float backgroundOpacity) {
		matrices.push();
		matrices.translate(x, y, z);
		matrices.multiply(camera.getRotation());
		matrices.scale(TEXT_SCALE * scale, -TEXT_SCALE * scale, TEXT_SCALE * scale);

		Matrix4f matrix = matrices.peek().getPositionMatrix();
		TextRenderer.TextLayerType layer = throughWalls
				? TextRenderer.TextLayerType.SEE_THROUGH
				: TextRenderer.TextLayerType.NORMAL;

		// The Y axis is flipped by the scale above, so the first row is drawn
		// highest by starting at the negative total height.
		int top = -rows.size() * LINE_HEIGHT;
		int background = ColorHelper.getArgb(Math.round(backgroundOpacity * alpha), 0, 0, 0);

		for (int i = 0; i < rows.size(); i++) {
			OrderedText row = rows.get(i);
			float rowX = -textRenderer.getWidth(row) / 2.0F;
			textRenderer.draw(row, rowX, top + i * LINE_HEIGHT, color, false,
					matrix, consumers, layer, background, FULL_BRIGHT);
		}

		matrices.pop();
	}
}
