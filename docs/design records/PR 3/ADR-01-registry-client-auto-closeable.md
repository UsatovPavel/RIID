# ADR-01: RegistryClient implements AutoCloseable

## Контекст
- `RegistryClientImpl` управляет Jetty HttpClient и должен корректно освобождать ресурсы.
- Тесты и прод-код используют try-with-resources и ожидают явный протокол закрытия.

## Решение
- Интерфейс `RegistryClient` расширяет `AutoCloseable` и объявляет `close()` (checked).
- Реализация `RegistryClientImpl.close()` пробрасывает checked исключение от остановки Jetty.

## Последствия
- Можно безопасно применять try-with-resources для всех реализаций клиента.
- Вызывающим требуется либо try-with-resources, либо обработка/проброс `Exception` при закрытии.
- Контракт чётко фиксирует необходимость явного закрытия и облегчает статический анализ утечек ресурсов.

