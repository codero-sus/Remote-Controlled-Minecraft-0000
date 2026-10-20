# Remote-Controlled-Minecraft (RCM)

**Full Remote Control, Live Visual Monitoring, File Management, Automation & Administration for Minecraft (Fabric Mod for Every Fabric Edition in Existence) + PC (Standalone Companion App) via Discord.**

---

## What's Inside

| Component | Path | Target Platforms / Editions | Highlights |
| :--- | :--- | :--- | :--- |
| **Universal Fabric Mod** | [`fabric-mod/`](fabric-mod/) | **100% of all Fabric-supported Minecraft editions** (`1.14` -> `1.21.11`, `26.1` -> `26.3+`, plus all snapshots/pre-releases/RCs) on **Client & Dedicated Server** | Built-in zero-dependency Discord Gateway v10 + REST engine, interactive 20-button Discord Gamepad with live framebuffer screenshots, movement/camera/auto-walk (`goto`), mining/combat/inventory control, 2-way chat bridge, full file read/write/edit/upload/download, macro automation, and host shell execution |
| **Standalone PC & Instance Controller** | [`pc-controller/`](pc-controller/) | **Windows x64 (`.exe`)**, **Linux x64**, **macOS arm64**, or Python 3.10+ (`rcm-pc`) | System telemetry (CPU, RAM, disk, network, battery), process manager & app launcher, full file manager (`ls`/`read`/`write`/`edit`/`upload`/`download`/`zip`/`rm`), multi-monitor live screenshots, mouse & keyboard control, and managed Minecraft server runner (`mc-start` / `mc-cmd` / `mc-logs` / `mc-stop`) |
| **Dynamic Multi-Edition CI Matrix** | [`.github/workflows/build-all-fabric-editions.yml`](.github/workflows/build-all-fabric-editions.yml) | Queries `meta.fabricmc.net/v2/versions/game` live on every run | Compiles **every stable Fabric release** directly with Gradle + Fabric Loom across 12 parallel shards, builds Universal Multi-Version JARs + dedicated JARs for **every single snapshot/pre-release/RC edition**, bytecode-verifies every JAR, builds Windows/Linux/macOS PC executables, and publishes GitHub Releases automatically |

---

## 60-Second Quick Start

