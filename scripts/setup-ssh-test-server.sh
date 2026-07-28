#!/bin/sh -eu

test_user=${CONNTEST_TEST_USER:-conntest}
credentials_file=/root/conntest-test-credentials
identity_file=/root/conntest-test-ed25519
sshd_drop_in=/etc/ssh/sshd_config.d/00-conntest-testing.conf
old_sshd_drop_in=/etc/ssh/sshd_config.d/60-conntest-testing.conf

if [ "$(id -u)" -ne 0 ]; then
    echo "Run this SSH test setup as root." >&2
    exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y openssh-server openssl

if ! id "$test_user" >/dev/null 2>&1; then
    useradd --create-home --shell /bin/sh "$test_user"
fi

if [ ! -f "$identity_file" ]; then
    ssh-keygen -q -t ed25519 -N '' -C 'ConnTest VM test key' -f "$identity_file"
fi

test_home=$(getent passwd "$test_user" | cut -d: -f6)
install -d -m 700 -o "$test_user" -g "$test_user" "$test_home/.ssh"
install -m 600 -o "$test_user" -g "$test_user" \
    "$identity_file.pub" "$test_home/.ssh/authorized_keys"

test_password=${CONNTEST_TEST_PASSWORD:-$(openssl rand -hex 12)}
printf '%s:%s\n' "$test_user" "$test_password" | chpasswd

umask 077
{
    printf 'SSH_TEST_USER=%s\n' "$test_user"
    printf 'SSH_TEST_IDENTITY=%s\n' "$identity_file"
    printf 'SSH_TEST_PASSWORD=%s\n' "$test_password"
} > "$credentials_file"

{
    printf '%s\n' 'PubkeyAuthentication yes'
    printf '%s\n' 'PasswordAuthentication yes'
    printf '%s\n' 'AllowTcpForwarding yes'
    printf '%s\n' 'PermitRootLogin prohibit-password'
    printf '%s\n' 'X11Forwarding no'
} > "$sshd_drop_in"
rm -f "$old_sshd_drop_in"

mkdir -p /run/sshd
sshd -t
systemctl restart ssh.service
printf 'SSH test account configured; key and fallback credentials are in %s\n' \
    "$credentials_file"
