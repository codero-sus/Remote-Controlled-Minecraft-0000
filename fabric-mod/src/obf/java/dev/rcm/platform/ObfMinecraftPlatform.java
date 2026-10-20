package dev.rcm.platform;

import com.mojang.brigadier.CommandDispatcher;
import dev.rcm.core.BrigadierRegistrar;
import dev.rcm.core.MinecraftPlatform;
import dev.rcm.core.PlayerSnapshot;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;

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
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Multi-version bridge covering ALL obfuscated Fabric editions from 1.14 through 1.21.11
 * (including snapshots) using Fabric Loader's MappingResolver and verified intermediary IDs.
 */
public final class ObfMinecraftPlatform implements MinecraftPlatform {
	private final MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
	private final ConcurrentLinkedQueue<Runnable> taskQueue = new ConcurrentLinkedQueue<Runnable>();
	private volatile Object clientInstance;
	private volatile Object serverInstance;

	@Override
	public String getMinecraftVersion() {
		ModContainer mc = FabricLoader.getInstance().getModContainer("minecraft").orElse(null);
		return mc != null ? mc.getMetadata().getVersion().getFriendlyString() : "unknown";
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
		this.clientInstance = client;
		drainQueue();
	}

	@Override
	public void onServerTick(Object server) {
		this.serverInstance = server;
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
				System.err.println("[RCM] Scheduled game task failed: " + t.getMessage());
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
		Object mc = this.clientInstance;
		Object srv = this.serverInstance;

		if (!isClientEnvironment() || mc == null) {
			snap.mode = "DEDICATED_SERVER";
			if (srv != null) {
				snap.inWorld = true;
				snap.serverAddress = "Dedicated Server (Running)";
				populateServerPlayers(srv, snap);
			}
			return snap;
		}

		snap.mode = srv != null ? "SINGLEPLAYER" : "CLIENT";
		snap.fps = getIntField(mc, "net.minecraft.class_310", "field_1738", "I", "currentFps", 0);
		Object player = getField(mc, "net.minecraft.class_310", "field_1724", "Lnet/minecraft/class_746;", "player");
		Object world = getField(mc, "net.minecraft.class_310", "field_1687", "Lnet/minecraft/class_638;", "world");

		if (player == null || world == null) {
			snap.inWorld = false;
			snap.serverAddress = "Main Menu / Title Screen";
			return snap;
		}

		snap.inWorld = true;
		Object serverEntry = invokeMapped(mc, "net.minecraft.class_310", "method_1558", "()Lnet/minecraft/class_642;", "getCurrentServerEntry");
		if (serverEntry != null) {
			Object addr = getField(serverEntry, "net.minecraft.class_642", "field_3761", "Ljava/lang/String;", "address");
			snap.serverAddress = addr != null ? String.valueOf(addr) : "Multiplayer Server";
		} else {
			snap.serverAddress = srv != null ? "Singleplayer / LAN Integrated World" : "Connected World";
		}

		snap.playerName = extractEntityName(player);
		snap.health = invokeFloat(player, "net.minecraft.class_1309", "method_6032", "()F", "getHealth", 20f);
		snap.maxHealth = invokeFloat(player, "net.minecraft.class_1309", "method_6063", "()F", "getMaxHealth", 20f);

		Object hungerMgr = getField(player, "net.minecraft.class_1657", "field_7493", "Lnet/minecraft/class_1702;", "hungerManager");
		if (hungerMgr != null) {
			Object food = invokeMapped(hungerMgr, "net.minecraft.class_1702", "method_7586", "()I", "getFoodLevel");
			if (food instanceof Number) {
				snap.foodLevel = ((Number) food).intValue();
			}
		}
		snap.xpLevel = getIntField(player, "net.minecraft.class_1657", "field_7520", "I", "experienceLevel", 0);

		snap.x = getCoord(player, "method_23317", "field_6014", "getX", "x");
		snap.y = getCoord(player, "method_23318", "field_6036", "getY", "y");
		snap.z = getCoord(player, "method_23321", "field_5969", "getZ", "z");
		snap.yaw = getAngle(player, "method_36454", "field_6031", "getYaw", "yaw");
		snap.pitch = getAngle(player, "method_36455", "field_5965", "getPitch", "pitch");
		snap.dimension = extractDimensionName(world);

		Object inv = getField(player, "net.minecraft.class_1657", "field_7514", "Lnet/minecraft/class_1661;", "inventory");
		if (inv != null) {
			int sel = getIntField(inv, "net.minecraft.class_1661", "field_7545", "I", "selectedSlot", 0);
			snap.selectedSlot = sel + 1;
			populateInventory(inv, snap);
		}

		populateClientPlayersAndEntities(player, world, snap);
		if (snap.onlinePlayers.isEmpty() && srv != null) {
			populateServerPlayers(srv, snap);
		}
		return snap;
	}

