#!/usr/bin/env bash
# Prints this computer's private IPv4 addresses, comma-separated: where a phone on the same network
# can reach the server. Sourced by run-server-container.sh and pair.sh; also runs on its own.
#
# A container cannot see these for itself, which is why the scripts ask the host.

host_addresses() {
  local all
  case "$(uname -s)" in
    Darwin)
      all=$(for i in $(ipconfig getiflist 2>/dev/null); do ipconfig getifaddr "$i" 2>/dev/null; done)
      ;;
    *)
      all=$(hostname -I 2>/dev/null | tr ' ' '\n')
      ;;
  esac
  # Home and office networks only: not loopback, not link-local, not a VPN's public address.
  printf '%s\n' $all |
    grep -E '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' |
    grep -vE '^172\.17\.' |
    paste -sd, -
}

host_name() {
  if [[ $(uname -s) == Darwin ]]; then
    scutil --get ComputerName 2>/dev/null || hostname -s
  else
    hostname
  fi
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
  host_addresses
fi
