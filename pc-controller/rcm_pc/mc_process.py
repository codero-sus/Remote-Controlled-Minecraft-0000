from __future__ import annotations

import asyncio
from collections import deque
from pathlib import Path
from typing import Deque, Optional


class ManagedMinecraftProcess:
    """Launch, monitor console logs, send live stdin commands, and gracefully stop any Minecraft server or client."""

    def __init__(self, max_log_lines: int = 500) -> None:
        self._proc: Optional[asyncio.subprocess.Process] = None
        self._reader_task: Optional[asyncio.Task[None]] = None
        self._logs: Deque[str] = deque(maxlen=max_log_lines)
        self.command_str: str = ""
        self.workdir: Optional[Path] = None

    @property
    def is_running(self) -> bool:
        return self._proc is not None and self._proc.returncode is None

    async def start(self, launch_command: str, workdir: Path) -> str:
        if self.is_running:
            return f"Managed Minecraft instance is already running (PID {self._proc.pid}). Stop it first with `mc-stop`."

        workdir.mkdir(parents=True, exist_ok=True)
        self._logs.clear()
        self.command_str = launch_command
        self.workdir = workdir

        self._proc = await asyncio.create_subprocess_shell(
            launch_command,
            cwd=str(workdir),
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
        )
        self._reader_task = asyncio.create_task(self._pump_stdout())
        await asyncio.sleep(0.35)
        return f"Started managed Minecraft process PID **{self._proc.pid}** in `{workdir}`:\n`{launch_command}`"

    async def _pump_stdout(self) -> None:
        assert self._proc is not None and self._proc.stdout is not None
        while True:
            line = await self._proc.stdout.readline()
            if not line:
                break
            decoded = line.decode("utf-8", errors="replace").rstrip("\r\n")
            self._logs.append(decoded)

    async def send_console_command(self, mc_command: str) -> str:
        if not self.is_running or self._proc is None or self._proc.stdin is None:
            return "No managed Minecraft process is currently running. Start one with `mc-start <java ...>`."

        prev_len = len(self._logs)
        clean = mc_command.lstrip("/").strip()
        self._proc.stdin.write((clean + "\n").encode("utf-8"))
        await self._proc.stdin.drain()
        await asyncio.sleep(0.45)

        recent = list(self._logs)[max(0, prev_len - 1) :]
        joined = "\n".join(recent[-20:]).strip()
        if joined:
            return f"Sent `{clean}` to PID {self._proc.pid}:\n```\n{joined}\n```"
        return f"Sent `{clean}` to managed Minecraft console (PID {self._proc.pid})."

    def tail_logs(self, lines: int = 40) -> str:
        status = (
            f"RUNNING (PID {self._proc.pid})"
            if self.is_running and self._proc is not None
            else f"STOPPED (exit={self._proc.returncode if self._proc else 'never started'})"
        )
        body = "\n".join(list(self._logs)[-max(1, lines) :]) or "(no console output yet)"
        return f"Instance Status: {status}\n{body}"

    async def stop(self, graceful_timeout: float = 12.0) -> str:
        if not self.is_running or self._proc is None:
            return "Managed Minecraft instance is not running."

        pid = self._proc.pid
        if self._proc.stdin is not None:
            try:
                self._proc.stdin.write(b"stop\n")
                await self._proc.stdin.drain()
            except Exception:
                pass

        try:
            await asyncio.wait_for(self._proc.wait(), timeout=graceful_timeout)
            return f"Minecraft process PID {pid} saved and stopped cleanly (exit {self._proc.returncode})."
        except asyncio.TimeoutError:
            self._proc.kill()
            await self._proc.wait()
            return f"Minecraft process PID {pid} did not exit within {graceful_timeout}s and was force-stopped."
