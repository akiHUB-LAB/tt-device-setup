#!/bin/bash
# 挿したら自動で仕上げる見張り(2026-10-17)。利用者がやるのは「QR→USBデバッグON→挿して許可」だけにする。
#   使い方: tools/auto_onboard.sh            見張りを始める(Ctrl+C か tools/auto_onboard.sh --stop で止める)
#           tools/auto_onboard.sh --once     今つながっている端末を1回だけ処理して終わる
#   対象: TestDPC が入っている端末だけ(=QRで初期化した直後)。TikTokの実行などでつないだ普段の端末には触らない。
#   1台ごとに並行で: finish_by_usb.sh(TestDPC削除・消灯10分・位置情報OFF)→ TT番号を製造番号から引く →
#   資産ダッシュボード導入と番号登録(onboard_device.sh、TikTok専用機・MOVE=1)→ 音量0とサイレント(アプリが対応していれば)
#   TT番号の引き方: 1) 手元の控え(serial_tt.tsv) 2) サーバーの製造番号末尾4桁+機種 3) 分からなければMacに入力欄を出す
#   知らせは Mac の通知と、~/Library/Logs/tt-onboard.log
set -u
ADB=/Users/aki/Library/Android/sdk/platform-tools/adb
HERE="$(cd "$(dirname "$0")" && pwd)"
ASSET="$HOME/Projects/paypay-dashboard"
DATA="$HOME/Library/Application Support/tt-onboard"
MAP="$DATA/serial_tt.tsv"
APK="$DATA/asset-std.apk"
LOG="$HOME/Library/Logs/tt-onboard.log"
PIDFILE="$DATA/watch.pid"
TESTDPC=com.afwsamples.testdpc
mkdir -p "$DATA"; touch "$MAP"

