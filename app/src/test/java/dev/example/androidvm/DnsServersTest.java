package dev.example.androidvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class DnsServersTest {
    @Test
    public void parsesWhitespaceAndCommaSeparatedServers() {
        assertEquals(Arrays.asList("1.1.1.1", "8.8.8.8", "9.9.9.9"),
                DnsServers.parse("1.1.1.1\n8.8.8.8, 9.9.9.9"));
    }

    @Test
    public void removesDuplicateServersWithoutReordering() {
        assertEquals(Arrays.asList("8.8.8.8", "1.1.1.1"),
                DnsServers.parse("8.8.8.8 1.1.1.1 8.8.8.8"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyList() {
        DnsServers.parse("  \n ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidAddress() {
        DnsServers.parse("1.1.1.1 256.0.0.1");
    }

    @Test
    public void validatesNumericIpv4Only() {
        assertTrue(DnsServers.isIpv4Address("192.0.2.53"));
        assertFalse(DnsServers.isIpv4Address("dns.example.org"));
        assertFalse(DnsServers.isIpv4Address("2001:db8::53"));
    }
}
