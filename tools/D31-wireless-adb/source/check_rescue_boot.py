"""记录D31重启后的8765可达时间；不写设备文件，重启需显式开关。"""
import argparse
import json
import pathlib
import subprocess
import time
from rescue_client import request, execute


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("output")
    parser.add_argument("--reboot", action="store_true")
    parser.add_argument("--adb", default="C:/Dev/android-sdk/platform-tools/adb.exe")
    args = parser.parse_args()
    out = pathlib.Path(args.output)
    out.mkdir(parents=True, exist_ok=False)
    start = time.monotonic()
    if args.reboot:
        try:
            result = subprocess.run([args.adb, "-P", "5042", "-s", args.host + ":5555", "reboot"], timeout=10,
                                    capture_output=True, text=True)
            (out / "reboot-request.txt").write_text(str(result), encoding="utf-8")
        except subprocess.TimeoutExpired:
            (out / "reboot-request.txt").write_text("重启请求等待超时，只回读状态，不重复重启。", encoding="utf-8")
    with (out / "timeline.jsonl").open("w", encoding="utf-8") as log:
        while time.monotonic() - start < 180:
            record = {"seconds": round(time.monotonic() - start, 2)}
            try:
                record["health"] = request(args.host, "/health")
                # 重启后才验收，避免把尚未断开的旧进程当成新启动。
                if record["health"]["uptime_ms"] < 180000:
                    record["state"] = execute(args.host,
                        "echo BOOT=$(getprop sys.boot_completed); "
                        "ps | grep -E 'net.elfradio.d31bootstrap|com.starnet.nexui|d31-rescue'; "
                        "cat /proc/uptime", 10)
                    print(json.dumps(record, ensure_ascii=False), flush=True)
                    log.write(json.dumps(record, ensure_ascii=False) + "\n"); log.flush()
                    if "BOOT=1" in record["state"]["output"]:
                        return
                    time.sleep(2)
                    continue
            except Exception as error:
                record["error"] = str(error)
            log.write(json.dumps(record, ensure_ascii=False) + "\n"); log.flush()
            time.sleep(1)
    raise SystemExit("未在180秒内同时取得新启动的8765与启动完成状态")


if __name__ == "__main__":
    main()
