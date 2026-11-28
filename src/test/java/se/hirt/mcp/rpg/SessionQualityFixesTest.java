/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class SessionQualityFixesTest {

	@TempDir
	Path tempDir;

	@Test
	void bootstrapEnforcesCharTargetAcrossFullPayloadIncludingWorldMapsAndMeta() throws Exception {
		try (Engine engine = engine(tempDb("bootstrap-budget"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			// Build a large multi-chapter campaign that would otherwise blow past the 48,000-char default target.
			String longText = "Stone walls along the river road and banners over Greyfall Manor. ".repeat(45);
			for (int ch = 0; ch < 6; ch++) {
				for (int i = 0; i < 4; i++) {
					engine.ledger().record(op(), campaign, "PLOT_EVENT",
							"Chapter " + ch + " event " + i + ": " + longText.substring(0, 140), List.of(pc),
							i == 0 ? "CRITICAL" : "MAJOR", "PARTY_KNOWN", "GM", null, null, longText, null);
				}
				engine.chronicle().write(op(), campaign, "CHAPTER", "Chapter " + ch,
						"Chapter " + ch + " prose: " + longText.substring(0, 2200), null);
			}
			engine.chronicle().write(op(), campaign, "SYNOPSIS", "The story so far",
					"Long rolling synopsis: " + longText.repeat(4).substring(0, 9000), null);
			for (int ch = 6; ch < 12; ch++) {
				engine.ledger().record(op(), campaign, "PLOT_EVENT", "Post-synopsis event " + ch, List.of(pc),
						"CRITICAL", "PARTY_KNOWN", "GM", null, null, longText, null);
				engine.chronicle().write(op(), campaign, "CHAPTER", "Chapter " + ch,
						"Post-synopsis chapter " + ch + ": " + longText.substring(0, 2200), null);
			}
			for (int i = 0; i < 12; i++) {
				engine.narrative()
						.upsert(op(), campaign, "STORY_BEAT", null, map("title", "Beat " + i, "state", "AVAILABLE",
								"visibility", "GM_ONLY", "payload", map("gm_brief", "Beat " + i + " " + longText)),
								"GM");
				engine.ledger().record(op(), campaign, "PLOT_EVENT",
						"Uncovered event " + i + ": " + longText.substring(0, 140), List.of(pc),
						i < 4 ? "CRITICAL" : "MAJOR", "PARTY_KNOWN", "GM", null, null, longText, null);
			}
			for (int i = 0; i < 6; i++) {
				String ally = (String) engine.runtime()
						.materialize(op(), campaign, "Guard", "Companion " + i, null, null, null, null, false)
						.get("character");
				engine.party().updateMembership(op(), campaign, ally, "JOIN", "joined");
				engine.characters().updateCharacter(op(), campaign, ally, null,
						map("appearance", longText, "biography", map("wants", List.of(
								map("note", "First long want for companion " + i + ": " + longText.substring(0, 400)),
								map("note", "Second long want for companion " + i + ": " + longText.substring(0, 400)),
								map("note",
										"Third long want for companion " + i + ": " + longText.substring(0, 400))))));
			}

			Map<String, Object> boot = engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> budget = m(boot.get("budget"));
			int target = ((Number) budget.get("chars_target")).intValue();
			int reported = ((Number) budget.get("chars_used")).intValue();
			int actual = Json.write(boot).length();
			assertEquals(48_000, target);
			assertEquals(actual, reported, "chars_used must include world_maps, session and meta");
			assertTrue(actual <= target, "bootstrap exceeded chars_target: " + actual + " > " + target);

			// Now ask for a tighter budget that forces enforceBudget to trim sections down to fit.
			Map<String, Object> tight = engine.sessions().bootstrap(op(), campaign, 8_000);
			Map<String, Object> tightBudget = m(tight.get("budget"));
			int tightTarget = ((Number) tightBudget.get("chars_target")).intValue();
			int tightReported = ((Number) tightBudget.get("chars_used")).intValue();
			int tightActual = Json.write(tight).length();
			assertEquals(24_000, tightTarget);
			assertEquals(tightActual, tightReported, "chars_used must match final serialized size after trimming");
			assertTrue(tightActual <= tightTarget,
					"tight bootstrap exceeded chars_target: " + tightActual + " > " + tightTarget);
			assertFalse(listOfStrings(tightBudget.get("trimmed_sections")).isEmpty());
		}
	}

	@Test
	void membershipMutationReturnsCompactRosterWithoutMemberWants() throws Exception {
		try (Engine engine = engine(tempDb("membership-compact"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String mara = (String) engine.runtime()
					.materialize(op(), campaign, "Guard", "Mara", null, null, null, null, false).get("character");
			engine.characters().updateCharacter(op(), campaign, mara, null,
					map("appearance", "Tall with an iron lantern.", "biography",
							map("wants", List.of(map("note", "Find the lost ledger at Wexbridge.")))));

			Map<String, Object> joined = engine.party().updateMembership(op(), campaign, mara, "JOIN", "joined");
			List<Map<String, Object>> roster = list(joined.get("party"));
			assertEquals(2, roster.size());
			Map<String, Object> maraEntry = roster.stream().filter(r -> "Mara".equals(r.get("name"))).findFirst()
					.orElseThrow();
			assertEquals("ACTIVE", maraEntry.get("membership"));
			assertFalse(maraEntry.containsKey("wants"), "character:MEMBERSHIP must not dump every member's wants");
			assertFalse(maraEntry.containsKey("appearance_brief"));
			assertFalse(maraEntry.containsKey("sheet_gaps"));
		}
	}

	@Test
	void queryMemoriesRanksFocusMatchesAboveUnrelatedCriticalStopWordHits() throws Exception {
		try (Engine engine = engine(tempDb("memory-relevance"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			// Many CRITICAL events containing common English stop words ("the", "and", "from") but no ferry/bridge.
			for (int i = 0; i < 6; i++) {
				engine.ledger().record(op(), campaign, "PLOT_EVENT",
						"The council met and swore the oath from the tower (" + i + ")", List.of(pc), "CRITICAL",
						"PARTY_KNOWN", "GM", null, null, "The lords debated taxes and winter stores for the keep.",
						null);
			}
			// A NOTABLE event that actually matches the focus keywords.
			engine.ledger().record(op(), campaign, "EXPLORATION", "Crossed the ruined ferry at Gant's Bend",
					List.of(pc), "NOTABLE", "PARTY_KNOWN", "GM", null, null,
					"The old ferryman's rope still hung across the south bank.", null);
			// A MAJOR event matching only one keyword ("bend").
			engine.ledger().record(op(), campaign, "EXPLORATION", "Followed the chalk ridge around a bend", List.of(pc),
					"MAJOR", "PARTY_KNOWN", "GM", null, null, "Quiet sheep country.", null);

			Map<String, Object> res = engine.ledger().queryMemories(campaign, null, null,
					"the ruined ferry at the bend", null, 3);
			List<Map<String, Object>> events = list(res.get("events"));
			assertFalse(events.isEmpty());
			assertTrue(events.getFirst().get("summary").toString().contains("ruined ferry"),
					"Expected the multi-keyword ferry match first, got: " + events);
			assertTrue(events.stream().noneMatch(e -> e.get("summary").toString().contains("council met")),
					"Unrelated CRITICAL events matching only stop words should not appear: " + events);
		}
	}

	@Test
	void movePartyAllowsTravelersInDifferentUnlockedRoomsOfSameBuildingAndStillBlocksLockedRoom() throws Exception {
		try (Engine engine = engine(tempDb("move-same-building"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String manor = location(engine, "character:1");
			String wexbridge = (String) engine
					.world().materialize(op(), campaign, null, map("name", "Wexbridge", "kind", "SETTLEMENT",
							"connections", List.of(map("to", manor, "kind", "ROAD", "travel_minutes", 40))), "GM")
					.get("ref");
			String hall = (String) engine.world()
					.materialize(op(), campaign, null, map("name", "Great Hall", "kind", "AREA", "parent", manor), "GM")
					.get("ref");
			String kitchen = (String) engine.world()
					.materialize(op(), campaign, null, map("name", "Kitchen", "kind", "AREA", "parent", manor), "GM")
					.get("ref");
			String ally = (String) engine.runtime()
					.materialize(op(), campaign, "Guard", "Mira", null, null, null, kitchen, false).get("character");
			engine.party().updateMembership(op(), campaign, ally, "JOIN", "companion");
			engine.rest().override(op(), campaign, "SET_LOCATION", "character:1", map("location", hall),
					"PC is in the hall while Mira is in the kitchen.", null);

			// Explicit characters in different rooms of the same building can leave together.
			Map<String, Object> moved = engine.world().move(op(), campaign, wexbridge, List.of("character:1", ally),
					false, null, "Leave the manor together for Wexbridge.");
			assertEquals("COMPLETED", moved.get("status"));
			assertEquals(wexbridge, location(engine, "character:1"));
			assertEquals(wexbridge, location(engine, ally));

			// Now test a mapped site with a locked interior door: must still refuse when one traveler is trapped.
			var prep = engine.maps().begin(op(), campaign, "SCENE",
					map("name", "Wexbridge Keep", "location_ref", wexbridge, "geometry",
							map("bounds_ft", List.of(0, 0, 40, 20)), "rooms",
							List.of(map("key", "entry", "role", "ENTRY", "rect_ft",
									List.of(0, 0, 20, 20)), map("key", "vault", "rect_ft", List.of(20, 0, 20, 20))),
							"connections",
							List.of(map("key", "vault-door", "from", "entry", "to", "vault", "kind", "DOOR", "state",
									"LOCKED", "locked", true, "travel_minutes", 1, "from_ft", List.of(20, 5), "to_ft",
									List.of(20, 10)))));
			var pub = engine.maps().publish(op(), campaign, prep.get("preparation").toString(), null);
			String entryRoom = m(pub.get("references")).get("room:entry").toString();
			String vaultRoom = m(pub.get("references")).get("room:vault").toString();
			engine.rest().override(op(), campaign, "SET_LOCATION", "character:1",
					map("location", entryRoom, "clear_map_position", true), "PC at entry.", null);
			engine.rest().override(op(), campaign, "SET_LOCATION", ally,
					map("location", vaultRoom, "clear_map_position", true), "Mira trapped in locked vault.", null);

			RpgException locked = assertThrows(RpgException.class, () -> engine.world().move(op(), campaign, manor,
					List.of("character:1", ally), false, null, "Cannot pull Mira through locked vault door."));
			assertTrue(locked.getMessage().contains("open exit from"), locked.getMessage());
			assertEquals("vault-door", locked.details().get("key"));
		}
	}

	@Test
	void orphanOwnerFilesArePrunedAlongsideOldLogs() throws Exception {
		String orphanLog = "rpg-mcp-server-" + UUID.randomUUID() + ".log";
		Path orphanMarker = tempDir.resolve(orphanLog + ".owner");
		Files.writeString(orphanMarker, "1000000000");
		RuntimeLogRetention.prune(tempDir, "rpg-mcp-server-" + UUID.randomUUID() + ".log", 16, 100_000);
		assertFalse(Files.exists(orphanMarker), "Orphaned .owner marker for dead process should be removed");
	}

	@Test
	void mapPreparationReviseAndContextSummaryOmitFullProposalUntilFullIsRequested() throws Exception {
		try (Engine engine = engine(tempDb("map-tools-compact-prep"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			MapTools tools = new MapTools();
			tools.engine = engine;
			var begun = toolResult(tools.mapPreparation("BEGIN", campaign, op(),
					map("kind", "SCENE", "proposal",
							map("name", "Courtyard", "geometry", map("bounds_ft", List.of(0, 0, 30, 30)), "rooms",
									List.of(map("key", "yard", "rect_ft", List.of(0, 0, 30, 30)))))));
			String prep = begun.get("preparation").toString();
			var revised = toolResult(tools.mapPreparation("REVISE", campaign, op(),
					map("preparation", prep, "changes", map("illumination", "DIM"))));
			assertFalse(revised.containsKey("proposal"), "REVISE should return compact summary, not full proposal");
			assertEquals("SUMMARY", revised.get("detail"));
			assertEquals(1, m(m(revised.get("proposal_summary")).get("counts")).get("rooms"));

			var summaryCtx = toolResult(
					tools.mapPlay("CONTEXT", campaign, null, map("reference", prep, "audience", "GM")));
			assertFalse(summaryCtx.containsKey("proposal"));
			assertEquals("SUMMARY", summaryCtx.get("detail"));

			var fullCtx = toolResult(tools.mapPlay("CONTEXT", campaign, null,
					map("reference", prep, "audience", "GM", "detail", "FULL")));
			assertTrue(fullCtx.containsKey("proposal"), "detail: FULL should still return full proposal");
		}
	}

	private static String location(Engine engine, String character) {
		return engine.db().read(
				tx -> Ref.of(Ref.LOCATION, tx.get("character", Ref.id(character, Ref.CHARACTER)).lng("location_id")));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> toolResult(io.quarkiverse.mcp.server.ToolResponse response) {
		assertFalse(response.isError(), "Unexpected tool error: " + response.content());
		String text = ((io.quarkiverse.mcp.server.TextContent) response.content().getFirst()).text();
		return (Map<String, Object>) Json.readMap(text).get("result");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<String> listOfStrings(Object o) {
		return o == null ? List.of() : (List<String>) o;
	}
}
