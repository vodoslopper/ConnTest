package dev.example.androidvm;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ConnectionLog {
    private static final int MAX_CHARACTERS = 64 * 1024;
    private static final StringBuilder BUFFER = new StringBuilder();
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private ConnectionLog() {
    }

    static synchronized void append(String message) {
        BUFFER.append(TIME_FORMAT.format(new Date()))
                .append("  ")
                .append(message == null ? "" : message)
                .append('\n');
        if (BUFFER.length() > MAX_CHARACTERS) {
            int removeThrough = BUFFER.indexOf(
                    "\n",
                    BUFFER.length() - MAX_CHARACTERS);
            BUFFER.delete(0, removeThrough < 0 ? BUFFER.length() - MAX_CHARACTERS : removeThrough + 1);
        }
    }

    static synchronized String snapshot() {
        return BUFFER.toString();
    }

    static synchronized void clear() {
        BUFFER.setLength(0);
    }
}
