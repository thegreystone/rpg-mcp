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
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** World-event reference groups can be patched independently. */
class WorldEventReferenceTest {

	@Test
	void affectedOnlyPatchPreservesExistingLocations() throws Exception {
		try (Engine engine = engine(tempDb("world-event-affected-patch"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String origin = (String) m(engine.sessions().party(campaign, "SUMMARY").get("location")).get("ref");
			Map<String, Object> materialized = engine.world().materialize(op(), campaign, origin,
					map("connections", List.of(map("to", map("name", "Old Watchtower", "kind", "SITE")))), "GM");
			String destination = (String) list(materialized.get("new_connections")).getFirst().get("to");
			String witness = (String) engine.runtime()
					.materialize(op(), campaign, "Commoner", "Aldren's messenger", null, null, null, null, false)
					.get("character");
			Map<String, Object> before = engine.narrative().upsert(op(), campaign, "WORLD_EVENT", null,
					map("title", "The watchtower beacon", "locations", List.of(origin), "affected",
							List.of("character:1"), "channels", List.of("RUMOR"), "description",
							"A messenger brings word of a distant beacon."),
					"DIRECTOR");
			String event = (String) before.get("ref");
			Map<String, Object> after = engine.narrative().upsert(op(), campaign, "WORLD_EVENT", event,
					map("affected", List.of(witness)), "DIRECTOR");
			assertNotEquals(origin, destination);
			assertEquals(List.of(origin, "character:1"), before.get("affected"));
			assertEquals(List.of(origin, witness), after.get("affected"),
					"updating affected characters must retain the event's existing locations");
			assertTrue(available(engine, campaign, origin, event));
			assertFalse(available(engine, campaign, destination, event),
					"updating affected characters must not make a local rumor available everywhere");
		}
	}

	@Test
	void locationOnlyPatchPreservesAffectedCharactersAndMovesTheRumor() throws Exception {
		try (Engine engine = engine(tempDb("world-event-location-patch"))) {
			Fixture fixture = fixture(engine);
			String event = create(fixture);
			assertTrue(available(engine, fixture.campaign(), fixture.origin(), event));
			assertFalse(available(engine, fixture.campaign(), fixture.destination(), event));

			Map<String, Object> patched = patch(fixture, event, map("locations", List.of(fixture.destination())));

			assertEquals(List.of(fixture.destination(), "character:1"), patched.get("affected"));
			assertFalse(available(engine, fixture.campaign(), fixture.origin(), event));
			assertTrue(available(engine, fixture.campaign(), fixture.destination(), event));
		}
	}

	@Test
	void explicitEmptyListsClearOnlyTheirOwnReferenceGroup() throws Exception {
		try (Engine engine = engine(tempDb("world-event-clear-refs"))) {
			Fixture fixture = fixture(engine);
			String event = create(fixture);
			assertEquals(List.of(fixture.origin()), patch(fixture, event, map("affected", List.of())).get("affected"));
			patch(fixture, event, map("affected", List.of("character:1")));
			assertEquals(List.of("character:1"), patch(fixture, event, map("locations", List.of())).get("affected"));
			assertTrue(available(engine, fixture.campaign(), fixture.destination(), event),
					"an event with no location restrictions can surface elsewhere");
			patch(fixture, event, map("locations", List.of(fixture.origin())));
			assertEquals(List.of(),
					patch(fixture, event, map("locations", List.of(), "affected", List.of())).get("affected"));
		}
	}

	@Test
	void omittedAndNullFieldsPreserveBothGroupsDuringOtherEdits() throws Exception {
		try (Engine engine = engine(tempDb("world-event-null-refs"))) {
			Fixture fixture = fixture(engine);
			String event = create(fixture);
			List<String> expected = List.of(fixture.origin(), "character:1");
			for (Map<String, Object> fields : List.of(map("description", "The beacon is still burning."),
					map("locations", null), map("affected", null), map("locations", null, "affected", null))) {
				assertEquals(expected, patch(fixture, event, fields).get("affected"), fields.toString());
			}
			assertEquals("The beacon is still burning.", read(engine, fixture.campaign(), event).get("description"));
			assertEquals(expected,
					patch(fixture, event, map("locations", null, "affected", List.of("character:1"))).get("affected"));
			assertEquals(expected, patch(fixture, event, map("locations", List.of(fixture.origin()), "affected", null))
					.get("affected"));
		}
	}

	@Test
	void suppliedGroupsReplaceAndDeduplicateTheirReferencesInStableOrder() throws Exception {
		try (Engine engine = engine(tempDb("world-event-both-refs"))) {
			Fixture fixture = fixture(engine);
			String event = create(fixture);

			Map<String, Object> patched = patch(fixture, event, map("locations",
					List.of(fixture.destination(), " " + fixture.destination() + " "), "affected",
					List.of(fixture.witness(), "character:1", " " + fixture.witness() + " ", fixture.destination())));

			assertEquals(List.of(fixture.destination(), fixture.witness(), "character:1"), patched.get("affected"));
			assertEquals(patched.get("affected"), read(engine, fixture.campaign(), event).get("affected"));
		}
	}

	@Test
	void mixedAffectedListsRemainUsableForLegacyCreationAndRoundTrips() throws Exception {
		try (Engine engine = engine(tempDb("world-event-mixed-refs"))) {
			Fixture fixture = fixture(engine);
			Map<String, Object> created = engine.narrative()
					.upsert(op(), fixture.campaign(), "WORLD_EVENT", null,
							map("title", "Legacy mixed references", "affected",
									List.of("character:1", fixture.origin(), "character:1", fixture.origin())),
							"DIRECTOR");
			String event = (String) created.get("ref");
			assertEquals(List.of(fixture.origin(), "character:1"), created.get("affected"));
			assertEquals(created.get("affected"),
					patch(fixture, event, map("affected", created.get("affected"))).get("affected"));
			assertEquals(List.of(fixture.origin(), fixture.destination(), fixture.witness()),
					patch(fixture, event, map("affected", List.of(fixture.destination(), fixture.witness())))
							.get("affected"),
					"location references in affected add to the retained location scope");
			assertEquals(List.of(fixture.witness()),
					patch(fixture, event, map("locations", List.of())).get("affected"));
		}
	}

	@Test
	void invalidReferenceListsFailAtomicallyWithoutChangingTheEvent() throws Exception {
		try (Engine engine = engine(tempDb("world-event-invalid-refs"))) {
			Fixture fixture = fixture(engine);
			String event = create(fixture);
			Map<String, Object> before = read(engine, fixture.campaign(), event);
			for (Map<String, Object> fields : List.of(map("locations", fixture.origin()),
					map("affected", "character:1"), map("locations", Arrays.asList((Object) null)),
					map("affected", Arrays.asList((Object) null)),
					map("locations", List.of(fixture.destination()), "affected", Arrays.asList((Object) null)),
					map("locations", Arrays.asList((Object) null), "affected", List.of(fixture.witness())))) {
				fields.put("description", "This description must roll back.");
				RpgException invalid = assertThrows(RpgException.class, () -> patch(fixture, event, fields));
				assertEquals(ErrorCode.INVALID_ARGUMENT, invalid.code(), fields.toString());
				assertEquals(before, read(engine, fixture.campaign(), event), fields.toString());
			}
		}
	}

	@Test
	void replayCheckpointAndReopenPreserveTheAuthoritativeReferenceGroups() throws Exception {
		Path database = tempDb("world-event-reference-restore");
		String campaign;
		String event;
		String origin;
		try (Engine engine = engine(database)) {
			Fixture fixture = fixture(engine);
			campaign = fixture.campaign();
			origin = fixture.origin();
			event = create(fixture);
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "before the messenger")
					.get("checkpoint");
			String operation = op();
			Map<String, Object> fields = map("affected", List.of(fixture.witness()));
			Map<String, Object> patched = engine.narrative().upsert(operation, campaign, "WORLD_EVENT", event, fields,
					"DIRECTOR");
			assertEquals(List.of(origin, fixture.witness()), patched.get("affected"));
			patch(fixture, event, map("locations", List.of(fixture.destination()), "affected", List.of("character:1")));
			Map<String, Object> replayed = engine.narrative().upsert(operation, campaign, "WORLD_EVENT", event, fields,
					"DIRECTOR");
			assertEquals(Boolean.TRUE, replayed.get("replayed"));
			assertEquals(patched.get("affected"), replayed.get("affected"));
			assertEquals(List.of(fixture.destination(), "character:1"), read(engine, campaign, event).get("affected"),
					"replaying an old update must not overwrite the newer scope");
			engine.checkpoints().restore(op(), campaign, checkpoint, "restore the original world event");
			assertEquals(List.of(origin, "character:1"), read(engine, campaign, event).get("affected"));
		}
		try (Engine reopened = engine(database)) {
			reopened.sessions().bootstrap(op(), campaign, null);
			assertEquals(List.of(origin, "character:1"), read(reopened, campaign, event).get("affected"));
			Map<String, Object> patched = reopened.narrative().upsert(op(), campaign, "WORLD_EVENT", event,
					map("affected", List.of()), "DIRECTOR");
			assertEquals(List.of(origin), patched.get("affected"), "the restored campaign remains playable");
		}
	}

	private record Fixture(Engine engine, String campaign, String origin, String destination, String witness) {
	}

	private static Fixture fixture(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		String origin = (String) m(engine.sessions().party(campaign, "SUMMARY").get("location")).get("ref");
		Map<String, Object> materialized = engine.world().materialize(op(), campaign, origin,
				map("connections", List.of(map("to", map("name", "Old Watchtower", "kind", "SITE")))), "GM");
		String destination = (String) list(materialized.get("new_connections")).getFirst().get("to");
		String witness = (String) engine.runtime()
				.materialize(op(), campaign, "Commoner", "Aldren's messenger", null, null, null, null, false)
				.get("character");
		return new Fixture(engine, campaign, origin, destination, witness);
	}

	private static String create(Fixture fixture) {
		return (String) fixture.engine().narrative()
				.upsert(op(), fixture.campaign(), "WORLD_EVENT", null,
						map("title", "The watchtower beacon", "locations", List.of(fixture.origin()), "affected",
								List.of("character:1"), "channels", List.of("RUMOR"), "description",
								"A messenger brings word of a distant beacon."),
						"DIRECTOR")
				.get("ref");
	}

	private static Map<String, Object> patch(Fixture fixture, String event, Map<String, Object> changes) {
		return fixture.engine().narrative().upsert(op(), fixture.campaign(), "WORLD_EVENT", event, changes, "DIRECTOR");
	}

	private static Map<String, Object> read(Engine engine, String campaign, String event) {
		return list(engine.narrative().directorContext(campaign, "CAMPAIGN_REVIEW").get("world_events")).stream()
				.filter(candidate -> event.equals(candidate.get("ref"))).findFirst().orElseThrow();
	}

	private static boolean available(Engine engine, String campaign, String location, String event) {
		return list(engine.narrative().diegetic(campaign, "RUMOR", location, 10).get("items")).stream()
				.anyMatch(candidate -> event.equals(candidate.get("ref")));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}
}
