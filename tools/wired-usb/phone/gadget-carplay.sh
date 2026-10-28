#!/system/bin/sh
# 阶段 1：枚举验证 —— 让 PC 能看到一台「CarPlay 手机」
#
# 设计要点：
# 1. 只用内核自带 function（ncm），不需要任何用户态程序 / FFS daemon。
#    只要 PC 端能枚举出这个设备，就证明 root 抢 UDC 这条路走得通。
# 2. 故意用非 Apple 的 VID（0x1209 = pid.codes 测试用）。
#    原因：Windows 见到 0x05ac(Apple) 会自动装 Apple Mobile Device 驱动，
#    那个驱动会独占设备，libusb/WinUSB 就拿不到句柄。等枚举验证通过、
#    需要真接车机时，再换成 0x05ac 并用 Zadig 强制绑 WinUSB。
# 3. UDC 是独占的，且 Android 的 USB Gadget HAL 会跟我们抢：
#    解绑 g1 后 HAL 检测到 UDC 空闲会立刻重新绑回去。
#    对策：先 setprop sys.usb.config none 让 HAL 停手，再建 gadget。
#
# 用法：
#   adb shell su -c "AUTO_RESTORE_SEC=90 sh /sdcard/gadget-carplay.sh"
# 说明：不用先开无线 adb —— 脚本自带定时自动还原安全网（见下）。

LOG=/sdcard/gadget.log
AUTO_RESTORE_SEC="${AUTO_RESTORE_SEC:-90}"

log() { echo "[$(date +%H:%M:%S)] $*" >> "$LOG"; }
# 关键：每一步都要落盘，因为 USB adb 会在解绑 g1 的瞬间断开，
# 之后 stdout 就再也看不到了。日志是唯一的取证手段。
run() {
    desc="$1"; shift
    if out=$("$@" 2>&1); then
        log "OK   $desc"
        [ -n "$out" ] && log "     -> $out"
        return 0
    else
        rc=$?
        log "FAIL $desc (rc=$rc)"
        [ -n "$out" ] && log "     -> $out"
        return $rc
    fi
}

: > "$LOG"
log "=============================="
log "gadget 启动，AUTO_RESTORE_SEC=$AUTO_RESTORE_SEC"
log "=============================="

# SELinux 放行：KernelSU 的 ksu 域默认没有 configfs 写权限
run "setenforce 0" setenforce 0
log "SELinux: $(getenforce 2>&1)"

# --- 让 Android 的 USB Gadget HAL 停手，否则它会把 UDC 抢回去 ---
log "--- 停 USB HAL ---"
run "setprop sys.usb.config none" setprop sys.usb.config none
run "setprop sys.usb.state none" setprop sys.usb.state none
run "setprop persist.sys.usb.config none" setprop persist.sys.usb.config none
sleep 1
log "sys.usb.config=$(getprop sys.usb.config 2>&1) state=$(getprop sys.usb.state 2>&1)"

UDC=$(ls /sys/class/udc 2>/dev/null | head -1)
if [ -z "$UDC" ]; then
    log "找不到 UDC 控制器，放弃"
    exit 1
fi
log "UDC = $UDC"

# 解绑所有现有 gadget（这一步之后 USB adb 断开）
for g in /config/usb_gadget/*/; do
    [ -f "$g/UDC" ] || continue
    cur=$(cat "$g/UDC" 2>/dev/null)
    if [ -n "$cur" ]; then
        log "解绑 $g (was $cur)"
        echo "" > "$g/UDC" 2>/dev/null && log "  已解绑" || log "  解绑失败"
    fi
done
sleep 1

G=/config/usb_gadget/carplay
[ -d "$G" ] && rm -rf "$G" 2>/dev/null
run "mkdir $G" mkdir -p "$G"

# --- 设备描述符 ---
run "idVendor"  sh -c "echo 0x1209 > $G/idVendor"
run "idProduct" sh -c "echo 0x0001 > $G/idProduct"
run "bcdDevice" sh -c "echo 0x0100 > $G/bcdDevice"
run "bcdUSB"    sh -c "echo 0x0200 > $G/bcdUSB"

run "mkdir strings" mkdir -p "$G/strings/0x409"
run "manufacturer" sh -c "echo CarPlayPhone > $G/strings/0x409/manufacturer"
run "product"      sh -c "echo CarPlayPhone > $G/strings/0x409/product"
run "serialnumber" sh -c "echo 000000001234567890abcdef > $G/strings/0x409/serialnumber"

# --- 配置 ---
run "mkdir configs" mkdir -p "$G/configs/c.1/strings/0x409"
run "configuration" sh -c "echo CarPlay > $G/configs/c.1/strings/0x409/configuration"
run "MaxPower"      sh -c "echo 500 > $G/configs/c.1/MaxPower"

# --- function: NCM ---
# 实例名可能冲突，逐个试。PC 端会看到一个 CDC NCM 网卡。
FN=""
for name in ncm.usb0 ncm.0 ncm.gs0; do
    if mkdir -p "$G/functions/$name" 2>/dev/null; then
        FN="$name"
        log "function 实例名可用: $name"
        break
    else
        log "function 实例名不可用: $name"
    fi
done
if [ -z "$FN" ]; then
    log "所有 NCM 实例名都创建失败，放弃"
    dmesg 2>/dev/null | tail -15 >> "$LOG"
    exit 1
fi

run "链接 function 到 config" ln -s "$G/functions/$FN" "$G/configs/c.1/"

# --- 上电枚举 ---
log "--- 绑定 UDC ---"
if echo "$UDC" > "$G/UDC" 2>/dev/null; then
    log "已写入 UDC"
else
    log "写入 UDC 失败: $?"
fi
sleep 3

BIND=$(cat "$G/UDC" 2>/dev/null)
log "绑定后 $G/UDC = [$BIND]"
log "VID=$(cat "$G/idVendor" 2>/dev/null) PID=$(cat "$G/idProduct" 2>/dev/null)"
{
    echo "--- ip addr (usb/ncm) ---"
    ip -o addr show 2>/dev/null | grep -iE "usb0|ncm"
    echo "--- dmesg 尾部 ---"
    dmesg 2>/dev/null | tail -20
} >> "$LOG" 2>&1

# ---------------------------------------------------------------------------
# 安全网：定时自动还原（setsid 脱离 adb shell 进程组，否则会被一起杀掉）
# ---------------------------------------------------------------------------
if [ "$AUTO_RESTORE_SEC" -gt 0 ]; then
    setsid sh -c "sleep $AUTO_RESTORE_SEC; sh /sdcard/gadget-restore.sh" >> "$LOG" 2>&1 &
    log "已安排 ${AUTO_RESTORE_SEC}s 后自动还原 (PID=$!)"
fi

log "===== 脚本结束 ====="
