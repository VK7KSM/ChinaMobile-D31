import hashlib
import struct
import unittest

from query_uptool import mac_bytes, parse_response, query_frame


class QueryTests(unittest.TestCase):
    source = bytes.fromhex('020000000001')
    target = bytes.fromhex('020000000002')

    def test_query_layout(self):
        frame = query_frame(self.source, self.target)
        self.assertEqual(len(frame), 60)
        self.assertEqual(frame[:14], self.target + self.source + b'\x99\x74')
        self.assertEqual(frame[18:34], bytes.fromhex('01010000000100000000000000000000'))
        self.assertEqual(frame[14:18], hashlib.md5(frame[18:34]).digest()[:4])

    def test_no_multicast_or_broadcast(self):
        for value in ('ff:ff:ff:ff:ff:ff', '01:00:5e:00:00:01', '00:00:00:00:00:00'):
            with self.assertRaises(ValueError):
                mac_bytes(value)

    def test_response_validation(self):
        body = struct.pack('!HI8sH', 0x0102, 9, bytes(8), 4) + b'test'
        frame = self.source + self.target + b'\x99\x74' + hashlib.md5(body).digest()[:4] + body
        self.assertTrue(parse_response(frame, self.source, self.target)['device_info_response'])
        self.assertTrue(parse_response(frame, self.source, self.target)['checksum_valid'])
        self.assertIsNone(parse_response(frame[:-1], self.source, self.target))
        self.assertIsNone(parse_response(frame, self.target, self.source))
        damaged = bytearray(frame)
        damaged[-1] ^= 1
        self.assertFalse(parse_response(damaged, self.source, self.target)['checksum_valid'])


if __name__ == '__main__':
    unittest.main()
