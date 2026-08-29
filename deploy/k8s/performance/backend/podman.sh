#!/usr/bin/env bash
# Compatibility shim for BACKEND=podman, as performance/Makefile and older
# commands spell it. The implementation moved to backend/bare.sh plus
# backend/engine/podman.inc.sh so that one baseline arm serves every engine.
# Behaviour is unchanged.
set -euo pipefail
# Through bash rather than exec'ing the file itself: backend/*.sh carry no x bit
# (run-pull-scenario.sh also runs them as `bash "$BACKEND_CMD"`), so a direct
# exec would fail with Permission denied.
exec env ENGINE=podman bash "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bare.sh" "$@"
