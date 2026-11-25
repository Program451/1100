from contextlib import asynccontextmanager
import asyncio
import secrets
import base64
import binascii
import hashlib
import os
import time
import uuid
from datetime import datetime, timezone
from typing import Literal
from urllib.parse import urlsplit
from fastapi import BackgroundTasks, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator
from config import settings
from database import database, initialize, reset
from push import send_alert
from reputation import LocalComplaintsProvider, MockOfficialProvider
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.exceptions import InvalidSignature

@asynccontextmanager
async def lifespan(app: FastAPI):
    initialize()
    task = asyncio.create_task(transfer_worker())
    try:
        yield
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

def finish_due(db):
    db.execute("UPDATE transfers SET status='completed' WHERE status IN ('pending', 'flagged') AND deadline <= ?", (time.time(),))

async def transfer_worker():
    while True:
        with database() as db:
            finish_due(db)
        await asyncio.sleep(1)

app = FastAPI(title='Senim API', version='1.0.0', lifespan=lifespan)

@app.middleware('http')
async def require_demo_token(request: Request, call_next):
    if request.url.path == '/health':
        return await call_next(request)
    expected = os.getenv('SENIM_API_TOKEN', '')
    header = request.headers.get('authorization', '')
    if not expected or not secrets.compare_digest(header, 'Bearer ' + expected):
        return JSONResponse(status_code=401, content={'error': {'code': 'UNAUTHORIZED', 'message': 'Требуется действующий ключ доступа'}})
    return await call_next(request)

def error(code, message, status):
    return JSONResponse(status_code=status, content={'error': {'code': code, 'message': message}})

@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError):
    return error('VALIDATION_ERROR', 'Некорректный запрос', 400)

class Device(BaseModel):
    user_id: str = Field(min_length=1)
    fcm_token: str = Field(min_length=1)
    role: Literal['user', 'trusted']
    public_key: str | None = None
    device_id: str | None = Field(default=None, min_length=1, max_length=128)

    @field_validator('public_key')
    @classmethod
    def valid_key(cls, value):
        if value is not None:
            try:
                Ed25519PublicKey.from_public_bytes(base64.b64decode(value, validate=True))
            except (ValueError, binascii.Error):
                raise ValueError('Требуется открытый ключ Ed25519 в base64')
        return value

class OfflineReserve(BaseModel):
    user_id: str = Field(min_length=1)
    amount: int = Field(gt=0, strict=True)

class OfflineSync(BaseModel):
    user_id: str = Field(min_length=1)
    device_id: str = Field(min_length=1, max_length=128)
    records: list[dict]

class OfflineRecord(BaseModel):
    record_id: str
    device_id: str = Field(min_length=1, max_length=128)
    counter: int = Field(gt=0, strict=True)
    prev_hash: str = Field(pattern=r'^[0-9a-f]{64}$')
    from_user_id: str = Field(min_length=1)
    to_user_id: str = Field(min_length=1)
    amount: int = Field(gt=0, strict=True)
    created_at: str
    signature: str = Field(min_length=1)

    @field_validator('device_id', 'from_user_id', 'to_user_id')
    @classmethod
    def no_separator(cls, value):
        if '|' in value or '\n' in value or '\r' in value:
            raise ValueError('Недопустимый символ')
        return value

    @field_validator('record_id')
    @classmethod
    def validate_uuid(cls, value):
        parsed = uuid.UUID(value)
        if parsed.version != 4 or str(parsed) != value:
            raise ValueError('Требуется UUID v4')
        return value

    @field_validator('created_at')
    @classmethod
    def validate_date(cls, value):
        if not value.endswith('Z') or datetime.fromisoformat(value.replace('Z', '+00:00')).utcoffset() is None:
            raise ValueError('Требуется ISO 8601 UTC')
        return value

class TrustedContact(BaseModel):
    user_id: str = Field(min_length=1)
    trusted_user_id: str = Field(min_length=1)

class DecoyIssue(BaseModel):
    user_id: str = Field(min_length=1)

class DecoyUse(DecoyIssue):
    code: str = Field(min_length=1, max_length=128)

