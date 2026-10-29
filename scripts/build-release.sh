#!/usr/bin/env bash
# Create a private local signing identity once, then reuse it for every update.
set -euo pipefail
umask 077
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
signing_dir="${NOTIPUSH_SIGNING_DIR:-${HOME}/.local/share/notipush/signing}"
mkdir -p "$signing_dir"
chmod 700 "$signing_dir"
export NOTIPUSH_STORE_FILE="$signing_dir/release.p12"
password_file="$signing_dir/store-password"
if [[ ! -f "$NOTIPUSH_STORE_FILE" ]]; then
    if [[ ! -f "$password_file" ]]; then
        openssl rand -hex 32 > "$password_file"
    fi
    export NOTIPUSH_STORE_PASSWORD="$(cat "$password_file")"
    keytool -genkeypair -storetype PKCS12 -keystore "$NOTIPUSH_STORE_FILE" \
        -alias notipush -keyalg RSA -keysize 3072 -validity 10000 \
        -storepass:env NOTIPUSH_STORE_PASSWORD \
        -dname 'CN=NotiPush Local Release, OU=Personal Apps, O=NotiPush'
else
    if [[ ! -f "$password_file" ]]; then
        echo "Signing password is missing: $password_file" >&2
        exit 1
    fi
    export NOTIPUSH_STORE_PASSWORD="$(cat "$password_file")"
fi
cd "$project_dir"
./gradlew :app:assembleRelease --console plain
python3 scripts/verify-apk.py app/build/outputs/apk/release/app-release.apk
app_version="$(python3 -c 'import json; print(json.load(open("app/build/outputs/apk/release/output-metadata.json"))["elements"][0]["versionName"])')"
artifact="dist/NotiPush-${app_version}.apk"
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "$artifact"
shasum -a 256 "$artifact" > dist/SHA256SUMS
chmod 644 "$artifact" dist/SHA256SUMS
echo "Built $artifact"
echo "Keep a private backup of $signing_dir to sign future updates."
