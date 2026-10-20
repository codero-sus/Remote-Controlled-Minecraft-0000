package dev.rcm.platform;

import com.mojang.brigadier.CommandDispatcher;
import dev.rcm.core.BrigadierRegistrar;
import dev.rcm.core.MinecraftPlatform;
import dev.rcm.core.PlayerSnapshot;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Native unobfuscated Mojang-named bridge for Minecraft 26.1+ (26.1, 26.1.1, 26.1.2, 26.2, 26.3, and newer).
 */
public final class UnobfMinecraftPlatform implements MinecraftPlatform {
	private final ConcurrentLinkedQueue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();
	private volatile Minecraft clientInstance;
	private volatile MinecraftServer serverInstance;

	@Override
	public String getMinecraftVersion() {
		ModContainer mc = FabricLoader.getInstance().getModContainer("minecraft").orElse(null);
		return mc != null ? mc.getMetadata().getVersion().getFriendlyString() : "26.x";
	}

	@Override
	public Path getGameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	@Override
	public Path getConfigDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	@Override
	public boolean isClientEnvironment() {
		return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
	}

	@Override
	public void onClientTick(Object client) {
		this.clientInstance = (Minecraft) client;
		drainQueue();
	}

	@Override
	public void onServerTick(Object server) {
		this.serverInstance = (MinecraftServer) server;
		if (!isClientEnvironment() || this.clientInstance == null) {
			drainQueue();
		}
	}

	@Override
	public void onServerStop(Object server) {
		if (this.serverInstance == server) {
			this.serverInstance = null;
		}
	}

	@Override
	public Object getActiveServer() {
		return serverInstance;
	}

	private void drainQueue() {
		Runnable r;
		int processed = 0;
		while (processed < 64 && (r = taskQueue.poll()) != null) {
			processed++;
			try {
				r.run();
			} catch (Throwable t) {
				System.err.println("[RCM] Game thread task error: " + t.getMessage());
			}
		}
	}

	@Override
	public void executeOnGameThread(Runnable task) {
		if (clientInstance == null && serverInstance == null) {
			task.run();
			return;
		}
		taskQueue.offer(task);
	}

	@Override
	public PlayerSnapshot captureSnapshot() {
		PlayerSnapshot snap = new PlayerSnapshot();
		snap.minecraftVersion = getMinecraftVersion();
		Minecraft mc = this.clientInstance;
		MinecraftServer srv = this.serverInstance;

		if (!isClientEnvironment() || mc == null) {
			snap.mode = "DEDICATED_SERVER";
			if (srv != null) {
				snap.inWorld = true;
				snap.serverAddress = "Dedicated Server (Running)";
				for (ServerPlayer sp : srv.getPlayerList().getPlayers()) {
					snap.onlinePlayers.add(sp.getName().getString());
				}
			}
			return snap;
		}

		snap.mode = srv != null ? "SINGLEPLAYER" : "CLIENT";
		snap.fps = mc.getFps();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;

		if (player == null || level == null) {
			snap.inWorld = false;
			snap.serverAddress = "Main Menu / Title Screen";
			return snap;
		}

		snap.inWorld = true;
		ServerData currentServer = mc.getCurrentServer();
		snap.serverAddress = currentServer != null
			? currentServer.ip
			: (srv != null ? "Singleplayer / LAN Integrated World" : "Connected World");

		snap.playerName = player.getName().getString();
		snap.health = player.getHealth();
		snap.maxHealth = player.getMaxHealth();
		snap.foodLevel = player.getFoodData().getFoodLevel();
		snap.xpLevel = player.experienceLevel;
		snap.x = player.getX();
		snap.y = player.getY();
		snap.z = player.getZ();
		snap.yaw = player.getYRot();
		snap.pitch = player.getXRot();
		snap.dimension = level.dimension().toString();

		Container inv = player.getInventory();
		int selSlot = readSelectedSlot(player.getInventory());
		snap.selectedSlot = selSlot + 1;
		int totalSlots = inv.getContainerSize();
		for (int i = 0; i < totalSlots; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			String desc = stack.getHoverName().getString() + " x" + stack.getCount();
			if (i == selSlot) {
				snap.mainHandItem = desc;
			} else if (i == 40) {
				snap.offHandItem = desc;
			}
			String tag = i < 9 ? "[Hotbar " + (i + 1) + "]" : (i == 40 ? "[Offhand]" : (i >= 36 && i <= 39 ? "[Armor]" : "[Slot " + i + "]"));
			snap.inventorySummary.add(tag + " " + desc);
		}

		if (player.connection != null) {
			for (PlayerInfo info : player.connection.getOnlinePlayers()) {
				snap.onlinePlayers.add(extractProfileName(info.getProfile()));
			}
		}

		record EntDist(double dist, String line) {}
		List<EntDist> entities = new ArrayList<>();
		for (Entity ent : level.entitiesForRendering()) {
			if (ent == null || ent == player) {
				continue;
			}
			double d = ent.distanceTo(player);
			if (d <= 64.0) {
				entities.add(new EntDist(d, String.format("%s — %.1fm (%.1f, %.1f, %.1f)",
					ent.getName().getString(), d, ent.getX(), ent.getY(), ent.getZ())));
			}
		}
		entities.sort(Comparator.comparingDouble(EntDist::dist));
		for (int i = 0; i < Math.min(20, entities.size()); i++) {
			snap.nearbyEntities.add(entities.get(i).line());
		}
		return snap;
	}

