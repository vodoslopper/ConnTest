#!/bin/sh -eu

android_home=${ANDROID_HOME:-/opt/android-sdk}
gradle_version=${GRADLE_VERSION:-8.9}
command_line_tools_version=${COMMAND_LINE_TOOLS_VERSION:-11076708}
work_dir=$(mktemp -d)

cleanup()
{
    rm -rf "$work_dir"
}
trap cleanup EXIT HUP INT TERM

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl openjdk-17-jdk-headless unzip

mkdir -p "$android_home/cmdline-tools"
curl -fL \
    "https://dl.google.com/android/repository/commandlinetools-linux-${command_line_tools_version}_latest.zip" \
    -o "$work_dir/android-command-line-tools.zip"
unzip -qo "$work_dir/android-command-line-tools.zip" -d "$work_dir/android-tools"
rm -rf "$android_home/cmdline-tools/latest"
mkdir -p "$android_home/cmdline-tools/latest"
cp -R "$work_dir/android-tools/cmdline-tools/." "$android_home/cmdline-tools/latest/"

export ANDROID_HOME="$android_home"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --licenses >/dev/null
sdkmanager \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;34.0.0" \
    "build-tools;35.0.0" \
    "ndk;27.0.12077973"

curl -fL \
    "https://services.gradle.org/distributions/gradle-${gradle_version}-bin.zip" \
    -o "$work_dir/gradle.zip"
unzip -qo "$work_dir/gradle.zip" -d /opt
ln -sfn "/opt/gradle-$gradle_version/bin/gradle" /usr/local/bin/gradle

cat > /etc/profile.d/android-sdk.sh <<EOF
export ANDROID_HOME=$android_home
export PATH=\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH
EOF

java -version
sdkmanager --version
gradle --version