### 1. Create Your Own Discord Bot
1. Open the [Discord Developer Portal](https://discord.com/developers/applications) -> **New Application** -> **Bot**.
2. Click **Reset Token** and copy your **Bot Token** (optionally enable **Message Content Intent** under *Privileged Gateway Intents* if you want prefix commands like `!mc` / `!pc` in server channels in addition to `/mc` & `/pc` Slash Commands and `@Bot` mentions).
3. Invite the bot to your Discord server under **OAuth2 -> URL Generator**:
   - Scopes: `bot`, `applications.commands`
   - Permissions: `Send Messages`, `Attach Files`, `Embed Links`, `Read Message History`

---

### 2A. Using the Fabric Mod (`fabric-mod/`)
1. Drop the `.jar` for your Minecraft edition (or one of the two drop-in **Universal JARs**: `UNIVERSAL-1.14-to-1.21.11` or `UNIVERSAL-26.x-plus`) into `.minecraft/mods/`! *(No `fabric-api` required — works standalone on `fabric-loader`!)*
2. Launch Minecraft and set your token either:
   - **In-game (Singleplayer / Server Console):** `/rcm token YOUR_BOT_TOKEN` *(or `.rcm token YOUR_BOT_TOKEN` in multiplayer chat — intercepted locally and never sent to the server!)*
   - **Or via config file:** edit `.minecraft/config/rcm.json` and set `"botToken"`.
3. Look at the console or run `/rcm pair` in-game to get your **6-digit pairing code**, then run in Discord:
   ```
   !mc pair 123456
   ```
   *(or Slash Command `/mc pair code:123456`)*
4. Open your live interactive gamepad + live viewport screenshot:
   ```
   !mc panel
   ```

---

### 2B. Using the Standalone PC & Instance Controller (`pc-controller/`)
Download the prebuilt binary for your OS (`rcm-pc-controller-windows-x64.exe`, `rcm-pc-controller-linux-x64`, `rcm-pc-controller-macos-arm64`) **or** install from source:
```bash
pip install ./pc-controller
rcm-pc init --token YOUR_DISCORD_BOT_TOKEN
rcm-pc run
```
Pair from Discord with the 6-digit code printed in your terminal:
```
!pc pair 123456
```
Then open the live desktop + game controller panel:
```
!pc panel
```

---

## Full Discord Command Reference

### Fabric Mod Commands (`!mc ...` or `/mc ...`)

| Category | Command | Description |
| :--- | :--- | :--- |
| **Pairing & Setup** | `!mc pair <code>` | Authorize your Discord account and save your ID to `config/rcm.json` |
| **Live Gamepad** | `!mc panel` | Open 20-button interactive movement/action/hotbar panel with live screenshot |
| **Monitoring** | `!mc status` | Health, food, XP, XYZ coords, yaw/pitch, dimension, equipped items, FPS, RAM, players |
| | `!mc screenshot` | Capture full-resolution OpenGL/GPU framebuffer PNG screenshot |
| | `!mc inventory` | Full slot-by-slot armor, offhand, hotbar, and inventory report |
| | `!mc entities` | Online players list + nearest entities with exact distances & coords |
| **Movement & Nav** | `!mc move <key> [ms\|toggle\|on\|off]` | Hold or toggle `forward`, `back`, `left`, `right`, `jump`, `sneak`, `sprint`, `attack`, `use` |
| | `!mc look <yaw> <pitch>` | Set exact player camera rotation |
| | `!mc turn <dyaw> <dpitch>` | Relative camera turn (e.g. `!mc turn 90 0`) |
| | `!mc goto <x> <y> <z> [sec]` | Autonomous coordinate walker with automatic obstacle jumping & arrival alert |
| | `!mc stop` | Emergency stop: release all held keys and cancel active `goto`/macros |
| **Combat & Items** | `!mc attack [count \| hold <ms>]` | Swing weapon / attack entity / hold continuous block mining |
| | `!mc mine [duration_ms]` | Mine continuously for `duration_ms` |
| | `!mc use [count \| hold <ms>]` | Right-click place block / open chest / interact / eat |
| | `!mc eat [duration_ms]` | Hold right-click to eat food or drink potion |
| | `!mc slot <1-9>` | Switch active hotbar slot |
| | `!mc swap` | Swap main hand and offhand items |
| | `!mc drop [all]` | Drop single item or full stack (`drop all`) |
| | `!mc respawn` | Respawn immediately from death screen |
| **Multiplayer & Chat** | `!mc connect <ip[:port]>` | Connect client directly to any multiplayer server |
| | `!mc disconnect` | Cleanly disconnect from server / save singleplayer world |
| | `!mc say <message>` | Send real chat message in Minecraft |
| | `!mc cmd <mc_command>` | Run any Minecraft command (`cmd tp @s 0 80 0`, `cmd gamemode creative`, etc.) |
| | `!mc bridge <here\|2way\|on\|off>` | Configure live 1-way or 2-way Minecraft <-> Discord chat bridge |
| | `!mc macro <step1 ; wait 500 ; step2>` | Run multi-step automated sequences |
| **Full File Control** | `!mc files ls [path]` | List files and directories with sizes & timestamps |
| | `!mc files read <path> [start] [end]` | Read config/log/properties/script files with line numbers |
| | `!mc files write <path> <content>` | Create or overwrite any file (supports triple-backtick codeblocks!) |
| | `!mc files edit <path> <old> -> <new>` | Edit file contents in place (`->` inline or `===` multi-line) |
| | `!mc files append <path> <content>` | Append lines to a file |
| | `!mc files upload [dest_path]` | Save attached Discord files (`.jar`, `.zip`, configs, etc.) to disk |
| | `!mc files download <path>` | Upload any file (or auto-zipped directory!) straight to Discord |
| | `!mc files mkdir\|mv\|cp\|rm ...` | Create folders, rename/move, copy recursively, or delete files/folders |
| **System Shell** | `!mc exec <command>` | Run host shell command with captured stdout/stderr |

---

### Standalone PC Controller Commands (`!pc ...` or `/pc ...`)

| Category | Command | Description |
| :--- | :--- | :--- |
| **System & Procs** | `!pc panel` | Interactive live desktop screenshot + mouse/keyboard control buttons |
| | `!pc status` | Hostname, OS, uptime, battery, CPU %, RAM, Disk, Network TX/RX |
| | `!pc ps [query]` | Top processes sorted by memory & CPU usage |
| | `!pc kill [-9] <pid\|name>` | Terminate or force-kill processes by PID or process name |
| | `!pc launch <command>` | Launch GUI apps, launchers, or scripts detached |
| | `!pc exec <command>` | Run async shell command in current working directory |
| **Managed MC Runner** | `!pc mc-start <java ...>` | Start any Vanilla/Fabric/Forge/Paper server or client with live stdin pipe |
| | `!pc mc-cmd <command>` | Send console command directly to running Minecraft stdin and return response |
| | `!pc mc-logs [lines]` | Tail recent Minecraft console output |
| | `!pc mc-stop` | Gracefully save (`stop`) and shut down managed Minecraft process |
| **File Manager** | `!pc pwd` / `!pc cd <path>` / `!pc ls [path]` | Navigate and browse host filesystem |
| | `!pc read <path> [start] [end]` | View files with line numbers |
| | `!pc write <path> <content>` | Write/overwrite files directly from Discord |
| | `!pc edit <path> <old> -> <new>` | Search-and-replace edit files in place (`->` or `===`) |
| | `!pc append <path> <content>` | Append text to file |
| | `!pc upload [dest]` / `!pc download <path>` | Upload Discord attachments or download files/zipped folders |
| | `!pc mkdir` / `mv` / `cp` / `rm` | Full folder and file lifecycle operations |
| **Screen & Input** | `!pc screenshot [monitor]` | Capture live PNG desktop screenshot from any monitor |
| | `!pc move <x> <y>` / `!pc nudge <dx> <dy>` | Absolute or relative cursor movement |
| | `!pc click [left\|right\|double] [x y]` | Mouse click at current or specified coordinates |
| | `!pc scroll <amount>` | Scroll mouse wheel up/down |
| | `!pc type <text>` / `!pc key <k> [sec]` / `!pc hotkey <combo>` | Full keyboard typing, timed key holds, and shortcuts (`alt+tab`, `ctrl+s`) |

---

## Building Locally

```bash
# Build any obfuscated Minecraft edition (1.14 -> 1.21.11)
cd fabric-mod
./gradlew build -Pminecraft_version=1.20.1 -Pyarn_mappings=1.20.1+build.10 -Pjava_release=17 -Punobfuscated=false

# Build any unobfuscated Minecraft edition (26.1 -> 26.3+)
./gradlew build -Pminecraft_version=26.3 -Pjava_release=25 -Punobfuscated=true

# Test PC Controller & Edition Packaging Scripts
python3 -m pip install -e "./pc-controller[dev]"
pytest -v pc-controller/tests
```