	@Override
	public void setKeyState(String keyAction, boolean pressed) {
		Object mc = this.clientInstance;
		if (mc == null) {
			return;
		}
		Object options = getField(mc, "net.minecraft.class_310", "field_1690", "Lnet/minecraft/class_315;", "options");
		if (options == null) {
			return;
		}
		String interField;
		String fallbackField;
		switch (keyAction) {
			case "forward":
				interField = "field_1894";
				fallbackField = "forwardKey";
				break;
			case "back":
				interField = "field_1881";
				fallbackField = "backKey";
				break;
			case "left":
				interField = "field_1913";
				fallbackField = "leftKey";
				break;
			case "right":
				interField = "field_1849";
				fallbackField = "rightKey";
				break;
			case "jump":
				interField = "field_1903";
				fallbackField = "jumpKey";
				break;
			case "sneak":
				interField = "field_1832";
				fallbackField = "sneakKey";
				break;
			case "sprint":
				interField = "field_1867";
				fallbackField = "sprintKey";
				break;
			case "attack":
				interField = "field_1886";
				fallbackField = "attackKey";
				break;
			case "use":
				interField = "field_1904";
				fallbackField = "useKey";
				break;
			default:
				return;
		}

		Object keyBinding = getField(options, "net.minecraft.class_315", interField, "Lnet/minecraft/class_304;", fallbackField);
		if (keyBinding == null) {
			return;
		}
		boolean previouslyPressed = getBooleanField(keyBinding, "net.minecraft.class_304", "field_1653", "Z", "pressed", false);
		invokeMappedWithArgs(keyBinding, "net.minecraft.class_304", "method_23481", "(Z)V", "setPressed",
			new Class<?>[]{boolean.class}, new Object[]{Boolean.valueOf(pressed)});
		setFieldValue(keyBinding, "net.minecraft.class_304", "field_1653", "Z", "pressed", Boolean.valueOf(pressed));
		if (pressed && !previouslyPressed) {
			int cur = getIntField(keyBinding, "net.minecraft.class_304", "field_1661", "I", "timesPressed", 0);
			setFieldValue(keyBinding, "net.minecraft.class_304", "field_1661", "I", "timesPressed", Integer.valueOf(cur + 1));
		}
	}

	@Override
	public String triggerAction(String action, boolean fullStack) {
		Object mc = this.clientInstance;
		if (mc == null) {
			return "Client actions require a Minecraft client environment.";
		}
		Object player = getField(mc, "net.minecraft.class_310", "field_1724", "Lnet/minecraft/class_746;", "player");
		Object options = getField(mc, "net.minecraft.class_310", "field_1690", "Lnet/minecraft/class_315;", "options");

		switch (action) {
			case "attack": {
				String mName = resolver.mapMethodName("intermediary", "net.minecraft.class_310", "method_1536", "()V");
				if ("method_1536".equals(mName)) {
					mName = resolver.mapMethodName("intermediary", "net.minecraft.class_310", "method_1536", "()Z");
				}
				invokeNoArgAnyReturn(mc, mName, "doAttack");
				return "Triggered attack / left-click swing.";
			}
			case "use":
				invokeMapped(mc, "net.minecraft.class_310", "method_1583", "()V", "doItemUse");
				return "Triggered item use / right-click interact.";
			case "drop":
				if (player == null) {
					return "Not currently in a world.";
				}
				if (!invokeMappedWithArgs(player, "net.minecraft.class_746", "method_7290", "(Z)Lnet/minecraft/class_1542;",
					"dropSelectedItem", new Class<?>[]{boolean.class}, new Object[]{Boolean.valueOf(fullStack)})) {
					Object inv = getField(player, "net.minecraft.class_1657", "field_7514", "Lnet/minecraft/class_1661;", "inventory");
					if (inv != null) {
						invokeMappedWithArgs(inv, "net.minecraft.class_1661", "method_37417", "(Z)Lnet/minecraft/class_1799;",
							"dropSelectedItem", new Class<?>[]{boolean.class}, new Object[]{Boolean.valueOf(fullStack)});
					}
				}
				return fullStack ? "Dropped entire stack from selected slot." : "Dropped 1 item from selected slot.";
			case "swap":
				if (options != null) {
					Object swapKey = getField(options, "net.minecraft.class_315", "field_1831", "Lnet/minecraft/class_304;", "swapHandsKey");
					if (swapKey != null) {
						int cur = getIntField(swapKey, "net.minecraft.class_304", "field_1661", "I", "timesPressed", 0);
						setFieldValue(swapKey, "net.minecraft.class_304", "field_1661", "I", "timesPressed", Integer.valueOf(cur + 1));
					}
				}
				return "Swapped main hand and offhand items.";
			case "respawn":
				if (player != null) {
					invokeMapped(player, "net.minecraft.class_746", "method_7331", "()V", "requestRespawn");
				}
				return "Sent respawn request.";
			case "disconnect": {
				Object world = getField(mc, "net.minecraft.class_310", "field_1687", "Lnet/minecraft/class_638;", "world");
				if (world != null) {
					invokeMapped(world, "net.minecraft.class_638", "method_8525", "()V", "disconnect");
				}
				invokeNoArgAnyReturn(mc, resolver.mapMethodName("intermediary", "net.minecraft.class_310", "method_18099", "()V"), "disconnect");
				return "Disconnected from world/server.";
			}
			default:
				return "Unknown action: " + action;
		}
	}

