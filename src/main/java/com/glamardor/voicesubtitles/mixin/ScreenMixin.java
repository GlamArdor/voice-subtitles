package com.glamardor.voicesubtitles.mixin;

import com.glamardor.voicesubtitles.config.ConfigScreenMarker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves the world visible behind our own settings screen.
 *
 * <p>Cloth Config's transparent-background flag no longer covers the blur and
 * darkening a screen applies on its own, so those are cancelled here instead.
 *
 * <p>Strictly for our screen: every other menu in the game, this mod's or
 * anyone else's, keeps its background exactly as it was.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

	@Inject(method = "applyBlur", at = @At("HEAD"), cancellable = true)
	private void voiceSubtitles$noBlur(DrawContext context, CallbackInfo ci) {
		if (ConfigScreenMarker.isOurs((Screen) (Object) this)) {
			ci.cancel();
		}
	}

	@Inject(method = "renderDarkening(Lnet/minecraft/client/gui/DrawContext;)V",
			at = @At("HEAD"), cancellable = true)
	private void voiceSubtitles$noDarkening(DrawContext context, CallbackInfo ci) {
		if (ConfigScreenMarker.isOurs((Screen) (Object) this)) {
			ci.cancel();
		}
	}
}
