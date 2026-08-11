package dev.example.androidvm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DnsServers {
    static final String DEFAULT = "1.1.1.1";

    private DnsServers() {}

    static List<String> defaults() {
        return new ArrayList<>(Collections.singletonList(DEFAULT));
    }

    static List<String> parse(String value) {
        Set<String> servers = new LinkedHashSet<>();
        for (String candidate : value.trim().split("[,\\s]+")) {
            if (candidate.isEmpty()) continue;
            if (!isIpv4Address(candidate)) {
                throw new IllegalArgumentException(candidate);
            }
            servers.add(candidate);
        }
        if (servers.isEmpty()) throw new IllegalArgumentException("");
        return new ArrayList<>(servers);
    }

    static boolean isIpv4Address(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) return false;
            int number = 0;
            for (int i = 0; i < octet.length(); i++) {
                char character = octet.charAt(i);
                if (character < '0' || character > '9') return false;
                number = number * 10 + character - '0';
            }
            if (number > 255) return false;
        }
        return true;
    }

    static String format(List<String> servers) {
        StringBuilder value = new StringBuilder();
        for (String server : servers) {
            if (value.length() > 0) value.append('\n');
            value.append(server);
        }
        return value.toString();
    }
}
