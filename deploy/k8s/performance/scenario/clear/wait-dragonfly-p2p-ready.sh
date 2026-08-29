#!/usr/bin/env bash
# Ждёт, пока P2P-меш Dragonfly действительно сойдётся, и только тогда возвращает 0.
#
# Зачем. dfdaemon и seed-client не знают адрес планировщика статически: они
# берут его из dynconfig манагера, а там лежит IP пода scheduler. После рестарта
# scheduler (его делает clear-cache-all-riid-pods.sh перед каждым армом) IP
# меняется, но манагер обновляет запись не сразу, а клиент перечитывает
# dynconfig раз в минуту. В этом окне каждый announce/download уходит на мёртвый
# адрес, dfdaemon молча скатывается в back-to-source, и арм меряет что угодно,
# только не P2P.
#
# Проверки (обе положительные, а не «нет ошибок в логе»):
#   1) в последнем "refresh available scheduler addresses: [...]" каждого пода
#      client/seed-client присутствуют IP всех живых подов scheduler. Строка
#      пишется при старте и при каждой смене списка, так что она есть всегда;
#   2) scheduler за последние SEED_WINDOW секунд не жаловался
#      "no seed peer found in host manager" — то есть seed-пиры зарегистрированы.
#      Здесь окно-без-ошибки уместно: строка пишется периодически, пока пиров нет.
#
# Проверять «нет ошибок health-client» по клиентам нельзя: записи схемы живут в
# MySQL манагера и переживают рестарты, так что в списке всегда болтаются адреса
# давно удалённых подов, и health-check по ним валится вечно.
#
# Env:
#   DRAGONFLY_NAMESPACE   — default: dragonfly-system
#   P2P_READY_TIMEOUT     — общий таймаут ожидания, сек (default: 420)
#   P2P_READY_INTERVAL    — период опроса, сек (default: 15)
#   P2P_READY_SEED_WINDOW — окно тишины по seed-пирам, сек (default: 120)
set -euo pipefail

DFS="${DRAGONFLY_NAMESPACE:-dragonfly-system}"
TIMEOUT="${P2P_READY_TIMEOUT:-420}"
INTERVAL="${P2P_READY_INTERVAL:-15}"
SEED_WINDOW="${P2P_READY_SEED_WINDOW:-120}"

SCHEDULER_ADDR_LINE='refresh available scheduler addresses'
NO_SEED_LINE='no seed peer found in host manager'

_pods() {
  kubectl -n "$DFS" get pods -l "app=dragonfly,component=$1" \
    --field-selector 'status.phase=Running' \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
}

# IP всех живых подов scheduler; ровно их и должен видеть клиент.
_scheduler_ips() {
  kubectl -n "$DFS" get pods -l 'app=dragonfly,component=scheduler' \
    --field-selector 'status.phase=Running' \
    -o jsonpath='{range .items[*]}{.status.podIP}{"\n"}{end}' | grep -v '^$' || true
}

# Последний список адресов, который клиент реально резолвил.
_client_sees() {
  kubectl -n "$DFS" logs "$1" --tail=-1 2>/dev/null \
    | grep -F "$SCHEDULER_ADDR_LINE" | tail -n 1 || true
}

deadline=$((SECONDS + TIMEOUT))
last_reason="no check performed yet"

while ((SECONDS < deadline)); do
  ready=1
  last_reason=""

  mapfile -t sched_ips < <(_scheduler_ips)
  if ((${#sched_ips[@]} == 0)); then
    ready=0
    last_reason="no Running scheduler pod"
  fi

  if ((ready)); then
    mapfile -t data_pods < <(_pods client; _pods seed-client)
    if ((${#data_pods[@]} == 0)); then
      ready=0
      last_reason="no Running client/seed-client pod"
    fi
    for pod in "${data_pods[@]}"; do
      [[ -z "$pod" ]] && continue
      line="$(_client_sees "$pod")"
      if [[ -z "$line" ]]; then
        ready=0
        last_reason="$pod has not resolved a scheduler address yet"
        break
      fi
      for ip in "${sched_ips[@]}"; do
        if [[ "$line" != *"$ip"* ]]; then
          ready=0
          last_reason="$pod still points at a stale scheduler (want $ip, has: ${line##*: })"
          break 2
        fi
      done
    done
  fi

  if ((ready)); then
    for pod in $(_pods scheduler); do
      if kubectl -n "$DFS" logs "$pod" --since="${SEED_WINDOW}s" 2>/dev/null | grep -qF "$NO_SEED_LINE"; then
        ready=0
        last_reason="$pod still reports: $NO_SEED_LINE"
        break
      fi
    done
  fi

  if ((ready)); then
    echo "wait-dragonfly-p2p-ready: mesh converged on scheduler ${sched_ips[*]}" >&2
    exit 0
  fi

  echo "wait-dragonfly-p2p-ready: waiting ($last_reason)" >&2
  sleep "$INTERVAL"
done

echo "wait-dragonfly-p2p-ready: TIMEOUT after ${TIMEOUT}s ($last_reason)" >&2
exit 1
