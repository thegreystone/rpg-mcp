package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Real campaign actions exercise the contracts between spatial rules, persistence and combat. */
class GridEncounterTest {

	@Test
	void movementBudgetAndPositionsSurviveAnEngineRestart() throws Exception {
		Path database = tempDb("grid-budget-restart");
		String campaign, encounter, guard, mapRef;
		try (Engine engine = engine(database)) {
			campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			guard = materialize(engine, campaign, "Sentinel");
			((ScriptedRollService) engine.roller()).queue(20, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(guard)), null, null, null, null, null,
					map("placements", List.of(map("character", "character:1", "x_ft", 0, "y_ft", 0),
							map("character", guard, "x_ft", 60, "y_ft", 60))));
			assertEquals("GRID", started.get("spatial_model"));
			encounter = (String) started.get("encounter");
			mapRef = (String) m(started.get("grid")).get("map_ref");
			Map<String, Object> moved = engine.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "MOVE", "x_ft", 20, "y_ft", 0), false);
			assertEquals(20.0, moved.get("movement_cost_ft"));
			assertEquals(10.0, m(moved.get("movement")).get("remaining_ft"));
		}
		try (Engine engine = engine(database)) {
			Map<String, Object> state = engine.encounters().encounterState(campaign, encounter, 10);
			assertEquals(mapRef, m(state.get("grid")).get("map_ref"));
			assertEquals(List.of(20.0, 0.0), actor(state, "character:1").get("position_ft"));
			assertThrows(RpgException.class, () -> engine.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "MOVE", "x_ft", 35, "y_ft", 0), false));
			assertEquals(List.of(20.0, 0.0),
					actor(engine.encounters().encounterState(campaign, encounter, 5), "character:1")
							.get("position_ft"));
			Map<String, Object> dashed = engine.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "DASH", "x_ft", 45, "y_ft", 0), false);
			assertEquals(15.0, m(dashed.get("movement")).get("remaining_ft"));
		}
	}

	@Test
	void anOpportunityChoiceLeavesTheMoverAtTheReachBoundaryAcrossRestart() throws Exception {
		Path database = tempDb("grid-reaction-restart");
		String campaign, encounter, guard, transaction;
		try (Engine engine = engine(database)) {
			campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			guard = materialize(engine, campaign, "Lookout");
			((ScriptedRollService) engine.roller()).queue(20, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(guard)), null, null, null, null, null,
					map("npc_reactions", "ASK", "placements",
							List.of(map("character", "character:1", "x_ft", 25, "y_ft", 25),
									map("character", guard, "x_ft", 20, "y_ft", 25))));
			encounter = (String) started.get("encounter");
			Map<String, Object> move = engine.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "MOVE", "x_ft", 40, "y_ft", 25), false);
			assertTrue((Boolean) move.get("paused_for_reaction"));
			assertEquals(List.of(25.0, 25.0), actor(m(move.get("state")), "character:1").get("position_ft"));
			assertEquals(30.0, m(actor(m(move.get("state")), "character:1").get("movement")).get("remaining_ft"));
			transaction = (String) list(move.get("pending_choices")).getFirst().get("transaction");
		}
		try (Engine engine = engine(database)) {
			Map<String, Object> resolved = engine.encounters().resolveChoice(op(), campaign, transaction,
					map("option", "DECLINE"));
			assertEquals(List.of(40.0, 25.0), actor(m(resolved.get("state")), "character:1").get("position_ft"));
			assertEquals(15.0, m(actor(m(resolved.get("state")), "character:1").get("movement")).get("remaining_ft"));
			assertThrows(RpgException.class,
					() -> engine.encounters().resolveChoice(op(), campaign, transaction, map("option", "DECLINE")));
		}
	}

	@Test
	void aConeDerivesAllAffectedCreaturesIncludingAnAlly() throws Exception {
		try (Engine engine = engine(tempDb("grid-friendly-fire"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.spells().prepare(op(), campaign, "character:1", null, List.of("Burning Hands"));
			String ally = materialize(engine, campaign, "Ally"), enemy = materialize(engine, campaign, "Enemy"),
					outside = materialize(engine, campaign, "Outside");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 15, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of("character:1", ally), "foes", List.of(enemy, outside)), null, null, null, null,
					null,
					map("placements",
							List.of(map("character", "character:1", "x_ft", 0, "y_ft", 0),
									map("character", ally, "x_ft", 10, "y_ft", 5),
									map("character", enemy, "x_ft", 10, "y_ft", 0),
									map("character", outside, "x_ft", 10, "y_ft", 10))));
			dice.queue(20, 1, 1, 1, 1);
			Map<String, Object> cast = engine.encounters()
					.perform(op(), campaign, (String) started.get("encounter"), "character:1", map("kind", "CAST",
							"spell", "Burning Hands", "targets", List.of(enemy), "direction_ft", List.of(20, 2.5)),
							false);
			assertEquals(Set.of(ally, enemy),
					list(cast.get("targets")).stream().map(t -> (String) t.get("target")).collect(Collectors.toSet()));
			assertEquals(11, m(engine.characters().characterSheet(campaign, outside, "PLAY").get("hp")).get("current"));
			assertTrue(dice.exhausted(), "two saves and one shared damage roll");
		}
	}

	@Test
	void anUnseenRoomDoesNotLeakThroughInitiativeOrTurnMetadata() throws Exception {
		try (Engine engine = engine(tempDb("grid-hidden-initiative"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String preparation = (String) engine.maps().begin(op(), campaign, "SCENE",
					map("name", "Two guardrooms", "location_ref", currentLocation(engine, campaign), "room_count", 2,
							"geometry", map("bounds_ft", List.of(0, 0, 60, 30)), "rooms",
							List.of(map("key", "west", "rect_ft",
									List.of(0, 0, 30, 30)), map("key", "east", "rect_ft", List.of(30, 0, 30, 30))),
							"connections",
							List.of(map("key", "door", "from", "west", "to", "east", "state", "CLOSED", "from_ft",
									List.of(30, 10), "to_ft", List.of(30, 15))),
							"actors", List.of(map("key", "guard", "room", "east", "name", "The hidden sentinel",
									"creature_ref", "Bandit", "x_ft", 45, "y_ft", 10))))
					.get("preparation");
			Map<String, Object> published = engine.maps().publish(op(), campaign, preparation, null);
			String guard = (String) m(published.get("references")).get("actor:guard"),
					mapRef = (String) published.get("map_ref");
			engine.scenes().place(op(), campaign, mapRef,
					List.of(map("character", "character:1", "x_ft", 20, "y_ft", 10)));
			((ScriptedRollService) engine.roller()).queue(20, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(guard)), null, null, null, null, null,
					map("map_ref", mapRef));
			assertFalse(Json.write(started).contains("hidden sentinel"));
			assertEquals(1, list(started.get("initiative_rolls")).size());
			String encounter = (String) started.get("encounter");
			Map<String, Object> end = engine.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "END_TURN"), true);
			assertFalse(Json.write(end).contains("hidden sentinel"));
			assertNull(end.get("next_turn"));
			Map<String, Object> handoff = m(end.get("gm_handoff"));
			assertEquals("get_encounter_state", handoff.get("tool"));
			assertEquals("GM", m(handoff.get("arguments")).get("audience"));
			assertFalse(Json.write(handoff).contains(guard));
			assertTrue(Json.write(engine.encounters().encounterState(campaign, encounter, 10, "GM"))
					.contains("hidden sentinel"));
			Map<String, Object> player = engine.encounters().encounterState(campaign, encounter, 10, "PLAYER");
			assertNull(player.get("turn"));
			assertNotNull(player.get("gm_handoff"));
			Map<String, Object> gm = engine.encounters().encounterState(campaign, encounter, 10, "GM");
			assertEquals(guard, m(gm.get("turn")).get("character"));
			String gmActor = m(gm.get("turn")).get("character").toString();
			engine.scenes().sound(op(), campaign, mapRef, 22.5, 12.5, 0, 60, "Footsteps beyond the wall.");
			Map<String, Object> perception = engine.scenes().perception(campaign, mapRef, gmActor);
			assertEquals("SUSPICIOUS", m(perception.get("evidence")).get("awareness"));
			assertFalse(Json.write(perception.get("currently_seen")).contains("character:1"),
					"A sound cue does not grant the guard sight through a closed door.");
			Map<String, Object> moved = engine.encounters().perform(op(), campaign, encounter, gmActor,
					map("kind", "MOVE", "x_ft", 50, "y_ft", 10), false);
			assertEquals(false, moved.get("turn_advanced"));
			assertEquals(25.0, m(moved.get("movement")).get("remaining_ft"));
			assertFalse(Json.write(moved.get("state")).contains(guard));
			assertFalse(Json.write(moved.get("state")).contains("hidden sentinel"));
			Map<String, Object> movedGm = engine.encounters().encounterState(campaign, encounter, 10, "GM");
			assertEquals(List.of(50.0, 10.0), m(movedGm.get("turn")).get("position_ft"));
			assertTrue(Json.write(movedGm.get("recent_log")).contains("hidden sentinel"));
			RpgException unseenTarget = assertThrows(RpgException.class,
					() -> engine.encounters().perform(op(), campaign, encounter, gmActor,
							map("kind", "ATTACK", "attack", "Light Crossbow", "target", "character:1"), false));
			assertTrue(unseenTarget.getMessage().contains("no perceived or last-known target"),
					"The GM roster does not grant this actor an exact unseen target.");
			long currentHp = engine.db().read(tx -> tx.get("character", 1).lng("current_hp"));
			engine.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "DAMAGE", "amount",
					currentHp, "damage_type", "bludgeoning", "reason", "The party falls unconscious."));
			assertEquals(guard,
					m(engine.encounters().encounterState(campaign, encounter, 10, "GM").get("turn")).get("character"));
			((ScriptedRollService) engine.roller()).queue(10); // the dying party member's next death save
			Map<String, Object> dodged = engine.encounters().perform(op(), campaign, encounter, gmActor,
					map("kind", "DODGE"), true);
			assertEquals(true, dodged.get("turn_advanced"));
			assertNull(dodged.get("next_turn"));
			assertFalse(Json.write(dodged.get("state")).contains(guard));
			assertFalse(Json.write(dodged.get("state")).contains("hidden sentinel"));
			Map<String, Object> nextGm = engine.encounters().encounterState(campaign, encounter, 10, "GM");
			assertEquals(2L, nextGm.get("round"));
			assertEquals(guard, m(nextGm.get("turn")).get("character"));
			Map<String, Object> stillActing = engine.encounters().perform(op(), campaign, encounter,
					m(nextGm.get("turn")).get("character").toString(), map("kind", "MOVE", "x_ft", 45, "y_ft", 10),
					false);
			assertEquals(25.0, m(stillActing.get("movement")).get("remaining_ft"));
			Map<String, Object> after = engine.encounters().encounterState(campaign, encounter, 20, "PLAYER");
			assertFalse(Json.write(after).contains(guard));
			assertFalse(Json.write(after).contains("hidden sentinel"));
			assertNull(after.get("turn"));
			assertNotNull(after.get("gm_handoff"));
			assertEquals(false, after.get("awaiting_player_input"));
			assertTrue(((ScriptedRollService) engine.roller()).exhausted());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"The Masked Arcanist", "CAST_SHIELD", "transaction:1", "PLAYER"})
	void aPlayerShieldChoiceDoesNotDiscloseTheUnseenAttackerAndStillResolves(String attackerName) throws Exception {
		try (Engine engine = engine(tempDb("grid-hidden-shield-choice"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.spells().prepare(op(), campaign, "character:1", null, List.of("Shield"));
			String guard = materialize(engine, campaign, attackerName);
			engine.runtime().applyRuntimeChange(op(), campaign, guard, map("kind", "ADD_CONDITION", "condition",
					"INVISIBLE", "reason", "An established invisibility effect."));
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(1, 20, 19); // the invisible guard rolls initiative with Advantage
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(guard)), null, null, null, null, null,
					map("placements", List.of(map("character", "character:1", "x_ft", 5, "y_ft", 5),
							map("character", guard, "x_ft", 10, "y_ft", 5))));
			String encounter = started.get("encounter").toString();
			assertFalse(Json.write(started).contains(guard));
			Map<String, Object> gmTurn = engine.encounters().encounterState(campaign, encounter, 10, "GM");
			assertEquals(guard, m(gmTurn.get("turn")).get("character"));
			int ac = ((Number) m(engine.characters().characterSheet(campaign, "character:1", "PLAY").get("armor_class"))
					.get("value")).intValue();
			dice.queue(ac - 3, ac - 3); // a noncritical hit that Shield will prevent
			Map<String, Object> swing = engine.encounters().perform(op(), campaign, encounter,
					m(gmTurn.get("turn")).get("character").toString(),
					map("kind", "ATTACK", "attack", "Scimitar", "target", "character:1"), false);
			assertEquals(false, swing.get("resolved"));
			Map<String, Object> gm = engine.encounters().encounterState(campaign, encounter, 10, "GM");
			Map<String, Object> gmChoice = list(gm.get("pending_choices")).getFirst();
			assertTrue(gmChoice.get("prompt").toString().contains(attackerName));
			Map<String, Object> player = engine.encounters().encounterState(campaign, encounter, 10, "PLAYER");
			Map<String, Object> playerChoice = list(player.get("pending_choices")).getFirst();
			assertFalse(playerChoice.get("prompt").toString().contains(attackerName));
			assertFalse(Json.write(player).contains(guard));
			assertEquals("PLAYER", playerChoice.get("decision_controller"));
			assertEquals(gmChoice.get("transaction"), playerChoice.get("transaction"));
			assertEquals(gmChoice.get("options"), playerChoice.get("options"));
			assertEquals(true, player.get("awaiting_player_input"));
			Map<String, Object> resolved = engine.encounters().resolveChoice(op(), campaign,
					playerChoice.get("transaction").toString(), map("option", "CAST_SHIELD"));
			assertEquals(false, m(resolved.get("attack")).get("hit"));
			assertEquals("RUNNING", m(resolved.get("state")).get("status"));
			assertTrue(list(m(resolved.get("state")).get("pending_choices")).isEmpty());
			assertFalse(Json.write(resolved.get("state")).contains(guard));
			assertFalse(Json.write(m(resolved.get("state")).get("recent_log")).contains(attackerName));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void aFightCrossesLevelsThroughStairsAfterItsPendingReaction() throws Exception {
		try (Engine engine = engine(tempDb("grid-multilevel"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String preparation = (String) engine.maps().begin(op(), campaign, "SCENE",
					map("name", "Two levels", "location_ref", currentLocation(engine, campaign), "room_count", 2,
							"levels", 2, "geometry", map("bounds_ft", List.of(0, 0, 30, 30)), "rooms", List
									.of(map("key", "lower", "level", 0, "rect_ft", List.of(0, 0, 30, 30)),
											map("key", "upper", "level", 1, "rect_ft", List.of(0, 0, 30, 30))),
							"connections",
							List.of(map("key", "stairs", "kind", "STAIRS", "from", "lower", "to", "upper", "state",
									"OPEN", "from_position_ft", List.of(5, 10), "to_position_ft", List.of(5, 10))),
							"actors",
							List.of(map("key", "below", "room", "lower", "name", "Lower guard", "creature_ref",
									"Bandit", "x_ft", 10, "y_ft", 10),
									map("key", "above", "room", "upper", "name", "Upper guard", "creature_ref",
											"Bandit", "x_ft", 10, "y_ft", 10))))
					.get("preparation");
			Map<String, Object> published = engine.maps().publish(op(), campaign, preparation, null);
			Map<String, Object> references = m(published.get("references"));
			String below = (String) references.get("actor:below"), above = (String) references.get("actor:above"),
					mapRef = (String) published.get("map_ref");
			engine.scenes().place(op(), campaign, mapRef,
					List.of(map("character", "character:1", "x_ft", 5, "y_ft", 10, "level", 0)));
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(below, above)), null, null, null, null, null,
					map("map_ref", mapRef, "npc_reactions", "ASK"));
			String encounter = (String) started.get("encounter");
			assertFalse(Json.write(started).contains("Upper guard"));
			Map<String, Object> moving = engine.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "MOVE", "portal", "stairs"), false);
			assertTrue((Boolean) moving.get("paused_for_reaction"));
			assertEquals(0, actor(m(moving.get("state")), "character:1").get("level"));
			Map<String, Object> choice = list(moving.get("pending_choices")).getFirst();
			assertEquals(below, choice.get("chooser"));
			Map<String, Object> finished = engine.encounters().resolveChoice(op(), campaign,
					(String) choice.get("transaction"), map("option", "DECLINE"));
			Map<String, Object> state = m(finished.get("state"));
			assertEquals(encounter, state.get("encounter"));
			assertEquals(1L, state.get("round"));
			assertEquals(1, actor(state, "character:1").get("level"));
			assertEquals(20.0, m(actor(state, "character:1").get("movement")).get("remaining_ft"));
			assertEquals(above, actor(state, above).get("character"));
			dice.queue(19);
			Map<String, Object> hit = engine.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "ATTACK", "weapon", "unarmed", "target", above), false);
			assertEquals(Boolean.TRUE, hit.get("hit"));
			assertTrue(dice.exhausted());
		}
	}

	@Test
	void destroyingLowCoverChangesDefenseWithoutChangingLineOfSight() throws Exception {
		try (Engine engine = engine(tempDb("grid-low-cover"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String preparation = (String) engine.maps().begin(op(), campaign, "SCENE", map("name", "Table cover",
					"location_ref", currentLocation(engine, campaign), "room_count", 1, "geometry",
					map("bounds_ft", List.of(0, 0, 60, 30)), "rooms",
					List.of(map("key", "hall", "rect_ft", List.of(0, 0, 60, 30))), "objects",
					List.of(map("key", "table", "room", "hall", "name", "Low table", "x_ft", 30, "y_ft", 10, "width_ft",
							5, "height_ft", 5, "cover", "HALF", "blocks_sight", false)),
					"actors",
					List.of(map("key", "guard", "room", "hall", "creature_ref", "Bandit", "x_ft", 50, "y_ft", 10))))
					.get("preparation");
			Map<String, Object> published = engine.maps().publish(op(), campaign, preparation, null);
			String mapRef = (String) published.get("map_ref"),
					guard = (String) m(published.get("references")).get("actor:guard");
			engine.scenes().place(op(), campaign, mapRef,
					List.of(map("character", "character:1", "x_ft", 5, "y_ft", 10)));
			assertEquals(se.hirt.mcp.rpg.spatial.SpatialRules.Cover.HALF, engine.db()
					.read(tx -> SceneService.cover(tx, SceneService.scene(tx, Ref.id(campaign, "campaign"), mapRef),
							SceneService.actor(tx, Ref.id(campaign, "campaign"), "character:1"),
							SceneService.actor(tx, Ref.id(campaign, "campaign"), guard)).cover()));
			assertTrue(engine.db()
					.read(tx -> SceneService.canSee(tx, SceneService.scene(tx, Ref.id(campaign, "campaign"), mapRef),
							SceneService.actor(tx, Ref.id(campaign, "campaign"), "character:1"),
							SceneService.actor(tx, Ref.id(campaign, "campaign"), guard)))
					.booleanValue());
			engine.scenes().changeObject(op(), campaign, mapRef, "object:table", map("state", "DESTROYED"),
					"Resolved damage shatters the low table.");
			assertEquals(se.hirt.mcp.rpg.spatial.SpatialRules.Cover.NONE, engine.db()
					.read(tx -> SceneService.cover(tx, SceneService.scene(tx, Ref.id(campaign, "campaign"), mapRef),
							SceneService.actor(tx, Ref.id(campaign, "campaign"), "character:1"),
							SceneService.actor(tx, Ref.id(campaign, "campaign"), guard)).cover()));
		}
	}

	@Test
	void finiteReinforcementsJoinMidRoundWithoutResettingExistingTurnsOrPositions() throws Exception {
		try (Engine engine = engine(tempDb("grid-reinforcements"))) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String preparation = (String) engine.maps().begin(op(), campaign, "SCENE",
					map("name", "Patrol courtyard", "location_ref", currentLocation(engine, campaign), "room_count", 1,
							"geometry", map("bounds_ft", List.of(0, 0, 100, 100)), "rooms",
							List.of(map("key", "yard", "rect_ft", List.of(0, 0, 100, 100))), "actors",
							List.of(map("key", "first", "room", "yard", "creature_ref", "Bandit", "x_ft", 5, "y_ft", 5),
									map("key", "patrol-one", "room", "yard", "creature_ref", "Bandit", "x_ft", 70,
											"y_ft", 70),
									map("key", "patrol-two", "room", "yard", "creature_ref", "Bandit", "x_ft", 80,
											"y_ft", 70))))
					.get("preparation");
			Map<String, Object> published = engine.maps().publish(op(), campaign, preparation, null);
			Map<String, Object> refs = m(published.get("references"));
			String first = (String) refs.get("actor:first"), one = (String) refs.get("actor:patrol-one"),
					two = (String) refs.get("actor:patrol-two");
			String mapRef = (String) published.get("map_ref");
			engine.scenes().place(op(), campaign, mapRef,
					List.of(map("character", "character:1", "x_ft", 25, "y_ft", 25)));
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(15, 4);
			String encounter = (String) engine.encounters()
					.start(op(), campaign, map("party", List.of("character:1"), "foes", List.of(first)), null, null,
							null, null, null, map("map_ref", mapRef))
					.get("encounter");
			engine.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "MOVE", "x_ft", 35, "y_ft", 25), false);
			engine.encounters().perform(op(), campaign, encounter, "character:1", map("kind", "DODGE"), false);
			Map<String, Object> before = engine.encounters().encounterState(campaign, encounter, 5, "GM");
			dice.queue(20);
			String operation = op();
			Map<String, Object> joined = engine.encounters().addParticipants(operation, campaign, encounter,
					Map.of("foes", List.of(one, two)));
			Map<String, Object> after = engine.encounters().encounterState(campaign, encounter, 5, "GM");
			assertEquals(1L, after.get("round"));
			assertEquals("character:1", m(after.get("turn")).get("character"));
			assertEquals(actor(before, "character:1").get("initiative"), actor(after, "character:1").get("initiative"));
			assertEquals(actor(before, first).get("initiative"), actor(after, first).get("initiative"));
			assertEquals(List.of(35.0, 25.0), actor(after, "character:1").get("position_ft"));
			assertEquals(Boolean.FALSE, actor(after, "character:1").get("action_available"));
			assertEquals(20.0, m(actor(after, "character:1").get("movement")).get("remaining_ft"));
			assertEquals(List.of(70.0, 70.0), actor(after, one).get("position_ft"));
			assertEquals(List.of(80.0, 70.0), actor(after, two).get("position_ft"));
			assertEquals(actor(after, one).get("initiative"), actor(after, two).get("initiative"));
			assertTrue(dice.exhausted(),
					"identical arriving guards share one new initiative roll; existing actors never reroll");
			assertEquals(Boolean.TRUE,
					engine.encounters()
							.addParticipants(operation, campaign, encounter, Map.of("foes", List.of(one, two)))
							.get("replayed"));
			assertThrows(RpgException.class,
					() -> engine.encounters().addParticipants(op(), campaign, encounter, Map.of("foes", List.of(one))));
			Map<String, Object> next = engine.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "END_TURN"), true);
			assertEquals(first, m(next.get("next_turn")).get("character"),
					"higher-initiative arrivals wait until the next round");
			Map<String, Object> roundTwo = engine.encounters().perform(op(), campaign, encounter, first,
					map("kind", "END_TURN"), true);
			assertEquals(2L, roundTwo.get("round"));
			assertEquals(one, m(roundTwo.get("next_turn")).get("character"));
			assertEquals(Boolean.TRUE, joined.get("applied"));
		}
	}

	@Test
	void legacyConversionPreservesInitiativeResourcesClockAndKnownMovement() throws Exception {
		for (boolean specified : List.of(false, true))
			try (Engine engine = engine(tempDb("grid-legacy-conversion"))) {
				String campaign = committedCampaign(engine);
				engine.sessions().bootstrap(op(), campaign, null);
				String guard = materialize(engine, campaign, "Legacy guard");
				ScriptedRollService dice = (ScriptedRollService) engine.roller();
				dice.queue(20, 1);
				Map<String, Object> started = engine.encounters().start(op(), campaign,
						map("party", List.of("character:1"), "foes", List.of(guard)), null, null, null, null, null);
				String encounter = (String) started.get("encounter");
				long cid = Ref.id(campaign, Ref.CAMPAIGN), eid = Ref.id(encounter, Ref.ENCOUNTER);
				List<Map<String, Object>> before = engine.db().read(tx -> tx.query(
						"SELECT character_id,initiative,initiative_tiebreak FROM encounter_participant WHERE encounter_id=? ORDER BY id",
						eid).stream().map(se.hirt.mcp.rpg.persistence.Row::asMap).toList());
				long seconds = engine.db().read(tx -> se.hirt.mcp.rpg.session.GameTime.currentSeconds(tx, cid));
				engine.db().mutate(
						se.hirt.mcp.rpg.persistence.Database.Mutation.of("legacy_grid_fixture", cid, op(), "GM", null),
						tx -> {
							var row = tx.get("encounter", eid);
							tx.update("encounter", eid, map("spatial_model", "ZONES", "environment_json",
									Json.write(map("description", "Legacy battle"))));
							var participant = tx.queryOne(
									"SELECT * FROM encounter_participant WHERE encounter_id=? AND character_id=1", eid)
									.orElseThrow();
							String turn = se.hirt.mcp.rpg.rules.TurnEconomy.turnKey(row);
							tx.update("encounter_participant", participant.id(),
									map("once_per_turn_json", Json.write(map(se.hirt.mcp.rpg.rules.TurnEconomy.ACTION,
											turn, se.hirt.mcp.rpg.rules.TurnEconomy.MOVED, turn))));
							return Map.of();
						});
				assertEquals("ZONES", engine.encounters().encounterState(campaign, encounter, 5).get("spatial_model"),
						"read-only resume does not convert");
				String operation = op();
				Map<String, Object> converted = engine.encounters().convertToGrid(operation, campaign, encounter,
						specified ? map("remaining_movement_ft", map("character:1", 10)) : null);
				Map<String, Object> hero = actor(m(converted.get("state")), "character:1");
				assertEquals("GRID", m(converted.get("state")).get("spatial_model"));
				assertEquals(Boolean.FALSE, hero.get("action_available"));
				assertEquals(specified ? 10.0 : 0.0, m(hero.get("movement")).get("remaining_ft"),
						"unknown legacy movement never grants a fresh budget");
				assertEquals(before, engine.db().read(tx -> tx.query(
						"SELECT character_id,initiative,initiative_tiebreak FROM encounter_participant WHERE encounter_id=? ORDER BY id",
						eid).stream().map(se.hirt.mcp.rpg.persistence.Row::asMap).toList()));
				assertEquals(seconds,
						engine.db().read(tx -> se.hirt.mcp.rpg.session.GameTime.currentSeconds(tx, cid)).longValue());
				assertTrue(dice.exhausted(), "conversion does not reroll initiative");
				assertEquals(Boolean.TRUE,
						engine.encounters()
								.convertToGrid(operation, campaign, encounter,
										specified ? map("remaining_movement_ft", map("character:1", 10)) : null)
								.get("replayed"));
			}
	}

	private static String currentLocation(Engine engine, String campaign) {
		return engine.db().read(tx -> Ref.of(Ref.LOCATION,
				tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).lng("current_location_id")));
	}

	private static String materialize(Engine engine, String campaign, String name) {
		return (String) engine.runtime().materialize(op(), campaign, "Bandit", name, null, null, null, null, false)
				.get("character");
	}

	private static Map<String, Object> actor(Map<String, Object> state, String character) {
		return list(state.get("participants")).stream().filter(p -> character.equals(p.get("character"))).findFirst()
				.orElseThrow();
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
