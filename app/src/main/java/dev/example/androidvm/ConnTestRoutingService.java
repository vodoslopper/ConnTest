package dev.example.androidvm;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hev.htproxy.TProxyService;

public final class ConnTestRoutingService extends VpnService {
    private static final String ACTION_CONNECT = "dev.example.androidvm.CONNECT";
    private static final String ACTION_DISCONNECT = "dev.example.androidvm.DISCONNECT";
    private static final String CHANNEL_ID = "conntest_ssh";
    private static final int NOTIFICATION_ID = 7;
    private static final long HEALTH_CHECK_INTERVAL_MILLIS = 5_000L;

    private static volatile RoutingStatus status = new RoutingStatus(R.string.status_disconnected);
    private static volatile boolean active;
    private static volatile boolean connected;
    private static volatile int localEndpointPort;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object tunnelLock = new Object();
    private ParcelFileDescriptor routingInterface;
    private volatile SshSocksEndpoint sshEndpoint;
    private volatile Thread connectionThread;
    private volatile boolean stopRequested = true;
    private PowerManager.WakeLock wakeLock;

    public static Intent connectIntent(
            Context context,
            String host,
            int sshPort,
            String user,
            byte[] privateKey,
            String password,
            int socksPort,
            boolean acceptUnknownHost,
            List<String> dnsServers,
            HostStore.Host jumpHost,
            byte[] jumpPrivateKey) {
        Intent intent = new Intent(context, ConnTestRoutingService.class)
                .setAction(ACTION_CONNECT)
                .putExtra("host", host)
                .putExtra("sshPort", sshPort)
                .putExtra("user", user)
                .putExtra("privateKey", privateKey)
                .putExtra("password", password)
                .putExtra("socksPort", socksPort)
                .putExtra("acceptUnknownHost", acceptUnknownHost)
                .putExtra("dnsServers", dnsServers.toArray(new String[0]));
        if (jumpHost != null) {
            intent.putExtra("jumpHost", jumpHost.address)
                    .putExtra("jumpPort", jumpHost.sshPort)
                    .putExtra("jumpUser", jumpHost.user)
                    .putExtra("jumpPrivateKey", jumpPrivateKey)
                    .putExtra("jumpPassword", jumpHost.password)
                    .putExtra("jumpAcceptUnknownHost", jumpHost.acceptUnknown);
        }
        return intent;
    }

    public static Intent disconnectIntent(Context context) {
        return new Intent(context, ConnTestRoutingService.class).setAction(ACTION_DISCONNECT);
    }

    public static String getStatus(Context context) {
        return status.resolve(context);
    }

    public static boolean isConnected() {
        return connected;
    }

    public static boolean isActive() {
        return active;
    }

    public static int getLocalEndpointPort() {
        return localEndpointPort;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground(getString(R.string.status_starting));
        if (intent == null || ACTION_DISCONNECT.equals(intent.getAction())) {
            requestStopTunnel();
            return Service.START_NOT_STICKY;
        }
        if (ACTION_CONNECT.equals(intent.getAction())) {
            if (active) return Service.START_REDELIVER_INTENT;
            final String host = intent.getStringExtra("host");
            final int sshPort = intent.getIntExtra("sshPort", 22);
            final String user = intent.getStringExtra("user");
            final byte[] privateKey = intent.getByteArrayExtra("privateKey");
            final String password = intent.getStringExtra("password");
            final int socksPort = intent.getIntExtra("socksPort", 1080);
            final boolean acceptUnknownHost =
                    intent.getBooleanExtra("acceptUnknownHost", false);
            final String[] requestedDnsServers = intent.getStringArrayExtra("dnsServers");
            final List<String> dnsServers = requestedDnsServers == null
                    ? DnsServers.defaults()
                    : java.util.Arrays.asList(requestedDnsServers);
            final ConnectionParameters parameters = new ConnectionParameters(
                    host,
                    sshPort,
                    user,
                    privateKey,
                    password,
                    socksPort,
                    acceptUnknownHost,
                    dnsServers,
                    intent.getStringExtra("jumpHost"),
                    intent.getIntExtra("jumpPort", 22),
                    intent.getStringExtra("jumpUser"),
                    intent.getByteArrayExtra("jumpPrivateKey"),
                    intent.getStringExtra("jumpPassword"),
                    intent.getBooleanExtra("jumpAcceptUnknownHost", false));
            stopRequested = false;
            active = true;
            worker.execute(() -> runConnection(parameters));
        }
        return Service.START_REDELIVER_INTENT;
    }

    @Override
    public void onRevoke() {
        requestStopTunnel();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        active = false;
        connected = false;
        closeSshEndpoint();
        worker.shutdownNow();
        Thread teardown = new Thread(() -> {
            clearTunnelResources();
            releaseWakeLock();
        }, "ConnTest-routing-teardown");
        teardown.start();
        super.onDestroy();
    }

