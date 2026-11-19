package com.fire17.linuxdesk.genie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StaleDeskdTest {
    private fun cmd(vararg a: String) = a.joinToString("\u0000", postfix = "\u0000").toByteArray()

    @Test fun guestPythonAndProotWrapperMatch() {
        assertTrue(StaleDeskd.isDeskd(StaleDeskd.argv(cmd("/usr/bin/python3", StaleDeskd.MARK))))
        assertTrue(StaleDeskd.isDeskd(StaleDeskd.argv(cmd("/data/app/x/lib/arm64/libproot.so", "--kill-on-exit",
            "-r", "/data/user/0/com.fire17.linuxdesk/files/rootfs-arch", "/usr/bin/env", "-i", "HOME=/root",
            "/usr/bin/python3", StaleDeskd.MARK))))
    }

    @Test fun othersDoNotMatch() {
        assertFalse(StaleDeskd.isDeskd(StaleDeskd.argv(cmd("/usr/bin/python3", "/opt/linuxdesk/deskd/other.py"))))
        assertFalse(StaleDeskd.isDeskd(StaleDeskd.argv(cmd("grep", "deskd.py"))))
        assertFalse(StaleDeskd.isDeskd(StaleDeskd.argv(ByteArray(0))))
    }

    @Test fun findsInFakeProcSkippingExcept() {
        val proc = Files.createTempDirectory("proc").toFile()
        fun pid(n: Int, b: ByteArray) = File(proc, "$n").apply { mkdirs() }.let { File(it, "cmdline").writeBytes(b) }
        pid(8087, cmd("libproot.so", "/usr/bin/python3", StaleDeskd.MARK))
        pid(8097, cmd("/usr/bin/python3", StaleDeskd.MARK))
        pid(8160, cmd("waybar"))
        pid(9000, cmd("/usr/bin/python3", StaleDeskd.MARK))
        File(proc, "self").mkdirs()
        assertEquals(listOf(8087, 8097), StaleDeskd.find(proc, except = setOf(9000)))
        proc.deleteRecursively()
    }
}
