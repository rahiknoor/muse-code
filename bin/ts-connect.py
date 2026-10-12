#!/usr/bin/env python3
"""ProxyCommand helper: reach a Tailscale address through the runtime tunnel proxy.

Usage in ssh:
    ssh -o ProxyCommand="/home/hatch/workspace/bin/ts-connect.py %h %p" user@host

Reads TUNNEL_PROXY env var (http://user:pass@proxy-host:3130). Performs an
HTTP CONNECT with Proxy-Authorization, then relays bytes between the tunnel
and stdio. TCP only.
"""
import base64
import os
import socket
import sys
import threading
from urllib.parse import urlparse


def main() -> int:
    if len(sys.argv) != 3:
        sys.stderr.write("usage: ts-connect.py <host> <port>\n")
        return 2
    target_host, target_port = sys.argv[1], int(sys.argv[2])

    proxy_url = os.environ.get("TUNNEL_PROXY", "")
    px = urlparse(proxy_url)
    if not px.hostname or not px.port:
        sys.stderr.write("TUNNEL_PROXY env var missing or invalid\n")
        return 2

    auth = base64.b64encode(f"{px.username}:{px.password}".encode()).decode()
    sock = socket.create_connection((px.hostname, px.port), timeout=60)
    req = (
        f"CONNECT {target_host}:{target_port} HTTP/1.1\r\n"
        f"Host: {target_host}:{target_port}\r\n"
        f"Proxy-Authorization: Basic {auth}\r\n"
        f"\r\n"
    )
    sock.sendall(req.encode())

    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = sock.recv(4096)
        if not chunk:
            sys.stderr.write("proxy closed connection during CONNECT\n")
            return 1
        resp += chunk
    status_line = resp.split(b"\r\n", 1)[0]
    if b" 200" not in status_line:
        sys.stderr.write(f"proxy CONNECT failed: {status_line!r}\n")
        return 1

    stdin_fd = sys.stdin.fileno()
    stdout_fd = sys.stdout.fileno()

    def pump(src_fd: int, dst_sock: socket.socket):
        try:
            while True:
                data = os.read(src_fd, 65536)
                if not data:
                    break
                dst_sock.sendall(data)
        except OSError:
            pass

    def pump_back(src_sock: socket.socket, dst_fd: int):
        try:
            while True:
                data = src_sock.recv(65536)
                if not data:
                    break
                os.write(dst_fd, data)
        except OSError:
            pass

    t = threading.Thread(target=pump, args=(stdin_fd, sock), daemon=True)
    t.start()
    pump_back(sock, stdout_fd)
    return 0


if __name__ == "__main__":
    sys.exit(main())
