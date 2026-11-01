#!/usr/bin/env python3
"""Prepare the dedicated API 29 AVD's private boot-time display-focus overlay.

Requires Linux debugfs and the exact AOSP API 29 x86_64 revision 8 image.
Stop road-home-api29 through the device tools before running. No root is used.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


# Offsets belong only to the image identified by this digest. Its logical vendor
# partition is inside system.img's GPT super partition, not the separate vendor.img.
SYSTEM_SHA256 = "1cec62e86c2cb2bf470bb0c530f2a2514af97eadc919667623ff6cac9034d1d6"
VENDOR_OFFSET = (4096 + 2906112) * 512
VENDOR_LENGTH = 244592 * 512


def main():
    sdk = os.environ.get("ANDROID_HOME")
    if not sdk or not shutil.which("debugfs"):
        raise SystemExit("Set ANDROID_HOME and install debugfs (e2fsprogs). No files changed.")
    avd = Path.home() / ".android/avd/road-home-api29.avd"
    config = avd / "config.ini"
    source = Path(sdk) / "system-images/android-29/default/x86_64"
    overlay = Path(__file__).resolve().parent.parent / "build/emulator-overlay/focus.apk"
    if not config.is_file() or not overlay.is_file():
        raise SystemExit("Create road-home-api29 and run build-emulator-overlay.sh first. No files changed.")
    for process in Path("/proc").glob("[0-9]*/cmdline"):
        try:
            command = process.read_bytes().split(b"\0")
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
        if b"-avd" in command and b"road-home-api29" in command:
            raise SystemExit("road-home-api29 is running. Shut down this AVD with the device tools first.")
    with (source / "system.img").open("rb") as image:
        if hashlib.file_digest(image, "sha256").hexdigest() != SYSTEM_SHA256:
            raise SystemExit("System image differs from API 29 AOSP x86_64 revision 8. No files changed.")
    destination = avd / "focus-system-image"
    destination.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="focus-build-", dir=avd) as temporary:
        folder = Path(temporary)
        image = folder / "system.img"
        subprocess.run(["cp", "--reflink=auto", "--sparse=always", str(source / "system.img"), str(image)], check=True)
        partition = folder / "vendor.ext4"
        with image.open("rb") as stream:
            stream.seek(VENDOR_OFFSET)
            partition.write_bytes(stream.read(VENDOR_LENGTH))
        commands = [
            "mkdir /overlay",
            f'write "{overlay}" /overlay/RoadHomeFocus.apk',
            "set_inode_field /overlay mode 040755",
            "set_inode_field /overlay/RoadHomeFocus.apk mode 0100644",
            "ea_set /overlay security.selinux u:object_r:vendor_overlay_file:s0",
            "ea_set /overlay/RoadHomeFocus.apk security.selinux u:object_r:vendor_overlay_file:s0",
        ]
        for command in commands:
            subprocess.run(["debugfs", "-w", "-R", command, str(partition)], check=True,
                           stdout=subprocess.DEVNULL)
        # debugfs can exit zero even when a command fails. Verify the actual bytes.
        extracted = folder / "verified.apk"
        subprocess.run(["debugfs", "-R", f'dump /overlay/RoadHomeFocus.apk "{extracted}"', str(partition)], check=True)
        if not extracted.is_file() or extracted.read_bytes() != overlay.read_bytes():
            raise SystemExit("Overlay verification failed. AVD boot configuration was not changed.")
        with image.open("r+b") as stream:
            stream.seek(VENDOR_OFFSET)
            stream.write(partition.read_bytes())
        for original in source.iterdir():
            link = destination / original.name
            if original.name != "system.img" and not link.exists():
                link.symlink_to(original.resolve())
        image.replace(destination / "system.img")
    settings = {
        "image.sysdir.1": str(destination) + "/",
        "fastboot.forceColdBoot": "yes",
        "fastboot.forceFastBoot": "no",
        "fastboot.forceChosenSnapshotBoot": "no",
    }
    lines = [line for line in config.read_text().splitlines()
             if line.split("=", 1)[0].strip() not in settings]
    config.write_text("\n".join(lines) + "\n" + "".join(f"{key} = {value}\n" for key, value in settings.items()))
    print("Prepared road-home-api29. Open it through the device tools for a cold boot.")


if __name__ == "__main__":
    main()
