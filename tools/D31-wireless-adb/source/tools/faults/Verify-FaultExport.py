"""离线核验已取回的故障包；仅输出归档参数，不连接或操作设备。"""
import argparse
import hashlib
import json
import re
import stat
import sys
import zipfile
from pathlib import Path

MAX_PACKAGE = 8 * 1024 * 1024
MAX_MANIFEST = 65536
MAX_FILES = 48


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def parse_json(data):
    return json.loads(data.decode("utf-8"), object_pairs_hook=unique_object)


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(16384), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_relative(path):
    return isinstance(path, str) and re.fullmatch(
        r"(?:event\.json|state\.json(?:\.next)?|pre-[1-4]\.json|post\.json|export-seal\.json"
        r"|attempt-[1-3]/[A-Za-z0-9][A-Za-z0-9_.-]{0,79})", path) is not None


def complete_source_files(archive, files, receipt, manifest):
    """独立检查原诊断的完整状态、源长度及原件摘要，窗口缺口不改写源状态。"""
    raw_summary = manifest.get("rawEvidence", {})
    if (receipt.get("captureState") != "COMPLETE" or not isinstance(raw_summary, dict)
            or raw_summary.get("captureState") != "COMPLETE"):
        return []
    catalog = {item["path"]: item for item in files}

    def metadata(path):
        item = catalog.get(path)
        if item is None or item["bytes"] > MAX_MANIFEST:
            return None
        try:
            value = parse_json(archive.read("evidence/" + path))
            return value if isinstance(value, dict) else None
        except (ValueError, UnicodeError):
            return None

    state = metadata("state.json")
    if state is None or state.get("capture") != "COMPLETE":
        return []
    complete = {}
    for attempt in range(1, 4):
        report = metadata(f"attempt-{attempt}/report.json")
        diagnostic = report.get("diagnostic") if report else None
        if not isinstance(diagnostic, dict) or diagnostic.get("state") != "COMPLETE":
            continue
        items = diagnostic.get("items")
        if not isinstance(items, list) or len(items) > 64:
            continue
        for item in items:
            if not isinstance(item, dict) or item.get("state") != "COMPLETE":
                continue
            artifact = item.get("artifact")
            if not isinstance(artifact, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", artifact):
                continue
            path = f"attempt-{attempt}/{artifact}"
            actual, before = catalog.get(path), item.get("before")
            if (actual is not None and actual["role"] == "RAW" and actual["bytes"] > 0
                    and isinstance(before, dict) and type(before.get("size")) is int
                    and type(item.get("storedBytes")) is int
                    and actual["bytes"] == before["size"] == item["storedBytes"]
                    and actual["sha256"] == item.get("storedSha256")):
                complete[path] = {"artifact": path, "bytes": actual["bytes"], "sha256": actual["sha256"]}
    return list(complete.values())


def verify(bundle_path, receipt, event_id):
    require(re.fullmatch(r"[a-f0-9]{64}", event_id) is not None, "INVALID_EVENT_ID")
    require(receipt["schemaVersion"] == 1 and receipt["kind"] == "FAULT_EVENT_EXPORT"
            and receipt["state"] == "EXPORTED" and receipt["eventId"] == event_id, "RECEIPT_IDENTITY")
    bundle = Path(bundle_path)
    require(not bundle.is_symlink() and bundle.is_file(), "BUNDLE_REGULAR_FILE_REQUIRED")
    size = bundle.stat().st_size
    require(0 < size <= MAX_PACKAGE and size == receipt["bytes"], "BUNDLE_LENGTH")
    require(digest_file(bundle) == receipt["sha256"], "BUNDLE_DIGEST")
    with zipfile.ZipFile(bundle) as archive:
        infos = archive.infolist()
        require(1 < len(infos) <= MAX_FILES + 1, "ZIP_ENTRY_LIMIT")
        names = set()
        for info in infos:
            require(info.filename not in names, "DUPLICATE_ZIP_ENTRY")
            names.add(info.filename)
            require(not info.is_dir() and not stat.S_ISLNK(info.external_attr >> 16), "ZIP_LINK_OR_DIRECTORY")
            require(info.compress_type == zipfile.ZIP_STORED and not info.flag_bits & 1, "ZIP_ENCODING")
            require(0 <= info.file_size <= 1048576 and info.compress_size == info.file_size, "ZIP_FILE_LIMIT")
        require("manifest.json" in names, "MANIFEST_MISSING")
        info = archive.getinfo("manifest.json")
        require(info.file_size <= MAX_MANIFEST and info.file_size == receipt["manifestBytes"], "MANIFEST_LENGTH")
        manifest_bytes = archive.read(info)
        require(hashlib.sha256(manifest_bytes).hexdigest() == receipt["manifestSha256"], "MANIFEST_DIGEST")
        manifest = parse_json(manifest_bytes)
        require(manifest["schemaVersion"] == 1 and manifest["kind"] == "FAULT_EVENT_BUNDLE"
                and manifest["eventId"] == event_id and manifest["scope"] == "ALL_FROZEN_EVENT_FILES", "MANIFEST_IDENTITY")
        files = manifest["files"]
        require(isinstance(files, list) and 0 < len(files) <= MAX_FILES and len(files) == receipt["files"], "MANIFEST_FILE_COUNT")
        expected_names = {"manifest.json"}
        total = 0
        raw_files = 0
        for item in files:
            relative = item["path"]
            require(safe_relative(relative), "UNSAFE_ENTRY_PATH")
            name = "evidence/" + relative
            require(name not in expected_names, "DUPLICATE_MANIFEST_ENTRY")
            expected_names.add(name)
            require(name in names, "EVIDENCE_MISSING")
            entry = archive.getinfo(name)
            require(type(item["bytes"]) is int and entry.file_size == item["bytes"], "EVIDENCE_LENGTH")
            digest = hashlib.sha256()
            count = 0
            with archive.open(entry) as stream:
                for chunk in iter(lambda: stream.read(16384), b""):
                    count += len(chunk)
                    require(count <= item["bytes"], "EVIDENCE_READ_LIMIT")
                    digest.update(chunk)
            require(count == item["bytes"] and digest.hexdigest() == item["sha256"], "EVIDENCE_DIGEST")
            raw = relative.endswith(".bin")
            require(item["role"] == ("RAW" if raw else "METADATA"), "EVIDENCE_ROLE")
            raw_files += int(raw)
            total += count
        require(names == expected_names, "UNLISTED_ZIP_ENTRY")
        require("evidence/event.json" in names and "evidence/state.json" in names
                and "evidence/export-seal.json" in names, "CORE_METADATA_MISSING")
        require(total == manifest["totalBytes"] == receipt["evidenceBytes"] and raw_files == manifest["rawFiles"], "TOTAL_MISMATCH")
        require(isinstance(manifest["gaps"], list) and len(manifest["gaps"]) <= 256, "GAPS_INVALID")
        require(total + len(manifest_bytes) <= MAX_PACKAGE, "TOTAL_LIMIT")
        complete_sources = complete_source_files(archive, files, receipt, manifest)
    return {
        "schemaVersion": 1,
        "state": "HOST_PACKAGE_VERIFIED",
        "eventId": event_id,
        "bytes": size,
        "sha256": receipt["sha256"],
        "manifestSha256": receipt["manifestSha256"],
        "files": len(files),
        "rawFiles": raw_files,
        "gapCount": len(manifest["gaps"]),
        "gaps": manifest["gaps"],
        "sourceLogComplete": bool(complete_sources),
        "completeSourceFiles": complete_sources,
        "sourceLogCompletenessScope": "AT_LEAST_ONE_NONEMPTY_CAPTURED_SOURCE_FILE_NOT_FULL_INCIDENT_WINDOW",
        "sourceCompleteness": "SEE_MANIFEST_GAPS",
        "deviceArchiveExecuted": False,
        "archiveArgs": ["archive", event_id, receipt["sha256"], str(size), receipt["manifestSha256"]],
    }


def main():
    parser = argparse.ArgumentParser(description="核验本机故障ZIP并生成显式归档参数，不运行ADB或网络命令")
    parser.add_argument("--zip", required=True, type=Path)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        require(args.receipt.is_file() and args.receipt.stat().st_size <= MAX_MANIFEST, "RECEIPT_LIMIT")
        result = verify(args.zip, parse_json(args.receipt.read_bytes()), args.event_id)
        with args.output.open("x", encoding="utf-8", newline="\n") as output:
            json.dump(result, output, ensure_ascii=True, indent=2)
            output.write("\n")
        print(json.dumps({"state": result["state"], "files": result["files"], "rawFiles": result["rawFiles"],
                          "gapCount": result["gapCount"], "sourceLogComplete": result["sourceLogComplete"]}))
        return 0
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, RuntimeError):
        print('{"state":"VERIFY_FAILED","deviceArchiveExecuted":false}')
        return 1


if __name__ == "__main__":
    sys.exit(main())
