package jp.akihub.devicesetup

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自動設定の本体。管理端末(デバイスオーナー)の権限で設定を当て、当たったかを読み返して記録する。
 * 結果の各行は「設定値確認済み：」「要手動確認：」「失敗：」のいずれかで始まる。
 */
class SetupRunner(private val ctx: Context) {

    data class Line(val state: State, val text: String) {
        enum class State { OK, MANUAL, FAIL }
        override fun toString(): String = when (state) {
            State.OK -> "設定値確認済み：$text"
            State.MANUAL -> "要手動確認：$text"
            State.FAIL -> "失敗：$text"
        }
    }

    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val admin = AdminReceiver.component(ctx)

    val isOwner: Boolean get() = dpm.isDeviceOwnerApp(ctx.packageName)

    fun runAllAndStore(): List<Line> {
        val lines = runAll()
        val stamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date())
        Prefs.get(ctx).edit()
            .putString(Prefs.KEY_LAST_RESULT, lines.joinToString("\n"))
            .putString(Prefs.KEY_LAST_RUN_AT, stamp)
            .apply()
        return lines
    }

    fun runAll(): List<Line> {
        val out = mutableListOf<Line>()
        if (!isOwner) {
            out += Line(Line.State.FAIL, "管理端末として登録されていません。QRで初期設定した端末でのみ自動設定が動きます。")
            return out
        }
        runCatching { dpm.setOrganizationName(admin, "管理端末") }

        out += applyScreenOff()
        out += applyMasterMute()
        out += applySilent()
        out += applyVolumes()
        out += applyLocationOff()
        return out
    }

    // ---- 各項目 ----

    private fun applyScreenOff(): Line = try {
        dpm.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, "600000")
        val v = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        if (v == 600000) Line(Line.State.OK, "画面消灯10分")
        else Line(Line.State.FAIL, "画面消灯10分(現在値 ${v / 1000}秒)")
    } catch (e: Exception) {
        Line(Line.State.FAIL, "画面消灯10分(${e.javaClass.simpleName})")
    }

    private fun applyMasterMute(): Line = try {
        dpm.setMasterVolumeMuted(admin, true)
        if (dpm.isMasterVolumeMuted(admin)) Line(Line.State.OK, "マスター音量ミュート")
        else Line(Line.State.FAIL, "マスター音量ミュート")
    } catch (e: Exception) {
        Line(Line.State.FAIL, "マスター音量ミュート(${e.javaClass.simpleName})")
    }

    /** サイレント(着信音モード)。通知制御の許可が無いとバイブ止まりになる機種がある。 */
    private fun applySilent(): Line {
        try { am.ringerMode = AudioManager.RINGER_MODE_SILENT } catch (_: SecurityException) {}
        return when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> Line(Line.State.OK, "サイレントモード")
            AudioManager.RINGER_MODE_VIBRATE -> Line(Line.State.MANUAL, "サイレントモード(現在はバイブ)")
            else -> Line(Line.State.MANUAL, "サイレントモード(現在は通常)")
        }
    }

    private fun applyVolumes(): List<Line> {
        val streams = listOf(
            "メディア音量" to AudioManager.STREAM_MUSIC,
            "着信音量" to AudioManager.STREAM_RING,
            "通知音量" to AudioManager.STREAM_NOTIFICATION,
            "アラーム音量" to AudioManager.STREAM_ALARM,
            "システム音量" to AudioManager.STREAM_SYSTEM,
        )
        return streams.map { (name, stream) ->
            val min = try { am.getStreamMinVolume(stream) } catch (_: Exception) { 0 }
            val label = if (min == 0) "${name}0" else "${name}${min}(最小)"
            try {
                am.setStreamVolume(stream, min, 0)
                val now = am.getStreamVolume(stream)
                if (now <= min) Line(Line.State.OK, label)
                else Line(Line.State.FAIL, "$label(現在値 $now)")
            } catch (e: SecurityException) {
                Line(Line.State.MANUAL, "$label(通知制御の許可が必要)")
            } catch (e: Exception) {
                Line(Line.State.FAIL, "$label(${e.javaClass.simpleName})")
            }
        }
    }

    private fun applyLocationOff(): Line = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setLocationEnabled(admin, false)
        } else {
            @Suppress("DEPRECATION")
            dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE, "0")
        }
        if (!isLocationOn()) Line(Line.State.OK, "位置情報OFF") else Line(Line.State.FAIL, "位置情報OFF(まだON)")
    } catch (e: Exception) {
        Line(Line.State.FAIL, "位置情報OFF(${e.javaClass.simpleName})")
    }

    fun isLocationOn(): Boolean {
        val lm = ctx.getSystemService(LocationManager::class.java)
        return lm.isLocationEnabled
    }

    /**
     * 仕上げ。初期設定ウィザードの残りの画面で位置情報が戻されることがあるので、
     * ウィザード後に一度だけ位置情報OFFと音量を再適用する。以後は自動で変えない。
     */
    fun finalizeOnce(): String? {
        val p = Prefs.get(ctx)
        if (!isOwner) return null
        if (p.getBoolean(Prefs.KEY_FINALIZED, false)) return p.getString(Prefs.KEY_FINALIZE_MSG, null)
        applyLocationOff()
        applyMasterMute()
        applyVolumes()
        val msg = "仕上げ完了：位置情報OFFを再適用しました。以後は自動変更しません。\n現在の位置情報：" +
            (if (isLocationOn()) "ON" else "OFF")
        p.edit().putBoolean(Prefs.KEY_FINALIZED, true).putString(Prefs.KEY_FINALIZE_MSG, msg).apply()
        return msg
    }

    /** Wi-Fiを切る。管理端末は Android 10以降でも setWifiEnabled が使える。 */
    fun wifiOff(): Boolean {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        val ok = runCatching { wm.setWifiEnabled(false) }.getOrDefault(false)
        @Suppress("DEPRECATION")
        return ok && !wm.isWifiEnabled
    }
}
