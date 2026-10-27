#!/usr/bin/env bash
# Builds and starts the sync server container from docker-compose.yml.
#
#   scripts/run-server-container.sh [NOTES_DIR] [docker compose up options, e.g. -d]
#
# Settings come from the environment, then from .env at the repository root, then from defaults.
# The notes directory is saved in .env, so later runs need no argument. This computer's addresses
# and name are looked up on every run and handed to the container, which cannot see them, for the
# pairing QR code; the certificate and paired devices live in ~/.korgnote-server.
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/host-addresses.sh
env_file=.env

# Prints KEY's value from .env, without quotes; nothing when the file or the key is missing.
env_value() {
  if [[ -f $env_file ]]; then
    sed -n "s/^$1=//p" "$env_file" | tail -n 1 | sed -e 's/^["'\'']//' -e 's/["'\'']$//'
  fi
}

# Appends KEY=value to .env (created readable by you only) unless the key is already there.
save_env_value() {
  [[ -n $(env_value "$1") ]] && return
  if [[ -s $env_file && -n $(tail -c 1 "$env_file") ]]; then echo >>"$env_file"; fi
  (umask 077 && echo "$1=$2" >>"$env_file")
}

notes_dir=
if [[ $# -gt 0 && $1 != -* ]]; then
  notes_dir=$1
  shift
fi
notes_dir=${notes_dir:-${NOTES_HOST_DIR:-$(env_value NOTES_HOST_DIR)}}
notes_dir=${notes_dir:-$HOME/notes}
notes_dir=${notes_dir/#\~/$HOME}
if [[ ! -d $notes_dir ]]; then
  echo "Notes directory not found: $notes_dir" >&2
  echo "Usage: $0 [NOTES_DIR] [docker compose up options]" >&2
  exit 1
fi
notes_dir=$(cd "$notes_dir" && pwd)
save_env_value NOTES_HOST_DIR "$notes_dir"

state_dir=${KORGNOTE_STATE_HOST_DIR:-$(env_value KORGNOTE_STATE_HOST_DIR)}
state_dir=${state_dir:-$HOME/.korgnote-server}
state_dir=${state_dir/#\~/$HOME}
# Made here, by you, so that Docker does not make it as root.
mkdir -p "$state_dir"
chmod 700 "$state_dir"

# Exported values win over .env in docker compose; an argument must override a saved directory.
export NOTES_HOST_DIR=$notes_dir KORGNOTE_STATE_HOST_DIR=$state_dir
uid=${KORGNOTE_UID:-$(env_value KORGNOTE_UID)}
gid=${KORGNOTE_GID:-$(env_value KORGNOTE_GID)}
export KORGNOTE_UID=${uid:-$(id -u)} KORGNOTE_GID=${gid:-$(id -g)}
export KORGNOTE_ADVERTISED_ADDRESSES=${KORGNOTE_ADVERTISED_ADDRESSES:-$(host_addresses)}
export KORGNOTE_SERVER_NAME=${KORGNOTE_SERVER_NAME:-$(host_name)}
port=${KORGNOTE_HOST_PORT:-$(env_value KORGNOTE_HOST_PORT)}

if [[ -n $(env_value KORGNOTE_TOKEN) ]]; then
  echo "KORGNOTE_TOKEN in $env_file is no longer used: devices pair instead. It can be removed."
fi
echo "Notes:   $notes_dir"
echo "State:   $state_dir"
echo "Web:     http://localhost:${port:-8080}/"
echo "Devices: http://localhost:${port:-8080}/devices   (pair a phone here, or with scripts/pair.sh)"
exec docker compose up --build "$@"
