from __future__ import annotations

import io
import os
import shutil
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Tuple


def unwrap_codeblock(raw: str) -> str:
    if not raw:
        return ""
    stripped = raw.strip()
    if stripped.startswith("```") and stripped.endswith("```") and len(stripped) >= 6:
        nl = stripped.find("\n")
        if 0 < nl < len(stripped) - 3:
            return stripped[nl + 1 : -3]
    return raw


def format_size(num_bytes: int) -> str:
    if num_bytes < 1024:
        return f"{num_bytes} B"
    if num_bytes < 1024**2:
        return f"{num_bytes / 1024:.1f} KB"
    if num_bytes < 1024**3:
        return f"{num_bytes / (1024**2):.2f} MB"
    return f"{num_bytes / (1024**3):.2f} GB"


class PcFileManager:
    """Full filesystem navigation, editing, transfers, archiving, and directory management."""

    def __init__(self, initial_dir: Path, allow_unrestricted: bool = True) -> None:
        self.root_dir = initial_dir.expanduser().resolve()
        self.cwd = self.root_dir
        self.allow_unrestricted = allow_unrestricted

    def resolve(self, raw_path: str = ".") -> Path:
        cleaned = (raw_path or ".").strip()
        p = Path(cleaned).expanduser()
        target = (p if p.is_absolute() else (self.cwd / p)).resolve()
        if not self.allow_unrestricted and not target.is_relative_to(self.root_dir):
            raise PermissionError(f"Path outside root directory disallowed: {raw_path}")
        return target

    def change_dir(self, raw_path: str) -> str:
        target = self.resolve(raw_path)
        if not target.exists():
            raise FileNotFoundError(f"Directory does not exist: {target}")
        if not target.is_dir():
            raise NotADirectoryError(f"Not a directory: {target}")
        self.cwd = target
        return str(self.cwd)

    def list_dir(self, raw_path: str = ".") -> str:
        target = self.resolve(raw_path)
        if not target.exists():
            return f"Path does not exist: {target}"
        if not target.is_dir():
            stat = target.stat()
            mod = datetime.fromtimestamp(stat.st_mtime).strftime("%Y-%m-%d %H:%M:%S")
            return f"File: {target}\nSize: {format_size(stat.st_size)}\nModified: {mod}"

        entries = sorted(target.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower()))
        lines = [f"Directory: {target} ({len(entries)} entries)"]
        for item in entries[:100]:
            try:
                st = item.stat()
                size_str = "-" if item.is_dir() else format_size(st.st_size)
                mtime = datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M")
            except OSError:
                size_str, mtime = "?", "?"
            kind = "[DIR] " if item.is_dir() else "[FILE]"
            suffix = "/" if item.is_dir() else ""
            lines.append(f"{kind}  {size_str:<10}  {mtime}  {item.name}{suffix}")
        if len(entries) > 100:
            lines.append(f"... and {len(entries) - 100} more entries")
        return "\n".join(lines)

    def read_file(self, raw_path: str, start_line: int = 1, end_line: int = 120) -> str:
        target = self.resolve(raw_path)
        if not target.exists():
            raise FileNotFoundError(f"File not found: {target}")
        if target.is_dir():
            raise IsADirectoryError(f"Target is a directory: {target}")
        data = target.read_bytes()
        text = data.decode("utf-8", errors="replace")
        all_lines = text.splitlines()
        start = max(1, start_line)
        end = min(len(all_lines), end_line if end_line >= start else start + 119)
        header = f"File: {target} (lines {start}-{end} of {len(all_lines)}, {format_size(len(data))})"
        body = [f"{idx:4d} | {all_lines[idx - 1]}" for idx in range(start, end + 1)]
        return "\n".join([header, *body])

    def write_file(self, raw_path: str, content: str) -> str:
        target = self.resolve(raw_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        clean = unwrap_codeblock(content)
        encoded = clean.encode("utf-8")
        target.write_bytes(encoded)
        return f"Wrote {format_size(len(encoded))} to `{target}`"

    def append_file(self, raw_path: str, content: str) -> str:
        target = self.resolve(raw_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        clean = unwrap_codeblock(content)
        encoded = clean.encode("utf-8")
        with target.open("ab") as f:
            f.write(encoded)
        return f"Appended {format_size(len(encoded))} to `{target}`"

    def edit_file(self, raw_path: str, old_text: str, new_text: str, replace_all: bool = False) -> str:
        target = self.resolve(raw_path)
        if not target.exists() or target.is_dir():
            raise FileNotFoundError(f"Editable file not found: {target}")
        existing = target.read_text(encoding="utf-8")
        old_clean = unwrap_codeblock(old_text)
        new_clean = unwrap_codeblock(new_text)

        if old_clean not in existing:
            norm_existing = existing.replace("\r\n", "\n")
            norm_old = old_clean.replace("\r\n", "\n")
            if norm_old not in norm_existing:
                raise ValueError(f"Snippet not found in `{target}`.")
            count = norm_existing.count(norm_old) if replace_all else 1
            updated = (
                norm_existing.replace(norm_old, new_clean.replace("\r\n", "\n"))
                if replace_all
                else norm_existing.replace(norm_old, new_clean.replace("\r\n", "\n"), 1)
            )
        else:
            count = existing.count(old_clean) if replace_all else 1
            updated = (
                existing.replace(old_clean, new_clean)
                if replace_all
                else existing.replace(old_clean, new_clean, 1)
            )

        target.write_text(updated, encoding="utf-8")
        return f"Edited `{target}` ({count} replacement(s), {format_size(len(updated.encode('utf-8')))})."

    def write_bytes(self, raw_path: str, data: bytes) -> str:
        target = self.resolve(raw_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return f"Saved `{target}` ({format_size(len(data))})"

    def prepare_download(self, raw_path: str) -> Tuple[str, bytes]:
        target = self.resolve(raw_path)
        if not target.exists():
            raise FileNotFoundError(f"Path not found: {target}")
        if target.is_file():
            return target.name, target.read_bytes()

        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            for root, _, filenames in os.walk(target):
                for fn in filenames:
                    full = Path(root) / fn
                    rel = full.relative_to(target).as_posix()
                    zf.write(full, arcname=rel)
        return f"{target.name or 'archive'}.zip", buf.getvalue()

    def make_dir(self, raw_path: str) -> str:
        target = self.resolve(raw_path)
        target.mkdir(parents=True, exist_ok=True)
        return f"Created directory `{target}`"

    def move_path(self, src_raw: str, dst_raw: str) -> str:
        src = self.resolve(src_raw)
        dst = self.resolve(dst_raw)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(src), str(dst))
        return f"Moved `{src}` -> `{dst}`"

    def copy_path(self, src_raw: str, dst_raw: str) -> str:
        src = self.resolve(src_raw)
        dst = self.resolve(dst_raw)
        if src.is_dir():
            shutil.copytree(src, dst, dirs_exist_ok=True)
        else:
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
        return f"Copied `{src}` -> `{dst}`"

    def delete_path(self, raw_path: str) -> str:
        target = self.resolve(raw_path)
        if target == Path(target.anchor):
            raise PermissionError("Refusing to delete filesystem root.")
        if not target.exists():
            return f"Nothing to delete at `{target}`"
        if target.is_dir():
            shutil.rmtree(target)
        else:
            target.unlink()
        return f"Deleted `{target}`"
