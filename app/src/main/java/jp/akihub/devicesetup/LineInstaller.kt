package jp.akihub.devicesetup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * LINEの導入。
 * QRのおまけ情報 line_apk_urls(カンマ区切りのAPKのURL)があれば、それを落として無言で入れる。
 * 管理端末なので確認画面なしで入る(Android 9以降)。URLが無ければ導入元を開くしかない。
 */
object LineInstaller {
    const val LINE_PKG = "jp.naver.line.android"

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(LINE_PKG, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun configuredUrls(ctx: Context): List<String> =
        Prefs.get(ctx).getString(Prefs.KEY_LINE_URLS, "").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun setStatus(ctx: Context, text: String) {
        Prefs.get(ctx).edit().putString(Prefs.KEY_LINE_STATUS, text).apply()
    }

    /** 別スレッドで導入を試みる。進み具合は Prefs.KEY_LINE_STATUS に書く。 */
    fun installAsync(ctx: Context) {
        val app = ctx.applicationContext
        if (isInstalled(app)) { setStatus(app, "LINEは導入済みです。ログインは手動です。"); return }
        val urls = configuredUrls(app)
        if (urls.isEmpty()) {
            setStatus(app, "LINEは未導入です。QRにLINEのAPKの置き場所(line_apk_urls)が無いため、Playストアから入れてください。")
            return
        }
        setStatus(app, "LINEをダウンロード中…(${urls.size}ファイル)")
        Thread {
            try {
                val files = urls.mapIndexed { i, u -> download(app, u, "line_$i.apk") }
                setStatus(app, "LINEを導入中…")
                install(app, files)
            } catch (e: Exception) {
                setStatus(app, "LINEの導入に失敗しました: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            }
        }.start()
    }

    private fun download(ctx: Context, url: String, name: String): File {
        val f = File(ctx.cacheDir, name)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.connect()
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode} $url")
        conn.inputStream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
        return f
    }

    private fun install(ctx: Context, files: List<File>) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(LINE_PKG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            files.forEachIndexed { i, f ->
                s.openWrite("part$i.apk", 0, f.length()).use { out ->
                    f.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            s.commit(pending.intentSender)
        }
        files.forEach { it.delete() }
    }
}
