#!/usr/bin/env bash
# Совместимость: BACKEND=podman из performance/Makefile и старых команд.
# Реализация переехала в backend/bare.sh + backend/engine/podman.inc.sh, чтобы
# baseline-арм был один на все движки. Поведение прежнее.
set -euo pipefail
# Через bash, а не exec самого файла: backend/*.sh лежат без бита x (их и
# run-pull-scenario.sh запускает как `bash "$BACKEND_CMD"`), так что прямой exec
# упал бы на Permission denied.
exec env ENGINE=podman bash "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bare.sh" "$@"
