package dev.rcm.mixin;

import dev.rcm.core.RcmRuntime;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "sendChat", at = @At("HEAD"), cancellable = true, require = 0)
	private void rcm$onSendChat(String content, CallbackInfo ci) {
		if (RcmRuntime.interceptOutgoingClientChat(content)) {
			ci.cancel();
		}
	}
}
