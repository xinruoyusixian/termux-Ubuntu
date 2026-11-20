@echo off
chcp 65001 >nul
title Ubuntu 24.04 chroot - Xiaomi Pad 6 Pro
set ADB=D:\xiaomiPad6Pro\UotanToolbox_Windows_x64_3.7.2\Bin\platform-tools\adb.exe

echo ============================================
echo   Ubuntu 24.04 arm64 chroot
echo   小米 Pad 6 Pro / LineageOS / KernelSU
echo ============================================
echo.
echo 提示: 平板需已开启 USB 调试并授权本电脑
echo 退出 Ubuntu 请输入: exit
echo ============================================
echo.

"%ADB%" devices
echo.

:: shell 交互模式进入；输入 exit 退出 Ubuntu，再输入 exit 退出 adb shell
"%ADB%" shell

pause
