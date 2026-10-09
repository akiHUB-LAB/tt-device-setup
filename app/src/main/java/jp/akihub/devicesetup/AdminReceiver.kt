package jp.akihub.devicesetup

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle

/** 管理端末(デバイスオーナー)としての受け口。 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        saveAdminExtras(context, intent)
        // 初期設定が完了した直後に自動設定を一通り実行する。
        SetupRunner(context).runAllAndStore()
        // 初期設定の最後に自動で管理を外すための見直しを始める。
        AutoRelease.schedule(context)
        // Android 11以前(旧フロー)はここで画面を出す。12以降は ADMIN_POLICY_COMPLIANCE で画面が出る。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val i = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    companion object {
        fun component(ctx: Context): ComponentName = ComponentName(ctx, AdminReceiver::class.java)

        /** QRの PROVISIONING_ADMIN_EXTRAS_BUNDLE に入れた値を保存する(どの経路で届いても同じ処理)。 */
        fun saveAdminExtras(ctx: Context, intent: Intent?) {
            val bundle: PersistableBundle = intent
                ?.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
                ?: return
            val urls = bundle.getString("line_apk_urls")?.trim().orEmpty()
            if (urls.isNotEmpty()) {
                Prefs.get(ctx).edit().putString(Prefs.KEY_LINE_URLS, urls).apply()
            }
            // 資産ダッシュボードの置き場所と、取り出し専用の合言葉(QRの tools/make_qr.py --asset-secrets で入る)。
            val assetUrl = bundle.getString("asset_url")?.trim().orEmpty()
            val assetKey = bundle.getString("asset_key")?.trim().orEmpty()
            // USBデバッグをONにするか(make_qr.py --enable-adb)。Macから端末の記録を読むため。
            if (bundle.getString("enable_adb") == "1") {
                Prefs.get(ctx).edit().putBoolean(Prefs.KEY_ENABLE_ADB, true).apply()
            }
            if (assetUrl.isNotEmpty() && assetKey.isNotEmpty()) {
                Prefs.get(ctx).edit().putString(Prefs.KEY_ASSET_URL, assetUrl).putString(Prefs.KEY_ASSET_KEY, assetKey).apply()
            }
        }
    }
}
