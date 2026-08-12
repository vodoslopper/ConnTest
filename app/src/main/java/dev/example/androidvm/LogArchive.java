package dev.example.androidvm;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class LogArchive {
    private static final int COPY_BUFFER_BYTES = 16 * 1024;

    private LogArchive() {
    }

    static void create(File destination, String connectionLog, File nativeLog)
            throws IOException {
        synchronized (NativeLogRotator.class) {
            try (ZipOutputStream archive = new ZipOutputStream(
                    new FileOutputStream(destination, false))) {
                addText(archive, "connection-log.txt", connectionLog);
                addFileIfPresent(archive, nativeLog);
                addFileIfPresent(archive, NativeLogRotator.backupFor(nativeLog));
            }
        }
    }

    private static void addText(ZipOutputStream archive, String name, String value)
            throws IOException {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(value.getBytes(StandardCharsets.UTF_8));
        archive.closeEntry();
    }

    private static void addFileIfPresent(ZipOutputStream archive, File file)
            throws IOException {
        if (!file.isFile()) return;
        archive.putNextEntry(new ZipEntry(file.getName()));
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int count;
            while ((count = input.read(buffer)) != -1) archive.write(buffer, 0, count);
        }
        archive.closeEntry();
    }
}
