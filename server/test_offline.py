import base64
import os
import tempfile
import unittest
import uuid

os.environ['DATABASE_PATH'] = os.path.join(tempfile.mkdtemp(prefix='senim-test-'), 'test.db')
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from database import initialize, reset
from main import Device, OfflineReserve, OfflineSync, account, offline_reserve, offline_sync, register_device

class OfflineTests(unittest.TestCase):
    def setUp(self):
        initialize()
        reset()
        self.key = Ed25519PrivateKey.generate()
        public = base64.b64encode(self.key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)).decode()
        register_device(Device(user_id='user_mom', fcm_token='test', role='user', device_id='phone', public_key=public))
        offline_reserve(OfflineReserve(user_id='user_mom', amount=50000))

    def record(self, counter=1, amount=10000):
        r = dict(record_id=str(uuid.uuid4()), device_id='phone', counter=counter, prev_hash='0'*64, from_user_id='user_mom', to_user_id='user_son', amount=amount, created_at='2026-11-25T10:30:00Z')
        text = '|'.join(str(r[k]) for k in ('record_id','device_id','counter','prev_hash','from_user_id','to_user_id','amount','created_at'))
        r['signature'] = base64.b64encode(self.key.sign(text.encode())).decode()
        return r

    def sync(self, record):
        return offline_sync(OfflineSync(user_id='user_mom', device_id='phone', records=[record]))

    def test_valid_and_replay(self):
        r = self.record()
        self.assertEqual(self.sync(r)['accepted'], [r['record_id']])
        self.assertEqual(account('user_son')['balance'], 10000)
        self.assertEqual(self.sync(r)['rejected'][0]['code'], 'REPLAY')

    def test_changed_amount(self):
        r = self.record()
        r['amount'] = 20000
        self.assertEqual(self.sync(r)['rejected'][0]['code'], 'INVALID_SIGNATURE')

    def test_skipped_counter(self):
        self.assertEqual(self.sync(self.record(counter=2))['rejected'][0]['code'], 'CHAIN_BROKEN')

    def test_limit(self):
        self.assertEqual(self.sync(self.record(amount=50001))['rejected'][0]['code'], 'LIMIT_EXCEEDED')

if __name__ == '__main__':
    unittest.main()
