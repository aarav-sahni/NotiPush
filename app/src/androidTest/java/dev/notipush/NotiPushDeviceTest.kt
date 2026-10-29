package dev.notipush

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NotiPushDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun resetApp() {
        compose.activity.repository.reset()
        compose.waitUntil(5000) { compose.activity.repository.settings.rules().size == 3 }
    }

    @Test fun navigationAndInitialSetupAreUsable() {
        compose.onNodeWithText("Your notifications.\nEverywhere.").assertIsDisplayed()
        compose.onNodeWithText("STANDBY").assertExists()
        compose.onNodeWithText("Apps", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Pick your sources.").assertIsDisplayed()
        compose.onNodeWithText("WhatsApp", substring = false).assertExists()
        compose.onNodeWithText("Activity", substring = false).performClick()
        compose.onNodeWithText("A clean slate.").assertIsDisplayed()
        compose.onNodeWithText("Setup", substring = false).performClick()
        compose.onNodeWithText("Make it yours.").assertIsDisplayed()
        compose.onNodeWithText("Application API token").assertExists()
    }

    @Test fun androidKeystoreEncryptsAndAuthenticatesLocalSecrets() {
        val vault = compose.activity.repository.vault
        val secret = "Private message नमस्ते 😀"
        val a = vault.encrypt(secret)
        val b = vault.encrypt(secret)
        assertNotEquals(a, b)
        assertFalse(a.contains("Private"))
        assertEquals(secret, vault.decrypt(a))
        val bytes = android.util.Base64.decode(a, android.util.Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(runCatching { vault.decrypt(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)) }.isFailure)
        vault.eraseKey()
        assertTrue(runCatching { vault.decrypt(a) }.isFailure)
        assertEquals(secret, vault.decrypt(vault.encrypt(secret)))
    }

    @Test fun destinationEntryIsValidatedAndSavedEncrypted() {
        compose.onNodeWithText("Setup", substring = false).performClick()
        compose.onNodeWithText("Application API token").performTextInput("bad")
        compose.onNodeWithText("User or group key").performTextInput("b".repeat(30))
        compose.onNodeWithText("Save destination").performScrollTo().performClick()
        compose.onNodeWithText("The application API token must be 30 letters or numbers.").assertExists()
        compose.onNodeWithText("Application API token").performScrollTo().performTextReplacement("a".repeat(30))
        compose.onNodeWithText("Save destination").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.activity.repository.destinationOrNull() != null }
        val settings = compose.activity.repository.settings
        assertEquals("a".repeat(30), settings.destination().token)
        val stored = settings.preferences.getString("destination", "")!!
        assertFalse(stored.contains("a".repeat(30)))
        assertFalse(stored.contains("b".repeat(30)))
    }

    @Test fun captureScreensForVisualReview() {
        // This flag is cleared only by the test APK; production screens remain protected.
        compose.activityRule.scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        val device = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun capture(name: String) {
            compose.waitForIdle()
            // Compose idleness does not include the system's splash/window transition.
            android.os.SystemClock.sleep(800)
            val file = File(compose.activity.getExternalFilesDir(null), "$name.png")
            device.takeScreenshot().useBitmap { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            // Gradle uninstalls test apps at the end; retain these non-sensitive demo screens.
            val command = device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/notipush-$name.png")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
        }
        capture("overview")
        compose.onNodeWithText("Apps", substring = false).performClick(); capture("apps")
        compose.onNodeWithText("Activity", substring = false).performClick(); capture("activity")
        compose.onNodeWithText("Setup", substring = false).performClick(); capture("setup")
    }

    private fun Bitmap.useBitmap(action: (Bitmap) -> Unit) { try { action(this) } finally { recycle() } }
}
