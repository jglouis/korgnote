#!/usr/bin/env bash
# Builds a signed release APK of the Android app, with the release key taken from KeePassXC: the
# keystore attached to an entry, its password in the entry's Password, and the key's in a
# KeyPassword attribute if it has one of its own (a PKCS12 keystore's key shares the store's).
#
#   scripts/release.sh [DATABASE.kdbx]     # or set KORGNOTE_KDBX
#
#   KORGNOTE_KDBX_ENTRY       the entry; "KorgNote release key" by default, "Group/Entry" in a group
#   KORGNOTE_KDBX_ATTACHMENT  the keystore's name among its attachments; korgnote-release.jks
#   KORGNOTE_KDBX_KEYFILE     the database's key file, if it is opened with one besides its password
#
# The keystore only touches the disk in a private temporary folder, gone when the script ends, and
# the passwords reach Gradle through its environment, never a file.
set -euo pipefail

database=${1:-${KORGNOTE_KDBX:-}}
entry=${KORGNOTE_KDBX_ENTRY:-KorgNote release key}
attachment=${KORGNOTE_KDBX_ATTACHMENT:-korgnote-release.jks}
key_file=${KORGNOTE_KDBX_KEYFILE:-}
if [[ -z $database ]]; then
  echo "Which KeePassXC database holds the release key? Pass it, or set KORGNOTE_KDBX." >&2
  exit 2
fi

cli=$(command -v keepassxc-cli || echo /Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli)
if [[ ! -x $cli ]]; then
  echo "keepassxc-cli is neither on the PATH nor in /Applications/KeePassXC.app." >&2
  exit 1
fi

trap 'echo "release.sh stopped at line $LINENO." >&2' ERR
IFS= read -rsp "Password of $database: " database_password
echo

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# keepassxc-cli reads the database password from standard input, which keeps it out of `ps`. Its
# prompt is dropped but not its errors, which -q would silence too, a rejected password among them.
kp() {
  local command=$1
  shift
  if ! printf '%s\n' "$database_password" |
    "$cli" "$command" ${key_file:+-k "$key_file"} "$database" "$entry" "$@" 2>"$work/kp.err"; then
    grep -v '^Enter password' "$work/kp.err" >&2
    if [[ -z $key_file ]] && grep -q 'Invalid credentials' "$work/kp.err"; then
      echo "If the database also needs a key file, set KORGNOTE_KDBX_KEYFILE to it." >&2
    fi
    return 1
  fi
}

kp attachment-export "$attachment" "$work/release.keystore" >/dev/null
store_password=$(kp show -s -a Password)
key_alias=$(kp show -a UserName)
key_password=$(kp show -s -a KeyPassword 2>/dev/null) || key_password=$store_password

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
