# Приём отчётов Сторожа

Копия приёмника отчётов Voyah FM: свой путь, свои ключи, своя папка, отдельная служба. Принимает только ZIP с `environment.txt`, `diagnostics.txt`, `events.log`, `events.previous.log`.

- `PUT /voyah-guard/v1/reports/<sha256>`: заголовки `Authorization: Bearer <код тестера>`, `Content-Type: application/zip`, `X-Guard-Source: headunit`, `X-Guard-Version`, `X-Guard-Report-Name: Voyah-Guard-report-yyyyMMdd-HHmmss.zip`.
- Ответ 201 при первой загрузке, 200 при повторе тех же байтов: `ok`, `id` (`<тестер>/<sha256>`), `sha256`, `bytes`. Отчёт хранится как `<тестер>/<sha256>.zip`, рядом `.json` с `app: voyah-guard`, версией и временем приёма.
- Чтения, списка, удаления и команд нет. Nginx проверяет код до чтения тела запроса, ограничивает частоту, соединения и размер. Waitress слушает только loopback (порт 18090), служба работает от отдельного пользователя без root.
- ZIP не распаковывается на диск: каталог архива, фактический распакованный размер и CRC проверяются заранее, ZIP64 отклоняется. До 16 MiB на отчёт, общая квота задаётся в `clients.json`.

Код тестера: 8 случайных символов Crockford base32 (40 бит), без учёта регистра. На сервере хранятся только SHA-256 кодов. Перебор ограничен двумя лимитами nginx: на адрес и общий на все адреса (30 запросов в минуту). Адрес приёмника задаётся при сборке (`guard.upload.endpoint` в ignored `guard-upload.properties` или `VOYAH_GUARD_UPLOAD_ENDPOINT`); без него сборка не отправляет логи. Коды и адрес в репозиторий не входят.

Проверка: `python upload/test_receiver.py` (нужен waitress только для `main`, тесты его не требуют).
