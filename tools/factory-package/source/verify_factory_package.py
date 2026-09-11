#!/usr/bin/env python3
"""离线核验D31签名刷机包的结构、载荷和隐私边界。"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import subprocess
import struct
import tempfile
import zipfile
from pathlib import Path

from build_factory_package import EXPECTED_SOURCES


FORBIDDEN_PARTS = {"shared_prefs", "databases", "accounts", "misc", "user", "user_de"}
REQUIRED_INSTALLER_ENTRIES = {
    "META-INF/com/google/android/update-binary",
    "META-INF/com/google/android/updater-script",
}
EXPECTED_APP_PAGE = [
    ("Firefox", "org.mozilla.firefox"),
    ("VLC", "org.videolan.vlc"),
    ("Zello", "com.loudtalks"),
    ("Telegram", "org.telegram.messenger.web"),
    ("计算器", "com.android.calculator2"),
    ("D31 无线ADB", "net.elfradio.d31bootstrap"),
    ("设置", "com.android.settings"),
    ("文件管理器", "me.zhanghai.android.files"),
]
EXPECTED_PROJECT_CERTIFICATE = "9B31F89FA50B672ECFE02D73A534CC03F6CF893739AEC268F9FE0B71E72DA72E"


def verify_manifest_files(manifest: dict, checked: dict) -> None:
    """将包内清单逐项绑定到已实算载荷，不能仅验证路径或接受空清单。"""
    if not isinstance(manifest, dict) or manifest.get("版本") != "1.4.3":
        raise SystemExit("包内清单版本不匹配")
    files = manifest.get("文件")
    actual = {name: value for name, value in checked.items() if " -> " not in name}
    if not isinstance(files, list) or len(files) != len(actual):
        raise SystemExit("包内清单数量与实际载荷不符")
    seen = set()
    for item in files:
        if not isinstance(item, dict):
            raise SystemExit("包内清单条目格式无效")
        name = item.get("path")
        if not isinstance(name, str) or name not in actual or name in seen:
            raise SystemExit("包内清单存在遗漏、重复或未登记路径")
        seen.add(name)
        verified = actual[name]
        if (type(item.get("bytes")) is not int or item["bytes"] != verified["bytes"]
                or not isinstance(item.get("sha256"), str)
                or item["sha256"].upper() != verified["sha256"].upper()):
            raise SystemExit("包内清单长度或摘要与实际载荷不符")
        if name == "payload/system.img.gz":
            system = checked["payload/system.img.gz -> system.img"]
            if (type(item.get("uncompressed_bytes")) is not int or item["uncompressed_bytes"] != system["bytes"]
                    or not isinstance(item.get("source_sha256"), str)
                    or item["source_sha256"].upper() != system["sha256"].upper()):
                raise SystemExit("包内清单系统原像与实际解压结果不符")


def run_checked(command: list[str], description: str) -> str:
    process = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    output = (process.stdout + process.stderr).decode("utf-8", errors="replace")
    if process.returncode != 0:
        raise SystemExit(f"{description}失败：{output.strip()}")
    return output


def docker_desktop_path(path: Path) -> str:
    resolved = path.resolve()
    drive = resolved.drive.rstrip(":").lower()
    if not drive:
        raise SystemExit(f"无法转换为docker-desktop路径：{resolved}")
    return "/mnt/host/" + drive + resolved.as_posix()[2:]


def verify_app_page(raw: bytes) -> list[dict[str, str]]:
    document = json.loads(raw.decode("utf-8"))
    tabs = document.get("tabs")
    if not isinstance(tabs, list) or len(tabs) < 2:
        raise SystemExit("config-tab缺少应用页")
    items = tabs[1].get("items")
    if not isinstance(items, list):
        raise SystemExit("config-tab应用页格式错误")
    actual = []
    for item in items:
        app = item.get("info", {}).get("app", {})
        actual.append((app.get("name"), app.get("packageName")))
    if actual != EXPECTED_APP_PAGE:
        raise SystemExit(f"config-tab应用页顺序或目标不匹配：{actual}")
    return [{"名称": name, "包名": package_name} for name, package_name in actual]


def verify_project_apk(
    apk: Path,
    aapt: Path,
    apksigner: Path,
    label: str,
    package_name: str,
    version_code: str,
    version_name: str,
) -> dict[str, str]:
    badging = run_checked([str(aapt), "dump", "badging", str(apk)], f"读取{label}版本")
    expected_package = (
        f"package: name='{package_name}' versionCode='{version_code}' "
        f"versionName='{version_name}'"
    )
    if expected_package not in badging:
        raise SystemExit(f"{label}包名或版本不匹配")
    certificate = run_checked(
        [str(apksigner), "verify", "--print-certs", str(apk)], f"验证{label}签名"
    )
    normalized = certificate.upper().replace(":", "")
    if EXPECTED_PROJECT_CERTIFICATE not in normalized:
        raise SystemExit(f"{label}签名证书不匹配")
    return {
        "包名": package_name,
        "版本": version_name,
        "版本号": version_code,
        "签名证书SHA-256": EXPECTED_PROJECT_CERTIFICATE,
    }


def verify_apk_metadata(
    apk: Path,
    aapt: Path,
    label: str,
    package_name: str,
    version_code: str,
    version_name: str,
) -> dict[str, str]:
    badging = run_checked([str(aapt), "dump", "badging", str(apk)], f"读取{label}版本")
    expected_package = (
        f"package: name='{package_name}' versionCode='{version_code}' "
        f"versionName='{version_name}'"
    )
    if expected_package not in badging:
        raise SystemExit(f"{label}包名或版本不匹配")
    return {
        "包名": package_name,
        "版本": version_name,
        "版本号": version_code,
        "部署方式": "PackageManager扫描原厂包后只读绑定候选APK",
    }


def sha256_stream(source) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    for chunk in iter(lambda: source.read(4 * 1024 * 1024), b""):
        digest.update(chunk)
        size += len(chunk)
    return size, digest.hexdigest().upper()


def expected_archive_hashes() -> dict[str, tuple[int, str]]:
    result: dict[str, tuple[int, str]] = {}
    for path, value in EXPECTED_SOURCES.items():
        size, digest = value
        if path.startswith("apks/"):
            result[f"payload/apps/{Path(path).name}"] = (size, digest)
        elif path.startswith("system_payload/"):
            result[f"payload/system-patches/{Path(path).name}"] = (size, digest)
        elif path.startswith("runtime/"):
            result[f"payload/runtime/{Path(path).name}"] = (size, digest)
        elif path in ("partitions/boot.img", "partitions/logo.img"):
            result["payload/" + Path(path).name] = (size, digest)
    return result


def verify_whole_file_footer(path: Path) -> dict[str, int]:
    prefix = b"signed by SignApk\x00"
    with path.open("rb") as source:
        source.seek(-6, 2)
        footer = source.read(6)
        signature_start, marker, comment_size = struct.unpack("<HHH", footer)
        if marker != 0xFFFF or comment_size < 6 or signature_start > comment_size:
            raise SystemExit("签名ZIP缺少AOSP Recovery整文件签名尾部")
        if comment_size + 22 > path.stat().st_size:
            raise SystemExit("AOSP Recovery整文件签名尾部尺寸越界")
        source.seek(-(comment_size), 2)
        if source.read(len(prefix)) != prefix:
            raise SystemExit("AOSP Recovery整文件签名前缀不匹配")
        if comment_size - signature_start != len(prefix):
            raise SystemExit("AOSP Recovery整文件签名偏移不匹配")
    return {"signature_start": signature_start, "comment_size": comment_size}


def verify_whole_file_signature(
    package: Path, certificate: Path, openssl: Path, footer: dict[str, int]
) -> dict[str, object]:
    expected_certificate_hash = "A4384BA815B9499A5CE349B4E33C1755278873FE2EAC150A068823F526E6DBDE"
    certificate_hash = sha256_stream(certificate.open("rb"))[1]
    if certificate_hash != expected_certificate_hash:
        raise SystemExit("Recovery签名证书SHA-256不匹配")

    signature_start = footer["signature_start"]
    comment_size = footer["comment_size"]
    package_size = package.stat().st_size
    cms_offset = package_size - signature_start
    cms_size = signature_start - 6
    signed_size = package_size - comment_size - 2

    with tempfile.TemporaryDirectory(prefix="d31-ota-signature-") as temporary:
        cms_path = Path(temporary) / "signature.der"
        content_path = Path(temporary) / "signed-content.bin"
        with package.open("rb") as source, cms_path.open("wb") as cms:
            source.seek(cms_offset)
            cms.write(source.read(cms_size))
        with package.open("rb") as source, content_path.open("wb") as content:
            remaining = signed_size
            while remaining:
                chunk = source.read(min(4 * 1024 * 1024, remaining))
                if not chunk:
                    raise SystemExit("读取Recovery整文件签名内容时提前结束")
                content.write(chunk)
                remaining -= len(chunk)
        command = [
            str(openssl), "cms", "-verify", "-inform", "DER",
            "-in", str(cms_path), "-content", str(content_path),
            "-certfile", str(certificate), "-nointern", "-noverify", "-binary",
            "-out", "NUL",
        ]
        process = subprocess.run(
            command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False
        )
        if process.returncode != 0:
            message = (process.stdout + process.stderr).decode("utf-8", errors="replace").strip()
            raise SystemExit(f"Recovery整文件密码学签名验证失败：{message}")
    return {
        "certificate_sha256": certificate_hash,
        "signed_bytes": signed_size,
        "cms_bytes": cms_size,
        "openssl_result": "通过",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--certificate", type=Path, required=True)
    parser.add_argument("--openssl", type=Path, required=True)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--updater-script", type=Path, required=True)
    parser.add_argument("--system-verifier", type=Path, required=True)
    parser.add_argument("--config-template", type=Path, required=True)
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    args = parser.parse_args()

    package = args.package.resolve()
    footer = verify_whole_file_footer(package)
    report: dict[str, object] = {
        "package": str(package),
        "bytes": package.stat().st_size,
        "sha256": sha256_stream(package.open("rb"))[1],
        "whole_file_signature": {
            **footer,
            **verify_whole_file_signature(package, args.certificate, args.openssl, footer),
        },
    }
    expected = expected_archive_hashes()
    checked: dict[str, dict[str, object]] = {}

    with tempfile.TemporaryDirectory(prefix="d31-package-content-") as temporary:
      temporary_path = Path(temporary)
      with zipfile.ZipFile(package, "r", allowZip64=False) as archive:
        bad_crc = archive.testzip()
        if bad_crc is not None:
            raise SystemExit(f"ZIP CRC校验失败：{bad_crc}")
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise SystemExit("刷机包存在重复ZIP路径")
        missing_installer = sorted(REQUIRED_INSTALLER_ENTRIES - set(names))
        if missing_installer:
            raise SystemExit(f"刷机包缺少Recovery安装入口：{missing_installer}")
        installer_sources = {
            "META-INF/com/google/android/update-binary": args.binary,
            "META-INF/com/google/android/updater-script": args.updater_script,
        }
        for name, source_path in installer_sources.items():
            info = archive.getinfo(name)
            if info.compress_type != zipfile.ZIP_STORED or (info.flag_bits & 0x0008):
                raise SystemExit(f"Recovery安装入口ZIP结构不兼容：{name}")
            expected_size, expected_hash = sha256_stream(source_path.open("rb"))
            with archive.open(name, "r") as source:
                actual_size, actual_hash = sha256_stream(source)
            if actual_size != expected_size or actual_hash != expected_hash:
                raise SystemExit(f"签名包内Recovery安装入口不匹配：{name}")
            checked[name] = {
                "bytes": actual_size,
                "sha256": actual_hash,
                "status": "通过",
            }

        otacert_name = "META-INF/com/android/otacert"
        if otacert_name not in names:
            raise SystemExit("签名包缺少Recovery证书条目")
        with archive.open(otacert_name, "r") as source:
            _, packaged_certificate_hash = sha256_stream(source)
        _, expected_certificate_hash = sha256_stream(args.certificate.open("rb"))
        if packaged_certificate_hash != expected_certificate_hash:
            raise SystemExit("签名包内Recovery证书与目标证书不匹配")
        for info in archive.infolist():
            parts = Path(info.filename).parts
            if FORBIDDEN_PARTS.intersection(parts):
                raise SystemExit(f"刷机包出现禁止的用户配置路径：{info.filename}")
            if info.file_size == 0xFFFFFFFF or info.compress_size == 0xFFFFFFFF:
                raise SystemExit(f"刷机包使用了ZIP64：{info.filename}")
            if info.filename.startswith("payload/") and info.compress_type != zipfile.ZIP_STORED:
                raise SystemExit(f"安装器载荷不是ZIP_STORED：{info.filename}")
            if info.filename.startswith("payload/") and (info.flag_bits & 0x0008):
                raise SystemExit(f"安装器载荷使用了data descriptor：{info.filename}")

        for name, (expected_size, expected_hash) in expected.items():
            if name not in names:
                raise SystemExit(f"签名刷机包缺少载荷：{name}")
            with archive.open(name, "r") as source:
                actual_size, actual_hash = sha256_stream(source)
            if actual_size != expected_size or actual_hash != expected_hash:
                raise SystemExit(f"签名后载荷不匹配：{name}")
            checked[name] = {"bytes": actual_size, "sha256": actual_hash, "status": "通过"}

        with archive.open("payload/system.img.gz", "r") as compressed:
            compressed_size, compressed_hash = sha256_stream(compressed)
        checked["payload/system.img.gz"] = {"bytes": compressed_size, "sha256": compressed_hash, "status": "通过"}
        with archive.open("payload/system.img.gz", "r") as compressed:
            with gzip.GzipFile(fileobj=compressed, mode="rb") as system_image:
                system_size, system_hash = sha256_stream(system_image)
        expected_system_size, expected_system_hash = EXPECTED_SOURCES["partitions/system.img"]
        if system_size != expected_system_size or system_hash != expected_system_hash:
            raise SystemExit("system.img.gz解压后的系统镜像不匹配")
        checked["payload/system.img.gz -> system.img"] = {
            "bytes": system_size,
            "sha256": system_hash,
            "status": "通过",
        }

        unsigned_payload_names = {name for name in names if not name.startswith("META-INF/")}
        expected_payload_names = set(expected) | {
            "payload/system.img.gz",
            "payload/manifest.json",
        }
        unexpected = sorted(unsigned_payload_names - expected_payload_names)
        if unexpected:
            raise SystemExit(f"刷机包出现未登记载荷：{unexpected}")

        app_page = verify_app_page(archive.read("payload/system-patches/config-tab"))
        project_apks = {
            "管理程序": (
                "D31-Wireless-ADB-1.11.6.apk",
                "net.elfradio.d31bootstrap",
                "60",
                "1.11.6-local-rescue-routing",
            ),
            "文件管理器": (
                "D31-File-Manager-1.7.4-d31.2.apk",
                "me.zhanghai.android.files",
                "41",
                "1.7.4-d31.2",
            ),
            "短信程序": (
                "D31-Messages-0.4.0.apk",
                "net.elfradio.d31phone.debug",
                "7",
                "0.4.0-dev-debug",
            ),
            "独立系统支持": (
                "D31-System-Support-1.0.4.apk",
                "net.elfradio.d31system",
                "5",
                "1.0.4",
            ),
        }
        project_apk_results: dict[str, dict[str, str]] = {}
        for label, (filename, package_name, version_code, version_name) in project_apks.items():
            extracted_apk = temporary_path / filename
            with archive.open(f"payload/apps/{filename}", "r") as source, extracted_apk.open("wb") as target:
                while chunk := source.read(4 * 1024 * 1024):
                    target.write(chunk)
            project_apk_results[label] = verify_project_apk(
                extracted_apk,
                args.aapt,
                args.apksigner,
                label,
                package_name,
                version_code,
                version_name,
            )

        getnumber_name = "getnumber-cellular-labels-v1-unsigned.apk"
        extracted_getnumber = temporary_path / getnumber_name
        with archive.open(f"payload/system-patches/{getnumber_name}", "r") as source, extracted_getnumber.open("wb") as target:
            while chunk := source.read(4 * 1024 * 1024):
                target.write(chunk)
        project_apk_results["蜂窝线路显示补丁"] = verify_apk_metadata(
            extracted_getnumber,
            args.aapt,
            "蜂窝线路显示补丁",
            "com.starnet.getnumber",
            "999",
            "1.0.0.5",
        )

        extracted_system = temporary_path / "system.img"
        with archive.open("payload/system.img.gz", "r") as compressed:
            with gzip.GzipFile(fileobj=compressed, mode="rb") as source, extracted_system.open("wb") as target:
                while chunk := source.read(4 * 1024 * 1024):
                    target.write(chunk)
        system_command = [
            "wsl.exe", "-d", "docker-desktop", "--", "sh",
            docker_desktop_path(args.system_verifier),
            docker_desktop_path(extracted_system),
            docker_desktop_path(args.config_template),
        ]
        system_output = run_checked(system_command, "独立挂载核验system镜像")

        manifest = json.loads(archive.read("payload/manifest.json"))
        verify_manifest_files(manifest, checked)

    report["checked_payloads"] = checked
    report["应用页"] = app_page
    report["项目APK"] = project_apk_results
    report["system镜像独立核验"] = {
        "结果": "通过",
        "验证器": str(args.system_verifier.resolve()),
        "输出": system_output.strip(),
    }
    report["result"] = "通过"
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
