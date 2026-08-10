package dev.example.androidvm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

final class HostStore {
    private static final String PREFS = "hosts";
    private static final String RECORDS = "records";
    private static final String SELECTED = "selected";

    static final class Host {
        String id = UUID.randomUUID().toString();
        String name = "";
        String address = "";
        int sshPort = 22;
        String user = "";
        String password = "";
        int socksPort = 1080;
        boolean acceptUnknown = true;
        String keyName = SshIdentityStore.DEFAULT_NAME;
        String jumpHostId = "";

        String label() {
            return name.trim().isEmpty() ? address : name + " — " + address;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("address", address)
                    .put("sshPort", sshPort).put("user", user).put("password", password)
                    .put("socksPort", socksPort).put("acceptUnknown", acceptUnknown)
                    .put("keyName", keyName).put("jumpHostId", jumpHostId);
        }

        static Host fromJson(JSONObject json) {
            Host host = new Host();
            host.id = json.optString("id", host.id);
            host.name = json.optString("name");
            host.address = json.optString("address");
            host.sshPort = json.optInt("sshPort", 22);
            host.user = json.optString("user");
            host.password = json.optString("password");
            host.socksPort = json.optInt("socksPort", 1080);
            host.acceptUnknown = json.optBoolean("acceptUnknown", true);
            host.keyName = json.optString("keyName", SshIdentityStore.DEFAULT_NAME);
            host.jumpHostId = json.optString("jumpHostId");
            return host;
        }
    }

    private final SharedPreferences preferences;
    private final List<Host> hosts = new ArrayList<>();

    HostStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
        migrateLegacy(context);
    }

    List<Host> all() {
        return new ArrayList<>(hosts);
    }

    Host selected() {
        String id = preferences.getString(SELECTED, "");
        for (Host host : hosts) {
            if (host.id.equals(id)) return host;
        }
        return hosts.isEmpty() ? null : hosts.get(0);
    }

    Host find(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Host host : hosts) {
            if (host.id.equals(id)) return host;
        }
        return null;
    }

    void select(Host host) {
        preferences.edit().putString(SELECTED, host.id).apply();
    }

    void save(Host host) {
        for (int i = 0; i < hosts.size(); i++) {
            if (hosts.get(i).id.equals(host.id)) {
                hosts.set(i, host);
                persist();
                return;
            }
        }
        hosts.add(host);
        if (hosts.size() == 1) select(host);
        persist();
    }

    void delete(Host host) {
        Iterator<Host> iterator = hosts.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().id.equals(host.id)) {
                iterator.remove();
                break;
            }
        }
        for (Host remaining : hosts) {
            if (host.id.equals(remaining.jumpHostId)) remaining.jumpHostId = "";
        }
        Host selected = selected();
        preferences.edit().putString(SELECTED, selected == null ? "" : selected.id).apply();
        persist();
    }

    void replaceMissingKey(String deletedName) {
        boolean changed = false;
        for (Host host : hosts) {
            if (deletedName.equals(host.keyName)) {
                host.keyName = SshIdentityStore.DEFAULT_NAME;
                changed = true;
            }
        }
        if (changed) persist();
    }

    private void load() {
        try {
            JSONArray array = new JSONArray(preferences.getString(RECORDS, "[]"));
            for (int i = 0; i < array.length(); i++) hosts.add(Host.fromJson(array.getJSONObject(i)));
        } catch (JSONException ignored) {
            hosts.clear();
        }
    }

    private void migrateLegacy(Context context) {
        if (!hosts.isEmpty()) return;
        SharedPreferences old = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        String address = old.getString("host", "").trim();
        if (address.isEmpty()) return;
        Host host = new Host();
        host.address = address;
        host.sshPort = port(old.getString("sshPort", "22"), 22);
        host.user = old.getString("user", "");
        host.socksPort = port(old.getString("socksPort", "1080"), 1080);
        host.acceptUnknown = old.getBoolean("acceptUnknown", true);
        save(host);
    }

    private void persist() {
        JSONArray array = new JSONArray();
        try {
            for (Host host : hosts) array.put(host.toJson());
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
        preferences.edit().putString(RECORDS, array.toString()).apply();
    }

    private static int port(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }
}
