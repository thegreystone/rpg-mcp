package se.hirt.mcp.rpg;

import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.maps.PngCodec;
import se.hirt.mcp.rpg.protocol.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class MapToolDeliveryTest {
	@Test
	void mapAndLivingWorldParametersExplainTheirContracts() {
		for (Class<?> group : List.of(MapTools.class, LivingWorldTools.class))
			for (var method : group.getDeclaredMethods()) {
				if (!method.isAnnotationPresent(io.quarkiverse.mcp.server.Tool.class))
					continue;
				for (var parameter : method.getParameters()) {
					var argument = parameter.getAnnotation(io.quarkiverse.mcp.server.ToolArg.class);
					assertNotNull(argument, method.getName() + ": " + parameter.getName());
					assertFalse(argument.description().isBlank(), method.getName() + ": " + parameter.getName());
				}
			}
	}

	@Test
	void imageAndTextDeliveryKeepAssetBindingsAndIdempotency() throws Exception {
		Path db = tempDb("map-tools");
		try (Engine e = engine(db)) {
			String c = committedCampaign(e);
			e.sessions().bootstrap(op(), c, null);
			MapTools tools = new MapTools();
			tools.engine = e;
			Path source = db.getParent().resolve("floor.png");
			Files.write(source, PngCodec
					.encode(new PngCodec.Raster(2, 2, new int[] {0xff111111, 0xffeeeeee, 0xffeeeeee, 0xff111111})));
			String importOp = op();
			var five = result(tools.asset(importOp, c, source.toString(), Optional.of(5.0)));
			String first = (String) object(five.get("asset")).get("ref");
			var ten = result(tools.asset(op(), c, source.toString(), Optional.of(10.0)));
			String second = (String) object(ten.get("asset")).get("ref");
			assertNotEquals(first, second);
			Files.delete(source);
			assertEquals(true, result(tools.asset(importOp, c, source.toString(), Optional.of(5.0))).get("replayed"));
			var draft = e.maps().begin(op(), c, "SCENE", map("name", "Texture delivery fixture", "room_count", 1,
					"geometry", map("bounds_ft", List.of(0, 0, 20, 20)), "rooms",
					List.of(map("key", "floor", "rect_ft", List.of(0, 0, 20, 20), "material", first)), "actors",
					List.of(map("key", "lookout", "room", "floor", "creature_ref", "Bandit", "x_ft", 5, "y_ft", 5))));
			String scene = (String) e.maps().publish(op(), c, (String) draft.get("preparation"), null).get("map_ref");
			var before = e.db().snapshot(se.hirt.mcp.rpg.protocol.Ref.id(c, "campaign"));
			ToolResponse image = tools.render(c, scene, Optional.of("GM"), Optional.of(0),
					Optional.of(Map.of("max_pixels", 128)), Optional.of(true));
			assertFalse(image.isError(), image.toString());
			assertEquals(2, image.content().size());
			assertEquals("image/png", image.content().get(1).asImage().mimeType());
			byte[] bytes = Base64.getDecoder().decode(image.content().get(1).asImage().data());
			assertEquals(128, PngCodec.decode(bytes).width());
			var marker = object(((List<?>) result(image).get("markers")).getFirst());
			assertEquals("1", marker.get("marker"));
			assertEquals(List.of(5d, 5d),
					((List<?>) marker.get("move_origin_ft")).stream().map(n -> ((Number) n).doubleValue()).toList());
			assertEquals(5d, ((Number) marker.get("width_ft")).doubleValue());
			var otherLevel = result(tools.render(c, scene, Optional.of("GM"), Optional.of(0),
					Optional.of(Map.of("level", 1)), Optional.of(false)));
			assertEquals(List.of(), otherLevel.get("markers"),
					"Marker metadata follows the same level filter as the image.");
			var text = tools.render(c, scene, Optional.of("GM"), Optional.of(0), Optional.empty(), Optional.of(false));
			assertFalse(text.isError());
			assertEquals(1, text.content().size());
			assertTrue(String.valueOf(result(text).get("text")).contains("north is up"));
			assertEquals(before, e.db().snapshot(se.hirt.mcp.rpg.protocol.Ref.id(c, "campaign")),
					"Rendering must not mutate observations or canonical state.");
			assertEquals(5.0,
					e.db().read(tx -> ((Number) tx.get("map_asset", se.hirt.mcp.rpg.protocol.Ref.id(first, "asset"))
							.get("repeat_ft")).doubleValue()));
		}
	}

	private static Map<String, Object> result(ToolResponse response) {
		assertFalse(response.isError(), response.toString());
		return object(Json.readMap(response.firstContent().asText().text()).get("result"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return (Map<String, Object>) value;
	}
}
