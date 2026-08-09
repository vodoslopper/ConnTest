package dev.example.androidvm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import org.junit.Test;

public final class SshSocksEndpointTest {
    @Test
    public void parsesIpv4DnsUdpRequest() throws Exception {
        byte[] payload = new byte[]{1, 2, 3, 4};
        byte[] packet = new byte[]{0, 0, 0, 1, 1, 1, 1, 1, 0, 53, 1, 2, 3, 4};

        SshSocksEndpoint.UdpRequest request = SshSocksEndpoint.parseUdpRequest(packet);

        assertEquals("1.1.1.1", request.host);
        assertEquals(53, request.port);
        assertArrayEquals(payload, request.payload);
    }

    @Test(expected = IOException.class)
    public void rejectsFragmentedUdpRequest() throws Exception {
        SshSocksEndpoint.parseUdpRequest(new byte[]{0, 0, 1, 1, 1, 1, 1, 1, 0, 53});
    }

    @Test(expected = IOException.class)
    public void rejectsTruncatedDomainUdpRequest() throws Exception {
        SshSocksEndpoint.parseUdpRequest(new byte[]{0, 0, 0, 3, 4, 't', 'e'});
    }

    @Test(expected = EOFException.class)
    public void exactReadRejectsEarlyEof() throws Exception {
        SshSocksEndpoint.readBytes(new ByteArrayInputStream(new byte[]{1}), 2);
    }
}