	@Override
	public String selectHotbarSlot(int slotZeroBased) {
		Object mc = this.clientInstance;
		if (mc == null) {
			return "Hotbar selection requires a Minecraft client.";
		}
		Object player = getField(mc, "net.minecraft.class_310", "field_1724", "Lnet/minecraft/class_746;", "player");
		if (player == null) {
			return "Player is not in a world.";
		}
		Object inv = getField(player, "net.minecraft.class_1657", "field_7514", "Lnet/minecraft/class_1661;", "inventory");
		if (inv == null) {
			return "Inventory unavailable.";
		}
		invokeBySimpleName(inv, "setSelectedSlot", new Class<?>[]{int.class}, new Object[]{Integer.valueOf(slotZeroBased)});
		setFieldValue(inv, "net.minecraft.class_1661", "field_7545", "I", "selectedSlot", Integer.valueOf(slotZeroBased));
		return "Selected hotbar slot **" + (slotZeroBased + 1) + "**.";
	}

	@Override
	public String setRotation(float yaw, float pitch, boolean relative) {
		Object mc = this.clientInstance;
		if (mc == null) {
			return "Rotation requires a Minecraft client.";
		}
		Object player = getField(mc, "net.minecraft.class_310", "field_1724", "Lnet/minecraft/class_746;", "player");
		if (player == null) {
			return "Player is not in a world.";
		}
		float curYaw = getAngle(player, "method_36454", "field_6031", "getYaw", "yaw");
		float curPitch = getAngle(player, "method_36455", "field_5965", "getPitch", "pitch");
		float nextYaw = relative ? (curYaw + yaw) : yaw;
		float nextPitch = Math.max(-90f, Math.min(90f, relative ? (curPitch + pitch) : pitch));

		invokeMappedWithArgs(player, "net.minecraft.class_1297", "method_36456", "(F)V", "setYaw",
			new Class<?>[]{float.class}, new Object[]{Float.valueOf(nextYaw)});
		invokeMappedWithArgs(player, "net.minecraft.class_1297", "method_36457", "(F)V", "setPitch",
			new Class<?>[]{float.class}, new Object[]{Float.valueOf(nextPitch)});
		setFieldValue(player, "net.minecraft.class_1297", "field_6031", "F", "yaw", Float.valueOf(nextYaw));
		setFieldValue(player, "net.minecraft.class_1297", "field_5965", "F", "pitch", Float.valueOf(nextPitch));
		return String.format("Camera set to Yaw `%.1f°`, Pitch `%.1f°`.", Float.valueOf(nextYaw), Float.valueOf(nextPitch));
	}

