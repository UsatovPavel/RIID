## Устанавливаем кластер
Вставляем конфиг в providers/cluster/Selectel и проксируем grafana в 1 терминале
make -C deploy/k8s/bootstrap install-all
## Говорим откуда в тесте будем скачивать во 2 терминале 
make -C deploy/k8s/bootstrap connect
make -C deploy/k8s/bootstrap/registry install-local-registry
make -C deploy/k8s/bootstrap/registry wait-local-registry
### small images 
make -C deploy/k8s/bootstrap/registry load-performance-registry-dataset
### big images
cp deploy/k8s/providers/registry/image/dataset/output/dataset_local_b.tsv \
   deploy/k8s/performance/input/dataset_b.tsv
make -C deploy/k8s/bootstrap/registry load-performance-registry-dataset \
  PERF_REGISTRY_DATASET="$(pwd)/deploy/k8s/performance/input/dataset_b.tsv"

make -C deploy/k8s/performance debug-registry-node
## Тестирование
make -C deploy/k8s/bootstrap/registry registry-apply-test-profile
make -C deploy/k8s/performance clear-cluster-cache
make -C deploy/k8s/performance run BACKEND=riid MODE=rolling CONCURRENCY=2 DATASET=A SCENARIO=perf-multi-riid
make -C deploy/k8s/performance run BACKEND=podman MODE=rolling CONCURRENCY=2 DATASET=A 
make -C deploy/k8s/performance summarize

## Настроить сеть
make -C deploy/k8s/bootstrap registry-node-tc-apply

## Смена test registry_provider или test images:
Поменять config.yaml
Перегенирировать тестовые dataset.
make -C deploy/k8s/providers generate-registry-image-lists




## Init registry

REGISTRY_PUSH_REPO_STRIP_LIBRARY=0 \
REGISTRY_MIRROR_TSV_ONLY=1 \
RIID_IMAGES_LIST=deploy/k8s/bootstrap/registry/init/infra_images_list.tsv \
make -C deploy/k8s/bootstrap/registry init-performance-registry-images

## Debug
kubectl -n dragonfly-system get pods &> _error.txt
helm -n dragonfly-system status dragonfly >> _error.txt
helm -n dragonfly-system history dragonfly >> _error.txt



kubectl -n dragonfly-system get pods
export NODE=
kubectl logs "$NODE" -n dragonfly-system
kubectl describe pod dragonfly-mysql-0 -n dragonfly-system | sed -n '/Events/,$p'


## Logs
kubectl -n riid-system get pods -o wide

# 2) Узнать node этого pod (подставь свой pod)
kubectl -n riid-system get pod riid-4n5cj -o jsonpath='{.spec.nodeName}{"\n"}'

# 3) Логи RIID + dfdaemon на этой node за окно падения
make -C deploy/k8s/bootstrap/logs logs-pull-all SINCE=120m NODE=<NODE_NAME> > _riid_dfdaemon_raw.txt


make -C deploy/k8s/bootstrap/logs logs-summarize-pull \
  RAW_LOG="$(pwd)/_riid_dfdaemon_raw.txt" \
  SUMMARY_LOG="$(pwd)/_riid_dfdaemon_summary.txt"