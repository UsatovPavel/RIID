# riid-podman-prefix — 2026-09-05 20:47-21:36

19 из 20 образов, сумма AGGREGATE 2888.0 с **(19/20, grafana исключена)**,
egress 14.94 GiB — ×1.35 к датасету 11.07 GiB; источник слоёв p2p=430, cache=35,
**registry=0**. Впервые за эпик podman выполнил инкрементальный импорт: 378
записей `Prefix of N layers handed to podman`.
Единственный отказ — `riid/grafana/grafana` — детерминированный дефект RIID, а не
сбой среды. `PrefixImportLayouts.writeLayout` (строки 229-230) переносит media
type исходного манифеста в OCI-layout дословно, а `podman pull -q oci:` такой тип
не принимает: `unsupported manifest MIME types
["application/vnd.docker.distribution.manifest.v2+json"]`, затем
`aborted after 1 of 11 layers`. Во всём датасете ровно один докеровский манифест —
grafana — и упал ровно он.
Значение: арка сравнима **по образам**, но её сумма не сопоставима с суммами
20/20 без пометки — grafana оборвалась через 42.3 с после одного слоя из
одиннадцати, так что итог занижен на её недостающую часть. containerd-версия
префикса от этого дефекта защищена конструктивно: она отдаёт layout через
`ctr images import`, а не через транспорт `oci:`, и grafana проходит у неё в
обоих прогонах. Ещё 11 слоёв были выброшены уже после успешного скачивания
дефектом закрытия puller'а.
