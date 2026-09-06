import argparse
import ctypes as ct
import hashlib
import json
import os
from pathlib import Path
import struct
import time


ETHER_TYPE = 0x9974
GET_INFO = 0x0101
SEND_INFO = 0x0102


def mac_bytes(value):
    value = bytes.fromhex(value.replace(':', '').replace('-', ''))
    if len(value) != 6 or value[0] & 1 or not any(value):
        raise ValueError('Only a nonzero unicast MAC is allowed')
    return value


def query_frame(source, target):
    if len(source) != 6 or len(target) != 6 or source == target:
        raise ValueError('Invalid source or target')
    if source[0] & 1 or target[0] & 1 or not any(source) or not any(target):
        raise ValueError('Unicast only')
    body = struct.pack('!HI8sH', GET_INFO, 1, bytes(8), 0)
    frame = target + source + struct.pack('!H', ETHER_TYPE)
    frame += hashlib.md5(body).digest()[:4] + body
    return frame.ljust(60, b'\0')


def parse_response(frame, source, target):
    if len(frame) < 34 or frame[:6] != source or frame[6:12] != target:
        return None
    if struct.unpack_from('!H', frame, 12)[0] != ETHER_TYPE:
        return None
    action, session, _, length = struct.unpack_from('!HI8sH', frame, 18)
    if length > 1480 or len(frame) < 34 + length:
        return None
    body = frame[18:34 + length]
    return {
        'action': f'0x{action:04x}', 'session': session,
        'payload_bytes': length,
        'checksum_valid': frame[14:18] == hashlib.md5(body).digest()[:4],
        'device_info_response': action == SEND_INFO,
    }


class Header(ct.Structure):
    _fields_ = [('seconds', ct.c_int32), ('microseconds', ct.c_int32),
                ('caplen', ct.c_uint32), ('length', ct.c_uint32)]


class Bpf(ct.Structure):
    _fields_ = [('length', ct.c_uint), ('instructions', ct.c_void_p)]


def capture_query(adapter, source, target, output):
    output.mkdir(parents=True, exist_ok=False)
    frame = query_frame(source, target)
    (output / 'request.bin').write_bytes(frame)
    dll_dir = os.add_dll_directory(r'C:\Windows\System32\Npcap')
    pcap = ct.CDLL(r'C:\Windows\System32\Npcap\wpcap.dll')
    pcap.pcap_open_live.argtypes = [ct.c_char_p, ct.c_int, ct.c_int, ct.c_int, ct.c_char_p]
    pcap.pcap_open_live.restype = ct.c_void_p
    pcap.pcap_close.argtypes = [ct.c_void_p]
    pcap.pcap_datalink.argtypes = [ct.c_void_p]
    pcap.pcap_compile.argtypes = [ct.c_void_p, ct.POINTER(Bpf), ct.c_char_p, ct.c_int, ct.c_uint32]
    pcap.pcap_setfilter.argtypes = [ct.c_void_p, ct.POINTER(Bpf)]
    pcap.pcap_freecode.argtypes = [ct.POINTER(Bpf)]
    pcap.pcap_setnonblock.argtypes = [ct.c_void_p, ct.c_int, ct.c_char_p]
    pcap.pcap_sendpacket.argtypes = [ct.c_void_p, ct.c_void_p, ct.c_int]
    pcap.pcap_next_ex.argtypes = [ct.c_void_p, ct.POINTER(ct.POINTER(Header)), ct.POINTER(ct.c_void_p)]
    error = ct.create_string_buffer(256)
    handle = pcap.pcap_open_live(adapter.encode('ascii'), 2048, 0, 100, error)
    if not handle:
        raise RuntimeError(error.value.decode(errors='replace'))
    result = {'request_sent': False, 'responses': [], 'started': time.time()}
    try:
        if pcap.pcap_datalink(handle) != 1:
            raise RuntimeError('Not an Ethernet adapter')
        filt = Bpf()
        expression = f'ether proto 0x9974 and ether host {target.hex(":")}'.encode()
        if pcap.pcap_compile(handle, ct.byref(filt), expression, 1, 0xffffffff) != 0:
            raise RuntimeError('Filter compilation failed')
        try:
            if pcap.pcap_setfilter(handle, ct.byref(filt)) != 0:
                raise RuntimeError('Filter installation failed')
        finally:
            pcap.pcap_freecode(ct.byref(filt))
        if pcap.pcap_setnonblock(handle, 1, error) != 0:
            raise RuntimeError('Nonblocking capture required')
        if frame[18:20] != b'\x01\x01' or frame[32:34] != b'\0\0':
            raise RuntimeError('Query-only gate failed')
        buffer = ct.create_string_buffer(frame)
        if pcap.pcap_sendpacket(handle, buffer, len(frame)) != 0:
            raise RuntimeError('Send failed')
        result['request_sent'] = True
        deadline = time.monotonic() + 8
        count = 0
        while time.monotonic() < deadline and count < 32:
            header, data = ct.POINTER(Header)(), ct.c_void_p()
            status = pcap.pcap_next_ex(handle, ct.byref(header), ct.byref(data))
            if status == 0:
                time.sleep(0.02)
                continue
            if status < 0:
                raise RuntimeError(f'Capture error {status}')
            if header.contents.caplen > 2048:
                raise RuntimeError('Invalid capture length')
            packet = ct.string_at(data, header.contents.caplen)
            count += 1
            (output / f'frame-{count:02}.bin').write_bytes(packet)
            parsed = parse_response(packet, source, target)
            if parsed is not None:
                parsed['file'] = f'frame-{count:02}.bin'
                result['responses'].append(parsed)
        result['captured_frames'] = count
        return result
    finally:
        result['ended'] = time.time()
        (output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        pcap.pcap_close(handle)
        dll_dir.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--adapter', required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    result = capture_query(args.adapter, mac_bytes(args.source), mac_bytes(args.target), Path(args.output))
    print(json.dumps(result, indent=2))
