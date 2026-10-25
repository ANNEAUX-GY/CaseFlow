# -*- coding: utf-8 -*-
"""GitHub 推送应急隧道（本机 DNS 把 github.com 解析到不可达 IP 时用）。

背景（2026-10-18、2026-10-25 各遇到一次）：
  - 本机 `http_proxy=127.0.0.1:xxxxx` 对 GitHub 一律返回
    `CONNECT tunnel failed, response 502`；
  - DNS 把 github.com 解析到 `20.205.243.166`，该 IP 的 443 直连超时；
  - 但 `140.82.113.3`（GitHub 美区）443 是通的，且 SNI 为 github.com 时证书正常；
  - 非管理员改不了 `hosts`，所以起一个本地 CONNECT 代理把目标固定到可达 IP。

用法：
  1. 起隧道（前台占一个终端，推完 Ctrl+C）
        python app/scripts/gh_tunnel.py
  2. 另开终端推送（务必清掉环境代理，用 -c http.proxy 指到隧道）
        env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
            -u ALL_PROXY -u all_proxy GIT_TERMINAL_PROMPT=0 \
            git -c http.proxy=http://127.0.0.1:18999 push origin main

  IP 变了用 `--ip` 指定；换端口用 `--port`：
        python app/scripts/gh_tunnel.py --ip 140.82.114.4 --port 18999

先探哪些 IP 通（Git Bash）：
        for ip in 140.82.112.3 140.82.113.3 140.82.114.4; do
          (echo > /dev/tcp/$ip/443) 2>/dev/null && echo "$ip ok"; done
"""
import argparse
import select
import socket
import sys
import threading

BUFSIZE = 65536


def pipe(src, dst):
    try:
        while True:
            data = src.recv(BUFSIZE)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client, target_ip):
    try:
        head = b""
        client.settimeout(10)
        while b"\r\n\r\n" not in head:
            chunk = client.recv(BUFSIZE)
            if not chunk:
                return
            head += chunk
        first = head.split(b"\r\n")[0].decode("latin1")
        parts = first.split()
        if len(parts) < 2 or parts[0].upper() != "CONNECT":
            client.sendall(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
            return
        host, _, port = parts[1].partition(":")
        port = int(port or 443)
        upstream = socket.create_connection((target_ip, port), timeout=15)
        # 只回 200，绝不把 CONNECT 报文转发给源站，否则 TLS 握手直接崩
        client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        client.settimeout(None)
        upstream.settimeout(None)
        t = threading.Thread(target=pipe, args=(upstream, client), daemon=True)
        t.start()
        pipe(client, upstream)
        t.join(timeout=30)
    except OSError as e:
        sys.stderr.write("connect error: %s\n" % e)
    finally:
        for s in (client,):
            try:
                s.close()
            except OSError:
                pass


def main():
    ap = argparse.ArgumentParser(description="把 github.com:443 转发到可达 IP 的本地 CONNECT 代理")
    ap.add_argument("--port", type=int, default=18999, help="本地监听端口（默认 18999）")
    ap.add_argument("--ip", default="140.82.113.3", help="GitHub 可达 IP（默认 140.82.113.3）")
    a = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", a.port))
    srv.listen(64)
    print("tunnel ready: 127.0.0.1:%d -> %s:443" % (a.port, a.ip))
    print("push: git -c http.proxy=http://127.0.0.1:%d push origin main" % a.port)
    try:
        while True:
            r, _, _ = select.select([srv], [], [], 1.0)
            if not r:
                continue
            client, _addr = srv.accept()
            threading.Thread(target=handle, args=(client, a.ip), daemon=True).start()
    except KeyboardInterrupt:
        print("\nbye")
    finally:
        srv.close()


if __name__ == "__main__":
    main()
