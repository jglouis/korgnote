#!/usr/bin/env bash
# Builds a signed release APK of the Android app, with the release key taken from KeePassXC: the
# keystore attached to an entry, its password in the entry's Password, and the key's in a
# KeyPassword attribute if it has one of its own (a PKCS12 keystore's key shares the store's).
#
#   scripts/release.sh [DATABASE.kdbx]     # or set KORGNOTE_KDBX
#
#   KORGNOTE_KDBX_ENTRY       the entry; "KorgNote release key" by default, "Group/Entry" in a group
#   KORGNOTE_KDBX_ATTACHMENT  the keystore's name among its attachments; korgnote-release.jks
#
# The keystore only touches the disk in a private temporary folder, gone when the script ends, and
# the passwords reach Gradle through its environment, never a file.
set -euo pipefail

database=${1:-${KORGNOTE_KDBX:-}}
entry=${KORGNOTE_KDBX_ENTRY:-KorgNote release key}
attachment=${KORGNOTE_KDBX_ATTACHMENT:-korgnote-release.jks}
if [[ -z $database ]]; then
  echo "Which KeePassXC database holds the release key? Pass it, or set KORGNOTE_KDBX." >&2
  exit 2
fi

cli=$(command -v keepassxc-cli || echo /Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli)
if [[ ! -x $cli ]]; then
  echo "keepassxc-cli is neither on the PATH nor in /Applications/KeePassXC.app." >&2
  exit 1
fi

read -rsp "Password of $database: " database_password
echo
# keepassxc-cli reads the database password from standard input, which keeps it out of `ps`.
unlock() { printf '%s\n' "$database_password"; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unlock | "$cli" attachment-export -q "$database" "$entry" "$attachment" "$work/release.keystore"
store_password=$(unlock | "$cli" show -q -s -a Password "$database" "$entry")
key_alias=$(unlock | "$cli" show -q -a UserName "$database" "$entry")
key_password=$(unlock | "$cli" show -q -s -a KeyPassword "$database" "$entry" 2>/dev/null) ||
  key_password=$store_password

cd "$(dirname "$0")/.."
# Without the configuration cache, which would keep the passwords it was handed on disk.
ORG_GRADLE_PROJECT_KORGNOTE_STORE_FILE="$work/release.keystore" \
ORG_GRADLE_PROJECT_KORGNOTE_STORE_PASSWORD="$store_password" \
ORG_GRADLE_PROJECT_KORGNOTE_KEY_ALIAS="${key_alias:-korgnote}" \
ORG_GRADLE_PROJECT_KORGNOTE_KEY_PASSWORD="$key_password" \
  ./gradlew :androidApp:assembleRelease --no-configuration-cache

apk=androidApp/build/outputs/apk/release/androidApp-release.apk
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)
if [[ -n $apksigner ]]; then
  "$apksigner" verify --print-certs "$apk" 2>/dev/null | grep 'SHA-256'
fi
echo
echo "Signed: $apk"
echo "Install: adb -s <serial> install -r $apk"
