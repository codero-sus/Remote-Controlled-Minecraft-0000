from __future__ import annotations

import sys
import zipfile
import io
from pathlib import Path

import pytest

from rcm_pc.bot import PcCommandDispatcher
from rcm_pc.config import PcConfig


@pytest.mark.asyncio
async def test_pairing_auth_and_persistence(tmp_path: Path) -> None:
    cfg_file = tmp_path / "config.json"
    cfg = PcConfig.load(cfg_file)
    cfg.default_workdir = str(tmp_path)
    dispatcher = PcCommandDispatcher(cfg)

    # Blocked before pairing
    res = await dispatcher.execute(42, 100, "status")
    assert "Access denied" in res.text

    # Reject wrong code, accept real code
    bad = await dispatcher.execute(42, 100, "pair 000000")
    assert "Invalid pairing code" in bad.text

    good = await dispatcher.execute(42, 100, f"pair {cfg.pairing_code}")
    assert "Paired successfully" in good.text

    # Reload from disk and ensure user stays authorized
    reloaded = PcConfig.load(cfg_file)
    assert reloaded.is_authorized(42)


@pytest.mark.asyncio
async def test_full_file_management_write_read_edit_upload_download_rm(tmp_path: Path) -> None:
    cfg = PcConfig.load(tmp_path / "config.json")
    cfg.default_workdir = str(tmp_path)
    cfg.authorized_user_ids = [999]
    dispatcher = PcCommandDispatcher(cfg)

    # Write with codeblock
    w = await dispatcher.execute(999, 1, "write server.properties ```ini\nmotd=Initial\npvp=false\n```")
    assert "Wrote" in w.text

    # In-place edit
    e = await dispatcher.execute(999, 1, "edit server.properties motd=Initial -> motd=Updated Via Discord")
    assert "Edited" in e.text

    # Append + Read
    await dispatcher.execute(999, 1, "append server.properties view-distance=12\n")
    r = await dispatcher.execute(999, 1, "read server.properties 1 10")
    assert "motd=Updated Via Discord" in r.text
    assert "view-distance=12" in r.text

    # Upload binary attachment into subfolder
    await dispatcher.execute(999, 1, "mkdir mods")
    up = await dispatcher.execute(999, 1, "upload mods", attachments=[("rcm-mod.jar", b"PK\x03\x04testjar")])
    assert "Saved" in up.text
    assert (tmp_path / "mods" / "rcm-mod.jar").read_bytes() == b"PK\x03\x04testjar"

    # Download folder as zip
    dl = await dispatcher.execute(999, 1, "download mods")
    assert dl.file_tuple is not None
    zip_name, zip_bytes = dl.file_tuple
    assert zip_name == "mods.zip"
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        assert "rcm-mod.jar" in zf.namelist()

    # Delete
    await dispatcher.execute(999, 1, "rm mods")
    assert not (tmp_path / "mods").exists()


@pytest.mark.asyncio
async def test_shell_execution_and_managed_minecraft_process(tmp_path: Path) -> None:
    cfg = PcConfig.load(tmp_path / "config.json")
    cfg.default_workdir = str(tmp_path)
    cfg.authorized_user_ids = [777]
    dispatcher = PcCommandDispatcher(cfg)

    # Shell command execution
    sh = await dispatcher.execute(777, 1, f'exec "{sys.executable}" -c "print(6 * 7)"')
    assert "42" in sh.text

    # Simulated interactive Minecraft console server over stdin/stdout
    fake_mc_server = tmp_path / "mock_mc_server.py"
    fake_mc_server.write_text(
        "import sys\n"
        "print('[Server thread/INFO]: Done (1.20s)! For help, type \"help\"', flush=True)\n"
        "for line in sys.stdin:\n"
        "    cmd = line.strip()\n"
        "    print(f'[Server thread/INFO]: Executed {cmd}', flush=True)\n"
        "    if cmd == 'stop':\n"
        "        print('[Server thread/INFO]: Saving worlds and stopping server', flush=True)\n"
        "        break\n",
        encoding="utf-8",
    )

    started = await dispatcher.execute(777, 1, f'mc-start "{sys.executable}" -u "{fake_mc_server}"')
    assert "Started managed Minecraft process" in started.text

    resp = await dispatcher.execute(777, 1, "mc-cmd whitelist add Steve")
    assert "Executed whitelist add Steve" in resp.text

    logs = await dispatcher.execute(777, 1, "mc-logs 20")
    assert "Done (1.20s)" in logs.text

    stopped = await dispatcher.execute(777, 1, "mc-stop")
    assert "stopped cleanly" in stopped.text
