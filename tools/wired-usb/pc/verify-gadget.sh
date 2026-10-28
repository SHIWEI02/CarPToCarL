#!/bin/bash
# 一键验证：小米手机（root）起的 USB gadget 能不能被 PC 枚举出来。
#
# 这是整条有线方案的 go/no-go 门：UDC 是独占的，如果抢不下来，
# 「手机端原生 gadget」这条路线就死了，只能改用外接硬件桥。
#
# 前置：手机用 USB 线插着、USB 调试已开、root 有效（KernelSU 硬重启会失效，需重跑 PoC）。
#
# 关键安全规则（2026-10-29 实测踩坑）：
#   gadget 一绑定 UDC，USB adb 立刻断。所以**必须先确认无线 adb 真的通了**才能起 gadget，
#   否则手机直接失联，只能靠物理拔插救回来。脚本把这条做成硬门槛。
#
# 用法：bash verify-gadget.sh

set -e

# 跨平台：按系统推断 adb 位置；也可直接用环境变量覆盖
if [ -z "$ANDROID_HOME" ]; then
    case "$(uname -s)" in
        Darwin)                 ANDROID_HOME="$HOME/Library/Android/sdk" ;;
        MINGW*|MSYS*|CYGWIN*)   ANDROID_HOME="C:/Users/awei/AppData/Local/Android/Sdk" ;;
        *)                      ANDROID_HOME="${ANDROID_SDK_ROOT:-/opt/android-sdk}" ;;
    esac
fi
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb)" || {
    echo "找不到 adb，请设置 ANDROID_HOME 或把 adb 加进 PATH"
    exit 1
}

PORT=5038
HERE="$(cd "$(dirname "$0")" && pwd)"
PHONE_DIR="$HERE/../phone"
PROBE="$HERE/../carplay-probe/target/release/carplay-probe"
[ -x "$PROBE.exe" ] && PROBE="$PROBE.exe"
[ -x "$PROBE" ] || {
    echo "找不到 carplay-probe，先编译：cd $HERE/../carplay-probe && cargo build --release"
    exit 1
}

say() { echo; echo "== $* =="; }

sh_phone() { "$ADB" -P $PORT -s "$SERIAL" shell "$@" 2>&1 | tr -d '\r'; }
su_phone() { "$ADB" -P $PORT -s "$SERIAL" shell su -c "$1" 2>&1 | tr -d '\r'; }

# ---------------------------------------------------------------- 1. USB adb
say "1) 确认 USB adb 在线"
"$ADB" -P $PORT devices | tail -n +2 | grep -v '^$' || {
    echo "手机没连上（USB）。插上手机、开 USB 调试再跑。"
    exit 1
}

# ---------------------------------------------------------------- 2. root 有效？
say "2) 确认 root 有效（KernelSU 硬重启会失效）"
su_phone "id" | head -1
if ! su_phone "id" | grep -q "uid=0"; then
    echo
    echo "root 无效。KernelSU 的 root 在硬重启后会失效，需重跑 CVE-2026-64560 PoC 再试。"
    exit 1
fi

# ---------------------------------------------------------------- 3. 手机 IP
say "3) 取手机 Wi-Fi IP"
IP=$(sh_phone "ip -f inet addr show wlan0 2>/dev/null | grep -oE 'inet [0-9.]+' | head -1" | awk '{print $2}')
if [ -z "$IP" ]; then
    echo "取不到 wlan0 IP —— 手机 Wi-Fi 没连？"
    exit 1
fi
echo "   手机 IP = $IP"

# ---------------------------------------------------------------- 4. 开无线 adb（持久化）
# 2026-10-29 实测：`adb tcpip 5555` 在小米 HyperOS 上不一定让 adbd 真正 listen，
# connect 会超时（10060）。更稳的是写 persist 属性再重启 adbd，并且持久化到重启后。
say "4) 开无线 adb（写 persist 属性 + 重启 adbd）"
su_phone "setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555; stop adbd; start adbd"
sleep 4

# ---------------------------------------------------------------- 5. 连无线【硬门槛】
say "5) 连无线 adb —— 不通就绝不起 gadget"
"$ADB" -P $PORT connect "$IP:5555" >/dev/null 2>&1 || true
sleep 2

WIRELESS_OK=0
for i in 1 2 3; do
    if "$ADB" -P $PORT devices | grep -q "$IP:5555.*device"; then
        WIRELESS_OK=1
        break
    fi
    echo "   第 $i 次未连上，重试..."
    "$ADB" -P $PORT connect "$IP:5555" >/dev/null 2>&1 || true
    sleep 3
done

if [ "$WIRELESS_OK" -ne 1 ]; then
    echo
    echo "无线 adb 连不上 —— 停止，绝不起 gadget（否则手机必失联）。"
    echo "排查："
    echo "  1. 开发者选项里开「无线调试」（Android 11+ 走配对码，和 adb tcpip 是两套）"
    echo "  2. 手机和 PC 是否同一 Wi-Fi / 有无 AP 隔离"
    echo "  3. 手动验证： adb -P $PORT connect $IP:5555"
    exit 1
fi
echo "   无线 adb 已通：$IP:5555"
SERIAL="$IP:5555"

# 再确认一次无线通道下 root 也可用（很多 KernelSU 只在 USB 下授信）
if ! su_phone "id" | grep -q "uid=0"; then
    echo "无线通道下 su 不可用 —— 停止。"
    exit 1
fi

# ---------------------------------------------------------------- 6. 推脚本
say "6) 推送 gadget 脚本到手机"
"$ADB" -P $PORT -s "$SERIAL" push "$PHONE_DIR/gadget-carplay.sh" /sdcard/gadget-carplay.sh | tail -1
"$ADB" -P $PORT -s "$SERIAL" push "$PHONE_DIR/gadget-restore.sh" /sdcard/gadget-restore.sh | tail -1
su_phone "chmod 755 /sdcard/gadget-carplay.sh /sdcard/gadget-restore.sh"
echo "   还原脚本已在手机上： sh /sdcard/gadget-restore.sh"

# ---------------------------------------------------------------- 7. 关 SELinux
say "7) 关 SELinux（configfs 读写需要）"
su_phone "setenforce 0; getenforce"

# ---------------------------------------------------------------- 8. 起 gadget
say "8) 起 gadget（USB adb 会在这里断，之后全靠 $IP:5555）"
su_phone "sh /sdcard/gadget-carplay.sh" || {
    echo "gadget 起失败，看上面输出。还原：sh /sdcard/gadget-restore.sh"
    exit 1
}

# ---------------------------------------------------------------- 9. PC 枚举
say "9) PC 端枚举：找 VID 1209"
sleep 3
"$PROBE" 1209 || {
    echo
    echo "PC 没看到 gadget。排查："
    echo "  - Windows 设备管理器有无新设备（可能显示未知设备，需 Zadig 装 WinUSB）"
    echo "  - 手机端： adb -s $IP:5555 shell su -c 'ip addr'  看有没有 usb0/ncm 网卡"
    echo "  - 手机端： adb -s $IP:5555 shell su -c 'cat /config/usb_gadget/.../UDC'  确认绑定成功"
    echo "  - 还原：   adb -s $IP:5555 shell su -c 'sh /sdcard/gadget-restore.sh'"
    exit 1
}

say "成功：PC 能看到手机起的 gadget，root 抢 UDC 这条路可行"
