package jp.akihub.devicesetup

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * 状況を見せる画面。
 * ・初期設定ウィザードの中から呼ばれた時(ADMIN_POLICY_COMPLIANCE)は自動設定を実行し「セットアップを続行」を出す。
 * ・普段の起動では記録を見せ、手動項目のチェックリストを出す。
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runner: SetupRunner
    private lateinit var prefs: SharedPreferences
    private val isCompliance: Boolean
        get() = intent?.action == "android.app.action.ADMIN_POLICY_COMPLIANCE"

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> handler.post { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        runner = SetupRunner(this)
        prefs = Prefs.get(this)
        AdminReceiver.saveAdminExtras(this, intent)

        findViewById<TextView>(R.id.title).text = "端末初期設定 v${BuildConfig.VERSION_NAME}"
        findViewById<TextView>(R.id.device).text =
            "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"

        findViewById<Button>(R.id.btnContinue).setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
        findViewById<Button>(R.id.btnRerun).setOnClickListener { runSetup() }
        findViewById<Button>(R.id.btnLine).setOnClickListener {
            if (LineInstaller.isInstalled(this) || LineInstaller.configuredUrls(this).isNotEmpty()) {
                LineInstaller.installAsync(this)
            } else {
                openPlayStore(LineInstaller.LINE_PKG)
            }
        }
        findViewById<Button>(R.id.btnSilentPerm).setOnClickListener {
            open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { open(Intent(Settings.ACTION_SETTINGS)) }

        buildChecklists()

        if (isCompliance) {
            findViewById<Button>(R.id.btnContinue).visibility = android.view.View.VISIBLE
            runSetup()
        } else {
            if (prefs.getString(Prefs.KEY_LAST_RESULT, null) == null) runSetup()
            // ウィザードが終わって初めて開いた時に一度だけ仕上げる。
            runner.finalizeOnce()
            if (!LineInstaller.isInstalled(this) && LineInstaller.configuredUrls(this).isNotEmpty()
                && prefs.getString(Prefs.KEY_LINE_STATUS, null) == null
            ) {
                LineInstaller.installAsync(this)
            }
            if (AssetAppInstaller.isConfigured(this) && AssetAppInstaller.installedVersion(this) == null) {
                AssetInstallService.start(this)
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        render()
        if (playProtectPending) askPlayProtectDone()
        // 設定の画面から戻ってきた。ONになったかを確かめて次へ(まだならもう一度頼む)。
        when (assetStep) {
            AssetStep.A11Y -> askAssetA11y()
            else -> {}
        }
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // ウィザード中は「セットアップを続行」で抜ける(戻るで初期設定を失敗させない)。
        if (isCompliance) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun runSetup() {
        findViewById<TextView>(R.id.results).text = "自動設定を実行中…"
        Thread {
            runner.runAllAndStore()
            if (isCompliance) LineInstaller.installAsync(this)
            handler.post {
                render()
                if (!isCompliance) return@post
                // 資産ダッシュボードを入れる前に、Play プロテクトのスキャンをOFFにしてもらう(利用者の判断、2026-10-01)。
                // 入れると「スキャンされたことがないデベロッパー」として毎回ブロックされるため。
                if (AssetAppInstaller.isConfigured(this) && AssetAppInstaller.installedVersion(this) == null &&
                    PlayProtect.isScanOn(this)
                ) {
                    playProtectPending = true
                    PlayProtect.openSettings(this)
                } else {
                    proceedAfterPlayProtect()
                }
            }
        }.start()
    }

    // Play プロテクトの画面から戻るのを待っている間。戻ったら確認を出す。
    private var playProtectPending = false
    private var playProtectDialog: android.app.AlertDialog? = null

    private fun askPlayProtectDone() {
        if (playProtectDialog?.isShowing == true) return
        val on = PlayProtect.isScanOn(this)
        playProtectDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Play プロテクト")
            .setMessage(
                (if (on) "まだ「アプリをスキャン」がONです。右上の歯車 →「Play プロテクトでアプリをスキャンする」をOFFにしてください" +
                    "(「有害なアプリの検出を改善」ではないほう)。" else "「アプリをスキャン」はOFFになっています。") +
                    "\nONのままだと、資産ダッシュボードが「安全でない」と止められて入りません(2026-10-03 XIG07)。"
            )
            .setCancelable(false)
            .setPositiveButton("OFFにした・続ける") { _, _ -> proceedAfterPlayProtect() }
            .setNeutralButton("設定を開く") { _, _ -> PlayProtect.openSettings(this) }
            .setNegativeButton("このまま続ける") { _, _ -> proceedAfterPlayProtect() }
            .show()
    }

    private fun proceedAfterPlayProtect() {
        playProtectPending = false
        AssetInstallService.start(this)
        if (isCompliance && AssetAppInstaller.isConfigured(this)) {
            assetStep = AssetStep.WAIT_INSTALL
            assetWaitStartedAt = System.currentTimeMillis()
            waitAssetInstalled()
        } else {
            autoContinue(3)
        }
    }

    // ---- 資産ダッシュボードの仕上げ(QRの流れの中で手でやってもらう) ----
    // 2026-10-03 利用者の希望: 初期化のあとにUSBを挿して仕上げるのは手間。QRを読んだあとの「次へ」や
    // Play プロテクトの流れの中で、ユーザー補助のONも済ませる(手作業はできるだけ少なく)。
    // ユーザー補助がONになると資産ダッシュボードが動き出し、サーバーに「初めてつながった」が届く
    // (それまでは一度も起動されないので、ダッシュボードにも出なかった)。
    // ユーザー補助が外れたときに自分で戻す許可(WRITE_SECURE_SETTINGS)だけはUSBでしか渡せない。
    // 電池の最適化の「許可」は、端末がつなぎっぱなし(充電中は最適化が働かない)なので手順に入れない(2026-10-03 利用者と決めた)。
    private enum class AssetStep { NONE, WAIT_INSTALL, A11Y }
    private var assetStep = AssetStep.NONE
    private var assetWaitStartedAt = 0L
    private var assetDialog: android.app.AlertDialog? = null

    private fun waitAssetInstalled() {
        if (isFinishing || assetStep != AssetStep.WAIT_INSTALL) return
        val btn = findViewById<Button>(R.id.btnContinue)
        if (AssetAppInstaller.installedVersion(this) != null) {
            assetStep = AssetStep.A11Y
            askAssetA11y()
            return
        }
        if (System.currentTimeMillis() - assetWaitStartedAt > ASSET_WAIT_MS) {
            // サーバーが混んでいる等で導入が長引いた。待たずに進み、あとで一覧の「開く」から仕上げてもらう。
            assetStep = AssetStep.NONE
            Toast.makeText(this, "資産ダッシュボードの導入が長引いているため先へ進みます。あとでこのアプリの一覧から仕上げてください。", Toast.LENGTH_LONG).show()
            autoContinue(3)
            return
        }
        btn.text = "資産ダッシュボードを導入中…(終わるまで待ちます)"
        handler.postDelayed({ waitAssetInstalled() }, 2000)
    }

    private fun assetA11yOn(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(AssetAppInstaller.PKG + "/") == true

    private fun askAssetA11y() {
        if (assetDialog?.isShowing == true) return
        if (assetA11yOn()) {
            prefs.edit().putBoolean("chk_asset_a11y", true).apply()
            buildChecklists()
            assetStep = AssetStep.NONE
            autoContinue(3)
            return
        }
        assetDialog = android.app.AlertDialog.Builder(this)
            .setTitle("資産ダッシュボードのユーザー補助")
            .setMessage("次の画面(ユーザー補助)の上の方にある「資産ダッシュボード」を押して、ONにしてください。\n" +
                "見当たらなければ「ダウンロードしたアプリ」の中にあります(Xiaomi など)。\n" +
                "「許可」などを押したら、「戻る」でこの画面に戻ってきてください。\n(これでUSBを挿さずに仕上がります)")
            .setCancelable(false)
            .setPositiveButton("開く") { _, _ -> openAssetA11ySettings() }
            .setNegativeButton("あとで") { _, _ -> assetStep = AssetStep.NONE; autoContinue(3) }
            .show()
    }

    /**
     * 資産ダッシュボードのユーザー補助の画面を直接開く。開けない機種はユーザー補助の一覧(SOG04 Android 13 では
     * 直接は開けず一覧になった。一覧のいちばん上の「サービス」に資産ダッシュボードが出る)。
     */
    private fun openAssetA11ySettings() {
        val component = ComponentName(AssetAppInstaller.PKG, AssetAppInstaller.PKG + ".BalanceAccessibilityService")
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
        try { startActivity(detail) } catch (e: Exception) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }


    /** ウィザード中はボタンを押さなくても数秒で次へ進む(結果はあとからアプリで見られる)。 */
    private fun autoContinue(secondsLeft: Int) {
        if (isFinishing) return
        val btn = findViewById<Button>(R.id.btnContinue)
        if (secondsLeft <= 0) {
            setResult(RESULT_OK)
            finish()
            return
        }
        btn.text = "セットアップを続行(${secondsLeft}秒後に自動で進みます)"
        handler.postDelayed({ autoContinue(secondsLeft - 1) }, 1000)
    }

    private fun render() {
        if (isFinishing) return
        val nm = getSystemService(NotificationManager::class.java)

        findViewById<TextView>(R.id.ownerStatus).text =
            if (runner.isOwner) "管理端末として登録済み" else "管理端末ではありません(QRで初期設定した端末のみ自動設定が動きます)"

        val at = prefs.getString(Prefs.KEY_LAST_RUN_AT, null)
        val result = prefs.getString(Prefs.KEY_LAST_RESULT, null)
        findViewById<TextView>(R.id.results).text =
            if (result == null) "(未実行)" else result + (if (at != null) "\n(実行 $at)" else "")

        findViewById<TextView>(R.id.finalizeStatus).text = prefs.getString(Prefs.KEY_FINALIZE_MSG, "") ?: ""

        val lineText = when {
            LineInstaller.isInstalled(this) -> "LINEは導入済みです。ログインは手動です。"
            else -> prefs.getString(Prefs.KEY_LINE_STATUS, null)
                ?: if (LineInstaller.configuredUrls(this).isEmpty())
                    "LINEは未導入です。QRにLINEのAPKの置き場所が無いので、ボタンでPlayストアを開いて入れてください。"
                else "LINEは未導入です。"
        }
        val assetText = prefs.getString(Prefs.KEY_ASSET_STATUS, null)
            ?: if (AssetAppInstaller.isConfigured(this)) "資産ダッシュボード: 未導入" else "資産ダッシュボード: QRに置き場所なし"
        findViewById<TextView>(R.id.lineStatus).text = lineText + "\n" + assetText

        findViewById<TextView>(R.id.policyStatus).text =
            if (nm.isNotificationPolicyAccessGranted) "通知制御の権限あり。サイレント切替の結果は上で確認できます。"
            else "通知制御の権限なし。サイレント切替が「要手動確認」なら上のボタンで許可して再実行してください。"

        findViewById<TextView>(R.id.language).text = "端末言語：" + Locale.getDefault().getDisplayName(Locale.JAPAN)

        findViewById<TextView>(R.id.networkStatus).text = "現在の通信：" + networkSummary()
    }

    // ---- チェックリスト ----

    private data class Item(val key: String, val label: String, val action: (() -> Unit)?)

    private fun buildChecklists() {
        val manual = listOf(
            Item("chk_line_login", "LINEのログイン(導入完了後)") { launchApp(LineInstaller.LINE_PKG) },
            Item("chk_play_protect", "Play プロテクトのスキャンOFF(資産ダッシュボードの更新が止められないように)") {
                PlayProtect.openSettings(this)
            },
            Item("chk_asset_install", "資産ダッシュボードを入れ直す(「導入に失敗しました」と出たとき)") {
                AssetInstallService.start(this)
                Toast.makeText(this, "資産ダッシュボードの取り寄せをやり直します", Toast.LENGTH_SHORT).show()
            },
            Item("chk_asset_a11y", "資産ダッシュボードのユーザー補助ON") { openAssetA11ySettings() },
            Item("chk_rotate", "自動回転OFF") { open(Intent(Settings.ACTION_DISPLAY_SETTINGS)) },
            Item("chk_eew", "緊急地震速報OFF") { openEmergencyAlerts() },
            Item("chk_saver", "省エネモードON") { open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) },
        )
        val final = listOf(
            Item("chk_airplane", "機内モードON") { open(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) },
            Item("chk_wifi", "Wi-Fi OFF") {
                if (runner.wifiOff()) Toast.makeText(this, "Wi-Fiを切りました", Toast.LENGTH_SHORT).show()
                else open(Intent(Settings.ACTION_WIFI_SETTINGS))
                handler.postDelayed({ render() }, 1500)
            },
        )
        fill(findViewById(R.id.manualList), manual)
        fill(findViewById(R.id.finalList), final)
    }

    private fun fill(container: LinearLayout, items: List<Item>) {
        container.removeAllViews()
        for (it in items) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val cb = CheckBox(this).apply {
                text = it.label
                textSize = 17f
                isChecked = prefs.getBoolean(it.key, false)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(it.key, c).apply() }
            }
            row.addView(cb)
            if (it.action != null) {
                row.addView(Button(this).apply {
                    text = "開く"
                    setOnClickListener { _ -> it.action.invoke() }
                })
            }
            container.addView(row)
        }
    }

    // ---- 画面を開く小道具 ----

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) {
            Toast.makeText(this, "この機種では開けませんでした。設定アプリから探してください。", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) open(i) else Toast.makeText(this, "まだ入っていません", Toast.LENGTH_SHORT).show()
    }

    private fun openPlayStore(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (e: Exception) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }

    /** 緊急速報の設定。機種で場所が違うので順に試す。 */
    private fun openEmergencyAlerts() {
        val candidates = listOf(
            Intent("android.settings.CELL_BROADCAST_SETTINGS"),
            Intent().setComponent(ComponentName("com.google.android.cellbroadcastreceiver", "com.android.cellbroadcastreceiver.CellBroadcastSettings")),
            Intent().setComponent(ComponentName("com.android.cellbroadcastreceiver", "com.android.cellbroadcastreceiver.CellBroadcastSettings")),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (c in candidates) {
            try { startActivity(c); return } catch (_: Exception) {}
        }
        open(Intent(Settings.ACTION_SETTINGS))
    }

    private fun networkSummary(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "切断"
        val parts = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "Wi-Fi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "モバイル"
        return if (parts.isEmpty()) "切断" else parts.joinToString("+") + " 接続中"
    }

    private companion object {
        // 資産ダッシュボードの導入を待つ上限。サーバーが混んでいると数分かかる(2026-10-02 TT59)。
        const val ASSET_WAIT_MS = 4 * 60_000L
    }
}
