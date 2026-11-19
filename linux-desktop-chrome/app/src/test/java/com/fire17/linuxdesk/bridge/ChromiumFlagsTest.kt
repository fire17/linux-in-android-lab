package com.fire17.linuxdesk.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ChromiumFlagsTest {

    private val phone = ChromiumFlags.Params(
        mode = ChromiumFlags.Mode.BROWSER,
        url = "example.com",
        density = 2.625f,
        scalePercent = 100,
        screenWidthPx = 1080,
        screenHeightPx = 2400,
        extensionDir = "/opt/linuxdesk/focus-bridge",
        acceptLanguages = "he-IL,en-US",
    )

    @Test fun mandatoryFlagsPresent() {
        val f = ChromiumFlags.build(phone)
        for (flag in listOf("--no-sandbox", "--touch-events=enabled", "--ozone-platform=x11", "--disable-gpu")) {
            assertTrue("missing $flag in $f", flag in f)
        }
    }

    @Test fun scaleFollowsDensity() {
        val f = ChromiumFlags.build(phone)
        assertTrue("--force-device-scale-factor=2.625" in f)
        val bigger = ChromiumFlags.build(phone.copy(scalePercent = 120))
        assertTrue("--force-device-scale-factor=3.15" in bigger)
    }

    @Test fun scaleIsLocaleIndependent() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // comma decimal separator
            assertEquals("2.625", ChromiumFlags.formatScale(2.625f))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun windowSizeIsInDips() {
        val f = ChromiumFlags.build(phone)
        assertTrue("--window-size=411,914" in f) // 1080/2.625, 2400/2.625 rounded
    }

    @Test fun modes() {
        assertEquals("https://example.com", ChromiumFlags.build(phone).last())
        val app = ChromiumFlags.build(phone.copy(mode = ChromiumFlags.Mode.APP))
        assertTrue("--app=https://example.com" in app)
        assertFalse("--start-maximized" in app)
        val kiosk = ChromiumFlags.build(phone.copy(mode = ChromiumFlags.Mode.KIOSK))
        assertTrue("--kiosk" in kiosk)
    }

    @Test fun extensionNeedsFeatureOverride() {
        val f = ChromiumFlags.build(phone)
        assertTrue("--load-extension=/opt/linuxdesk/focus-bridge" in f)
        assertTrue("--disable-features=DisableLoadExtensionCommandLineSwitch" in f)
        val none = ChromiumFlags.build(phone.copy(extensionDir = null))
        assertFalse(none.any { it.startsWith("--load-extension") })
    }

    @Test fun urlNormalization() {
        assertEquals(ChromiumFlags.DEFAULT_URL, ChromiumFlags.normalizeUrl("  "))
        assertEquals("https://ynet.co.il", ChromiumFlags.normalizeUrl("ynet.co.il"))
        assertEquals("http://10.0.0.2:8080", ChromiumFlags.normalizeUrl("http://10.0.0.2:8080"))
        assertEquals("about:blank", ChromiumFlags.normalizeUrl("about:blank"))
    }
}
