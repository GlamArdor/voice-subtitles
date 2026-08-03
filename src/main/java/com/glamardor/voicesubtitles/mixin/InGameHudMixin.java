package com.glamardor.voicesubtitles.mixin;

import com.glamardor.voicesubtitles.config.VoiceSubtitlesConfig;
import com.glamardor.voicesubtitles.hud.SubtitleHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the subtitle feed on screen while the interface is hidden.
 *
 * <p>With the HUD hidden, vanilla skips its HUD layers wholesale – and the feed,
 * being one of them, goes with it. That is wrong for this mod: someone who reads
 * the conversation rather than hearing it would lose it the moment they press
 * F1. So when the HUD is hidden the feed is drawn from here instead, unless the
 * player asked for it to hide along with everything else.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void voiceSubtitles$renderWhileHudHidden(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!client.options.hudHidden) {
			return; // the normal HUD layer is drawing it
		}
		if (VoiceSubtitlesConfig.get().hideWithHud) {
			return;
		}
		SubtitleHud.render(context, tickCounter);
	}
}
