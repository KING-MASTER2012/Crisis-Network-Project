package org.crisisnetwork.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.crisisnetwork.android.core.ffi.runCoreSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the native core self-test on a real device or emulator (phase A0 exit criterion). */
@RunWith(AndroidJUnit4::class)
class CoreSmokeTest {
    @Test
    fun theNativeCoreWorksOnTheDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = runCoreSmoke(File(context.cacheDir, "core-smoke-test"))

        assertNotNull("the native library did not load", report.info)
        assertTrue("self-test failed:\n${report.describe()}", report.passed)
    }

    @Test
    fun encryptionMatchesTheBuildConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = runCoreSmoke(File(context.cacheDir, "core-smoke-test-encryption"))

        // `-Pcrisis.sqlcipher=true` must produce a core that really encrypts; a build without
        // it must refuse a key rather than store plaintext.
        val expected = InstrumentationRegistry.getArguments().getString("expectEncryption").toBoolean()
        assertEquals(report.describe(), expected, report.encryptionAvailable)
    }
}
