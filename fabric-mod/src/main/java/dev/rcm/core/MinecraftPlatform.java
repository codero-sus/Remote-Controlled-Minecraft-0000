package dev.rcm.core;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Version-independent contract bridging core remote control logic to any Fabric Minecraft edition.
 */
public interface MinecraftPlatform {
	String getMinecraftVersion();

	Path getGameDir();

	Path getConfigDir();

	boolean isClientEnvironment();

	void onClientTick(Object clientInstance);

	void onServerTick(Object serverInstance);

	void onServerStop(Object serverInstance);

	Object getActiveServer();

	void executeOnGameThread(Runnable task);

	PlayerSnapshot captureSnapshot();

	void setKeyState(String keyAction, boolean pressed);

	String triggerAction(String action, boolean fullStack);

	String selectHotbarSlot(int slotZeroBased);

	String setRotation(float yaw, float pitch, boolean relative);

	String sendChatMessage(String text);

	String executeCommand(String rawCommand);

	String connectToServer(String serverAddress);

	void displayLocalMessage(String message);

	CompletableFuture<byte[]> captureScreenshotPng();
}
