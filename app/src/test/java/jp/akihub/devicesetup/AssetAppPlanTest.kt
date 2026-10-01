package jp.akihub.devicesetup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 資産ダッシュボードのアプリを入れるかどうかと、取り寄せた中身の確かめ。 */
class AssetAppPlanTest {
    @Test
    fun 入っていなければ入れ_古ければ入れ替え_同じか新しければ何もしない() {
        assertEquals(true, AssetAppPlan.needsInstall(installedVersionCode = null, serverVersionCode = 3_550_000))
        assertEquals(true, AssetAppPlan.needsInstall(installedVersionCode = 3_549_000, serverVersionCode = 3_550_000))
        assertEquals(false, AssetAppPlan.needsInstall(installedVersionCode = 3_550_000, serverVersionCode = 3_550_000))
        assertEquals(false, AssetAppPlan.needsInstall(installedVersionCode = null, serverVersionCode = 0))
    }

    @Test
    fun 中身は大きさと指紋で確かめる() {
        val sha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertNull(AssetAppPlan.verify("hello".toByteArray(), 5, sha))
        assertEquals("大きさが違う(4/5バイト)", AssetAppPlan.verify("hell".toByteArray(), 5, sha))
        assertEquals("指紋が違う", AssetAppPlan.verify("hellO".toByteArray(), 5, sha))
    }

    @Test
    fun 取り寄せのURLには取り出し専用の合言葉だけを付ける() {
        assertEquals(
            "https://script.google.com/macros/s/X/exec?action=apkChunk&key=K1&version_code=5&i=2",
            AssetAppPlan.chunkUrl("https://script.google.com/macros/s/X/exec", "K1", 5, 2),
        )
        assertEquals(
            "https://script.google.com/macros/s/X/exec?action=apkInfo&key=K1",
            AssetAppPlan.infoUrl("https://script.google.com/macros/s/X/exec", "K1"),
        )
    }
}
