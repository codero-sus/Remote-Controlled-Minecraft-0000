from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import discord

from .bot import RcmPcBot
from .config import PcConfig


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    parser = argparse.ArgumentParser(
        prog="rcm-pc",
        description="Standalone Discord Remote Controller for PC & Minecraft Instances",
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=Path("config.json"),
        help="Path to JSON configuration file (default: ./config.json)",
    )
    sub = parser.add_subparsers(dest="subcommand")

    init_p = sub.add_parser("init", help="Initialize or update bot token & settings")
    init_p.add_argument("--token", type=str, default="", help="Discord Bot Token")
    init_p.add_argument("--user-id", type=int, default=0, help="Authorized Discord User ID")
    init_p.add_argument("--prefix", type=str, default="!pc", help="Command prefix (default: !pc)")

    sub.add_parser("run", help="Start the Discord remote controller bot")

    args = parser.parse_args()
    cfg = PcConfig.load(args.config)

    if args.subcommand == "init":
        if args.token:
            cfg.bot_token = args.token.strip()
        if args.user_id:
            if args.user_id not in cfg.authorized_user_ids:
                cfg.authorized_user_ids.append(args.user_id)
        if args.prefix:
            cfg.prefix = args.prefix.strip()
        cfg.save()
        print(f"Saved configuration to {cfg.config_path}")
        print(f"Next step: rcm-pc --config {cfg.config_path} run")
        return 0

    if not cfg.bot_token:
        print(
            f"[RCM-PC] No bot_token configured in {cfg.config_path}!\n"
            f"Run: rcm-pc --config {cfg.config_path} init --token YOUR_DISCORD_BOT_TOKEN\n"
            f"(Or set environment variable RCM_DISCORD_TOKEN)",
            file=sys.stderr,
        )
        return 1

    print(f"[RCM-PC] Starting controller with config: {cfg.config_path}")
    print(f"[RCM-PC] 6-digit Discord Pairing Code: {cfg.pairing_code}")
    try:
        client = RcmPcBot(cfg)
        client.run(cfg.bot_token)
    except discord.PrivilegedIntentsRequired:
        logging.warning(
            "Message Content Intent is disabled in Discord Developer Portal; retrying with Slash Commands + @Mentions mode..."
        )
        intents = discord.Intents.default()
        intents.message_content = False
        client = RcmPcBot(cfg)
        client._connection._intents = intents
        client.run(cfg.bot_token)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
