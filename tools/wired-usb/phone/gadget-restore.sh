#!/system/bin/sh
# 还原：拆掉我们的 gadget，把 UDC 还给系统 g1（USB adb 随之恢复）
#
# 用法：adb shell su -c "sh /sdcard/gadget-restore.sh"
# 注意：如果 USB adb 已经断了，就用无线 adb 执行。

set -e

say() { echo "[restore] $*"; }

UDC=$(ls /sys/class/udc 2>/dev/null | head -1)

G=/config/usb_gadget/carplay
if [ -d "$G" ]; then
    echo "" > "$G/UDC" 2>/dev/null || true
    sleep 1
    # 先拆 function 软链，再删目录，避免 rm 卡在 busy 的 function 上
    rm -f "$G"/configs/c.1/* 2>/dev/null || true
    rmdir "$G"/configs/c.1/strings/0x409 2>/dev/null || true
    rmdir "$G"/configs/c.1 2>/dev/null || true
    rmdir "$G"/functions/* 2>/dev/null || true
    rmdir "$G"/strings/0x409 2>/dev/null || true
    rmdir "$G" 2>/dev/null || true
    say "carplay gadget 已拆除"
else
    say "没有 carplay gadget，跳过拆除"
fi

# 把 UDC 还给系统 gadget
for g in /config/usb_gadget/*/; do
    [ -f "$g/UDC" ] || continue
    name=$(basename "$g")
    [ "$name" = "carplay" ] && continue
    say "重新绑定 $g -> $UDC"
    echo "$UDC" > "$g/UDC" 2>/dev/null || say "  $g 绑定失败（可能本来就不该绑定）"
    break
done

sleep 2
say "完成。检查：adb devices 应该能重新看到手机（USB）"
