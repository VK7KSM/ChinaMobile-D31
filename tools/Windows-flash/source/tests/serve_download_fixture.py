"""仅在本机提供固定测试固件，记录Range请求并支持显式结束。"""
import argparse, http.server, json, re, threading
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--file',type=Path,required=True)
p.add_argument('--log',type=Path,required=True)
p.add_argument('--port',type=int,default=18765)
a=p.parse_args()
lock=threading.Lock()
class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def do_HEAD(self): self.send_file(False)
    def do_GET(self):
        if self.path=='/stop':
            self.send_response(200); self.send_header('Content-Length','0'); self.end_headers()
            threading.Thread(target=self.server.shutdown).start(); return
        self.send_file(True)
    def send_file(self,body):
        size=a.file.stat().st_size
        start,end=0,size-1
        requested=self.headers.get('Range')
        if requested:
            m=re.fullmatch(r'bytes=(\d+)-(\d*)',requested)
            if not m: self.send_error(416); return
            start=int(m[1]); end=min(int(m[2]) if m[2] else end,end)
            if start>end: self.send_error(416); return
        with lock:
            with a.log.open('a',encoding='utf-8') as f: f.write(json.dumps({'method':self.command,'range':requested,'start':start,'end':end})+'\n')
        self.send_response(206 if requested else 200)
        self.send_header('Content-Length',str(end-start+1))
        self.send_header('Accept-Ranges','bytes')
        if requested: self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
        self.end_headers()
        if body:
            try:
                with a.file.open('rb') as f:
                    f.seek(start); remaining=end-start+1
                    while remaining:
                        data=f.read(min(262144,remaining))
                        if not data: break
                        self.wfile.write(data); remaining-=len(data)
            except (BrokenPipeError, ConnectionResetError): pass
    def log_message(self,*args): pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',a.port),Handler)
print('本机下载测试服务已就绪',flush=True)
server.serve_forever()
server.server_close()
