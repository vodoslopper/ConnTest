package dev.example.androidvm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hev.htproxy.TProxyService;

public final class ConnTestRoutingService extends VpnService {
    private static final String ACTION_CONNECT = "dev.example.androidvm.CONNECT";
    private static final String ACTION_DISCONNECT = "dev.example.androidvm.DISCONNECT";
    private static final String CHANNEL_ID = "conntest_ssh";
    private static final int NOTIFICATION_ID = 7;

    private static volatile RoutingStatus status = new RoutingStatus(R.string.status_disconnected);
    private static volatile boolean connected;
    private static volatile int localEndpointPort;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object tunnelLock = new Object();
    private ParcelFileDescriptor routingInterface;
    private volatile SshSocksEndpoint sshEndpoint;

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
            final String jumpHost = intent.getStringExtra("jumpHost");
            final SshSocksEndpoint.JumpHost jump = jumpHost == null ? null
                    : new SshSocksEndpoint.JumpHost(
                            jumpHost,
                            intent.getIntExtra("jumpPort", 22),
                            intent.getStringExtra("jumpUser"),
                            intent.getByteArrayExtra("jumpPrivateKey"),
                            intent.getStringExtra("jumpPassword"),
                            intent.getBooleanExtra("jumpAcceptUnknownHost", false));
            worker.execute(() -> connect(
                    host,
                    sshPort,
                    user,
                    privateKey,
                    password,
                    socksPort,
                    acceptUnknownHost,
                    dnsServers,
                    jump));
        }
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        requestStopTunnel();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        Thread teardown = new Thread(this::stopTunnel, "ConnTest-routing-teardown");
        teardown.start();
        super.onDestroy();
    }

    private void connect(
            String host,
            int sshPort,
            String user,
            byte[] privateKey,
            String password,
            int socksPort,
            boolean acceptUnknownHost,
            List<String> dnsServers,
            SshSocksEndpoint.JumpHost jumpHost) {
        synchronized (tunnelLock) {
            connected = false;
            ConnectionLog.append("Starting connection worker");
            clearTunnelResources();
            try {
                String statusText = setStatus(R.string.status_connecting, user, host, sshPort);
                ConnectionLog.append(statusText);
                updateNotification(statusText);

                sshEndpoint = new SshSocksEndpoint(
                        this,
                        host,
                        sshPort,
                        user,
                        privateKey,
                        password == null ? "" : password,
                        socksPort,
                        acceptUnknownHost,
                        jumpHost);
                sshEndpoint.start();
                ConnectionLog.append("SSH authentication completed; SOCKS listener is ready");

                VpnService.Builder builder = new VpnService.Builder()
                        .setSession("ConnTest: " + host)
                        .setMtu(1500)
                        .addAddress("198.18.0.1", 32)
                        .addRoute("0.0.0.0", 0);
                for (String dnsServer : dnsServers) builder.addDnsServer(dnsServer);
                builder.addDisallowedApplication(getPackageName());
                routingInterface = builder.establish();
                if (routingInterface == null) {
                    throw new IOException("Android did not establish the routing interface");
                }
                ConnectionLog.append("Android routing interface established with DNS "
                        + DnsServers.format(dnsServers).replace("\n", ", "));

                File config = writeTunnelConfig(socksPort);
                TProxyService.start(config.getAbsolutePath(), routingInterface.getFd());
                ConnectionLog.append("TUN-to-SOCKS bridge started");
                statusText = setStatus(R.string.status_connected, host);
                localEndpointPort = socksPort;
                connected = true;
                ConnectionLog.append(statusText);
                updateNotification(statusText);
            } catch (Exception exception) {
                String statusText = setStatus(R.string.status_failed, readableMessage(exception));
                ConnectionLog.append(statusText);
                updateNotification(statusText);
                clearTunnelResources();
                stopForeground(true);
                stopSelf();
            }
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

    private void stopTunnel() {
        synchronized (tunnelLock) {
            ConnectionLog.append("Stopping connection and routing resources");
            clearTunnelResources();
            connected = false;
            localEndpointPort = 0;
            status = new RoutingStatus(R.string.status_disconnected);
            ConnectionLog.append("Disconnected");
            stopForeground(true);
            stopSelf();
        }
    }

    private void requestStopTunnel() {
        SshSocksEndpoint endpoint = sshEndpoint;
        if (endpoint != null) endpoint.close();
        worker.execute(this::stopTunnel);
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
}
