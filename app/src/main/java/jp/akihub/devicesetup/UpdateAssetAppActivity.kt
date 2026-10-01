package jp.akihub.devicesetup

import android.app.Activity
import android.os.Bundle

/**
 * 資産ダッシュボードの「アプリ更新」から開かれる、画面の無い入口。入れ替えを始めてすぐ閉じる。
 * 知らせ(ブロードキャスト)で頼むと、ZTE(Nubia A504ZT)は動いていないアプリ宛ての知らせを捨てた
 * (2026-10-01 TT58: ZteBrSlowDetect all receivers are skipped)。画面を開く形なら捨てられない。
 * 誰から開かれても、入れるのはサーバーに置いた署名済みの新しい版だけ。
 */
class UpdateAssetAppActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 画面が消えている・ロック画面のときに開かれても動くように(2026-10-01 TT58: ロック中は開かれなかった)。
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        AssetInstallService.start(this)
        finish()
        overridePendingTransition(0, 0)
    }
}
