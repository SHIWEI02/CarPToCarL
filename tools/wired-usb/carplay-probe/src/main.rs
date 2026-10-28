// carplay-probe: 在 Windows 上枚举 iPhone / CarPlay gadget 的完整 USB 描述符。
//
// 目的：拿到真机的配置与端点布局，作为 Android 端 configfs gadget 的模板。
// 与 MacPlay 的 usb-probe 同源（都用 nusb），但多打端点、并支持触发 CarPlay 配置切换。
//
//   carplay-probe                       列出 05ac 设备并 dump 全部配置/接口/端点
//   carplay-probe 1209                  看我们自己起的 gadget（VID 1209）
//   carplay-probe 05ac --carplay        发 Apple vendor request 0x52 wIndex=4 切 CarPlay 配置
//   carplay-probe 05ac --config 6       直接切到 6 号配置
//   carplay-probe 05ac --carplay --iface 1   指定用哪个接口句柄发控制传输
//
// 注意：--carplay 会让 iPhone 重新枚举（短暂断开，几秒后回来，拔插可完全恢复）。
//
// Windows 上的坑（nusb 源码 device.rs 明确写了）：
//   Device::control_in 在 Windows 不支持 —— 必须先 claim_interface 拿到 Interface 句柄，
//   再用 Interface::control_in 提交。MacPlay 的 request_carplay_configs 用的是 Device 版，
//   所以那段代码在 Windows 上根本编译不过，这是移植时必须改的点之一。

use std::process::ExitCode;
use std::time::Duration;

use nusb::transfer::{ControlIn, ControlType, Recipient};
use nusb::MaybeFuture;

const APPLE_VID: u16 = 0x05ac;

/// Apple 的厂商请求：让 iPhone 重新枚举并暴露 CarPlay 配置。wIndex=4 选 CarPlay 那一组。
/// MacPlay 在 iap2-usbmux/src/linux.rs 用的就是这个。
const CARPLAY_VENDOR_REQ: u8 = 0x52;
const CARPLAY_WINDEX: u16 = 0x0004;

/// MacPlay 里切过去的配置号（iap2-usbmux/src/lib.rs: CP_CONFIG = 6）
const CARPLAY_CONFIG: u8 = 6;

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect::<Vec<_>>().join(" ")
}

fn dump(device: &nusb::Device) {
    let active = device
        .active_configuration()
        .ok()
        .map(|c| c.configuration_value());

    for cfg in device.configurations() {
        let v = cfg.configuration_value();
        let mark = if Some(v) == active { " (active)" } else { "" };
        println!("  config {v}{mark}");
        for desc in cfg.interface_alt_settings() {
            println!(
                "    iface {} alt {} class {:02x}/{:02x}/{:02x}",
                desc.interface_number(),
                desc.alternate_setting(),
                desc.class(),
                desc.subclass(),
                desc.protocol()
            );
            for ep in desc.endpoints() {
                let dir = if ep.address() & 0x80 != 0 { "IN " } else { "OUT" };
                println!(
                    "      ep 0x{:02x} {} maxpkt={} {:?}",
                    ep.address(),
                    dir,
                    ep.max_packet_size(),
                    ep.transfer_type()
                );
            }
        }
    }
}

fn flag_value(args: &[String], flag: &str) -> Option<String> {
    args.iter()
        .position(|a| a == flag)
        .and_then(|i| args.get(i + 1))
        .cloned()
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let do_carplay = args.iter().any(|a| a == "--carplay");
    let iface_num: u8 = flag_value(&args, "--iface")
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);
    let switch_config: Option<u8> = flag_value(&args, "--config").and_then(|v| v.parse().ok());
    let vid = args
        .iter()
        .skip(1)
        .find(|a| !a.starts_with("--"))
        .and_then(|a| u16::from_str_radix(a.trim_start_matches("0x"), 16).ok())
        .unwrap_or(APPLE_VID);

    let Ok(list) = nusb::list_devices().wait() else {
        eprintln!("[carplay-probe] list_devices 失败");
        return ExitCode::FAILURE;
    };
    let infos: Vec<_> = list.filter(|d| d.vendor_id() == vid).collect();
    if infos.is_empty() {
        eprintln!("[carplay-probe] 没有 VID={vid:04x} 的设备");
        return ExitCode::FAILURE;
    }

    for info in infos {
        println!(
            "=== {:04x}:{:04x} {} ===",
            info.vendor_id(),
            info.product_id(),
            info.product_string().unwrap_or("?")
        );
        let Ok(device) = info.open().wait() else {
            eprintln!("  打开失败：多半被系统驱动独占，需要用 Zadig 换成 WinUSB");
            continue;
        };

        if do_carplay || switch_config.is_some() {
            match device.claim_interface(iface_num).wait() {
                Ok(iface) => {
                    if do_carplay {
                        println!(
                            "  经 iface {iface_num} 发 vendor request 0x{CARPLAY_VENDOR_REQ:02x} \
                             wIndex={CARPLAY_WINDEX}（目标 config {CARPLAY_CONFIG}）"
                        );
                        match iface
                            .control_in(
                                ControlIn {
                                    control_type: ControlType::Vendor,
                                    recipient: Recipient::Device,
                                    request: CARPLAY_VENDOR_REQ,
                                    value: 0x0000,
                                    index: CARPLAY_WINDEX,
                                    length: 1,
                                },
                                Duration::from_secs(2),
                            )
                            .wait()
                        {
                            Ok(data) => println!("  成功，返回 {} 字节: {}", data.len(), hex(&data)),
                            Err(e) => println!("  失败: {e}"),
                        }
                        println!("  等 5 秒让它重新枚举…");
                        std::thread::sleep(Duration::from_secs(5));
                        if let Ok(l) = nusb::list_devices().wait() {
                            println!("  重新枚举后：");
                            for d in l.filter(|d| d.vendor_id() == vid) {
                                println!(
                                    "    {:04x}:{:04x} {}",
                                    d.vendor_id(),
                                    d.product_id(),
                                    d.product_string().unwrap_or("?")
                                );
                            }
                        }
                    }
                    drop(iface);
                }
                Err(e) => eprintln!(
                    "  claim interface {iface_num} 失败: {e}（被驱动独占时用 Zadig 换 WinUSB）"
                ),
            }

            if let Some(c) = switch_config {
                match device.set_configuration(c).wait() {
                    Ok(()) => println!("  已切到 config {c}"),
                    Err(e) => println!("  切到 config {c} 失败: {e}"),
                }
            }
        }

        dump(&device);
    }

    ExitCode::SUCCESS
}
