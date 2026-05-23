# Metrics (vmagent → VictoriaMetrics → Grafana)

This folder holds **observability** configs for the stack around RIID. It is **not** read by the RIID `ConfigLoader` (`config/config.yaml` is the application config only).

## vmagent

- **File:** `vmagent-scrape.yaml` — Prometheus-compatible `global` / `scrape_configs` for pulling `GET /metrics` from the RIID daemon TCP listener.
- **Run** (example; adjust paths and URLs):

```bash
vmagent \
  -promscrape.config=/path/to/config/metrics/vmagent-scrape.yaml \
  -remoteWrite.url=http://victoriametrics:8428/api/v1/write
```

Point `static_configs.targets` at the host and port where the daemon exposes metrics (`app.daemon.metricsHost`, `app.daemon.metricsPort`).

## Grafana

- **Tree:** `grafana/provisioning/` — datasources and dashboard providers (YAML).
- **Dashboards:** `grafana/dashboards/` — drop exported `*.json` dashboards here.
- **Default home:** `grafana/home/riid-home.json` — mounted into the container and set via `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH` (see root `Makefile` target `grafana`).

**Docker** (example mounts):

```yaml
volumes:
  - ./config/metrics/grafana/provisioning:/etc/grafana/provisioning:ro
  - ./config/metrics/grafana/dashboards:/etc/grafana/dashboards:ro
  - ./config/metrics/grafana/home:/etc/grafana/home-dashboard:ro
environment:
  GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH: /etc/grafana/home-dashboard/riid-home.json
```

Default `url` is `http://host.docker.internal:8428` for VictoriaMetrics on the **host** (see `make grafana`). In Compose/Kubernetes, change `url` to your service (e.g. `http://victoriametrics:8428`).

**Makefile (repo root):** `make metrics-stack-create` — tear down old stack if needed, then start VM + Grafana + vmagent; `make metrics-stack-update` — `docker restart grafana` after editing JSON under `grafana/dashboards/`; `make metrics-stack-down` — remove the three containers.
