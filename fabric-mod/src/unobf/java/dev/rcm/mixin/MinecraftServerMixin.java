package dev.rcm.mixin;

import dev.rcm.core.RcmRuntime;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(method = "tickServer", at = @At("TAIL"))
	private void rcm$onServerTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		RcmRuntime.onServerTick(this);
	}

	@Inject(method = "stopServer", at = @At("HEAD"))
	private void rcm$onServerStop(CallbackInfo ci) {
		RcmRuntime.onServerStop(this);
	}
}
