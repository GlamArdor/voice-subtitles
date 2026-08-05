package com.glamardor.voicesubtitles.mixin;

import com.glamardor.voicesubtitles.hud.SubtitleHud;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the subtitle feed on screen when the rest of the interface is not.
 *
 * <p>Two ways that happens. Vanilla skips every HUD layer when the player hides
 * the interface, and the feed is one of them: someone who reads the conversation
 * rather than hearing it would lose it the moment they press F1. And Absolute
 * Cinema Camera cancels the HUD outright for its film mode, which takes the feed
 * with it for the same reason.
 *
 * <p>Both are answered here, by drawing the feed before either can happen.
 *
 * <p>The priority is deliberate. Absolute Cinema's own hook sits at this very
 * point and cancels the method, and once a callback cancels, the ones after it
 * are never called. A lower number is applied first, which puts this one in
 * front of it.
 */
@Mixin(value = InGameHud.class, priority = 500)
public abstract class InGameHudMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void voiceSubtitles$renderWhileHudHidden(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		SubtitleHud.renderWhileHidden(context, tickCounter);
	}
}
