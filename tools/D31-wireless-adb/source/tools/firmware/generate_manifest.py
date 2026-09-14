#!/usr/bin/env python3
"""离线核对刷机包与显式安装映射，生成第1版局部固件预期清单。"""

import argparse
import contextlib
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import time
import zipfile
import zlib

MAX_ENTRIES = 4096
MAX_JSON = 8 * 1024 * 1024
MAX_FILE = 4 * 1024 * 1024 * 1024
MAX_TOTAL = 16 * 1024 * 1024 * 1024
CONTEXT = {'model', 'hardwareClass', 'firmwareFamily', 'stage', 'network', 'sim', 'storage'}


class Rejected(ValueError):
    pass


def require(ok, reason):
    if not ok:
        raise Rejected(reason)


def keys(value, required, optional=()):
    require(isinstance(value, dict), '必须为JSON对象')
    require(set(required) <= value.keys() <= set(required) | set(optional), '字段缺失或含未知字段')


def text(value):
    require(isinstance(value, str) and value.strip() and len(value.encode('utf-16-le')) // 2 <= 4096,
            '字符串为空或超限')
    require(not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value), '字符串含控制字符')
    return value


def number(value, limit=2**63 - 1):
    require(type(value) is int and 0 <= value <= limit, '整数超限或类型错误')
    return value


def sha(value):
    require(isinstance(value, str) and re.fullmatch('[a-fA-F0-9]{64}', value), 'SHA-256格式错误')
    return value.lower()


def path(value, absolute=False):
    text(value)
    require('\\' not in value and ':' not in value, '路径含反斜杠或盘符')
    if absolute and value == '/':
        return value
    require(value.startswith('/') == absolute, '路径绝对性不匹配')
    parts = value[1:].split('/') if absolute else value.split('/')
    require(all(p not in ('', '.', '..') for p in parts), '路径包含空段或越界段')
    require(all(not p.endswith((' ', '.')) for p in parts), '路径含平台歧义尾字符')
    return value


def within(child, parent):
    return parent == '/' or child == parent or child.startswith(parent + '/')


def load_json(data):
    require(len(data) <= MAX_JSON, 'JSON大小超限')

    def unique(pairs):
        result = {}
        for k, v in pairs:
            require(k not in result, 'JSON重复键')
            result[k] = v
        return result

    result = json.loads(data.decode('utf-8-sig'), object_pairs_hook=unique)
    budget(result)
    return result


def budget(value, depth=0, used=None):
    used = [0] if used is None else used
    used[0] += 1
    require(depth <= 12 and used[0] <= 600000, 'JSON层级或节点超限')
    if isinstance(value, dict):
        for k, v in value.items():
            text(k)
            budget(v, depth + 1, used)
    elif isinstance(value, list):
        require(len(value) <= MAX_ENTRIES, '条目超过4096，拒绝截断；本版未实现分批聚合')
        for v in value:
            budget(v, depth + 1, used)
    elif isinstance(value, str):
        text(value)
    elif value is not None and type(value) is not bool:
        number(value)


def digest(stream, limit=MAX_FILE):
    h = hashlib.sha256()
    size = 0
    while True:
        chunk = stream.read(min(1024 * 1024, limit - size + 1))
        if not chunk:
            return {'bytes': size, 'sha256': h.hexdigest()}
        size += len(chunk)
        require(size <= limit, '源文件或解压输出大小超限')
        h.update(chunk)


def checked(actual, size, expected, label):
    require(actual == {'bytes': number(size, MAX_FILE), 'sha256': sha(expected)}, '源长度或哈希不匹配：' + label)


def safe_local(p):
    # lstat同时拒绝坏链接和Windows junction/reparse；不把resolve作为链接检测。
    p = Path(os.path.abspath(p))
    for node in [*reversed(p.parents), p]:
        s = node.lstat()
        require(not stat.S_ISLNK(s.st_mode) and not getattr(s, 'st_file_attributes', 0) & 0x400,
                '本地输入路径含链接或重解析点')
    return p


def read_local(p):
    p = safe_local(p)
    require(p.is_file() and p.stat().st_size <= MAX_JSON, '元数据文件类型或大小无效')
    return p.read_bytes()


