package dev.example.androidvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.util.Arrays;

public final class HostListTransferTest {
    @Test
    public void roundTripPreservesConfigurationSelectionAndJumpHost() throws Exception {
        HostStore.Host jump = host("jump-id", "Jump", "jump.example", "relay");
        HostStore.Host destination = host("destination-id", "Destination", "ssh.example", "alice");
        destination.keyName = "work";
        destination.jumpHostId = jump.id;
        destination.acceptUnknown = false;
        destination.dnsServers = Arrays.asList("1.1.1.1", "9.9.9.9");

        String encoded = HostListTransfer.encode(Arrays.asList(jump, destination), destination.id);
        HostListTransfer.Document decoded = HostListTransfer.decode(encoded);

        assertEquals(HostListTransfer.FORMAT,
                new org.json.JSONObject(encoded).getString("format"));
        assertEquals(2, decoded.hosts.size());
        assertEquals(destination.id, decoded.selectedHostId);
        assertEquals(jump.id, decoded.hosts.get(1).jumpHostId);
        assertFalse(new org.json.JSONObject(encoded).getJSONArray("hosts")
                .getJSONObject(1).has("password"));
        assertEquals("work", decoded.hosts.get(1).keyName);
        assertFalse(decoded.hosts.get(1).acceptUnknown);
        assertEquals(Arrays.asList("1.1.1.1", "9.9.9.9"), decoded.hosts.get(1).dnsServers);
    }

    @Test
    public void legacyImportedPasswordIsDiscarded() throws Exception {
        HostStore.Host source = host("one", "One", "one.example", "alice");
        org.json.JSONObject legacy = new org.json.JSONObject(
                HostListTransfer.encode(Arrays.asList(source), source.id));
        legacy.getJSONArray("hosts").getJSONObject(0).put("password", "legacy secret");

        HostListTransfer.Document decoded = HostListTransfer.decode(legacy.toString());
        org.json.JSONObject reexported = new org.json.JSONObject(
                HostListTransfer.encode(decoded.hosts, decoded.selectedHostId));

        assertFalse(reexported.getJSONArray("hosts").getJSONObject(0).has("password"));
    }

    @Test
    public void addCopyAssignsNewIdsAndPreservesInternalJumpRelationship() throws Exception {
        HostStore.Host jump = host("jump-id", "Jump", "jump.example", "relay");
        HostStore.Host destination = host("destination-id", "Destination", "ssh.example", "alice");
        destination.jumpHostId = jump.id;
        HostListTransfer.Document original = new HostListTransfer.Document(
                Arrays.asList(jump, destination), destination.id);

        HostListTransfer.Document copy = HostListTransfer.copyForAdd(original);

        assertNotEquals(jump.id, copy.hosts.get(0).id);
        assertNotEquals(destination.id, copy.hosts.get(1).id);
        assertEquals(copy.hosts.get(0).id, copy.hosts.get(1).jumpHostId);
        assertEquals(copy.hosts.get(1).id, copy.selectedHostId);
        assertEquals("jump-id", original.hosts.get(0).id);
    }

    @Test(expected = JSONException.class)
    public void rejectsUnsupportedVersion() throws Exception {
        HostListTransfer.decode("{\"format\":\"conntest-hosts\",\"version\":2,\"hosts\":[]}");
    }

    @Test(expected = JSONException.class)
    public void rejectsOutOfRangePort() throws Exception {
        String json = HostListTransfer.encode(
                Arrays.asList(host("one", "One", "one.example", "alice")), "one")
                .replace("\"sshPort\": 22", "\"sshPort\": 70000");
        HostListTransfer.decode(json);
    }

    @Test(expected = JSONException.class)
    public void rejectsDanglingJumpHost() throws Exception {
        HostStore.Host host = host("one", "One", "one.example", "alice");
        host.jumpHostId = "missing";
        HostListTransfer.decode(HostListTransfer.encode(Arrays.asList(host), host.id));
    }

    @Test
    public void emptyDocumentIsValid() throws Exception {
        HostListTransfer.Document decoded = HostListTransfer.decode(
                HostListTransfer.encode(java.util.Collections.emptyList(), ""));
        assertTrue(decoded.hosts.isEmpty());
        assertEquals("", decoded.selectedHostId);
    }

    private static HostStore.Host host(String id, String name, String address, String user) {
        HostStore.Host host = new HostStore.Host();
        host.id = id;
        host.name = name;
        host.address = address;
        host.user = user;
        return host;
    }
}
