from __future__ import annotations

import io
import json
import struct
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

import discover_fabric_versions as disc
import package_all_editions as pkg


def _make_fake_jar(java_release: int) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(
            "fabric.mod.json",
            json.dumps(
                {
                    "schemaVersion": 1,
                    "id": "rcm",
                    "version": "2.0.0",
                    "depends": {"fabricloader": "*", "minecraft": "1.14", "java": f">={java_release}"},
                }
            ),
        )
        zf.writestr("rcm.mixins.json", json.dumps({"required": True}))
        zf.writestr("assets/rcm/icon.png", b"\x89PNG\r\n\x1a\n")
        # Minimal valid header with CAFEBABE + minor=0 + major=(44 + java_release)
        cls_header = struct.pack(">IHH", 0xCAFEBABE, 0, 44 + java_release) + b"\x00" * 16
        zf.writestr("dev/rcm/RcmMod.class", cls_header)
    return buf.getvalue()


def test_discover_and_package_pipeline(tmp_path: Path) -> None:
    games = [
        {"version": "26.3", "stable": True},
        {"version": "26w14a", "stable": False},
        {"version": "1.21.11", "stable": True},
        {"version": "1.20.1", "stable": True},
        {"version": "1.17.1", "stable": True},
        {"version": "1.14", "stable": True},
        {"version": "20w14infinite", "stable": False},
    ]
    inter = {"1.21.11", "1.20.1", "1.17.1", "1.14", "20w14infinite"}
    yarns = {"1.21.11": "1.21.11+build.1", "1.14": "1.14+build.21"}
    cat = disc.build_edition_catalog(games, inter, yarns, "0.20.1")

    by_ver = {c["version"]: c for c in cat}
    assert by_ver["26.3"]["unobfuscated"] is True
    assert by_ver["26.3"]["java_release"] == 25
    assert by_ver["1.21.11"]["java_release"] == 21
    assert by_ver["1.20.1"]["java_release"] == 17
    assert by_ver["1.17.1"]["java_release"] == 16
    assert by_ver["1.14"]["java_release"] == 8

    cat_path = tmp_path / "catalog.json"
    cat_path.write_text(json.dumps(cat), encoding="utf-8")

    shards = tmp_path / "shards"
    shards.mkdir()
    (shards / "remote-controlled-minecraft-mc1.14-2.0.0.jar").write_bytes(_make_fake_jar(8))
    (shards / "remote-controlled-minecraft-mc26.3-2.0.0.jar").write_bytes(_make_fake_jar(25))

    out_dir = tmp_path / "dist"
    sys.argv = [
        "package_all_editions.py",
        "--catalog",
        str(cat_path),
        "--shards-dir",
        str(shards),
        "--out-dir",
        str(out_dir),
    ]
    assert pkg.main() == 0

    manifest = json.loads((out_dir / "EDITION-MANIFEST.json").read_text(encoding="utf-8"))
    assert manifest["total_fabric_editions_built_and_verified"] == 7
    assert (out_dir / "SHA256SUMS.txt").exists()
