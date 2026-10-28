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
import java.util.List;
import java.util.Map;
import se.hirt.mcp.rpg.protocol.Json;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity test for the native image binary: starts the executable against a temporary data
 * directory, performs the MCP handshake over STDIO, lists tools, and calls get_server_state (which
 * exercises SQLite, migrations and the seed import inside the native image).
 * <p>
 * Skipped unless {@code native.image.path} is set, e.g.
 * {@code mvn test-compile failsafe:integration-test failsafe:verify -Dnative.image.path=target/<binary>.exe}.
 */
class NativeImageSanityIT {

	@Test
	@EnabledIfSystemProperty(named = "native.image.path", matches = ".+")
	void nativeBinaryRespondsToMcp() throws Exception {
		Path binary = Path.of(System.getProperty("native.image.path"));
		assertTrue(Files.exists(binary), "Native binary not found at: " + binary);
		Path dataDir = Files.createTempDirectory("rpg-mcp-native-it");

		ProcessBuilder pb = new ProcessBuilder(binary.toAbsolutePath().toString(),
				"-Drpg.data-dir=" + dataDir.toAbsolutePath(),
				"-Dquarkus.log.file.path=" + dataDir.resolve("native.log"), "-Dquarkus.mcp.server.stdio.enabled=true");
		pb.redirectErrorStream(false);
		Process process = pb.start();
		try {
			OutputStream stdin = process.getOutputStream();
			InputStream stdout = process.getInputStream();

			send(stdin, "{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
					+ "\"clientInfo\":{\"name\":\"sanity-test\",\"version\":\"1.0\"}},\"jsonrpc\":\"2.0\",\"id\":0}");
			String init = readResponse(stdout, 15_000);
			assertNotNull(init, "No initialize response");
			assertTrue(init.contains("rpg-mcp-server"), init);

			send(stdin, "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}");
			send(stdin, "{\"method\":\"tools/list\",\"params\":{},\"jsonrpc\":\"2.0\",\"id\":1}");
			String tools = readResponse(stdout, 15_000);
			assertNotNull(tools, "No tools/list response");
			assertTrue(tools.contains("get_server_state"), tools);
			assertTrue(tools.contains("commit_campaign_setup"), tools);
			assertTrue(tools.contains("render_map"), tools);
			assertTrue(tools.contains("define_patrol"), tools);
			assertTrue(tools.contains("set_tactical_control"), tools);
			assertTrue(!cast(Json.readMap(tools).get("result")).containsKey("nextCursor"),
					"Desktop hosts must discover the complete tool surface on the first page");
			var describedTools = new java.util.HashSet<String>();
			for (Class<?> group : List.of(MapTools.class, LivingWorldTools.class))
				for (var method : group.getDeclaredMethods()) {
					var annotation = method.getAnnotation(io.quarkiverse.mcp.server.Tool.class);
					if (annotation != null)
						describedTools.add(annotation.name());
				}
			for (Object definition : (List<?>) cast(Json.readMap(tools).get("result")).get("tools")) {
				var tool = cast(definition);
				if (describedTools.contains(tool.get("name")))
					for (var parameter : cast(cast(tool.get("inputSchema")).get("properties")).entrySet())
						assertTrue(
								!String.valueOf(cast(parameter.getValue()).getOrDefault("description", "")).isBlank(),
								tool.get("name") + ": " + parameter.getKey() + " needs usable parameter documentation");
			}

			send(stdin,
					"{\"method\":\"tools/call\",\"params\":{\"name\":\"get_server_state\",\"arguments\":{}},\"jsonrpc\":\"2.0\",\"id\":2}");
			String state = readResponse(stdout, 20_000);
			assertNotNull(state, "No get_server_state response");
			assertTrue(state.contains("CAMPAIGN_SELECTION"), state);
			assertTrue(state.contains("srd5e:5.2.1"), state);

			// Exercise the actual MCP image/text contract and renderer in the native executable.
			Map<String, Object> created = call(stdin, stdout, 3, "create_campaign",
					Map.of("operation_id", "native-map-campaign", "title", "Native map playtest"));
			String campaign = (String) created.get("campaign");
			Map<String, Object> control = call(stdin, stdout, 32, "set_tactical_control",
					Map.of("operation_id", "native-companion-control", "campaign", campaign, "controller", "PLAYER",
							"scope", "UNTIL_RELEASED"));
			assertTrue("UNTIL_RELEASED".equals(control.get("scope")), control.toString());
			Path texture = dataDir.resolve("native-floor.png");
			Files.write(texture, se.hirt.mcp.rpg.maps.PngCodec.encode(new se.hirt.mcp.rpg.maps.PngCodec.Raster(2, 2,
					new int[] {0xff999988, 0xff777766, 0xff777766, 0xff999988})));
			Map<String, Object> imported = call(stdin, stdout, 30, "import_map_asset", Map.of("operation_id",
					"native-map-texture", "campaign", campaign, "source_path", texture.toString(), "repeat_ft", 5));
			String asset = (String) cast(imported.get("asset")).get("ref");
			Files.delete(texture);
			Map<String, Object> proposal = Map.of("name", "Native two-room fixture", "room_count", 2, "geometry",
					Map.of("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
					List.of(Map.of("key", "hall", "rect_ft", List.of(0, 0, 20, 20), "material", asset), Map.of("key",
							"vault", "polygon_ft",
							List.of(List.of(20, 0), List.of(40, 0), List.of(40, 20), List.of(20, 20)), "holes_ft",
							List.of(List.of(List.of(30, 10), List.of(35, 10), List.of(35, 15), List.of(30, 15))))),
					"connections",
					List.of(Map.of("key", "door", "from", "hall", "to", "vault", "from_ft", List.of(20, 5), "to_ft",
							List.of(20, 10)),
							Map.of("key", "second-door", "from", "hall", "to", "vault", "from_ft", List.of(20, 15),
									"to_ft", List.of(20, 20))),
					"actors",
					List.of(Map.of("key", "lookout", "room", "hall", "creature_ref", "Bandit", "x_ft", 5, "y_ft", 5)));
			Map<String, Object> draft = call(stdin, stdout, 4, "begin_map_preparation", Map.of("operation_id",
					"native-map-draft", "campaign", campaign, "kind", "SCENE", "proposal", proposal));
			Map<String, Object> published = call(stdin, stdout, 5, "publish_map_preparation", Map.of("operation_id",
					"native-map-publish", "campaign", campaign, "preparation", draft.get("preparation")));
			String map = (String) published.get("map_ref");
			Map<String, Object> geometryContext = call(stdin, stdout, 33, "get_map_context",
					Map.of("campaign", campaign, "reference", map, "audience", "GM"));
			Map<String, Object> corrected = call(stdin, stdout, 34, "correct_map_geometry",
					Map.of("operation_id", "native-door-correction", "campaign", campaign, "map", map,
							"expected_revision", geometryContext.get("map_revision"), "patch",
							Map.of("doors",
									List.of(Map.of("key", "door", "from_ft", List.of(20, 0), "to_ft", List.of(20, 5)))),
							"reason", "Correct the office doorway to the southern grid cell without republishing."));
			assertTrue(Boolean.TRUE.equals(corrected.get("corrected")), corrected.toString());
			assertTrue(map.equals(corrected.get("map")), corrected.toString());
			String lookout = (String) cast(published.get("references")).get("actor:lookout");
			Map<String, Object> preview = call(stdin, stdout, 31, "preview_spatial_action", Map.of("campaign", campaign,
					"map", map, "character", lookout, "x_ft", 10, "y_ft", 5, "audience", "GM"));
			assertTrue("FOUND".equals(preview.get("status")), preview.toString());
			assertTrue(((Number) preview.get("cost_ft")).doubleValue() == 5, preview.toString());
			Map<String, Object> arguments = Map.of("campaign", campaign, "map", map, "audience", "GM", "options",
					Map.of("max_pixels", 128));
			send(stdin, Json.write(Map.of("method", "tools/call", "params",
					Map.of("name", "render_map", "arguments", arguments), "jsonrpc", "2.0", "id", 6)));
			Map<String, Object> response = Json.readMap(readResponse(stdout, 20000));
			Map<String, Object> rendered = cast(response.get("result"));
			assertTrue(!Boolean.TRUE.equals(rendered.get("isError")), response.toString());
			List<?> content = (List<?>) rendered.get("content");
			assertTrue(content.stream().anyMatch(v -> "text".equals(cast(v).get("type"))),
					"Text alternative is mandatory");
			Map<String, Object> png = content.stream().map(NativeImageSanityIT::cast)
					.filter(v -> "image".equals(v.get("type"))).findFirst().orElseThrow();
			byte[] bytes = java.util.Base64.getDecoder().decode((String) png.get("data"));
			assertTrue(bytes.length > 8 && bytes[0] == (byte) 137 && bytes[1] == 80,
					"Native tool must return actual PNG content");
			Path evidence = Path.of("target/map-playtests/06-native-map.png");
			Files.createDirectories(evidence.getParent());
			Files.write(evidence, bytes);
			var textOnly = new java.util.LinkedHashMap<>(arguments);
			textOnly.put("include_image", false);
			Map<String, Object> text = call(stdin, stdout, 7, "render_map", textOnly);
			assertTrue(String.valueOf(text.get("text")).contains("north is up"), text.toString());

			process.destroyForcibly();
			process.waitFor();
			process = pb.start();
			stdin = process.getOutputStream();
			stdout = process.getInputStream();
			send(stdin,
					Json.write(Map.of(
							"method", "initialize", "params", Map.of("protocolVersion", "2025-11-25", "capabilities",
									Map.of(), "clientInfo", Map.of("name", "reload-test", "version", "1.0")),
							"jsonrpc", "2.0", "id", 0)));
			assertNotNull(readResponse(stdout, 15000), "No initialize response after native restart");
			send(stdin, "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}");
			Map<String, Object> reloaded = call(stdin, stdout, 8, "render_map", textOnly);
			assertTrue(text.get("text").equals(reloaded.get("text")), reloaded.toString());
			Map<String, Object> party = call(stdin, stdout, 9, "get_party", Map.of("campaign", campaign));
			assertTrue(Json.write(party.get("tactical_control")).contains("UNTIL_RELEASED"), party.toString());
		} finally {
			process.destroyForcibly();
			process.waitFor();
		}
	}

	private static Map<String, Object> call(
		OutputStream stdin, InputStream stdout, int id, String tool, Map<String, Object> arguments) throws Exception {
		send(stdin, Json.write(Map.of("method", "tools/call", "params", Map.of("name", tool, "arguments", arguments),
				"jsonrpc", "2.0", "id", id)));
		String line = readResponse(stdout, 20000);
		assertNotNull(line, "No response to " + tool);
		Map<String, Object> response = Json.readMap(line);
		Map<String, Object> result = cast(response.get("result"));
		assertTrue(!Boolean.TRUE.equals(result.get("isError")), tool + ": " + line);
		Map<String, Object> text = cast(((List<?>) result.get("content")).getFirst());
		return cast(Json.readMap((String) text.get("text")).get("result"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Object value) {
		return (Map<String, Object>) value;
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
