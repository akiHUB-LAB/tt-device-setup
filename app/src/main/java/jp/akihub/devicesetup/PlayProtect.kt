package jp.akihub.devicesetup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Google Play プロテクトの「アプリをスキャン」の画面を開く。ON/OFFの切り替えは人が押す
 * (Googleの設定で、管理端末から変える正式な方法は無い。安全のための設定なので自動では変えない)。
 * 資産ダッシュボードは「スキャンされたことがないデベロッパー」として毎回ブロックされた
 * (2026-10-01 TT63 moto g06・利用者談)ので、利用者の判断で初期設定の途中にOFFにしてもらう。
 */
object PlayProtect {
    /** スキャンがONか(読めなければONとみなす)。Play プロテクトのスイッチは package_verifier_user_consent に出る(OFF=-1)。 */
    fun isScanOn(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, "package_verifier_user_consent", 1) != -1

    /** 設定画面を開く。Google Play 開発者サービスの画面が無ければ、セキュリティの設定を開く。 */
    fun openSettings(ctx: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.google.android.gms",
                "com.google.android.gms.security.settings.VerifyAppsSettingsActivity")),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return true
        }
        return false
    }
}