	@Override
	public String sendChatMessage(String text) {
		Object mc = this.clientInstance;
		if (mc != null) {
			Object player = getField(mc, "net.minecraft.class_310", "field_1724", "Lnet/minecraft/class_746;", "player");
			if (player != null) {
				Object netHandler = getField(player, "net.minecraft.class_746", "field_3944", "Lnet/minecraft/class_634;", "networkHandler");
				// 1.19.3 - 1.21.11
				if (netHandler != null && invokeMappedWithArgs(netHandler, "net.minecraft.class_634", "method_45729", "(Ljava/lang/String;)V",
					"sendChatMessage", new Class<?>[]{String.class}, new Object[]{text})) {
					return "Sent chat message: `" + text + "`";
				}
				// 1.19.0 - 1.19.2
				if (invokeByParamTypes(player, "method_44096", "sendChatMessage", 2, text)) {
					return "Sent chat message: `" + text + "`";
				}
				// 1.14.0 - 1.18.2
				if (invokeMappedWithArgs(player, "net.minecraft.class_746", "method_3142", "(Ljava/lang/String;)V",
					"sendChatMessage", new Class<?>[]{String.class}, new Object[]{text})) {
					return "Sent chat message: `" + text + "`";
				}
			}
		}
		if (serverInstance != null) {
			return executeCommand("say " + text);
		}
		return "Cannot send chat: not connected to a world or server.";
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public String executeCommand(String rawCommand) {
		String noSlash = rawCommand.trim();
		while (noSlash.startsWith("/")) {
			noSlash = noSlash.substring(1).trim();
		}

		// Execute directly via server Brigadier CommandDispatcher when available (Integrated or Dedicated server)
		Object srv = this.serverInstance;
		if (srv != null) {
			try {
				CommandDispatcher dispatcher = BrigadierRegistrar.extractDispatcher(srv);
				Object source = invokeMapped(srv, "net.minecraft.server.MinecraftServer", "method_3739", "()Lnet/minecraft/class_2168;", "getCommandSource");
				if (dispatcher != null && source != null) {
					int result = dispatcher.execute(noSlash, source);
					return "Executed server command `/" + noSlash + "` (result: " + result + ").";
				}
			} catch (Exception e) {
				return "Command error on `/" + noSlash + "`: `" + e.getMessage() + "`";
			}
		}

		// Otherwise execute from client onto remote multiplayer server
		Object mc = this.clientInstance;
		if (mc != null) {
			Object player = getField(mc, "net.minecraft.class_310", "field_1724", "Lnet/minecraft/class_746;", "player");
			if (player != null) {
				Object netHandler = getField(player, "net.minecraft.class_746", "field_3944", "Lnet/minecraft/class_634;", "networkHandler");
				if (netHandler != null) {
					if (invokeMappedWithArgs(netHandler, "net.minecraft.class_634", "method_45730", "(Ljava/lang/String;)V",
						"sendChatCommand", new Class<?>[]{String.class}, new Object[]{noSlash})) {
						return "Sent client command `/" + noSlash + "`.";
					}
					if (invokeMappedWithArgs(netHandler, "net.minecraft.class_634", "method_45731", "(Ljava/lang/String;)Z",
						"sendCommand", new Class<?>[]{String.class}, new Object[]{noSlash})) {
						return "Sent client command `/" + noSlash + "`.";
					}
				}
				if (invokeByParamTypes(player, "method_44099", "sendCommand", 1, noSlash)) {
					return "Sent client command `/" + noSlash + "`.";
				}
				if (invokeMappedWithArgs(player, "net.minecraft.class_746", "method_3142", "(Ljava/lang/String;)V",
					"sendChatMessage", new Class<?>[]{String.class}, new Object[]{"/" + noSlash})) {
					return "Sent client command `/" + noSlash + "`.";
				}
			}
		}
		return "Cannot run command: not in an active world or server.";
	}

	@Override
	public String connectToServer(String serverAddress) {
		Object mc = this.clientInstance;
		if (mc == null) {
			return "Connecting to multiplayer servers requires a Minecraft client.";
		}
		try {
			// Disconnect cleanly if already in world
			Object world = getField(mc, "net.minecraft.class_310", "field_1687", "Lnet/minecraft/class_638;", "world");
			if (world != null) {
				triggerAction("disconnect", false);
			}
			String titleClsName = resolver.mapClassName("intermediary", "net.minecraft.class_442");
			Object titleScreen = Class.forName(titleClsName).getDeclaredConstructor().newInstance();

			String connectClsName = resolver.mapClassName("intermediary", "net.minecraft.class_412");
			Class<?> connectCls = Class.forName(connectClsName);
			String serverInfoClsName = resolver.mapClassName("intermediary", "net.minecraft.class_642");
			Class<?> serverInfoCls = Class.forName(serverInfoClsName);

			Object serverInfo = instantiateServerInfo(serverInfoCls, serverAddress);

			// 1.17+ ConnectScreen.connect(...) static helper
			String addrClsName = resolver.mapClassName("intermediary", "net.minecraft.class_639");
			Class<?> addrCls = Class.forName(addrClsName);
			Object parsedAddr = null;
			for (Method m : addrCls.getMethods()) {
				if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class && m.getReturnType() == addrCls) {
					parsedAddr = m.invoke(null, serverAddress);
					break;
				}
			}
			for (Method m : connectCls.getMethods()) {
				if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
					Class<?>[] p = m.getParameterTypes();
					if (p.length == 4 && p[2] == addrCls) {
						m.invoke(null, titleScreen, mc, parsedAddr, serverInfo);
						return "Connecting Minecraft client to `" + serverAddress + "`...";
					}
					if (p.length == 5 && p[2] == addrCls) {
						m.invoke(null, titleScreen, mc, parsedAddr, serverInfo, Boolean.FALSE);
						return "Connecting Minecraft client to `" + serverAddress + "`...";
					}
					if (p.length == 6 && p[2] == addrCls) {
						m.invoke(null, titleScreen, mc, parsedAddr, serverInfo, Boolean.FALSE, null);
						return "Connecting Minecraft client to `" + serverAddress + "`...";
					}
				}
			}
			// 1.14 - 1.16.5 ConnectScreen constructor `(Screen, MinecraftClient, ServerInfo)`
			for (java.lang.reflect.Constructor<?> c : connectCls.getConstructors()) {
				if (c.getParameterCount() == 3) {
					Object screen = c.newInstance(titleScreen, mc, serverInfo);
					invokeByParamCount(mc, "method_1507", "openScreen", 1, screen);
					return "Connecting Minecraft client to `" + serverAddress + "`...";
				}
			}
			return "Unable to find ConnectScreen entry point on this edition.";
		} catch (Throwable t) {
			return "Connect failed: `" + t.getMessage() + "`";
		}
	}

	@Override
	public void displayLocalMessage(String message) {
		System.out.println("[RCM] " + message);
		Object mc = this.clientInstance;
		if (mc == null) {
			return;
		}
		try {
			Object textObj = createTextObject("[RCM] " + message);
			Object player = getField(mc, "net.minecraft.class_310", "field_1724", "Lnet/minecraft/class_746;", "player");
			if (player != null && textObj != null) {
				if (!invokeByParamTypes(player, "method_7353", "sendMessage", 2, textObj, Boolean.FALSE)) {
					invokeByParamTypes(player, "method_43496", "sendMessage", 1, textObj);
				}
			}
		} catch (Throwable ignored) {
		}
	}

	@Override
	public CompletableFuture<byte[]> captureScreenshotPng() {
		final CompletableFuture<byte[]> future = new CompletableFuture<byte[]>();
		Object mc = this.clientInstance;
		if (mc == null || !isClientEnvironment()) {
			future.complete(captureDesktopFallback());
			return future;
		}

		executeOnGameThread(new Runnable() {
			@Override
			public void run() {
				try {
					Path tempRoot = getConfigDir().resolve("rcm-snap-" + UUID.randomUUID().toString());
					Files.createDirectories(tempRoot);
					final File tempDir = tempRoot.toFile();
					Object fb = invokeMapped(mc, "net.minecraft.class_310", "method_1522", "()Lnet/minecraft/class_276;", "getFramebuffer");

					String recorderClsName = resolver.mapClassName("intermediary", "net.minecraft.class_318");
					Class<?> recorderCls = Class.forName(recorderClsName);

					final Consumer<Object> onSaved = new Consumer<Object>() {
						@Override
						public void accept(Object textMsg) {
							try {
								Path shotsDir = tempRoot.resolve("screenshots");
								byte[] found = null;
								if (Files.isDirectory(shotsDir)) {
									DirectoryStream<Path> ds = Files.newDirectoryStream(shotsDir, "*.png");
									try {
										for (Path p : ds) {
											found = Files.readAllBytes(p);
											Files.deleteIfExists(p);
											break;
										}
									} finally {
										ds.close();
									}
									Files.deleteIfExists(shotsDir);
								}
								Files.deleteIfExists(tempRoot);
								if (found != null && found.length > 0) {
									future.complete(found);
								} else {
									future.complete(captureDesktopFallback());
								}
							} catch (Throwable err) {
								future.complete(captureDesktopFallback());
							}
						}
					};

					boolean invoked = false;
					for (Method m : recorderCls.getMethods()) {
						if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
							continue;
						}
						Class<?>[] params = m.getParameterTypes();
						// 1.18 - 1.21.11: saveScreenshot(File, Framebuffer, Consumer)
						if (params.length == 3 && params[0] == File.class && params[2] == Consumer.class) {
							m.invoke(null, tempDir, fb, onSaved);
							invoked = true;
							break;
						}
						// 1.14 - 1.17.1: saveScreenshot(File, int, int, Framebuffer, Consumer)
						if (params.length == 5 && params[0] == File.class && params[1] == int.class && params[2] == int.class && params[4] == Consumer.class) {
							int width = getIntField(fb, "net.minecraft.class_276", "field_1482", "I", "textureWidth", 1280);
							int height = getIntField(fb, "net.minecraft.class_276", "field_1481", "I", "textureHeight", 720);
							m.invoke(null, tempDir, Integer.valueOf(width), Integer.valueOf(height), fb, onSaved);
							invoked = true;
							break;
						}
					}
					if (!invoked) {
						Files.deleteIfExists(tempRoot);
						future.complete(captureDesktopFallback());
					}
				} catch (Throwable t) {
					future.complete(captureDesktopFallback());
				}
			}
		});
		return future;
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

	private Object createTextObject(String msg) throws Exception {
		String textIntfName = resolver.mapClassName("intermediary", "net.minecraft.class_2561");
		Class<?> textCls = Class.forName(textIntfName);
		// 1.19+: Text.literal(String) -> method_43470
		String litMethod = resolver.mapMethodName("intermediary", "net.minecraft.class_2561", "method_43470", "(Ljava/lang/String;)Lnet/minecraft/class_5250;");
		for (Method m : textCls.getMethods()) {
			if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
				&& (m.getName().equals(litMethod) || "literal".equals(m.getName()) || "of".equals(m.getName()))
				&& m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
				return m.invoke(null, msg);
			}
		}
		// 1.14 - 1.18.2: new LiteralText(String) -> class_2585
		String litClsName = resolver.mapClassName("intermediary", "net.minecraft.class_2585");
		return Class.forName(litClsName).getConstructor(String.class).newInstance(msg);
	}

	private static Object instantiateServerInfo(Class<?> serverInfoCls, String address) throws Exception {
		for (java.lang.reflect.Constructor<?> c : serverInfoCls.getConstructors()) {
			Class<?>[] p = c.getParameterTypes();
			if (p.length == 2 && p[0] == String.class && p[1] == String.class) {
				return c.newInstance("Remote Server", address);
			}
			if (p.length == 3 && p[0] == String.class && p[1] == String.class && p[2] == boolean.class) {
				return c.newInstance("Remote Server", address, Boolean.FALSE);
			}
			if (p.length == 3 && p[0] == String.class && p[1] == String.class && p[2].isEnum()) {
				Object enumConst = p[2].getEnumConstants()[p[2].getEnumConstants().length - 1];
				return c.newInstance("Remote Server", address, enumConst);
			}
		}
		throw new NoSuchMethodException("ServerInfo constructor");
	}

	private void populateInventory(Object inv, PlayerSnapshot snap) {
		try {
			Object sizeObj = invokeMapped(inv, "net.minecraft.class_1263", "method_5439", "()I", "size");
			int total = sizeObj instanceof Number ? ((Number) sizeObj).intValue() : 41;
			for (int i = 0; i < total; i++) {
				Object stack = invokeMappedReturn(inv, "net.minecraft.class_1263", "method_5438", "(I)Lnet/minecraft/class_1799;",
					"getStack", new Class<?>[]{int.class}, new Object[]{Integer.valueOf(i)});
				if (stack == null) {
					continue;
				}
				Object emptyObj = invokeMapped(stack, "net.minecraft.class_1799", "method_7960", "()Z", "isEmpty");
				boolean empty = emptyObj instanceof Boolean ? ((Boolean) emptyObj).booleanValue() : false;
				if (empty) {
					continue;
				}
				Object countObj = invokeMapped(stack, "net.minecraft.class_1799", "method_7947", "()I", "getCount");
				int count = countObj instanceof Number ? ((Number) countObj).intValue() : 1;
				Object nameText = invokeMapped(stack, "net.minecraft.class_1799", "method_7964", "()Lnet/minecraft/class_2561;", "getName");
				String itemName = extractTextString(nameText);
				String label = itemName + " x" + count;
				if (i == snap.selectedSlot - 1) {
					snap.mainHandItem = label;
				} else if (i == 40) {
					snap.offHandItem = label;
				}
				String prefix = i < 9 ? "[Hotbar " + (i + 1) + "]" : (i == 40 ? "[Offhand]" : (i >= 36 && i <= 39 ? "[Armor]" : "[Slot " + i + "]"));
				snap.inventorySummary.add(prefix + " " + label);
			}
		} catch (Throwable ignored) {
		}
	}

	@SuppressWarnings("unchecked")
	private void populateClientPlayersAndEntities(Object selfPlayer, Object world, PlayerSnapshot snap) {
		try {
			Object netHandler = getField(selfPlayer, "net.minecraft.class_746", "field_3944", "Lnet/minecraft/class_634;", "networkHandler");
			if (netHandler != null) {
				Object listObj = invokeMapped(netHandler, "net.minecraft.class_634", "method_2880", "()Ljava/util/Collection;", "getPlayerList");
				if (listObj instanceof Iterable) {
					for (Object entry : (Iterable<Object>) listObj) {
						Object profile = invokeMapped(entry, "net.minecraft.class_640", "method_2966", "()Lcom/mojang/authlib/GameProfile;", "getProfile");
						if (profile != null) {
							String name = extractProfileName(profile);
							if (!name.isEmpty()) {
								snap.onlinePlayers.add(name);
							}
						}
					}
				}
			}

			Object entitiesIter = invokeMapped(world, "net.minecraft.class_638", "method_18112", "()Ljava/lang/Iterable;", "getEntities");
			if (entitiesIter instanceof Iterable) {
				List<EntityDist> nearby = new ArrayList<EntityDist>();
				for (Object ent : (Iterable<Object>) entitiesIter) {
					if (ent == null || ent == selfPlayer) {
						continue;
					}
					double ex = getCoord(ent, "method_23317", "field_6014", "getX", "x");
					double ey = getCoord(ent, "method_23318", "field_6036", "getY", "y");
					double ez = getCoord(ent, "method_23321", "field_5969", "getZ", "z");
					double dist = Math.sqrt((ex - snap.x) * (ex - snap.x) + (ey - snap.y) * (ey - snap.y) + (ez - snap.z) * (ez - snap.z));
					if (dist <= 64.0) {
						nearby.add(new EntityDist(dist, String.format("%s — %.1fm (%.1f, %.1f, %.1f)",
							extractEntityName(ent), Double.valueOf(dist), Double.valueOf(ex), Double.valueOf(ey), Double.valueOf(ez))));
					}
				}
				Collections.sort(nearby, new Comparator<EntityDist>() {
					@Override
					public int compare(EntityDist a, EntityDist b) {
						return Double.compare(a.dist, b.dist);
					}
				});
				for (int i = 0; i < Math.min(20, nearby.size()); i++) {
					snap.nearbyEntities.add(nearby.get(i).line);
				}
			}
		} catch (Throwable ignored) {
		}
	}

	@SuppressWarnings("unchecked")
	private void populateServerPlayers(Object srv, PlayerSnapshot snap) {
		try {
			Object pm = invokeMapped(srv, "net.minecraft.server.MinecraftServer", "method_3760", "()Lnet/minecraft/class_3324;", "getPlayerManager");
			if (pm != null) {
				Object list = invokeMapped(pm, "net.minecraft.class_3324", "method_14571", "()Ljava/util/List;", "getPlayerList");
				if (list instanceof Iterable) {
					for (Object p : (Iterable<Object>) list) {
						snap.onlinePlayers.add(extractEntityName(p));
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private static final class EntityDist {
		final double dist;
		final String line;

		EntityDist(double dist, String line) {
			this.dist = dist;
			this.line = line;
		}
	}

	private String extractEntityName(Object entity) {
		Object text = invokeMapped(entity, "net.minecraft.class_1297", "method_5477", "()Lnet/minecraft/class_2561;", "getName");
		return extractTextString(text);
	}

	private String extractTextString(Object textObj) {
		if (textObj == null) {
			return "Unknown";
		}
		for (String mName : new String[]{"getString", resolver.mapMethodName("intermediary", "net.minecraft.class_2561", "method_10851", "()Ljava/lang/String;")}) {
			try {
				Method m = textObj.getClass().getMethod(mName);
				Object v = m.invoke(textObj);
				if (v != null) {
					return String.valueOf(v);
				}
			} catch (Throwable ignored) {
			}
		}
		return String.valueOf(textObj);
	}

	private static String extractProfileName(Object profile) {
		for (String mName : new String[]{"getName", "name"}) {
			try {
				Method m = profile.getClass().getMethod(mName);
				Object v = m.invoke(profile);
				if (v != null) {
					return String.valueOf(v);
				}
			} catch (Throwable ignored) {
			}
		}
		return "";
	}

	private String extractDimensionName(Object world) {
		Object key = invokeMapped(world, "net.minecraft.class_1937", "method_27983", "()Lnet/minecraft/class_5321;", "getRegistryKey");
		if (key != null) {
			Object id = invokeMapped(key, "net.minecraft.class_5321", "method_29177", "()Lnet/minecraft/class_2960;", "getValue");
			if (id != null) {
				return String.valueOf(id);
			}
			return String.valueOf(key);
		}
		return "minecraft:overworld";
	}

	private double getCoord(Object entity, String interMethod, String interField114, String yarnMethod, String yarnField) {
		Object val = invokeMapped(entity, "net.minecraft.class_1297", interMethod, "()D", yarnMethod);
		if (val instanceof Number) {
			return ((Number) val).doubleValue();
		}
		Object fVal = getField(entity, "net.minecraft.class_1297", interField114, "D", yarnField);
		return fVal instanceof Number ? ((Number) fVal).doubleValue() : 0.0;
	}

	private float getAngle(Object entity, String interMethod, String interField, String yarnMethod, String yarnField) {
		Object val = invokeMapped(entity, "net.minecraft.class_1297", interMethod, "()F", yarnMethod);
		if (val instanceof Number) {
			return ((Number) val).floatValue();
		}
		Object fVal = getField(entity, "net.minecraft.class_1297", interField, "F", yarnField);
		return fVal instanceof Number ? ((Number) fVal).floatValue() : 0f;
	}

	private float invokeFloat(Object obj, String interOwner, String interMethod, String desc, String fallback, float def) {
		Object v = invokeMapped(obj, interOwner, interMethod, desc, fallback);
		return v instanceof Number ? ((Number) v).floatValue() : def;
	}

	private int getIntField(Object obj, String interOwner, String interField, String desc, String fallback, int def) {
		Object v = getField(obj, interOwner, interField, desc, fallback);
		return v instanceof Number ? ((Number) v).intValue() : def;
	}

	private boolean getBooleanField(Object obj, String interOwner, String interField, String desc, String fallback, boolean def) {
		Object v = getField(obj, interOwner, interField, desc, fallback);
		return v instanceof Boolean ? ((Boolean) v).booleanValue() : def;
	}

	private Object getField(Object obj, String interOwner, String interField, String desc, String fallbackName) {
		if (obj == null) {
			return null;
		}
		String mapped = resolver.mapFieldName("intermediary", interOwner, interField, desc);
		Field f = findField(obj.getClass(), mapped);
		if (f == null) {
			f = findField(obj.getClass(), fallbackName);
		}
		if (f == null) {
			f = findField(obj.getClass(), interField);
		}
		if (f == null) {
			return null;
		}
		try {
			f.setAccessible(true);
			return f.get(obj);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private void setFieldValue(Object obj, String interOwner, String interField, String desc, String fallbackName, Object value) {
		if (obj == null) {
			return;
		}
		String mapped = resolver.mapFieldName("intermediary", interOwner, interField, desc);
		Field f = findField(obj.getClass(), mapped);
		if (f == null) {
			f = findField(obj.getClass(), fallbackName);
		}
		if (f == null) {
			return;
		}
		try {
			f.setAccessible(true);
			f.set(obj, value);
		} catch (Throwable ignored) {
		}
	}

	private static Field findField(Class<?> cls, String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		Class<?> cur = cls;
		while (cur != null && cur != Object.class) {
			for (Field f : cur.getDeclaredFields()) {
				if (name.equals(f.getName())) {
					return f;
				}
			}
			cur = cur.getSuperclass();
		}
		return null;
	}

	private Object invokeMapped(Object obj, String interOwner, String interMethod, String desc, String fallback) {
		if (obj == null) {
			return null;
		}
		String mapped = resolver.mapMethodName("intermediary", interOwner, interMethod, desc);
		return invokeNoArgAnyReturn(obj, mapped, fallback);
	}

	private Object invokeMappedReturn(
		Object obj, String interOwner, String interMethod, String desc,
		String fallback, Class<?>[] types, Object[] args
	) {
		if (obj == null) {
			return null;
		}
		String mapped = resolver.mapMethodName("intermediary", interOwner, interMethod, desc);
		for (String candidate : new String[]{mapped, fallback, interMethod}) {
			Method m = findMethod(obj.getClass(), candidate, types);
			if (m != null) {
				try {
					m.setAccessible(true);
					return m.invoke(obj, args);
				} catch (Throwable ignored) {
				}
			}
		}
		return null;
	}

	private boolean invokeMappedWithArgs(
		Object obj, String interOwner, String interMethod, String desc,
		String fallback, Class<?>[] types, Object[] args
	) {
		if (obj == null) {
			return false;
		}
		String mapped = resolver.mapMethodName("intermediary", interOwner, interMethod, desc);
		for (String candidate : new String[]{mapped, fallback, interMethod}) {
			Method m = findMethod(obj.getClass(), candidate, types);
			if (m != null) {
				try {
					m.setAccessible(true);
					m.invoke(obj, args);
					return true;
				} catch (Throwable ignored) {
				}
			}
		}
		return false;
	}

	private boolean invokeByParamTypes(Object obj, String interMethod, String fallbackName, int paramCount, Object... givenArgs) {
		Class<?> cur = obj.getClass();
		while (cur != null && cur != Object.class) {
			for (Method m : cur.getDeclaredMethods()) {
				if ((m.getName().equals(interMethod) || m.getName().equals(fallbackName)) && m.getParameterCount() == paramCount) {
					try {
						m.setAccessible(true);
						Object[] actual = new Object[paramCount];
						for (int i = 0; i < paramCount; i++) {
							actual[i] = i < givenArgs.length ? givenArgs[i] : null;
						}
						m.invoke(obj, actual);
						return true;
					} catch (Throwable ignored) {
					}
				}
			}
			cur = cur.getSuperclass();
		}
		return false;
	}

	private static boolean invokeByParamCount(Object obj, String interName, String fallback, int count, Object arg) {
		for (Method m : obj.getClass().getMethods()) {
			if (m.getParameterCount() == count && (m.getName().equals(interName) || m.getName().equals(fallback) || "setScreen".equals(m.getName()))) {
				try {
					m.invoke(obj, arg);
					return true;
				} catch (Throwable ignored) {
				}
			}
		}
		return false;
	}

	private static void invokeBySimpleName(Object obj, String methodName, Class<?>[] types, Object[] args) {
		Method m = findMethod(obj.getClass(), methodName, types);
		if (m != null) {
			try {
				m.setAccessible(true);
				m.invoke(obj, args);
			} catch (Throwable ignored) {
			}
		}
	}

	private static Object invokeNoArgAnyReturn(Object obj, String primaryName, String fallbackName) {
		for (String candidate : new String[]{primaryName, fallbackName}) {
			Method m = findMethod(obj.getClass(), candidate, new Class<?>[0]);
			if (m != null) {
				try {
					m.setAccessible(true);
					return m.invoke(obj);
				} catch (Throwable ignored) {
				}
			}
		}
		return null;
	}

	private static Method findMethod(Class<?> cls, String name, Class<?>[] types) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		Class<?> cur = cls;
		while (cur != null && cur != Object.class) {
			try {
				return cur.getDeclaredMethod(name, types);
			} catch (NoSuchMethodException ignored) {
			}
			cur = cur.getSuperclass();
		}
		return null;
	}
}
