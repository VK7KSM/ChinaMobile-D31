"""显式指定设备后执行8765验收；仅临时目录写入，停ADB测试需额外开关。"""
import argparse
import json
import pathlib
import time
import uuid
import urllib.error
from rescue_client import request, execute


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("output")
    parser.add_argument("--adb-interruption", action="store_true")
    args = parser.parse_args()
    out = pathlib.Path(args.output)
    out.mkdir(parents=True, exist_ok=False)
    def save(name, value):
        (out / (name + ".json")).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
        print(name, value.get("state", "ok"), flush=True)
        return value
    health = save("health", request(args.host, "/health"))
    assert health["uid"] == 0
    def run(name, command, timeout=30):
        return save(name, execute(args.host, command, timeout))
    if args.adb_interruption:
        try:
            run("stop-adbd", "setprop ctl.stop adbd")
            time.sleep(2)
            r = run("without-adbd", "getprop init.svc.adbd; echo rescue > /data/local/tmp/d31-rescue-test; cat /data/local/tmp/d31-rescue-test; rm /data/local/tmp/d31-rescue-test")
            assert "stopped" in r["output"] and "rescue" in r["output"]
            save("health-without-adbd", request(args.host, "/health"))
        finally:
            run("restore-adbd", "setprop service.adb.tcp.port 5555; setprop ctl.start adbd")
        return
    assert "uid=0" in run("identity", "id")["output"]
    r = run("write-read", "echo d31-rescue > /data/local/tmp/d31-rescue-test; cat /data/local/tmp/d31-rescue-test; rm /data/local/tmp/d31-rescue-test; exit 7")
    assert r["exit_code"] == 7 and r["output"].strip() == "d31-rescue"
    r = run("output-limit", "busybox yes x | busybox head -c 100000")
    assert r["truncated"] and len(r["output"].encode()) == 65536
    r = run("timeout", "sleep 10", 1)
    assert r["state"] == "timed_out" and r["elapsed_ms"] < 5000
    ident = str(uuid.uuid4())
    body = {"id": ident, "command": "sleep 2; echo once", "timeout": 10}
    save("slow-start", request(args.host, "/exec", body))
    save("same-id", request(args.host, "/exec", body))
    save("health-busy", request(args.host, "/health"))
    try:
        request(args.host, "/exec", {"id": str(uuid.uuid4()), "command": "id", "timeout": 10})
        raise AssertionError("busy accepted")
    except urllib.error.HTTPError as e:
        save("busy-rejection", {"code": e.code, "body": e.read().decode()})
        assert e.code == 409
    time.sleep(3)
    assert save("slow-result", request(args.host, "/jobs/" + ident))["output"].strip() == "once"
    assert run("after-timeout", "echo healthy")["exit_code"] == 0


if __name__ == "__main__":
    main()
