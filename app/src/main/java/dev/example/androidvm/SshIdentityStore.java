package dev.example.androidvm;

import android.content.Context;
import android.util.Base64;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SshIdentityStore {
    static final String DEFAULT_NAME = "main";
    private static final String LEGACY_FILE = "id_ed25519";
    private static final String PREFIX = "ssh_key_";
    private static final String AUTHORIZED_KEYS_OPTIONS = "restrict,port-forwarding ";
    private static final String COMMENT_SUFFIX = "@conntest";
    private static final int MAX_KEY_BYTES = 64 * 1024;

    private final File privateKeyFile;
    private final String name;
    private final String publicKey;

    private SshIdentityStore(File privateKeyFile, String name, String publicKey) {
        this.privateKeyFile = privateKeyFile;
        this.name = name;
        this.publicKey = publicKey;
    }

    static synchronized void ensureDefault(Context context) throws IOException, JSchException {
        File main = file(context, DEFAULT_NAME);
        File legacy = new File(context.getFilesDir(), LEGACY_FILE);
        if (!main.isFile() && legacy.isFile() && !legacy.renameTo(main)) {
            throw new IOException("could not migrate the existing SSH identity");
        }
        if (!main.isFile()) {
            writeKey(main, DEFAULT_NAME);
            ConnectionLog.append("Generated the default unencrypted Ed25519 key 'main'");
        }
    }

    static synchronized SshIdentityStore load(Context context, String name)
            throws IOException, JSchException {
        validateName(name);
        ensureDefault(context);
        File privateKeyFile = file(context, name);
        if (!privateKeyFile.isFile()) {
            throw new IOException("SSH key does not exist: " + name);
        }
        KeyPair keyPair = KeyPair.load(new JSch(), privateKeyFile.getAbsolutePath());
        try {
            if (keyPair.getKeyType() != KeyPair.ED25519 || keyPair.isEncrypted()) {
                throw new IOException("stored SSH identity is not an unencrypted Ed25519 key");
            }
            String publicKey = AUTHORIZED_KEYS_OPTIONS + keyPair.getKeyTypeString() + " "
                    + Base64.encodeToString(keyPair.getPublicKeyBlob(), Base64.NO_WRAP)
                    + " " + name + COMMENT_SUFFIX;
            return new SshIdentityStore(privateKeyFile, name, publicKey);
        } finally {
            keyPair.dispose();
        }
    }

    static synchronized SshIdentityStore create(Context context, String name)
            throws IOException, JSchException {
        validateName(name);
        ensureDefault(context);
        File destination = file(context, name);
        if (destination.exists()) {
            throw new IOException("A key named '" + name + "' already exists");
        }
        writeKey(destination, name);
        ConnectionLog.append("Generated unencrypted Ed25519 key '" + name + "'");
        return load(context, name);
    }

    static synchronized SshIdentityStore regenerate(Context context, String name)
            throws IOException, JSchException {
        validateName(name);
        File destination = file(context, name);
        File replacement = new File(context.getFilesDir(), destination.getName() + ".replacement");
        File backup = new File(context.getFilesDir(), destination.getName() + ".backup");
        deleteIfPresent(replacement, "could not clear an incomplete replacement key");
        deleteIfPresent(backup, "could not clear an incomplete key backup");
        writeKey(replacement, name);
        if (destination.exists() && !destination.renameTo(backup)) {
            replacement.delete();
            throw new IOException("could not preserve the existing SSH key");
        }
        if (!replacement.renameTo(destination)) {
            if (backup.exists()) {
                backup.renameTo(destination);
            }
            throw new IOException("could not save the replacement SSH key");
        }
        backup.delete();
        ConnectionLog.append("Regenerated Ed25519 key '" + name + "'");
        return load(context, name);
    }

    static synchronized List<String> names(Context context) throws IOException, JSchException {
        ensureDefault(context);
        List<String> result = new ArrayList<>();
        File[] files = context.getFilesDir().listFiles();
        if (files != null) {
            for (File candidate : files) {
                String filename = candidate.getName();
                if (candidate.isFile() && filename.startsWith(PREFIX)
                        && !filename.endsWith(".replacement") && !filename.endsWith(".backup")) {
                    result.add(filename.substring(PREFIX.length()));
                }
            }
        }
        Collections.sort(result);
        result.remove(DEFAULT_NAME);
        result.add(0, DEFAULT_NAME);
        return result;
    }

    static synchronized void delete(Context context, String name) throws IOException {
        validateName(name);
        if (DEFAULT_NAME.equals(name)) {
            throw new IOException("The default key 'main' cannot be deleted");
        }
        File destination = file(context, name);
        if (!destination.isFile() || !destination.delete()) {
            throw new IOException("could not delete SSH key '" + name + "'");
        }
        ConnectionLog.append("Deleted SSH key '" + name + "'");
    }

    String getName() {
        return name;
    }

    String getPublicKey() {
        return publicKey;
    }

    byte[] readPrivateKey() throws IOException {
        try (FileInputStream input = new FileInputStream(privateKeyFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_KEY_BYTES) {
                    throw new IOException("stored SSH identity is unexpectedly large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static File file(Context context, String name) {
        return new File(context.getFilesDir(), PREFIX + name);
    }

    private static void validateName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) {
            throw new IOException("Key names may use 1-40 letters, numbers, '.', '_' or '-'");
        }
    }

    private static void writeKey(File destination, String name) throws IOException, JSchException {
        KeyPair keyPair = KeyPair.genKeyPair(new JSch(), KeyPair.ED25519);
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            keyPair.setPublicKeyComment(name + COMMENT_SUFFIX);
            keyPair.writeOpenSSHv1PrivateKey(output, (byte[]) null);
            output.getFD().sync();
        } finally {
            keyPair.dispose();
        }
        destination.setReadable(false, false);
        destination.setWritable(false, false);
        destination.setReadable(true, true);
        destination.setWritable(true, true);
    }

    private static void deleteIfPresent(File file, String message) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException(message);
        }
    }
}
