package dev.notipush

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class ProfileRulesTest {
    @Test fun clonedAccountsHaveIndependentRulesAndNamesWithLegacyFallback() {
        val settings = SecureSettings(RuntimeEnvironment.getApplication(), Vault())
        settings.preferences.edit().clear().commit()
        settings.saveRule("com.whatsapp", AppRule(include = "main"))
        assertEquals("main", settings.ruleFor(sourceKey("com.whatsapp", 998))!!.include)
        settings.saveRule(sourceKey("com.whatsapp", 998), AppRule(include = "work", displayName = "WhatsApp SG"))
        settings.saveRule(sourceKey("com.whatsapp", 999), AppRule(enabled = false, displayName = "WhatsApp 2"))
        assertEquals("main", settings.ruleFor("com.whatsapp")!!.include)
        assertEquals("work", settings.ruleFor("com.whatsapp@998")!!.include)
        assertEquals("WhatsApp SG", settings.ruleFor("com.whatsapp@998")!!.displayName)
        assertFalse(settings.ruleFor("com.whatsapp@999")!!.enabled)
        assertNotEquals(sourceKey("com.whatsapp", 998), sourceKey("com.whatsapp", 999))
    }
    @Test fun clonesCanBeDiscoveredFromObservedNotificationsWhenVendorApiIsUnavailable() {
        val settings = SecureSettings(RuntimeEnvironment.getApplication(), Vault())
        settings.preferences.edit().clear().commit()
        settings.rememberSource(SourceApp("com.whatsapp", "WhatsApp SG", 998))
        settings.rememberSource(SourceApp("com.whatsapp", "WhatsApp 2", 999))
        assertEquals(setOf("com.whatsapp@998", "com.whatsapp@999"), settings.observedSources().map { it.key }.toSet())
    }
    @Test fun listenerDiagnosticsAreBoundedAndPreserveAodSkipEvents() {
        val settings = SecureSettings(RuntimeEnvironment.getApplication(), Vault())
        settings.preferences.edit().clear().commit()
        repeat(30) { settings.recordObservation("com.whatsapp@998", DeliveryTiming(postedAt = 1, receivedAt = 2, receivedState = "AOD"), "No message text exposed by Android") }
        val stored = org.json.JSONArray(settings.preferences.getString("listenerObservations", "[]"))
        assertEquals(20, stored.length())
        assertTrue(settings.observationSummary().contains("AOD"))
        assertTrue(settings.observationSummary().contains("No message text exposed"))
        assertFalse(stored.toString().contains("token"))
    }
}
