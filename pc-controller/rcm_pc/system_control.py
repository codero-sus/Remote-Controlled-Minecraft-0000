from __future__ import annotations

import asyncio
import os
import platform
import socket
import subprocess
import time
from datetime import timedelta
from pathlib import Path
from typing import Any, Dict, List

import psutil


def collect_system_telemetry() -> Dict[str, Any]:
    cpu_pct = psutil.cpu_percent(interval=0.15)
    mem = psutil.virtual_memory()
    disk = psutil.disk_usage(os.getcwd())
    net = psutil.net_io_counters()
    uptime_sec = int(time.time() - psutil.boot_time())
    battery = psutil.sensors_battery()

    return {
        "hostname": socket.gethostname(),
        "os": f"{platform.system()} {platform.release()} ({platform.machine()})",
        "python": platform.python_version(),
        "uptime": str(timedelta(seconds=uptime_sec)),
        "cpu_percent": cpu_pct,
        "cpu_cores": psutil.cpu_count(logical=True) or 1,
        "ram_used_gb": round(mem.used / (1024**3), 2),
        "ram_total_gb": round(mem.total / (1024**3), 2),
        "ram_percent": mem.percent,
        "disk_used_gb": round(disk.used / (1024**3), 2),
        "disk_total_gb": round(disk.total / (1024**3), 2),
        "disk_percent": disk.percent,
        "net_sent_mb": round(net.bytes_sent / (1024**2), 1),
        "net_recv_mb": round(net.bytes_recv / (1024**2), 1),
        "battery": (
            f"{battery.percent:.0f}% ({'Charging' if battery.power_plugged else 'Discharging'})"
            if battery is not None
            else "AC / Desktop"
        ),
    }


def list_processes(filter_query: str = "", limit: int = 30) -> str:
    query = filter_query.strip().lower()
    rows: List[tuple[float, str]] = []
    for proc in psutil.process_iter(["pid", "name", "memory_info", "cpu_percent", "status"]):
        try:
            info = proc.info
            name = info.get("name") or "unknown"
            if query and query not in name.lower() and query != str(info.get("pid")):
                continue
            mem_mb = (info["memory_info"].rss / (1024**2)) if info.get("memory_info") else 0.0
            cpu = info.get("cpu_percent") or 0.0
            rows.append((mem_mb, f"{info['pid']:>7}  {mem_mb:>8.1f} MB  {cpu:>5.1f}%  {name}"))
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue

    rows.sort(key=lambda r: r[0], reverse=True)
    header = f"{'PID':>7}  {'RAM':>11}  {'CPU':>6}  NAME"
    return "\n".join([header, "-" * 48, *[line for _, line in rows[:limit]]]) or "No matching processes."


def kill_process(target: str, force: bool = False) -> str:
    target = target.strip()
    killed: List[str] = []

    if target.isdigit():
        pid = int(target)
        proc = psutil.Process(pid)
        name = proc.name()
        proc.kill() if force else proc.terminate()
        return f"{'Killed' if force else 'Terminated'} PID {pid} (`{name}`)."

    lower = target.lower()
    for proc in psutil.process_iter(["pid", "name"]):
        try:
            if (proc.info.get("name") or "").lower() == lower or lower in (proc.info.get("name") or "").lower():
                if proc.pid == os.getpid():
                    continue
                proc.kill() if force else proc.terminate()
                killed.append(f"{proc.pid} ({proc.info.get('name')})")
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue

    if not killed:
        return f"No running process matched `{target}`."
    return f"{'Killed' if force else 'Terminated'} {len(killed)} process(es): {', '.join(killed)}"


def launch_detached(command: str, cwd: Path) -> str:
    kwargs: Dict[str, Any] = {
        "cwd": str(cwd),
        "shell": True,
        "stdin": subprocess.DEVNULL,
        "stdout": subprocess.DEVNULL,
        "stderr": subprocess.DEVNULL,
    }
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True

    proc = subprocess.Popen(command, **kwargs)
    return f"Launched detached process PID **{proc.pid}** in `{cwd}`: `{command}`"


async def run_shell_command(command: str, cwd: Path, timeout: float = 45.0) -> tuple[int, str, bool]:
    proc = await asyncio.create_subprocess_shell(
        command,
        cwd=str(cwd),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    timed_out = False
    try:
        stdout_bytes, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        timed_out = True
        proc.kill()
        stdout_bytes, _ = await proc.communicate()

    output = (stdout_bytes or b"").decode("utf-8", errors="replace").strip()
    return (proc.returncode if proc.returncode is not None else -1), output, timed_out
