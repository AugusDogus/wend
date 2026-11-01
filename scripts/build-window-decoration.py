#!/usr/bin/env python3
"""Build a reversible API 29 freeform-titlebar overlay against the exact target ROM."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("framework", type=Path, help="framework-res.apk pulled from the target device")
parser.add_argument("--target", choices=("aosp29", "px6"), default="aosp29")
args = parser.parse_args()
framework = args.framework.resolve(strict=True)
root = Path(__file__).resolve().parent.parent
sdk = Path(os.environ["ANDROID_HOME"])
aapt = sdk / "build-tools/36.0.0/aapt2"
out = root / "build/window-overlay"
out.mkdir(parents=True, exist_ok=True)
android = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", android)
manifest = ET.parse(root / "window-overlay/AndroidManifest.xml")
application = manifest.getroot().find("application")
if application is None:
    raise SystemExit("Overlay manifest has no application element; no APK built")
ET.SubElement(application, "meta-data", {
    f"{{{android}}}name": "frameworkSha256",
    f"{{{android}}}value": hashlib.sha256(framework.read_bytes()).hexdigest(),
})
manifest.write(out / "AndroidManifest.xml", encoding="utf-8", xml_declaration=True)
subprocess.run([str(aapt), "compile", "--dir", str(root / "window-overlay/res"),
                "-o", str(out / "resources.zip")], check=True)
subprocess.run([str(aapt), "link", "-I", str(framework), "--manifest", str(out / "AndroidManifest.xml"),
                "-o", str(out / "unsigned.apk"), str(out / "resources.zip")], check=True)
subprocess.run([sys.executable, str(root / "scripts/sign-px6.py"), "--target", args.target,
                str(out / "unsigned.apk"), str(out / "window-decor.apk")], check=True)
