# riid-podman-prefix — 2026-09-05 20:47-21:36

19 из 20 образов, сумма AGGREGATE 2888.0 с, egress 14.94 GiB — ×1.35 к датасету
11.07 GiB; источник слоёв p2p=860, cache=70, **registry=0**.
Единственный отказ — `riid/grafana/grafana`, и он не случайный: это дефект RIID.
Префиксный импорт переносит media type исходного манифеста в OCI-layout, который
отдаёт podman, а транспорт `oci:` докеровский тип не принимает:
`matches unsupported manifest MIME types
["application/vnd.docker.distribution.manifest.v2+json"]`, затем
`Prefix import ... aborted after 1 of 11 layers`. Доказано перебором: во всём
датасете ровно один образ с докеровским манифестом — grafana — и упал ровно он,
остальные 19 в формате OCI и прошли (PodmanRuntimeAdapter.java:213).
Значение: podman впервые выполнил инкрементальный импорт на этом стенде —
756 записей `Prefix of N layers handed to podman`. Но арка самая медленная из
riid-* (2888.0 с против 2106.3 у riid-containerd), а 22 слоя были выброшены уже
после успешного скачивания дефектом закрытия puller'а.