    private void runConnection(ConnectionParameters parameters) {
        connectionThread = Thread.currentThread();
        boolean connectedOnce = false;
        int consecutiveFailures = 0;
        String reconnectReason = getString(R.string.ssh_connection_lost);
        try {
            acquireWakeLock();
            ConnectionLog.append("Starting connection worker");
            while (!stopRequested) {
                if (connectedOnce) {
                    long delayMillis = ReconnectBackoff.delayMillis(consecutiveFailures);
                    long delaySeconds = delayMillis / 1_000L;
                    String statusText = setStatus(
                            R.string.status_reconnecting, delaySeconds);
                    ConnectionLog.append(statusText + ": " + reconnectReason);
                    updateNotification(statusText);
                    sleepUntilRetry(delayMillis);
                    if (stopRequested) break;
                } else {
                    String statusText = setStatus(
                            R.string.status_connecting,
                            parameters.user,
                            parameters.host,
                            parameters.sshPort);
                    ConnectionLog.append(statusText);
                    updateNotification(statusText);
                }

                try {
                    startSshEndpoint(parameters);
                    ConnectionLog.append(
                            "SSH authentication completed; SOCKS listener is ready");
                    if (routingInterface == null) establishRouting(parameters);

                    connectedOnce = true;
                    consecutiveFailures = 0;
                    localEndpointPort = parameters.socksPort;
                    connected = true;
                    String statusText = setStatus(R.string.status_connected, parameters.host);
                    ConnectionLog.append(statusText);
                    updateNotification(statusText);

                    waitForConnectionLoss();
                    if (!stopRequested) {
                        reconnectReason = getString(R.string.ssh_connection_lost);
                        connected = false;
                        localEndpointPort = 0;
                        closeSshEndpoint();
                    }
                } catch (Exception exception) {
                    connected = false;
                    localEndpointPort = 0;
                    closeSshEndpoint();
                    if (stopRequested) break;
                    if (!connectedOnce) {
                        String statusText = setStatus(
                                R.string.status_failed, readableMessage(exception));
                        ConnectionLog.append(statusText);
                        updateNotification(statusText);
                        return;
                    }
                    consecutiveFailures++;
                    reconnectReason = readableMessage(exception);
                    ConnectionLog.append(
                            "SSH reconnect attempt failed: " + readableMessage(exception));
                }
            }
        } finally {
            parameters.close();
            clearTunnelResources();
            releaseWakeLock();
            connectionThread = null;
            connected = false;
            active = false;
            localEndpointPort = 0;
            if (stopRequested) {
                status = new RoutingStatus(R.string.status_disconnected);
                ConnectionLog.append("Disconnected");
            }
            stopForeground(true);
            stopSelf();
        }
    }

    private void startSshEndpoint(ConnectionParameters parameters)
            throws IOException, com.jcraft.jsch.JSchException {
        SshSocksEndpoint endpoint = parameters.newEndpoint(this);
        synchronized (tunnelLock) {
            sshEndpoint = endpoint;
        }
        try {
            endpoint.start();
        } catch (IOException | com.jcraft.jsch.JSchException exception) {
            endpoint.close();
            synchronized (tunnelLock) {
                if (sshEndpoint == endpoint) sshEndpoint = null;
            }
            throw exception;
        }
    }

    private void establishRouting(ConnectionParameters parameters)
            throws IOException, PackageManager.NameNotFoundException {
        VpnService.Builder builder = new VpnService.Builder()
                .setSession("ConnTest: " + parameters.host)
                .setMtu(1500)
                .addAddress("198.18.0.1", 32)
                .addRoute("0.0.0.0", 0);
        for (String dnsServer : parameters.dnsServers) builder.addDnsServer(dnsServer);
        builder.addDisallowedApplication(getPackageName());
        routingInterface = builder.establish();
        if (routingInterface == null) {
            throw new IOException("Android did not establish the routing interface");
        }
        ConnectionLog.append("Android routing interface established with DNS "
                + DnsServers.format(parameters.dnsServers).replace("\n", ", "));

        File config = writeTunnelConfig(parameters.socksPort);
        TProxyService.start(config.getAbsolutePath(), routingInterface.getFd());
        ConnectionLog.append("TUN-to-SOCKS bridge started");
    }

    private void waitForConnectionLoss() throws InterruptedException {
        while (!stopRequested) {
            SshSocksEndpoint endpoint = sshEndpoint;
            if (endpoint == null || !endpoint.isConnected()) return;
            Thread.sleep(HEALTH_CHECK_INTERVAL_MILLIS);
        }
    }

