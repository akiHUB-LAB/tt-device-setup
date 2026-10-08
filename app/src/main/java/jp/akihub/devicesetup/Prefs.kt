package jp.akihub.devicesetup

import android.content.Context
import android.content.SharedPreferences

/** アプリ内の記録置き場(自動設定の結果、チェック状態など)。 */
object Prefs {
    const val KEY_LINE_URLS = "line_apk_urls"      // QRのおまけ情報で渡されたLINEのAPKの置き場所
    const val KEY_FINALIZED = "finalized"          // 仕上げ(位置情報OFFの再適用)を済ませたか
    const val KEY_LAST_RESULT = "last_result"      // 自動設定の最終結果(表示用の文章)
    const val KEY_LAST_RUN_AT = "last_run_at"
    const val KEY_LINE_STATUS = "line_status"      // LINE導入の進み具合(表示用の文章)
    const val KEY_FINALIZE_MSG = "finalize_msg"
    const val KEY_ASSET_URL = "asset_url"          // QRのおまけ情報: 資産ダッシュボードのApps ScriptのURL
    const val KEY_ASSET_KEY = "asset_key"          // QRのおまけ情報: アプリの中身を取り出すだけの合言葉
    const val KEY_ASSET_STATUS = "asset_status"    // 資産ダッシュボード導入の進み具合(表示用の文章)
    const val KEY_ASSET_LAUNCH_AFTER = "asset_launch_after"
    const val KEY_SERIAL = "serial"
    const val KEY_ENABLE_ADB = "enable_adb"        // QRのおまけ情報: USBデバッグをONにする("1")
    const val KEY_RELEASED_AT = "released_at"      // 「管理を外す」を押した日時(表示用)

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("setup", Context.MODE_PRIVATE)
}