	@Override
	public void setKeyState(String keyAction, boolean pressed) {
		Minecraft mc = this.clientInstance;
		if (mc == null) {
			return;
		}
		Options opt = mc.options;
		KeyMapping mapping = switch (keyAction) {
			case "forward" -> opt.keyUp;
			case "back" -> opt.keyDown;
			case "left" -> opt.keyLeft;
			case "right" -> opt.keyRight;
			case "jump" -> opt.keyJump;
			case "sneak" -> opt.keyShift;
			case "sprint" -> opt.keySprint;
			case "attack" -> opt.keyAttack;
			case "use" -> opt.keyUse;
			default -> null;
		};
		if (mapping == null) {
			return;
		}
		boolean wasDown = mapping.isDown();
		mapping.setDown(pressed);
		if (pressed && !wasDown) {
			incrementClickCount(mapping);
		}
	}

	@Override
	public String triggerAction(String action, boolean fullStack) {
		Minecraft mc = this.clientInstance;
		if (mc == null) {
			return "Client actions require a Minecraft client environment.";
		}
		LocalPlayer player = mc.player;
		return switch (action) {
			case "attack" -> {
				invokeNoArg(mc, "startAttack");
				yield "Triggered attack / left-click swing.";
			}
			case "use" -> {
				invokeNoArg(mc, "startUseItem");
				yield "Triggered item use / right-click interact.";
			}
			case "drop" -> {
				if (player == null) {
					yield "Player is not in a world.";
				}
				invokeDropSelected(player, mc, fullStack);
				yield fullStack ? "Dropped entire stack from selected slot." : "Dropped 1 item from selected slot.";
			}
			case "swap" -> {
				incrementClickCount(mc.options.keySwapOffhand);
				yield "Swapped main hand and offhand items.";
			}
			case "respawn" -> {
				if (player != null) {
					player.respawn();
				}
				yield "Sent respawn request.";
			}
			case "disconnect" -> {
				if (mc.level != null) {
					invokeDisconnect(mc);
				}
				yield "Disconnected from world/server.";
			}
			default -> "Unknown action: " + action;
		};
	}

	@Override
	public String selectHotbarSlot(int slotZeroBased) {
		Minecraft mc = this.clientInstance;
		if (mc == null || mc.player == null) {
			return "Player is not currently in a world.";
		}
		writeSelectedSlot(mc.player.getInventory(), slotZeroBased);
		return "Selected hotbar slot **" + (slotZeroBased + 1) + "**.";
	}

	@Override
	public String setRotation(float yaw, float pitch, boolean relative) {
		Minecraft mc = this.clientInstance;
		if (mc == null || mc.player == null) {
			return "Player is not currently in a world.";
		}
		LocalPlayer p = mc.player;
		float nextYaw = relative ? (p.getYRot() + yaw) : yaw;
		float nextPitch = Math.clamp(relative ? (p.getXRot() + pitch) : pitch, -90f, 90f);
		p.setYRot(nextYaw);
		p.setXRot(nextPitch);
		return String.format("Camera set to Yaw `%.1f°`, Pitch `%.1f°`.", nextYaw, nextPitch);
	}