class LinkCheck(BaseModel):
    user_id: str = Field(min_length=1)
    url: str = Field(min_length=1, max_length=4096)
    source: Literal['notification', 'telegram_bot', 'demo_chat', 'gatekeeper']
    sender_key: str | None = Field(default=None, min_length=1, max_length=256)

    @field_validator('url')
    @classmethod
    def valid_url(cls, value):
        try:
            parts = urlsplit(value)
            host = parts.hostname
            port = parts.port
            if (parts.scheme not in ('http', 'https') or not host or parts.username is not None
                or parts.password is not None or '?' in value or '#' in value
                or any(ch.isspace() or ord(ch) < 32 for ch in value)
                or host != host.lower() or parts.netloc != host + (f':{port}' if port else '')):
                raise ValueError()
        except ValueError:
            raise ValueError('Требуется нормализованная ссылка HTTP(S) без query и fragment')
        return value

class DepositClose(BaseModel):
    user_id: str = Field(min_length=1)
    amount: int = Field(gt=0, strict=True)

class NumberReport(BaseModel):
    user_id: str = Field(min_length=1)
    phone_hash: str = Field(pattern=r'^[0-9a-f]{64}$')

class FileCheck(BaseModel):
    user_id: str = Field(min_length=1)
    sha256: str = Field(pattern=r'^[0-9a-f]{64}$')
    file_name: str = Field(min_length=1, max_length=255)
    size_bytes: int = Field(ge=0, strict=True)
    source_app: str = Field(min_length=1, max_length=255)

class NumberCheck(NumberReport):
    sender_kind: Literal['phone', 'telegram'] = 'phone'
    channel: Literal['message', 'call', 'call_transcript'] = 'message'
    in_contacts: bool = Field(strict=True)
    pattern_flags: list[Literal['family_claim_new_number', 'urgent_money_request', 'asks_code', 'asks_screen_share']] = Field(default_factory=list)
    local_score: float | None = Field(default=None, ge=0, le=1, allow_inf_nan=False)

class Transfer(BaseModel):
    transfer_id: str
    from_user_id: str = Field(min_length=1)
    to_account: str = Field(min_length=1)
    amount: int = Field(gt=0, strict=True)
    created_at: str

    @field_validator('transfer_id')
    @classmethod
    def valid_id(cls, value):
        try:
            parsed = uuid.UUID(value)
            if parsed.version != 4 or str(parsed) != value:
                raise ValueError()
        except ValueError:
            raise ValueError('Требуется UUID v4 в нижнем регистре')
        return value

    @field_validator('created_at')
    @classmethod
    def valid_time(cls, value):
        try:
            parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
            if not value.endswith('Z') or parsed.utcoffset() is None:
                raise ValueError()
        except ValueError:
            raise ValueError('Требуется время ISO 8601 UTC с суффиксом Z')
        return value

@app.get('/health', include_in_schema=False)
def health():
    return {'status': 'ok'}

@app.post('/demo/reset')
def demo_reset():
    reset()
    return {'status': 'ok'}

@app.post('/devices/register')
def register_device(device: Device):
    if (device.public_key is None) != (device.device_id is None):
        return error('VALIDATION_ERROR', 'device_id и public_key передаются вместе', 400)
    with database() as db:
        db.execute('BEGIN IMMEDIATE')
        if not db.execute('SELECT 1 FROM users WHERE user_id = ?', (device.user_id,)).fetchone():
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        if device.device_id is not None:
            existing = db.execute('SELECT user_id, public_key FROM offline_devices WHERE device_id=?', (device.device_id,)).fetchone()
            if existing and existing != (device.user_id, device.public_key):
                return error('INVALID_STATE', 'Устройство уже связано с другим ключом или пользователем', 409)
            db.execute('INSERT OR IGNORE INTO offline_devices (device_id, user_id, public_key, last_hash) VALUES (?, ?, ?, ?)', (device.device_id, device.user_id, device.public_key, '0'*64))
        db.execute('INSERT INTO devices VALUES (?, ?, ?, ?) ON CONFLICT(fcm_token) DO UPDATE SET user_id=excluded.user_id, role=excluded.role, public_key=excluded.public_key', (device.fcm_token, device.user_id, device.role, device.public_key))
    return {'status': 'registered'}

@app.post('/offline/reserve')
def offline_reserve(item: OfflineReserve):
    with database() as db:
        db.execute('BEGIN IMMEDIATE')
        user = db.execute('SELECT balance FROM users WHERE user_id=?', (item.user_id,)).fetchone()
        if not user:
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        row = db.execute('SELECT remaining FROM offline_reserves WHERE user_id=?', (item.user_id,)).fetchone()
        current = row[0] if row else 0
        if current + item.amount > settings.offline_limit or item.amount > user[0]:
            return error('INVALID_STATE', 'Недостаточно средств или превышен лимит резерва', 409)
        db.execute('UPDATE users SET balance=balance-? WHERE user_id=?', (item.amount, item.user_id))
        db.execute('INSERT INTO offline_reserves VALUES (?, ?) ON CONFLICT(user_id) DO UPDATE SET remaining=excluded.remaining', (item.user_id, current+item.amount))
    return {'reserved': current+item.amount, 'limit': settings.offline_limit}

