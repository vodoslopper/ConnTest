package dev.example.androidvm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

final class NativeLogRotator {
    static final String FILE_NAME = "routing-native.log";
    static final long MAX_BYTES = 1024L * 1024L;
    private static final int COPY_BUFFER_BYTES = 16 * 1024;

    private NativeLogRotator() {
    }

    static synchronized boolean rotateIfNeeded(File log) throws IOException {
        if (!log.isFile() || log.length() <= MAX_BYTES) return false;

        File backup = backupFor(log);
        File temporary = new File(log.getPath() + ".1.tmp");
        copyTail(log, temporary);
        if (backup.exists() && !backup.delete()) {
            deleteTemporary(temporary);
            throw new IOException("Could not replace native routing log backup");
        }
        if (!temporary.renameTo(backup)) {
            deleteTemporary(temporary);
            throw new IOException("Could not install native routing log backup");
        }

        try (RandomAccessFile active = new RandomAccessFile(log, "rw")) {
            active.setLength(0);
        }
        return true;
    }

    static synchronized void reset(File log) throws IOException {
        delete(log, "Could not reset native routing log");
        delete(backupFor(log), "Could not reset native routing log backup");
        delete(new File(log.getPath() + ".1.tmp"),
                "Could not reset temporary native routing log backup");
    }

    static synchronized void clear(File log) throws IOException {
        if (log.exists()) {
            try (RandomAccessFile active = new RandomAccessFile(log, "rw")) {
                active.setLength(0);
            }
        }
        delete(backupFor(log), "Could not clear native routing log backup");
        delete(new File(log.getPath() + ".1.tmp"),
                "Could not clear temporary native routing log backup");
    }

    private static void copyTail(File sourceFile, File destination) throws IOException {
        try (RandomAccessFile source = new RandomAccessFile(sourceFile, "r");
             FileOutputStream output = new FileOutputStream(destination, false)) {
            long remaining = Math.min(source.length(), MAX_BYTES);
            source.seek(source.length() - remaining);
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            while (remaining > 0) {
                int count = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) break;
                output.write(buffer, 0, count);
                remaining -= count;
            }
        } catch (IOException exception) {
            deleteTemporary(destination);
            throw exception;
        }
    }

    static File backupFor(File log) {
        return new File(log.getPath() + ".1");
    }

    private static void delete(File file, String message) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException(message);
    }

    private static void deleteTemporary(File file) {
        if (file.exists()) file.delete();
    }
}
