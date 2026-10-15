package jp.akihub.devicesetup

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/** 管理端末(デバイスオーナー)としての受け口。 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        // 初期設定が完了した直後に自動設定を一通り実行する。
        SetupRunner(context).runAllAndStore()
        // Android 11以前(旧フロー)はここで画面を出す。12以降は ADMIN_POLICY_COMPLIANCE で画面が出る。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val i = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    companion object {
        fun component(ctx: Context): ComponentName = ComponentName(ctx, AdminReceiver::class.java)
    }
}
