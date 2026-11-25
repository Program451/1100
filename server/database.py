import sqlite3
from contextlib import contextmanager
from config import settings

@contextmanager
def database():
    db = sqlite3.connect(settings.database_path)
    db.execute('PRAGMA foreign_keys = ON')
    try:
        with db:
            yield db
    finally:
        db.close()

def initialize():
    with database() as db:
        db.executescript('''
        CREATE TABLE IF NOT EXISTS users (user_id TEXT PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL, deposit_balance INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS devices (fcm_token TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(user_id), role TEXT NOT NULL, public_key TEXT);
        CREATE TABLE IF NOT EXISTS trusted_contacts (user_id TEXT NOT NULL REFERENCES users(user_id), trusted_user_id TEXT NOT NULL REFERENCES users(user_id), PRIMARY KEY (user_id, trusted_user_id));
        CREATE TABLE IF NOT EXISTS complaints (phone_hash TEXT PRIMARY KEY);
        CREATE TABLE IF NOT EXISTS file_hashes (sha256 TEXT PRIMARY KEY);
        CREATE TABLE IF NOT EXISTS demo_state (initialized INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS deposits_closed (user_id TEXT NOT NULL REFERENCES users(user_id), amount INTEGER NOT NULL, closed_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS transfers (transfer_id TEXT PRIMARY KEY, from_user_id TEXT NOT NULL REFERENCES users(user_id), to_account TEXT NOT NULL, amount INTEGER NOT NULL, created_at TEXT NOT NULL, deadline REAL NOT NULL, status TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS risk_signals (user_id TEXT NOT NULL REFERENCES users(user_id), signal_type TEXT NOT NULL, occurred_at REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS alerts (alert_id TEXT PRIMARY KEY, alert_type TEXT NOT NULL, about_user_id TEXT NOT NULL, about_user_name TEXT NOT NULL, transfer_id TEXT, message TEXT NOT NULL, created_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS alert_recipients (alert_id TEXT NOT NULL REFERENCES alerts(alert_id), trusted_user_id TEXT NOT NULL REFERENCES users(user_id), PRIMARY KEY(alert_id, trusted_user_id));
        CREATE TABLE IF NOT EXISTS decoy_codes (user_id TEXT PRIMARY KEY REFERENCES users(user_id), code TEXT NOT NULL, expires_at REAL NOT NULL, used INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS offline_devices (device_id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(user_id), public_key TEXT NOT NULL, counter INTEGER NOT NULL DEFAULT 0, last_hash TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS offline_reserves (user_id TEXT PRIMARY KEY REFERENCES users(user_id), remaining INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS offline_records (record_id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES offline_devices(device_id), record_hash TEXT NOT NULL);
        ''')
        if 'sender_key' not in [row[1] for row in db.execute('PRAGMA table_info(risk_signals)')]:
            db.execute('ALTER TABLE risk_signals ADD COLUMN sender_key TEXT')
        if not db.execute('SELECT 1 FROM demo_state').fetchone():
            seed(db)

def seed(db):
    db.executemany('INSERT INTO users VALUES (?, ?, ?, ?)', [('user_mom', 'Мама', 500000, 300000), ('user_son', 'Сын', 0, 0)])
    db.execute('INSERT INTO trusted_contacts VALUES (?, ?)', ('user_mom', 'user_son'))
    db.executemany('INSERT INTO complaints VALUES (?)', [('44c5fc32459f3d78586f0610a3ebc18d7b198bd37b78c14f91489c5d8fa0a58e',), ('3d1ff9786f93ca7ebb649067d953259f1b97054e242b35a78917626453087d39',)])
    db.execute('INSERT INTO file_hashes VALUES (?)', ('e68fddcb1e212ef01ee09dec3b219405798ee8b25ad62764303afff606f2c75e',))
    db.execute('INSERT INTO demo_state VALUES (1)')

def reset():
    with database() as db:
        for table in ('offline_records', 'offline_devices', 'offline_reserves', 'alert_recipients', 'alerts', 'decoy_codes', 'risk_signals', 'transfers', 'deposits_closed', 'devices', 'trusted_contacts', 'complaints', 'file_hashes', 'users', 'demo_state'):
            db.execute(f'DELETE FROM {table}')
        seed(db)
