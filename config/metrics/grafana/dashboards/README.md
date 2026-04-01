Put Grafana dashboard **JSON** exports here (e.g. from UI → Share → Export). The file provider loads all `*.json` in this directory when the container mounts this folder to `/etc/grafana/dashboards`.

- **`riid-main.json`** — main load SLO dashboard (unsuccess rate, pipeline latency p50/p95, throughput p95 for tar ≥ 10 MiB). See PR 14 plan for SLO targets.
