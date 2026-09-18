# Подключение сайта

Документ для того, кто будет делать раздел «Рынок» на сайте `our-server-site`. Исходники плагина читать не нужно.

## Что уже есть на стороне сайта

У сайта есть аккаунты, и в таблице `users` уже хранится `minecraft_uuid` — он проставляется при первой синхронизации
скинов и сверяется с `usercache.json` самого сервера. Это и есть связь «аккаунт сайта → игрок Minecraft», больше
ничего заводить не нужно.

Если у аккаунта `minecraft_uuid` пуст, раздел рынка показывать нечего: попросите игрока зайти на сервер (тогда UUID
свяжется) или используйте тот же механизм сверки по `usercache.json`.

## Схема

```
браузер → бэкенд сайта (Node, та же машина) → 127.0.0.1:8788 → плагин → SQLite + инвентари
```

Браузер **никогда** не ходит в API рынка напрямую: токен не должен попадать во фронтенд. Бэкенд сайта авторизует
своего пользователя своей сессией, берёт из базы его `minecraft_uuid` и уже от себя зовёт рынок.

## 1. Переменные окружения сайта

```ini
MARKET_API_URL=http://127.0.0.1:8788/api/v1/market
MARKET_API_TOKEN=тот-же-токен-что-в-OUR_SERVER_MARKET_TOKEN
```

Токен — секрет: только в `.env` сайта, не в сборке фронтенда.

## 2. Аутентификация

Каждый запрос от бэкенда сайта:

```
Authorization: Bearer ${MARKET_API_TOKEN}
```

## 3. Личность игрока

В теле действий передаётся `minecraftUuid` — тот самый из `users.minecraft_uuid`. Никогда не берите UUID или ник из
запроса браузера: пользователь может подставить чужой. Правильно так:

```ts
const user = await accounts.current(sessionToken);      // сессия сайта
const uuid = accounts.minecraftUuidOf(user.id);          // из своей базы
if (!uuid) return { status: 409, error: "minecraft-not-linked" };
await market.cancelListing(listingId, uuid);             // и только теперь в API рынка
```

Плагин дополнительно проверит, что этот UUID действительно владелец лота, — то есть ошибка на стороне сайта не
превратится в чужой отменённый лот.

## 4. Примеры запросов

Список активных лотов:

```bash
curl -s -H "Authorization: Bearer $MARKET_API_TOKEN" \
  "$MARKET_API_URL/listings?limit=20&offset=0"
```

```json
{ "listings": [ {
  "id": 12, "type": "TRADE", "state": "ACTIVE", "ownerUuid": "…",
  "summary": "offers 16x diamond for 32x gold ingot",
  "offered": [ { "summary": "16x diamond", "amount": 16, "sha256": "…" } ],
  "wanted":  [ { "summary": "32x gold ingot", "amount": 32, "sha256": "…" } ],
  "createdAt": "2026-09-19T10:00:00Z"
} ] }
```

Лоты и сделки игрока:

```bash
curl -s -H "Authorization: Bearer $MARKET_API_TOKEN" "$MARKET_API_URL/players/$UUID/listings"
curl -s -H "Authorization: Bearer $MARKET_API_TOKEN" "$MARKET_API_URL/players/$UUID/trades"
curl -s -H "Authorization: Bearer $MARKET_API_TOKEN" "$MARKET_API_URL/players/$UUID/deliveries"
```

Действие (снять свой лот):

```bash
curl -s -X POST "$MARKET_API_URL/listings/12/cancel" \
  -H "Authorization: Bearer $MARKET_API_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: cancel-12-$(uuidgen)" \
  -d '{"minecraftUuid":"'"$UUID"'"}'
```

```json
{ "ok": true, "listingId": 12 }
```

Остальные действия: `POST /trades/{id}/accept`, `/decline`, `/confirm` — тем же телом и с тем же заголовком.

## 5. Ошибки

```json
{ "error": { "code": "NOT_OWNER", "message": "This listing is not yours" } }
```

Показывайте пользователю свой текст по `code`, а не `message` (он английский и для логов). Минимум, что стоит
перевести: `LISTING_NOT_FOUND`, `NOT_OWNER`, `NOT_PARTICIPANT`, `LISTING_ALREADY_TAKEN`, `TRADE_NOT_ACCEPTED`,
`RATE_LIMITED`. Полный список — [API.md](API.md).

`500` и сетевые ошибки: покажите «рынок недоступен» и повторите позже. Не повторяйте автоматически действие без того же
`Idempotency-Key`.

## 6. Идемпотентность

Для каждого действия генерируйте ключ и **сохраняйте его вместе с попыткой**: если ответ не дошёл, повторите запрос с
тем же ключом. Плагин вернёт тот же ответ и не выполнит действие второй раз.

Ключ принадлежит операции, а не сессии: один ключ на один путь. Тот же ключ для другого пути — ошибка.

## 7. Обновления в реальном времени

WebSocket не нужен. Лента событий пронумерована:

```bash
curl -s -H "Authorization: Bearer $MARKET_API_TOKEN" "$MARKET_API_URL/events?since=57&limit=100"
```

Храните последний `id` и опрашивайте раз в 10–30 секунд, пока страница рынка открыта. Типы событий перечислены в
[API.md](API.md); для интерфейса достаточно `LISTING_CREATED`, `LISTING_CANCELLED`, `LISTING_CLAIMED`,
`TRADE_CREATED`, `TRADE_COMPLETED`, `PENDING_DELIVERY_CREATED`.

## 8. Как может выглядеть раздел

```
/market                 список лотов: тип, что отдают, что хотят, автор, время
/market/[id]            лот целиком, кнопки действий для своих лотов
/market/mine            мои лоты, мои сделки, мои посылки
```

- Кнопка «Отменить» — только на своих лотах (сайт проверяет по `ownerUuid`, плагин проверит ещё раз).
- «Принять» / «Отклонить» — на своих сделках в состоянии `PENDING`, «Подтвердить» — в `ACCEPTED`.
- Посылки показывайте списком с пояснением: забрать их можно только в игре, командой `/market deliveries`. Сайт не
  выдаёт предметы — это принципиально.
- Создание лота с сайта не поддерживается: предмет нужно держать в руке. Ставьте на странице подсказку с командами.

## 9. Чего сайт не может

Двигать предметы, создавать лоты, выдавать посылки, выполнять команды сервера, читать инвентарь. Всё это делает
плагин, и он же решает, можно ли.

## 10. Проверка связи

```bash
curl -s -H "Authorization: Bearer $MARKET_API_TOKEN" "$MARKET_API_URL/health"   # {"ok":true,"schema":1}
curl -s -o /dev/null -w '%{http_code}\n' "$MARKET_API_URL/health"               # 401 без токена
```

Если первое не отвечает — плагин не поднял API (нет токена или порт занят), смотрите `logs/latest.log`.
