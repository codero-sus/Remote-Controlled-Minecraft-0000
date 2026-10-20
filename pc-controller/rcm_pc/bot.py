from __future__ import annotations

import io
import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, List, Optional, Tuple

import discord
from discord import app_commands

from .config import PcConfig
from .file_manager import PcFileManager, format_size
from .input_screen import (
    capture_screen_png,
    keyboard_hotkey,
    keyboard_press,
    keyboard_type,
    mouse_click,
    mouse_move,
    mouse_scroll,
)
from .mc_process import ManagedMinecraftProcess
from .system_control import (
    collect_system_telemetry,
    kill_process,
    launch_detached,
    list_processes,
    run_shell_command,
)

logger = logging.getLogger("rcm_pc")


@dataclass
class DispatchResult:
    text: str = ""
    embed: Optional[discord.Embed] = None
    file_tuple: Optional[Tuple[str, bytes]] = None
    attach_panel: bool = False


def _split_target_and_body(raw: str) -> Tuple[str, str]:
    s = raw.strip()
    if not s:
        return "", ""
    if s.startswith('"'):
        end = s.find('"', 1)
        if end > 1:
            return s[1:end], s[end + 1 :].strip()
    for idx, ch in enumerate(s):
        if ch.isspace():
            return s[:idx], s[idx + 1 :].strip()
    return s, ""


