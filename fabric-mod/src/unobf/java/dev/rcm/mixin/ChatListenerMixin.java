package dev.rcm.mixin;

import com.mojang.authlib.GameProfile;
import dev.rcm.core.RcmRuntime;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.time.Instant;

@Mixin(ChatListener.class)
public abstract class ChatListenerMixin {
	@Inject(method = "showMessageToPlayer", at = @At("HEAD"), require = 0)
	private void rcm$onPlayerChat(
		ChatType.Bound boundChatType,
		PlayerChatMessage message,
		Component decorated,
		GameProfile sender,
		boolean onlyShowSecureChat,
		Instant receptionTimestamp,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (decorated != null) {
			RcmRuntime.onChatMessage(decorated.getString());
		}
	}

	@Inject(method = "handleSystemMessage", at = @At("HEAD"), require = 0)
	private void rcm$onSystemMessage(Component message, boolean overlay, CallbackInfo ci) {
		if (!overlay && message != null) {
			RcmRuntime.onChatMessage(message.getString());
		}
	}
}
