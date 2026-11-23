package com.openrs.dash

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #391: the app ships only the PID asset it opens (`data/ForscanCatalog.kt` reads `pids/forscan_modules.json`). Assets
 * are not removed by resource shrinking, so an asset no code opens still ships in every APK. The merged catalog that
 * `android/docs/pid-reference.md` cites lives under `android/docs/`, outside the APK.
 */
@RunWith(RobolectricTestRunner::class)
class ShippedAssetsTest {
    @Test fun `the pids folder holds only the catalog the app opens`() {
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        assertEquals(listOf("forscan_modules.json"), assets.list("pids")!!.sorted())
    }
}
