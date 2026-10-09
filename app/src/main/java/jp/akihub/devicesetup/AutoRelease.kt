package jp.akihub.devicesetup

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * 初期設定の最後に自動で管理を外す(2026-10-09 利用者の決定)。
 *
 * QRの初期設定は「長い初期画面(docomo等)を飛ばせる」「音量などが最初から入る」のが利点。一方、管理端末のままだと
 * Playストアが仕事用アカウントしか受け付けず、仕事に必要なアプリの導入やGoogleアプリの更新ができない。
 * そこで、管理端末の力が要る作業が済んだら自動で外して、普通のスマホに戻す。外したときに再起動が要っても良い(利用者了承)。
 *
 * 外す条件(すべて満たしたとき):
 * 1. 初期設定の画面がすべて終わっている(途中で外すと初期設定が失敗する)
 * 2. 資産ダッシュボードが入り、入った直後の仕上げ(通知の許可・起動)も済んでいる(外すと無言で入れられなくなる)
 * 3. 資産ダッシュボードのユーザー補助がON(管理端末のうちに済ませておく。外したあとは「制限付き設定」で止められる機種がある)
 * QRに資産ダッシュボードの置き場所が無いときは 2・3 を見ない。
 * 条件がそろうまで、約1分ごとに見直す(再起動後も続ける)。
 */
object AutoRelease {

    enum class State { NOT_OWNER, WIZARD_RUNNING, WAITING_ASSET_INSTALL, WAITING_A11Y, READY }

    fun state(ctx: Context): State {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) return State.NOT_OWNER
        if (Settings.Secure.getInt(ctx.contentResolver, "user_setup_complete", 0) != 1) return State.WIZARD_RUNNING
        if (AssetAppInstaller.isConfigured(ctx)) {
            val installed = AssetAppInstaller.installedVersion(ctx) != null
            val afterDone = !Prefs.get(ctx).getBoolean(Prefs.KEY_ASSET_LAUNCH_AFTER, false)
            if (!installed || !afterDone) return State.WAITING_ASSET_INSTALL
            if (!AssetAppInstaller.isAssetA11yOn(ctx)) return State.WAITING_A11Y
        }
        return State.READY
    }

    /** 画面に出す一言。外れていれば null。 */
    fun waitingText(ctx: Context): String? = when (state(ctx)) {
        State.NOT_OWNER -> null
        State.WIZARD_RUNNING -> "初期設定の画面が終わったら、自動で管理を外します。"
        State.WAITING_ASSET_INSTALL -> "資産ダッシュボードが入り終わったら、自動で管理を外します。"
        State.WAITING_A11Y -> "資産ダッシュボードのユーザー補助をONにすると、自動で管理を外します。"
        State.READY -> "まもなく自動で管理を外します。"
    }

    /**
     * 条件がそろっていれば、仕上げ(位置情報OFFの再適用など)をしてから外す。
     * まだなら見直しを予約し、資産ダッシュボードが入っていなければ取り寄せを促す。
     */
    fun check(ctx: Context) {
        val app = ctx.applicationContext
        when (state(app)) {
            State.NOT_OWNER -> cancel(app)
            State.WIZARD_RUNNING, State.WAITING_A11Y -> schedule(app)
            State.WAITING_ASSET_INSTALL -> {
                if (AssetAppInstaller.installedVersion(app) == null) AssetInstallService.start(app)
                schedule(app)
            }
            State.READY -> release(app)
        }
    }

    private fun release(ctx: Context) {
        SetupRunner(ctx).finalizeOnce()
        grantOwnNotifications(ctx)
        val error = Unmanage.release(ctx)
        if (error == null) {
            cancel(ctx)
            notifyReleased(ctx)
        } else {
            Prefs.get(ctx).edit().putString(Prefs.KEY_RELEASE_ERROR, error).apply()
            schedule(ctx, 10 * 60_000L)  // しばらくしてもう一度
        }
    }

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 41, Intent(ctx, AutoReleaseReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(ctx: Context, delayMs: Long = 60_000L) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pending(ctx))
    }

    private fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
    }

    /** 外したあとは自分に許可を与えられないので、外す前に通知の許可を取っておく。 */
    private fun grantOwnNotifications(ctx: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        runCatching {
            dpm.setPermissionGrantState(AdminReceiver.component(ctx), ctx.packageName,
                Manifest.permission.POST_NOTIFICATIONS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
    }

    private fun notifyReleased(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("released", "管理を外したお知らせ", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = "初期設定が終わったので管理を外しました。Googleアカウントの追加とPlayストアが使えます。" +
            "Playストアが「仕事用」のままなら、一度再起動してください。"
        val n = Notification.Builder(ctx, "released")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("普通のスマホに戻りました")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(RELEASED_NOTIFICATION_ID, n) }
    }

    private const val RELEASED_NOTIFICATION_ID = 3
}

/** AutoRelease の見直しの予約を受ける。 */
class AutoReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutoRelease.check(context)
    }
}
