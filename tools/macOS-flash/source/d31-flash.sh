#!/bin/bash
# D31（星网锐捷 SVP3390 / 中国移动云视讯）刷机工具 macOS 命令行版 —— 引导层。
#
# 这一层只做一件事：确保 pwsh 与 adb 可用，然后把活交给 d31-flash.ps1。
# 之所以入口必须是 shell 而不是 PowerShell：还没装 PowerShell 的时候没法跑 PowerShell。
#
# 两个运行时都用官方免安装包，解压到本目录的 runtime/ 下：
#   - 不需要 brew、不需要 sudo、不写任何系统目录
#   - curl 下载不带隔离标记，Gatekeeper 不介入（浏览器下载才会带）
#   - 整个工具目录删掉即可彻底清除
# 系统里已经有 pwsh 或 adb 时直接用现成的，不重复下载。

set -euo pipefail

PWSH_VERSION="7.6.6"
PWSH_RELEASE="https://github.com/PowerShell/PowerShell/releases/download/v${PWSH_VERSION}"
PLATFORM_TOOLS_URL="https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$SCRIPT_DIR/runtime"

log()  { printf '%s\n' "$*"; }
die()  { printf '错误：%s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'USAGE'
用法：bash d31-flash.sh --device <IP:端口> [选项]

  --device <IP:端口>      必填。D31 的有线地址与设备侧 ADB 端口，例如 192.168.2.62:5555
  --preflight-only        只做只读检查，不写设备任何分区
  --package-path <路径>   使用已经下好的刷机包，不再下载
  --source <github|cloudflare>  刷机包下载源，默认 github
  --download-dir <目录>   刷机包存放目录，默认为本工具目录下的 downloads
  --yes                   跳过“会清空全部数据”的二次确认
  --help                  显示本说明

前提：D31 必须插网线（刷机会清空 Wi-Fi 密码，只有有线能自动回到网上完成校验）。
      Mac 走 Wi-Fi 即可，只要和 D31 在同一个局域网。
USAGE
}

# 命令行用 macOS 习惯的双横线，PowerShell 只认单横线，在这里翻译。
PS_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --help|-h)        usage; exit 0 ;;
        --device)         [ $# -ge 2 ] || die "--device 缺少取值"; PS_ARGS+=(-Device "$2"); shift 2 ;;
        --package-path)   [ $# -ge 2 ] || die "--package-path 缺少取值"; PS_ARGS+=(-PackagePath "$2"); shift 2 ;;
        --source)         [ $# -ge 2 ] || die "--source 缺少取值"; PS_ARGS+=(-Source "$2"); shift 2 ;;
        --download-dir)   [ $# -ge 2 ] || die "--download-dir 缺少取值"; PS_ARGS+=(-DownloadDirectory "$2"); shift 2 ;;
        --preflight-only) PS_ARGS+=(-PreflightOnly); shift ;;
        --yes)            PS_ARGS+=(-Yes); shift ;;
        --*)              die "无法识别的参数：$1（用 --help 查看用法）" ;;
        -*)               PS_ARGS+=("$1"); shift ;;   # 单横线原样透传给 PowerShell
        *)                die "无法识别的参数：$1（用 --help 查看用法）" ;;
    esac
done

if [ ${#PS_ARGS[@]} -eq 0 ]; then usage; exit 1; fi

# 平台检查放在参数解析之后：--help 在任何系统上都该能看到。
[ "$(uname -s)" = "Darwin" ] || die "本脚本只用于 macOS。Windows 请用图形版刷机工具。"

case "$(uname -m)" in
    arm64)  PWSH_ARCH="osx-arm64" ;;
    x86_64) PWSH_ARCH="osx-x64" ;;
    *)      die "不支持的 CPU 架构：$(uname -m)" ;;
esac

for tool in curl unzip tar shasum iconv; do
    command -v "$tool" >/dev/null 2>&1 || die "系统缺少 $tool，无法继续。"
done

# 先找本目录 runtime/，再找系统 PATH，都没有才下载。
resolve_pwsh() {
    if [ -x "$RUNTIME_DIR/powershell/pwsh" ]; then
        printf '%s' "$RUNTIME_DIR/powershell/pwsh"; return 0
    fi
    if command -v pwsh >/dev/null 2>&1; then
        command -v pwsh; return 0
    fi
    return 1
}

resolve_adb() {
    if [ -x "$RUNTIME_DIR/platform-tools/adb" ]; then
        printf '%s' "$RUNTIME_DIR/platform-tools/adb"; return 0
    fi
    if command -v adb >/dev/null 2>&1; then
        command -v adb; return 0
    fi
    return 1
}

# 官方校验清单是 UTF-16 编码、`<哈希> *<文件名>` 的 Windows 风格，必须转码后再取。
install_pwsh() {
    local archive="powershell-${PWSH_VERSION}-${PWSH_ARCH}.tar.gz"
    local target="$RUNTIME_DIR/powershell"
    local work; work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN

    log "未找到 PowerShell，正在下载官方免安装包（约 68 MB，只需一次）…"
    curl -fL --progress-bar -o "$work/$archive" "$PWSH_RELEASE/$archive" \
        || die "PowerShell 下载失败，请检查网络后重试。"

    local expected actual
    expected="$(curl -fsSL "$PWSH_RELEASE/hashes.sha256" | iconv -f UTF-16 -t UTF-8 | tr -d '\r' \
        | awk -v name="$archive" '$0 ~ name {print tolower($1); exit}')"
    [ -n "$expected" ] || die "取不到 PowerShell 官方校验值，拒绝安装未校验的运行时。"
    actual="$(shasum -a 256 "$work/$archive" | awk '{print tolower($1)}')"
    [ "$expected" = "$actual" ] || die "PowerShell 安装包校验失败，已丢弃。期望 $expected，实际 $actual"

    rm -rf "$target"
    mkdir -p "$target"
    tar -xzf "$work/$archive" -C "$target" || die "PowerShell 解压失败。"
    chmod +x "$target/pwsh"
    log "PowerShell ${PWSH_VERSION} 就绪。"
}

install_adb() {
    local target="$RUNTIME_DIR"
    local work; work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN

    log "未找到 adb，正在下载官方 platform-tools（约 16 MB，只需一次）…"
    curl -fL --progress-bar -o "$work/platform-tools.zip" "$PLATFORM_TOOLS_URL" \
        || die "platform-tools 下载失败，请检查网络后重试。"

    rm -rf "$target/platform-tools"
    mkdir -p "$target"
    unzip -q "$work/platform-tools.zip" -d "$target" || die "platform-tools 解压失败。"
    chmod +x "$target/platform-tools/adb" "$target/platform-tools/fastboot" 2>/dev/null || true
    [ -x "$target/platform-tools/adb" ] || die "解压后没有可执行的 adb。"
    log "adb 就绪。"
}

PWSH="$(resolve_pwsh || true)"
if [ -z "$PWSH" ]; then
    install_pwsh
    PWSH="$(resolve_pwsh)" || die "PowerShell 安装后仍找不到可执行文件。"
fi

ADB="$(resolve_adb || true)"
if [ -z "$ADB" ]; then
    install_adb
    ADB="$(resolve_adb)" || die "adb 安装后仍找不到可执行文件。"
fi

log "PowerShell：$PWSH"
log "adb：$ADB"
log ""

exec "$PWSH" -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_DIR/d31-flash.ps1" -AdbPath "$ADB" "${PS_ARGS[@]}"
