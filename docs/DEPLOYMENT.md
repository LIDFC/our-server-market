# Установка на VPS

Сервер: Ubuntu 24.04, Paper 1.21.11, Java 21, каталог `/home/ubuntu/minecraft`, запуск через `screen` (не systemd).

## 1. Собрать jar

Локально (нужен JDK 21 и Maven):

```bash
mvn -B verify
```

Файл появится в `target/OurServerMarket-1.0.0.jar`.

Без установленной Java: каждый пуш собирается в GitHub Actions. Вкладка Actions → последняя сборка → Artifacts →
`OurServerMarket` → распаковать.

## 2. Скопировать на сервер

С Windows (PowerShell, из папки со скачанным jar):

```powershell
scp OurServerMarket-1.0.0.jar ubuntu@158.160.23.67:/home/ubuntu/
```

## 3. Остановить сервер Minecraft

Посмотреть, что за сессия screen:

```bash
screen -ls
```

Подключиться и остановить сервер изнутри консоли:

```bash
screen -r minecraft
```

В консоли сервера:

```
save-all
stop
```

Дождитесь, пока в консоли появится строка об остановке и screen вернёт вас в оболочку (или сессия закроется). **Не
закрывайте сервер через `kill`**: незавершённая остановка означает, что плагин не успел отметить чистое выключение, и
при следующем старте он проведёт полную проверку.

Отключиться от screen, не останавливая сервер: `Ctrl+A`, затем `D`.

## 4. Положить плагин

```bash
cp /home/ubuntu/OurServerMarket-1.0.0.jar /home/ubuntu/minecraft/plugins/
ls -l /home/ubuntu/minecraft/plugins/ | grep -i market
```

## 5. Запустить сервер

Так, как он запускается у вас (обычно скрипт `start.sh`):

```bash
cd /home/ubuntu/minecraft
screen -S minecraft -dm ./start.sh
screen -r minecraft
```

## 6. Проверить, что плагин поднялся

В консоли сервера:

```
plugins
```

`OurServerMarket` должен быть зелёным. В логе (`logs/latest.log`) ищите:

```
[OurServerMarket] Database ready at market.db, schema version 1
[OurServerMarket] AuthMe found: items are only handed over after a player has logged in.
[OurServerMarket] OurServerMarket is ready.
```

Если Paper пишет, что не смог скачать библиотеку `org.xerial:sqlite-jdbc` — у сервера нет доступа в интернет к Maven
Central. Тогда скачайте jar драйвера вручную и положите в `/home/ubuntu/minecraft/libraries/` (путь Paper печатает в
логе).

## 7. База

```bash
ls -l /home/ubuntu/minecraft/plugins/OurServerMarket/
```

Должны появиться `config.yml`, `market.db`, рядом `market.db-wal` и `market.db-shm` — это нормально.

## 8. Токен API

```bash
openssl rand -hex 32
```

Лучший способ — переменная окружения: добавьте в начало `start.sh`, **до** строки с `java`:

```bash
export OUR_SERVER_MARKET_TOKEN=вставьте-токен
```

Либо, если так проще, в `plugins/OurServerMarket/config.yml`:

```yaml
api:
  token: "вставьте-токен"
```

## 9. Адрес и порт API

По умолчанию `127.0.0.1:8788`. Менять `bind` на `0.0.0.0` не нужно и опасно: бэкенд сайта живёт на этой же машине.
После правки конфига — перезапуск сервера (шаги 3 и 5).

## 10. Перезапустить и проверить API

```bash
curl -s -H "Authorization: Bearer $OUR_SERVER_MARKET_TOKEN" http://127.0.0.1:8788/api/v1/market/health
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8788/api/v1/market/health
```

Первая команда вернёт `{"ok":true,"schema":1}`, вторая — `401` (без токена нельзя). Проверьте, что порт не смотрит
наружу:

```bash
ss -tlnp | grep 8788
```

Должен быть только `127.0.0.1:8788`.

## 11–16. Проверка в игре

1. `/market` — появляется справка.
2. Возьмите в руку стак, `/market create giveaway`, `/market add`, `/market publish` — предмет исчезает из инвентаря,
   `/market browse` показывает лот.
3. `/market cancel <лот>` — лот снят, `/market deliveries` возвращает предмет.
4. Обмен: первый игрок `create trade` → `add` → `want` → `publish`; второй берёт нужный предмет в руку и делает
   `/market offer <лот>`; первый — `/market accept <сделка>`; оба — `/market confirm <сделка>`; после этого оба делают
   `/market deliveries`.
5. Полный инвентарь: заполните всё, повторите выдачу — предметы остаются в очереди, ничего не падает на землю.

## 17. Проверка восстановления

Самый честный тест: во время сделки **жёстко** прервать сервер (`kill -9` процесса java), затем запустить снова.

```bash
pgrep -af java
kill -9 <pid>
cd /home/ubuntu/minecraft && screen -S minecraft -dm ./start.sh
```

В логе после старта:

```
[OurServerMarket] The last run of the server did not stop cleanly; checking the marketplace.
[OurServerMarket] Recovery: N stack(s) returned to their owners, M handover(s) waiting for their player to log in.
```

Зайдите в игру теми же игроками: то, что «ждало входа», разберётся по отпечатку инвентаря. Проверьте `/market
deliveries` и количество предметов — их должно быть ровно столько же, сколько до падения.

## Обновление плагина

1. Соберите новый jar.
2. Остановите сервер (шаг 3) — обязательно через `stop`, чтобы плагин отметил чистое выключение.
3. Замените jar в `plugins/`.
4. Запустите сервер, посмотрите строку `schema version` в логе.

## Откат

```bash
cd /home/ubuntu/minecraft
screen -r minecraft     # внутри: save-all, stop
cp /home/ubuntu/backups/OurServerMarket-предыдущая.jar plugins/OurServerMarket-1.0.0.jar
screen -S minecraft -dm ./start.sh
```

Если новая версия успела применить миграцию схемы, старый jar её не поймёт — тогда нужна и база из резервной копии:

```bash
cp /home/ubuntu/backups/market-2026-09-19.db plugins/OurServerMarket/market.db
rm -f plugins/OurServerMarket/market.db-wal plugins/OurServerMarket/market.db-shm
```

Восстанавливать базу в отрыве от мира нельзя: расхождение между `playerdata` и базой — это и есть дюп.

## Резервные копии

```bash
mkdir -p /home/ubuntu/backups
sqlite3 /home/ubuntu/minecraft/plugins/OurServerMarket/market.db \
  ".backup '/home/ubuntu/backups/market-$(date +%F).db'"
```

Ежедневно через cron (`crontab -e`), рядом с бэкапом мира:

```
20 4 * * * sqlite3 /home/ubuntu/minecraft/plugins/OurServerMarket/market.db ".backup '/home/ubuntu/backups/market-$(date +\%F).db'" && find /home/ubuntu/backups -name 'market-*.db' -mtime +14 -delete
```