class PcCommandDispatcher:
    """Core authenticated command handler for standalone PC + Minecraft instance control."""

    def __init__(self, config: PcConfig) -> None:
        self.config = config
        self.files = PcFileManager(Path(config.default_workdir), config.allow_unrestricted_paths)
        self.mc = ManagedMinecraftProcess()

    def build_status_embed(self) -> discord.Embed:
        stats = collect_system_telemetry()
        emb = discord.Embed(
            title=f"PC Remote Controller — {stats['hostname']}",
            color=0x57F287,
        )
        emb.add_field(name="OS / Arch", value=f"`{stats['os']}`", inline=True)
        emb.add_field(name="Uptime", value=f"`{stats['uptime']}`", inline=True)
        emb.add_field(name="Battery", value=stats["battery"], inline=True)
        emb.add_field(
            name="CPU & Memory",
            value=(
                f"CPU: **{stats['cpu_percent']}%** ({stats['cpu_cores']} threads)\n"
                f"RAM: **{stats['ram_used_gb']} / {stats['ram_total_gb']} GB** ({stats['ram_percent']}%)"
            ),
            inline=False,
        )
        emb.add_field(
            name="Storage & Network",
            value=(
                f"Disk: **{stats['disk_used_gb']} / {stats['disk_total_gb']} GB** ({stats['disk_percent']}%)\n"
                f"Net TX/RX: **{stats['net_sent_mb']} MB / {stats['net_recv_mb']} MB**"
            ),
            inline=False,
        )
        emb.add_field(name="Working Directory", value=f"`{self.files.cwd}`", inline=False)
        emb.add_field(
            name="Managed Minecraft Process",
            value="Running" if self.mc.is_running else "Idle (`!pc mc-start <cmd>`)",
            inline=True,
        )
        return emb

    def build_help_embed(self) -> discord.Embed:
        p = self.config.prefix
        emb = discord.Embed(
            title="Remote Controlled PC & Minecraft Launcher — Commands",
            description=f"Use `/pc <subcommand>` Slash Commands or `{p} <command>` messages:",
            color=0x5865F2,
        )
        emb.add_field(
            name="Monitoring, Processes & Shell",
            value=(
                f"`{p} panel` — Interactive live screen + mouse/keyboard controller\n"
                f"`{p} status` | `{p} ps [filter]` | `{p} kill <pid|name>`\n"
                f"`{p} exec <shell_cmd>` | `{p} launch <app_or_script>`"
            ),
            inline=False,
        )
        emb.add_field(
            name="Managed Minecraft Server / Instance Runner",
            value=(
                f"`{p} mc-start <java -Xmx4G -jar server.jar nogui>`\n"
                f"`{p} mc-cmd <mc_console_command>` | `{p} mc-logs [lines]` | `{p} mc-stop`"
            ),
            inline=False,
        )
        emb.add_field(
            name="Full File System Control",
            value=(
                f"`{p} pwd` | `{p} cd <path>` | `{p} ls [path]`\n"
                f"`{p} read <path> [start] [end]`\n"
                f"`{p} write <path> <content>` | `{p} append <path> <content>`\n"
                f"`{p} edit <path> <old> -> <new>` (or `<old> === <new>`)\n"
                f"`{p} upload [dest]` (attach files!) | `{p} download <file_or_folder>`\n"
                f"`{p} mkdir <path>` | `{p} mv <src> <dst>` | `{p} cp <src> <dst>` | `{p} rm <path>`"
            ),
            inline=False,
        )
        emb.add_field(
            name="Screen Capture, Mouse & Keyboard Control",
            value=(
                f"`{p} screenshot [monitor]` | `{p} move <x> <y>` | `{p} nudge <dx> <dy>`\n"
                f"`{p} click [left|right|double] [x y]` | `{p} scroll <amount>`\n"
                f"`{p} type <text>` | `{p} key <key> [hold_sec]` | `{p} hotkey <ctrl+s>`"
            ),
            inline=False,
        )
        return emb

    async def execute(
        self,
        user_id: int,
        channel_id: int,
        raw_command: str,
        attachments: Optional[List[Tuple[str, bytes]]] = None,
    ) -> DispatchResult:
        cmdline = (raw_command or "help").strip()
        cmd, rest = _split_target_and_body(cmdline)
        cmd = cmd.lower()

        if cmd == "pair":
            if self.config.try_pair(user_id, rest):
                logger.info("Paired authorized Discord user ID %s", user_id)
                return DispatchResult(
                    text=(
                        f"Paired successfully! Discord User ID `{user_id}` is saved in `{self.config.config_path}`.\n"
                        f"Run `{self.config.prefix} panel` or `/pc panel` to launch the interactive controller!"
                    )
                )
            return DispatchResult(
                text="Invalid pairing code. Check the terminal running `rcm-pc` for the active 6-digit pairing code."
            )

        if not self.config.is_authorized(user_id):
            return DispatchResult(
                text=(
                    f"Access denied for Discord user `{user_id}`.\n"
                    f"Run `{self.config.prefix} pair <code>` or `/pc pair code:<code>` using the 6-digit code in the console."
                )
            )

        if not self.config.is_channel_allowed(channel_id):
            return DispatchResult(
                text=f"Commands are restricted to channel `<#{self.config.command_channel_id}>`."
            )

        logger.info("[Audit] User %s executed: %s", user_id, cmdline)

        try:
            if cmd in ("", "help"):
                return DispatchResult(embed=self.build_help_embed())
            if cmd in ("status", "info", "stats"):
                return DispatchResult(embed=self.build_status_embed())
            if cmd in ("panel", "pad", "gui"):
                return self.build_panel_result("Live PC & Game Remote Controller active.")
            if cmd in ("ps", "procs", "processes", "top"):
                table = list_processes(rest)
                return DispatchResult(text=f"```\n{table[:1900]}\n```")
            if cmd in ("kill", "terminate"):
                force = rest.startswith("-9 ") or rest.startswith("--force ")
                target = rest.split(None, 1)[1] if force and " " in rest else rest
                return DispatchResult(text=kill_process(target, force=force))
            if cmd in ("launch", "open", "start"):
                if not rest:
                    return DispatchResult(text="Usage: `launch <command_or_executable>`")
                return DispatchResult(text=launch_detached(rest, self.files.cwd))
            if cmd in ("exec", "run", "sh", "shell", "cmd"):
                if not rest:
                    return DispatchResult(text="Usage: `exec <shell_command>`")
                code, out, timed_out = await run_shell_command(rest, self.files.cwd)
                header = f"Timed out (exit {code})" if timed_out else f"Exit code `{code}` (`{self.files.cwd}`)"
                body = out or "(no output)"
                if len(body) > 1800:
                    return DispatchResult(
                        text=f"{header} — full output attached:",
                        file_tuple=("command-output.txt", body.encode("utf-8")),
                    )
                return DispatchResult(text=f"{header}\n```\n{body}\n```")

            # Managed Minecraft process controls
            if cmd == "mc-start":
                if not rest:
                    return DispatchResult(text="Usage: `mc-start <java -Xmx4G -jar server.jar nogui>`")
                return DispatchResult(text=await self.mc.start(rest, self.files.cwd))
            if cmd == "mc-cmd":
                return DispatchResult(text=await self.mc.send_console_command(rest))
            if cmd == "mc-logs":
                n = int(rest) if rest.isdigit() else 40
                logs = self.mc.tail_logs(n)
                return DispatchResult(text=f"```\n{logs[-1900:]}\n```")
            if cmd == "mc-stop":
                return DispatchResult(text=await self.mc.stop())

            # Full File System Management
            if cmd == "pwd":
                return DispatchResult(text=f"Current directory: `{self.files.cwd}`")
            if cmd == "cd":
                new_dir = self.files.change_dir(rest or ".")
                return DispatchResult(text=f"Changed directory to `{new_dir}`")
            if cmd in ("ls", "dir", "list"):
                listing = self.files.list_dir(rest or ".")
                return DispatchResult(text=f"```\n{listing[:1900]}\n```")
            if cmd in ("read", "cat", "view"):
                parts = rest.split()
                if not parts:
                    return DispatchResult(text="Usage: `read <path> [start_line] [end_line]`")
                start_l = int(parts[1]) if len(parts) >= 2 else 1
                end_l = int(parts[2]) if len(parts) >= 3 else start_l + 119
                txt = self.files.read_file(parts[0], start_l, end_l)
                return DispatchResult(text=f"```\n{txt[:1900]}\n```")
            if cmd in ("write", "save"):
                path_arg, content = _split_target_and_body(rest)
                if not path_arg:
                    return DispatchResult(text="Usage: `write <path> <content>`")
                return DispatchResult(text=self.files.write_file(path_arg, content))
            if cmd == "append":
                path_arg, content = _split_target_and_body(rest)
                if not path_arg:
                    return DispatchResult(text="Usage: `append <path> <content>`")
                return DispatchResult(text=self.files.append_file(path_arg, content))
            if cmd in ("edit", "replace"):
                path_arg, body = _split_target_and_body(rest)
                delim = "===" if "===" in body else ("->" if "->" in body else "")
                if not path_arg or not delim:
                    return DispatchResult(text="Usage: `edit <path> <old_text> -> <new_text>` (or `===` for multi-line)")
                old_part, new_part = body.split(delim, 1)
                return DispatchResult(text=self.files.edit_file(path_arg, old_part.strip(), new_part.strip()))
            if cmd in ("upload", "put"):
                if not attachments:
                    return DispatchResult(text="Attach file(s) to your Discord message with `!pc upload [dest_path]`.")
                saved_msgs = []
                for fname, raw_bytes in attachments:
                    target = (
                        fname
                        if not rest
                        else (f"{rest.rstrip('/')}/{fname}" if self.files.resolve(rest).is_dir() else rest)
                    )
                    saved_msgs.append(self.files.write_bytes(target, raw_bytes))
                return DispatchResult(text="\n".join(saved_msgs))
            if cmd in ("download", "get"):
                if not rest:
                    return DispatchResult(text="Usage: `download <file_or_directory>`")
                out_name, out_bytes = self.files.prepare_download(rest)
                return DispatchResult(
                    text=f"Prepared `{out_name}` ({format_size(len(out_bytes))})",
                    file_tuple=(out_name, out_bytes),
                )
            if cmd == "mkdir":
                return DispatchResult(text=self.files.make_dir(rest))
            if cmd in ("mv", "move-file", "rename"):
                src, dst = _split_target_and_body(rest)
                return DispatchResult(text=self.files.move_path(src, dst))
            if cmd in ("cp", "copy"):
                src, dst = _split_target_and_body(rest)
                return DispatchResult(text=self.files.copy_path(src, dst))
            if cmd in ("rm", "del", "delete"):
                return DispatchResult(text=self.files.delete_path(rest))

            # Screen & Input Controls
            if cmd in ("screenshot", "screen", "snap"):
                mon = int(rest) if rest.isdigit() else 1
                png = capture_screen_png(mon)
                if not png:
                    return DispatchResult(text="Screenshot capture unavailable on headless display.")
                return DispatchResult(text=f"Monitor {mon} Screenshot", file_tuple=("screen.png", png))
            if cmd == "move":
                pts = rest.split()
                return DispatchResult(text=mouse_move(int(pts[0]), int(pts[1]), relative=False))
            if cmd == "nudge":
                pts = rest.split()
                return DispatchResult(text=mouse_move(int(pts[0]), int(pts[1]), relative=True))
            if cmd == "click":
                pts = rest.split()
                btn = pts[0] if pts and not pts[0].lstrip("-").isdigit() else "left"
                coords = [int(p) for p in (pts[1:] if pts and btn != "left" or (pts and not pts[0].lstrip("-").isdigit()) else pts)]
                x, y = (coords[0], coords[1]) if len(coords) >= 2 else (None, None)
                return DispatchResult(text=mouse_click(button=btn, x=x, y=y))
            if cmd == "scroll":
                return DispatchResult(text=mouse_scroll(int(rest)))
            if cmd == "type":
                return DispatchResult(text=keyboard_type(rest))
            if cmd == "key":
                pts = rest.split()
                hold = float(pts[1]) if len(pts) >= 2 else 0.0
                return DispatchResult(text=await keyboard_press(pts[0], hold))
            if cmd == "hotkey":
                return DispatchResult(text=keyboard_hotkey(rest))

            return DispatchResult(text=f"Unknown command `{cmd}`. Run `{self.config.prefix} help` or `/pc help`.")
        except Exception as exc:
            return DispatchResult(text=f"Error executing `{cmd}`: `{exc}`")

    def build_panel_result(self, status_note: str) -> DispatchResult:
        emb = self.build_status_embed()
        png = capture_screen_png(1) if self.config.auto_screenshot_on_panel else None
        file_tuple = None
        if png:
            file_tuple = ("desktop.png", png)
            emb.set_image(url="attachment://desktop.png")
        return DispatchResult(text=status_note, embed=emb, file_tuple=file_tuple, attach_panel=True)


