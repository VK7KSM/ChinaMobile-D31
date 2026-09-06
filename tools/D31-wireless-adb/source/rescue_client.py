"""D31 的8765命令客户端，仅使用Python标准库，不依赖ADB。"""
import argparse
import json
import time
import uuid
import urllib.request


def request(host, path, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request("http://" + host + ":8765" + path, data=data,
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    # 局域网设备不经过电脑的HTTP代理。
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=5) as response:
        return json.load(response)


def execute(host, command, timeout=30, job_id=None):
    job_id = job_id or str(uuid.uuid4())
    result = request(host, "/exec", {"id": job_id, "command": command, "timeout": timeout})
    deadline = time.monotonic() + timeout + 15
    while result["state"] == "running" and time.monotonic() < deadline:
        time.sleep(0.2)
        result = request(host, "/jobs/" + job_id)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", help="D31的局域网IPv4地址")
    parser.add_argument("command", nargs="?", help="省略则只查询健康状态")
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--id", help="同一任务重查时使用原任务号")
    args = parser.parse_args()
    result = execute(args.host, args.command, args.timeout, args.id) if args.command else request(args.host, "/health")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
