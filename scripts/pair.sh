#!/usr/bin/env bash
# Prints a QR code to pair a phone with the server running on this computer, with this computer's
# current addresses in it. Scan it in KorgNote's Settings, under Pair with a computer.
#
#   scripts/pair.sh [PORT]      # the server's plain port on this computer; 8080 by default
#
# The same code is on the Devices page, http://localhost:8080/devices, which also lists the paired
# devices and revokes them.
set -euo pipefail

source "$(dirname "$0")/host-addresses.sh"

port=${1:-${KORGNOTE_HOST_PORT:-8080}}
addresses=$(host_addresses)
if [[ -z $addresses ]]; then
  echo "This computer has no address on a private network; is it connected?" >&2
fi

curl --silent --show-error --fail-with-body -X POST \
  "http://localhost:$port/api/local/pairing?addresses=$addresses"
