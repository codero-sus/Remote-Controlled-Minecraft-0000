package dev.rcm;

import dev.rcm.core.CommandRouter;
import dev.rcm.core.FileControlManager;
import dev.rcm.core.MinecraftPlatform;
import dev.rcm.core.MotionController;
import dev.rcm.core.PlayerSnapshot;
import dev.rcm.core.RcmConfig;
import dev.rcm.util.MiniJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RcmCoreTest {

	@Test
	void miniJsonRoundTripsComplexPayloads() {
		Map<String, Object> src = new LinkedHashMap<>();
		src.put("op", 0L);
		src.put("t", "MESSAGE_CREATE");
		src.put("ok", true);
		src.put("escaped", "line1\nline2\t\"quoted\"");
		String encoded = MiniJson.stringify(src);
		Map<String, Object> decoded = MiniJson.parseObject(encoded);
		assertEquals(0, MiniJson.getInt(decoded, "op", -1));
		assertEquals("MESSAGE_CREATE", MiniJson.getString(decoded, "t", ""));
		assertTrue(MiniJson.getBoolean(decoded, "ok", false));
		assertEquals("line1\nline2\t\"quoted\"", MiniJson.getString(decoded, "escaped", ""));
	}

	@Test
	void configPairingAndAuthorizationPersists(@TempDir Path tempDir) throws Exception {
		Path cfgFile = tempDir.resolve("rcm.json");
		RcmConfig cfg = new RcmConfig(cfgFile);
		cfg.load();
		assertTrue(Files.exists(cfgFile));
		assertFalse(cfg.isAuthorized("123456789"));

		String code = cfg.getPairingCode();
		assertFalse(cfg.tryPair("123456789", "000000"));
		assertTrue(cfg.tryPair("123456789", code));
		assertTrue(cfg.isAuthorized("123456789"));

		// Reload from disk and verify persistence
		RcmConfig reloaded = new RcmConfig(cfgFile);
		reloaded.load();
		assertTrue(reloaded.isAuthorized("123456789"));
	}

	@Test
	void fileManagerSupportsWriteReadEditAppendArchiveAndDelete(@TempDir Path tempDir) throws Exception {
		RcmConfig cfg = new RcmConfig(tempDir.resolve("rcm.json"));
		cfg.load();
		FileControlManager fm = new FileControlManager(tempDir, cfg);

		fm.writeFile("server.properties", "```properties\nmotd=Old Server\npvp=false\n```");
		String read1 = fm.readFile("server.properties", 1, 10);
		assertTrue(read1.contains("motd=Old Server"));

		fm.editFile("server.properties", "motd=Old Server", "motd=Remote Controlled Server");
		fm.appendFile("server.properties", "max-players=50\n");

		String read2 = fm.readFile("server.properties", 1, 10);
		assertTrue(read2.contains("motd=Remote Controlled Server"));
		assertTrue(read2.contains("max-players=50"));

		fm.mkdir("config/sub");
		fm.copy("server.properties", "config/sub/backup.properties");
		FileControlManager.TransferPayload zip = fm.prepareDownload("config");
		assertEquals("config.zip", zip.fileName);
		assertTrue(zip.data.length > 20);

		fm.delete("config");
		assertFalse(Files.exists(tempDir.resolve("config")));
	}

	@Test
	void commandRouterAndMotionControllerDriveFullControl(@TempDir Path tempDir) throws Exception {
		RcmConfig cfg = new RcmConfig(tempDir.resolve("rcm.json"));
		cfg.load();
		FakePlatform platform = new FakePlatform(tempDir);
		MotionController motion = new MotionController();
		FileControlManager files = new FileControlManager(tempDir, cfg);
		AtomicReference<String> lastAsyncMsg = new AtomicReference<>("");
		CommandRouter router = new CommandRouter(cfg, platform, motion, files, null, lastAsyncMsg::set);

		// Unauthorized attempt blocked
		CommandRouter.CommandResponse denied = router.dispatch("owner1", "chan1", "status", Collections.emptyList());
		assertTrue(denied.content.contains("Access denied"));

		// Pair using valid 6-digit pairing code
		CommandRouter.CommandResponse paired = router.dispatch("owner1", "chan1", "pair " + cfg.getPairingCode(), Collections.emptyList());
		assertTrue(paired.content.contains("Paired successfully"));

		// Movement + button panel + file edit + command execution + shell execution
		router.dispatch("owner1", "chan1", "move forward on", Collections.emptyList());
		motion.tick(platform);
		assertEquals(Boolean.TRUE, platform.keyStates.get("forward"));

		CommandRouter.CommandResponse panelResp = router.handleButtonPress("owner1", "rcm:slot_next");
		assertNotNull(panelResp.components);
		assertEquals(4, panelResp.components.size());
		assertEquals(2, platform.selectedSlot + 1);

		CommandRouter.CommandResponse cmdResp = router.dispatch("owner1", "chan1", "cmd gamemode creative", Collections.emptyList());
		assertTrue(cmdResp.content.contains("gamemode creative"));

		CommandRouter.CommandResponse fileWrite = router.dispatch("owner1", "chan1", "files write options.txt fov:90.0", Collections.emptyList());
		assertTrue(fileWrite.content.contains("Wrote"));
		router.dispatch("owner1", "chan1", "files edit options.txt fov:90.0 -> fov:110.0", Collections.emptyList());
		assertTrue(files.readFile("options.txt", 1, 5).contains("fov:110.0"));

		CommandRouter.CommandResponse execResp = router.dispatch("owner1", "chan1", "exec echo hello_rcm", Collections.emptyList());
		assertTrue(execResp.content.contains("hello_rcm"));

		// Auto-walk arrival
		router.dispatch("owner1", "chan1", "goto 10 64 0", Collections.emptyList());
		motion.tick(platform);
		platform.x = 10.0;
		platform.z = 0.0;
		motion.tick(platform);
		assertTrue(lastAsyncMsg.get().contains("Arrived"));
	}

	private static final class FakePlatform implements MinecraftPlatform {
		final Path dir;
		final Map<String, Boolean> keyStates = new HashMap<>();
		int selectedSlot = 0;
		double x = 0.0;
		double y = 64.0;
		double z = 0.0;
		float yaw = 0f;
		float pitch = 0f;

		FakePlatform(Path dir) {
			this.dir = dir;
		}

		@Override
		public String getMinecraftVersion() {
			return "test-edition";
		}

		@Override
		public Path getGameDir() {
			return dir;
		}

		@Override
		public Path getConfigDir() {
			return dir;
		}

		@Override
		public boolean isClientEnvironment() {
			return true;
		}

		@Override
		public void onClientTick(Object clientInstance) {
		}

		@Override
		public void onServerTick(Object serverInstance) {
		}

		@Override
		public void onServerStop(Object serverInstance) {
		}

		@Override
		public Object getActiveServer() {
			return null;
		}

		@Override
		public void executeOnGameThread(Runnable task) {
			task.run();
		}

		@Override
		public PlayerSnapshot captureSnapshot() {
			PlayerSnapshot s = new PlayerSnapshot();
			s.inWorld = true;
			s.minecraftVersion = "test-edition";
			s.playerName = "Steve";
			s.health = 20f;
			s.selectedSlot = selectedSlot + 1;
			s.x = x;
			s.y = y;
			s.z = z;
			s.yaw = yaw;
			s.pitch = pitch;
			return s;
		}

		@Override
		public void setKeyState(String keyAction, boolean pressed) {
			keyStates.put(keyAction, pressed);
		}

		@Override
		public String triggerAction(String action, boolean fullStack) {
			return "Action: " + action;
		}

		@Override
		public String selectHotbarSlot(int slotZeroBased) {
			this.selectedSlot = slotZeroBased;
			return "Slot " + (slotZeroBased + 1);
		}

		@Override
		public String setRotation(float yaw, float pitch, boolean relative) {
			this.yaw = relative ? this.yaw + yaw : yaw;
			this.pitch = relative ? this.pitch + pitch : pitch;
			return "Rotated";
		}

		@Override
		public String sendChatMessage(String text) {
			return "Chat: " + text;
		}

		@Override
		public String executeCommand(String rawCommand) {
			return "Executed: " + rawCommand;
		}

		@Override
		public String connectToServer(String serverAddress) {
			return "Connecting: " + serverAddress;
		}

		@Override
		public void displayLocalMessage(String message) {
		}

		@Override
		public CompletableFuture<byte[]> captureScreenshotPng() {
			return CompletableFuture.completedFuture(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
		}
	}
}