@app.post('/offline/sync')
def offline_sync(item: OfflineSync):
    accepted, rejected = [], []
    with database() as db:
        db.execute('BEGIN IMMEDIATE')
        device = db.execute('SELECT user_id, public_key, counter, last_hash FROM offline_devices WHERE device_id=?', (item.device_id,)).fetchone()
        counter, last_hash = (device[2], device[3]) if device else (0, '0'*64)
        for raw in item.records:
            record_id = raw.get('record_id')
            code = None
            try:
                record = OfflineRecord.model_validate(raw)
            except Exception:
                rejected.append({'record_id': record_id, 'code': 'VALIDATION_ERROR'})
                continue
            if db.execute('SELECT 1 FROM offline_records WHERE record_id=?', (record.record_id,)).fetchone() or record.counter <= counter:
                code = 'REPLAY'
            elif not device or device[0] != item.user_id or record.device_id != item.device_id or record.from_user_id != item.user_id:
                code = 'INVALID_SIGNATURE'
            else:
                parts = (record.record_id, record.device_id, str(record.counter), record.prev_hash, record.from_user_id, record.to_user_id, str(record.amount), record.created_at)
                signed = '|'.join(parts)
                try:
                    Ed25519PublicKey.from_public_bytes(base64.b64decode(device[1], validate=True)).verify(base64.b64decode(record.signature, validate=True), signed.encode('utf-8'))
                except (ValueError, binascii.Error, InvalidSignature):
                    code = 'INVALID_SIGNATURE'
                if code is None and (record.counter != counter+1 or record.prev_hash != last_hash):
                    code = 'CHAIN_BROKEN'
                reserve = db.execute('SELECT remaining FROM offline_reserves WHERE user_id=?', (item.user_id,)).fetchone()
                if code is None and (not reserve or reserve[0] < record.amount):
                    code = 'LIMIT_EXCEEDED'
                if code is None and (record.to_user_id == item.user_id or not db.execute('SELECT 1 FROM users WHERE user_id=?', (record.to_user_id,)).fetchone()):
                    code = 'VALIDATION_ERROR'
            if code:
                rejected.append({'record_id': record.record_id, 'code': code})
                continue
            last_hash = hashlib.sha256((signed + '|' + record.signature).encode('utf-8')).hexdigest()
            counter = record.counter
            db.execute('UPDATE offline_reserves SET remaining=remaining-? WHERE user_id=?', (record.amount, item.user_id))
            db.execute('UPDATE users SET balance=balance+? WHERE user_id=?', (record.amount, record.to_user_id))
            db.execute('INSERT INTO offline_records VALUES (?, ?, ?)', (record.record_id, item.device_id, last_hash))
            db.execute('UPDATE offline_devices SET counter=?, last_hash=? WHERE device_id=?', (counter, last_hash, item.device_id))
            accepted.append(record.record_id)
    return {'accepted': accepted, 'rejected': rejected}

@app.post('/links/check')
def check_link(item: LinkCheck):
    parts = urlsplit(item.url)
    host = parts.hostname
    reasons = []
    shorteners = ('bit.ly', 'tinyurl.com', 't.co', 'goo.gl', 'is.gd')
    if any(host == domain or host.endswith('.' + domain) for domain in shorteners):
        reasons.append('сокращённая ссылка')
    if host == 'kaspi-bonus-verify.example':
        reasons.append('тестовый опасный домен')
    if any(word in (host + parts.path).lower() for word in ('verify', 'bonus')):
        reasons.append('подозрительные слова в адресе')
    if 'kaspi' in host and host != 'kaspi.kz' and not host.endswith('.kaspi.kz'):
        reasons.append('возможная подделка домена банка')
    with database() as db:
        if not db.execute('SELECT 1 FROM users WHERE user_id=?', (item.user_id,)).fetchone():
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        if reasons:
            db.execute('INSERT INTO risk_signals (user_id, signal_type, occurred_at, sender_key) VALUES (?, ?, ?, ?)', (item.user_id, 'link_high', time.time(), item.sender_key))
    return {'risk': 'high' if reasons else 'low', 'reasons': reasons}