class Package:
    def __init__(self, filename):
        self.root = safe_local(filename)
        self.archive = None
        self.files = {}
        self.total = 0
        self.nodes = 0
        self.package_digest = None

    def __enter__(self):
        try:
            if self.root.is_dir():
                self._walk(self.root)
            else:
                require(self.root.is_file(), '输入必须为展开目录或ZIP')
                with self.root.open('rb') as f:
                    self.package_digest = digest(f)
                self.archive = zipfile.ZipFile(self.root)
                seen = set()
                for info in self.archive.infolist():
                    require(info.orig_filename == info.filename, 'ZIP名称含零字节')
                    name = path(info.filename[:-1] if info.is_dir() else info.filename)
                    require(name not in seen, 'ZIP重复路径')
                    seen.add(name)
                    require(len(seen) <= MAX_ENTRIES, 'ZIP条目超过4096')
                    mode = stat.S_IFMT(info.external_attr >> 16)
                    require(mode in (0, stat.S_IFDIR if info.is_dir() else stat.S_IFREG), 'ZIP含链接或特殊节点')
                    require(not info.flag_bits & 1, '不支持加密ZIP')
                    if not info.is_dir():
                        self._add(name, info, info.file_size)
                for name in seen:
                    parts = name.split('/')
                    require(not any('/'.join(parts[:n]) in self.files for n in range(1, len(parts))),
                            'ZIP文件与目录前缀冲突')
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def _add(self, name, value, size):
        number(size, MAX_FILE)
        self.total += size
        require(self.total <= MAX_TOTAL and len(self.files) < MAX_ENTRIES, '包大小或条目数超限')
        require(name not in self.files, '包内重复路径')
        self.files[name] = value

    def _walk(self, directory):
        for child in directory.iterdir():
            self.nodes += 1
            require(self.nodes <= MAX_ENTRIES, '展开目录节点超过4096')
            safe_local(child)
            name = path(child.relative_to(self.root).as_posix())
            if child.is_dir():
                self._walk(child)
            else:
                require(child.is_file(), '展开目录含特殊节点')
                self._add(name, child, child.stat().st_size)

    @contextlib.contextmanager
    def open(self, name):
        require(name in self.files, '缺少源文件：' + name)
        item = self.files[name]
        if self.archive:
            with self.archive.open(item) as f:
                yield f
        else:
            safe_local(item)
            with item.open('rb') as f:
                yield f

    def read(self, name):
        with self.open(name) as f:
            data = f.read(MAX_JSON + 1)
        require(len(data) <= MAX_JSON, '元数据大小超限')
        return data

    def __exit__(self, *args):
        if self.archive:
            self.archive.close()


def observed(value, source):
    return {'state': 'OBSERVED', 'value': value, 'source': source}


def unchecked(source, reason):
    return {'state': 'NOT_CHECKED', 'source': source, 'reason': reason}


