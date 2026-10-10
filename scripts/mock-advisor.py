"""Local protocol fixture only; never used as the application's default advisor."""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ready")

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        if self.headers.get("Authorization") != "Bearer local-protocol-test":
            self.send_response(401)
            self.end_headers()
            return
        messages = data.get("messages", [])
        previous = sum(item.get("role") == "assistant" for item in messages)
        text = "[模拟模型联调] 已收到群组参考资料；历史轮数=" + str(previous)
        result = json.dumps({"choices": [{"message": {"content": text}}]}, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(result)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
