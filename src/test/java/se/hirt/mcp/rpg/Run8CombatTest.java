package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run8CombatTest {

	@Test
	void unconsciousPartyKeepsOwnCoordinatesWhileEnemyAndLaterMemberCoordinatesStayPrivate() throws Exception {
		var file = tempDb("run8-unconscious-own-positions");
		String campaign, encounter;
		Map<String, Object> saved;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			String ally = npc(e, campaign, "Oddny"), friend = npc(e, campaign, "Unaffiliated ally");
			String seen = npc(e, campaign, "Previously seen enemy"), hidden = npc(e, campaign, "NEVER_SEEN_ENEMY");
			e.party().updateMembership(op(), campaign, ally, "JOIN", "Oddny is a party member.");
			((ScriptedRollService) e.roller()).queue(20, 1);
			var started = e.encounters().start(op(), campaign,
					map("party", List.of("character:1", ally, friend), "foes", List.of(seen, hidden)), null, null, null,
					null, null,
					map("scene",
							map("name", "Divided hall", "bounds_ft", List.of(0, 0, 50, 20), "rooms",
									List.of(map("key", "west", "rect_ft", List.of(0, 0, 30, 20)),
											map("key", "east", "rect_ft", List.of(30, 0, 20, 20)))),
							"placements", List.of(position("character:1", 5, 5), position(ally, 10, 5),
									position(friend, 15, 5), position(seen, 20, 5), position(hidden, 40, 5))));
			encounter = started.get("encounter").toString();
			String board = SceneService.map(started.get("grid")).get("map_ref").toString();
			for (String character : List.of("character:1", ally, friend))
				e.runtime().applyRuntimeChange(op(), campaign, character,
						map("kind", "ADD_CONDITION", "condition", "UNCONSCIOUS", "reason", "A resolved sleep effect."));
			e.encounters().end(op(), campaign, encounter, "OTHER", "Every party-side actor is unconscious.");
			var player = e.encounters().encounterState(campaign, encounter, 100, "PLAYER");
			assertEquals(List.of(5.0, 5.0), person(player, "character:1").get("position_ft"));
			assertEquals(List.of(10.0, 5.0), person(player, ally).get("move_origin_ft"));
			assertEquals(board, person(player, ally).get("map_ref"));
			assertEquals(5, ((Number) person(player, ally).get("footprint_ft")).intValue());
			assertNoPosition(person(player, friend));
			assertNoPosition(person(player, seen));
			assertFalse(Json.write(player).contains(hidden));
			assertFalse(Json.write(player).contains("NEVER_SEEN_ENEMY"));
			saved = historicalData(player);
			String checkpoint = e.checkpoints().create(op(), campaign, "The unconscious aftermath").get("checkpoint")
					.toString();
			for (String character : List.of("character:1", ally, friend))
				e.runtime().applyRuntimeChange(op(), campaign, character,
						map("kind", "REMOVE_CONDITION", "condition", "UNCONSCIOUS", "reason", "Later recovery."));
			e.party().updateMembership(op(), campaign, ally, "LEAVE", "A later departure does not rewrite history.");
			for (String character : List.of(friend, seen, hidden))
				e.party().updateMembership(op(), campaign, character, "JOIN",
						"A later alliance does not reveal old positions.");
			e.runtime().transferControl(op(), campaign, friend, "Control changes after the ended fight.");
			String home = e.db().read(
					tx -> Ref.of(Ref.LOCATION, tx.get("character", Ref.id(friend, Ref.CHARACTER)).lng("location_id")));
			e.rest().override(op(), campaign, "SET_LOCATION", friend, map("location", home, "clear_map_position", true),
					"Release a same-place figure for a later adjudicated placement.", null);
			e.scenes().place(op(), campaign, board, List.of(position(friend, 25, 15)));
			assertEquals(saved, historicalData(e.encounters().encounterState(campaign, encounter, 100, "PLAYER")));
			e.checkpoints().restore(op(), campaign, checkpoint, "Return to the saved aftermath.");
			assertEquals(saved, historicalData(e.encounters().encounterState(campaign, encounter, 100, "PLAYER")));
		}
		try (Engine e = engine(file)) {
			assertEquals(saved, historicalData(e.encounters().encounterState(campaign, encounter, 100, "PLAYER")));
		}
	}

	@Test
	void dyingControlledActorAndDeathEndedCompanionKeepPositionsButEarlierFormerMemberDoesNot() throws Exception {
		try (Engine e = engine(tempDb("run8-fallen-owned-positions"))) {
			String campaign = ready(e), ally = npc(e, campaign, "Fallen companion"),
					former = npc(e, campaign, "Former companion");
			String foe = npc(e, campaign, "Unobserved at completion");
			for (String character : List.of(ally, former))
				e.party().updateMembership(op(), campaign, character, "JOIN", "An established companion.");
			e.party().updateMembership(op(), campaign, former, "LEAVE", "Left before the fight.");
			((ScriptedRollService) e.roller()).queue(20, 1);
			String encounter = e.encounters()
					.start(op(), campaign, map("party", List.of("character:1", ally, former), "foes", List.of(foe)),
							null, null, null, null, null,
							map("placements",
									List.of(position("character:1", 5, 5), position(ally, 10, 5),
											position(former, 15, 5), position(foe, 25, 5))))
					.get("encounter").toString();
			e.runtime().applyRuntimeChange(op(), campaign, former,
					map("kind", "ADD_CONDITION", "condition", "UNCONSCIOUS", "reason", "A resolved sleep effect."));
			e.runtime().applyRuntimeChange(op(), campaign, ally,
					map("kind", "DAMAGE", "amount", 1000, "reason", "A resolved fatal collapse."));
			long hp = e.db().read(tx -> tx.get("character", 1).lng("current_hp"));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "DAMAGE", "amount", hp, "reason",
					"The final resolved wound drops the controlled actor."));
			e.encounters().end(op(), campaign, encounter, "OTHER", "The party has fallen.");
			assertEquals("DEAD",
					e.db().read(tx -> tx.queryOne(
							"SELECT state FROM party_membership WHERE character_id=? ORDER BY id DESC LIMIT 1",
							Ref.id(ally, Ref.CHARACTER)).orElseThrow().str("state")));
			var player = e.encounters().encounterState(campaign, encounter, 100, "PLAYER");
			assertEquals(List.of(5.0, 5.0), person(player, "character:1").get("position_ft"));
			assertEquals(List.of(10.0, 5.0), person(player, ally).get("position_ft"));
			assertEquals("DEAD", person(player, ally).get("life_state"));
			assertNoPosition(person(player, former));
			assertNoPosition(person(player, foe));
			var saved = historicalData(player);
			e.rest().override(op(), campaign, "SET_LIFE_STATE", ally, map("life_state", "ALIVE", "hp", 1),
					"A later revival.", null);
			e.party().updateMembership(op(), campaign, former, "REJOIN", "A later reunion.");
			assertEquals(saved, historicalData(e.encounters().encounterState(campaign, encounter, 100, "PLAYER")));
		}
	}

	private static void assertNoPosition(Map<String, Object> person) {
		for (String key : List.of("position_ft", "move_origin_ft", "map_ref", "level", "footprint_ft"))
			assertFalse(person.containsKey(key), key + " must not be reconstructed from unseen current figures.");
	}

	private static Map<String, Object> historicalData(Map<String, Object> state) {
		var saved = new LinkedHashMap<>(state);
		saved.remove("meta");
		return saved;
	}

	private static Map<String, Object> person(Map<String, Object> state, String ref) {
		return SceneService.maps(state.get("participants")).stream().filter(row -> ref.equals(row.get("character")))
				.findFirst().orElseThrow();
	}

	private static Map<String, Object> position(String ref, int x, int y) {
		return map("character", ref, "x_ft", x, "y_ft", y);
	}

	private static String npc(Engine e, String campaign, String name) {
		return e.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false).get("character")
				.toString();
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}
}