	@Override
	public String sendChatMessage(String text) {
		Minecraft mc = this.clientInstance;
		if (mc != null && mc.player != null && mc.player.connection != null) {
			mc.player.connection.sendChat(text);
			return "Sent chat message: `" + text + "`";
		}
		if (serverInstance != null) {
			return executeCommand("say " + text);
		}
		return "Cannot send chat: not in a world or server.";
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public String executeCommand(String rawCommand) {
		String noSlash = rawCommand.trim();
		while (noSlash.startsWith("/")) {
			noSlash = noSlash.substring(1).trim();
		}

		MinecraftServer srv = this.serverInstance;
		if (srv != null) {
			try {
				CommandDispatcher dispatcher = BrigadierRegistrar.extractDispatcher(srv);
				Object source = srv.createCommandSourceStack();
				if (dispatcher != null) {
					int res = dispatcher.execute(noSlash, source);
					return "Executed server command `/" + noSlash + "` (result: " + res + ").";
				}
			} catch (Exception e) {
				return "Command error on `/" + noSlash + "`: `" + e.getMessage() + "`";
			}
		}

		Minecraft mc = this.clientInstance;
		if (mc != null && mc.player != null && mc.player.connection != null) {
			mc.player.connection.sendCommand(noSlash);
			return "Sent client command `/" + noSlash + "`.";
		}
		return "Cannot execute command: not in an active world or server.";
	}

	@Override
	public String connectToServer(String serverAddress) {
		Minecraft mc = this.clientInstance;
		if (mc == null) {
			return "Connecting to multiplayer servers requires a Minecraft client.";
		}
		try {
			if (mc.level != null) {
				invokeDisconnect(mc);
			}
			TitleScreen title = new TitleScreen();
			ServerAddress parsed = ServerAddress.parseString(serverAddress);
			ServerData data = new ServerData("Remote Server", serverAddress, ServerData.Type.OTHER);
			for (Method m : ConnectScreen.class.getMethods()) {
				if ("startConnecting".equals(m.getName())) {
					if (m.getParameterCount() == 5) {
						m.invoke(null, title, mc, parsed, data, Boolean.FALSE);
						return "Connecting Minecraft client to `" + serverAddress + "`...";
					}
					if (m.getParameterCount() == 6) {
						m.invoke(null, title, mc, parsed, data, Boolean.FALSE, null);
						return "Connecting Minecraft client to `" + serverAddress + "`...";
					}
				}
			}
			return "Unable to invoke ConnectScreen.startConnecting.";
		} catch (Throwable t) {
			return "Connect error: `" + t.getMessage() + "`";
		}
	}

	@Override
	public void displayLocalMessage(String message) {
		System.out.println("[RCM] " + message);
		Minecraft mc = this.clientInstance;
		if (mc == null || mc.player == null) {
			return;
		}
		Component comp = Component.literal("[RCM] " + message);
		try {
			// 26.3+: LocalPlayer.sendSystemMessage(Component)
			Method m1 = mc.player.getClass().getMethod("sendSystemMessage", Component.class);
			m1.invoke(mc.player, comp);
			return;
		} catch (Throwable ignored) {
		}
		try {
			// 26.1 - 26.2: LocalPlayer.displayClientMessage(Component, boolean)
			Method m2 = mc.player.getClass().getMethod("displayClientMessage", Component.class, boolean.class);
			m2.invoke(mc.player, comp, Boolean.FALSE);
		} catch (Throwable ignored) {
		}
	}

	@Override
	public CompletableFuture<byte[]> captureScreenshotPng() {
		CompletableFuture<byte[]> future = new CompletableFuture<>();
		Minecraft mc = this.clientInstance;
		if (mc == null || !isClientEnvironment()) {
			future.complete(captureDesktopFallback());
			return future;
		}
		executeOnGameThread(() -> {
			try {
				Path tempRoot = getConfigDir().resolve("rcm-snap-" + UUID.randomUUID());
				Files.createDirectories(tempRoot);
				File tempDir = tempRoot.toFile();
				Consumer<Component> callback = msg -> {
					try {
						Path shotsDir = tempRoot.resolve("screenshots");
						byte[] found = null;
						if (Files.isDirectory(shotsDir)) {
							try (DirectoryStream<Path> ds = Files.newDirectoryStream(shotsDir, "*.png")) {
								for (Path p : ds) {
									found = Files.readAllBytes(p);
									Files.deleteIfExists(p);
									break;
								}
							}
							Files.deleteIfExists(shotsDir);
						}
						Files.deleteIfExists(tempRoot);
						future.complete(found != null && found.length > 0 ? found : captureDesktopFallback());
					} catch (Throwable err) {
						future.complete(captureDesktopFallback());
					}
				};

				boolean called = false;
				for (Method m : Screenshot.class.getMethods()) {
					if ("grab".equals(m.getName()) && m.getParameterCount() == 3) {
						Object renderTarget = resolveRenderTarget(mc, m.getParameterTypes()[1]);
						if (renderTarget != null) {
							m.invoke(null, tempDir, renderTarget, callback);
							called = true;
							break;
						}
					}
				}
				if (!called) {
					Files.deleteIfExists(tempRoot);
					future.complete(captureDesktopFallback());
				}
			} catch (Throwable t) {
				future.complete(captureDesktopFallback());
			}
		});
		return future;
	}

	private static void invokeDropSelected(LocalPlayer player, Minecraft mc, boolean fullStack) {
		for (String mName : new String[]{"drop", "dropSelectedItem"}) {
			try {
				Method m = player.getClass().getMethod(mName, boolean.class);
				m.invoke(player, Boolean.valueOf(fullStack));
				return;
			} catch (Throwable ignored) {
			}
		}
		int count = fullStack ? 64 : 1;
		for (int i = 0; i < count; i++) {
			incrementClickCount(mc.options.keyDrop);
		}
	}

	private static Object resolveRenderTarget(Minecraft mc, Class<?> targetType) {
		for (String preferred : new String[]{"mainRenderTarget", "getMainRenderTarget"}) {
			try {
				Method m = mc.getClass().getMethod(preferred);
				if (targetType.isAssignableFrom(m.getReturnType())) {
					return m.invoke(mc);
				}
			} catch (Throwable ignored) {
			}
		}
		for (Method m : mc.getClass().getMethods()) {
			if (m.getParameterCount() == 0 && targetType.isAssignableFrom(m.getReturnType())) {
				try {
					return m.invoke(mc);
				} catch (Throwable ignored) {
				}
			}
		}
		return null;
	}

	private static byte[] captureDesktopFallback() {
		try {
			if (GraphicsEnvironment.isHeadless()) {
				return new byte[0];
			}
			BufferedImage img = new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(img, "png", baos);
			return baos.toByteArray();
		} catch (Throwable ignored) {
			return new byte[0];
		}
	}

	private static String extractProfileName(Object profile) {
		if (profile == null) {
			return "";
		}
		for (String mName : new String[]{"name", "getName"}) {
			try {
				Method m = profile.getClass().getMethod(mName);
				Object v = m.invoke(profile);
				if (v != null) {
					return String.valueOf(v);
				}
			} catch (Throwable ignored) {
			}
		}
		return String.valueOf(profile);
	}

	private static int readSelectedSlot(Object inventory) {
		try {
			Method getter = inventory.getClass().getMethod("getSelectedSlot");
			Object v = getter.invoke(inventory);
			if (v instanceof Number n) {
				return n.intValue();
			}
		} catch (Throwable ignored) {
		}
		try {
			Field f = inventory.getClass().getDeclaredField("selected");
			f.setAccessible(true);
			return f.getInt(inventory);
		} catch (Throwable ignored) {
			return 0;
		}
	}

	private static void writeSelectedSlot(Object inventory, int slot) {
		try {
			Method setter = inventory.getClass().getMethod("setSelectedSlot", int.class);
			setter.invoke(inventory, slot);
			return;
		} catch (Throwable ignored) {
		}
		try {
			Field f = inventory.getClass().getDeclaredField("selected");
			f.setAccessible(true);
			f.setInt(inventory, slot);
		} catch (Throwable ignored) {
		}
	}

	private static void incrementClickCount(KeyMapping keyMapping) {
		for (String fieldName : new String[]{"clickCount", "timesPressed"}) {
			try {
				Field f = KeyMapping.class.getDeclaredField(fieldName);
				f.setAccessible(true);
				f.setInt(keyMapping, f.getInt(keyMapping) + 1);
				return;
			} catch (Throwable ignored) {
			}
		}
	}

	private static void invokeNoArg(Object target, String name) {
		try {
			Method m = target.getClass().getDeclaredMethod(name);
			m.setAccessible(true);
			m.invoke(target);
		} catch (Throwable ignored) {
		}
	}

	private static void invokeDisconnect(Minecraft mc) {
		for (Method m : mc.getClass().getMethods()) {
			if ("disconnectFromWorld".equals(m.getName()) && m.getParameterCount() == 1) {
				try {
					m.invoke(mc, Component.literal("Disconnected via Remote Controlled Minecraft"));
					return;
				} catch (Throwable ignored) {
				}
			}
		}
		for (Method m : mc.getClass().getMethods()) {
			if ("disconnect".equals(m.getName())) {
				try {
					if (m.getParameterCount() == 0) {
						m.invoke(mc);
						return;
					}
					if (m.getParameterCount() == 2) {
						m.invoke(mc, new TitleScreen(), Boolean.FALSE);
						return;
					}
					if (m.getParameterCount() == 3) {
						m.invoke(mc, new TitleScreen(), Boolean.FALSE, Boolean.FALSE);
						return;
					}
				} catch (Throwable ignored) {
				}
			}
		}
	}
}
