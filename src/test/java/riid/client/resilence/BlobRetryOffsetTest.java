package riid.client.resilence;

class BlobRetryOffsetTest {
    //TODO на этапе оптимизации - в конце проетка
    /*
    Коротко: сейчас у клиента нет поддержки HTTP Range.
То есть:
BlobRequest не содержит параметров offset/length.
BlobService.fetchBlob() всегда скачивает blob целиком с нуля.
Resume/докачка при обрыве сети не реализована, только повторная попытка с начала.
Если нужен resume через Range — это придётся добавить отдельно как новую функциональность.
    */
}
