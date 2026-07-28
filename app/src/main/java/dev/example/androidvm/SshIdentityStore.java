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

final class SshIdentityStore {
    private static final String PRIVATE_KEY_FILE = "id_ed25519";
    private static final String COMMENT = "conntest@android";
    private static final int MAX_KEY_BYTES = 64 * 1024;

    private final File privateKeyFile;
    private final String publicKey;

    private SshIdentityStore(File privateKeyFile, String publicKey) {
        this.privateKeyFile = privateKeyFile;
        this.publicKey = publicKey;
    }

    static synchronized SshIdentityStore loadOrCreate(Context context)
            throws IOException, JSchException {
        File privateKeyFile = new File(context.getFilesDir(), PRIVATE_KEY_FILE);
        if (!privateKeyFile.isFile()) {
            generate(privateKeyFile);
            ConnectionLog.append("Generated a new unencrypted Ed25519 SSH identity");
        }
        KeyPair keyPair = KeyPair.load(new JSch(), privateKeyFile.getAbsolutePath());
        try {
            if (keyPair.getKeyType() != KeyPair.ED25519 || keyPair.isEncrypted()) {
                throw new IOException("stored SSH identity is not an unencrypted Ed25519 key");
            }
            String publicKey = keyPair.getKeyTypeString()
                    + " "
                    + Base64.encodeToString(keyPair.getPublicKeyBlob(), Base64.NO_WRAP)
                    + " "
                    + COMMENT;
            return new SshIdentityStore(privateKeyFile, publicKey);
        } finally {
            keyPair.dispose();
        }
    }

    static synchronized SshIdentityStore regenerate(Context context)
            throws IOException, JSchException {
        File privateKeyFile = new File(context.getFilesDir(), PRIVATE_KEY_FILE);
        if (privateKeyFile.exists() && !privateKeyFile.delete()) {
            throw new IOException("could not replace the existing SSH identity");
        }
        return loadOrCreate(context);
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

    private static void generate(File destination) throws IOException, JSchException {
        File temporary = new File(destination.getParentFile(), PRIVATE_KEY_FILE + ".new");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("could not clear an incomplete SSH identity");
        }
        KeyPair keyPair = KeyPair.genKeyPair(new JSch(), KeyPair.ED25519);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            keyPair.setPublicKeyComment(COMMENT);
            keyPair.writeOpenSSHv1PrivateKey(output, (byte[]) null);
            output.getFD().sync();
        } finally {
            keyPair.dispose();
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("could not save the generated SSH identity");
        }
        destination.setReadable(false, false);
        destination.setWritable(false, false);
        destination.setReadable(true, true);
        destination.setWritable(true, true);
    }
}
