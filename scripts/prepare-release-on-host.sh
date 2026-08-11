#!/bin/sh -eu

project_dir=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
artifact_dir="$project_dir/artifacts"
artifact_file="$artifact_dir/ConnTest-release.apk"
checksum_file="$artifact_dir/SHA256SUMS"
signature_file="$checksum_file.asc"

if ! command -v sha256sum >/dev/null 2>&1; then
    echo "error: sha256sum is required" >&2
    exit 1
fi

if ! command -v gpg >/dev/null 2>&1; then
    echo "error: gpg is required" >&2
    exit 1
fi

if [ ! -f "$artifact_file" ]; then
    echo "error: release APK not found: $artifact_file" >&2
    exit 1
fi

checksum_temporary="$checksum_file.new"
signature_temporary="$signature_file.new"
trap 'rm -f "$checksum_temporary" "$signature_temporary"' EXIT HUP INT TERM

(
    cd "$project_dir"
    sha256sum "artifacts/$(basename "$artifact_file")"
) >"$checksum_temporary"

set --
if [ -n "${CONNTEST_GPG_KEY:-}" ]; then
    set -- --local-user "$CONNTEST_GPG_KEY"
fi
gpg --batch --yes --armor "$@" --output "$signature_temporary" \
    --detach-sign "$checksum_temporary"

(
    cd "$project_dir"
    sha256sum -c "$checksum_temporary"
)
gpg --batch --verify "$signature_temporary" "$checksum_temporary"

mv "$checksum_temporary" "$checksum_file"
mv "$signature_temporary" "$signature_file"

(
    cd "$project_dir"
    sha256sum -c "$checksum_file"
)
gpg --batch --verify "$signature_file" "$checksum_file"

echo "Release files:"
echo "  $artifact_file"
echo "  $checksum_file"
echo "  $signature_file"
