#!/usr/bin/env python3
"""生成D31刷机包的gzip系统载荷、无压缩ZIP和隐私扫描报告。"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import shutil
import zipfile
from pathlib import Path


SYSTEM_SIZE = 1_610_612_736
EXPECTED_SOURCES = json.loads((Path(__file__).parent / "sources-v1.4.3.json").read_text(encoding="utf-8"))
SENSITIVE_PATTERNS = {
    "电子邮件地址": re.compile(rb"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
    "澳大利亚手机号": re.compile(rb"(?<!\d)04\d{8}(?!\d)"),
    "SIP密码标记": re.compile(rb"(?i)(sip.{0,24}(password|passwd)|auth_password)"),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def copy_and_hash(source: Path, destination: Path) -> dict[str, object]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    return {
        "path": destination.as_posix(),
        "bytes": destination.stat().st_size,
        "sha256": sha256(destination),
    }


def scan_binary(path: Path) -> dict[str, list[str]]:
    findings: dict[str, list[str]] = {}
    members: list[tuple[str, bytes]] = []
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path, "r") as archive:
            members = [
                (item.filename, archive.read(item))
                for item in archive.infolist()
                if not item.is_dir()
            ]
    else:
        members = [(path.name, path.read_bytes())]
    for member_name, data in members:
        for label, pattern in SENSITIVE_PATTERNS.items():
            matches = sorted(
                {match.group(0).decode("ascii", errors="replace") for match in pattern.finditer(data)}
            )
            if matches:
                findings.setdefault(label, []).extend(
                    f"{member_name}: {match}" for match in matches[:50]
                )
    for label in findings:
        findings[label] = sorted(set(findings[label]))[:50]
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    source = args.source.resolve()
    output = args.output.resolve()
    staging = output / "staging"
    staging.mkdir(parents=True, exist_ok=False)

    source_verification: dict[str, dict[str, object]] = {}
    for relative_path, (expected_size, expected_hash) in EXPECTED_SOURCES.items():
        candidate = source / relative_path
        if not candidate.is_file():
            raise SystemExit(f"缺少固定源文件：{relative_path}")
        actual_size = candidate.stat().st_size
        actual_hash = sha256(candidate)
        if actual_size != expected_size or actual_hash != expected_hash:
            raise SystemExit(
                f"固定源文件不匹配：{relative_path}，"
                f"长度={actual_size}，SHA-256={actual_hash}"
            )
        source_verification[relative_path] = {
            "bytes": actual_size,
            "sha256": actual_hash,
            "status": "通过",
        }
    (output / "source_verification.json").write_text(
        json.dumps(source_verification, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    system_image = source / "partitions/system.img"
    boot_image = source / "partitions/boot.img"
    if system_image.stat().st_size != SYSTEM_SIZE:
        raise SystemExit("system.img尺寸不匹配")

    artifacts: list[dict[str, object]] = []
    meta = staging / "META-INF/com/google/android"
    meta.mkdir(parents=True)
    artifacts.append(copy_and_hash(args.binary, meta / "update-binary"))
    artifacts.append(copy_and_hash(Path(__file__).parent / "native/updater-script", meta / "updater-script"))

    payload = staging / "payload"
    payload.mkdir()
    artifacts.append(copy_and_hash(boot_image, payload / "boot.img"))
    artifacts.append(copy_and_hash(source / "partitions/logo.img", payload / "logo.img"))

    compressed_system = payload / "system.img.gz"
    with system_image.open("rb") as source_file, compressed_system.open("wb") as compressed_file:
        with gzip.GzipFile(fileobj=compressed_file, mode="wb", compresslevel=6, mtime=0) as target:
            shutil.copyfileobj(source_file, target, length=4 * 1024 * 1024)
    artifacts.append({
        "path": compressed_system.as_posix(),
        "bytes": compressed_system.stat().st_size,
        "sha256": sha256(compressed_system),
        "source_sha256": source_verification["partitions/system.img"]["sha256"],
        "uncompressed_bytes": system_image.stat().st_size,
    })

    for apk in sorted((source / "apks").glob("*.apk")):
        artifacts.append(copy_and_hash(apk, payload / "apps" / apk.name))
    for item in sorted((source / "runtime").iterdir()):
        if item.is_file():
            artifacts.append(copy_and_hash(item, payload / "runtime" / item.name))
    for item in sorted((source / "system_payload").iterdir()):
        if item.is_file():
            artifacts.append(copy_and_hash(item, payload / "system-patches" / item.name))

    for artifact in artifacts:
        artifact["path"] = Path(str(artifact["path"])).relative_to(staging).as_posix()

    manifest = {
        "产品": "D31 SVP3390完整刷机包",
        "版本": "1.4.3",
        "目标构建": "alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys",
        "数据策略": "清空userdata，只写入APK本体及无账号系统功能文件",
        "禁止写入分区": ["boot", "recovery", "nvram", "nvdata", "protect1", "protect2", "proinfo", "keystore", "oemkeystore", "frp"],
        "文件": artifacts,
    }
    manifest_path = payload / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    forbidden_parts = {"shared_prefs", "databases", "accounts", "misc", "user", "user_de"}
    for item in staging.rglob("*"):
        if forbidden_parts.intersection(item.relative_to(staging).parts):
            raise SystemExit(f"刷机包出现禁止的用户配置路径：{item}")

    privacy_findings: dict[str, dict[str, list[str]]] = {}
    privacy_candidates = (
        list((payload / "apps").glob("*.apk"))
        + list((payload / "runtime").glob("*"))
        + list((payload / "system-patches").glob("*"))
    )
    for candidate in privacy_candidates:
        if candidate.is_file():
            findings = scan_binary(candidate)
            if findings:
                privacy_findings[candidate.name] = findings
    privacy_report = output / "privacy_scan_structured.json"
    privacy_report.write_text(json.dumps(privacy_findings, ensure_ascii=False, indent=2), encoding="utf-8")

    unsigned = output / "D31_SVP3390_Factory_Flash_v1.4.3_unsigned.zip"
    with zipfile.ZipFile(unsigned, "w", compression=zipfile.ZIP_STORED, allowZip64=False) as archive:
        for item in sorted(staging.rglob("*")):
            if item.is_file():
                archive.write(item, item.relative_to(staging).as_posix())

    summary = {
        "unsigned_zip": str(unsigned),
        "bytes": unsigned.stat().st_size,
        "sha256": sha256(unsigned),
        "privacy_report": str(privacy_report),
        "privacy_finding_files": len(privacy_findings),
    }
    (output / "build_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
