#!/usr/bin/env bash
# Official distribution, verified before extraction. Destination must not already exist.
set -euo pipefail
[ "$#" -eq 1 ] || { echo 'usage: install-maven.sh <new-directory>' >&2; exit 1; }
[ ! -e "$1" ] || { echo 'destination already exists' >&2; exit 1; }
mkdir -p "$1"
dest="$(cd "$1" && pwd)"
archive="$dest/maven.tar.gz"
curl --fail --location --retry 3 https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.14/apache-maven-3.9.14-bin.tar.gz -o "$archive"
printf '%s  %s\n' 'd50af8ab5e6005b46a07f0ce9d3719e67cfdf898da988a84871304cd59fb1af0fef2f99dea709e6e66f21f732f905979b5c2dce6b6860406f60a70e84d9cf0b8' "$archive" | shasum -a 512 -c -
tar -xzf "$archive" -C "$dest"
printf '%s\n' "$dest/apache-maven-3.9.14/bin"
