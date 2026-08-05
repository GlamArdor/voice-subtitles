package com.glamardor.voicesubtitles.mixin;

import com.glamardor.voicesubtitles.LogEvents;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices every command the player sends.
 *
 * <p>Hooked here rather than through Fabric's send-message event, which was
 * letting most of them past: {@code /me} and {@code /try} were filed while
 * {@code /gamemode survival} and {@code /time add 1} were not, and a log that
 * keeps some of the commands is worse than useless, because there is no way to
 * tell from it which ones it kept.
 *
 * <p>This is the one place every command typed into chat goes through, so
 * nothing can slip past it.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ChatCommandMixin {

	/** The command arrives without its slash, which is how the log wants it anyway. */
	@Inject(method = "sendChatCommand", at = @At("HEAD"))
	private void voice_subtitles$noteCommand(String command, CallbackInfo info) {
		LogEvents.onCommandSent(command);
	}
}
