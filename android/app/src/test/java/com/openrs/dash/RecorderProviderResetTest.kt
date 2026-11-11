package com.openrs.dash

import com.openrs.dash.service.CanDataService
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #316 seam 5: [TestServices.destroyAll] puts the app's recorder back, so a test's own recorder never reaches a later class. */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class RecorderProviderResetTest {
    private val h = RecorderHarness()
    @After fun tearDown() { TestServices.destroyAll(); h.close() }

    @Test fun `destroyAll puts the app's recorder back in the provider`() {
        val rec = h.recorder()
        CanDataService.recorderProvider = { rec }
        TestServices.create()
        TestServices.destroyAll()
        assertSame(CanDataService.APP_RECORDER, CanDataService.recorderProvider)
    }
}
