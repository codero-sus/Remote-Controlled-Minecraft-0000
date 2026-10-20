package dev.rcm;

import dev.rcm.core.MinecraftPlatform;
import dev.rcm.core.RcmRuntime;
import net.fabricmc.api.ModInitializer;

public final class RcmMod implements ModInitializer {
	@Override
	public void onInitialize() {
		try {
			Class<?> cls = findPlatformClass();
			MinecraftPlatform impl = (MinecraftPlatform) cls.getDeclaredConstructor().newInstance();
			RcmRuntime.initialize(impl);
		} catch (Throwable t) {
			System.err.println("[RCM] Fatal initialization error: " + t);
			t.printStackTrace();
		}
	}

	private static Class<?> findPlatformClass() throws ClassNotFoundException {
		try {
			return Class.forName("dev.rcm.platform.UnobfMinecraftPlatform");
		} catch (ClassNotFoundException ignored) {
			return Class.forName("dev.rcm.platform.ObfMinecraftPlatform");
		}
	}
}
