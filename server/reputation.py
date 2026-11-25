"""Источники репутации: локальные жалобы и вымышленная демонстрационная заглушка."""
import hashlib
from typing import Protocol

class ReputationProvider(Protocol):
    def contains(self, phone_hash: str) -> bool: ...

class LocalComplaintsProvider:
    def __init__(self, db):
        self.db = db

    def contains(self, phone_hash: str) -> bool:
        return self.db.execute('SELECT 1 FROM complaints WHERE phone_hash=?', (phone_hash,)).fetchone() is not None

class MockOfficialProvider:
    # Исключительно вымышленные строки, не сведения госорганов.
    hashes = frozenset(hashlib.sha256(s.encode('utf-8')).hexdigest() for s in ('senim-official-mock-1', 'senim-official-mock-2'))

    def contains(self, phone_hash: str) -> bool:
        return phone_hash in self.hashes
