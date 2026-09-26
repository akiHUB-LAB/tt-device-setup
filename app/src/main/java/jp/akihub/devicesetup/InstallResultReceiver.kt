package jp.akihub.devicesetup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** 無言インストールの結果を受け取り、表示用の文章として保存する。 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val text = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 管理端末でなければ確認画面が必要になる。その場合は画面を出す。
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
                "LINEの導入に確認が必要です。画面の指示に従ってください。"
            }
            PackageInstaller.STATUS_SUCCESS -> "LINEは導入済みです。ログインは手動です。"
            else -> "LINEの導入に失敗しました(コード $status)。$msg"
        }
        Prefs.get(context).edit().putString(Prefs.KEY_LINE_STATUS, text).apply()
    }
}
