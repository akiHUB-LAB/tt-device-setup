package jp.akihub.devicesetup

import android.Manifest
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 資産ダッシュボード(com.akikanno.assetdashboard)の導入と入れ替え(2026-10-01)。
 *
 * - QRのおまけ情報 asset_url(資産ダッシュボードのApps ScriptのURL)と asset_key(取り出し専用の合言葉)が
 *   あれば、初期設定の途中でアプリを取り寄せて確認なしで入れる。合言葉はアプリの中身の取り出しにしか使えない
 * - 製造番号を読み、「アプリの設定値」で資産ダッシュボードに渡す。サーバーはそれで初期化前のTT番号に戻す
 * - 資産ダッシュボードの「アプリ更新」は、ここ(UpdateAssetAppReceiver)に頼みが来て、同じ流れで入れ替える。
 *   管理端末が入れるので、自分での入れ替えを断るXiaomiでも通る見込み
 */
object AssetAppInstaller {
    const val PKG = "com.akikanno.assetdashboard"
    const val ACTION_UPDATE = "jp.akihub.devicesetup.UPDATE_ASSET_APP"
    const val EXTRA_KIND = "kind"
    const val KIND_ASSET = "asset"

    @Volatile private var running = false

    fun isConfigured(ctx: Context): Boolean {
        val p = Prefs.get(ctx)
        return !p.getString(Prefs.KEY_ASSET_URL, null).isNullOrBlank() && !p.getString(Prefs.KEY_ASSET_KEY, null).isNullOrBlank()
    }

