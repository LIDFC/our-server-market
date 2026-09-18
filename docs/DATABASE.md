# База данных

SQLite, файл `plugins/OurServerMarket/market.db`. Режим WAL, `synchronous=FULL`, `foreign_keys=ON` (проверяется при
открытии: этот PRAGMA действует на соединение и молча ничего не делает, если его не применили).

Версия схемы — в `PRAGMA user_version`. Миграции применяются шагами при запуске, каждая в своей транзакции; шаг,
который уже вышел в релиз, больше не редактируется — добавляется следующий.

## Таблицы

### `identities`
Кто есть кто. `uuid` — первичный ключ, `account_id` позволяет позже связать несколько UUID одного человека,
`frozen_reason` — пометка «трогать нельзя, разбирается администратор».

### `items`
Одна строка = один стак. `blob` — **прототип одного предмета** в NBT (Paper `serializeItemsAsBytes`), `amount` —
сколько их. Такое разделение делает деление стака арифметикой, а не операцией над NBT, а `sha256` даёт быстрый способ
сравнить два предмета, не распаковывая их. `data_version` пишется, чтобы заметить откат сервера на старую версию.

### `listings`, `listing_items`
Лот и его предметы. `role` = `OFFERED` (уходит в эскроу) или `WANTED` (просто описание того, что ищут).
`recipient_uuid` + `recipient_name_lower` — для подарков: при получении проверяются оба.

### `trades`, `trade_confirmations`, `trade_items`
Сделка и подтверждения сторон. Подтверждения — отдельные строки с `PRIMARY KEY (trade_id, party)`, а не два булевых
поля: так «подтвердить дважды» физически не может дать двойной эффект. `expected_escrow_count` фиксируется при создании
сделки и перепроверяется в момент завершения.

### `escrow_items`
Что рынок держит. `state` = `HELD` → `RELEASED`, плюс `released_to`, `released_tx`, `released_at` — во время разбора
инцидента важно не только «выдано», но и «кому и какой транзакцией».

### `pending_deliveries`
Очередь выдачи. `state` = `PENDING` → `CLAIMING` → `CLAIMED`. Ключевое ограничение:

```sql
source_escrow_item_id INTEGER UNIQUE REFERENCES escrow_items(id)
```

Даже если ошибка в коде выполнит завершение сделки дважды, вторая доставка из той же строки эскроу не создастся —
это запретит база, а не аккуратность программиста.

### `intents`
Намерения. Пишутся до того, как тронут инвентарь, и хранят отпечаток инвентаря (`pre_digest`) и версию данных.
Состояния: `INTENT` → `APPLIED` → `FINALIZED`, либо `ABORTED`, либо `MANUAL` (не смогли решить сами).

### `item_movements`
Журнал перемещений, только добавление:

```sql
CREATE TRIGGER item_movements_no_update BEFORE UPDATE ON item_movements
  BEGIN SELECT RAISE(ABORT, 'item_movements is append only'); END;
```

Держатели записываются строками: `PLAYER:<uuid>`, `LISTING:<id>`, `TRADE:<id>`, `PENDING:<id>`, `CONSUMED`. По журналу
проверяется главное утверждение всего плагина: каждая вещь в каждый момент лежит ровно в одном месте, и каждое
перемещение начинается там, где закончилось предыдущее. Тесты проверяют это после каждого сценария.

### `events`
Лог и одновременно лента для сайта (`GET /events?since=`). Тоже только добавление.

### `api_requests`
Ответы, уже выданные API по конкретному `Idempotency-Key`. Повтор запроса возвращает тот же ответ и ничего не делает
второй раз.

### `server_state`
Мелочи вроде отметки о чистой остановке сервера.

## Резервное копирование

```bash
sqlite3 market.db ".backup '/home/ubuntu/backups/market-$(date +%F).db'"
```

Обычное `cp` во время работы сервера может дать испорченный файл: данные лежат ещё и в WAL. Восстанавливать базу нужно
**вместе с миром** — откат мира при новой базе означает, что предметы, уже выданные игрокам, вернутся и в инвентари, и
в эскроу.

## Проверка целостности вручную

```sql
-- где сейчас каждая вещь
SELECT item_uid, to_holder FROM item_movements m
 WHERE id = (SELECT MAX(id) FROM item_movements WHERE item_uid = m.item_uid);

-- эскроу, зависший под завершённым лотом (обычно пусто: это чинится при старте)
SELECT e.id FROM escrow_items e JOIN listings l ON l.id = e.listing_id
 WHERE e.state = 'HELD' AND l.state IN ('COMPLETED','CANCELLED','EXPIRED');

-- незакрытые намерения
SELECT tx_id, op, player_uuid, state FROM intents WHERE state IN ('INTENT','APPLIED','MANUAL');
```
