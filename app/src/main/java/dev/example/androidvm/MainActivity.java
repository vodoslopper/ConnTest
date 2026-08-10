package dev.example.androidvm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.jcraft.jsch.JSchException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int ROUTING_REQUEST = 100;
    private static final int MENU_HOME = 1;
    private static final int MENU_HOSTS = 2;
    private static final int MENU_KEYS = 3;
    private static final int MENU_THEME = 4;
    private static final String APPEARANCE_PREFS = "appearance";
    private static final String NIGHT_OVERRIDE = "nightOverride";
    private static final String DEFAULT_TEST_URL = "https://api.ipify.org?format=json";
    private static final int MAX_HTTP_RESPONSE_BYTES = 64 * 1024;

    private final Handler statusHandler = new Handler();
    private final ExecutorService httpWorker = Executors.newSingleThreadExecutor();
    private HostStore hostStore;
    private TextView selectedHostView;
    private TextView statusView;
    private Button connectButton;
    private ColorStateList defaultConnectButtonBackgroundTint;
    private Boolean connectButtonConnected;
    private Button logsButton;
    private TextView logsView;
    private EditText testUrlField;
    private TextView httpResultView;
    private Intent pendingConnectIntent;
    private int page = MENU_HOME;

    @Override protected void attachBaseContext(Context newBase) {
        int override = newBase.getSharedPreferences(APPEARANCE_PREFS, MODE_PRIVATE)
                .getInt(NIGHT_OVERRIDE, 0);
        Configuration configuration = new Configuration(newBase.getResources().getConfiguration());
        if (override == Configuration.UI_MODE_NIGHT_YES
                || override == Configuration.UI_MODE_NIGHT_NO) {
            configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | override;
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration));
    }

    private final Runnable statusUpdater = new Runnable() {
        @Override public void run() {
            if (statusView != null) {
                statusView.setText(getString(R.string.status_format,
                        ConnTestRoutingService.getStatus(MainActivity.this)));
            }
            if (connectButton != null) {
                updateConnectButton();
            }
            if (logsView != null && logsView.getVisibility() == View.VISIBLE) updateLogs();
            statusHandler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hostStore = new HostStore(this);
        try {
            SshIdentityStore.ensureDefault(this);
        } catch (IOException | JSchException exception) {
            showError(getString(R.string.key_generation_failed, readableMessage(exception)));
        }
        showMainPage();
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_HOME, 0, R.string.menu_home).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_HOSTS, 1, R.string.menu_hosts).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_KEYS, 2, R.string.menu_keys).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_THEME, 3, R.string.menu_switch_theme).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_HOME) showMainPage();
        else if (item.getItemId() == MENU_HOSTS) showHostsPage();
        else if (item.getItemId() == MENU_KEYS) showKeysPage();
        else if (item.getItemId() == MENU_THEME) switchTheme();
        else return super.onOptionsItemSelected(item);
        return true;
    }

    @Override public void onBackPressed() {
        if (page != MENU_HOME) showMainPage(); else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        statusHandler.post(statusUpdater);
    }

    @Override protected void onPause() {
        statusHandler.removeCallbacks(statusUpdater);
        super.onPause();
    }

    @Override protected void onDestroy() {
        httpWorker.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ROUTING_REQUEST && resultCode == RESULT_OK && pendingConnectIntent != null) {
            startRoutingService(pendingConnectIntent);
            pendingConnectIntent = null;
        } else if (requestCode == ROUTING_REQUEST) {
            pendingConnectIntent = null;
            ConnectionLog.append("Android routing permission was denied");
            Toast.makeText(this, R.string.routing_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void showMainPage() {
        page = MENU_HOME;
        setTitle(R.string.app_name);
        LinearLayout content = pageLayout();

        TextView heading = heading(R.string.app_name);
        content.addView(heading);
        TextView intro = text(R.string.intro_short);
        intro.setPadding(0, dp(6), 0, dp(20));
        content.addView(intro);

        selectedHostView = new Button(this);
        selectedHostView.setTextSize(20);
        selectedHostView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        selectedHostView.setPadding(dp(16), dp(18), dp(16), dp(18));
        ((Button) selectedHostView).setAllCaps(false);
        selectedHostView.setOnClickListener(view -> chooseHost());
        content.addView(selectedHostView, matchWrap());
        refreshSelectedHost();

        connectButton = new Button(this);
        defaultConnectButtonBackgroundTint = themeColorStateList(android.R.attr.colorButtonNormal);
        connectButtonConnected = null;
        connectButton.setTextSize(22);
        connectButton.setMinHeight(dp(76));
        connectButton.setOnClickListener(view -> {
            if (ConnTestRoutingService.isConnected()) disconnect(); else requestConnect();
        });
        LinearLayout.LayoutParams connectParams = matchWrap();
        connectParams.setMargins(0, dp(20), 0, 0);
        content.addView(connectButton, connectParams);
        updateConnectButton();

        statusView = text(0);
        statusView.setText(getString(R.string.status_format, ConnTestRoutingService.getStatus(this)));
        statusView.setPadding(0, dp(12), 0, dp(24));
        content.addView(statusView);

        TextView testHeading = heading(R.string.test_section);
        testHeading.setTextSize(20);
        content.addView(testHeading);
        testUrlField = field(R.string.test_url, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        testUrlField.setText(DEFAULT_TEST_URL);
        content.addView(testUrlField);
        Button test = new Button(this);
        test.setText(R.string.send_http_request);
        test.setOnClickListener(view -> requestHttpTest());
        content.addView(test);
        httpResultView = text(R.string.http_result_waiting);
        httpResultView.setTextIsSelectable(true);
        httpResultView.setTypeface(Typeface.MONOSPACE);
        httpResultView.setPadding(0, dp(8), 0, dp(20));
        content.addView(httpResultView);

        TextView logsHeading = heading(R.string.logs_section);
        logsHeading.setTextSize(20);
        content.addView(logsHeading);
        logsButton = button(R.string.show_logs, view -> toggleLogs());
        content.addView(logsButton);
        LinearLayout logActions = new LinearLayout(this);
        logActions.addView(button(R.string.share_logs, view -> shareLogs()), weighted());
        logActions.addView(button(R.string.clear_logs, view -> { ConnectionLog.clear(); updateLogs(); }), weighted());
        content.addView(logActions);
        logsView = text(0);
        logsView.setTextIsSelectable(true);
        logsView.setTypeface(Typeface.MONOSPACE);
        logsView.setVisibility(View.GONE);
        content.addView(logsView);
        setScrollableContent(content);
    }

    private void updateConnectButton() {
        boolean connected = ConnTestRoutingService.isConnected();
        if (connectButtonConnected != null && connectButtonConnected == connected) return;
        connectButtonConnected = connected;
        connectButton.setText(connected ? R.string.disconnect : R.string.connect);
        if (connected) {
            connectButton.setBackgroundTintList(ColorStateList.valueOf(
                    getColor(R.color.disconnect_button_green)));
            connectButton.setTextColor(getColor(R.color.disconnect_button_text));
        } else {
            connectButton.setBackgroundTintList(defaultConnectButtonBackgroundTint);
            connectButton.setTextColor(getColor(R.color.connect_button_green));
        }
    }

    private ColorStateList themeColorStateList(int attribute) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        return value.resourceId != 0
                ? getColorStateList(value.resourceId)
                : ColorStateList.valueOf(value.data);
    }

    private void showHostsPage() {
        page = MENU_HOSTS;
        clearMainReferences();
        setTitle(R.string.menu_hosts);
        LinearLayout content = pageLayout();
        content.addView(heading(R.string.hosts_title));
        TextView hint = text(R.string.hosts_hint);
        hint.setPadding(0, dp(4), 0, dp(12));
        content.addView(hint);
        for (HostStore.Host host : hostStore.all()) {
            Button row = new Button(this);
            row.setAllCaps(false);
            row.setText(host.label());
            row.setContentDescription(getString(R.string.edit_host_description, host.label()));
            row.setOnClickListener(view -> showHostDialog(host));
            content.addView(row, matchWrap());
        }
        Button add = button(R.string.add_host, view -> showHostDialog(null));
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(12), 0, 0);
        content.addView(add, params);
        setScrollableContent(content);
    }

    private void showHostDialog(HostStore.Host existing) {
        HostStore.Host host = existing == null ? new HostStore.Host() : existing;
        LinearLayout form = dialogLayout();
        EditText name = field(R.string.host_name_optional, InputType.TYPE_CLASS_TEXT);
        EditText address = field(R.string.ssh_host, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText sshPort = field(R.string.ssh_port, InputType.TYPE_CLASS_NUMBER);
        EditText user = field(R.string.ssh_username, InputType.TYPE_CLASS_TEXT);
        EditText password = field(R.string.ssh_password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText socksPort = field(R.string.socks_port, InputType.TYPE_CLASS_NUMBER);
        CheckBox acceptUnknown = new CheckBox(this);
        acceptUnknown.setText(R.string.accept_unknown_host);
        Spinner keys = new Spinner(this);
        Spinner jumpHosts = new Spinner(this);
        List<String> keyNames;
        try { keyNames = SshIdentityStore.names(this); }
        catch (IOException | JSchException exception) { showError(readableMessage(exception)); return; }
        keys.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, keyNames));
        List<HostStore.Host> jumpCandidates = new ArrayList<>();
        List<String> jumpLabels = new ArrayList<>();
        jumpLabels.add(getString(R.string.jump_host_direct));
        for (HostStore.Host candidate : hostStore.all()) {
            if (!candidate.id.equals(host.id)) {
                jumpCandidates.add(candidate);
                jumpLabels.add(candidate.label());
            }
        }
        jumpHosts.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, jumpLabels));
        name.setText(host.name); address.setText(host.address); sshPort.setText(Integer.toString(host.sshPort));
        user.setText(host.user); password.setText(host.password); socksPort.setText(Integer.toString(host.socksPort));
        acceptUnknown.setChecked(host.acceptUnknown);
        int keyIndex = keyNames.indexOf(host.keyName);
        keys.setSelection(Math.max(0, keyIndex));
        int jumpIndex = 0;
        for (int i = 0; i < jumpCandidates.size(); i++) {
            if (jumpCandidates.get(i).id.equals(host.jumpHostId)) jumpIndex = i + 1;
        }
        form.addView(name); form.addView(address); form.addView(sshPort); form.addView(user); form.addView(password);
        TextView keyLabel = text(R.string.host_key); keyLabel.setPadding(0, dp(10), 0, 0); form.addView(keyLabel);
        form.addView(keys);
        TextView jumpLabel = text(R.string.jump_host); jumpLabel.setPadding(0, dp(10), 0, 0); form.addView(jumpLabel);
        form.addView(jumpHosts); jumpHosts.setSelection(jumpIndex);
        form.addView(socksPort); form.addView(acceptUnknown);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.add_host : R.string.edit_host)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, null);
        if (existing != null) builder.setNeutralButton(R.string.delete, null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    String hostAddress = address.getText().toString().trim();
                    String hostUser = user.getText().toString().trim();
                    if (hostAddress.isEmpty() || hostUser.isEmpty()) throw new IllegalArgumentException(getString(R.string.host_and_user_required));
                    host.name = name.getText().toString().trim(); host.address = hostAddress; host.user = hostUser;
                    host.sshPort = parsePort(sshPort.getText().toString(), getString(R.string.ssh_port));
                    host.socksPort = parsePort(socksPort.getText().toString(), getString(R.string.socks_port));
                    host.password = password.getText().toString(); host.acceptUnknown = acceptUnknown.isChecked();
                    host.keyName = (String) keys.getSelectedItem();
                    int selectedJump = jumpHosts.getSelectedItemPosition();
                    host.jumpHostId = selectedJump == 0 ? ""
                            : jumpCandidates.get(selectedJump - 1).id;
                    hostStore.save(host); dialog.dismiss(); showHostsPage();
                } catch (IllegalArgumentException exception) { showError(exception.getMessage()); }
            });
            if (existing != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
                    new AlertDialog.Builder(this).setMessage(R.string.delete_host_warning)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.delete, (d, w) -> { hostStore.delete(host); dialog.dismiss(); showHostsPage(); }).show());
        });
        dialog.show();
    }

    private void showKeysPage() {
        page = MENU_KEYS;
        clearMainReferences();
        setTitle(R.string.menu_keys);
        LinearLayout content = pageLayout();
        content.addView(heading(R.string.keys_title));
        TextView hint = text(R.string.keys_hint);
        hint.setPadding(0, dp(4), 0, dp(12));
        content.addView(hint);
        try {
            for (String name : SshIdentityStore.names(this)) {
                SshIdentityStore key = SshIdentityStore.load(this, name);
                Button row = new Button(this);
                row.setAllCaps(false);
                row.setText(name);
                row.setContentDescription(getString(R.string.view_key_description, name));
                row.setOnClickListener(view -> showKeyDialog(key));
                content.addView(row, matchWrap());
            }
        } catch (IOException | JSchException exception) { showError(readableMessage(exception)); }
        Button add = button(R.string.add_key, view -> showCreateKeyDialog());
        LinearLayout.LayoutParams params = matchWrap(); params.setMargins(0, dp(12), 0, 0);
        content.addView(add, params);
        setScrollableContent(content);
    }

    private void showCreateKeyDialog() {
        EditText name = field(R.string.key_name, InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle(R.string.add_key).setView(name)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.create, (dialog, which) -> {
                    try { SshIdentityStore key = SshIdentityStore.create(this, name.getText().toString().trim()); showKeyDialog(key); showKeysPage(); }
                    catch (IOException | JSchException exception) { showError(readableMessage(exception)); }
                }).show();
    }

    private void showKeyDialog(SshIdentityStore key) {
        LinearLayout content = dialogLayout();
        TextView publicKey = text(0);
        publicKey.setText(key.getPublicKey());
        publicKey.setTextIsSelectable(true);
        publicKey.setPadding(0, dp(12), 0, dp(12));
        content.addView(publicKey);
        LinearLayout publicKeyActions = new LinearLayout(this);
        publicKeyActions.addView(button(R.string.copy_public_key, view -> copy(key.getPublicKey())), weighted());
        publicKeyActions.addView(button(R.string.share_public_key, view -> sharePublicKey(key)), weighted());
        content.addView(publicKeyActions);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(key.getName()).setView(content)
                .setPositiveButton(R.string.close, null)
                .setNegativeButton(R.string.regenerate, null);
        if (!SshIdentityStore.DEFAULT_NAME.equals(key.getName())) {
            builder.setNeutralButton(R.string.delete, null);
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> confirmRegenerate(key.getName(), dialog));
            if (!SshIdentityStore.DEFAULT_NAME.equals(key.getName())) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> confirmDeleteKey(key.getName(), dialog));
            }
        });
        dialog.show();
    }

    private void confirmRegenerate(String name, AlertDialog parent) {
        new AlertDialog.Builder(this).setMessage(R.string.regenerate_key_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.regenerate, (d, w) -> {
                    try { SshIdentityStore.regenerate(this, name); parent.dismiss(); showKeysPage(); }
                    catch (IOException | JSchException exception) { showError(readableMessage(exception)); }
                }).show();
    }

    private void confirmDeleteKey(String name, AlertDialog parent) {
        new AlertDialog.Builder(this).setMessage(R.string.delete_key_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    try { SshIdentityStore.delete(this, name); hostStore.replaceMissingKey(name); parent.dismiss(); showKeysPage(); }
                    catch (IOException exception) { showError(readableMessage(exception)); }
                }).show();
    }

    private void chooseHost() {
        List<HostStore.Host> hosts = hostStore.all();
        if (hosts.isEmpty()) { showHostDialog(null); return; }
        String[] labels = new String[hosts.size()];
        for (int i = 0; i < hosts.size(); i++) labels[i] = hosts.get(i).label();
        new AlertDialog.Builder(this).setTitle(R.string.choose_host).setItems(labels, (dialog, which) -> {
            hostStore.select(hosts.get(which)); refreshSelectedHost();
        }).setNeutralButton(R.string.add_host, (dialog, which) -> showHostDialog(null)).show();
    }

    private void refreshSelectedHost() {
        HostStore.Host host = hostStore.selected();
        if (host == null) selectedHostView.setText(R.string.no_hosts);
        else selectedHostView.setText(host.label());
    }

    private void requestConnect() {
        HostStore.Host host = hostStore.selected();
        if (host == null) { showHostDialog(null); return; }
        try {
            SshIdentityStore identity = SshIdentityStore.load(this, host.keyName);
            HostStore.Host jump = hostStore.find(host.jumpHostId);
            SshIdentityStore jumpIdentity = jump == null ? null
                    : SshIdentityStore.load(this, jump.keyName);
            ConnectionLog.clear();
            ConnectionLog.append("Connection requested for " + host.user + "@" + host.address + ":" + host.sshPort + " using key '" + host.keyName + "'");
            pendingConnectIntent = ConnTestRoutingService.connectIntent(this, host.address, host.sshPort,
                    host.user, identity.readPrivateKey(), host.password, host.socksPort,
                    host.acceptUnknown, jump, jumpIdentity == null ? null : jumpIdentity.readPrivateKey());
            Intent permissionIntent = VpnService.prepare(this);
            if (permissionIntent == null) { startRoutingService(pendingConnectIntent); pendingConnectIntent = null; }
            else { ConnectionLog.append("Waiting for Android routing permission"); startActivityForResult(permissionIntent, ROUTING_REQUEST); }
        } catch (IOException | JSchException exception) { showError(readableMessage(exception)); }
    }

    private void disconnect() {
        ConnectionLog.append("Disconnect requested by user");
        startService(ConnTestRoutingService.disconnectIntent(this));
    }

    private void toggleLogs() {
        boolean show = logsView.getVisibility() != View.VISIBLE;
        logsView.setVisibility(show ? View.VISIBLE : View.GONE);
        logsButton.setText(show ? R.string.hide_logs : R.string.show_logs);
        if (show) updateLogs();
    }

    private void requestHttpTest() {
        if (!ConnTestRoutingService.isConnected()) { Toast.makeText(this, R.string.routing_not_connected, Toast.LENGTH_LONG).show(); return; }
        String value = testUrlField.getText().toString().trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) { Toast.makeText(this, R.string.invalid_http_url, Toast.LENGTH_LONG).show(); return; }
        httpResultView.setText(getString(R.string.http_request_running, value));
        ConnectionLog.append("HTTP routing test requesting " + value);
        httpWorker.execute(() -> runHttpTest(value));
    }

    private void runHttpTest(String value) {
        HttpURLConnection connection = null;
        try {
            int endpointPort = ConnTestRoutingService.getLocalEndpointPort();
            if (endpointPort <= 0) throw new IOException("SSH endpoint is not ready");
            connection = (HttpURLConnection) new URL(value).openConnection(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", endpointPort)));
            connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000); connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "text/plain, application/json, */*"); connection.setRequestProperty("User-Agent", "ConnTest/5");
            int code = connection.getResponseCode(); String message = connection.getResponseMessage();
            InputStream response = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = response == null ? "" : readHttpResponse(response);
            String result = getString(R.string.http_result_format, code, message == null ? "" : message, body);
            ConnectionLog.append("HTTP routing test completed with status " + code + " and " + body.getBytes(StandardCharsets.UTF_8).length + " response bytes");
            runOnUiThread(() -> { if (httpResultView != null) httpResultView.setText(result); });
        } catch (Exception exception) {
            String message = getString(R.string.http_request_failed, readableMessage(exception));
            ConnectionLog.append(message); runOnUiThread(() -> { if (httpResultView != null) httpResultView.setText(message); });
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static String readHttpResponse(InputStream input) throws IOException {
        try (InputStream response = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int total = 0; int count;
            while ((count = response.read(buffer)) != -1) {
                if (total + count > MAX_HTTP_RESPONSE_BYTES) {
                    int remaining = MAX_HTTP_RESPONSE_BYTES - total; if (remaining > 0) output.write(buffer, 0, remaining);
                    output.write("\n[response truncated]".getBytes(StandardCharsets.UTF_8)); break;
                }
                output.write(buffer, 0, count); total += count;
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void updateLogs() {
        if (logsView == null) return;
        String logs = ConnectionLog.snapshot(); logsView.setText(logs.isEmpty() ? getString(R.string.no_logs) : logs);
    }

    private void shareLogs() {
        try {
            File directory = new File(getCacheDir(), "shared-logs");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("could not create log-share directory");
            File file = new File(directory, "conntest-connection-log.txt");
            try (FileOutputStream output = new FileOutputStream(file, false)) { output.write(ConnectionLog.snapshot().getBytes(StandardCharsets.UTF_8)); }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("ConnTest connection log", uri));
            startActivity(Intent.createChooser(share, getString(R.string.share_logs)));
        } catch (IOException | IllegalArgumentException exception) { showError(getString(R.string.share_logs_failed, readableMessage(exception))); }
    }

    private void copy(String value) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("SSH public key", value));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void sharePublicKey(SshIdentityStore key) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.public_key_subject, key.getName()))
                .putExtra(Intent.EXTRA_TEXT, key.getPublicKey());
        startActivity(Intent.createChooser(share, getString(R.string.share_public_key)));
    }

    private void switchTheme() {
        int current = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int next = current == Configuration.UI_MODE_NIGHT_YES
                ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES;
        getSharedPreferences(APPEARANCE_PREFS, MODE_PRIVATE).edit()
                .putInt(NIGHT_OVERRIDE, next).apply();
        recreate();
    }

    private void startRoutingService(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private LinearLayout pageLayout() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(28));
        return content;
    }

    private LinearLayout dialogLayout() {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), 0, dp(20), dp(8)); return content;
    }

    private void setScrollableContent(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        content.post(() -> applyActionBarInset(content));
    }

    private void applyActionBarInset(LinearLayout content) {
        int actionBarId = getResources().getIdentifier("action_bar_container", "id", "android");
        View actionBar = actionBarId == 0 ? null : getWindow().getDecorView().findViewById(actionBarId);
        if (actionBar == null || actionBar.getVisibility() != View.VISIBLE) {
            return;
        }
        int[] actionBarLocation = new int[2];
        int[] contentLocation = new int[2];
        actionBar.getLocationOnScreen(actionBarLocation);
        content.getLocationOnScreen(contentLocation);
        int overlap = Math.max(0,
                actionBarLocation[1] + actionBar.getHeight() - contentLocation[1]);
        content.setPadding(dp(20), dp(20) + overlap, dp(20), dp(28));
    }

    private TextView heading(int resource) { TextView view = text(resource); view.setTextSize(28); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }

    private TextView text(int resource) { TextView view = new TextView(this); if (resource != 0) view.setText(resource); return view; }

    private EditText field(int hint, int type) { EditText field = new EditText(this); field.setHint(hint); field.setSingleLine(true); field.setInputType(type); return field; }

    private Button button(int text, View.OnClickListener listener) { Button button = new Button(this); button.setText(text); button.setOnClickListener(listener); return button; }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }

    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1); }

    private void clearMainReferences() { selectedHostView = null; statusView = null; connectButton = null; logsButton = null; logsView = null; testUrlField = null; httpResultView = null; }

    private int parsePort(String value, String label) {
        try { int port = Integer.parseInt(value); if (port < 1 || port > 65535) throw new NumberFormatException(); return port; }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(getString(R.string.invalid_port, label)); }
    }

    private void showError(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String readableMessage(Throwable throwable) {
        Throwable current = throwable; while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage(); return message == null || message.trim().isEmpty() ? current.getClass().getSimpleName() : message;
    }
}
