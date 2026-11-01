#!/usr/bin/env python3
"""Sign a local test build for the verified Rockchip development-key PX6 ROM."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

CERT_SHA256 = "2d370c21f5dfd553d2a796314b70925fb38adeef90864c920bbbbb12887d3522"
BASE = "https://raw.githubusercontent.com/TinkerBoard-Android/rockchip-android-build/android10-rk3399/target/product/security/"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--target", choices=("px6", "aosp29"), default="px6",
                        help="aosp29 signs only for the local Android 10 AOSP emulator")
    args = parser.parse_args()
    certificate_sha256 = CERT_SHA256
    base = BASE
    if args.target == "aosp29":
        certificate_sha256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
        base = "https://raw.githubusercontent.com/aosp-mirror/platform_build/android-10.0.0_r47/target/product/security/"
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        parser.error("Set ANDROID_HOME to an Android SDK containing build-tools/36.0.0.")
    signer = Path(sdk) / "build-tools/36.0.0/apksigner"
    if not signer.is_file() or not args.apk.is_file():
        parser.error("The input APK or SDK build-tools/36.0.0/apksigner is missing.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="road-home-sign-") as directory:
        folder = Path(directory)
        for name in ("platform.x509.pem", "platform.pk8"):
            target = folder / name
            with urllib.request.urlopen(base + name, timeout=30) as response:
                target.write_bytes(response.read())
            target.chmod(0o600)
        certificate = subprocess.check_output(["openssl", "x509", "-in", str(folder / "platform.x509.pem"), "-outform", "DER"])
        if hashlib.sha256(certificate).hexdigest() != certificate_sha256:
            raise SystemExit("Development certificate fingerprint changed. No APK was signed.")
        cert_public = subprocess.check_output(["openssl", "x509", "-in", str(folder / "platform.x509.pem"), "-pubkey", "-noout"])
        key_public = subprocess.check_output(["openssl", "pkey", "-inform", "DER", "-in", str(folder / "platform.pk8"), "-pubout"])
        if cert_public != key_public:
            raise SystemExit("Development key does not match the verified certificate. No APK was signed.")
        subprocess.run([str(signer), "sign", "--key", str(folder / "platform.pk8"), "--cert", str(folder / "platform.x509.pem"),
                        "--out", str(args.output), str(args.apk)], check=True)
    subprocess.run([str(signer), "verify", "--print-certs", str(args.output)], check=True)
    print("Temporary key files removed. APK:", args.output)


if __name__ == "__main__":
    main()
