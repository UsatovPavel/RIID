#!/bin/bash
# Саму подготовку тестового окружения я не покажу потому что она слишком от локального компьютера завивист
# P2P-тест Dragonfly: проверка инфраструктуры между двумя нодами.
# Запускать на VM 1 (где есть kubectl).
set -e

URL="${1:-https://httpbin.org/bytes/1024}"

echo ">>> P2P test: $URL"
POD_VM2=$(kubectl get pods -n dragonfly-system -l app=dragonfly,component=client -o wide | grep server2 | awk '{print $1}')
POD_VM1=$(kubectl get pods -n dragonfly-system -l app=dragonfly,component=client -o wide | grep ubuntu24 | awk '{print $1}')

test -n "$POD_VM2" || { echo "No dfdaemon pod on server2"; exit 1; }
test -n "$POD_VM1" || { echo "No dfdaemon pod on ubuntu24"; exit 1; }

echo ">>> Seed (server2): $POD_VM2"
kubectl exec -n dragonfly-system $POD_VM2 -- dfget --output /tmp/out1 "$URL"

echo ">>> Fetch (ubuntu24): $POD_VM1"
kubectl exec -n dragonfly-system $POD_VM1 -- dfget --output /tmp/out2 "$URL"

echo ">>> Verify:"
kubectl exec -n dragonfly-system $POD_VM1 -- ls -la /tmp/out2
echo ">>> Done"
