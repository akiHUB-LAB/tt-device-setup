#!/usr/bin/env python3
"""初期設定QR(Android Enterprise の QR provisioning)を作る。

使い方:
  python3 tools/make_qr.py --apk app/build/outputs/apk/release/app-release.apk \
      --url https://github.com/akiHUB-LAB/tt-device-setup/releases/latest/download/device-setup.apk \
      --ssid "Wi-Fi名" --password "Wi-Fiパスワード"

  Wi-Fi は wifi.properties(gitignore済み。ssid=... / password=... / security=WPA)に書いておけば省略できる。
  --line-urls にカンマ区切りでLINEのAPKのURLを渡すと、端末側でLINEを無言導入する。
  --asset-secrets に資産ダッシュボードの secrets.properties を渡すと、資産ダッシュボードも無言導入する
  (QRに入るのはApps ScriptのURLと、アプリの中身を取り出すだけの合言葉。元の合言葉は入れない)。

出力: out/provisioning.json, out/qr.png, out/qr.html(印刷用)
"""
import argparse, base64, hashlib, json, os, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
COMPONENT = "jp.akihub.devicesetup/jp.akihub.devicesetup.AdminReceiver"


def cert_checksum(apk: pathlib.Path) -> str:
    """APKの署名証明書のSHA-256を、QR用(URL安全なbase64、=なし)にする。"""
    sdk = os.environ.get("ANDROID_HOME") or str(pathlib.Path.home() / "Library/Android/sdk")
    tools = sorted((pathlib.Path(sdk) / "build-tools").glob("*"))
    if not tools:
        sys.exit("build-tools が見つかりません。--checksum で指紋(16進)を直接渡してください。")
    apksigner = tools[-1] / "apksigner"
    env = dict(os.environ)
    jbr = pathlib.Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    if "JAVA_HOME" not in env and jbr.exists():
        env["JAVA_HOME"] = str(jbr)  # apksigner は java が要る
    r = subprocess.run([str(apksigner), "verify", "--print-certs", str(apk)],
                       capture_output=True, text=True, env=env)
    if r.returncode != 0:
        sys.exit("apksigner が失敗しました:\n" + r.stderr)
    out = r.stdout
    m = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", out)
    if not m:
        sys.exit("apksigner の出力から指紋を読めませんでした:\n" + out)
    return hex_to_b64url(m.group(1))


def hex_to_b64url(h: str) -> str:
    return base64.urlsafe_b64encode(bytes.fromhex(h)).decode().rstrip("=")


def provision_key(token: str) -> str:
    """取り出し専用の合言葉。資産ダッシュボードの Code.gs の provisionKeyFor_ と同じ式(末尾の v1 を上げると差し替わる)。"""
    return hashlib.sha256(f"{token}:provision-v1".encode("utf-8")).hexdigest()[:32]


def read_props(p: pathlib.Path) -> dict:
    d = {}
    if p.exists():
        for line in p.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                d[k.strip()] = v.strip()
    return d


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", help="署名済みAPK(指紋の計算に使う)")
    ap.add_argument("--checksum", help="署名証明書のSHA-256(16進64桁)。--apk の代わり")
    ap.add_argument("--url", required=True, help="APKの公開URL(https)")
    ap.add_argument("--ssid")
    ap.add_argument("--password")
    ap.add_argument("--security", default=None, help="WPA / WEP / NONE (既定 WPA)")
    ap.add_argument("--line-urls", default="", help="LINEのAPKのURL(カンマ区切り)")
    ap.add_argument("--asset-secrets", default="",
                    help="資産ダッシュボードの secrets.properties(WEB_APP_URL と TOKEN)。渡すと資産ダッシュボードも無言導入する")
    ap.add_argument("--no-wifi", action="store_true", help="Wi-Fi情報を入れない")
    ap.add_argument("--skip-disclaimer", action="store_true",
                    help="「組織が所有するデバイスです」画面を飛ばす(Android 12以降。機種により無視される)")
    ap.add_argument("--out", default=str(ROOT / "out"))
    a = ap.parse_args()

    if a.checksum:
        checksum = hex_to_b64url(a.checksum)
    elif a.apk:
        checksum = cert_checksum(pathlib.Path(a.apk))
    else:
        sys.exit("--apk か --checksum のどちらかが必要です")

    wifi = read_props(ROOT / "wifi.properties")
    ssid = a.ssid or wifi.get("ssid")
    password = a.password or wifi.get("password")
    security = a.security or wifi.get("security") or "WPA"

    payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": COMPONENT,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": a.url,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": checksum,
        "android.app.extra.PROVISIONING_LOCALE": "ja_JP",
        "android.app.extra.PROVISIONING_TIME_ZONE": "Asia/Tokyo",
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
        "android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS": True,
    }
    if a.skip_disclaimer:
        payload["android.app.extra.PROVISIONING_SKIP_OWNERSHIP_DISCLAIMER"] = True
    if not a.no_wifi and ssid:
        payload["android.app.extra.PROVISIONING_WIFI_SSID"] = ssid
        payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = security
        if password:
            payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = password
    extras = {}
    if a.line_urls.strip():
        extras["line_apk_urls"] = a.line_urls.strip()
    if a.asset_secrets:
        sec = read_props(pathlib.Path(a.asset_secrets).expanduser())
        if not sec.get("WEB_APP_URL") or not sec.get("TOKEN"):
            sys.exit(f"{a.asset_secrets} に WEB_APP_URL と TOKEN がありません")
        extras["asset_url"] = sec["WEB_APP_URL"]
        extras["asset_key"] = provision_key(sec["TOKEN"])
    if extras:
        payload["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"] = extras

    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))

    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "provisioning.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    import segno
    qr = segno.make(text, error="m")
    qr.save(str(out / "qr.png"), scale=12, border=4)

    wifi_note = f"Wi-Fi: {ssid}" if (ssid and not a.no_wifi) else "Wi-Fi: なし(手動で接続)"
    html = f"""<!doctype html><meta charset="utf-8"><title>端末初期設定QR</title>
<style>body{{font-family:sans-serif;text-align:center;padding:24px}} img{{width:70vmin;max-width:600px;image-rendering:pixelated}}
ol{{text-align:left;display:inline-block;font-size:18px;line-height:1.7}}</style>
<h1>端末初期設定QR</h1>
<img src="qr.png" alt="QR">
<p>{wifi_note} ／ アプリ: {a.url.split('/')[-1]}</p>
<ol>
<li>端末を初期化し、Googleアカウントが外れていることを確認する</li>
<li>最初の「ようこそ」画面の何もない所を6回タップする</li>
<li>カメラが起動したらこのQRを読む</li>
<li>画面の指示に従う。「端末初期設定」の画面が出たら「セットアップを続行」を押す</li>
<li>ホーム画面が出たら「端末初期設定」アプリを開き、手動項目を仕上げる</li>
</ol>"""
    (out / "qr.html").write_text(html, encoding="utf-8")

    print("QRの中身(JSON):")
    def masked(k, v):
        if "PASSWORD" in k:
            return "*****"
        if isinstance(v, dict):
            return {kk: ("*****" if kk == "asset_key" else vv) for kk, vv in v.items()}
        return v
    print(json.dumps({k: masked(k, v) for k, v in payload.items()}, ensure_ascii=False, indent=2))
    print(f"\n文字数: {len(text)}  →  {out/'qr.png'}  {out/'qr.html'}")


if __name__ == "__main__":
    main()
