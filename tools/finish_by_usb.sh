#!/bin/bash
# QRが通らない機種(Play プロテクトが自作の管理アプリを止める機種)を、USB(adb)で仕上げる。
#   使い方: tools/finish_by_usb.sh SERIAL [SERIAL...]   (SERIALは adb devices の左の列)
#   前提: 端末で USBデバッグON、このMacを「許可」済み。
#   やること: TestDPCが管理者なら外して消す → 画面消灯10分・位置情報OFF・各音量0(アラームは最小)・サイレント → 再起動
#   音量とサイレントは生きた値を adb から変えられない(cmd media_session は効かない。2026-10-15 SH-52E・moto g06)ので、
#   保存値(settings system volume_* / global mode_ringer)を書いて再起動で反映させる。
set -u
ADB=/Users/aki/Library/Android/sdk/platform-tools/adb
TESTDPC=com.afwsamples.testdpc
[ $# -ge 1 ] || { echo "使い方: $0 SERIAL [SERIAL...]"; exit 1; }

for S in "$@"; do
  echo "===== $S $("$ADB" -s "$S" shell getprop ro.product.model | tr -d '\r') / Android $("$ADB" -s "$S" shell getprop ro.build.version.release | tr -d '\r')"
  if "$ADB" -s "$S" shell pm list packages | tr -d '\r' | grep -q "^package:$TESTDPC$"; then
    echo "-- TestDPCの管理を外して消す"
    "$ADB" -s "$S" shell dumpsys activity service $TESTDPC clear-device-owner >/dev/null 2>&1
    sleep 3
    "$ADB" -s "$S" uninstall $TESTDPC
  fi
  echo "-- 設定値を書く"
  "$ADB" -s "$S" shell settings put system screen_off_timeout 600000
  "$ADB" -s "$S" shell settings put secure location_mode 0
  "$ADB" -s "$S" shell cmd location set-location-enabled false >/dev/null 2>&1
  for k in volume_music volume_music_speaker volume_ring volume_ring_speaker \
           volume_notification volume_notification_speaker volume_system volume_system_speaker; do
    "$ADB" -s "$S" shell settings put system $k 0
  done
  "$ADB" -s "$S" shell settings put system volume_alarm 1
  "$ADB" -s "$S" shell settings put system volume_alarm_speaker 1
  "$ADB" -s "$S" shell cmd notification set_dnd off >/dev/null 2>&1
  "$ADB" -s "$S" shell settings put global mode_ringer 0
  echo "-- 再起動して反映"
  "$ADB" -s "$S" reboot
done

echo "起動を待っています…"
sleep 40
for S in "$@"; do
  "$ADB" -s "$S" wait-for-device
  for i in $(seq 1 40); do
    [ "$("$ADB" -s "$S" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 3
  done
  sleep 5
  echo "===== $S 読み返し"
  RM=$("$ADB" -s "$S" shell dumpsys audio 2>/dev/null | grep -m1 "mode (external)" | sed 's/.*= //' | tr -d '\r')
  if [ "$RM" != "SILENT" ]; then
    # 1回目の起動でサイレントにならず保存値が2に戻る機種がある(SHV43 Android 10)。書き直してもう一度再起動すると効く
    echo "サイレントになっていない($RM)。保存値を書き直してもう一度再起動"
    "$ADB" -s "$S" shell settings put global mode_ringer 0
    "$ADB" -s "$S" reboot; sleep 40; "$ADB" -s "$S" wait-for-device
    for i in $(seq 1 40); do
      [ "$("$ADB" -s "$S" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
      sleep 3
    done
    sleep 8
  fi
  echo -n "サイレント: "; "$ADB" -s "$S" shell dumpsys audio 2>/dev/null | grep -m1 "mode (external)" | sed 's/.*= //'
  for st in 2 5 3 4 1; do
    echo -n "音量 stream$st: "
    # Android 11以降は cmd media_session、10以前は media
    V=$("$ADB" -s "$S" shell cmd media_session volume --stream $st --get 2>/dev/null | tr -d '\r' | grep "volume is")
    [ -z "$V" ] && V=$("$ADB" -s "$S" shell media volume --stream $st --get 2>/dev/null | tr -d '\r' | grep "volume is")
    echo "$V" | tail -1 | sed 's/.*volume is //'
  done
  echo -n "画面消灯(ms)="; "$ADB" -s "$S" shell settings get system screen_off_timeout
  echo -n "位置情報(0=OFF)="; "$ADB" -s "$S" shell settings get secure location_mode
  echo -n "USBデバッグ="; "$ADB" -s "$S" shell settings get global adb_enabled
done
