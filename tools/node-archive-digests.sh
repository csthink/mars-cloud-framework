#!/usr/bin/env bash
# Print the BOM properties node.sha256.<platform> for one Node release, taken from its SHASUMS256.txt
# after the detached signature of that file is verified with the Node release keys listed in
# github.com/nodejs/release-keys. Run it when changing node.version and paste the output into
# mars-cloud-dependencies/pom.xml. Requires curl and gpg; the keyring lives in a temporary directory.
#
#   tools/node-archive-digests.sh v24.21.0
#
# NODE_DOWNLOAD_ROOT (ending in /) replaces https://nodejs.org/dist/ (a mirror or a file:// directory);
# the signature is verified in the same way.
set -euo pipefail
[ "$#" -eq 1 ] && [[ "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'usage: node-archive-digests.sh v<major>.<minor>.<patch>' >&2; exit 1; }
version="$1"
root="${NODE_DOWNLOAD_ROOT:-https://nodejs.org/dist/}"
keys_root=https://raw.githubusercontent.com/nodejs/release-keys/main
platforms='darwin-arm64 darwin-x64 linux-x64 linux-arm64'

work="$(mktemp -d)"
export GNUPGHOME="$work/gnupg"
# gpg starts an agent for the temporary home; stop it before the directory is removed.
trap 'gpgconf --kill gpg-agent 2>/dev/null || true; rm -rf "$work"' EXIT
mkdir -m 700 "$GNUPGHOME"
fetch() { curl --fail --silent --show-error --location --retry 3 "$1" -o "$2"; }

fetch "$keys_root/keys.list" "$work/keys.list"
while read -r key; do
  [[ "$key" =~ ^[0-9A-F]{40}$ ]] || { echo "unexpected line in keys.list: $key" >&2; exit 1; }
  fetch "$keys_root/keys/$key.asc" "$work/$key.asc"
done < "$work/keys.list"
gpg --batch --quiet --import "$work"/*.asc

fetch "$root$version/SHASUMS256.txt" "$work/SHASUMS256.txt"
fetch "$root$version/SHASUMS256.txt.sig" "$work/SHASUMS256.txt.sig"
status="$(gpg --batch --status-fd 1 --verify "$work/SHASUMS256.txt.sig" "$work/SHASUMS256.txt" 2>/dev/null)" \
  || { echo "signature of SHASUMS256.txt for $version is not valid" >&2; exit 1; }
signer="$(sed -n 's/^\[GNUPG:\] GOODSIG [0-9A-F]* //p' <<<"$status")"
primary="$(sed -n 's/^\[GNUPG:\] VALIDSIG .* \([0-9A-F]\{40\}\)$/\1/p' <<<"$status")"
if [ -z "$signer" ] || [ -z "$primary" ] || ! grep -qx "$primary" "$work/keys.list"; then
  echo "SHASUMS256.txt for $version is not signed by a listed Node release key" >&2
  exit 1
fi
echo "SHASUMS256.txt for $version: good signature from $signer (primary key $primary)" >&2

for platform in $platforms; do
  file="node-$version-$platform.tar.gz"
  digest="$(awk -v file="$file" '$2 == file { print $1 }' "$work/SHASUMS256.txt")"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || { echo "SHASUMS256.txt for $version has no digest for $file" >&2; exit 1; }
  printf '        <node.sha256.%s>%s</node.sha256.%s>\n' "$platform" "$digest" "$platform"
done