@app.post('/numbers/report')
def report_number(item: NumberReport):
    with database() as db:
        if not db.execute('SELECT 1 FROM users WHERE user_id=?', (item.user_id,)).fetchone():
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        db.execute('INSERT OR IGNORE INTO complaints (phone_hash) VALUES (?)', (item.phone_hash,))
    return {'status': 'ok'}

@app.post('/files/check')
def check_file(item: FileCheck):
    reasons = []
    name = item.file_name.casefold()
    if name.endswith('.apk'):
        reasons.append('файл установки Android (.apk)')
        if '.' in name[:-4].lstrip('.'):
            reasons.append('двойное расширение')
    with database() as db:
        if not db.execute('SELECT 1 FROM users WHERE user_id=?', (item.user_id,)).fetchone():
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        if db.execute('SELECT 1 FROM file_hashes WHERE sha256=?', (item.sha256,)).fetchone():
            reasons.append('хеш найден в демонстрационной базе опасных файлов')
        if reasons:
            db.execute('INSERT INTO risk_signals (user_id, signal_type, occurred_at) VALUES (?, ?, ?)', (item.user_id, 'file_suspicious', time.time()))
    return {'verdict': 'suspicious' if reasons else 'unknown', 'reasons': reasons}

@app.post('/decoy/issue')
def issue_decoy(item: DecoyIssue):
    code = f'{secrets.randbelow(1000000):06d}'
    expiry = time.time() + settings.decoy_ttl_seconds
    with database() as db:
        if not db.execute('SELECT 1 FROM users WHERE user_id=?', (item.user_id,)).fetchone():
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        db.execute('INSERT INTO decoy_codes (user_id, code, expires_at, used) VALUES (?, ?, ?, 0) ON CONFLICT(user_id) DO UPDATE SET code=excluded.code, expires_at=excluded.expires_at, used=0', (item.user_id, code, expiry))
    return {'decoy_code': code, 'expires_at': datetime.fromtimestamp(expiry, timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')}

@app.post('/decoy/use')
def use_decoy(item: DecoyUse, background_tasks: BackgroundTasks):
    with database() as db:
        db.execute('BEGIN IMMEDIATE')
        user = db.execute('SELECT name FROM users WHERE user_id=?', (item.user_id,)).fetchone()
        if not user:
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        now = time.time()
        # Только действующий совпадающий код помечаем использованным; тревога создаётся при любой попытке.
        db.execute('UPDATE decoy_codes SET used=1 WHERE user_id=? AND code=? AND used=0 AND expires_at>?', (item.user_id, item.code, now))
        alert = {'alert_id': str(uuid.uuid4()), 'alert_type': 'decoy_used', 'about_user_id': item.user_id, 'about_user_name': user[0], 'transfer_id': None, 'message': 'Возможно, вашего близкого пытаются обмануть. Позвоните сейчас', 'created_at': datetime.fromtimestamp(now, timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')}
        db.execute('INSERT INTO alerts VALUES (?, ?, ?, ?, ?, ?, ?)', tuple(alert.values()))
        db.execute('INSERT INTO alert_recipients SELECT ?, trusted_user_id FROM trusted_contacts WHERE user_id=?', (alert['alert_id'], item.user_id))
        tokens = [r[0] for r in db.execute("SELECT d.fcm_token FROM devices d JOIN alert_recipients r ON d.user_id=r.trusted_user_id WHERE r.alert_id=? AND d.role='trusted'", (alert['alert_id'],))]
    background_tasks.add_task(send_alert, alert, tokens)
    return {'valid': False, 'alert_raised': True}

@app.post('/numbers/check')
def check_number(item: NumberCheck, background_tasks: BackgroundTasks):
    alert = None
    tokens = []
    with database() as db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('SELECT name FROM users WHERE user_id=?', (item.user_id,)).fetchone()
        if not row:
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        complaints = LocalComplaintsProvider(db).contains(item.phone_hash)
        mock = MockOfficialProvider().contains(item.phone_hash)
        sources = (['complaints'] if complaints else []) + (['official_mock'] if mock else [])
        reasons = []
        if complaints:
            reasons.append('хеш найден в базе жалоб')
        if mock:
            reasons.append('совпадение с демонстрационной заглушкой official_mock')
        if not item.in_contacts and item.pattern_flags:
            reasons.append('подозрительные признаки у неизвестного отправителя')
        if not item.in_contacts and item.local_score is not None and item.local_score >= 0.7:
            reasons.append('высокая оценка локальной модели')
        now = time.time()
        if reasons:
            db.execute('INSERT INTO risk_signals (user_id, signal_type, occurred_at, sender_key) VALUES (?, ?, ?, ?)', (item.user_id, 'number_high', now, item.phone_hash))
        call_alert = (item.channel == 'call' and complaints) or (item.channel == 'call_transcript' and item.local_score is not None and item.local_score >= settings.call_alert_score)
        if call_alert:
            alert = {'alert_id': str(uuid.uuid4()), 'alert_type': 'suspicious_call', 'about_user_id': item.user_id, 'about_user_name': row[0], 'transfer_id': None, 'message': 'Возможно, вашему близкому сейчас звонит мошенник. Напишите ему или перезвоните после разговора', 'created_at': datetime.fromtimestamp(now, timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')}
            db.execute('INSERT INTO alerts VALUES (?, ?, ?, ?, ?, ?, ?)', tuple(alert.values()))
            db.execute('INSERT INTO alert_recipients SELECT ?, trusted_user_id FROM trusted_contacts WHERE user_id=?', (alert['alert_id'], item.user_id))
            tokens = [r[0] for r in db.execute("SELECT d.fcm_token FROM devices d JOIN alert_recipients r ON d.user_id=r.trusted_user_id WHERE r.alert_id=? AND d.role='trusted'", (alert['alert_id'],))]
    if alert:
        background_tasks.add_task(send_alert, alert, tokens)
    return {'risk': 'high' if reasons else 'low', 'reasons': reasons, 'sources': sources}

@app.post('/trusted-contacts')
def add_trusted_contact(contact: TrustedContact):
    with database() as db:
        for user_id in (contact.user_id, contact.trusted_user_id):
            if not db.execute('SELECT 1 FROM users WHERE user_id = ?', (user_id,)).fetchone():
                return error('NOT_FOUND', 'Пользователь не найден', 404)
        db.execute('INSERT OR IGNORE INTO trusted_contacts VALUES (?, ?)', (contact.user_id, contact.trusted_user_id))
    return {'status': 'ok'}

@app.get('/trusted-contacts/{user_id}')
def get_trusted_contacts(user_id: str):
    with database() as db:
        if not db.execute('SELECT 1 FROM users WHERE user_id = ?', (user_id,)).fetchone():
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        ids = [row[0] for row in db.execute('SELECT trusted_user_id FROM trusted_contacts WHERE user_id = ? ORDER BY trusted_user_id', (user_id,))]
    return {'user_id': user_id, 'trusted_user_ids': ids}

@app.get('/accounts/{user_id}')
def account(user_id: str):
    with database() as db:
        row = db.execute('SELECT balance, deposit_balance FROM users WHERE user_id=?', (user_id,)).fetchone()
        if row is None:
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        return {'user_id': user_id, 'balance': row[0], 'deposit_balance': row[1]}

@app.post('/deposits/close')
def close_deposit(item: DepositClose):
    with database() as db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('SELECT deposit_balance FROM users WHERE user_id=?', (item.user_id,)).fetchone()
        if row is None:
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        if item.amount > row[0]:
            return error('INVALID_STATE', 'Недостаточно средств на депозите', 409)
        db.execute('UPDATE users SET balance=balance+?, deposit_balance=deposit_balance-? WHERE user_id=?', (item.amount, item.amount, item.user_id))
        db.execute('INSERT INTO deposits_closed VALUES (?, ?, ?)', (item.user_id, item.amount, datetime.now(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')))
    return {'status': 'closed'}

@app.post('/transfers', status_code=201)
def create_transfer(item: Transfer, background_tasks: BackgroundTasks):
    alert = None
    tokens = []
    with database() as db:
        db.execute('BEGIN IMMEDIATE')
        finish_due(db)
        if db.execute('SELECT 1 FROM transfers WHERE transfer_id=?', (item.transfer_id,)).fetchone():
            return error('INVALID_STATE', 'Перевод с таким ID уже существует', 409)
        row = db.execute('SELECT balance FROM users WHERE user_id=?', (item.from_user_id,)).fetchone()
        if row is None:
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        if item.amount > row[0]:
            return error('INVALID_STATE', 'Недостаточно средств', 409)
        now = time.time()
        cutoff = datetime.fromtimestamp(now - settings.risk_window_minutes * 60, timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')
        new_recipient = not db.execute("SELECT 1 FROM transfers WHERE from_user_id=? AND to_account=? AND status='completed'", (item.from_user_id, item.to_account)).fetchone()
        closed = db.execute('SELECT 1 FROM deposits_closed WHERE user_id=? AND closed_at>=? LIMIT 1', (item.from_user_id, cutoff)).fetchone()
        signal = db.execute("SELECT 1 FROM risk_signals WHERE user_id=? AND signal_type IN ('link_high','number_high','file_suspicious') AND occurred_at BETWEEN ? AND ? LIMIT 1", (item.from_user_id, now-settings.risk_window_minutes*60, now)).fetchone()
        threshold = settings.risk_amount_threshold_with_signal if signal else settings.risk_amount_threshold
        flagged = bool(new_recipient and closed and item.amount >= threshold)
        status = 'flagged' if flagged else 'pending'
        db.execute('UPDATE users SET balance=balance-? WHERE user_id=?', (item.amount, item.from_user_id))
        db.execute('INSERT INTO transfers VALUES (?, ?, ?, ?, ?, ?, ?)', (item.transfer_id, item.from_user_id, item.to_account, item.amount, item.created_at, now+settings.transfer_pending_seconds, status))
        if flagged:
            name = db.execute('SELECT name FROM users WHERE user_id=?', (item.from_user_id,)).fetchone()[0]
            alert = {'alert_id': str(uuid.uuid4()), 'alert_type': 'new_recipient', 'about_user_id': item.from_user_id, 'about_user_name': name, 'transfer_id': item.transfer_id, 'message': 'Возможно, вашего близкого пытаются обмануть. Позвоните сейчас', 'created_at': datetime.fromtimestamp(now, timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')}
            db.execute('INSERT INTO alerts VALUES (?, ?, ?, ?, ?, ?, ?)', tuple(alert.values()))
            db.execute('INSERT INTO alert_recipients SELECT ?, trusted_user_id FROM trusted_contacts WHERE user_id=?', (alert['alert_id'], item.from_user_id))
            tokens = [r[0] for r in db.execute("SELECT d.fcm_token FROM devices d JOIN alert_recipients r ON d.user_id=r.trusted_user_id WHERE r.alert_id=? AND d.role='trusted'", (alert['alert_id'],))]
    if alert:
        background_tasks.add_task(send_alert, alert, tokens)
    return {'transfer_id': item.transfer_id, 'status': status}

@app.get('/alerts')
def get_alerts(user_id: str, since: str):
    try:
        dt = datetime.fromisoformat(since.replace('Z', '+00:00'))
        if dt.utcoffset() is None:
            raise ValueError()
        since = dt.astimezone(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')
    except ValueError:
        return error('VALIDATION_ERROR', 'Некорректное время since', 400)
    with database() as db:
        if not db.execute('SELECT 1 FROM users WHERE user_id=?', (user_id,)).fetchone():
            return error('NOT_FOUND', 'Пользователь не найден', 404)
        rows = db.execute('SELECT a.alert_id,a.alert_type,a.about_user_id,a.about_user_name,a.transfer_id,a.message,a.created_at FROM alerts a JOIN alert_recipients r ON a.alert_id=r.alert_id WHERE r.trusted_user_id=? AND a.created_at>? ORDER BY a.created_at,a.alert_id', (user_id, since)).fetchall()
    keys = ('alert_id','alert_type','about_user_id','about_user_name','transfer_id','message','created_at')
    return {'alerts': [dict(zip(keys, row)) for row in rows]}

@app.post('/transfers/{transfer_id}/cancel')
def cancel_transfer(transfer_id: str):
    with database() as db:
        db.execute('BEGIN IMMEDIATE')
        finish_due(db)
        row = db.execute('SELECT from_user_id, amount, status FROM transfers WHERE transfer_id=?', (transfer_id,)).fetchone()
        if row is None:
            return error('NOT_FOUND', 'Перевод не найден', 404)
        if row[2] not in ('pending', 'flagged'):
            return error('INVALID_STATE', 'Перевод нельзя отменить', 409)
        db.execute("UPDATE transfers SET status='cancelled' WHERE transfer_id=?", (transfer_id,))
        db.execute('UPDATE users SET balance=balance+? WHERE user_id=?', (row[1], row[0]))
    return {'transfer_id': transfer_id, 'status': 'cancelled'}

if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host=settings.host, port=settings.port)
