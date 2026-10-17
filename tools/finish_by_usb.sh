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
DONE=""

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
  if [ "$("$ADB" -s "$S" shell settings get system screen_off_timeout | tr -d '\r')" = "600000" ] &&
     [ "$("$ADB" -s "$S" shell dumpsys audio 2>/dev/null | grep -m1 "mode (external)" | sed 's/.*= //' | tr -d '\r')" = "SILENT" ]; then
    echo "-- すでに設定済み(消灯10分・サイレント)。再起動せず読み返しだけ"
    DONE="$DONE $S"
    continue
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

echo "起動を待っています(最長2分)…"
sleep 40
# 再起動後に戻ってくるのを最長2分待つ。ZTE/nubiaはUSBが「充電のみ」に戻って見えなくなるので、永遠に待たず報告して次へ
wait_back() {
  local S=$1
  for i in $(seq 1 40); do
    [ "$("$ADB" -s "$S" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && return 0
    sleep 3
  done
  return 1
}
for S in "$@"; do
  case " $DONE " in *" $S "*) ;; *) sleep 0 ;; esac
  if ! wait_back "$S"; then
    echo "===== $S 再起動後にadbから見えない(2分待った)。USBの用途を「ファイル転送」に戻してから、もう一度このスクリプトを実行すると読み返しだけ行う"
    continue
  fi
  sleep 5
  echo "===== $S 読み返し"
  RM=$("$ADB" -s "$S" shell dumpsys audio 2>/dev/null | grep -m1 "mode (external)" | sed 's/.*= //' | tr -d '\r')
  if [ "$RM" != "SILENT" ]; then
    # 1回目の起動でサイレントにならず保存値が2に戻る機種がある(SHV43 Android 10)。書き直してもう一度再起動すると効く
    echo "サイレントになっていない($RM)。保存値を書き直してもう一度再起動"
    "$ADB" -s "$S" shell settings put global mode_ringer 0
    "$ADB" -s "$S" reboot; sleep 40
    if ! wait_back "$S"; then
      echo "===== $S 2回目の再起動後にadbから見えない(2分待った)。USBの用途を「ファイル転送」に戻してから、もう一度実行"
      continue
    fi
    sleep 8
  fi
  echo -n "サイレント: "; "$ADB" -s "$S" shell dumpsys audio 2>/dev/null | grep -m1 "mode (external)" | sed 's/.*= //'
  # 音量はスピーカーの現在値を dumpsys audio から読む(cmd media_session/media は機種・版で出たり出なかったりする)
  "$ADB" -s "$S" shell dumpsys audio 2>/dev/null | tr -d '\r' \
    | grep -A6 -E "^- STREAM_(RING|NOTIFICATION|MUSIC|ALARM|SYSTEM):" \
    | grep -E "^- STREAM|Current" | sed -E 's/^- STREAM_([A-Z]+):/\1/; s/^ *Current: 2 \(speaker\): ([0-9]+).*/  スピーカー=\1/' | paste - - | sed 's/^/音量 /'
  echo -n "画面消灯(ms)="; "$ADB" -s "$S" shell settings get system screen_off_timeout
  echo -n "位置情報(0=OFF)="; "$ADB" -s "$S" shell settings get secure location_mode
  echo -n "USBデバッグ="; "$ADB" -s "$S" shell settings get global adb_enabled
done
