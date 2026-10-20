package dev.rcm.mixin;

import dev.rcm.core.RcmRuntime;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void rcm$onClientTick(CallbackInfo ci) {
		RcmRuntime.onClientTick(this);
	}
}