    fun installedVersion(ctx: Context): Long? = try {
        val info = ctx.packageManager.getPackageInfo(PKG, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun setStatus(ctx: Context, text: String) {
        Prefs.get(ctx).edit().putString(Prefs.KEY_ASSET_STATUS, text).apply()
    }

    /**
     * 製造番号を読み、資産ダッシュボードに渡す(まだ入っていなくても先に渡しておける)。
     * 管理端末は電話の状態を読む許可を自分に与えられ、それで製造番号を読める(Android 10以降の決まり)。
     */
    fun handOverSerial(ctx: Context): String {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val admin = AdminReceiver.component(ctx)
        runCatching {
            dpm.setPermissionGrantState(admin, ctx.packageName, Manifest.permission.READ_PHONE_STATE,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        val serial = runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else Build.SERIAL
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
        runCatching {
            dpm.setApplicationRestrictions(admin, PKG, Bundle().apply {
                putString("serial", serial)
                putString("setup_app", ctx.packageName)
            })
        }
        Prefs.get(ctx).edit().putString(Prefs.KEY_SERIAL, serial).apply()
        return serial
    }

    /** 別スレッドで、入っていなければ入れ、古ければ入れ替える。進み具合は Prefs.KEY_ASSET_STATUS に書く。 */
    fun installAsync(ctx: Context, done: () -> Unit = {}) {
        val app = ctx.applicationContext
        if (!isConfigured(app)) {
            setStatus(app, "資産ダッシュボードは未導入です。QRに置き場所(asset_url/asset_key)がありません。")
            done()
            return
        }
        if (running) { done(); return }
        running = true
        Thread {
            try {
                run(app)
            } catch (e: Exception) {
                setStatus(app, "資産ダッシュボードの導入に失敗しました: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            } finally {
                running = false
                done()
            }
        }.start()
    }

    private fun run(ctx: Context) {
        val p = Prefs.get(ctx)
        val base = p.getString(Prefs.KEY_ASSET_URL, "")!!.trim()
        val key = p.getString(Prefs.KEY_ASSET_KEY, "")!!.trim()
        val serial = handOverSerial(ctx)
        setStatus(ctx, "資産ダッシュボードの版を確認中…(製造番号 …${serial.takeLast(4)})")
        val info = getJson(AssetAppPlan.infoUrl(base, key), 30_000)
        if (!info.optBoolean("ok", false)) {
            setStatus(ctx, "資産ダッシュボードの版を確認できませんでした: ${info.optString("error")}")
            return
        }
        val versionCode = info.optLong("version_code", 0)
        val build = info.optString("build", "")
        if (!AssetAppPlan.needsInstall(installedVersion(ctx), versionCode)) {
            setStatus(ctx, "資産ダッシュボードは最新です($build)。")
            return
        }
        val chunks = info.optInt("chunks", 0)
        val out = ByteArrayOutputStream(info.optLong("size", 0).toInt().coerceAtLeast(0))
        for (i in 0 until chunks) {
            setStatus(ctx, "資産ダッシュボードを取り寄せ中…(${i + 1}/$chunks)")
            val chunk = getJson(AssetAppPlan.chunkUrl(base, key, versionCode, i), 120_000)
            val data = chunk.optString("data", "")
            if (!chunk.optBoolean("ok", false) || data.isEmpty()) {
                setStatus(ctx, "資産ダッシュボードの取り寄せに失敗しました(${i + 1}/$chunks): ${chunk.optString("error")}")
                return
            }
            out.write(Base64.decode(data, Base64.DEFAULT))
        }
        val bytes = out.toByteArray()
        AssetAppPlan.verify(bytes, info.optLong("size", 0), info.optString("sha256", ""))?.let {
            setStatus(ctx, "資産ダッシュボードの中身が壊れていました: $it")
            return
        }
        // 取り寄せの間に別の経路(USB等)で新しい版が入っていたら、古い版で上書きしない(2026-10-01 TT58で起きた)。
        if (!AssetAppPlan.needsInstall(installedVersion(ctx), versionCode)) {
            setStatus(ctx, "資産ダッシュボードは最新です(取り寄せ中に新しい版が入っていた)。")
            return
        }
        // 初めて入れるときだけ、入れたあとに起動する(更新のときは資産ダッシュボードが自分で起き直す)。
        p.edit().putBoolean(Prefs.KEY_ASSET_LAUNCH_AFTER, installedVersion(ctx) == null).apply()
        setStatus(ctx, "資産ダッシュボードを導入中…($build)")
        install(ctx, bytes)
    }

    private fun install(ctx: Context, bytes: ByteArray) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(PKG)
        params.setSize(bytes.size.toLong())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, bytes.size.toLong()).use { o ->
                o.write(bytes)
                s.fsync(o)
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java).putExtra(EXTRA_KIND, KIND_ASSET)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    /**
     * 入った直後の仕上げ。通知の許可を与え、ユーザー補助を入れられるか試す(管理端末でも
     * 他のアプリのユーザー補助は入れられない見込み。だめなら端末で手で入れる)。初めてなら起動する。
     */
    fun afterInstalled(ctx: Context) {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val admin = AdminReceiver.component(ctx)
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                dpm.setPermissionGrantState(admin, PKG, Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
        val service = "$PKG/$PKG.BalanceAccessibilityService"
        val a11y = runCatching {
            @Suppress("DEPRECATION")
            dpm.setSecureSetting(admin, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, service)
            @Suppress("DEPRECATION")
            dpm.setSecureSetting(admin, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            "ユーザー補助を自動で入れました。"
        }.getOrElse { "ユーザー補助は端末で手で入れてください(${it.javaClass.simpleName})。" }
        setStatus(ctx, "資産ダッシュボードは導入済みです。$a11y")
        val p = Prefs.get(ctx)
        if (p.getBoolean(Prefs.KEY_ASSET_LAUNCH_AFTER, false)) {
            p.edit().putBoolean(Prefs.KEY_ASSET_LAUNCH_AFTER, false).apply()
            ctx.packageManager.getLaunchIntentForPackage(PKG)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(it) }
            }
        }
    }

    /**
     * サーバー(Apps Script)は、何も悪くなくても4回に1回ほど、JSONの代わりにGoogleのエラーの画面
     * (「ドライブ: 現在、ファイルを開くことができません」)を返す(2026-10-03 実測)。取り寄せは6回問い合わせるので、
     * 1回の失敗で止めると2割ほどしか入らなかった(A203SO・XIG07)。失敗した問い合わせだけ、間をあけて数回やり直す。
     */
    private fun getJson(url: String, readTimeoutMs: Int): JSONObject {
        var last: Exception? = null
        for (attempt in 1..GET_ATTEMPTS) {
            try {
                return getJsonOnce(url, readTimeoutMs)
            } catch (e: Exception) {
                last = e
                if (attempt < GET_ATTEMPTS) Thread.sleep(RETRY_WAIT_MS * attempt)
            }
        }
        throw last ?: IllegalStateException("サーバーに問い合わせできなかった")
    }

    private const val GET_ATTEMPTS = 5
    private const val RETRY_WAIT_MS = 4_000L

    private fun getJsonOnce(url: String, readTimeoutMs: Int): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = readTimeoutMs
        return try {
            val body = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}
