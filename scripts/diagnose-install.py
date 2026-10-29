#!/usr/bin/env python3
"""Capture the phone's actual package-manager rejection without changing security settings.

Read-only by default. --try-install performs one normal update/install attempt.
No uninstall, downgrade, test-only override, or verification bypass is used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

PACKAGE = "dev.notipush"
REASONS = {
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE": "An installed package has a different signing identity. Preserve its data and inspect the installed APK before deciding how to replace it.",
    "INSTALL_FAILED_VERSION_DOWNGRADE": "The installed version code is newer than this APK. Build an update with a higher version code.",
    "INSTALL_FAILED_OLDER_SDK": "The phone's Android API level is below the APK's minimum. Compare the reported device SDK and APK manifest.",
    "INSTALL_FAILED_NEWER_SDK": "The package declares a maximum Android version below this phone's API level.",
    "INSTALL_FAILED_NO_MATCHING_ABIS": "The APK does not contain native libraries for a CPU ABI supported by this phone.",
    "INSTALL_FAILED_TEST_ONLY": "The selected APK is marked test-only. Use a production release APK.",
    "INSTALL_PARSE_FAILED_NO_CERTIFICATES": "Android could not verify the APK signature. Compare the phone copy's hash and inspect signing output.",
    "INSTALL_FAILED_INVALID_APK": "Android rejected package structure or content. The accompanying failure detail is needed to identify which part.",
    "INSTALL_FAILED_INSUFFICIENT_STORAGE": "The device cannot allocate installation space. Check the reported /data free space.",
    "INSTALL_FAILED_USER_RESTRICTED": "A user, profile, or device policy blocked this installation. The device's policy/source settings need inspection.",
    "INSTALL_FAILED_VERIFICATION_FAILURE": "A device verification component rejected the install. Read its accompanying status; a clean scan screen is not the final installation result.",
    "INSTALL_FAILED_VERIFICATION_TIMEOUT": "The device's verification component did not respond in time.",
    "INSTALL_FAILED_CONFLICTING_PROVIDER": "Another package already owns a content-provider authority declared by this app.",
    "INSTALL_FAILED_DEXOPT": "Android failed to process the app's bytecode. Inspect the ART/dex error before changing compiler settings.",
    "INSTALL_FAILED_BAD_DEX_METADATA": "Android rejected DEX optimisation metadata. Inspect the exact metadata error.",
    "INSTALL_FAILED_INTERNAL_ERROR": "The installer reported an internal failure. Its detail and system log are needed.",
}


def parse_failure(text):
    match = re.search(r"\b(INSTALL_(?:FAILED|PARSE_FAILED)_[A-Z0-9_]+)\b", text)
    if not match:
        return None
    code = match[1]
    return {"code": code, "explanation": REASONS.get(code, "Use the complete device rejection detail to diagnose this code.")}


def parse_devices(output):
    devices = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[1] in {"device", "unauthorized", "offline"}:
            devices.append({"serial": fields[0], "state": fields[1], "emulator": fields[0].startswith("emulator-")})
    return devices


def installation_lines(text):
    # Only the system buffer is read, and unrelated lines are omitted from the report.
    pattern = re.compile(r"dev\.notipush|NotiPush|INSTALL_(?:PARSE_FAILED|FAILED)_[A-Z_]+", re.IGNORECASE)
    return [line for line in text.splitlines() if pattern.search(line)][-100:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--serial", help="Select one device explicitly when more than one is connected")
    parser.add_argument("--try-install", action="store_true", help="Perform one normal adb install --no-streaming -r attempt")
    parser.add_argument("--output", type=Path, help="Private JSON report path (default: a file in /tmp)")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.apk is None:
        versions = list((root / "dist").glob("NotiPush-*.apk"))
        if not versions:
            parser.error("No release APK in dist/; pass --apk explicitly")
        args.apk = max(versions, key=lambda path: path.stat().st_mtime)
    apk = args.apk.resolve()
    if not apk.is_file():
        parser.error(f"APK not found: {apk}")

    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    properties = root / "local.properties"
    if not sdk and properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1]
    adb = shutil.which("adb") or (str(Path(sdk) / "platform-tools/adb") if sdk else None)
    if not adb or not Path(adb).is_file():
        parser.error("adb not found; set ANDROID_HOME or install Android SDK Platform Tools")

    def run(command, timeout=20):
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        return {"exit_code": result.returncode, "output": (result.stdout + result.stderr).strip()}

    devices = parse_devices(run([adb, "devices", "-l"])["output"])
    if args.serial:
        selected = [device for device in devices if device["serial"] == args.serial]
    else:
        selected = [device for device in devices if not device["emulator"]]
    if len(selected) != 1:
        print("Connect one phone by USB, enable USB debugging, unlock it, and accept its debugging prompt.")
        print("When multiple phones are connected, select one with --serial.")
        return 2
    device = selected[0]
    if device["state"] != "device":
        print(f"Phone state: {device['state']}. Unlock it and accept the USB debugging prompt, then retry.")
        return 2
    prefix = [adb, "-s", device["serial"]]

    def shell(command):
        return run(prefix + ["shell", command])

    report = {"captured_at_utc": datetime.now(timezone.utc).isoformat(),
              "apk": {"name": apk.name, "bytes": apk.stat().st_size,
                      "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()},
              "device": {}, "installation_attempt": None}
    for key, prop in {"manufacturer": "ro.product.manufacturer", "model": "ro.product.model",
                      "android_release": "ro.build.version.release", "sdk": "ro.build.version.sdk",
                      "abis": "ro.product.cpu.abilist", "build": "ro.build.display.id"}.items():
        report["device"][key] = shell("getprop " + prop)["output"]
    report["device"]["page_size"] = shell("getconf PAGE_SIZE")["output"]
    report["device"]["data_storage"] = shell("df -k /data")["output"]
    package_dump = shell("dumpsys package " + PACKAGE)["output"]
    report["existing_package"] = [line.strip() for line in package_dump.splitlines()
        if re.search(r"versionCode=|versionName=|User \d+:|installerPackageName=|initiatingPackageName=", line)]

    # Check only NotiPush APKs in Download; no messages, app data, or other files are read.
    files = shell("find /sdcard/Download -maxdepth 1 -type f -iname 'notipush*.apk'")
    report["downloaded_apks"] = []
    if files["exit_code"] == 0:
        for filename in files["output"].splitlines()[:10]:
            result = shell("sha256sum " + shlex.quote(filename))
            digest = re.match(r"([0-9a-fA-F]{64})\s", result["output"])
            if digest:
                report["downloaded_apks"].append({"name": Path(filename).name,
                    "sha256": digest[1].lower(), "matches_release": digest[1].lower() == report["apk"]["sha256"]})

    if args.try_install:
        print("Attempting one normal release install/update; keep the phone unlocked.", flush=True)
        attempt = run(prefix + ["install", "--no-streaming", "-r", str(apk)], timeout=120)
        report["installation_attempt"] = attempt
        report["failure"] = parse_failure(attempt["output"])
        print(attempt["output"])
        if report["failure"]:
            print(report["failure"]["explanation"])
        elif attempt["exit_code"] == 0 and re.search(r"^Success$", attempt["output"], re.MULTILINE):
            print("Android accepted this APK through adb. A separate GUI/App Guard failure still needs its own system-log evidence.")
    logs = run(prefix + ["logcat", "-b", "system", "-d", "-t", "2000", "-v", "brief"])
    report["installation_system_log"] = installation_lines(logs["output"])
    output = args.output
    if output is None:
        fd, name = tempfile.mkstemp(prefix="notipush-install-", suffix=".json")
        os.close(fd)
        output = Path(name)
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
    with open(output, "w", encoding="utf-8", opener=lambda path, flags: os.open(path, flags, 0o600)) as stream:
        stream.write(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    output.chmod(0o600)
    print("Device:", report["device"]["model"], "Android", report["device"]["android_release"], "API", report["device"]["sdk"])
    print("Filtered diagnostic report:", output.resolve())
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.TimeoutExpired) as error:
        sys.exit(f"Diagnostic could not complete: {error}")
