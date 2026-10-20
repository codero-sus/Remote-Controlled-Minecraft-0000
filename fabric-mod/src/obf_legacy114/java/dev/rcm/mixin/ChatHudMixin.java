package dev.rcm.mixin;

import dev.rcm.core.RcmRuntime;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), require = 0)
	private void rcm$onAddMessage(Component message, CallbackInfo ci) {
		if (message != null) {
			RcmRuntime.onChatMessage(message.getText());
		}
	}
}
