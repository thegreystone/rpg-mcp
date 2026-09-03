/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity test for the native image binary: starts the executable against a temporary data directory, performs the MCP
 * handshake over STDIO, lists tools, and calls get_server_state (which exercises SQLite, migrations and the seed import
 * inside the native image).
 * <p>
 * Skipped unless {@code native.image.path} is set, e.g.
 * {@code mvn test-compile failsafe:integration-test -Dnative.image.path=target/rpg-mcp-server-0.1.0-runner.exe}.
 */
class NativeImageSanityIT {

	@Test
	@EnabledIfSystemProperty(named = "native.image.path", matches = ".+")
	void nativeBinaryRespondsToMcp() throws Exception {
		Path binary = Path.of(System.getProperty("native.image.path"));
		assertTrue(Files.exists(binary), "Native binary not found at: " + binary);
		Path dataDir = Files.createTempDirectory("rpg-mcp-native-it");

		ProcessBuilder pb = new ProcessBuilder(binary.toAbsolutePath().toString(),
				"-Drpg.data-dir=" + dataDir.toAbsolutePath(), "-Dquarkus.mcp.server.stdio.enabled=true");
		pb.redirectErrorStream(false);
		Process process = pb.start();
		try {
			OutputStream stdin = process.getOutputStream();
			InputStream stdout = process.getInputStream();

			send(stdin,
					"{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}," + "\"clientInfo\":{\"name\":\"sanity-test\",\"version\":\"1.0\"}},\"jsonrpc\":\"2.0\",\"id\":0}");
			String init = readResponse(stdout, 15_000);
			assertNotNull(init, "No initialize response");
			assertTrue(init.contains("rpg-mcp-server"), init);

			send(stdin, "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}");
			send(stdin, "{\"method\":\"tools/list\",\"params\":{},\"jsonrpc\":\"2.0\",\"id\":1}");
			String tools = readResponse(stdout, 15_000);
			assertNotNull(tools, "No tools/list response");
			assertTrue(tools.contains("get_server_state"), tools);
			assertTrue(tools.contains("commit_campaign_setup"), tools);

			send(stdin,
					"{\"method\":\"tools/call\",\"params\":{\"name\":\"get_server_state\",\"arguments\":{}},\"jsonrpc\":\"2.0\",\"id\":2}");
			String state = readResponse(stdout, 20_000);
			assertNotNull(state, "No get_server_state response");
			assertTrue(state.contains("CAMPAIGN_SELECTION"), state);
			assertTrue(state.contains("srd5e:5.2.1"), state);
		} finally {
			process.destroyForcibly();
			process.waitFor();
		}
	}

	private static void send(OutputStream stdin, String json) throws Exception {
		stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
		stdin.flush();
	}

	private static String readResponse(InputStream stdout, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		StringBuilder sb = new StringBuilder();
		while (System.currentTimeMillis() < deadline) {
			if (stdout.available() > 0) {
				int b = stdout.read();
				if (b == -1) {
					break;
				}
				if (b == '\n') {
					String line = sb.toString().trim();
					if (!line.isEmpty()) {
						return line;
					}
					sb.setLength(0);
				} else {
					sb.append((char) b);
				}
			} else {
				Thread.sleep(50);
			}
		}
		String remaining = sb.toString().trim();
		return remaining.isEmpty() ? null : remaining;
	}
}
