#!/bin/sh -eu

project_dir=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
properties_file="$project_dir/keystore.properties"
keystore_dir="$project_dir/signing"
keystore_file="$keystore_dir/ConnTest-release.jks"
artifact_dir="$project_dir/artifacts"
artifact_file="$artifact_dir/ConnTest-release.apk"

if ! command -v keytool >/dev/null 2>&1; then
    echo "error: keytool is required (install a JDK)" >&2
    exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
    echo "error: openssl is required to generate signing passwords" >&2
    exit 1
fi

if [ ! -f "$properties_file" ] || [ ! -f "$keystore_file" ]; then
    if [ -e "$properties_file" ] || [ -e "$keystore_file" ]; then
        echo "error: incomplete signing configuration; restore both signing files from backup" >&2
        exit 1
    fi

    mkdir -p "$keystore_dir"
    chmod 700 "$keystore_dir"
    store_password=$(openssl rand -hex 24)

    keytool -genkeypair -noprompt \
        -keystore "$keystore_file" \
        -storepass "$store_password" \
        -alias conntest \
        -keypass "$store_password" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 10000 \
        -dname "CN=ConnTest, OU=Direct Distribution, O=ConnTest"

    umask 077
    {
        echo "storeFile=signing/ConnTest-release.jks"
        echo "storePassword=$store_password"
        echo "keyAlias=conntest"
        echo "keyPassword=$store_password"
    } >"$properties_file"
fi

cd "$project_dir"
ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk} \
    ./gradlew --no-daemon lintRelease assembleRelease

mkdir -p "$artifact_dir"
cp "$project_dir/app/build/outputs/apk/release/app-release.apk" "$artifact_file"

echo "Release APK: $artifact_file"
echo "Export the APK, then run scripts/prepare-release-on-host.sh on the host."
echo "Back up signing/ConnTest-release.jks and keystore.properties securely."
