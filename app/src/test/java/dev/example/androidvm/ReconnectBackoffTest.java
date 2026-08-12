package dev.example.androidvm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ReconnectBackoffTest {
    @Test
    public void growsExponentiallyAndStopsAtOneMinute() {
        assertEquals(1_000L, ReconnectBackoff.delayMillis(0));
        assertEquals(2_000L, ReconnectBackoff.delayMillis(1));
        assertEquals(32_000L, ReconnectBackoff.delayMillis(5));
        assertEquals(60_000L, ReconnectBackoff.delayMillis(6));
        assertEquals(60_000L, ReconnectBackoff.delayMillis(100));
    }

    @Test
    public void treatsNegativeFailureCountAsFirstFailure() {
        assertEquals(1_000L, ReconnectBackoff.delayMillis(-1));
    }
}
