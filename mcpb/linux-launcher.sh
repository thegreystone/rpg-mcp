#!/bin/sh
# Linux entry point of the universal MCP Bundle. The bundle carries only the x86_64 Linux build (a second one
# would push it past the 50 MB ceiling); on any other architecture, say so instead of failing with an obscure
# "exec format error".
dir="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -m)" in
	x86_64 | amd64) ;;
	*)
		echo "rpg-mcp: the universal bundle has no Linux build for $(uname -m); download rpg-mcp-server-__VERSION__-linux-aarch64 from https://github.com/thegreystone/rpg-mcp/releases and point your MCP client at it" >&2
		exit 1
		;;
esac
exec "$dir/rpg-mcp-server-__VERSION__-linux-x86_64" "$@"
