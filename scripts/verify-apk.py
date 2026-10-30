#!/usr/bin/env python3
"""Reject corrupt, unsigned, or incorrectly packaged release APKs before delivery."""
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET


def check(condition, message):
    if not condition:
        raise ValueError(message)


def verify(apk):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        properties = Path(__file__).resolve().parents[1] / "local.properties"
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].replace("\\:", ":").replace("\\ ", " ")
    check(sdk, "Set ANDROID_HOME or sdk.dir in local.properties")
    versions = [p for p in (Path(sdk) / "build-tools").iterdir() if (p / "apksigner").is_file()]
    tools = max(versions, key=lambda p: tuple(int(v) for v in re.findall(r"\d+", p.name)))
    # Check both stored signature schemes. With minSdk 36 the default verifier
    # skips v2 and verifies only v3; this range verifies both, without changing
    # the APK's Android 16 minimum requirement.
    result = subprocess.run([str(tools / "apksigner"), "verify", "--min-sdk-version", "26",
                             "--max-sdk-version", "37", "--verbose", "--print-certs", str(apk)],
                            check=True, capture_output=True, text=True).stdout
    for scheme in (2, 3):
        check(re.search(rf"Verified using v{scheme} scheme .*: true", result), f"Missing v{scheme} signature")
    print(result.strip())
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
    badging = subprocess.run([str(tools / "aapt2"), "dump", "badging", str(apk)],
                             check=True, capture_output=True, text=True).stdout
    check("name='dev.notipush'" in badging.splitlines()[0], "Unexpected package identity")
    check("split=" not in badging.splitlines()[0], "Deliver a standalone APK, not a split")
    check("application-debuggable" not in badging and "application-testOnly" not in badging, "Not a production APK")
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.MULTILINE)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.MULTILINE)
    check(minimum is not None and target is not None, "Missing numeric Android SDK metadata")
    check(int(minimum[1]) <= 36, "APK cannot install on Android 16")
    check(int(target[1]) >= 37, "APK must target Android 17 or newer")
    print("PASS standalone production package, Android 16 minimum support, Android 17+ target")

    # Verify the minified APK, rather than source/debug classes. WorkManager
    # uses reflection for these entry points; a successful build alone cannot
    # detect R8 stripping their constructors.
    layout = ET.fromstring(subprocess.run([str(tools / "dexdump"), "-l", "xml", str(apk)],
                                        check=True, capture_output=True, text=True).stdout)
    classes = {pkg.get("name") + "." + cls.get("name"): cls
               for pkg in layout.findall("package") for cls in pkg.findall("class")}
    entry_points = {
        "androidx.work.impl.WorkDatabase_Impl": [],
        "androidx.work.OverwritingInputMerger": [],
        "dev.notipush.ForwardWorker": ["android.content.Context", "androidx.work.WorkerParameters"],
        "dev.notipush.RecoveryWorker": ["android.content.Context", "androidx.work.WorkerParameters"],
        "dev.notipush.ForwardingService": [],
    }
    for name, parameters in entry_points.items():
        cls = classes.get(name)
        check(cls is not None, f"Release reflection entry point was stripped: {name}")
        constructors = cls.findall("constructor")
        check(any(c.get("visibility") == "public" and
                  [p.get("type") for p in c.findall("parameter")] == parameters for c in constructors),
              f"Release public constructor was stripped: {name}{parameters}")
        print(f"PASS minified reflection constructor: {name}{parameters}")

    with zipfile.ZipFile(apk) as archive, apk.open("rb") as raw:
        check(archive.testzip() is None, "APK ZIP CRC verification failed")
        libraries = [f for f in archive.infolist() if f.filename.endswith(".so")]
        check(any(f.filename.startswith("lib/arm64-v8a/") for f in libraries), "Missing ARM64 library")
        for library in libraries:
            data = archive.read(library)
            check(data[:4] == b"\x7fELF" and data[5] == 1, f"Invalid ELF: {library.filename}")
            is64 = data[4] == 2
            offset = struct.unpack_from("<Q" if is64 else "<I", data, 32 if is64 else 28)[0]
            stride, count = struct.unpack_from("<HH", data, 54 if is64 else 42)
            load_alignments = []
            for index in range(count):
                header = offset + index * stride
                if struct.unpack_from("<I", data, header)[0] == 1:
                    alignment = struct.unpack_from("<Q" if is64 else "<I", data, header + (48 if is64 else 28))[0]
                    check(alignment >= 16384, f"ELF is not 16 KB aligned: {library.filename}")
                    load_alignments.append(alignment)
            check(load_alignments, f"ELF has no load segments: {library.filename}")
            if library.compress_type == zipfile.ZIP_STORED:
                raw.seek(library.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", raw.read(4))
                content_offset = library.header_offset + 30 + name_size + extra_size
                check(content_offset % 16384 == 0, f"Uncompressed JNI ZIP offset is not 16 KB aligned: {library.filename}")
            print(f"PASS {library.filename}: ELF alignments {load_alignments}, ZIP method {library.compress_type}")
    print(f"PASS signatures, ZIP integrity, alignment, and ARM64 support: {apk}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: verify-apk.py path/to/app.apk")
    try:
        verify(Path(sys.argv[1]))
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile, ET.ParseError) as error:
        sys.exit(f"APK verification failed: {error}")
