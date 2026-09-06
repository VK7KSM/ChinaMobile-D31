import hashlib
import struct
import unittest

import restore_adbd_uptool as rescue


class RestoreTests(unittest.TestCase):
    source = bytes.fromhex('020000000001')
    target = bytes.fromhex('020000000002')

    def test_fixed_command_and_layout(self):
        frame = rescue.query_frame(self.source, self.target)
        action, session, reserved, length = struct.unpack_from('!HI8sH', frame, 18)
        self.assertEqual((action, session, reserved), (0x0301, 2, bytes(8)))
        self.assertEqual(frame[:14], self.target + self.source + b'\x99\x74')
        payload = frame[34:34 + length]
        number, command_length = struct.unpack_from('!BH', payload)
        expected = b'test "$(id -u)" = 0 && setprop service.adb.tcp.port 5555 && setprop ctl.start adbd'
        self.assertEqual((number, command_length), (1, len(expected)))
        self.assertEqual(payload[3:], expected + b'\0')
        self.assertEqual(len(frame), 34 + length)
        self.assertEqual(frame[14:18], hashlib.md5(frame[18:]).digest()[:4])
        self.assertLess(len(expected), 200)
        self.assertNotIn(b';', expected)

    def test_reject_invalid_targets(self):
        for target in (self.source, bytes(6), b'\xff' * 6, bytes.fromhex('01005e000001')):
            with self.assertRaises(ValueError):
                rescue.query_frame(self.source, target)


if __name__ == '__main__':
    unittest.main()
