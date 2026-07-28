package dev.example.androidvm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.jcraft.jsch.JSchException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int ROUTING_REQUEST = 100;
    private static final String PREFS = "connection";
    private static final String DEFAULT_TEST_URL = "https://api.ipify.org?format=json";
    private static final int MAX_HTTP_RESPONSE_BYTES = 64 * 1024;

    private final Handler statusHandler = new Handler();
    private final ExecutorService httpWorker = Executors.newSingleThreadExecutor();
    private EditText hostField;
    private EditText sshPortField;
    private EditText userField;
    private TextView publicKeyView;
    private EditText passwordField;
    private EditText socksPortField;
    private CheckBox acceptUnknownHostField;
    private TextView commandView;
    private TextView statusView;
    private Button logsButton;
    private TextView logsView;
    private EditText testUrlField;
    private TextView httpResultView;
    private Intent pendingConnectIntent;
    private SshIdentityStore identity;

    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            statusView.setText(getString(
                    R.string.status_format,
                    ConnTestRoutingService.getStatus()));
            if (logsView.getVisibility() == View.VISIBLE) {
                updateLogs();
            }
            statusHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.app_name);
        setContentView(createContent());
        restoreFields();
        loadIdentity(false);
        updateCommand();

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        statusHandler.post(statusUpdater);
    }

    @Override
    protected void onPause() {
        statusHandler.removeCallbacks(statusUpdater);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        httpWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ROUTING_REQUEST
                && resultCode == RESULT_OK
                && pendingConnectIntent != null) {
            startRoutingService(pendingConnectIntent);
            pendingConnectIntent = null;
        } else if (requestCode == ROUTING_REQUEST) {
            ConnectionLog.append("Android routing permission was denied");
            statusView.setText(getString(
                    R.string.status_format,
                    getString(R.string.routing_permission_denied)));
        }
    }

    private View createContent() {
        int padding = dp(20);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, padding, padding, padding);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_conntest);
        logo.setContentDescription(getString(R.string.app_name));
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int logoSize = dp(120);
        form.addView(logo, new LinearLayout.LayoutParams(logoSize, logoSize));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(28);
        form.addView(title);

        TextView intro = new TextView(this);
        intro.setText(R.string.intro);
        intro.setPadding(0, dp(8), 0, dp(12));
        form.addView(intro);

        hostField = field(R.string.ssh_host, InputType.TYPE_CLASS_TEXT);
        sshPortField = field(R.string.ssh_port, InputType.TYPE_CLASS_NUMBER);
        userField = field(R.string.ssh_username, InputType.TYPE_CLASS_TEXT);
        passwordField = field(
                R.string.ssh_password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        socksPortField = field(R.string.socks_port, InputType.TYPE_CLASS_NUMBER);
        form.addView(hostField);
        form.addView(sshPortField);
        form.addView(userField);

        TextView publicKeyLabel = new TextView(this);
        publicKeyLabel.setText(R.string.generated_public_key);
        publicKeyLabel.setPadding(0, dp(12), 0, dp(4));
        form.addView(publicKeyLabel);

        publicKeyView = new TextView(this);
        publicKeyView.setText(R.string.generating_key);
        publicKeyView.setTextIsSelectable(true);
        publicKeyView.setHorizontallyScrolling(false);
        form.addView(publicKeyView);

        Button regenerateKey = new Button(this);
        regenerateKey.setText(R.string.regenerate_key);
        regenerateKey.setOnClickListener(view -> confirmRegenerateIdentity());
        form.addView(regenerateKey);

        form.addView(passwordField);
        form.addView(socksPortField);

        acceptUnknownHostField = new CheckBox(this);
        acceptUnknownHostField.setText(R.string.accept_unknown_host);
        acceptUnknownHostField.setChecked(true);
        form.addView(acceptUnknownHostField);

        commandView = new TextView(this);
        commandView.setPadding(0, dp(10), 0, dp(10));
        form.addView(commandView);

        View.OnFocusChangeListener updateOnBlur = (view, hasFocus) -> {
            if (!hasFocus) {
                updateCommand();
            }
        };
        hostField.setOnFocusChangeListener(updateOnBlur);
        sshPortField.setOnFocusChangeListener(updateOnBlur);
        userField.setOnFocusChangeListener(updateOnBlur);
        socksPortField.setOnFocusChangeListener(updateOnBlur);
        acceptUnknownHostField.setOnCheckedChangeListener((button, checked) -> updateCommand());

        Button connect = new Button(this);
        connect.setText(R.string.connect);
        connect.setOnClickListener(view -> requestConnect());
        form.addView(connect);

        Button disconnect = new Button(this);
        disconnect.setText(R.string.disconnect);
        disconnect.setOnClickListener(view -> disconnect());
        form.addView(disconnect);

        statusView = new TextView(this);
        statusView.setText(getString(
                R.string.status_format,
                getString(R.string.status_disconnected)));
        statusView.setPadding(0, dp(14), 0, dp(8));
        form.addView(statusView);

        testUrlField = field(
                R.string.test_url,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        testUrlField.setText(DEFAULT_TEST_URL);
        form.addView(testUrlField);

        Button sendHttpRequest = new Button(this);
        sendHttpRequest.setText(R.string.send_http_request);
        sendHttpRequest.setOnClickListener(view -> requestHttpTest());
        form.addView(sendHttpRequest);

        httpResultView = new TextView(this);
        httpResultView.setText(R.string.http_result_waiting);
        httpResultView.setTextIsSelectable(true);
        httpResultView.setTypeface(android.graphics.Typeface.MONOSPACE);
        httpResultView.setPadding(0, dp(8), 0, dp(8));
        form.addView(httpResultView);

        logsButton = new Button(this);
        logsButton.setText(R.string.show_logs);
        logsButton.setOnClickListener(view -> toggleLogs());
        form.addView(logsButton);

        Button shareLogs = new Button(this);
        shareLogs.setText(R.string.share_logs);
        shareLogs.setOnClickListener(view -> shareLogs());
        form.addView(shareLogs);

        Button clearLogs = new Button(this);
        clearLogs.setText(R.string.clear_logs);
        clearLogs.setOnClickListener(view -> {
            ConnectionLog.clear();
            updateLogs();
        });
        form.addView(clearLogs);

        logsView = new TextView(this);
        logsView.setTextIsSelectable(true);
        logsView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logsView.setMovementMethod(new ScrollingMovementMethod());
        logsView.setVisibility(View.GONE);
        logsView.setPadding(0, dp(8), 0, 0);
        form.addView(logsView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        return scroll;
    }

    private EditText field(int hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(inputType);
        return field;
    }

    private void restoreFields() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostField.setText(preferences.getString("host", ""));
        sshPortField.setText(preferences.getString("sshPort", "22"));
        userField.setText(preferences.getString("user", ""));
        socksPortField.setText(preferences.getString("socksPort", "1080"));
        acceptUnknownHostField.setChecked(preferences.getBoolean("acceptUnknown", true));
    }

    private void loadIdentity(boolean regenerate) {
        try {
            identity = regenerate
                    ? SshIdentityStore.regenerate(this)
                    : SshIdentityStore.loadOrCreate(this);
            publicKeyView.setText(identity.getPublicKey());
            updateCommand();
        } catch (IOException | JSchException exception) {
            identity = null;
            String message = getString(
                    R.string.key_generation_failed,
                    readableMessage(exception));
            publicKeyView.setText(message);
            ConnectionLog.append(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRegenerateIdentity() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.regenerate_key)
                .setMessage(R.string.regenerate_key_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.regenerate, (dialog, which) -> loadIdentity(true))
                .show();
    }

    private void requestConnect() {
        String host = hostField.getText().toString().trim();
        String user = userField.getText().toString().trim();
        String password = passwordField.getText().toString();
        int sshPort;
        int socksPort;
        try {
            sshPort = parsePort(
                    sshPortField.getText().toString(),
                    getString(R.string.ssh_port));
            socksPort = parsePort(
                    socksPortField.getText().toString(),
                    getString(R.string.socks_port));
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (host.isEmpty() || user.isEmpty()) {
            Toast.makeText(this, R.string.host_and_user_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (identity == null) {
            Toast.makeText(this, R.string.generated_key_required, Toast.LENGTH_LONG).show();
            return;
        }

        byte[] privateKey;
        try {
            privateKey = identity.readPrivateKey();
        } catch (IOException exception) {
            String message = getString(R.string.private_key_read_failed, readableMessage(exception));
            ConnectionLog.append(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString("host", host)
                .putString("sshPort", Integer.toString(sshPort))
                .putString("user", user)
                .putString("socksPort", Integer.toString(socksPort))
                .putBoolean("acceptUnknown", acceptUnknownHostField.isChecked())
                .apply();

        ConnectionLog.clear();
        ConnectionLog.append("Connection requested for " + user + "@" + host + ":" + sshPort);
        pendingConnectIntent = ConnTestRoutingService.connectIntent(
                this,
                host,
                sshPort,
                user,
                privateKey,
                password,
                socksPort,
                acceptUnknownHostField.isChecked());
        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent == null) {
            startRoutingService(pendingConnectIntent);
            pendingConnectIntent = null;
        } else {
            ConnectionLog.append("Waiting for Android routing permission");
            startActivityForResult(permissionIntent, ROUTING_REQUEST);
        }
    }

    private void disconnect() {
        ConnectionLog.append("Disconnect requested by user");
        startService(ConnTestRoutingService.disconnectIntent(this));
    }

    private void toggleLogs() {
        boolean show = logsView.getVisibility() != View.VISIBLE;
        logsView.setVisibility(show ? View.VISIBLE : View.GONE);
        logsButton.setText(show ? R.string.hide_logs : R.string.show_logs);
        if (show) {
            updateLogs();
        }
    }

    private void requestHttpTest() {
        if (!ConnTestRoutingService.isConnected()) {
            Toast.makeText(this, R.string.routing_not_connected, Toast.LENGTH_LONG).show();
            return;
        }
        String value = testUrlField.getText().toString().trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            Toast.makeText(this, R.string.invalid_http_url, Toast.LENGTH_LONG).show();
            return;
        }
        httpResultView.setText(getString(R.string.http_request_running, value));
        ConnectionLog.append("HTTP routing test requesting " + value);
        httpWorker.execute(() -> runHttpTest(value));
    }

    private void runHttpTest(String value) {
        HttpURLConnection connection = null;
        try {
            int endpointPort = ConnTestRoutingService.getLocalEndpointPort();
            if (endpointPort <= 0) {
                throw new IOException("SSH endpoint is not ready");
            }
            Proxy route = new Proxy(
                    Proxy.Type.SOCKS,
                    new InetSocketAddress("127.0.0.1", endpointPort));
            connection = (HttpURLConnection) new URL(value).openConnection(route);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "text/plain, application/json, */*");
            connection.setRequestProperty("User-Agent", "ConnTest/4");
            int statusCode = connection.getResponseCode();
            String statusMessage = connection.getResponseMessage();
            InputStream response = statusCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            String body = response == null ? "" : readHttpResponse(response);
            String result = getString(
                    R.string.http_result_format,
                    statusCode,
                    statusMessage == null ? "" : statusMessage,
                    body);
            ConnectionLog.append("HTTP routing test completed with status " + statusCode
                    + " and " + body.getBytes(StandardCharsets.UTF_8).length + " response bytes");
            runOnUiThread(() -> httpResultView.setText(result));
        } catch (Exception exception) {
            String message = getString(
                    R.string.http_request_failed,
                    readableMessage(exception));
            ConnectionLog.append(message);
            runOnUiThread(() -> httpResultView.setText(message));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readHttpResponse(InputStream input) throws IOException {
        try (InputStream response = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = response.read(buffer)) != -1) {
                if (total + count > MAX_HTTP_RESPONSE_BYTES) {
                    int remaining = MAX_HTTP_RESPONSE_BYTES - total;
                    if (remaining > 0) {
                        output.write(buffer, 0, remaining);
                    }
                    output.write("\n[response truncated]".getBytes(StandardCharsets.UTF_8));
                    break;
                }
                output.write(buffer, 0, count);
                total += count;
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void updateLogs() {
        String logs = ConnectionLog.snapshot();
        logsView.setText(logs.isEmpty() ? getString(R.string.no_logs) : logs);
    }

    private void shareLogs() {
        try {
            File directory = new File(getCacheDir(), "shared-logs");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("could not create log-share directory");
            }
            File file = new File(directory, "conntest-connection-log.txt");
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(ConnectionLog.snapshot().getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".files",
                    file);
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("ConnTest connection log", uri));
            startActivity(Intent.createChooser(share, getString(R.string.share_logs)));
        } catch (IOException | IllegalArgumentException exception) {
            Toast.makeText(
                    this,
                    getString(R.string.share_logs_failed, readableMessage(exception)),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startRoutingService(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void updateCommand() {
        if (commandView == null) {
            return;
        }
        String host = hostField.getText().toString().trim();
        String user = userField.getText().toString().trim();
        String sshPort = sshPortField.getText().toString().trim();
        String socksPort = socksPortField.getText().toString().trim();
        String strict = acceptUnknownHostField.isChecked()
                ? " -o StrictHostKeyChecking=no"
                : "";
        commandView.setText(getString(
                R.string.command_format,
                socksPort,
                sshPort,
                strict,
                user,
                host));
    }

    private int parsePort(String value, String label) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(getString(R.string.invalid_port, label));
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String readableMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
