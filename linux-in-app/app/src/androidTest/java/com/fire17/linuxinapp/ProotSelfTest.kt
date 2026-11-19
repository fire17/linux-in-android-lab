package com.fire17.linuxinapp

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fire17.linuxinapp.proot.ProotLauncher
import com.fire17.linuxinapp.rootfs.RootfsInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same check as the in-app "Self-test" button, runnable headless:
 *   ./gradlew connectedDebugAndroidTest
 * CI runs it on an x86_64 emulator (job "emulator-selftest"); on a phone it runs on arm64.
 */
@RunWith(AndroidJUnit4::class)
class ProotSelfTest {

    @Test
    fun alpineBootsUnderProot() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val installLog = RootfsInstaller(ctx).ensureInstalled()
        Log.i(TAG, installLog)

        val launcher = ProotLauncher(ctx)
        launcher.preflight().forEach { Log.i(TAG, it) }
        val r = launcher.runOnce(ProotLauncher.SELF_TEST_SCRIPT, timeoutSec = 120)
        Log.i(TAG, "exit=${r.exitCode} ms=${r.millis}\n${r.output}")

        assertEquals("proot exit code; output:\n${r.output}", 0, r.exitCode)
        assertTrue("expected Alpine os-release; output:\n${r.output}", r.output.contains("Alpine", ignoreCase = true))
        assertTrue("expected fake root uid=0; output:\n${r.output}", r.output.contains("uid=0"))
        assertTrue("expected uname line; output:\n${r.output}", r.output.contains("Linux"))
    }

    companion object { private const val TAG = "LinuxInAppSelfTest" }
}
