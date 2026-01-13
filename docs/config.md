## Модуль config

### Назначение
Парсинг и валидация YAML-конфигурации приложения (клиент реестра + диспетчер). Загрузка — `ConfigLoader`, правила — `ConfigValidator`.

### Минимальный валидный пример
```yaml
client:
  http: {}
  auth:
    defaultTokenTtlSeconds: 300
  registries:
    - scheme: https
      host: registry.example.com
      port: -1
dispatcher:
  maxConcurrentRegistry: 1
```

### Дефолты (по выводу smoke-теста)
- client.http.connectTimeout = PT5S
- client.http.requestTimeout = PT30S
- client.http.maxRetries = 0
- client.http.initialBackoff = PT0.2S
- client.http.maxBackoff = PT2S
- client.http.retryIdempotentOnly = false
- client.http.userAgent = riid-registry-client
- client.http.followRedirects = false
- client.auth.defaultTokenTtlSeconds = 300
- client.auth.certPath / keyPath / caPath = null
- client.registries.size = 1

### Правила валидации (ConfigValidator)
- Обязательны `client`, `dispatcher`, `registries`; минимум один registry с `scheme` и `host`.
- `dispatcher.maxConcurrentRegistry` > 0.
- `client.http`: таймауты/бэкоффы > 0, `initialBackoff <= maxBackoff`, `userAgent` не пустой, `maxRetries` < 0 запрещён (валидация падает).
- `client.auth.defaultTokenTtlSeconds` > 0; пути cert/key/ca, если заданы, должны существовать.

### Известные особенности
- Отсутствие `registries` приводит к `ConfigValidationException`.

### Тесты
- `ConfigBranchTest`: ветвления валидации (включая maxRetries < 0, отсутствие http/auth/registries/dispatcher).
- `ConfigLoaderTest` (integration/config_client): загрузка/валидация файлов.
- Smoke: `smokePrintsDefaultsFromMinimalConfig` — печать фактических дефолтов после загрузки минимального валидного YAML.

