#!/usr/bin/env python3
"""
通过 Windows 上 iTunes 的 Apple Mobile Device Service (usbmux) 与 iPhone 通信。

为什么走这条路：
  iPhone 被 Apple Mobile Device 驱动独占，nusb/libusb 的 claim_interface 会失败
  （实测 error 50），想直接发 USB 控制传输必须用 Zadig 把驱动换成 WinUSB ——
  那会破坏 iTunes 同步。而 usbmux（127.0.0.1:27015）是 Apple 自己的通道，
  由 Apple Mobile Device Service 提供，**零风险、不需要换驱动**。
  MacPlay 在 macOS 上走的就是同一条路（系统 usbmuxd）。

usbmux 协议（plist 版）：
  包头 16 字节：length(uint32 BE，含包头) / version=1 / msgtype=8(plist) / tag
  载荷：XML plist

用法：
  python usbmux-probe.py            # 列设备 + lockdown QueryType + 尝试 carkit
  python usbmux-probe.py --carkit   # 只尝试 StartService(com.apple.carkit)
"""

import plistlib
import socket
import struct
import sys

MUX_HOST = "127.0.0.1"
MUX_PORT = 27015
LOCKDOWN_PORT = 62078


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("连接已关闭")
        buf += chunk
    return buf


def mux_send(sock, payload, tag=1, msgtype=8):
    body = plistlib.dumps(payload, fmt=plistlib.FMT_XML)
    sock.sendall(struct.pack(">IIII", len(body) + 16, 1, msgtype, tag) + body)


def mux_recv(sock):
    hdr = recv_exact(sock, 16)
    length, version, msgtype, tag = struct.unpack(">IIII", hdr)
    body = recv_exact(sock, length - 16)
    try:
        return plistlib.loads(body), version, msgtype, tag
    except Exception:
        return {"_raw": body[:200]}, version, msgtype, tag


def lockdown_send(sock, payload):
    body = plistlib.dumps(payload, fmt=plistlib.FMT_XML)
    sock.sendall(struct.pack(">I", len(body)) + body)


def lockdown_recv(sock):
    (length,) = struct.unpack(">I", recv_exact(sock, 4))
    body = recv_exact(sock, length)
    try:
        return plistlib.loads(body)
    except Exception:
        return {"_raw": body[:400]}


def main():
    print(f"=== 连接 usbmux {MUX_HOST}:{MUX_PORT} ===")
    sock = socket.create_connection((MUX_HOST, MUX_PORT), timeout=15)
    print("已连接")

    # --- Listen：注册监听，服务端会立刻回当前设备列表 ---
    mux_send(sock, {
        "MessageType": "Listen",
        "ClientID": "carplay-probe",
        "ProgName": "carplay-probe",
    })
    resp, _, _, _ = mux_recv(sock)
    print(f"Listen 结果: {resp.get('MessageType')} = {resp.get('Number')}")

    # 收集 Attached 事件里的设备
    devices = []
    msg = resp
    for _ in range(6):
        if msg.get("MessageType") == "Attached":
            devices.append(msg)
        elif msg.get("MessageType") == "Result" and msg.get("Number") == 0:
            pass
        if devices:
            break
        try:
            msg, _, _, _ = mux_recv(sock)
        except EOFError:
            break

    if not devices:
        print("\n没有发现设备。iPhone 锁屏/未信任此电脑时会出现这种情况。")
        print("响应原文：", resp)
        return

    for d in devices:
        props = d.get("Properties", {})
        print("\n=== 设备 ===")
        print(f"  DeviceID : {d.get('DeviceID')}")
        print(f"  UDID     : {props.get('SerialNumber')}")
        print(f"  连接类型 : {props.get('ConnectionType')}")
        print(f"  ProductID: {props.get('ProductID')}")
        print(f"  LocationID: {props.get('LocationID')}")

    dev = devices[0]
    dev_id = dev.get("DeviceID")

    # --- Connect：连到 lockdown 端口 62078 ---
    # 注意：PortNumber 必须是**网络字节序**（htons），这是 usbmux 众所周知的坑。
    port_net = struct.unpack(">I", struct.pack(">H", LOCKDOWN_PORT)[::-1] + b"\x00\x00")[0]
    print(f"\n=== Connect DeviceID={dev_id} -> lockdown 端口 {LOCKDOWN_PORT} (网络序 {port_net}) ===")
    mux_send(sock, {
        "MessageType": "Connect",
        "DeviceID": dev_id,
        "PortNumber": port_net,
    })
    r, _, _, _ = mux_recv(sock)
    print(f"Connect 结果: Number={r.get('Number')}")
    if r.get("Number") != 0:
        print("连接 lockdown 失败：", r)
        return

    print("已建立 lockdown 直通通道")

    # --- lockdown: QueryType（不需要配对）---
    lockdown_send(sock, {"Request": "QueryType", "Label": "carplay-probe"})
    print("\nQueryType ->", lockdown_recv(sock))

    # --- lockdown: GetValue（设备基本信息）---
    lockdown_send(sock, {
        "Request": "GetValue",
        "Label": "carplay-probe",
        "Domain": None,
        "Key": None,
    })
    v = lockdown_recv(sock)
    if "Value" in v:
        val = v["Value"]
        for k in ("DeviceName", "ProductType", "ProductVersion", "BuildVersion", "UniqueDeviceID"):
            print(f"  {k}: {val.get(k)}")
    else:
        print("GetValue ->", v)

    # --- lockdown: 尝试启动 CarPlay 服务 ---
    # 这一步通常需要配对记录（iTunes 的 pairing record）。
    # 真正要测的就是它：如果 PC 能拿到 carkit 通道，就等于拿到了 iAP2 管道。
    print("\n=== 尝试 StartService(com.apple.carkit) ===")
    lockdown_send(sock, {
        "Request": "StartService",
        "Label": "carplay-probe",
        "Service": "com.apple.carkit",
    })
    s = lockdown_recv(sock)
    print("  ->", {k: s.get(k) for k in ("Request", "Service", "Port", "EnableServiceSSL", "Error")})

    # 顺带看看这个设备到底暴露了哪些服务（对我们判断可行性很有用）
    print("\n=== 尝试 StartService(com.apple.mobile.heartbeat)（验证服务枚举机制）===")
    lockdown_send(sock, {
        "Request": "StartService",
        "Label": "carplay-probe",
        "Service": "com.apple.mobile.heartbeat",
    })
    h = lockdown_recv(sock)
    print("  ->", {k: h.get(k) for k in ("Request", "Service", "Port", "Error")})

    sock.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"失败: {type(e).__name__}: {e}")
        sys.exit(1)
