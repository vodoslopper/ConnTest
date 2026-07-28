package hev.htproxy;

public final class TProxyService {
    private TProxyService() {
    }

    public static native void TProxyStartService(String configPath, int fileDescriptor);

    public static native void TProxyStopService();

    public static native long[] TProxyGetStats();

    public static void start(String configPath, int fileDescriptor) {
        TProxyStartService(configPath, fileDescriptor);
    }

    public static void stop() {
        TProxyStopService();
    }

    public static long[] stats() {
        return TProxyGetStats();
    }

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
}
