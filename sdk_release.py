"""Pack python/ into a signed SDK release for SdkUpdater.

    python sdk_release.py 1.0.1 --key C:/path/sdk-signing.pem

Writes out/sdk/sdk-<version>.zip and out/sdk/sdk.json. Publishing is a separate step:

    gh release create sdk --repo entaytion/entinyGram --prerelease --title "SDK" --notes "python sdk"   (first time)
    gh release upload sdk out/sdk/sdk-<version>.zip out/sdk/sdk.json --clobber --repo entaytion/entinyGram
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

URL = "https://github.com/entinyGram/plugins/releases/download/python-sdk/sdk-{version}.zip"
PAYLOAD = "entiny-pysdk|{version}|{sha256}"
ALLOWED = {".py", ".txt", ".json"}


def pack(source, target):
    count = 0
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source.rglob("*")):
            relative = path.relative_to(source)
            if not path.is_file() or "__pycache__" in relative.parts or path.suffix not in ALLOWED:
                continue
            info = zipfile.ZipInfo(relative.as_posix(), date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
            count += 1
    return count


def sign(key, version, sha256):
    with tempfile.NamedTemporaryFile(delete=False) as message:
        message.write(PAYLOAD.format(version=version, sha256=sha256).encode())
    try:
        out = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key), message.name],
                             check=True, capture_output=True).stdout
    finally:
        Path(message.name).unlink(missing_ok=True)
    return base64.b64encode(out).decode()


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("--key", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]{1,4}(\.[0-9]{1,4}){1,3}", args.version):
        sys.exit("version must look like 1.0.1")
    if not args.key.is_file():
        sys.exit(f"no key at {args.key}")
    out = here / "var" / "out" / "sdk"
    out.mkdir(parents=True, exist_ok=True)
    zip_path = out / f"sdk-{args.version}.zip"
    count = pack(here / "engine" / "python", zip_path)
    sha256 = hashlib.sha256(zip_path.read_bytes()).hexdigest()
    manifest = {"stable": {"version": args.version, "url": URL.format(version=args.version), "sha256": sha256,
                           "signature": sign(args.key, args.version, sha256)}}
    (out / "sdk.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"{count} files, {zip_path.stat().st_size} bytes, sha256 {sha256}")
    print(f"wrote {out / 'sdk.json'}")


if __name__ == "__main__":
    main()
