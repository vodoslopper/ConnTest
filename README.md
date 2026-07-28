# ConnTest

> [!WARNING]
> ConnTest is experimental diagnostic software under active development. It has
> not been security-audited and may disrupt or leak network traffic. Do not use
> it for production, privacy-critical, or sensitive connections.

ConnTest is an Android test client for checking an SSH connection with the
equivalent of:

```sh
ssh -N -D <local-socks-port> -p <ssh-port> <user>@<host>
```

After the SSH connection succeeds, ConnTest creates an Android routing
interface and sends other apps' TCP connections and DNS lookups to the SSH
dynamic SOCKS5 endpoint. The ready-to-install, release-signed APK is
[artifacts/ConnTest-release.apk](artifacts/ConnTest-release.apk).

## Test an SSH connection

1. Install `artifacts/ConnTest-release.apk`.
2. Enter the SSH host, port, username, and desired local SOCKS port.
3. Copy the Ed25519 public key shown by ConnTest into the SSH account's
   `~/.ssh/authorized_keys`. The corresponding unencrypted private key is
   generated once and kept in the app's private internal storage.
4. Leave **accept unknown SSH host key** enabled only for a disposable test
   server. Disable it when testing strict host-key rejection.
5. Tap **Test SSH with routing** and approve Android's connection request.
6. Open a browser or another app to test its TCP connection through SSH.
7. Return to ConnTest and tap **Disconnect** when the test is complete. Use
   **Show connection logs** for verbose diagnostics or **Share logs file** to
   send the current text log through Android's share sheet.
8. To verify the complete route from inside ConnTest, leave the default
   `https://api.ipify.org?format=json` URL and tap **Send HTTP request through
   SSH routing**. The response displays the public address observed by the
   site. Any HTTP or routing error is also added to the connection log.

ConnTest does not save the fallback password. It saves only the generated
unencrypted Ed25519 identity in private app storage plus the host, username,
port numbers, and test host-key option. Regenerating the identity requires the
new public key to be installed on every SSH server that should accept it.

## What the SSH test covers

ConnTest uses an embedded SSH client to implement the behavior of `ssh -D`.
The SSH transport is created first and excluded from the Android routing path.
A loopback SOCKS5 listener opens SSH `direct-tcpip` channels for each
connection. The native TUN-to-SOCKS bridge then routes other apps through that
listener.

The test covers:

- SSH public-key authentication, tried first.
- Optional SSH password fallback.
- SSH connection and keepalive behavior.
- Dynamic SOCKS5 TCP forwarding.
- DNS queries converted to DNS-over-TCP and carried through SSH.
- Android routing for other installed apps.

Standard OpenSSH dynamic forwarding does not implement SOCKS5 UDP forwarding.
ConnTest handles only DNS from SOCKS5 UDP association, converting each query to
DNS-over-TCP on an SSH `direct-tcpip` channel. It blocks other UDP instead of
allowing it to bypass the SSH test. Apps using QUIC or another UDP-only protocol
might fail; many browsers fall back to TCP. IPv6 is intentionally not routed in
this test build.

## SSH test security

The **accept unknown SSH host key** option is equivalent to
`-o StrictHostKeyChecking=no`. It is useful for short-lived connection tests but
does not authenticate the server and is unsafe for sensitive traffic.

ConnTest is a diagnostic example, not a production traffic-routing tool. Use
only the release-signed artifact for installation.

## SSH test server in the VM

The `android-builder` VM can host a disposable SSH account for connection
tests. Start the VM, copy the setup script, and run it as root:

```sh
vml start --wait-ssh -n android-builder
vml rsync-to --archive --check --sources scripts/setup-ssh-test-server.sh \
  --destination /root/ -n android-builder
vml ssh --check --cmd '/root/setup-ssh-test-server.sh' -n android-builder
```

The script creates the `conntest` account, generates and authorizes an Ed25519
test identity, enables TCP forwarding, and writes the identity path plus
fallback credentials to:

```text
/root/conntest-test-credentials
```

Copy the public key displayed in ConnTest into the test account's
`~/.ssh/authorized_keys`. The generated test-server password remains available
for optional fallback testing.

VML currently forwards this server only to the host loopback address. Read the
generated VML SSH configuration to find its host port. A physical phone cannot
reach that loopback-only listener; changing it to a LAN listener is a separate
security-sensitive host-network configuration.

## Build the SSH test APK in the VM

The persistent `android-builder` VM uses Ubuntu 24.04 LTS, OpenJDK 17, Android
API 35, NDK 27, and Gradle 8.9. Create it when it does not already exist:

```sh
vml run --image ubuntu-noble --memory 6G --nproc 2 \
  --minimum-disk-size 40G --wait-ssh -n android-builder
```

Clone submodules, copy the SSH test project into the VM, and build the
release-signed artifact:

```sh
git submodule update --init --recursive
vml rsync-to --archive --check --sources ./ \
  --destination /root/ConnTest/ -n android-builder
vml ssh --check --cmd \
  'cd /root/ConnTest && ./scripts/provision-android-builder.sh' \
  -n android-builder
vml ssh --check --cmd \
  'cd /root/ConnTest && ./scripts/build-release.sh' \
  -n android-builder
```

The VM produces the SSH test APK at:

```text
artifacts/ConnTest-release.apk
```

Copy it into the repository as `artifacts/ConnTest-release.apk`, update
`artifacts/SHA256SUMS`, and verify:

```sh
sha256sum -c artifacts/SHA256SUMS
```

## Build a release APK for direct installation

Run the release builder inside `android-builder`:

```sh
cd /home/androidbuild/ConnTest
./scripts/build-release.sh
```

On its first run, the script creates a private release key and passwords in
`signing/ConnTest-release.jks` and `keystore.properties`. Both files are ignored
by Git. Back up both files securely: every future update must use the same key,
or Android will reject it. Do not distribute either signing file.

The installable output is `artifacts/ConnTest-release.apk`. Every update must
be built with the same signing files.

## Components used by the SSH test

- Android's packet-routing service provides the user-approved TUN interface.
- `com.github.mwiede:jsch` provides the embedded SSH transport.
- `hev-socks5-tunnel` 2.12.0 bridges the TUN interface to SOCKS5 and is pinned
  as a Git submodule. This version supports 16-KB Android page sizes.

## License

ConnTest's original code is available under the [MIT License](LICENSE).
Third-party components remain under their respective licenses; their copyright
and license notices are not replaced by the ConnTest license.
