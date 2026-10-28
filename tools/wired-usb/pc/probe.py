"""USB 枚举探针 —— 对标 MacPlay 的 usb-probe，但用 Python 实现。

用途：在把手机端 gadget 起起来之后，验证 PC 到底看不看得见它、能不能 claim 接口、
能不能收发 bulk 数据。这一步过了，才值得往上面叠 iAP2。

用法：
    python probe.py                        # 列出所有 USB 设备
    python probe.py 1209                   # 只看 VID=1209，并 dump 配置/接口/端点
    python probe.py 1209 --rw              # 额外做一次 bulk 写+读试探

Windows 前置（很重要）：
    自定义 VID/PID 不会自动有 WinUSB 驱动，必须先用 Zadig 绑定一次：
      https://zadig.akeo.ie/  ->  Options > List All Devices  ->  选中设备  ->  Install WinUSB
    libusb 后端 dll 由 libusb-package 提供；若导入失败，先：
      pip install pyusb libusb-package
"""

import sys
import usb.core
import usb.util
import usb.backend.libusb1  # noqa: F401  必须显式导入，否则 usb.backend 不可用

# 默认找手机端 gadget-carplay.sh 里用的 VID（0x1209）
DEFAULT_VID = 0x1209


def safe_str(dev, attr):
    """读字符串描述符。Windows 上被其他驱动占用的设备会直接抛错，不能让它炸掉整个清单。"""
    try:
        return getattr(dev, attr) or "?"
    except Exception:
        return "<无权限>"


def get_backend():
    """在 Windows 上找到 libusb-1.0.dll，其他平台交给 pyusb 自己找。"""
    if sys.platform != "win32":
        return None
    try:
        import libusb_package
        return usb.backend.libusb1.get_backend(
            find_library=lambda x: libusb_package.find_library(x)
        )
    except Exception:
        # 退回默认查找（dll 已在 PATH 里时可用）
        return usb.backend.libusb1.get_backend()


def dump_device(dev, do_rw):
    print(f"  厂商={safe_str(dev, 'manufacturer')!r} 产品={safe_str(dev, 'product')!r} 序列号={safe_str(dev, 'serial_number')!r}")
    try:
        cfg = dev.get_active_configuration()
    except Exception as e:
        print(f"  取活动配置失败: {e}")
        print("  -> 多半是没装 WinUSB 驱动，用 Zadig 绑一下")
        return

    print(f"  活动配置 value={cfg.bConfigurationValue}")
    for iface in cfg:
        n = iface.bInterfaceNumber
        print(
            f"    iface {n} alt {iface.bAlternateSetting} "
            f"class {iface.bInterfaceClass:02x}/{iface.bInterfaceSubclass:02x}/"
            f"{iface.bInterfaceProtocol:02x} 端点数={iface.bNumEndpoints}"
        )
        for ep in iface:
            direction = "IN " if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN else "OUT"
            print(
                f"      ep 0x{ep.bEndpointAddress:02x} {direction} "
                f"attr=0x{ep.bmAttributes:02x} maxpkt={ep.wMaxPacketSize}"
            )

    if not do_rw:
        return

    # bulk 收发试探：往第一个 bulk OUT 写 64 字节，再从第一个 bulk IN 读
    bulk_out = bulk_in = None
    for iface in cfg:
        for ep in iface:
            if (ep.bmAttributes & usb.util.ENDPOINT_TYPE_MASK) != usb.util.ENDPOINT_TYPE_BULK:
                continue
            if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN:
                bulk_in = bulk_in or (iface.bInterfaceNumber, ep.bEndpointAddress)
            else:
                bulk_out = bulk_out or (iface.bInterfaceNumber, ep.bEndpointAddress)

    if not bulk_out or not bulk_in:
        print("  没有成对的 bulk 端点，跳过收发试探（纯 NCM 阶段这是正常的）")
        return

    try:
        usb.util.claim_interface(dev, bulk_out[0])
        payload = bytes([0xAA, 0xBB]) + bytes(62)
        written = dev.write(bulk_out[1], payload, timeout=2000)
        print(f"  bulk OUT 0x{bulk_out[1]:02x} 写入 {written} 字节")
    except Exception as e:
        print(f"  bulk OUT 失败: {e}")
        return
    finally:
        try:
            usb.util.release_interface(dev, bulk_out[0])
        except Exception:
            pass

    try:
        usb.util.claim_interface(dev, bulk_in[0])
        data = dev.read(bulk_in[1], 512, timeout=2000)
        print(f"  bulk IN 0x{bulk_in[1]:02x} 收到 {len(data)} 字节: {bytes(data[:32]).hex()}")
    except Exception as e:
        print(f"  bulk IN 无数据（正常，手机端还没人回）: {type(e).__name__}")
    finally:
        try:
            usb.util.release_interface(dev, bulk_in[0])
        except Exception:
            pass


def main():
    args = [a for a in sys.argv[1:]]
    do_rw = "--rw" in args
    args = [a for a in args if not a.startswith("--")]

    vid = DEFAULT_VID
    pid = None
    if args:
        vid = int(args[0], 16)
    if len(args) > 1:
        pid = int(args[1], 16)

    backend = get_backend()
    if backend is None and sys.platform == "win32":
        print("警告：没找到 libusb 后端，pip install libusb-package 后再试")

    found = usb.core.find(find_all=True, backend=backend)
    devices = list(found)
    if not devices:
        print("一个 USB 设备都没找到（后端可能没装好）")
        return

    matches = [d for d in devices if d.idVendor == vid and (pid is None or d.idProduct == pid)]
    print(f"总设备 {len(devices)} 台，VID={vid:04x} 匹配 {len(matches)} 台")

    if not args:
        # 没指定 VID：只列清单
        for d in devices:
            print(f"  {d.idVendor:04x}:{d.idProduct:04x} {safe_str(d, 'manufacturer')} / {safe_str(d, 'product')}")
        print(f"\n提示：默认在找 VID={DEFAULT_VID:04x}。要看别的，把 VID 当参数传进来。")
        return

    for d in matches:
        print(f"\n=== {d.idVendor:04x}:{d.idProduct:04x} ===")
        dump_device(d, do_rw)


if __name__ == "__main__":
    main()
