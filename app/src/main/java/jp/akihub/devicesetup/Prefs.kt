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

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("setup", Context.MODE_PRIVATE)
}
