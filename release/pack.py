#!/usr/bin/env python3
"""Pack the release artifacts for phonebot-android.

Produces, under ``release/dist/``:

* ``botd-<ver>.tar.gz``   - the Python daemon payload (``botd/``) + install
  script, so Termux can ``pkg install`` + run it without the app.
* ``skills-<ver>.tar.gz`` - preset bot templates (if any exist).
* ``manifest.json``       - version pins: botd API version, required Termux
  major, the F-Droid APK URLs + SHA-256 the wizard verifies, and the GPG key
  fingerprint. This is the single source the Android wizard downloads from
  our GitHub Release and checks before installing.
* ``*.sha256``            - per-artifact checksums (also embedded in manifest).

If ``gpg`` is available and ``--sign`` is passed, each artifact is signed in
place (``--detach``) and the key fingerprint is recorded in the manifest.

Run from the repo root:

    py -3 release/pack.py --version 0.1.0 [--sign]

The APK itself is NOT produced here: it is built by the CI workflow
(``.github/workflows/release.yml``) or Android Studio, then uploaded to the
same release. This script only produces the *non-APK* release assets + the
manifest that pins them.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
from pathlib import Path
from typing import Any, Dict, List, Optional

REPO = Path(__file__).resolve().parents[1]
DIST = REPO / "release" / "dist"

# --------------------------------------------------------------------------- #
# F-Droid APKs the first-run wizard auto-installs (version-pinned).
# --------------------------------------------------------------------------- #
FDOID_APKS: List[Dict[str, Any]] = [
    {
        "name": "Termux",
        # F-Droid is the only build that works for our background automation
        # (the Play Store build's integrity check breaks proot/crun).
        # Pinned to the latest stable (not the 0.119 beta line).
        "url": "https://f-droid.org/repo/com.termux_1002.apk",
        "package": "com.termux",
        "version": "0.118.3",
        "version_code": 1002,
        "sha256": "e6265a57eb5ca363808488e3b01955958bed93bc0c8a0d281849b363b11027ec",
    },
    {
        "name": "Termux:API",
        "url": "https://f-droid.org/repo/com.termux.api_1002.apk",
        "package": "com.termux.api",
        "version": "0.53.0",
        "version_code": 1002,
        "sha256": "4497dbbf81906df52e59ed387a5223d225aa0de3aca817cc557a621e4dadda44",
    },
    {
        "name": "Termux:Boot",
        "url": "https://f-droid.org/repo/com.termux.boot_1000.apk",
        "package": "com.termux.boot",
        "version": "0.8.1",
        "version_code": 1000,
        "sha256": "6f7cf9b94f539d3efd4af3544ff819947b49395275d8cfa7e5f80de14f3d9cf8",
    },
]

BOTD_API_VERSION = "1"  # IPC protocol / endpoint.json contract version.


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _tarball(source_dir: Path, out: Path, root_name: str) -> Path:
    """Tar+gzip ``source_dir`` into ``out`` with files rooted at ``root_name/``."""
    out.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(out, "w:gz") as tf:
        tf.add(source_dir, arcname=root_name)
    return out


def pack(version: str, sign: bool = False) -> Dict[str, Any]:
    DIST.mkdir(parents=True, exist_ok=True)
    (DIST / "botd").mkdir(parents=True, exist_ok=True)
    # Assemble the deploy payload: the Python package + the install script.
    shutil.copytree(
        REPO / "botd", DIST / "botd" / "botd", dirs_exist_ok=True,
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
    )
    shutil.copy(REPO / "release" / "botd-install.sh", DIST / "botd" / "install.sh")

    artifacts: List[Dict[str, Any]] = []

    # botd payload
    botd_tar = _tarball(DIST / "botd", DIST / f"botd-{version}.tar.gz", "botd")
    artifacts.append({
        "name": "botd payload",
        "file": botd_tar.name,
        "sha256": _sha256(botd_tar),
        "kind": "botd",
    })

    # skills bundle (optional - only if a skills dir with templates exists)
    skills_src = REPO / "skills"
    if skills_src.is_dir() and any(skills_src.iterdir()):
        skills_tar = _tarball(skills_src, DIST / f"skills-{version}.tar.gz", "skills")
        artifacts.append({
            "name": "skills bundle",
            "file": skills_tar.name,
            "sha256": _sha256(skills_tar),
            "kind": "skills",
        })

    # per-artifact .sha256 files (classic checksum distribution style)
    for a in artifacts:
        (DIST / f"{a['file']}.sha256").write_text(f"{a['sha256']}  {a['file']}\n", encoding="utf-8")

    manifest: Dict[str, Any] = {
        "version": version,
        "botd_api": BOTD_API_VERSION,
        "generated_by": "release/pack.py",
        "fdroid": FDOID_APKS,
        "artifacts": artifacts,
        "signing": {"algorithm": "gpg-rsa", "fingerprint": None},
    }

    if sign:
        _sign(DIST, artifacts, manifest)

    manifest_path = DIST / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"packaged {len(artifacts)} artifact(s) -> {DIST}")
    for a in artifacts:
        print(f"  {a['file']:<24} sha256={a['sha256'][:16]}...")
    print(f"  manifest.json {manifest['signing']['fingerprint'] or 'unsigned'}")
    return manifest


def _sign(dist: Path, artifacts: List[Dict[str, Any]], manifest: Dict[str, Any]) -> None:
    if shutil.which("gpg") is None:
        print("  [sign] gpg not found; skipping (artifacts left unsigned)")
        return
    keys = subprocess.run(
        ["gpg", "--list-secret-keys", "--fingerprint", "--with-subkeys"],
        capture_output=True, text=True, check=False,
    )
    # CI images ship gpg without a secret key. Signing is optional (the
    # manifest records "fingerprint": null), so skip instead of failing the
    # whole release when there is nothing to sign with.
    if keys.returncode != 0 or not keys.stdout.strip():
        print("  [sign] no GPG secret key configured; skipping (artifacts left unsigned)")
        return
    for a in artifacts:
        target = dist / a["file"]
        subprocess.run(
            ["gpg", "--batch", "--yes", "--detach-sign", "--armor",
             "--output", str(target + ".sig"), str(target)],
            check=True,
        )
        print(f"  [sign] signed {a['file']}")
    # Record the active key's fingerprint from the listing we already have.
    for line in keys.stdout.splitlines():
        if line.strip().startswith("fingerprint") or len(line.strip()) == 40:
            fp = line.replace("fingerprint", "").strip()
            if len(fp) == 40:
                manifest["signing"]["fingerprint"] = fp
                break


def main() -> int:
    parser = argparse.ArgumentParser(description="Pack phonebot-android release assets")
    parser.add_argument("--version", required=True, help="release version (e.g. 0.1.0)")
    parser.add_argument("--sign", action="store_true", help="GPG-sign artifacts if gpg is present")
    args = parser.parse_args()
    pack(args.version, sign=args.sign)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
