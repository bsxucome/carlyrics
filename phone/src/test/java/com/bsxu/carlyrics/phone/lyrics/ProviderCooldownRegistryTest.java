package com.bsxu.carlyrics.phone.lyrics;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProviderCooldownRegistryTest {

    @After
    public void clearRegistry() {
        ProviderCooldownRegistry.clearForTests();
    }

    @Test
    public void rateLimitBlocksUntilRetryWindowExpires() {
        ProviderCooldownRegistry.recordResponse("provider", 429, "12", 1000L);

        assertTrue(ProviderCooldownRegistry.isBlocked("provider", 12999L));
        assertFalse(ProviderCooldownRegistry.isBlocked("provider", 13000L));
    }

    @Test
    public void successClearsExistingCooldown() {
        ProviderCooldownRegistry.recordResponse("provider", 503, null, 1000L);
        ProviderCooldownRegistry.recordResponse("provider", 200, null, 2000L);

        assertFalse(ProviderCooldownRegistry.isBlocked("provider", 2001L));
    }

    @Test
    public void retryAfterIsBoundedAndMalformedValuesUseDefault() {
        assertEquals(30000L, ProviderCooldownRegistry.parseRetryAfterMs("invalid"));
        assertEquals(30000L, ProviderCooldownRegistry.parseRetryAfterMs("0"));
        assertEquals(300000L, ProviderCooldownRegistry.parseRetryAfterMs("9999"));
    }
}