def validate_mapping(m):
    keys(m, {'schemaVersion', 'firmwareId', 'build', 'context', 'scope', 'bindings', 'inventory',
             'originRoots', 'ignoredSources', 'optionalMembers', 'entries'})
    require(type(m['schemaVersion']) is int and m['schemaVersion'] == 1, '映射版本不支持')
    text(m['firmwareId'])
    text(m['build'])
    keys(m['context'], CONTEXT)
    for v in m['context'].values():
        text(v)
    keys(m['bindings'], {'installerSourceSha256', 'sourceListSha256', 'packageSha256'})
    for v in m['bindings'].values():
        sha(v)
    keys(m['inventory'], {'path', 'sha256'})
    path(m['inventory']['path'])
    sha(m['inventory']['sha256'])
    require(isinstance(m['scope'], list) and 0 < len(m['scope']) <= 32, '范围数无效')
    for i, scope in enumerate(m['scope']):
        path(scope, True)
        require(not any(within(scope, p) or within(p, scope) for p in m['scope'][:i]), '范围重叠')
    require(isinstance(m['entries'], list) and 0 < len(m['entries']) <= MAX_ENTRIES, '映射条目数无效')
    destinations, sources = set(), set()
    for row in m['entries']:
        keys(row, {'action', 'destination'}, {'source', 'mode', 'uid', 'gid', 'reason'})
        dest, action = path(row['destination'], True), row['action']
        require(dest not in destinations, '重复目的路径')
        require(any(within(dest, s) for s in m['scope']), '目的路径越出声明范围')
        destinations.add(dest)
        if action in ('generated', 'migration', 'not-checked'):
            keys(row, {'action', 'destination', 'reason'})
            text(row['reason'])
        else:
            require(action in ('copy', 'block', 'gzip-block', 'verify-only'), '未知安装动作')
            keys(row, {'action', 'destination', 'source'} | ({'mode', 'uid', 'gid'} if action == 'copy' else set()))
            src = path(row['source'])
            require(src not in sources, '重复来源映射；一对多须另行明确审查')
            sources.add(src)
            if action == 'copy':
                require(isinstance(row['mode'], str) and re.fullmatch('[0-7]{4}', row['mode']), 'mode必须为四位八进制权限位')
                number(row['uid'], 2**32 - 1)
                number(row['gid'], 2**32 - 1)
    for row in m['entries']:
        if row['action'] in ('copy', 'block', 'gzip-block'):
            require(not any(d != row['destination'] and within(d, row['destination']) for d in destinations),
                    '文件或块目标与子路径冲突')
    require(isinstance(m['originRoots'], dict) and m['originRoots'], '来源根映射缺失')
    for src, dest in m['originRoots'].items():
        path(src)
        path(dest)
    require(isinstance(m['ignoredSources'], dict), '忽略来源必须为对象')
    for src, reason in m['ignoredSources'].items():
        path(src)
        text(reason)
    require(not sources & m['ignoredSources'].keys(), '已映射来源不得同时忽略')
    require(isinstance(m['optionalMembers'], dict), '可选元数据必须为对象')
    for src, item in m['optionalMembers'].items():
        path(src)
        keys(item, {'bytes', 'sha256', 'reason'})
        number(item['bytes'], MAX_FILE)
        sha(item['sha256'])
        text(item['reason'])


