Put Grafana dashboard **JSON** exports here (e.g. from UI → Share → Export). The file provider loads all `*.json` in this directory when the container mounts this folder to `/etc/grafana/dashboards`.

- **`riid-main.json`** — main load SLO dashboard (unsuccess rate, pipeline latency p50/p95, throughput p95 for tar ≥ 10 MiB, layer **byte volume** by dispatcher source `increase(riid_dispatcher_layer_bytes_total[6h])` per `cache` / `p2p` / `registry`). Tune SLO targets in panels or alerting as needed.
- **`riid-health.json`** — CPU and heap gauges; dashboard link to `riid-main` (Grafana uid in JSON: `riid-daemon-resources`).
- **`riid-daemon-errors.json`** — POST /pull HTTP classes, timeout vs pipeline errors, CPU/memory timeseries.
- **`riid-image-sizes.json`** — tar size bucket counts and load duration p50/p95 by bucket.
