#!/usr/bin/env python3
"""Read-only Android/AOD power snapshot. Never reads notification contents or app secrets."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

PACKAGE = "dev.notipush"


def boolean_settings(output):
    relevant = re.compile(r"aod|always.?on|doze|sleep.?standby|deep.?sleep|low.?power|app.?freez", re.I)
    values = {}
    for line in output.splitlines():
        key, sep, value = line.partition("=")
        if sep and relevant.search(key) and value.lower() in {"0", "1", "true", "false", "on", "off"}:
            values[key] = value
    return values


def power_state(output):
    keys = ["mWakefulness", "mIsPowered", "mDeviceIdleMode", "mLightDeviceIdleMode",
            "mLowPowerStandbyActive", "mLowPowerStandbyEnabled"]
    return {key: match[1] for key in keys
            if (match := re.search(r"\b" + key + r"=(\w+)", output))}


def display_states(output):
    # Ignore historical state-change logs in dumpsys display.
    current = re.findall(r"(?m)^\s*mDisplayState=(DOZE_SUSPEND|DOZE|OFF|ON_SUSPEND|ON|VR)\b", output)
    if current:
        return sorted(set(current))
    return sorted({match[1] for line in output.splitlines() if re.match(r"\s*DisplayDeviceInfo\{", line)
                   if (match := re.search(r"\bstate (DOZE_SUSPEND|DOZE|OFF|ON_SUSPEND|ON|VR)\b", line))})


def process_state(output):
    # Keep only known lifecycle fields from the main NotiPush process record.
    blocks = re.split(r"(?m)^\s*\*APP\*", output)
    own = next((block for block in blocks[1:]
                if re.search(r"\bdev\.notipush/", block.splitlines()[0])), "")
    keys = ["curProcState", "setProcState", "curAdj", "setAdj", "cached", "empty",
            "isFrozen", "mFrozen", "hasForegroundServices", "hasStartedServices"]
    return {key: match[1] for key in keys
            if (match := re.search(r"\b" + key + r"[=:]\s*([\w+-]{1,20})\b", own))}


def listener_enabled(output):
    return any(value.split("/", 1)[0] == PACKAGE for value in output.strip().split(":"))


def whitelist_kinds(output):
    return sorted({parts[0] for line in output.splitlines()
                   if len(parts := line.split(",")) >= 2 and parts[1] == PACKAGE
                   and parts[0] in {"user", "system", "system-excidle"}})


def appop_mode(output):
    match = re.search(r":\s*(allow|ignore|deny|default|foreground|errored)\b", output)
    return match[1] if match else "unavailable"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="USB or paired wireless ADB device")
    parser.add_argument("--output", type=Path, help="New private JSON file; existing files are not overwritten")
    parser.add_argument("--app-report", action="store_true", help="Read metadata from the running forwarding service AFTER a test; invokes app dump code")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and (root / "local.properties").exists():
        sdk = next((line.split("=", 1)[1] for line in (root / "local.properties").read_text().splitlines()
                    if line.startswith("sdk.dir=")), None)
    adb = shutil.which("adb") or (str(Path(sdk) / "platform-tools/adb") if sdk else None)
    if not adb:
        parser.error("Set ANDROID_HOME or put adb on PATH")
    prefix = [adb, "-s", args.serial]
    state = subprocess.run(prefix + ["get-state"], capture_output=True, text=True, timeout=10)
    if state.returncode or state.stdout.strip() != "device":
        parser.error("Device is not connected and authorized. Pair wireless debugging or connect USB first.")

    def shell(*command):
        result = subprocess.run(prefix + ["shell", *command], capture_output=True, text=True, timeout=20)
        return result.stdout if result.returncode == 0 else ""

    report = {"captured_at_utc": datetime.now(timezone.utc).isoformat(), "read_only": True,
              "device": {key: shell("getprop", prop).strip() for key, prop in {
                  "manufacturer": "ro.product.manufacturer", "model": "ro.product.model",
                  "android": "ro.build.version.release", "sdk": "ro.build.version.sdk",
                  "build": "ro.build.version.incremental"}.items()}}
    package = shell("dumpsys", "package", PACKAGE)
    version = re.search(r"\bversionName=([^\s]+)", package)
    report["app_version"] = version[1] if version else None
    report["power"] = power_state(shell("dumpsys", "power"))
    report["display_states"] = display_states(shell("dumpsys", "display"))
    report["device_idle"] = {key: shell("cmd", "deviceidle", "get", key).strip()
                             for key in ["deep", "light", "screen", "charging", "network"]}
    report["battery_exemption_kinds"] = whitelist_kinds(shell("cmd", "deviceidle", "whitelist"))
    report["notification_access"] = listener_enabled(shell("settings", "get", "secure", "enabled_notification_listeners"))
    report["background_appops"] = {op: appop_mode(shell("cmd", "appops", "get", PACKAGE, op))
                                  for op in ["RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND", "WAKE_LOCK", "START_FOREGROUND"]}
    report["process"] = process_state(shell("dumpsys", "activity", "processes", PACKAGE))
    services = shell("dumpsys", "activity", "services", PACKAGE)
    report["listener_service_record_present"] = "NotificationBridge" in services
    report["foreground_service_present"] = "isForeground=true" in services
    report["boolean_power_settings"] = {namespace: boolean_settings(shell("settings", "list", namespace))
                                         for namespace in ["secure", "system", "global"]}
    if args.app_report:
        dump = shell("dumpsys", "activity", "service", PACKAGE + "/.ForwardingService")
        try:
            body = dump.split("NOTIPUSH_DIAGNOSTICS_BEGIN", 1)[1].split("NOTIPUSH_DIAGNOSTICS_END", 1)[0].strip()
            report["app_diagnostics"] = json.loads(body)
        except (IndexError, ValueError):
            report["app_diagnostics"] = {"available": False, "reason": "No running service report; use NotiPush 1.0.4+ and enable forwarding"}
    report["interpretation"] = [
        "OFF versus DOZE/DOZE_SUSPEND distinguishes a dark display from framework AOD at snapshot time.",
        "An enabled AOD setting does not prove notification delivery, CPU availability, or internet access.",
        "Vendor setting names vary; boolean settings are observations, not a verified OxygenOS menu mapping.",
        "Use wireless debugging unplugged to reproduce standby; USB charging and ADB observation can affect power state.",
        "No settings were changed, no notification contents read, and no synthetic notification posted.",
        "--app-report invokes app code. Read it after the test, never poll it to prove unattended delivery."]
    if args.output:
        fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        path = args.output
    else:
        fd, name = tempfile.mkstemp(prefix="notipush-background-", suffix=".json")
        path = Path(name)
    with os.fdopen(fd, "w") as file:
        json.dump(report, file, indent=2)
        file.write("\n")
    print(json.dumps(report, indent=2))
    print(f"Private report: {path}")


if __name__ == "__main__":
    main()
