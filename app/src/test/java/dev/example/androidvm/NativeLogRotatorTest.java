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
import java.io.IOException;

public final class NativeLogRotatorTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void leavesLogAtLimitInPlace() throws Exception {
        File log = temporaryFolder.newFile("routing-native.log");
        writePattern(log, (int) NativeLogRotator.MAX_BYTES, 0);

        assertFalse(NativeLogRotator.rotateIfNeeded(log));
        assertEquals(NativeLogRotator.MAX_BYTES, log.length());
        assertFalse(new File(log.getPath() + ".1").exists());
    }

    @Test
    public void keepsLatestMegabyteAndTruncatesActiveLog() throws Exception {
        File log = temporaryFolder.newFile("routing-native.log");
        int excess = 4096;
        writePattern(log, (int) NativeLogRotator.MAX_BYTES + excess, 0);

        assertTrue(NativeLogRotator.rotateIfNeeded(log));

        File backup = new File(log.getPath() + ".1");
        assertEquals(0, log.length());
        assertEquals(NativeLogRotator.MAX_BYTES, backup.length());
        assertArrayEquals(expectedPattern(
                (int) NativeLogRotator.MAX_BYTES, excess), readAll(backup));
    }

    @Test
    public void replacesOldBackupAndResetRemovesBothLogs() throws Exception {
        File log = temporaryFolder.newFile("routing-native.log");
        File backup = new File(log.getPath() + ".1");
        writePattern(backup, 32, 7);
        writePattern(log, (int) NativeLogRotator.MAX_BYTES + 1, 19);

        assertTrue(NativeLogRotator.rotateIfNeeded(log));
        assertEquals(NativeLogRotator.MAX_BYTES, backup.length());

        NativeLogRotator.reset(log);
        assertFalse(log.exists());
        assertFalse(backup.exists());
    }

    @Test
    public void appendWriterContinuesAfterActiveLogIsTruncated() throws Exception {
        File log = temporaryFolder.newFile("routing-native.log");
        writePattern(log, (int) NativeLogRotator.MAX_BYTES + 1, 0);

        try (FileOutputStream nativeWriter = new FileOutputStream(log, true)) {
            assertTrue(NativeLogRotator.rotateIfNeeded(log));
            nativeWriter.write(new byte[]{11, 12, 13});
        }

        assertArrayEquals(new byte[]{11, 12, 13}, readAll(log));
    }

    @Test
    public void clearTruncatesActiveWriterAndDeletesBackup() throws Exception {
        File log = temporaryFolder.newFile(NativeLogRotator.FILE_NAME);
        File backup = NativeLogRotator.backupFor(log);
        writePattern(log, 64, 0);
        writePattern(backup, 32, 7);

        try (FileOutputStream nativeWriter = new FileOutputStream(log, true)) {
            NativeLogRotator.clear(log);
            nativeWriter.write(new byte[]{21, 22});
        }

        assertArrayEquals(new byte[]{21, 22}, readAll(log));
        assertFalse(backup.exists());
    }

    private static void writePattern(File file, int size, int offset) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(expectedPattern(size, offset));
        }
    }

    private static byte[] expectedPattern(int size, int offset) {
        byte[] result = new byte[size];
        for (int i = 0; i < size; i++) result[i] = (byte) ((i + offset) % 251);
        return result;
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
