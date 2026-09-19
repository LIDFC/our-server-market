# API v1

Базовый адрес: `http://127.0.0.1:8788/api/v1/market`

Слушает только петлевой интерфейс. Наружу выставлять нельзя: снаружи с ним говорит не браузер, а бэкенд сайта, который
работает на той же машине.

## Аутентификация

```
Authorization: Bearer <OUR_SERVER_MARKET_TOKEN>
```

Токен задаётся переменной окружения `OUR_SERVER_MARKET_TOKEN` или полем `api.token` в `config.yml`, минимум 24 символа.
Сравнение — постоянное по времени. Без токена или с неверным — `401`, и в лог сервера пишется строка с адресом
обратившегося (сам токен никогда не логируется).

Лимит: `api.rate-limit-per-minute` запросов в минуту с адреса (по умолчанию 120), сверх — `429`.

## Формат ошибок

```json
{ "error": { "code": "LISTING_NOT_FOUND", "message": "No such listing" } }
```

Коды повторяют перечисление `MarketError`, поэтому и игра, и сайт говорят об одном и том же одинаково.

| Код | HTTP | Когда |
|---|---|---|
| `UNAUTHORIZED` | 401 | нет токена или он неверный |
| `RATE_LIMITED` | 429 | слишком много запросов |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | POST без заголовка `Idempotency-Key` |
| `INVALID_REQUEST` | 400 | не разобрали тело или путь |
| `LISTING_NOT_FOUND`, `TRADE_NOT_FOUND` | 404 | нет такого лота или сделки |
| `NOT_OWNER`, `NOT_PARTICIPANT`, `NOT_RECIPIENT` | 403 | игрок не имеет отношения к объекту |
| `LISTING_ALREADY_TAKEN`, `TRADE_NOT_ACCEPTED`, `LISTING_NOT_ACTIVE` | 409 | состояние не позволяет |
| `INTERNAL_ERROR` | 500 | что-то сломалось, подробности в логе сервера |

## Чтение

### `GET /health`
```json
{ "ok": true, "schema": 1 }
```

### `GET /listings?type=TRADE&limit=25&offset=0`
`type` — `GIVEAWAY | TRADE | WANTED | GIFT`, необязательный. Отдаёт только активные лоты.

```json
{ "listings": [ {
  "id": 12,
  "type": "TRADE",
  "state": "ACTIVE",
  "ownerUuid": "…",
  "recipientUuid": null,
  "summary": "offers 16x diamond for 32x gold ingot",
  "createdAt": "2026-09-19T10:00:00Z",
  "offered": [ { "summary": "16x diamond", "amount": 16, "sha256": "…" } ],
  "wanted":  [ { "summary": "32x gold ingot", "amount": 32, "sha256": "…" } ]
} ] }
```

Байтов предмета в ответе нет и не будет: сайту они не нужны, а утечка NBT — лишний повод для фантазии.

### `GET /listings/{id}`
Тот же объект без обёртки. `404 LISTING_NOT_FOUND`, если лота нет.

### `GET /trades/{id}`
Одна сделка целиком, с обеими сторонами: `ownerItems` — что выложил автор лота, `buyerItems` — что предложил
покупатель. Без этого сайт видит только лот, но не предложение. `404 TRADE_NOT_FOUND`, если сделки нет.

```json
{ "id": 4, "listingId": 12, "ownerUuid": "…", "buyerUuid": "…", "state": "PENDING", "confirmations": [],
  "createdAt": "…",
  "ownerItems": [ { "summary": "16x diamond", "amount": 16, "sha256": "…" } ],
  "buyerItems":  [ { "summary": "32x gold ingot", "amount": 32, "sha256": "…" } ] }
```

### `GET /players/{uuid}/listings` · `/trades` · `/deliveries`
Лоты, сделки и ожидающие посылки конкретного игрока. `uuid` — Minecraft UUID.

```json
{ "trades": [ {
  "id": 4, "listingId": 12, "ownerUuid": "…", "buyerUuid": "…",
  "state": "ACCEPTED", "confirmations": ["BUYER"], "createdAt": "…"
} ] }
```

### `GET /events?since=0&limit=100`
Лента событий: `LISTING_CREATED`, `LISTING_CANCELLED`, `LISTING_CLAIMED`, `TRADE_CREATED`, `TRADE_ACCEPTED`,
`TRADE_CONFIRMED`, `TRADE_COMPLETED`, `TRADE_REJECTED`, `ESCROW_IN`, `PENDING_DELIVERY_CREATED`, `DELIVERY_CLAIMED`,
`RECOVERY`.

```json
{ "events": [ { "id": 57, "at": "…", "type": "TRADE_COMPLETED", "listingId": 12, "tradeId": 4 } ] }
```

Сайт хранит последний виденный `id` и спрашивает `since=<id>`. Этого достаточно для «обновлять раздел раз в 10 секунд».
WebSocket не нужен; если он когда-нибудь понадобится, лента уже пронумерована, и push становится надстройкой, а не
переделкой.

## Действия

Все действия — `POST`, с обязательным заголовком **`Idempotency-Key`** (до 100 символов, уникальный на операцию) и
телом:

```json
{ "minecraftUuid": "0000-…" }
```

`minecraftUuid` — от чьего имени действие. Плагин сам проверяет, имеет ли этот игрок право: сайт не может отменить
чужой лот, даже если очень попросит.

| Метод и путь | Что делает |
|---|---|
| `POST /listings/{id}/cancel` | снять свой лот; предметы уходят в очередь выдачи владельцу |
| `POST /trades/{id}/accept` | владелец принимает предложение |
| `POST /trades/{id}/decline` | любая из сторон отказывается; предметы покупателя возвращаются |
| `POST /trades/{id}/confirm` | подтверждение стороны; когда подтвердили обе — сделка завершается |

Ответ:

```json
{ "ok": true, "tradeId": 4, "completed": true }
```

### Идемпотентность

Ответ на `Idempotency-Key` сохраняется. Повтор того же запроса (например, после таймаута) возвращает **тот же** ответ и
ничего не делает второй раз. Тот же ключ на другой путь — ошибка: ключ принадлежит операции, а не соединению.

Даже без ключа повтор действия безопасен по сути: переходы состояний охраняемые, и «отменить уже отменённое» или
«подтвердить уже завершённое» ничего не меняют. Ключ нужен, чтобы сайт мог отличить «не дошло» от «дошло, ответ
потерялся».

## Чего API не делает

- не выдаёт и не забирает предметы напрямую;
- не выполняет команды сервера;
- не отдаёт содержимое инвентарей;
- не создаёт лоты: предмет должен быть у игрока в руках, а значит, начало — только в игре.

Создание лота с сайта потребует «отложенной операции», которую игрок подтвердит в игре. Это возможно поверх нынешней
схемы (намерения уже есть), но в первой версии сознательно не делается.
