# tt-device-setup ― 初期化直後にQRで入る「端末初期設定」アプリ

Android を初期化したあと、最初の「ようこそ」画面で **6回タップ → QR読取** で
このアプリが管理端末(デバイスオーナー)として入り、初期設定を自動で当てます。

## 自動で当たるもの
- 画面消灯 10分
- マスター音量ミュート、メディア/着信/通知/アラーム/システム音量 0
- サイレントモード(機種によっては通知制御の許可が必要 → 画面のボタンから)
- 位置情報 OFF(初期設定ウィザード後にもう一度だけ再適用)
- LINE の無言導入(QRに `line_apk_urls` を入れた場合のみ)

## 手動で仕上げるもの(アプリ内にチェックリストと「開く」ボタンあり)
LINEログイン / 自動回転OFF / 緊急地震速報OFF / 省エネモードON / 機内モードON / Wi-Fi OFF

## QRの作り方
```
python3 tools/make_qr.py --apk app/build/outputs/apk/release/app-release.apk \
  --url https://github.com/akiHUB-LAB/tt-device-setup/releases/latest/download/device-setup.apk \
  --ssid "Wi-Fi名" --password "Wi-Fiパスワード"
```
`out/qr.png` と印刷用 `out/qr.html` ができます。Wi-Fi情報は `wifi.properties` に書いておけば省略できます。
`out/` と `wifi.properties` は git に入りません。

## ビルド
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```
APKは GitHub の Release に `device-setup.apk` という名前で添付します。
QRのURLは `releases/latest/download/device-setup.apk` で固定なので、更新時も同じ名前で添付すればQRはそのままです。

`keystore/release.jks` と `keystore.properties` は git に入れません。
**この鍵を失うとQRが無効になる**(QRの指紋は署名証明書に紐づく)ので、バックアップを保管してください。

## 仕組み
- `AdminReceiver` … デバイスオーナーの受け口。旧フロー(Android 11以前)の完了通知もここ。
- `GetProvisioningModeActivity` … Android 12以降の新フローで「完全管理端末」と答える。
- `MainActivity`(別名 `PolicyComplianceActivity`) … ウィザード中は自動設定を実行して「セットアップを続行」、普段は記録とチェックリスト。
- `SetupRunner` … 設定を当てて読み返す本体。
- `LineInstaller` … PackageInstaller で無言導入。
