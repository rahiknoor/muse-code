#!/usr/bin/env python3
"""SSH ProxyCommand helper: HTTP CONNECT through the runtime egress proxy (port 3130 for Tailscale). Reads proxy config from HTTPS_PROXY env. Usage: ssh_via_tunnel.py <host> <port>"""
import os, sys, socket, base64, select

def main():
    host, port = sys.argv[1], int(sys.argv[2])
    p = __import__("urllib.parse", fromlist=["urlparse"]).urlparse(os.environ.get("HTTPS_PROXY", ""))
    proxy_host = p.hostname
    proxy_port = 3130  # Tailscale tunnel port
    s = socket.create_connection((proxy_host, proxy_port), timeout=20)
    auth = ""
    if p.username:
        creds = f"{p.username}:{p.password or ''}"
        auth = "Proxy-Authorization: Basic " + base64.b64encode(creds.encode()).decode() + "\r\n"
    req = f"CONNECT {host}:{port} HTTP/1.1\r\nHost: {host}:{port}\r\n{auth}\r\n"
    s.sendall(req.encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = s.recv(4096)
        if not chunk:
            sys.exit(1)
        resp += chunk
    if b" 200 " not in resp.split(b"\r\n", 1)[0]:
        sys.stderr.write(resp.decode(errors="replace"))
        sys.exit(1)
    # Bidirectional relay with select
    stdin = sys.stdin.buffer
    stdout = sys.stdout.buffer
    s.setblocking(False)
    os.set_blocking(stdin.fileno(), False)
    while True:
        r, _, _ = select.select([s, stdin], [], [], 60)
        if not r:
            break
        if s in r:
            d = s.recv(65536)
            if not d:
                break
            stdout.write(d)
            stdout.flush()
        if stdin in r:
            d = stdin.read(65536)
            if not d:
                break
            s.sendall(d)

main()
