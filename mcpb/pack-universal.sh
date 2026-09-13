#!/usr/bin/env bash
# Pack the universal MCP Bundle: the macOS, Windows and Linux x86_64 native binaries in one .mcpb, with the
# manifest's platform_overrides choosing the right one at launch. This is the bundle a Claude plugin
# marketplace entry points at, since a plugin can reference only one bundle for all platforms. Linux aarch64
# is left out to keep the bundle under its 50 MB ceiling; that platform takes the bare binary from the release.
#
#   mcpb/pack-universal.sh <version> <binaries-dir> <output.mcpb>
#
#   version       release version without the "v" prefix, e.g. 0.1.2
#   binaries-dir  directory holding rpg-mcp-server-<version>-{linux-x86_64,macos-aarch64,windows-x86_64.exe}
#   output        path of the bundle to write
#
# Run on Linux or macOS: the executable bits of the Unix binaries and the launcher must end up in the zip, and
# a pack done on Windows cannot set them.
set -euo pipefail

if [ $# -ne 3 ]; then
	echo "usage: $0 <version> <binaries-dir> <output.mcpb>" >&2
	exit 2
fi
VERSION="$1"; SRC="$2"; OUTPUT="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/server"
for suffix in linux-x86_64 macos-aarch64 windows-x86_64.exe; do
	name="rpg-mcp-server-${VERSION}-${suffix}"
	if [ ! -f "$SRC/$name" ]; then
		echo "missing binary: $SRC/$name" >&2
		exit 1
	fi
	cp "$SRC/$name" "$STAGE/server/$name"
	chmod +x "$STAGE/server/$name"
done
sed -e "s/__VERSION__/${VERSION}/g" "$HERE/linux-launcher.sh" > "$STAGE/server/rpg-mcp-server-${VERSION}-linux"
chmod +x "$STAGE/server/rpg-mcp-server-${VERSION}-linux"
sed -e "s/__VERSION__/${VERSION}/g" "$HERE/manifest-universal.json" > "$STAGE/manifest.json"

npx -y @anthropic-ai/mcpb validate "$STAGE/manifest.json"
npx -y @anthropic-ai/mcpb pack "$STAGE" "$OUTPUT"

# The marketplace bundle has a hard 50 MB ceiling; each native image gets about 12 MB of it compressed
# (three images leave room to spare, four did not, which is why Linux aarch64 is not in here).
# DEVGUIDE.md ("Native image size budget") explains what keeps the images that small.
LIMIT=50000000
SIZE="$(wc -c < "$OUTPUT")"
if [ "$SIZE" -gt "$LIMIT" ]; then
	echo "universal bundle is $SIZE bytes, over the $LIMIT byte limit" >&2
	exit 1
fi
echo "packed $OUTPUT (universal, $SIZE bytes)"