if [ "${1:-}" = "--stop" ]; then
  [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null && echo "止めました" || echo "動いていません"
  rm -f "$PIDFILE"; exit 0
fi

log() { echo "$(date '+%m/%d %H:%M:%S') $*" | tee -a "$LOG"; }
notify() { osascript -e "display notification \"$2\" with title \"$1\" sound name \"Glass\"" >/dev/null 2>&1; log "[通知] $1: $2"; }

# 標準の資産ダッシュボード(サーバー配布版)を1日1回取り直す
refresh_apk() {
  if [ ! -s "$APK" ] || [ -n "$(find "$APK" -mtime +0 2>/dev/null)" ]; then
    log "資産ダッシュボードの標準版を取り寄せ"
    python3 "$ASSET/android-app/tools/fetch_server_apk.py" "$APK.tmp" >>"$LOG" 2>&1 && mv "$APK.tmp" "$APK"
  fi
}
apk_build() { "$HOME/Library/Android/sdk/build-tools/34.0.0/aapt" dump badging "$APK" 2>/dev/null | grep -oE "versionName='[^']+'" | cut -d"'" -f2; }

# 製造番号 → TT番号
lookup_tt() {
  local S=$1 MODEL=$2 TT
  TT=$(awk -F'\t' -v s="$S" '$1==s{print $2; exit}' "$MAP")
  [ -n "$TT" ] && { echo "$TT"; return; }
  local URL TOKEN
  URL=$(grep -oE 'https://script\.google\.com/macros/s/[^"]+/exec' "$ASSET/pc-dashboard/config.js")
  TOKEN=$(sed -n 's/^TOKEN=//p' "$ASSET/android-app/secrets.properties")
  TT=$(curl -sL -m 90 -G "$URL" --data-urlencode action=dashboard --data-urlencode "token=$TOKEN" | python3 -c "
import json,sys
s,m=sys.argv[1].lower(),sys.argv[2]
try: d=json.load(sys.stdin)
except Exception: sys.exit()
hits={x['ttId'] for x in d.get('items',[]) if x.get('ttId') and x.get('serialTail') and s.endswith(str(x['serialTail']).lower())
      and (not x.get('deviceModel') or x['deviceModel']==m)}
if len(hits)==1: print(hits.pop())" "$S" "$MODEL")
  [ -n "$TT" ] && { echo "$TT"; return; }
  # 分からない(新しく買った端末など)→ Mac に入力欄。10分で諦める
  TT=$(osascript -e "text returned of (display dialog \"新しい端末です: $MODEL (製造番号 $S)\nTT番号を入れてください(例 TT71)\" default answer \"TT\" with title \"端末の自動仕上げ\" giving up after 600)" 2>/dev/null | tr -d ' ' | tr '[:lower:]' '[:upper:]')
  echo "$TT" | grep -qE '^TT[0-9]{2,3}$' && echo "$TT"
}

# 音量0とサイレント。資産ダッシュボードに入口ができたらここで呼ぶ(依頼中、2026-10-17)
quiet() {
  local S=$1
  [ -f "$DATA/quiet_cmd" ] || return 1
  "$ADB" -s "$S" shell cmd notification allow_dnd com.akikanno.assetdashboard >/dev/null 2>&1
  local CMD; CMD=$(cat "$DATA/quiet_cmd")
  "$ADB" -s "$S" shell "$CMD" 2>&1 | tr -d '\r' | tail -2 | tee -a "$LOG" | grep -q "QUIET ok"
}

process() {
  local S=$1 MODEL TT B
  MODEL=$("$ADB" -s "$S" shell getprop ro.product.model | tr -d '\r')
  notify "$MODEL" "見つけました。仕上げを始めます"
  "$HERE/finish_by_usb.sh" "$S" >>"$LOG" 2>&1
  if "$ADB" -s "$S" shell pm list packages | grep -q "$TESTDPC"; then
    notify "$MODEL" "TestDPCを消せません。設定→セキュリティ→デバイス管理アプリ→Test DPC→無効にする を押してください"
    for i in $(seq 1 60); do sleep 5; "$ADB" -s "$S" uninstall $TESTDPC 2>/dev/null | grep -q Success && break; done
  fi
  TT=$(lookup_tt "$S" "$MODEL")
  if [ -z "$TT" ]; then notify "$MODEL" "TT番号が分からないので止めました(製造番号 $S)"; return 1; fi
  log "$S → $TT"
  B=$(apk_build)
  local OUT
  OUT=$(cd "$ASSET" && APK="$APK" BUILD="$B" MOVE=1 android-app/tools/onboard_device.sh "$S" "$TT" --tiktok-only 2>&1)
  echo "$OUT" | sed "s/^/[$TT] /" >> "$LOG"
  if echo "$OUT" | grep -q '"ok":true'; then
    grep -q "^$S	" "$MAP" || printf '%s\t%s\t%s\n' "$S" "$TT" "$MODEL" >> "$MAP"
    if quiet "$S"; then notify "$TT($MODEL)" "完了。抜いてOK"
    else notify "$TT($MODEL)" "完了。音量とサイレントだけ手で下げて、抜いてOK"; fi
  else
    notify "$TT($MODEL)" "番号登録でつまずきました。ログを見てください"
  fi
}

if [ "${1:-}" = "--lookup" ]; then lookup_tt "$2" "$3"; exit; fi   # 試し: --lookup 製造番号 機種
refresh_apk
ONCE=false; [ "${1:-}" = "--once" ] && ONCE=true
$ONCE || echo $$ > "$PIDFILE"
log "見張りを開始"
SEEN=" "
while :; do
  while read -r S STATE; do
    [ -z "$S" ] && continue
    if [ "$STATE" = unauthorized ]; then
      case "$SEEN" in *" U$S "*) ;; *) notify "端末" "「USBデバッグを許可」を押してください"; SEEN="$SEEN U$S ";; esac
      continue
    fi
    [ "$STATE" = device ] || continue
    case "$SEEN" in *" $S "*) continue;; esac
    if "$ADB" -s "$S" shell pm list packages 2>/dev/null | grep -q "$TESTDPC"; then
      SEEN="$SEEN $S "
      process "$S" &
    fi
  done < <("$ADB" devices | awk 'NR>1 && NF>=2{print $1, $2}')
  $ONCE && { wait; break; }
  # 抜かれた端末は覚えを消す(同じ端末を初期化し直して挿したら、また処理する)
  NOW=" $("$ADB" devices | awk 'NR>1{print $1}' | tr '\n' ' ') "
  for X in $SEEN; do Y=${X#U}; case "$NOW" in *" $Y "*) ;; *) SEEN=${SEEN/ $X / };; esac; done
  sleep 3
done
log "見張りを終了"
