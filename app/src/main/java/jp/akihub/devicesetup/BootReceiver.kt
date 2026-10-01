package jp.akihub.devicesetup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** 再起動後、初期設定ウィザードが終わっていれば一度だけ仕上げ(位置情報OFFなど)を再適用する。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val setupDone = Settings.Secure.getInt(context.contentResolver, "user_setup_complete", 0) == 1
        if (!setupDone) return
        SetupRunner(context).finalizeOnce()
        // 初期設定の途中で取り寄せに失敗していたら、起動のたびにやり直す(入っていれば何もしない)。
        if (AssetAppInstaller.isConfigured(context) && AssetAppInstaller.installedVersion(context) == null) {
            AssetInstallService.start(context)
        }
    }
}
