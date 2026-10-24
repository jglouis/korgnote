#!/usr/bin/env bash
# Builds and starts the sync server container from docker-compose.yml.
#
#   scripts/run-server-container.sh [NOTES_DIR] [docker compose up options, e.g. -d]
#
# Settings come from the environment, then from .env at the repository root, then from defaults.
# A missing token is generated once and saved in .env, so the app keeps working across runs; the
# notes directory is saved there too, so later runs need no argument.
set -euo pipefail

cd "$(dirname "$0")/.."
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

token=${KORGNOTE_TOKEN:-$(env_value KORGNOTE_TOKEN)}
if [[ -z $token ]]; then
  token=$(openssl rand -hex 16)
  save_env_value KORGNOTE_TOKEN "$token"
  echo "Generated a new token and saved it in $env_file; enter it in the app's Settings."
fi

# Exported values win over .env in docker compose; an argument must override a saved directory.
export NOTES_HOST_DIR=$notes_dir KORGNOTE_TOKEN=$token
uid=${KORGNOTE_UID:-$(env_value KORGNOTE_UID)}
gid=${KORGNOTE_GID:-$(env_value KORGNOTE_GID)}
export KORGNOTE_UID=${uid:-$(id -u)} KORGNOTE_GID=${gid:-$(id -g)}
port=${KORGNOTE_HOST_PORT:-$(env_value KORGNOTE_HOST_PORT)}

echo "Notes: $notes_dir"
echo "Token: $token"
echo "Web:   http://localhost:${port:-8080}/"
exec docker compose up --build "$@"
