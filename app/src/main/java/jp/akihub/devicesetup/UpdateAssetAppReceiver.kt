package jp.akihub.devicesetup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 資産ダッシュボードの「アプリ更新」から頼まれて、サーバーの新しい版に入れ替える。
 * 誰から頼まれても、入れるのはサーバーに置いた署名済みの資産ダッシュボードだけで、古い版には戻さない。
 */
class UpdateAssetAppReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AssetAppInstaller.ACTION_UPDATE) return
        AssetInstallService.start(context)
    }
}
