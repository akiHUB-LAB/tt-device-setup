package jp.akihub.devicesetup

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「管理を外す」(2026-10-09 利用者の希望)。
 *
 * 管理端末のままだと、Google側が「仕事用アカウント」の画面に切り替え、個人のGoogleアカウントを作れない・
 * Playストアに個人でログインできない(LINEも入れられなかった)。そこで、初期設定が終わったら
 * 管理端末をやめて普通のスマホに戻すボタンを用意する。
 *
 * 一方通行: Androidは、初期化直後(またはアカウントが1つも無い状態)でしか管理端末にできない。
 * 外してGoogleアカウントを入れたら、もう一度管理端末にするには初期化してQRを読み直すしかない。
 *
 * 外しても残るもの: 音量0・位置情報OFF・消灯10分(どれも普通の設定値なので残る)、資産ダッシュボード本体。
 * 外すと消えるもの: マスターミュート(各音量は0のまま)、資産ダッシュボードの無言入れ替え(以後は資産ダッシュボードが
 * 自分で入れ替える。管理端末を使う前と同じ動き)。
 */
object Unmanage {

    /** 外す。うまくいけば null、だめなら理由を返す。 */
    fun release(ctx: Context): String? {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val admin = AdminReceiver.component(ctx)
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) return "この端末は管理端末ではありません。"

        // 資産ダッシュボードには製造番号だけ残し、入れ替えの頼み先(setup_app)は消す。
        // 頼み先が残っていると、外したあとも資産ダッシュボードがこのアプリに入れ替えを頼み、無言で入れられずに止まる。
        runCatching {
            val serial = Prefs.get(ctx).getString(Prefs.KEY_SERIAL, null)
            dpm.setApplicationRestrictions(admin, AssetAppInstaller.PKG, Bundle().apply {
                if (!serial.isNullOrBlank()) putString("serial", serial)
            })
        }

        return try {
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(ctx.packageName)
            // 管理アプリとしての登録も外し、普通のアプリとして消せるようにする(既に外れていれば何もしない)。
            runCatching { dpm.removeActiveAdmin(admin) }
            val stamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date())
            Prefs.get(ctx).edit().putString(Prefs.KEY_RELEASED_AT, stamp).apply()
            null
        } catch (e: Exception) {
            "管理を外せませんでした: ${e.javaClass.simpleName} ${e.message.orEmpty()}"
        }
    }

    /** Googleアカウントの追加画面を開く(ここから「アカウントを作成」で新規登録できる)。 */
    fun openAddGoogleAccount(activity: Activity): Boolean {
        val google = Intent(Settings.ACTION_ADD_ACCOUNT)
            .putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
        return try {
            activity.startActivity(google); true
        } catch (e: Exception) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_ADD_ACCOUNT)) }.isSuccess
        }
    }
}
