package dev.example.androidvm;

import android.net.VpnService;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Logger;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SshSocksEndpoint implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private final VpnService routingService;
    private final String host;
    private final int sshPort;
    private final String user;
    private final byte[] privateKey;
    private final String password;
    private final int socksPort;
    private final boolean acceptUnknownHost;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final Set<Socket> clientSockets = Collections.newSetFromMap(
            new ConcurrentHashMap<Socket, Boolean>());

    private volatile boolean running;
    private Session session;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    SshSocksEndpoint(
            VpnService routingService,
            String host,
            int sshPort,
            String user,
            byte[] privateKey,
            String password,
            int socksPort,
            boolean acceptUnknownHost) {
        this.routingService = routingService;
        this.host = host;
        this.sshPort = sshPort;
        this.user = user;
        this.privateKey = privateKey;
        this.password = password;
        this.socksPort = socksPort;
        this.acceptUnknownHost = acceptUnknownHost;
    }

    void start() throws IOException, JSchException {
        JSch jsch = new JSch();
        jsch.setInstanceLogger(new ConnectionLogger());
        if (privateKey != null && privateKey.length > 0) {
            try {
                jsch.addIdentity("ConnTest generated Ed25519 key", privateKey, null, null);
                ConnectionLog.append("Loaded generated Ed25519 SSH identity");
            } finally {
                Arrays.fill(privateKey, (byte) 0);
            }
        }
        session = jsch.getSession(user, host, sshPort);
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }
        session.setConfig(
                "StrictHostKeyChecking",
                acceptUnknownHost ? "no" : "yes");
        session.setConfig(
                "PreferredAuthentications",
                "publickey,password,keyboard-interactive");
        session.setServerAliveInterval(15_000);
        session.setServerAliveCountMax(3);
        session.setSocketFactory(new ProtectedSocketFactory(routingService));
        ConnectionLog.append("Opening protected SSH transport socket");
        session.connect(CONNECT_TIMEOUT_MS);

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), socksPort));
        ConnectionLog.append("SOCKS5 listener bound to 127.0.0.1:" + socksPort);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "ConnTest-SOCKS-accept");
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                clientSockets.add(socket);
                clients.execute(() -> handleClient(socket));
            } catch (IOException exception) {
                if (running) {
                    close();
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            InputStream clientInput = socket.getInputStream();
            OutputStream clientOutput = socket.getOutputStream();
            SocksRequest request = negotiate(clientInput, clientOutput);
            ConnectionLog.append(
                    "SOCKS request command=" + request.command
                            + " target=" + request.host + ":" + request.port);
            if (request.command == 0x01) {
                handleTcpConnect(socket, clientInput, clientOutput, request);
            } else if (request.command == 0x03) {
                handleUdpAssociate(clientInput, clientOutput);
            } else {
                sendReply(clientOutput, 0x07, "0.0.0.0", 0);
            }
        } catch (Exception exception) {
            try {
                sendReply(socket.getOutputStream(), 0x01, "0.0.0.0", 0);
            } catch (IOException ignored) {
                // The client may already be gone.
            }
        } finally {
            clientSockets.remove(socket);
            closeQuietly(socket);
        }
    }

    private void handleTcpConnect(
            Socket socket,
            InputStream clientInput,
            OutputStream clientOutput,
            SocksRequest request) {
        ChannelDirectTCPIP channel = null;
        boolean established = false;
        try {
            channel = openChannel(request.host, request.port, socket.getPort());
            InputStream remoteInput = channel.getInputStream();
            OutputStream remoteOutput = channel.getOutputStream();
            channel.connect(CONNECT_TIMEOUT_MS);
            ConnectionLog.append("SSH direct-tcpip channel opened to "
                    + request.host + ":" + request.port);
            sendReply(clientOutput, 0x00, "0.0.0.0", 0);
            established = true;

            final ChannelDirectTCPIP activeChannel = channel;
            clients.execute(() -> {
                try {
                    copy(remoteInput, clientOutput);
                } catch (IOException ignored) {
                    // The opposite direction closes the channel.
                } finally {
                    activeChannel.disconnect();
                    closeQuietly(socket);
                }
            });
            copy(clientInput, remoteOutput);
        } catch (Exception exception) {
            ConnectionLog.append("TCP forwarding failed: " + readableMessage(exception));
            if (!established) {
                try {
                    sendReply(clientOutput, 0x05, "0.0.0.0", 0);
                } catch (IOException ignored) {
                    // The client may already be gone.
                }
            }
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    private void handleUdpAssociate(InputStream controlInput, OutputStream controlOutput)
            throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket relay = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            sendReply(
                    controlOutput,
                    0x00,
                    loopback.getHostAddress(),
                    relay.getLocalPort());
            clients.execute(() -> udpRelayLoop(relay));
            while (controlInput.read() >= 0) {
                // SOCKS keeps the TCP control connection open for the association.
            }
        }
    }

    private void udpRelayLoop(DatagramSocket relay) {
        while (running && !relay.isClosed()) {
            try {
                byte[] buffer = new byte[65_535];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                relay.receive(packet);
                byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
                clients.execute(() -> handleUdpPacket(relay, packet, request));
            } catch (IOException exception) {
                return;
            }
        }
    }

    private void handleUdpPacket(
            DatagramSocket relay,
            DatagramPacket sourcePacket,
            byte[] packetBytes) {
        ChannelDirectTCPIP channel = null;
        try {
            UdpRequest request = parseUdpRequest(packetBytes);
            if (request.port != 53) {
                ConnectionLog.append("Blocked non-DNS UDP request to "
                        + request.host + ":" + request.port);
                return;
            }
            channel = openChannel(request.host, request.port, sourcePacket.getPort());
            InputStream dnsInput = channel.getInputStream();
            OutputStream dnsOutput = channel.getOutputStream();
            channel.connect(CONNECT_TIMEOUT_MS);
            ConnectionLog.append("Forwarding DNS-over-TCP query to " + request.host);

            int queryLength = request.payload.length;
            dnsOutput.write((queryLength >>> 8) & 0xFF);
            dnsOutput.write(queryLength & 0xFF);
            dnsOutput.write(request.payload);
            dnsOutput.flush();

            int responseLength = (readByte(dnsInput) << 8) | readByte(dnsInput);
            byte[] response = readBytes(dnsInput, responseLength);
            byte[] udpResponse = new byte[request.header.length + response.length];
            System.arraycopy(request.header, 0, udpResponse, 0, request.header.length);
            System.arraycopy(
                    response,
                    0,
                    udpResponse,
                    request.header.length,
                    response.length);
            DatagramPacket reply = new DatagramPacket(
                    udpResponse,
                    udpResponse.length,
                    sourcePacket.getSocketAddress());
            synchronized (relay) {
                relay.send(reply);
            }
        } catch (Exception exception) {
            ConnectionLog.append("DNS forwarding failed: " + readableMessage(exception));
            // A failed DNS query times out in the requesting app.
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    private ChannelDirectTCPIP openChannel(String targetHost, int targetPort, int originPort)
            throws JSchException {
        ChannelDirectTCPIP channel =
                (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
        try {
            channel.setHost(targetHost);
            channel.setPort(targetPort);
            channel.setOrgIPAddress("127.0.0.1");
            channel.setOrgPort(originPort);
            return channel;
        } catch (RuntimeException exception) {
            channel.disconnect();
            throw exception;
        }
    }

    private static SocksRequest negotiate(InputStream input, OutputStream output)
            throws IOException {
        if (readByte(input) != 0x05) {
            throw new IOException("Only SOCKS5 is supported");
        }
        int methodCount = readByte(input);
        boolean supportsNoAuthentication = false;
        for (int index = 0; index < methodCount; index++) {
            if (readByte(input) == 0x00) {
                supportsNoAuthentication = true;
            }
        }
        if (!supportsNoAuthentication) {
            output.write(new byte[]{0x05, (byte) 0xFF});
            output.flush();
            throw new IOException("SOCKS client requires unsupported authentication");
        }
        output.write(new byte[]{0x05, 0x00});
        output.flush();

        if (readByte(input) != 0x05) {
            throw new IOException("Invalid SOCKS5 request");
        }
        int command = readByte(input);
        readByte(input);
        int addressType = readByte(input);
        String targetHost;
        if (addressType == 0x01) {
            targetHost = InetAddress.getByAddress(readBytes(input, 4)).getHostAddress();
        } else if (addressType == 0x03) {
            int length = readByte(input);
            targetHost = new String(readBytes(input, length), StandardCharsets.UTF_8);
        } else if (addressType == 0x04) {
            targetHost = InetAddress.getByAddress(readBytes(input, 16)).getHostAddress();
        } else {
            throw new IOException("Unsupported SOCKS address type");
        }
        int targetPort = (readByte(input) << 8) | readByte(input);
        return new SocksRequest(command, targetHost, targetPort);
    }

    private static UdpRequest parseUdpRequest(byte[] packet) throws IOException {
        if (packet.length < 10 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0) {
            throw new IOException("Invalid SOCKS5 UDP packet");
        }
        int index = 3;
        int addressType = packet[index++] & 0xFF;
        String targetHost;
        if (addressType == 0x01) {
            requireBytes(packet, index, 4);
            targetHost = InetAddress.getByAddress(
                    Arrays.copyOfRange(packet, index, index + 4)).getHostAddress();
            index += 4;
        } else if (addressType == 0x03) {
            requireBytes(packet, index, 1);
            int length = packet[index++] & 0xFF;
            requireBytes(packet, index, length);
            targetHost = new String(packet, index, length, StandardCharsets.UTF_8);
            index += length;
        } else if (addressType == 0x04) {
            requireBytes(packet, index, 16);
            targetHost = InetAddress.getByAddress(
                    Arrays.copyOfRange(packet, index, index + 16)).getHostAddress();
            index += 16;
        } else {
            throw new IOException("Unsupported SOCKS5 UDP address type");
        }
        requireBytes(packet, index, 2);
        int targetPort = ((packet[index] & 0xFF) << 8) | (packet[index + 1] & 0xFF);
        index += 2;
        return new UdpRequest(
                targetHost,
                targetPort,
                Arrays.copyOf(packet, index),
                Arrays.copyOfRange(packet, index, packet.length));
    }

    private static void requireBytes(byte[] packet, int offset, int count)
            throws IOException {
        if (offset < 0 || count < 0 || offset + count > packet.length) {
            throw new IOException("Truncated SOCKS5 UDP packet");
        }
    }

    private static void sendReply(
            OutputStream output,
            int result,
            String boundAddress,
            int boundPort) throws IOException {
        byte[] address = InetAddress.getByName(boundAddress).getAddress();
        if (address.length != 4) {
            throw new IOException("SOCKS5 relay requires an IPv4 loopback address");
        }
        output.write(new byte[]{0x05, (byte) result, 0x00, 0x01});
        output.write(address);
        output.write((boundPort >>> 8) & 0xFF);
        output.write(boundPort & 0xFF);
        output.flush();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16_384];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
            output.flush();
        }
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Unexpected end of SOCKS request");
        }
        return value;
    }

    private static byte[] readBytes(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new EOFException("Unexpected end of SOCKS request");
            }
            offset += count;
        }
        return bytes;
    }

    @Override
    public void close() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
            serverSocket = null;
        }
        for (Socket socket : clientSockets) {
            closeQuietly(socket);
        }
        clientSockets.clear();
        clients.shutdownNow();
        if (session != null) {
            session.disconnect();
            session = null;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private static String readableMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static final class ConnectionLogger implements Logger {
        @Override
        public boolean isEnabled(int level) {
            return true;
        }

        @Override
        public void log(int level, String message) {
            ConnectionLog.append("JSch " + levelName(level) + ": " + message);
        }

        private static String levelName(int level) {
            switch (level) {
                case Logger.DEBUG:
                    return "DEBUG";
                case Logger.INFO:
                    return "INFO";
                case Logger.WARN:
                    return "WARN";
                case Logger.ERROR:
                    return "ERROR";
                case Logger.FATAL:
                    return "FATAL";
                default:
                    return Integer.toString(level);
            }
        }
    }

    private static final class SocksRequest {
        final int command;
        final String host;
        final int port;

        SocksRequest(int command, String host, int port) {
            this.command = command;
            this.host = host;
            this.port = port;
        }
    }

    private static final class UdpRequest {
        final String host;
        final int port;
        final byte[] header;
        final byte[] payload;

        UdpRequest(String host, int port, byte[] header, byte[] payload) {
            this.host = host;
            this.port = port;
            this.header = header;
            this.payload = payload;
        }
    }

    private static final class ProtectedSocketFactory implements SocketFactory {
        private final VpnService routingService;

        ProtectedSocketFactory(VpnService routingService) {
            this.routingService = routingService;
        }

        @Override
        public Socket createSocket(String host, int port)
                throws IOException, UnknownHostException {
            Socket socket = new Socket();
            // Some Android releases do not allocate the socket file descriptor
            // until bind/connect. Allocate it without sending traffic so the
            // The routing service can exempt this SSH transport before connection.
            socket.bind(new InetSocketAddress(0));
            ConnectionLog.append("Allocated SSH socket on local port "
                    + socket.getLocalPort());
            if (!routingService.protect(socket)) {
                socket.close();
                throw new IOException("Android refused to protect the SSH socket");
            }
            ConnectionLog.append("SSH transport socket excluded from device routing");
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            ConnectionLog.append("SSH transport socket connected to " + host + ":" + port);
            return socket;
        }

        @Override
        public InputStream getInputStream(Socket socket) throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream(Socket socket) throws IOException {
            return socket.getOutputStream();
        }
    }
}
