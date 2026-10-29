#!/usr/bin/env python3
"""Check an installed release survives cold starts and renders its main screen.

Force-stops only NotiPush and preserves its data. Run on a selected test device
after installing the signed APK. This deliberately tests release, not debug.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--seconds", type=int, default=15)
    parser.add_argument("--rounds", type=int, default=2)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        sdk = next(line.split("=", 1)[1] for line in (root / "local.properties").read_text().splitlines() if line.startswith("sdk.dir="))
    adb = shutil.which("adb") or str(Path(sdk) / "platform-tools/adb")
    prefix = [adb, "-s", args.serial]

    def shell(command, timeout=20):
        result = subprocess.run(prefix + ["shell", command], capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout.strip()

    package = shell("dumpsys package dev.notipush")
    if not re.search(r"versionName=" + re.escape(args.expected_version) + r"(?:\s|$)", package):
        raise RuntimeError("The expected release version is not installed")
    if re.search(r"flags=\[[^\]]*DEBUGGABLE", package):
        raise RuntimeError("A debug APK is installed; this check requires a release APK")
    since = shell("date '+%m-%d %H:%M:%S.000'")
    for round_number in range(1, args.rounds + 1):
        shell("am start -W -S -n dev.notipush/.MainActivity")
        pid = shell("pidof dev.notipush")
        end = time.monotonic() + args.seconds
        while time.monotonic() < end:
            time.sleep(0.5)
            if shell("pidof dev.notipush") != pid:
                raise RuntimeError("Release process exited or restarted after launch")
        activity = shell("dumpsys activity activities")
        if not re.search(r"topResumedActivity=.*dev\.notipush/", activity):
            raise RuntimeError("NotiPush is no longer the foreground activity")
        path = "/data/local/tmp/notipush-release-smoke.xml"
        try:
            shell("uiautomator dump " + path)
            layout = ET.fromstring(shell("cat " + path))
            labels = {node.get("text") for node in layout.iter("node") if node.get("package") == "dev.notipush"}
            if not {"NotiPush", "Overview", "Apps", "Activity", "Setup"}.issubset(labels):
                raise RuntimeError("The main navigation did not render")
        finally:
            shell("rm -f " + path)
        print(f"PASS cold start {round_number}: process {pid} survived {args.seconds}s and main navigation rendered", flush=True)
    crashes = subprocess.check_output(prefix + ["logcat", "-b", "crash", "-d", "-T", since, "-v", "brief"], text=True)
    if "Process: dev.notipush" in crashes:
        raise RuntimeError("NotiPush has a new crash in the device crash buffer")
    print(f"PASS signed release {args.expected_version}: stable startup, real UI, no new app crash", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.TimeoutExpired, subprocess.CalledProcessError, ET.ParseError) as error:
        sys.exit(f"Release smoke check failed: {error}")
