package jp.akihub.devicesetup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 資産ダッシュボードの取り寄せ〜入れ替えの間だけ動く。取り寄せに1分ほどかかり、知らせ(ブロードキャスト)の
 * 受け口の中では終わらないので、前面のサービスで動かす(管理端末は裏からでも前面サービスを始められる)。
 */
class AssetInstallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "アプリの導入", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("資産ダッシュボードを導入中")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        AssetAppInstaller.installAsync(this) { stopSelf(startId) }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "asset_install"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, AssetInstallService::class.java)) }
                .onFailure { AssetAppInstaller.installAsync(ctx) }
        }
    }
}
