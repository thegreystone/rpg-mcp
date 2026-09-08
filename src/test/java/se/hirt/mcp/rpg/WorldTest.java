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
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Semantic maps, travel, narrative state, the Director and diegetic delivery (MCP_PROTOCOL.md §25.7, §25.8;
 * DOMAIN_MODEL.md I-37..I-41).
 */
class WorldTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	@Test
	void locationsTravelAndDiegeticDelivery() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("world"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bellhaven = "location:1";

			// Materialize the opening location with features, a secret, children and a road to a new semantic node.
			Map<String, Object> town = engine.world().materialize(op(), campaign, bellhaven,
					map("description", "Slate roofs tumbling toward a silver river.", "features",
							List.of(map("name", "South Gate", "description", "Two bored watchmen."),
									map("name", "The Copper Kettle", "description", "An inn.")), "secrets",
							List.of(map("name", "Smugglers' cellar", "description", "Under the Kettle.")), "children",
							List.of(map("name", "Harbour District", "kind", "DISTRICT")), "connections",
							List.of(map("to", map("name", "Ruined Tower", "kind", "SITE"), "kind", "ROAD",
									"travel_minutes", 180, "distance_miles", 9))), "GM");
			assertEquals("MATERIALIZED", town.get("materialization"));
			assertEquals(3, list(town.get("features")).size());
			assertEquals("GM_ONLY", list(town.get("features")).get(2).get("visibility"));
			assertEquals(1, list(town.get("new_children")).size());
			String tower = (String) list(town.get("new_connections")).get(0).get("to");
			assertEquals("location:3", tower);
			// Committed detail cannot be contradicted (I-41); it can be extended.
			RpgException again = assertThrows(RpgException.class,
					() -> engine.world().materialize(op(), campaign, bellhaven, map("description", "x"), "GM"));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, again.code());
			RpgException redefine = assertThrows(RpgException.class, () -> engine.world()
					.materialize(op(), campaign, bellhaven, map("extend", true, "features",
							List.of(map("name", "South Gate", "description", "different"))), "GM"));
			assertEquals(ErrorCode.VALIDATION_FAILED, redefine.code());
			engine.world().materialize(op(), campaign, bellhaven,
					map("extend", true, "features", List.of(map("name", "Market Square", "description", "Loud."))),
					"GM");

			// Travel: the road exists, so the party moves and the clock advances 3 hours; the tower is still semantic.
			String before = (String) m(engine.sessions().party(campaign, "SUMMARY").get("game_time")).get("instant");
			Map<String, Object> moved = engine.world()
					.move(op(), campaign, tower, null, false, null, "following the letter");
			assertEquals(180L, moved.get("travel_minutes"));
			assertEquals(1, list(moved.get("route")).size());
			assertEquals("Ruined Tower", m(moved.get("arrived_at")).get("name"));
			assertFalse(((List<?>) m(moved.get("meta")).get("warnings")).isEmpty(), "semantic destination warns");
			assertNotEquals(before, m(moved.get("game_time")).get("instant"));
			assertEquals("Ruined Tower",
					m(m(engine.sessions().party(campaign, "SUMMARY").get("location"))).get("name"));
			// No route to an unconnected place; an authorized route creates one.
			String cave = (String) engine.world()
					.materialize(op(), campaign, null, map("name", "Sea Cave", "kind", "SITE"), "GM").get("ref");
			RpgException noRoute = assertThrows(RpgException.class,
					() -> engine.world().move(op(), campaign, cave, null, false, null, null));
			assertEquals(ErrorCode.VALIDATION_FAILED, noRoute.code());
			engine.world().move(op(), campaign, cave, null, true, 45, "a fisherman rowed them");
			Map<String, Object> back = engine.world().move(op(), campaign, tower, null, false, null, null);
			assertEquals(45L, back.get("travel_minutes"), "the authorized route is remembered");
			// Multi-hop route: cave → tower → Bellhaven.
			Map<String, Object> home = engine.world().move(op(), campaign, bellhaven, null, false, null, null);
			assertEquals(1, list(home.get("route")).size());
			Map<String, Object> here = engine.narrative().context(campaign, "LOCATION", null, null, null, null);
			assertEquals("Bellhaven - South Gate", here.get("name"));
			assertEquals(4, list(here.get("features")).size());
			assertEquals(2, list(here.get("connections")).size(), "road to the tower and the district passage");

			// World events with channels and places; diegetic queries only read what is committed and already happened.
			engine.narrative().upsert(op(), campaign, "WORLD_EVENT", null,
					map("title", "THIRD NORTH ROAD CARAVAN LOST", "description",
							"Survivors describe burned wagons without ordinary fire.", "channels",
							List.of("NEWSPAPER", "RUMOR"), "locations", List.of(bellhaven), "game_time",
							"Day 1, 06:00"), "DIRECTOR");
			engine.narrative().upsert(op(), campaign, "WORLD_EVENT", null,
					map("title", "Refugees from the north", "channels", List.of("REFUGEES"), "game_time",
							"Day 1, 12:00"), "DIRECTOR");
			engine.narrative().upsert(op(), campaign, "WORLD_EVENT", null,
					map("title", "Festival cancelled", "channels", List.of("TOWN_CRIER"), "locations", List.of(cave),
							"game_time", "Day 1, 12:00"), "DIRECTOR");
			engine.narrative().upsert(op(), campaign, "WORLD_EVENT", null,
					map("title", "Not yet happened", "channels", List.of("NEWSPAPER"), "game_time", "Day 30, 12:00"),
					"DIRECTOR");
			Map<String, Object> paper = engine.narrative().diegetic(campaign, "NEWSPAPER", null, 10);
			List<Map<String, Object>> headlines = list(paper.get("items"));
			assertEquals(1, headlines.size(), headlines.toString());
			assertEquals("THIRD NORTH ROAD CARAVAN LOST", headlines.get(0).get("title"));
			Map<String, Object> any = engine.narrative().diegetic(campaign, "ANY", null, 10);
			assertEquals(2, list(any.get("items")).size(),
					"global refugees + local caravan; not the cave-only crier, not the future");
			// In the Harbour District (a child of Bellhaven) the town's news is still available.
			assertEquals(1, list(engine.narrative().diegetic(campaign, "RUMOR", "location:2", 10).get("items")).size());

			// Quests and story beats write ledger events on transitions.
			String quest = (String) engine.narrative().upsert(op(), campaign, "QUEST", null,
					map("title", "Find Aldren's contact", "objective", "Reach the Copper Kettle.", "rewards",
							"unknown"), "GM").get("ref");
			engine.narrative().upsert(op(), campaign, "QUEST", quest, map("status", "ACCEPTED"), "GM");
			engine.narrative().upsert(op(), campaign, "QUEST", quest, map("status", "COMPLETED"), "GM");
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, assertThrows(RpgException.class, () -> engine.narrative()
					.upsert(op(), campaign, "QUEST", quest, map("status", "FAILED"), "GM")).code());
			List<Map<String, Object>> questEvents = list(engine.ledger().queryTimeline(campaign, null, null, null,
					List.of("QUEST_OFFERED", "QUEST_ACCEPTED", "QUEST_COMPLETED"), null, true, 10).get("events"));
			assertEquals(3, questEvents.size());
			// NPC agendas are GM-only and show on FULL sheets.
			String mara = (String) engine.runtime()
					.materialize(op(), campaign, "Guard", "Mara", null, null, null, null, false).get("character");
			engine.narrative().upsert(op(), campaign, "NPC_AGENDA", mara,
					map("goals", List.of("find her brother"), "secret", "she stole the notebook"), "GM");
			assertEquals("GM_ONLY",
					m(engine.characters().characterSheet(campaign, mara, "FULL").get("agenda")).get("visibility"));
			assertNull(engine.characters().characterSheet(campaign, mara, "PLAY").get("agenda"));
			assertEquals("Mara",
					m(engine.narrative().context(campaign, "CHARACTER", mara, null, null, null).get("character")).get(
							"name"));
		}
	}

	@Test
	void directorPlansAndAdaptiveCompanionIntroduction() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("director"))) {
			// Initial Director material authored during setup becomes canonical at commit.
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"CHECKPOINT", "party", "SURPRISE_ME"));
			String pc = (String) engine.characters().createDraft(op(), campaign,
					map("name", "Richard", "species", "Human", "class", "Sorcerer", "ability_scores",
							map("CHA", 15, "CON", 14, "DEX", 13, "INT", 12, "WIS", 10, "STR", 8), "skills",
							List.of("Deception", "Persuasion"), "background", "Criminal", "background_ability_scores",
							map("CON", 2, "INT", 1), "species_skill", "Insight", "origin_feat",
							map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")),
							"personality", "Confident."), true).get("character");
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().updateSetup(op(), campaign, null, map("adventure",
					map("premise", "An ancient boundary is failing.", "background_truth", "Someone accelerates it.",
							"opening_location", map("name", "Bellhaven", "kind", "SETTLEMENT"), "immediate_goal",
							"Find Aldren's contact.", "locations",
							List.of(map("name", "North Road", "kind", "REGION", "travel_minutes", 240),
									map("name", "Avarra", "kind", "REGION", "connected_to", List.of("North Road"),
											"travel_minutes", 600)), "seeds",
							List.of(map("kind", "COMPANION_INTRO", "intention",
											"Introduce Mara, a rogue, through the caravan survivor.", "archetype", "rogue"),
									map("kind", "PRESSURE", "intention", "The court faction quietly buys artifacts.")),
							"story_beats",
							List.of(map("title", "Meet the survivor at the riverside tavern", "state", "AVAILABLE")),
							"factions",
							List.of(map("name", "Avarran Court", "goals", List.of("stability"), "standing", 0)))));
			Map<String, Object> committed = engine.campaigns().commitSetup(op(), campaign, null);
			Map<String, Object> initial = m(committed.get("initial_narrative_state"));
			assertEquals(2, list(initial.get("locations")).size());
			assertEquals(2, list(initial.get("seeds")).size());
			assertEquals(1, list(initial.get("factions")).size());
			engine.sessions().bootstrap(op(), campaign, null);
			// The authored regions are connected: Bellhaven → North Road → Avarra.
			Map<String, Object> trip = engine.world().move(op(), campaign, "location:3", null, false, null, "north");
			assertEquals(2, list(trip.get("route")).size());
			assertEquals(840L, trip.get("travel_minutes"));

			// Director context is DIRECTOR_ONLY and lists the intentions; player agency is protected (I-37).
			Map<String, Object> ctx = engine.narrative().directorContext(campaign, "CAMPAIGN_REVIEW");
			assertEquals("DIRECTOR_ONLY", ctx.get("visibility"));
			assertEquals(1, list(ctx.get("companion_intentions")).size());
			assertNull(ctx.get("last_director_review"));
			String seed = (String) list(ctx.get("companion_intentions")).get(0).get("ref");
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class, () -> engine.narrative()
					.commitDirectorChanges(op(), campaign,
							List.of(map("kind", "STORY_SEED", "intention", "x", "player_must", "accept the quest")),
							null)).code());
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class, () -> engine.narrative()
					.commitDirectorChanges(op(), campaign,
							List.of(map("kind", "COMPANION_INTRO", "intention", "y", "relationship_outcome",
									"romance")), null)).code());

			// 25.7: the tavern plan became impossible; supersede it while preserving the intention.
			Map<String, Object> review = engine.narrative().commitDirectorChanges(op(), campaign,
					List.of(map("kind", "STORY_BEAT", "ref", "story_beat:2", "state", "BLOCKED", "reason",
									"the player skipped the tavern"), map("kind", "COMPANION_INTRO", "intention",
									"Mara tries to steal the survivor's notebook wherever the player finds it.", "supersedes",
									seed),
							map("kind", "STORY_SEED", "ref", seed, "state", "SUPERSEDED", "superseded_by", "seed:3"),
							map("kind", "WORLD_EVENT", "title", "Grain prices rise", "channels",
									List.of("PRICES", "MERCHANT")),
							map("kind", "PACING_INTENT", "intention", "Favor dialogue before the next fight.")),
					"The player went north instead of the tavern.");
			assertEquals(5, list(review.get("applied")).size());
			Map<String, Object> after = engine.narrative().directorContext(campaign, null);
			assertNotNull(after.get("last_director_review"));
			assertEquals(1, list(after.get("invalidated_plans")).size());
			List<Map<String, Object>> intentions = list(after.get("companion_intentions"));
			assertEquals(2, intentions.size(), "the superseded intention keeps its history (I-36)");
			assertEquals("seed:3",
					intentions.stream().filter(s -> "SUPERSEDED".equals(s.get("state"))).findFirst().orElseThrow()
							.get("superseded_by"));
			// An empty review is valid and still recorded.
			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "nothing to change");
			assertEquals(2, list(engine.ledger()
					.queryTimeline(campaign, null, null, null, List.of("DIRECTOR_REVIEW"), null, true, 10)
					.get("events")).size());
			// Director-only material never reaches the bootstrap context.
			Map<String, Object> boot = engine.sessions().bootstrap(op(), campaign, null);
			assertFalse(boot.toString().contains("steal the survivor's notebook"));
			assertEquals(1, ((List<?>) boot.get("director_seeds")).size() >= 1 ? 1 : 0, "seed references only");
			// The materialized companion closes the seed.
			String mara = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", "Mara", null, "dry", null, null, false).get("character");
			Map<String, Object> closed = engine.narrative()
					.upsert(op(), campaign, "STORY_SEED", "seed:3", map("materialized_character", mara), "DIRECTOR");
			assertEquals("MATERIALIZED", closed.get("state"));
		}
	}

	@Test
	void travelEncountersAreSuggestedFromTheGround() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("travel-encounters"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String bellhaven = "location:1";
			// A day's road through forest to a wild place, and a short hop inside the town walls.
			Map<String, Object> town = engine.world().materialize(op(), campaign, bellhaven,
					map("description", "A town.", "tags", List.of("town"), "connections", List.of(
							map("to", map("name", "Deep Wood", "kind", "SITE", "tags", List.of("forest")), "kind",
									"PATH", "travel_minutes", 480),
							map("to", map("name", "Harbour", "kind", "DISTRICT", "tags", List.of("town", "quay")),
									"kind", "STREET", "travel_minutes", 300))), "GM");
			String wood = (String) list(town.get("new_connections")).get(0).get("to");
			String harbourRef = (String) list(town.get("new_connections")).get(1).get("to");

			// Two four-hour blocks: the first d6 misses, the second hits; then the table pick.
			dice.queue(4, 1, 1);
			Map<String, Object> trip = engine.world().move(op(), campaign, wood, null, false, null, null);
			List<?> consequences = (List<?>) trip.get("consequences");
			assertEquals(1, consequences.size(), "one suggestion");
			Map<String, Object> suggestion = m(consequences.get(0));
			assertEquals("TRAVEL_ENCOUNTER_SUGGESTED", suggestion.get("kind"));
			assertEquals("forest", suggestion.get("terrain"));
			assertEquals(8L, suggestion.get("at_hour"));
			Map<String, Object> creature = m(suggestion.get("creature"));
			assertTrue(((String) creature.get("id")).startsWith("srd5e:creature/"));
			assertTrue(((Number) suggestion.get("count")).intValue() >= 1);
			assertTrue(((Number) suggestion.get("xp_budget")).intValue() > 0);
			assertTrue(((List<?>) m(trip.get("director_trigger")).get("reasons")).contains(
					"TRAVEL_ENCOUNTER_SUGGESTED"));
			assertTrue(engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM encounter")) == 0,
					"a suggestion writes nothing");

			// Back to town, then a long walk inside the walls: safe ground never rolls.
			dice.queue(1, 1, 1);
			engine.world().move(op(), campaign, bellhaven, null, false, null, null);
			Map<String, Object> harbour = engine.world().move(op(), campaign, harbourRef, null, false, null, null);
			assertTrue(((List<?>) harbour.get("consequences")).isEmpty(), "town streets are safe ground");
			assertFalse(dice.exhausted(), "no die was rolled for the street");
		}
	}
}
