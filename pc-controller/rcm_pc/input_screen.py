from __future__ import annotations

import asyncio
import io
from typing import Optional


def capture_screen_png(monitor_index: int = 1) -> Optional[bytes]:
    """Capture desktop screenshot PNG via mss + Pillow (safe on headless machines)."""
    try:
        import mss
        from PIL import Image

        with mss.mss() as sct:
            monitors = sct.monitors
            idx = min(max(0, monitor_index), len(monitors) - 1)
            raw = sct.grab(monitors[idx])
            img = Image.frombytes("RGB", raw.size, raw.bgra, "raw", "BGRX")
            out = io.BytesIO()
            img.save(out, format="PNG", optimize=True)
            return out.getvalue()
    except Exception:
        return None


def _load_pyautogui():
    import pyautogui

    pyautogui.FAILSAFE = False
    return pyautogui


def mouse_move(x: int, y: int, relative: bool = False) -> str:
    gui = _load_pyautogui()
    if relative:
        gui.moveRel(x, y, duration=0.08)
    else:
        gui.moveTo(x, y, duration=0.08)
    pos = gui.position()
    return f"Cursor moved to `({pos.x}, {pos.y})`."


def mouse_click(button: str = "left", clicks: int = 1, x: Optional[int] = None, y: Optional[int] = None) -> str:
    gui = _load_pyautogui()
    btn = button.lower().strip()
    if btn == "double":
        btn = "left"
        clicks = 2
    if x is not None and y is not None:
        gui.click(x=x, y=y, clicks=clicks, button=btn)
    else:
        gui.click(clicks=clicks, button=btn)
    pos = gui.position()
    return f"Clicked `{btn}` x{clicks} at `({pos.x}, {pos.y})`."


def mouse_scroll(amount: int) -> str:
    gui = _load_pyautogui()
    gui.scroll(amount)
    return f"Scrolled mouse wheel by `{amount}`."


def keyboard_type(text: str, interval: float = 0.015) -> str:
    gui = _load_pyautogui()
    gui.write(text, interval=interval)
    return f"Typed `{len(text)}` character(s)."


async def keyboard_press(key: str, hold_seconds: float = 0.0) -> str:
    gui = _load_pyautogui()
    k = key.strip().lower()
    if hold_seconds > 0:
        gui.keyDown(k)
        try:
            await asyncio.sleep(min(hold_seconds, 30.0))
        finally:
            gui.keyUp(k)
        return f"Held key `{k}` for {hold_seconds:.2f}s."
    gui.press(k)
    return f"Pressed key `{k}`."


def keyboard_hotkey(combo: str) -> str:
    gui = _load_pyautogui()
    keys = [p.strip().lower() for p in combo.replace("+", " ").split() if p.strip()]
    if not keys:
        raise ValueError("Specify keys e.g. `ctrl+s` or `alt+tab`.")
    gui.hotkey(*keys)
    return f"Sent hotkey `{' + '.join(keys)}`."
