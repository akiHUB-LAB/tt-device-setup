#!/bin/bash
# QRが通らない機種(Play プロテクトが自作の管理アプリを止める機種)を、USB(adb)で仕上げる。
#   使い方: tools/finish_by_usb.sh SERIAL [SERIAL...]   (SERIALは adb devices の左の列)
#   前提: 端末で USBデバッグON、このMacを「許可」済み。
#   やること: TestDPCが管理者なら外して消す → 画面消灯10分・位置情報OFF(どちらも書いた瞬間に効く)
#   音量とサイレントはやらない(2026-10-17 利用者の判断)。USBからは保存値しか書けず再起動が要るが、
#   再起動するとZTE/nubiaはUSBが「充電のみ」に戻って挿し直し・再許可が要り、サイレントも機種によって入らない。
#   音量とサイレントは端末で手で下げる。
set -u
ADB=/Users/aki/Library/Android/sdk/platform-tools/adb
TESTDPC=com.afwsamples.testdpc
[ $# -ge 1 ] || { echo "使い方: $0 SERIAL [SERIAL...]"; exit 1; }

for S in "$@"; do
  echo "===== $S $("$ADB" -s "$S" shell getprop ro.product.model | tr -d '\r') / Android $("$ADB" -s "$S" shell getprop ro.build.version.release | tr -d '\r')"
  if "$ADB" -s "$S" shell pm list packages | tr -d '\r' | grep -q "^package:$TESTDPC$"; then
    echo "-- TestDPCの管理を外して消す"
    "$ADB" -s "$S" shell dumpsys activity service $TESTDPC clear-device-owner >/dev/null 2>&1
    # 外れきるまで最長20秒待つ。Xperia SO-52B は「端末管理アプリ」の指定が残り、USBからは外せなかった(2026-10-17)
    for i in $(seq 1 10); do
      "$ADB" -s "$S" shell dumpsys device_policy 2>/dev/null | grep -q "$TESTDPC" || break
      sleep 2
    done
    if ! "$ADB" -s "$S" uninstall $TESTDPC | grep -q Success; then
      echo "!! TestDPCを消せない(端末管理アプリの指定が残っている)。端末で 設定→セキュリティ→セキュリティの詳細設定→デバイス管理アプリ→Test DPC→「このデバイス管理アプリを無効にする」を押してから、adb uninstall $TESTDPC"
    fi
  fi
  echo "-- 画面消灯10分・位置情報OFF"
  "$ADB" -s "$S" shell settings put system screen_off_timeout 600000
  "$ADB" -s "$S" shell settings put secure location_mode 0
  "$ADB" -s "$S" shell cmd location set-location-enabled false >/dev/null 2>&1
  echo -n "画面消灯(ms)="; "$ADB" -s "$S" shell settings get system screen_off_timeout
  echo -n "位置情報(0=OFF)="; "$ADB" -s "$S" shell settings get secure location_mode
  echo -n "TestDPC残り="; "$ADB" -s "$S" shell pm list packages | grep -c "$TESTDPC"
  echo "音量とサイレントは端末で手で下げてください"
done
