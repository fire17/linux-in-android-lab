package com.fire17.linuxinapp

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fire17.linuxinapp.proot.ProotLauncher
import com.fire17.linuxinapp.rootfs.RootfsInstaller
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same check as the in-app "Self-test" button, runnable headless:
 *   ./gradlew connectedDebugAndroidTest
 * CI runs it on an x86_64 emulator (job "emulator-selftest"); on a phone it runs on arm64.
 *
 * Every step is a separate proot run and is logged on its own line as
 *   STEP <name> exit=<code> ms=<ms> :: <first output line>
 * so a CI log shows exactly which step broke and the exact failure line (e.g. the x86_64
 * "/bin/sh: can't fork: Function not implemented" seen in run 44508979139), not just "exit=2".
 */
@RunWith(AndroidJUnit4::class)
class ProotSelfTest {

    @Test
    fun alpineBootsUnderProot() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Log.i(TAG, "device: abi=${Build.SUPPORTED_ABIS.joinToString(",")} sdk=${Build.VERSION.SDK_INT} " +
            "model=${Build.MODEL} fingerprint=${Build.FINGERPRINT}")
        val installLog = RootfsInstaller(ctx).ensureInstalled()
        Log.i(TAG, installLog)

        val launcher = ProotLauncher(ctx)
        launcher.preflight().forEach { Log.i(TAG, it) }

        val steps = launcher.runSelfTestSteps(timeoutSec = 120)
        for (s in steps) {
            Log.i(TAG, "STEP ${s.name} exit=${s.result.exitCode} ms=${s.result.millis} :: ${s.firstLine}")
            // Full output on following lines (logcat splits long messages; keep each one short).
            s.result.output.lineSequence().filter { it.isNotBlank() }.take(20)
                .forEach { Log.i(TAG, "  ${s.name}| $it") }
        }
        val failed = steps.filterNot { it.ok }
        Log.i(TAG, "SUMMARY ${steps.size - failed.size}/${steps.size} steps ok" +
            (if (failed.isEmpty()) "" else "; FAILED: " + failed.joinToString { "${it.name}(exit=${it.result.exitCode}: ${it.firstLine})" }))

        if (failed.isNotEmpty()) {
            fail("self-test steps failed on ${Build.SUPPORTED_ABIS.first()}:\n" +
                failed.joinToString("\n") { "  ${it.name}: exit=${it.result.exitCode} :: ${it.firstLine}" })
        }
        val out = steps.associate { it.name to it.result.output }
        assertTrue("expected Alpine os-release; got:\n${out["os-release"]}", out["os-release"].orEmpty().contains("Alpine", ignoreCase = true))
        assertTrue("expected fake root uid=0; got:\n${out["id"]}", out["id"].orEmpty().contains("uid=0"))
        assertTrue("expected uname line; got:\n${out["uname-a"]}", out["uname-a"].orEmpty().contains("Linux"))
    }

    companion object { private const val TAG = "LinuxInAppSelfTest" }
}
