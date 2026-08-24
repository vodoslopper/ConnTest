package dev.example.androidvm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class HostListTransfer {
    static final String FORMAT = "conntest-hosts";
    static final int VERSION = 1;
    static final int MAX_HOSTS = 1000;

    static final class Document {
        final List<HostStore.Host> hosts;
        final String selectedHostId;

        Document(List<HostStore.Host> hosts, String selectedHostId) {
            this.hosts = hosts;
            this.selectedHostId = selectedHostId;
        }
    }

    private HostListTransfer() {}

    static String encode(List<HostStore.Host> hosts, String selectedHostId) {
        if (hosts.size() > MAX_HOSTS) throw new IllegalArgumentException("Too many hosts");
        try {
            JSONArray records = new JSONArray();
            for (HostStore.Host host : hosts) records.put(host.toJson());
            return new JSONObject()
                    .put("format", FORMAT)
                    .put("version", VERSION)
                    .put("selectedHostId", selectedHostId == null ? "" : selectedHostId)
                    .put("hosts", records)
                    .toString(2) + "\n";
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Document decode(String contents) throws JSONException {
        JSONObject root = new JSONObject(contents);
        if (!FORMAT.equals(requiredString(root, "format"))) {
            throw new JSONException("Not a ConnTest hosts file");
        }
        if (requiredInt(root, "version", 1, Integer.MAX_VALUE) != VERSION) {
            throw new JSONException("Unsupported hosts file version");
        }
        JSONArray records = root.optJSONArray("hosts");
        if (records == null) throw new JSONException("Missing hosts list");
        if (records.length() > MAX_HOSTS) throw new JSONException("Too many hosts");

        List<HostStore.Host> hosts = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject json = records.optJSONObject(i);
            if (json == null) throw new JSONException("Host " + (i + 1) + " is not an object");
            HostStore.Host host = decodeHost(json, i + 1);
            if (!ids.add(host.id)) throw new JSONException("Duplicate host id at host " + (i + 1));
            hosts.add(host);
        }
        for (int i = 0; i < hosts.size(); i++) {
            String jumpHostId = hosts.get(i).jumpHostId;
            if (!jumpHostId.isEmpty() && !ids.contains(jumpHostId)) {
                throw new JSONException("Unknown jump host at host " + (i + 1));
            }
            if (hosts.get(i).id.equals(jumpHostId)) {
                throw new JSONException("A host cannot use itself as a jump host");
            }
        }

        String selectedHostId = optionalString(root, "selectedHostId", "");
        if (!selectedHostId.isEmpty() && !ids.contains(selectedHostId)) {
            throw new JSONException("Selected host is not in the hosts list");
        }
        return new Document(hosts, selectedHostId);
    }

    static Document copyForAdd(Document source) {
        Map<String, String> newIds = new HashMap<>();
        for (HostStore.Host host : source.hosts) {
            newIds.put(host.id, UUID.randomUUID().toString());
        }
        List<HostStore.Host> copies = new ArrayList<>();
        for (HostStore.Host sourceHost : source.hosts) {
            HostStore.Host host = copy(sourceHost);
            host.id = newIds.get(sourceHost.id);
            host.jumpHostId = sourceHost.jumpHostId.isEmpty()
                    ? "" : newIds.get(sourceHost.jumpHostId);
            copies.add(host);
        }
        String selected = source.selectedHostId.isEmpty()
                ? "" : newIds.get(source.selectedHostId);
        return new Document(copies, selected);
    }

    private static HostStore.Host decodeHost(JSONObject json, int index) throws JSONException {
        HostStore.Host host = new HostStore.Host();
        host.id = boundedRequiredString(json, "id", 200, index);
        host.name = boundedOptionalString(json, "name", "", 1000, index);
        host.address = boundedRequiredString(json, "address", 4096, index).trim();
        host.sshPort = requiredInt(json, "sshPort", 1, 65535);
        host.user = boundedRequiredString(json, "user", 1000, index).trim();
        host.socksPort = requiredInt(json, "socksPort", 1, 65535);
        Object acceptUnknown = json.opt("acceptUnknown");
        if (!(acceptUnknown instanceof Boolean)) {
            throw new JSONException("Invalid acceptUnknown at host " + index);
        }
        host.acceptUnknown = (Boolean) acceptUnknown;
        host.keyName = boundedOptionalString(json, "keyName",
                SshIdentityStore.DEFAULT_NAME, 1000, index);
        if (host.keyName.isEmpty()) host.keyName = SshIdentityStore.DEFAULT_NAME;
        host.jumpHostId = boundedOptionalString(json, "jumpHostId", "", 200, index);
        if (host.address.isEmpty() || host.user.isEmpty()) {
            throw new JSONException("Host and username are required at host " + index);
        }

        JSONArray dns = json.optJSONArray("dnsServers");
        if (dns == null || dns.length() == 0) {
            throw new JSONException("Missing DNS servers at host " + index);
        }
        List<String> servers = new ArrayList<>();
        for (int i = 0; i < dns.length(); i++) {
            Object value = dns.opt(i);
            if (!(value instanceof String) || !DnsServers.isIpv4Address((String) value)) {
                throw new JSONException("Invalid DNS server at host " + index);
            }
            if (!servers.contains(value)) servers.add((String) value);
        }
        host.dnsServers = servers;
        return host;
    }

    private static HostStore.Host copy(HostStore.Host source) {
        HostStore.Host host = new HostStore.Host();
        host.id = source.id;
        host.name = source.name;
        host.address = source.address;
        host.sshPort = source.sshPort;
        host.user = source.user;
        host.socksPort = source.socksPort;
        host.acceptUnknown = source.acceptUnknown;
        host.keyName = source.keyName;
        host.jumpHostId = source.jumpHostId;
        host.dnsServers = new ArrayList<>(source.dnsServers);
        return host;
    }

    private static String boundedRequiredString(JSONObject json, String key, int limit, int index)
            throws JSONException {
        String value = requiredString(json, key);
        if (value.isEmpty() || value.length() > limit) {
            throw new JSONException("Invalid " + key + " at host " + index);
        }
        return value;
    }

    private static String boundedOptionalString(JSONObject json, String key, String fallback,
            int limit, int index) throws JSONException {
        String value = optionalString(json, key, fallback);
        if (value.length() > limit) throw new JSONException("Invalid " + key + " at host " + index);
        return value;
    }

    private static String requiredString(JSONObject json, String key) throws JSONException {
        Object value = json.opt(key);
        if (!(value instanceof String)) throw new JSONException("Missing or invalid " + key);
        return (String) value;
    }

    private static String optionalString(JSONObject json, String key, String fallback)
            throws JSONException {
        if (!json.has(key)) return fallback;
        Object value = json.opt(key);
        if (!(value instanceof String)) throw new JSONException("Invalid " + key);
        return (String) value;
    }

    private static int requiredInt(JSONObject json, String key, int minimum, int maximum)
            throws JSONException {
        Object value = json.opt(key);
        if (!(value instanceof Number)) throw new JSONException("Missing or invalid " + key);
        long number = ((Number) value).longValue();
        if (number < minimum || number > maximum || ((Number) value).doubleValue() != number) {
            throw new JSONException("Invalid " + key);
        }
        return (int) number;
    }
}
