from __future__ import annotations

import json
import os
import secrets
from dataclasses import dataclass, field
from pathlib import Path
from typing import List


def _new_pairing_code() -> str:
    return f"{100000 + secrets.randbelow(900000)}"


@dataclass
class PcConfig:
    config_path: Path
    bot_token: str = ""
    authorized_user_ids: List[int] = field(default_factory=list)
    command_channel_id: int = 0
    prefix: str = "!pc"
    default_workdir: str = field(default_factory=lambda: str(Path.cwd()))
    auto_screenshot_on_panel: bool = True
    allow_unrestricted_paths: bool = True
    pairing_code: str = field(default_factory=_new_pairing_code)

    @classmethod
    def load(cls, config_path: Path) -> "PcConfig":
        cfg = cls(config_path=config_path.expanduser().resolve())
        if cfg.config_path.exists():
            data = json.loads(cfg.config_path.read_text(encoding="utf-8"))
            cfg.bot_token = str(data.get("bot_token", "")).strip()
            cfg.authorized_user_ids = [
                int(x) for x in data.get("authorized_user_ids", []) if str(x).strip().isdigit()
            ]
            ch = str(data.get("command_channel_id", 0)).strip()
            cfg.command_channel_id = int(ch) if ch.isdigit() else 0
            cfg.prefix = str(data.get("prefix", "!pc")).strip() or "!pc"
            cfg.default_workdir = str(data.get("default_workdir") or Path.cwd())
            cfg.auto_screenshot_on_panel = bool(data.get("auto_screenshot_on_panel", True))
            cfg.allow_unrestricted_paths = bool(data.get("allow_unrestricted_paths", True))
        else:
            cfg.save()

        env_tok = os.environ.get("RCM_PC_DISCORD_TOKEN") or os.environ.get("RCM_DISCORD_TOKEN")
        if env_tok and env_tok.strip():
            cfg.bot_token = env_tok.strip()

        env_users = os.environ.get("RCM_AUTHORIZED_USERS", "")
        for part in env_users.split(","):
            part = part.strip()
            if part.isdigit() and int(part) not in cfg.authorized_user_ids:
                cfg.authorized_user_ids.append(int(part))

        env_chan = os.environ.get("RCM_CHANNEL_ID", "").strip()
        if env_chan.isdigit():
            cfg.command_channel_id = int(env_chan)

        return cfg

    def save(self) -> None:
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "bot_token": self.bot_token,
            "authorized_user_ids": self.authorized_user_ids,
            "command_channel_id": self.command_channel_id,
            "prefix": self.prefix,
            "default_workdir": self.default_workdir,
            "auto_screenshot_on_panel": self.auto_screenshot_on_panel,
            "allow_unrestricted_paths": self.allow_unrestricted_paths,
        }
        self.config_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    def is_authorized(self, user_id: int) -> bool:
        return int(user_id) in self.authorized_user_ids

    def is_channel_allowed(self, channel_id: int) -> bool:
        return self.command_channel_id == 0 or int(channel_id) == self.command_channel_id

    def try_pair(self, user_id: int, code: str) -> bool:
        if code and code.strip() == self.pairing_code:
            uid = int(user_id)
            if uid not in self.authorized_user_ids:
                self.authorized_user_ids.append(uid)
            self.pairing_code = _new_pairing_code()
            self.save()
            return True
        return False