def generate(input_path, mapping_path, installer_source, source_list, baseline_id, baseline_revision, valid_until_ms):
    mapping_bytes = read_local(mapping_path)
    m = load_json(mapping_bytes)
    validate_mapping(m)
    for value in (baseline_id, baseline_revision):
        text(value)
    now = time.time_ns() // 1000000
    require(number(valid_until_ms) >= now, '有效期早于生成时刻')
    installer = read_local(installer_source)
    catalog_bytes = read_local(source_list)
    for data, key in ((installer, 'installerSourceSha256'), (catalog_bytes, 'sourceListSha256')):
        require(hashlib.sha256(data).hexdigest() == sha(m['bindings'][key]), '审查绑定哈希不匹配：' + key)
    catalog = load_json(catalog_bytes)
    require(isinstance(catalog, dict), '来源清单必须为对象')
    for name, value in catalog.items():
        path(name)
        require(isinstance(value, list) and len(value) == 2, '来源清单行格式无效')
        number(value[0], MAX_FILE)
        sha(value[1])
    mapping_hash = hashlib.sha256(mapping_bytes).hexdigest()
    evidence_source = 'explicit-install-map:sha256:' + mapping_hash
    with Package(input_path) as package:
        if package.package_digest:
            require(package.package_digest['sha256'] == sha(m['bindings']['packageSha256']), '整包SHA-256不匹配')
        inventory_bytes = package.read(m['inventory']['path'])
        require(hashlib.sha256(inventory_bytes).hexdigest() == sha(m['inventory']['sha256']), '包内来源清单哈希不匹配')
        inventory = load_json(inventory_bytes)
        require(isinstance(inventory, dict) and isinstance(inventory.get('文件'), list), '包内来源清单格式无效')
        records = {}
        for item in inventory['文件']:
            keys(item, {'path', 'bytes', 'sha256'}, {'source_sha256', 'uncompressed_bytes'})
            name = path(item['path'])
            require(name not in records, '来源清单重复路径')
            number(item['bytes'], MAX_FILE)
            sha(item['sha256'])
            records[name] = item
        used = {r['source'] for r in m['entries'] if 'source' in r}
        require(used | m['ignoredSources'].keys() == records.keys(), '安装映射与来源清单不完整或含多余项')
        require(not (records.keys() | {m['inventory']['path']}) & m['optionalMembers'].keys(), '可选元数据与必需来源冲突')
        required = records.keys() | {m['inventory']['path']}
        require(required <= package.files.keys(), '包中缺少来源清单要求的文件')
        require(package.files.keys() <= required | m['optionalMembers'].keys(), '包中存在未声明文件')
        verified = {}
        for name, record in records.items():
            with package.open(name) as f:
                actual = digest(f, record['bytes'])
            checked(actual, record['bytes'], record['sha256'], name)
            verified[name] = actual
        optional = []
        for name, item in m['optionalMembers'].items():
            if name in package.files:
                with package.open(name) as f:
                    checked(digest(f, item['bytes']), item['bytes'], item['sha256'], name)
                optional.append(name)
        entries, provenance, origins = [], [], set()
        for row in m['entries']:
            action, dest = row['action'], row['destination']
            entry = {'path': dest, 'presence': unchecked(evidence_source, '运行时生成或迁移结果未检查'), 'fields': {}}
            report_row = dict(row)
            if 'source' in row:
                src = row['source']
                roots = [p for p in m['originRoots'] if src.startswith(p + '/')]
                require(roots, '来源根映射缺失：' + src)
                root = max(roots, key=len)
                origin = m['originRoots'][root] + src[len(root):]
                # gzip的文件名转换仅由已声明动作决定，不解释C语义。
                if action == 'gzip-block':
                    require(origin.endswith('.gz'), 'gzip映射源后缀无效')
                    origin = origin[:-3]
                require(origin in catalog and origin not in origins, '原始来源缺失或重复：' + origin)
                origins.add(origin)
                actual = verified[src]
                if action == 'gzip-block':
                    with package.open(src) as f, gzip.GzipFile(fileobj=f) as raw:
                        actual = digest(raw, catalog[origin][0])
                    record = records[src]
                    require('source_sha256' in record and 'uncompressed_bytes' in record, 'gzip缺少解压来源证据')
                    checked(actual, record['uncompressed_bytes'], record['source_sha256'], src)
                checked(actual, *catalog[origin], origin)
                report_row.update({'origin': origin, 'sourceEvidence': verified[src], 'installedContentEvidence': actual})
                source = evidence_source + ';payload:' + src
                if action == 'verify-only':
                    entry['presence'] = unchecked(source, '安装器仅校验现有分区，未写入；现场状态未检查')
                else:
                    entry['presence'] = observed('PRESENT', source)
                    fields = entry['fields']
                    fields['type'] = observed('file' if action == 'copy' else 'block', source)
                    fields['sha256'] = observed(actual['sha256'], source)
                    for field in ('mode', 'uid', 'gid'):
                        fields[field] = (observed(row[field], evidence_source) if action == 'copy'
                                         else unchecked(source, '未推定分区节点权限或属主'))
                    for field in ('link', 'selinux', 'xattrs', 'activeSource', 'mountSource', 'activation'):
                        fields[field] = unchecked(source, '离线包未提供该字段的现场证据')
            else:
                entry['presence'] = unchecked(evidence_source, row['reason'])
            entries.append(entry)
            provenance.append(report_row)
        require(origins == catalog.keys(), '原始来源清单存在未映射项')
        manifest = {
            'schemaVersion': 1, 'role': 'FIRMWARE', 'snapshotId': 'firmware-' + str(now) + '-' + mapping_hash[:12],
            'baselineId': baseline_id, 'baselineRevision': baseline_revision, 'firmwareId': m['firmwareId'],
            'build': m['build'], 'collectorVersion': 'd31-firmware-offline-1', 'capturedAtMs': now,
            'validUntilMs': valid_until_ms, 'uptimeMs': 0, 'context': m['context'], 'completeness': 'PARTIAL',
            'scope': [{'path': s, 'state': 'PARTIAL', 'source': evidence_source,
                       'reason': '仅核对显式安装映射；未枚举完整系统文件树及运行状态'} for s in m['scope']],
            'entries': sorted(entries, key=lambda e: e['path'])}
        budget(manifest)
        require(len(json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8')) + 1 <= MAX_JSON,
                '输出清单大小超限')
        report = {
            'schemaVersion': 1, 'snapshotId': manifest['snapshotId'], 'mappingSha256': mapping_hash,
            'generatorSha256': hashlib.sha256(read_local(__file__)).hexdigest(),
            'bindings': m['bindings'], 'inventory': m['inventory'],
            'inputKind': 'ZIP' if package.archive else 'DIRECTORY',
            'packageEvidence': package.package_digest if package.archive else unchecked(evidence_source, '展开目录不代表ZIP原件；未检查整包哈希'),
            'comparisonExtent': 'SINGLE_BOUNDED_BATCH', 'wholeSystemCoverage': 'NOT_ESTABLISHED',
            'aggregationStatus': 'NOT_IMPLEMENTED', 'systemConsistency': 'NOT_ASSESSED',
            'runtimeVerification': 'NOT_PERFORMED',
            'counts': {'entries': len(entries), 'verifiedSources': len(verified),
                       'expectedPresent': sum(e['presence']['state'] == 'OBSERVED' for e in entries),
                       'uncheckedPresence': sum(e['presence']['state'] == 'NOT_CHECKED' for e in entries)},
            'ignoredSources': m['ignoredSources'], 'optionalMembersPresent': optional,
            'verifiedSources': verified, 'entries': provenance,
            'limitations': ['OBSERVED仅表示已核实的固件安装预期，不代表设备实际存在或执行成功',
                            '源码与二进制分别绑定哈希，未证明可重现构建或执行任意C语义',
                            '分区镜像摘要不等于完整系统文件树枚举；运行时和迁移结果未检查',
                            'baselineId与baselineRevision由调用者指定，不证明开发板基准已冻结',
                            '输入应为静止的可信离线副本；不保证对抗恶意并发替换路径',
                            '不修改输入、不解包到文件系统、不执行安装器、无网络或设备动作']}
        return manifest, report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('input', 'mapping', 'installer-source', 'source-list', 'output', 'baseline-id', 'baseline-revision'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--valid-until-ms', required=True, type=int, help='调用者明确指定的清单有效期，毫秒时间戳')
    args = parser.parse_args(argv)
    output = Path(os.path.abspath(args.output))
    try:
        safe_local(output.parent)
        input_root = safe_local(args.input)
        require(output != input_root and input_root not in output.parents, '输出不得位于输入包内')
        output.mkdir(exist_ok=False)
    except (OSError, Rejected) as e:
        print(json.dumps({'code': 'OUTPUT_REJECTED', 'reason': str(e)}, ensure_ascii=False), file=sys.stderr)
        return 2
    try:
        manifest, report = generate(args.input, args.mapping, args.installer_source, args.source_list,
                                    args.baseline_id, args.baseline_revision, args.valid_until_ms)
        for filename, value in (('source-report.json', report), ('firmware-manifest.json.pending', manifest)):
            with (output / filename).open('x', encoding='utf-8', newline='\n') as f:
                json.dump(value, f, ensure_ascii=False, indent=2)
                f.write('\n')
        (output / 'firmware-manifest.json.pending').rename(output / 'firmware-manifest.json')
        print(json.dumps({'result': 'PARTIAL', 'counts': report['counts']}, ensure_ascii=False))
        return 0
    except (OSError, ValueError, KeyError, TypeError, RecursionError, EOFError, zipfile.BadZipFile,
            zlib.error, NotImplementedError) as e:
        rejection = {'schemaVersion': 1, 'code': 'GENERATION_REJECTED', 'reason': str(e),
                     'systemConsistency': 'NOT_ASSESSED', 'runtimeVerification': 'NOT_PERFORMED'}
        with (output / 'rejection.json').open('x', encoding='utf-8') as f:
            json.dump(rejection, f, ensure_ascii=False, indent=2)
        print(json.dumps(rejection, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
