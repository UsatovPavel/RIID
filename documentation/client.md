## Registry Client

### Конфиг
- Registry endpoints задаются через `RegistryEndpoint` (scheme/host/port/creds), список приходит из внешнего конфига/Orchestrator.
- HTTP-клиент: `HttpClientConfig` (таймауты, ретраи идемпотентных GET, backoff, User-Agent).
- Кэш: внешний `CacheAdapter` (опционально), клиент только пишет в кэш после скачивания; выбор источника (cache/P2P/registry) — за Orchestrator.

### Поддерживаемые операции
- `fetchManifest(repo, ref)` → `ManifestResult` (валидирует digest, умеет manifest list/индексы).
- `fetchBlob(request, file)` → `BlobResult` (SHA256, размер, Range/ретраи).
- `headBlob(repo, digest)` → Optional size.
- `fetchConfig(repo, manifest, file)` → blob по config.digest.
- `listTags(repo, n, last)` (HTTP к registry).

### Как использовать
```java
RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry-1.docker.io", -1, null);
HttpClientConfig httpCfg = HttpClientConfig.builder().maxRetries(2).build();
CacheAdapter cache = null;// TODO
RegistryClient client = new RegistryClientImpl(endpoint, httpCfg, cache);

var manifest = client.fetchManifest("library/busybox", "latest").manifest();
var layer = manifest.layers().getFirst();
File tmp = Files.createTempFile("layer", ".tar").toFile();
var res = client.fetchBlob(new BlobRequest("library/busybox", layer.digest(), layer.size(), layer.mediaType()), tmp);
```

### Медиатипы
- OCI: manifest/index/config/layers (zstd/gzip/тар).
- Docker schema2: manifest/list/config/layers.
Accept заголовок включает OCI и Docker.

### Тесты
- Юнит: парсинг манифестов/индексов, WWW-Authenticate, URI/Range, digest/size.
- Интеграция  — `RegistryLocalTest` 
- Публичный (Docker Hub) — `RegistryLiveTest` (alpine/busybox) — можно запускать локально.
- Стресс (tag `stress`, не в CI): массовые скачивания маленьких слоёв, ретраи.

### Ограничения/архитектура
- Клиент не выбирает источник (cache/P2P/registry) и не знает о runtime; это делает Orchestrator/Runtime Adapter.
- Клиент валидирует digest/size при скачивании; финальная проверка/импорт — на уровне Runtime Adapter.