class PcControlPanelView(discord.ui.View):
    """Persistent Discord UI Gamepad & Desktop Controller."""

    def __init__(self, dispatcher: PcCommandDispatcher) -> None:
        super().__init__(timeout=None)
        self.dispatcher = dispatcher

    async def _run_action(self, interaction: discord.Interaction, fn: Callable[[], str]) -> None:
        if not self.dispatcher.config.is_authorized(interaction.user.id):
            await interaction.response.send_message("Unauthorized: run `/pc pair` first.", ephemeral=True)
            return
        await interaction.response.defer()
        try:
            note = fn()
        except Exception as exc:
            note = f"Input warning: `{exc}`"
        res = self.dispatcher.build_panel_result(note)
        kwargs = {"content": res.text, "embed": res.embed, "view": self}
        if res.file_tuple:
            kwargs["attachments"] = [discord.File(io.BytesIO(res.file_tuple[1]), filename=res.file_tuple[0])]
        await interaction.edit_original_response(**kwargs)

    @discord.ui.button(label="Cursor Up", style=discord.ButtonStyle.primary, row=0)
    async def cur_up(self, interaction: discord.Interaction, _: discord.ui.Button) -> None:
        await self._run_action(interaction, lambda: mouse_move(0, -60, relative=True))

    @discord.ui.button(label="W (Hold)", style=discord.ButtonStyle.success, row=0)
    async def key_w(self, interaction: discord.Interaction, _: discord.ui.Button) -> None:
        if not self.dispatcher.config.is_authorized(interaction.user.id):
            await interaction.response.send_message("Unauthorized.", ephemeral=True)
            return
        await interaction.response.defer()
        try:
            note = await keyboard_press("w", 0.65)
        except Exception as exc:
            note = f"Input warning: `{exc}`"
        res = self.dispatcher.build_panel_result(note)
        kwargs = {"content": res.text, "embed": res.embed, "view": self}
        if res.file_tuple:
            kwargs["attachments"] = [discord.File(io.BytesIO(res.file_tuple[1]), filename=res.file_tuple[0])]
        await interaction.edit_original_response(**kwargs)

    @discord.ui.button(label="Left Click", style=discord.ButtonStyle.danger, row=0)
    async def lclick(self, interaction: discord.Interaction, _: discord.ui.Button) -> None:
        await self._run_action(interaction, lambda: mouse_click("left"))

    @discord.ui.button(label="Right Click", style=discord.ButtonStyle.success, row=0)
    async def rclick(self, interaction: discord.Interaction, _: discord.ui.Button) -> None:
        await self._run_action(interaction, lambda: mouse_click("right"))

    @discord.ui.button(label="Refresh Screen", style=discord.ButtonStyle.secondary, row=0)
    async def refresh(self, interaction: discord.Interaction, _: discord.ui.Button) -> None:
        await self._run_action(interaction, lambda: "Refreshed live screen & system stats.")

    @discord.ui.button(label="Cursor Left", style=discord.ButtonStyle.primary, row=1)
    async def cur_left(self, interaction: discord.Interaction, _: discord.ui.Button) -> None:
        await self._run_action(interaction, lambda: mouse_move(-80, 0, relative=True))

    @discord.ui.button(label="Cursor Down", style=discord.ButtonStyle.primary, row=1)
    async def cur_down(self, interaction: discord.Interaction, _: discord.ui.Button) -> None:
        await self._run_action(interaction, lambda: mouse_move(0, 60, relative=True))

    @discord.ui.button(label="Cursor Right", style=discord.ButtonStyle.primary, row=1)
    async def cur_right(self, interaction: discord.Interaction, _: discord.ui.Button) -> None:
        await self._run_action(interaction, lambda: mouse_move(80, 0, relative=True))

    @discord.ui.button(label="Space", style=discord.ButtonStyle.secondary, row=1)
    async def spc(self, interaction: discord.Interaction, _: discord.ui.Button) -> None:
        if not self.dispatcher.config.is_authorized(interaction.user.id):
            await interaction.response.send_message("Unauthorized.", ephemeral=True)
            return
        await interaction.response.defer()
        note = await keyboard_press("space")
        res = self.dispatcher.build_panel_result(note)
        kwargs = {"content": res.text, "embed": res.embed, "view": self}
        if res.file_tuple:
            kwargs["attachments"] = [discord.File(io.BytesIO(res.file_tuple[1]), filename=res.file_tuple[0])]
        await interaction.edit_original_response(**kwargs)

    @discord.ui.button(label="Esc", style=discord.ButtonStyle.secondary, row=1)
    async def esc_btn(self, interaction: discord.Interaction, _: discord.ui.Button) -> None:
        if not self.dispatcher.config.is_authorized(interaction.user.id):
            await interaction.response.send_message("Unauthorized.", ephemeral=True)
            return
        await interaction.response.defer()
        note = await keyboard_press("esc")
        res = self.dispatcher.build_panel_result(note)
        kwargs = {"content": res.text, "embed": res.embed, "view": self}
        if res.file_tuple:
            kwargs["attachments"] = [discord.File(io.BytesIO(res.file_tuple[1]), filename=res.file_tuple[0])]
        await interaction.edit_original_response(**kwargs)


