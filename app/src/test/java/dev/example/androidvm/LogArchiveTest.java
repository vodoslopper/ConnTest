package dev.example.androidvm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LogArchiveTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void includesConnectionCurrentAndBackupLogs() throws Exception {
        File nativeLog = temporaryFolder.newFile(NativeLogRotator.FILE_NAME);
        File backup = NativeLogRotator.backupFor(nativeLog);
        write(nativeLog, new byte[]{1, 2, 3});
        write(backup, new byte[]{4, 5});
        File archive = new File(temporaryFolder.getRoot(), "conntest-logs.zip");

        LogArchive.create(archive, "connection details", nativeLog);

        Map<String, byte[]> entries = readEntries(archive);
        assertEquals(3, entries.size());
        assertArrayEquals("connection details".getBytes(StandardCharsets.UTF_8),
                entries.get("connection-log.txt"));
        assertArrayEquals(new byte[]{1, 2, 3}, entries.get(NativeLogRotator.FILE_NAME));
        assertArrayEquals(new byte[]{4, 5},
                entries.get(NativeLogRotator.FILE_NAME + ".1"));
    }

    @Test
    public void omitsNativeEntriesWhenTheyDoNotExist() throws Exception {
        File archive = temporaryFolder.newFile("conntest-logs.zip");
        File nativeLog = new File(temporaryFolder.getRoot(), NativeLogRotator.FILE_NAME);

        LogArchive.create(archive, "only connection log", nativeLog);

        Map<String, byte[]> entries = readEntries(archive);
        assertEquals(1, entries.size());
        assertTrue(entries.containsKey("connection-log.txt"));
        assertFalse(entries.containsKey(NativeLogRotator.FILE_NAME));
    }

    private static void write(File file, byte[] value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value);
        }
    }

    private static Map<String, byte[]> readEntries(File archive) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                entries.put(entry.getName(), output.toByteArray());
            }
        }
        return entries;
    }
}
