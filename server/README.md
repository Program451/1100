# Senim server

Для запросов к API установите непустой `SENIM_API_TOKEN` в `.env` или окружении и передавайте заголовок `Authorization: Bearer <токен>`. `/health` доступен без токена. Это общий ключ демо, а не авторизация отдельных пользователей: владелец ключа может выполнять все операции API. Android-клиенту потребуется добавить этот заголовок. Не размещайте токен в Git и не включайте его в публичную сборку.

## Запуск

Требуется Python 3.11.

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
Copy-Item .env.example .env
uvicorn main:app --host 0.0.0.0 --port 8000
```

Проверка запуска:

```powershell
curl.exe http://127.0.0.1:8000/health
```

Ожидаемый ответ: `{"status":"ok"}`.

Проверка API шага 2 в PowerShell:

```powershell
curl.exe -X POST http://127.0.0.1:8000/demo/reset
curl.exe -X POST http://127.0.0.1:8000/devices/register -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\",\"fcm_token\":\"demo_token\",\"role\":\"user\"}'
curl.exe -X POST http://127.0.0.1:8000/trusted-contacts -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\",\"trusted_user_id\":\"user_son\"}'
curl.exe http://127.0.0.1:8000/trusted-contacts/user_mom
```

Проверка демобанка (после сброса; пример UUID используется один раз):

```powershell
curl.exe http://127.0.0.1:8000/accounts/user_mom
curl.exe -X POST http://127.0.0.1:8000/deposits/close -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\",\"amount\":300000}'
curl.exe -X POST http://127.0.0.1:8000/transfers -H 'Content-Type: application/json' -d '{\"transfer_id\":\"7f3b54e6-52da-4d5b-8e46-b9529c7b7a45\",\"from_user_id\":\"user_mom\",\"to_account\":\"acc_scammer_001\",\"amount\":100000,\"created_at\":\"2026-11-25T10:30:00Z\"}'
curl.exe -X POST http://127.0.0.1:8000/transfers/7f3b54e6-52da-4d5b-8e46-b9529c7b7a45/cancel
```

Получение тревог доверенным лицом:

```powershell
curl.exe 'http://127.0.0.1:8000/alerts?user_id=user_son&since=2026-01-01T00%3A00%3A00Z'
```

Для push установите зависимости и укажите в `.env` переменную `GOOGLE_APPLICATION_CREDENTIALS` с путём к JSON сервисного аккаунта Firebase. Без неё тревоги доступны через `/alerts`. Значение `FCM_SERVER_KEY` оставлено для совместимости с исходным примером, но для отправки не используется. В data-сообщении FCM отсутствующий `transfer_id` представлен пустой строкой.

Проверка ссылки:

```powershell
curl.exe -X POST http://127.0.0.1:8000/links/check -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\",\"url\":\"http://kaspi-bonus-verify.example/pay\",\"source\":\"demo_chat\",\"sender_key\":\"demo_sender\"}'
curl.exe -X POST http://127.0.0.1:8000/links/check -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\",\"url\":\"https://kaspi.kz\",\"source\":\"gatekeeper\"}'
```

Проверка и жалоба на хеш номера:

```powershell
curl.exe -X POST http://127.0.0.1:8000/numbers/check -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\",\"phone_hash\":\"44c5fc32459f3d78586f0610a3ebc18d7b198bd37b78c14f91489c5d8fa0a58e\",\"sender_kind\":\"phone\",\"channel\":\"call\",\"in_contacts\":false,\"pattern_flags\":[]}'
curl.exe -X POST http://127.0.0.1:8000/numbers/report -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\",\"phone_hash\":\"44c5fc32459f3d78586f0610a3ebc18d7b198bd37b78c14f91489c5d8fa0a58e\"}'
```

`official_mock` — вымышленная заглушка, не источник государственных данных; для её примеров хешируются строки `senim-official-mock-1` и `senim-official-mock-2`.

Выдача и проверка кода-приманки (замените пример кода на полученный):

```powershell
curl.exe -X POST http://127.0.0.1:8000/decoy/issue -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\"}'
curl.exe -X POST http://127.0.0.1:8000/decoy/use -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\",\"code\":\"123456\"}'
```

Новая выдача заменяет предыдущий код пользователя. Любая попытка использования создаёт отдельную тревогу и не подтверждает платёж.

Проверка файла по хешу и имени (содержимое файла не передаётся):

```powershell
curl.exe -X POST http://127.0.0.1:8000/files/check -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\",\"sha256\":\"e68fddcb1e212ef01ee09dec3b219405798ee8b25ad62764303afff606f2c75e\",\"file_name\":\"demo.txt\",\"size_bytes\":18,\"source_app\":\"demo\"}'
```

Неизвестный хеш без признаков риска получает `unknown`: отсутствие совпадения не означает, что файл безопасен.

Офлайн-расширение контракта: для устройства с журналом в `/devices/register` вместе передаются `device_id` и `public_key` (base64 от 32 сырых байт Ed25519). Идентификатор и ключ фиксируются до сброса демо. Доступный резерв ограничен `OFFLINE_LIMIT` (по умолчанию 50000 тенге); резерв снимается с баланса сразу, а синхронизация списывает из резерва и зачисляет получателю. Повторный резерв добавляется к оставшемуся. Возврат остатка резерва контрактом не предусмотрен.

```powershell
curl.exe -X POST http://127.0.0.1:8000/offline/reserve -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\",\"amount\":50000}'
curl.exe -X POST http://127.0.0.1:8000/offline/sync -H 'Content-Type: application/json' -d '{\"user_id\":\"user_mom\",\"device_id\":\"demo-device\",\"records\":[]}'
```

Для непустой синхронизации сначала зарегистрируйте настоящий ключ устройства и подпишите записи по контракту; тестовые ключи в проект не включены.