    private void sleepUntilRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ignored) {
            // Disconnect interrupts reconnect delays so teardown is immediate.
        }
    }

    private File writeTunnelConfig(int socksPort) throws IOException {
        File bridgeLog = new File(getFilesDir(), "routing-native.log");
        if (bridgeLog.exists() && !bridgeLog.delete()) {
            throw new IOException("Could not reset native routing log");
        }
        String config =
                "tunnel:\n"
                        + "  mtu: 1500\n"
                        + "  ipv4: 198.18.0.1\n"
                        + "  icmp: off\n"
                        + "socks5:\n"
                        + "  address: 127.0.0.1\n"
                        + "  port: "
                        + socksPort
                        + "\n"
                        + "  udp: udp\n"
                        + "misc:\n"
                        + "  log-file: "
                        + bridgeLog.getAbsolutePath()
                        + "\n"
                        + "  log-level: debug\n";
        File file = new File(getFilesDir(), "hev-socks5-tunnel.yml");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(config.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private void requestStopTunnel() {
        stopRequested = true;
        connected = false;
        localEndpointPort = 0;
        closeSshEndpoint();
        Thread thread = connectionThread;
        if (thread != null) {
            thread.interrupt();
        } else {
            active = false;
            clearTunnelResources();
            releaseWakeLock();
            status = new RoutingStatus(R.string.status_disconnected);
            ConnectionLog.append("Disconnected");
            stopForeground(true);
            stopSelf();
        }
    }

    private String setStatus(int resource, Object... arguments) {
        RoutingStatus next = new RoutingStatus(resource, arguments);
        status = next;
        return next.resolve(this);
    }

    private static final class RoutingStatus {
        private final int resource;
        private final Object[] arguments;

        private RoutingStatus(int resource, Object... arguments) {
            this.resource = resource;
            this.arguments = arguments;
        }

        private String resolve(Context context) {
            return context.getString(resource, arguments);
        }
    }

    private void clearTunnelResources() {
        synchronized (tunnelLock) {
            try {
                TProxyService.stop();
            } catch (Throwable ignored) {
                // The native library may not have started yet.
            }
            if (routingInterface != null) {
                try {
                    routingInterface.close();
                } catch (IOException ignored) {
                    // Closing is best effort during teardown.
                }
                routingInterface = null;
            }
            if (sshEndpoint != null) {
                sshEndpoint.close();
                sshEndpoint = null;
            }
            localEndpointPort = 0;
        }
    }

    private void closeSshEndpoint() {
        synchronized (tunnelLock) {
            if (sshEndpoint != null) {
                sshEndpoint.close();
                sshEndpoint = null;
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private synchronized void acquireWakeLock() {
        PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":ssh-routing");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        ConnectionLog.append("Acquired screen-off routing wake lock");
    }

    private synchronized void releaseWakeLock() {
        PowerManager.WakeLock currentWakeLock = wakeLock;
        wakeLock = null;
        if (currentWakeLock != null && currentWakeLock.isHeld()) {
            currentWakeLock.release();
            ConnectionLog.append("Released screen-off routing wake lock");
        }
    }

    private void startAsForeground(String text) {
        createNotificationChannel();
        Notification notification = notification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent activityPendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent disconnectPendingIntent = PendingIntent.getService(
                this,
                1,
                disconnectIntent(this),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(activityPendingIntent)
                .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.disconnect),
                        disconnectPendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private static String readableMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }

    private static final class ConnectionParameters implements AutoCloseable {
        final String host;
        final int sshPort;
        final String user;
        final byte[] privateKey;
        final String password;
        final int socksPort;
        final boolean acceptUnknownHost;
        final List<String> dnsServers;
        final String jumpHost;
        final int jumpPort;
        final String jumpUser;
        final byte[] jumpPrivateKey;
        final String jumpPassword;
        final boolean jumpAcceptUnknownHost;

        ConnectionParameters(
                String host,
                int sshPort,
                String user,
                byte[] privateKey,
                String password,
                int socksPort,
                boolean acceptUnknownHost,
                List<String> dnsServers,
                String jumpHost,
                int jumpPort,
                String jumpUser,
                byte[] jumpPrivateKey,
                String jumpPassword,
                boolean jumpAcceptUnknownHost) {
            this.host = host;
            this.sshPort = sshPort;
            this.user = user;
            this.privateKey = privateKey;
            this.password = password == null ? "" : password;
            this.socksPort = socksPort;
            this.acceptUnknownHost = acceptUnknownHost;
            this.dnsServers = dnsServers;
            this.jumpHost = jumpHost;
            this.jumpPort = jumpPort;
            this.jumpUser = jumpUser;
            this.jumpPrivateKey = jumpPrivateKey;
            this.jumpPassword = jumpPassword == null ? "" : jumpPassword;
            this.jumpAcceptUnknownHost = jumpAcceptUnknownHost;
        }

        SshSocksEndpoint newEndpoint(VpnService service) {
            SshSocksEndpoint.JumpHost jump = jumpHost == null ? null
                    : new SshSocksEndpoint.JumpHost(
                            jumpHost,
                            jumpPort,
                            jumpUser,
                            copy(jumpPrivateKey),
                            jumpPassword,
                            jumpAcceptUnknownHost);
            return new SshSocksEndpoint(
                    service,
                    host,
                    sshPort,
                    user,
                    copy(privateKey),
                    password,
                    socksPort,
                    acceptUnknownHost,
                    jump);
        }

        @Override
        public void close() {
            wipe(privateKey);
            wipe(jumpPrivateKey);
        }

        private static byte[] copy(byte[] value) {
            return value == null ? null : Arrays.copyOf(value, value.length);
        }

        private static void wipe(byte[] value) {
            if (value != null) Arrays.fill(value, (byte) 0);
        }
    }
}
