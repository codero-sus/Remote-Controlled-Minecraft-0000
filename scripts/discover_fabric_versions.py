#!/usr/bin/env python3
"""
Dynamically queries Fabric's official Meta v2 API to discover EVERY Minecraft edition
supported by Fabric in existence (all stable releases + all snapshots/pre-releases/RCs),
resolves exact Java target release, obfuscation mode, Yarn mapping version, and Fabric Loader
version, and emits balanced parallel CI build shards.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import time
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Set

FABRIC_META_BASE = "https://meta.fabricmc.net/v2/versions"
USER_AGENT = "Remote-Controlled-Minecraft-CI/2.0 (+https://github.com/codero-sus/Remote-Controlled-Minecraft-0000)"


def fetch_json(url: str, retries: int = 4) -> Any:
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as exc:
            last_err = exc
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"Failed fetching {url}: {last_err}")


def resolve_java_release(mc_version: str, unobfuscated: bool) -> int:
    """Return exact minimum Java `--release` bytecode target required by a Minecraft version."""
    v = mc_version.strip()
    if unobfuscated:
        return 21 if (v.startswith("1.") or v.startswith("25w")) else 25

    # Release / pre-release / RC version numbers: 1.<minor>[.<patch>]
    m = re.match(r"^1\.(\d+)(?:\.(\d+))?", v)
    if m:
        minor = int(m.group(1))
        patch = int(m.group(2) or 0)
        if minor >= 21 or (minor == 20 and patch >= 5):
            return 21
        if minor >= 18:
            return 17
        if minor == 17:
            return 16
        return 8

    # Weekly snapshots: <yy>w<ww><letter>
    sw = re.match(r"^(\d{2})w(\d{2})", v)
    if sw:
        yy = int(sw.group(1))
        ww = int(sw.group(2))
        if yy >= 26:
            return 25
        if yy > 24 or (yy == 24 and ww >= 14):
            return 21
        if yy > 21 or (yy == 21 and ww >= 44):
            return 17
        if yy == 21 and ww >= 19:
            return 16
        return 8

    return 8


def build_edition_catalog(
    game_entries: List[Dict[str, Any]],
    intermediary_set: Set[str],
    yarn_map: Dict[str, str],
    loader_version: str,
) -> List[Dict[str, Any]]:
    catalog: List[Dict[str, Any]] = []
    for entry in game_entries:
        ver = str(entry["version"])
        stable = bool(entry.get("stable", False))
        is_unobf = ver not in intermediary_set
        java_rel = resolve_java_release(ver, is_unobf)
        yarn_ver = "" if is_unobf else yarn_map.get(ver, "MOJANG")
        catalog.append(
            {
                "version": ver,
                "stable": stable,
                "unobfuscated": is_unobf,
                "java_release": java_rel,
                "yarn_mappings": yarn_ver,
                "loader_version": loader_version,
            }
        )
    return catalog


def main() -> int:
    parser = argparse.ArgumentParser(description="Discover all Fabric-supported Minecraft editions.")
    parser.add_argument("--shards", type=int, default=12, help="Number of parallel CI build shards")
    parser.add_argument("--output-json", type=Path, default=Path("all_fabric_editions.json"))
    parser.add_argument(
        "--compile-mode",
        choices=["all-stable", "all-including-snapshots", "filter"],
        default="all-stable",
        help="Which editions receive dedicated Gradle builds (remaining snapshot editions are stamped & verified in packaging)",
    )
    parser.add_argument("--filter", type=str, default="", help="Optional comma/regex filter when compile-mode=filter")
    args = parser.parse_args()

    games = fetch_json(f"{FABRIC_META_BASE}/game")
    intermediaries = fetch_json(f"{FABRIC_META_BASE}/intermediary")
    yarns = fetch_json(f"{FABRIC_META_BASE}/yarn")
    loaders = fetch_json(f"{FABRIC_META_BASE}/loader")

    intermediary_set: Set[str] = {str(item["version"]) for item in intermediaries}
    yarn_map: Dict[str, str] = {}
    for y in yarns:
        gv = str(y.get("gameVersion", ""))
        if gv and gv not in yarn_map:
            yarn_map[gv] = str(y["version"])

    stable_loaders = [l["version"] for l in loaders if l.get("stable")]
    loader_version = stable_loaders[0] if stable_loaders else loaders[0]["version"]

    catalog = build_edition_catalog(games, intermediary_set, yarn_map, loader_version)
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")

    if args.compile_mode == "all-including-snapshots":
        compile_targets = catalog
    elif args.compile_mode == "filter" and args.filter.strip():
        pat = re.compile(args.filter.strip())
        compile_targets = [c for c in catalog if pat.search(c["version"])]
    else:
        compile_targets = [c for c in catalog if c["stable"]]

    shard_count = max(1, min(args.shards, max(1, len(compile_targets))))
    buckets: List[List[Dict[str, Any]]] = [[] for _ in range(shard_count)]
    for idx, item in enumerate(compile_targets):
        buckets[idx % shard_count].append(item)

    matrix_include = [
        {
            "shard": idx,
            "count": len(bucket),
            "versions_label": f"{bucket[0]['version']} .. {bucket[-1]['version']} ({len(bucket)})",
            "editions_json": json.dumps(bucket),
        }
        for idx, bucket in enumerate(buckets)
        if bucket
    ]

    print(
        f"Discovered {len(catalog)} total Fabric Minecraft editions "
        f"({sum(1 for c in catalog if c['stable'])} stable releases, "
        f"{sum(1 for c in catalog if not c['stable'])} snapshots/pre-releases)."
    )
    print(f"Scheduled {len(compile_targets)} editions across {len(matrix_include)} parallel build shards.")

    gh_out = os.environ.get("GITHUB_OUTPUT")
    if gh_out:
        with open(gh_out, "a", encoding="utf-8") as fh:
            fh.write(f"matrix={json.dumps({'include': matrix_include})}\n")
            fh.write(f"total_editions={len(catalog)}\n")
            fh.write(f"stable_editions={sum(1 for c in catalog if c['stable'])}\n")
            fh.write(f"loader_version={loader_version}\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
