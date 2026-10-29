package dev.notipush

import org.junit.Assert.*
import org.junit.Test

class MessagePolicyTest {
    @Test fun filtersMatchAnyIncludeAndExcludeWins() {
        val rule = AppRule(include = " family, WORK, ", exclude = "promo, secret")
        assertTrue(rule.accepts("Family", "Hello"))
        assertTrue(rule.accepts("A colleague", "work update"))
        assertFalse(rule.accepts("Family", "secret"))
        assertFalse(rule.accepts("Other", "Hello"))
        assertTrue(AppRule().accepts("Any", "Message"))
    }
    @Test fun truncationPreservesUnicodeAndPushoverLimits() {
        val text = "😀".repeat(1100)
        val result = MessagePolicy.truncate(text, 1024)
        assertEquals(1024, result.codePointCount(0, result.length))
        assertTrue(result.endsWith("…"))
        assertFalse(result.contains('\uFFFD'))
        assertEquals("hello", MessagePolicy.truncate("hello", 250))
    }
    @Test fun credentialsAndDevicesAreValidated() {
        val good = Destination("a".repeat(30), "Z".repeat(30))
        assertNull(good.validationError())
        assertNull(good.copy(device = "iphone,my-ipad").validationError())
        assertNotNull(good.copy(token = "bad").validationError())
        assertNotNull(good.copy(device = "phone with spaces").validationError())
        assertNotNull(good.copy(priority = 2).validationError())
        assertNotNull(good.copy(device = "x".repeat(26)).validationError())
    }
    @Test fun invalidRequestsAndQuotaAreNeverAutomaticallyRetried() {
        listOf(200, 301, 400, 401, 403, 408, 429).forEach { assertFalse(MessagePolicy.retryable(it)) }
        listOf(500, 502, 503, 599).forEach { assertTrue(MessagePolicy.retryable(it)) }
    }
    @Test fun fingerprintsSeparateFieldsAndDistinguishMessages() {
        assertEquals(MessagePolicy.fingerprint("a", "b"), MessagePolicy.fingerprint("a", "b"))
        assertNotEquals(MessagePolicy.fingerprint("ab", "c"), MessagePolicy.fingerprint("a", "bc"))
        assertNotEquals(MessagePolicy.fingerprint("app", "123", "Hi"), MessagePolicy.fingerprint("app", "124", "Hi"))
    }
}
