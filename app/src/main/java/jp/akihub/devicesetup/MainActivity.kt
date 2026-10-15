package jp.akihub.devicesetup

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        runner = SetupRunner(this)
        prefs = Prefs.get(this)

        findViewById<TextView>(R.id.title).text = "端末初期設定 v${BuildConfig.VERSION_NAME}"
        findViewById<TextView>(R.id.device).text =
            "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"

        findViewById<Button>(R.id.btnContinue).setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
        findViewById<Button>(R.id.btnRerun).setOnClickListener { runSetup() }
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
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
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
            handler.post { render() }
        }.start()
    }

    private fun render() {
        findViewById<TextView>(R.id.ownerStatus).text =
            if (runner.isOwner) "管理端末として登録済み" else "管理端末ではありません(QRで初期設定した端末でのみ自動設定が動きます)"

        val last = prefs.getString(Prefs.KEY_LAST_RESULT, null)
        val at = prefs.getString(Prefs.KEY_LAST_RUN_AT, null)
        findViewById<TextView>(R.id.results).text =
            if (last == null) "まだ実行していません" else (if (at != null) "実行 $at\n" else "") + last

        findViewById<TextView>(R.id.finalizeStatus).text = prefs.getString(Prefs.KEY_FINALIZE_MSG, "") ?: ""

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        findViewById<TextView>(R.id.policyStatus).text =
            if (nm.isNotificationPolicyAccessGranted) "通知制御の権限あり。サイレント切替の結果は上で確認できます。"
            else "通知制御の権限なし。サイレントが当たらない機種では上のボタンから許可してください。"

        findViewById<TextView>(R.id.language).text = "端末言語：" + Locale.getDefault().displayLanguage
        findViewById<TextView>(R.id.networkStatus).text = "通信状態：" + networkSummary()
    }

    // ---- チェックリスト ----

    private data class Item(val key: String, val label: String, val action: (() -> Unit)?)

    private fun buildChecklists() {
        val manual = listOf(
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
}
