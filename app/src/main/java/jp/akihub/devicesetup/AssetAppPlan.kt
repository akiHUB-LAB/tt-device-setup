package jp.akihub.devicesetup

import java.security.MessageDigest

/** 資産ダッシュボードのアプリを入れるかどうかと、取り寄せた中身の確かめ(画面や端末を使わない判断だけ)。 */
object AssetAppPlan {
    /** 入っていない(null)か、サーバーの版のほうが新しければ入れる。サーバーに何も無ければ(0)入れない。 */
    fun needsInstall(installedVersionCode: Long?, serverVersionCode: Long): Boolean =
        serverVersionCode > 0 && (installedVersionCode == null || serverVersionCode > installedVersionCode)

    /** 大きさと指紋(SHA-256)が合えば null、違えば理由。 */
    fun verify(bytes: ByteArray, expectedSize: Long, expectedSha256: String): String? {
        if (bytes.size.toLong() != expectedSize) return "大きさが違う(${bytes.size}/${expectedSize}バイト)"
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!sha.equals(expectedSha256.trim(), ignoreCase = true)) return "指紋が違う"
        return null
    }

    // サーバー(資産ダッシュボードのApps Script)は、取り出し専用の合言葉なら apkInfo / apkChunk だけ通す。
    fun infoUrl(base: String, key: String) = "$base?action=apkInfo&key=$key"

    fun chunkUrl(base: String, key: String, versionCode: Long, index: Int) =
        "$base?action=apkChunk&key=$key&version_code=$versionCode&i=$index"
}