class RcmPcBot(discord.Client):
    """Discord client supporting Slash Commands (`/pc`), Prefix Commands (`!pc`), file uploads/downloads, and interactive panels."""

    def __init__(self, config: PcConfig) -> None:
        intents = discord.Intents.default()
        intents.message_content = True
        super().__init__(intents=intents)
        self.config = config
        self.dispatcher = PcCommandDispatcher(config)
        self.tree = app_commands.CommandTree(self)
        self._register_slash_commands()

    def _register_slash_commands(self) -> None:
        pc_group = app_commands.Group(name="pc", description="Monitor and control PC & Minecraft via Discord")

        @pc_group.command(name="panel", description="Open live desktop/game control panel")
        async def slash_panel(interaction: discord.Interaction) -> None:
            await self._reply_interaction(interaction, "panel")

        @pc_group.command(name="status", description="Show CPU, RAM, disk, network, and MC status")
        async def slash_status(interaction: discord.Interaction) -> None:
            await self._reply_interaction(interaction, "status")

        @pc_group.command(name="screenshot", description="Capture live PNG screenshot")
        async def slash_screen(interaction: discord.Interaction, monitor: int = 1) -> None:
            await self._reply_interaction(interaction, f"screenshot {monitor}")

        @pc_group.command(name="pair", description="Pair your Discord account using the 6-digit code")
        async def slash_pair(interaction: discord.Interaction, code: str) -> None:
            await self._reply_interaction(interaction, f"pair {code}")

        @pc_group.command(name="exec", description="Run a shell command on host machine")
        async def slash_exec(interaction: discord.Interaction, command: str) -> None:
            await self._reply_interaction(interaction, f"exec {command}")

        @pc_group.command(name="run", description="Execute any PC/File/MC/Input command (ls, read, write, edit, mc-start, ...)")
        async def slash_run(interaction: discord.Interaction, query: str) -> None:
            await self._reply_interaction(interaction, query)

        self.tree.add_command(pc_group)

    async def _reply_interaction(self, interaction: discord.Interaction, command_str: str) -> None:
        await interaction.response.defer()
        res = await self.dispatcher.execute(interaction.user.id, interaction.channel_id or 0, command_str)
        kwargs = {}
        if res.text:
            kwargs["content"] = res.text
        if res.embed:
            kwargs["embed"] = res.embed
        if res.attach_panel:
            kwargs["view"] = PcControlPanelView(self.dispatcher)
        if res.file_tuple:
            kwargs["attachments"] = [discord.File(io.BytesIO(res.file_tuple[1]), filename=res.file_tuple[0])]
        await interaction.followup.send(**kwargs)

    async def setup_hook(self) -> None:
        await self.tree.sync()

    async def on_ready(self) -> None:
        logger.info("Connected to Discord as %s (%s)", self.user, self.user.id if self.user else "?")
        logger.info("Active 6-digit pairing code: %s", self.config.pairing_code)
        if not self.config.authorized_user_ids:
            logger.info("Pair your Discord account by running: %s pair %s", self.config.prefix, self.config.pairing_code)

    async def on_message(self, message: discord.Message) -> None:
        if message.author.bot:
            return
        content = message.content.strip()
        prefix = self.config.prefix
        mention_a = f"<@{self.user.id}>" if self.user else ""
        mention_b = f"<@!{self.user.id}>" if self.user else ""

        cmd_body: Optional[str] = None
        if content == prefix or content.startswith(prefix + " "):
            cmd_body = content[len(prefix) :].strip()
        elif mention_a and content.startswith(mention_a):
            cmd_body = content[len(mention_a) :].strip()
        elif mention_b and content.startswith(mention_b):
            cmd_body = content[len(mention_b) :].strip()

        if cmd_body is None:
            return

        downloaded: List[Tuple[str, bytes]] = []
        for att in message.attachments:
            downloaded.append((att.filename, await att.read()))

        res = await self.dispatcher.execute(message.author.id, message.channel.id, cmd_body, downloaded)
        kwargs = {}
        if res.text:
            kwargs["content"] = res.text
        if res.embed:
            kwargs["embed"] = res.embed
        if res.attach_panel:
            kwargs["view"] = PcControlPanelView(self.dispatcher)
        if res.file_tuple:
            kwargs["file"] = discord.File(io.BytesIO(res.file_tuple[1]), filename=res.file_tuple[0])
        await message.reply(**kwargs, mention_author=False)
