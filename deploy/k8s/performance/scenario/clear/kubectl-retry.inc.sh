#!/usr/bin/env bash
# One retry wrapper for all three cache cleaners. The workstation reaches the MKS
# API over the public internet, and a dropped handshake mid-clear leaves nodes
# holding the previous arm: on 2026-09-11 the RIID clear stopped after 6 of 10
# pods on "i/o timeout" and the arm ran warm on the rest.
#
# Retried ONLY when the connection never opened, so nothing ran and no real
# failure is hidden; a command that ran and exited non-zero passes through.
riid_kc() {
  local attempt=1 max="${CLEAR_CONNECT_RETRIES:-4}" err rc
  err="$(mktemp)"
  while :; do
    rc=0
    kubectl "$@" 2>"$err" || rc=$?
    cat "$err" >&2
    if ((rc != 0)) && ((attempt < max)) && grep -qE \
        'connect: connection (timed out|refused)|connect: no route to host|Unable to connect to the server|TLS handshake timeout|i/o timeout|error dialing backend' "$err"; then
      echo "clear: API unreachable, attempt $attempt/$max, retrying in 5s" >&2
      attempt=$((attempt + 1)); sleep 5; continue
    fi
    rm -f "$err"; return "$rc"
  done
}
