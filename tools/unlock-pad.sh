#!/usr/bin/env bash
# unlock-pad.sh —— 小米 Pad 6 Pro 锁屏一键解锁（实测可用）
#
# 用法：
#   bash tools/unlock-pad.sh            # 默认密码 xw + 4 个空格
#   UNLOCK_PASS=abc UNLOCK_SPACES=0 bash tools/unlock-pad.sh
#   ADB_BIN=/path/to/adb bash tools/unlock-pad.sh
#
# 设计要点（都是踩过的坑）：
#   1. 不用 screencap 判断界面 —— 这台设备截图恒为黑图，只用 uiautomator dump；
#   2. 必须 MSYS_NO_PATHCONV=1，否则 Git Bash 把 /data/local/tmp/... 转成 Windows 路径；
#   3. 密码框必须先 tap 聚焦再 input text，否则输入丢失、界面不动；
#   4. 用 keyevent 62 打空格（input text 里的空格容易被 shell 吃掉）；
#   5. 全程幂等：已解锁直接退出，失败自动重试并打印界面文字，不瞎猜。

set -u

PASS="${UNLOCK_PASS:-xw}"
SPACES="${UNLOCK_SPACES:-4}"
MAX_TRY="${MAX_TRY:-3}"

export MSYS_NO_PATHCONV=1

# ---------- 找 adb ----------
find_adb() {
    if [ -n "${ADB_BIN:-}" ] && [ -x "$ADB_BIN" ]; then echo "$ADB_BIN"; return; fi
    if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
    for p in \
        "/d/carapp-dev/sdk/platform-tools/adb.exe" \
        "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" \
        "/c/Users/$USERNAME/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
        "/d/xiaomiPad6Pro/UotanToolbox_Windows_x64_3.7.2/Drive/adb.exe" ; do
        [ -x "$p" ] && { echo "$p"; return; }
    done
    return 1
}

ADB="$(find_adb)" || { echo "❌ 找不到 adb，请设置 ADB_BIN=/path/to/adb"; exit 1; }
echo "adb: $ADB"

# ---------- 设备 ----------
"$ADB" wait-for-device || { echo "❌ 设备未连接"; exit 1; }
DEV="$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
[ -n "$DEV" ] || { echo "❌ 没有处于 device 状态的设备（检查 USB 调试/授权）"; exit 1; }
echo "设备: $DEV"

# ---------- 小工具 ----------
is_unlocked() {
    local win focus
    win="$("$ADB" shell dumpsys window 2>/dev/null)"
    printf '%s' "$win" | grep -q "mDreamingLockscreen=true" && return 1
    # 注意：dumpsys 会先输出一行 mCurrentFocus=null，再输出真实窗口，必须取最后一条窗口行
    focus="$(printf '%s' "$win" | grep -oE 'mCurrentFocus=Window\{[^}]*\}' | tail -1)"
    [ -n "$focus" ] || return 1
    case "$focus" in
        *NotificationShade*) return 1 ;;
        *StatusBar*) return 1 ;;
    esac
    return 0
}

wake_screen() {
    local state i
    for i in 1 2 3 4 5; do
        state="$("$ADB" shell dumpsys power 2>/dev/null | grep -m1 -oE 'mWakefulness=[A-Za-z]+' | cut -d= -f2)"
        [ "$state" = "Awake" ] && { echo "电源状态: Awake"; sleep 1; return 0; }
        echo "电源状态: ${state:-unknown}，唤醒中($i)"
        "$ADB" shell input keyevent 224 >/dev/null 2>&1   # WAKEUP（不切换，安全）
        sleep 1
        state="$("$ADB" shell dumpsys power 2>/dev/null | grep -m1 -oE 'mWakefulness=[A-Za-z]+' | cut -d= -f2)"
        if [ "$state" != "Awake" ]; then
            "$ADB" shell input keyevent 26 >/dev/null 2>&1  # POWER 兜底
        fi
        sleep 1
    done
    echo "⚠️ 屏幕未进入 Awake（当前 $state）"
}

screen_size() {
    "$ADB" shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1
}

dump_ui() {
    "$ADB" shell uiautomator dump /data/local/tmp/unlock-ui.xml >/dev/null 2>&1 || return 1
    "$ADB" shell cat /data/local/tmp/unlock-ui.xml 2>/dev/null
}

show_texts() {
    dump_ui | grep -oE 'text="[^"]{1,40}"' | sed 's/text=//' | sort -u | head -12
}

# 返回密码框中心坐标 "x y"，没有则返回空
password_entry_center() {
    local xml b nums
    xml="$(dump_ui)" || return 0
    b="$(printf '%s' "$xml" | grep -oE 'resource-id="com.android.systemui:id/passwordEntry"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
        | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)"
    [ -n "$b" ] || return 0
    nums="$(printf '%s' "$b" | tr -cs '0-9' ' ' | tr -s ' ')"
    # shellcheck disable=SC2086
    set -- $nums
    [ $# -eq 4 ] || return 0
    echo $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

clear_field() {
    "$ADB" shell input keyevent 123 >/dev/null 2>&1   # MOVE_END
    for _ in $(seq 1 40); do "$ADB" shell input keyevent 67 >/dev/null 2>&1; done  # DEL
}

type_password() {
    if [ -n "$PASS" ]; then
        "$ADB" shell input text "$PASS" >/dev/null 2>&1
    fi
    for _ in $(seq 1 "$SPACES"); do
        "$ADB" shell input keyevent 62 >/dev/null 2>&1   # SPACE
    done
    "$ADB" shell input keyevent 66 >/dev/null 2>&1       # ENTER
}

# ---------- 主流程 ----------
if is_unlocked; then
    echo "✅ 已经解锁，无需操作"
    exit 0
fi

SIZE="$(screen_size)"
if [ -n "$SIZE" ]; then
    W="${SIZE%x*}"; H="${SIZE#*x}"
else
    W=1800; H=2880   # Pad 6 Pro 竖屏兜底
fi
X=$((W / 2)); Y1=$((H * 85 / 100)); Y2=$((H * 15 / 100))
echo "屏幕: ${W}x${H}，上滑: ($X,$Y1) -> ($X,$Y2)"

for try in $(seq 1 "$MAX_TRY"); do
    echo "--- 第 $try 次尝试 ---"
    wake_screen

    # 上滑进入 bouncer（无所谓在 AOD 还是通知页）
    "$ADB" shell input swipe "$X" "$Y1" "$X" "$Y2" 300 >/dev/null 2>&1
    sleep 2

    if is_unlocked; then
        echo "✅ 解锁成功（上滑后直接进入系统）"
        exit 0
    fi

    COORD=""
    for _ in 1 2 3 4 5; do
        COORD="$(password_entry_center)"
        [ -n "$COORD" ] && break
        sleep 1
        "$ADB" shell input swipe "$X" "$Y1" "$X" "$Y2" 300 >/dev/null 2>&1
    done

    if [ -z "$COORD" ]; then
        echo "⚠️ 没找到密码框，当前界面文字："
        show_texts
        continue
    fi

    echo "密码框位置: $COORD，点击聚焦并输入密码"
    # shellcheck disable=SC2086
    "$ADB" shell input tap $COORD >/dev/null 2>&1
    sleep 1
    [ "$try" -gt 1 ] && clear_field
    type_password
    sleep 2

    if is_unlocked; then
        echo "✅ 解锁成功"
        exit 0
    fi
    echo "⚠️ 密码似乎未通过，界面文字："
    show_texts
done

echo "❌ 连续 $MAX_TRY 次失败。可手动确认：密码='$PASS'+${SPACES}个空格；界面文字见上。"
exit 1
