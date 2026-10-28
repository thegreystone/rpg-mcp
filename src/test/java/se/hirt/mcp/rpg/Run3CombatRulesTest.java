/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.WorldTimeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run3CombatRulesTest {
	@Test
	void runtimeItemUseCannotBypassTheCombatTurnOrBonusAction() throws Exception {
		try (Engine e = engine(tempDb("run3-runtime-item-turn"))) {
			String campaign = ready(e), ogre = npc(e, campaign, "Ogre");
			Map<String, Object> loot = e.inventory().grantLoot(op(), campaign, "character:1",
					List.of(map("item", "Potion of Healing", "quantity", 2)), null, "QUEST", null);
			String entry = se.hirt.mcp.rpg.maps.SceneService.maps(loot.get("granted")).getFirst().get("entry")
					.toString();
			long itemId = Ref.id(entry, Ref.INVENTORY);
			long quantity = e.db().read(tx -> tx.get("inventory_entry", itemId).lng("quantity"));
			dice(e).queue(20, 1);
			String encounter = start(e, campaign, "character:1", ogre);
			long rolls = rollCount(e);
			RpgException refused = assertThrows(RpgException.class, () -> e.runtime().applyRuntimeChange(op(), campaign,
					"character:1", map("kind", "USE_ITEM", "item", entry)));
			assertTrue(refused.getMessage().contains("perform_encounter_action"));
			assertEquals(rolls, rollCount(e));
			assertEquals(quantity, e.db().read(tx -> tx.get("inventory_entry", itemId).lng("quantity")).longValue());
			dice(e).queue(2, 2);
			Map<String, Object> used = e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "USE_ITEM", "item", entry), false);
			assertEquals("BONUS_ACTION", used.get("action_cost"));
			assertEquals(quantity - 1,
					e.db().read(tx -> tx.get("inventory_entry", itemId).lng("quantity")).longValue());
			long afterUseRolls = rollCount(e);
			assertThrows(RpgException.class, () -> e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "USE_ITEM", "item", entry)));
			assertThrows(RpgException.class, () -> e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "USE_ITEM", "item", entry), false));
			assertEquals(afterUseRolls, rollCount(e));
			assertEquals(quantity - 1,
					e.db().read(tx -> tx.get("inventory_entry", itemId).lng("quantity")).longValue());
		}
	}

	@Test
	void lightWeaponKeepsTheTurnOpenButDoesNotGrantUnlimitedAttackActions() throws Exception {
		try (Engine e = engine(tempDb("run3-action-limit"))) {
			String campaign = ready(e), ogre = npc(e, campaign, "Ogre");
			e.inventory().grantLoot(op(), campaign, "character:1",
					List.of(map("item", "Dagger"), map("item", "Handaxe")), null, "QUEST",
					"Test two different Light weapons.");
			dice(e).queue(20, 1);
			String encounter = start(e, campaign, "character:1", ogre);
			dice(e).queue(1);
			Map<String, Object> first = e.encounters().perform(op(), campaign, encounter, "character:1",
					attack(ogre, "Dagger"));
			assertEquals(false, first.get("turn_advanced"));
			assertFalse(((List<?>) first.get("light_extra_attack_available")).isEmpty());
			long rolls = rollCount(e);
			assertThrows(RpgException.class, () -> e.encounters().perform(op(), campaign, encounter, "character:1",
					attack(ogre, "Handaxe"), false));
			assertEquals(rolls, rollCount(e), "a refused second Action does not roll");
			dice(e).queue(1);
			Map<String, Object> extra = e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "ATTACK", "target", ogre, "weapon", "Handaxe", "light_extra_attack", true));
			assertEquals("BONUS_ACTION", extra.get("action_cost"));
			assertEquals(false, extra.get("turn_advanced"));
			assertThrows(RpgException.class, () -> e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "DODGE"), false));
			assertThrows(RpgException.class, () -> e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "ATTACK", "target", ogre, "weapon", "Dagger", "light_extra_attack", true), false));
			endTurn(e, campaign, encounter, "character:1");
			endTurn(e, campaign, encounter, ogre);
			assertEquals("DODGE", e.encounters()
					.perform(op(), campaign, encounter, "character:1", map("kind", "DODGE"), false).get("kind"));
		}
	}

	@Test
	void multiattackContinuesOnlyItsExistingAttackActionAndStopsAtItsSavedCount() throws Exception {
		try (Engine e = engine(tempDb("run3-multiattack-limit"))) {
			String campaign = ready(e), scout = npc(e, campaign, "Scout");
			dice(e).queue(1, 20);
			String encounter = start(e, campaign, "character:1", scout);
			dice(e).queue(1);
			Map<String, Object> first = e.encounters().perform(op(), campaign, encounter, scout,
					attack("character:1", null), false);
			assertEquals(1, first.get("attacks_remaining"));
			assertThrows(RpgException.class,
					() -> e.encounters().perform(op(), campaign, encounter, scout, map("kind", "DODGE"), false));
			dice(e).queue(1);
			e.encounters().perform(op(), campaign, encounter, scout, attack("character:1", null), false);
			assertThrows(RpgException.class,
					() -> e.encounters().perform(op(), campaign, encounter, scout, attack("character:1", null), false));
			endTurn(e, campaign, encounter, scout);
			endTurn(e, campaign, encounter, "character:1");
			e.encounters().perform(op(), campaign, encounter, scout, map("kind", "DODGE"), false);
			assertThrows(RpgException.class,
					() -> e.encounters().perform(op(), campaign, encounter, scout, attack("character:1", null), false),
					"Multiattack cannot start after spending the Action on Dodge");
		}
	}

	@Test
	void levelFiveFighterRetainsBothAttacksOfOneActionButCannotStartAThird() throws Exception {
		try (Engine e = engine(tempDb("run3-fighter-extra-attack"))) {
			String campaign = ready(e), fighter = npc(e, campaign, "Guard");
			e.party().updateMembership(op(), campaign, fighter, "JOIN", "A guard trains as a classed Fighter.");
			String promotion = (String) cast(e.levelUps().begin(op(), campaign, fighter).get("transaction")).get("ref");
			e.levelUps().update(op(), campaign, promotion, null,
					map("class", "Fighter", "skills", List.of("Athletics", "Survival")));
			e.levelUps().commit(op(), campaign, promotion, null);
			e.runtime().awardXp(op(), campaign, List.of(fighter), e.rules().xpThreshold(5), "MILESTONE",
					"Train to Fighter level five.");
			for (int level = 2; level <= 5; level++) {
				Map<String, Object> begun = e.levelUps().begin(op(), campaign, fighter);
				String transaction = (String) cast(begun.get("transaction")).get("ref");
				if (begun.get("ability_score_improvement") instanceof Map<?, ?> improvement)
					e.levelUps().update(op(), campaign, transaction, null,
							map("ability_score_improvement", improvement.get("recommended")));
				e.levelUps().commit(op(), campaign, transaction, null);
			}
			assertEquals(5, e.characters().characterSheet(campaign, fighter, "PLAY").get("level"));
			String ogre = npc(e, campaign, "Ogre");
			dice(e).queue(20, 1);
			String encounter = start(e, campaign, fighter, ogre);
			dice(e).queue(1);
			Map<String, Object> first = e.encounters().perform(op(), campaign, encounter, fighter,
					attack(ogre, "unarmed"));
			assertEquals(false, first.get("turn_advanced"));
			assertEquals(1, first.get("attacks_remaining"));
			assertThrows(RpgException.class,
					() -> e.encounters().perform(op(), campaign, encounter, fighter, map("kind", "DODGE"), false));
			dice(e).queue(1);
			e.encounters().perform(op(), campaign, encounter, fighter, attack(ogre, "unarmed"), false);
			assertThrows(RpgException.class,
					() -> e.encounters().perform(op(), campaign, encounter, fighter, attack(ogre, "unarmed"), false));
		}
	}

	@Test
	void bonusActionCastByDefaultKeepsTheActionAndTurnOpenThroughMcpAndEngine() throws Exception {
		for (boolean tool : List.of(false, true)) {
			try (Engine e = engine(tempDb("run3-bonus-cast-" + tool))) {
				String campaign = ready(e), priest = npc(e, campaign, "Priest Acolyte");
				dice(e).queue(1, 20);
				String encounter = start(e, campaign, "character:1", priest);
				assertThrows(RpgException.class,
						() -> e.spells().cast(op(), campaign, priest, "Bless", null, List.of(priest), null),
						"the outer cast_spell boundary cannot bypass encounter action orchestration");
				Map<String, Object> result;
				Map<String, Object> action = map("kind", "CAST", "spell", "Bless", "targets", List.of(priest));
				if (tool) {
					RpgTools tools = new RpgTools();
					tools.engine = e;
					var response = tools.performEncounterAction(op(), campaign, priest, action, Optional.of(encounter),
							Optional.empty());
					assertFalse(response.isError(), response.firstContent().asText().text());
					result = cast(Json.readMap(response.firstContent().asText().text()).get("result"));
				} else
					result = e.encounters().perform(op(), campaign, encounter, priest, action);
				assertEquals(false, result.get("turn_advanced"));
				assertEquals("AVAILABLE", cast(result.get("turn_economy")).get("action"));
				assertEquals("SPENT", cast(result.get("turn_economy")).get("bonus_action"));
				e.encounters().perform(op(), campaign, encounter, priest, map("kind", "DODGE"), false);
				assertThrows(RpgException.class, () -> e.encounters().perform(op(), campaign, encounter, priest,
						map("kind", "CAST", "spell", "Thaumaturgy"), false));
			}
		}
	}

	@Test
	void checkpointPolicySavesAnEncounterStartThatRewindsTheWholeFightAfterRestart() throws Exception {
		Path path = tempDb("run3-checkpoint-retry");
		String campaign, checkpoint;
		long before;
		try (Engine e = engine(path)) {
			campaign = ready(e);
			String ogre = npc(e, campaign, "Ogre");
			before = hp(e, "character:1");
			dice(e).queue(20, 1);
			Map<String, Object> started = e.encounters().start(op(), campaign,
					map("party", List.of("character:1"), "foes", List.of(ogre)), null, null, null, null, null);
			checkpoint = (String) started.get("retry_checkpoint");
			assertNotNull(checkpoint);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", 3, "reason", "A wound during the fight."));
		}
		try (Engine e = engine(path)) {
			e.checkpoints().restore(op(), campaign, checkpoint,
					"Retry this encounter, preserving earlier exploration.");
			assertEquals(before, hp(e, "character:1"));
			assertEquals(0L, e.db().read(tx -> tx.count("SELECT COUNT(*) FROM encounter")).longValue());
			assertEquals("EXPLORATION",
					e.db().read(tx -> tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)).str("harness_state")));
		}
	}

	@Test
	void auditedRevivalRestoresDeathEndedMembershipAndPrimaryIdentityAfterReloadAndRewind() throws Exception {
		Path path = tempDb("run3-revival-party");
		String campaign, deadCheckpoint;
		try (Engine e = engine(path)) {
			campaign = ready(e);
			kill(e, campaign, "character:1");
			assertEquals("DEAD", membership(e, "character:1"));
			deadCheckpoint = (String) e.checkpoints().create(op(), campaign, "Dead before the audited revival.")
					.get("checkpoint");
		}
		try (Engine e = engine(path)) {
			revive(e, campaign, "character:1");
			assertEquals("ACTIVE", membership(e, "character:1"));
			assertTrue(String.valueOf(e.sessions().party(campaign, "FULL")).contains("Richard Greystone"));
			assertEquals(1L,
					e.db().read(tx -> tx.queryOne("SELECT character_id FROM player_control_assignment WHERE active=1")
							.orElseThrow().lng("character_id")).longValue());
			e.checkpoints().restore(op(), campaign, deadCheckpoint, "Verify revival is rewindable.");
			assertEquals("DEAD", membership(e, "character:1"));
			revive(e, campaign, "character:1");
			assertEquals("ACTIVE", membership(e, "character:1"));
		}
	}

	@Test
	void revivalPreservesGuestAndSeparatedStateAndNeverRecruitsADismissedFormerMember() throws Exception {
		try (Engine e = engine(tempDb("run3-revival-membership"))) {
			String campaign = ready(e);
			for (String state : List.of("GUEST", "SEPARATED", "DISMISSED")) {
				String member = npc(e, campaign, "Guard");
				e.party().updateMembership(op(), campaign, member, state.equals("GUEST") ? "GUEST_ADD" : "JOIN",
						"Test saved membership.");
				if (!state.equals("GUEST"))
					e.party().updateMembership(op(), campaign, member,
							state.equals("SEPARATED") ? "SEPARATE" : "DISMISS",
							"Keep the prior membership distinction.");
				kill(e, campaign, member);
				revive(e, campaign, member);
				assertEquals(state, membership(e, member));
			}
		}
	}

	@Test
	void dyingCompanionsKeepMembershipAndCareHintsAndDeathSavesRecordTheirActualCause() throws Exception {
		try (Engine e = engine(tempDb("run3-dying-care"))) {
			String campaign = ready(e), guard = npc(e, campaign, "Guard");
			e.party().updateMembership(op(), campaign, guard, "JOIN", "Ansel is a real companion.");
			Map<String, Object> damage = e.runtime().applyRuntimeChange(op(), campaign, guard,
					map("kind", "DAMAGE", "amount", hp(e, guard), "reason", "Knocked down."));
			assertEquals("DYING", damage.get("life_state"));
			assertEquals("ACTIVE", membership(e, guard));
			assertTrue(String.valueOf(damage.get("care")).contains("STABILIZE"));
			String ogre = npc(e, campaign, "Ogre");
			dice(e).queue(1, 20);
			String encounter = start(e, campaign, "character:1", ogre);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", hp(e, "character:1"), "reason", "Wounded during the fight."));
			for (int i = 0; i < 3; i++) {
				dice(e).queue(2);
				endTurn(e, campaign, encounter, ogre);
			}
			assertEquals("DEAD", e.db().read(tx -> tx.get("character", 1).str("life_state")));
			assertEquals("FAILED_DEATH_SAVES",
					e.db().read(tx -> tx
							.queryOne("SELECT * FROM event WHERE type='CHARACTER_DIED' ORDER BY id DESC LIMIT 1")
							.orElseThrow().map("payload_json").get("cause")));
			RpgException error = assertThrows(RpgException.class,
					() -> e.encounters().perform(op(), campaign, encounter, ogre, attack("character:1", null), false));
			assertTrue(error.getMessage().contains("Dead"));
			assertEquals("DEAD", error.details().get("life_state"));
		}
	}

	@Test
	void repeatedLongRestWithNobodyEligibleDoesNotAdvanceEightEmptyHours() throws Exception {
		try (Engine e = engine(tempDb("run3-rest-no-eligible"))) {
			String campaign = ready(e);
			assertEquals(true, e.rest().rest(op(), campaign, "LONG", null, null).get("completed"));
			long before = seconds(e, campaign);
			long actions = e.db().read(tx -> tx.count("SELECT COUNT(*) FROM action_progress"));
			Map<String, Object> refused = e.rest().rest(op(), campaign, "LONG", null, null);
			assertEquals(false, refused.get("completed"));
			assertEquals(0L, refused.get("elapsed_seconds"));
			assertEquals(before, seconds(e, campaign));
			assertEquals(actions, e.db().read(tx -> tx.count("SELECT COUNT(*) FROM action_progress")).longValue());
			new WorldTimeline(e.db()).perform(op(), campaign, "WAIT", 16 * 3600, "explicit-wait", Map.of());
			assertEquals(true, e.rest().rest(op(), campaign, "LONG", null, null).get("completed"));
			assertEquals(before + 24 * 3600, seconds(e, campaign));
		}
	}

	@Test
	void thrownCreatureSpearIsExplicitInTheResultAndLog() throws Exception {
		try (Engine e = engine(tempDb("run3-thrown-evidence"))) {
			String campaign = ready(e), guard = npc(e, campaign, "Guard");
			dice(e).queue(1, 20);
			String encounter = (String) e.encounters()
					.start(op(), campaign, map("party", List.of("character:1"), "guards", List.of(guard)), null, null,
							null, null, null,
							map("placements", List.of(map("character", "character:1", "x_ft", 0, "y_ft", 0),
									map("character", guard, "x_ft", 20, "y_ft", 0))))
					.get("encounter");
			dice(e).queue(1);
			Map<String, Object> attack = e.encounters().perform(op(), campaign, encounter, guard,
					map("kind", "ATTACK", "target", "character:1", "attack", "Spear", "thrown", true), false);
			assertEquals("RANGED", attack.get("attack_mode"));
			assertEquals(true, attack.get("thrown"));
			assertTrue(String.valueOf(attack.get("summary")).contains("thrown"));
			assertTrue(e.db()
					.read(tx -> tx
							.queryOne("SELECT summary FROM encounter_log WHERE kind='ATTACK' ORDER BY id DESC LIMIT 1")
							.orElseThrow().str("summary"))
					.contains("thrown"));
		}
	}

	@Test
	void recoveredCreatureCanStandOutsideCombatWithInterruptibleTimeAndNoCombatBypass() throws Exception {
		try (Engine e = engine(tempDb("run3-exploration-stand"))) {
			String campaign = ready(e);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", hp(e, "character:1"), "reason", "A wound before recovery."));
			dice(e).queue(1);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "STABILIZE", "reason", "Successful first aid."));
			new WorldTimeline(e.db()).perform(op(), campaign, "WAIT", 3600, "recover-before-standing", Map.of());
			assertEquals(1L, hp(e, "character:1"));
			assertTrue(prone(e));
			long before = seconds(e, campaign);
			Map<String, Object> stand = e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "STAND", "reason", "Get up after recovering consciousness."));
			assertEquals(true, stand.get("stood_up"));
			assertEquals(15, stand.get("movement_cost_ft"));
			assertEquals(before + 3, seconds(e, campaign));
			assertFalse(prone(e));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "ADD_CONDITION", "condition", "PRONE", "reason", "A second fall."));
			new WorldTimeline(e.db()).schedule(op(), campaign,
					map("after_seconds", 1, "kind", "BELL", "interrupting", true));
			Map<String, Object> interrupted = e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "STAND"));
			assertEquals(false, interrupted.get("stood_up"));
			assertTrue(prone(e));
			assertEquals(true, e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "STAND"))
					.get("stood_up"));
			assertEquals(before + 6, seconds(e, campaign), "the interrupted stand resumes its remaining two seconds");
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "ADD_CONDITION", "condition", "PRONE", "reason", "On the floor before combat."));
			String ogre = npc(e, campaign, "Ogre");
			dice(e).queue(20, 1);
			String encounter = start(e, campaign, "character:1", ogre);
			RpgException error = assertThrows(RpgException.class,
					() -> e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "STAND")));
			assertTrue(error.getMessage().contains("perform_encounter_action"));
			assertTrue(prone(e));
			Map<String, Object> combatStand = e.encounters().perform(op(), campaign, encounter, "character:1",
					map("kind", "STAND"));
			assertEquals(15, combatStand.get("movement_cost"));
			assertFalse(prone(e));
		}
	}

	@Test
	void speedZeroRefusesExplorationStandingAndMassiveDamageIsDistinguishedFromOrdinaryNpcDeath() throws Exception {
		try (Engine e = engine(tempDb("run3-stand-and-death-evidence"))) {
			String campaign = ready(e);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "ADD_CONDITION", "condition", "PRONE", "reason", "Lying down."));
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "ADD_CONDITION", "condition", "RESTRAINED", "reason", "Bound in place."));
			long before = seconds(e, campaign);
			assertThrows(RpgException.class,
					() -> e.runtime().applyRuntimeChange(op(), campaign, "character:1", map("kind", "STAND")));
			assertEquals(before, seconds(e, campaign));
			assertTrue(prone(e));
			String guard = npc(e, campaign, "Guard");
			Map<String, Object> ordinary = e.runtime().applyRuntimeChange(op(), campaign, guard, map("kind", "DAMAGE",
					"amount", hp(e, guard), "reason", "An ordinary nonparty creature reaches zero."));
			assertEquals("DEAD", ordinary.get("life_state"));
			assertNotEquals(true, ordinary.get("massive_damage"));
			Map<String, Object> massive = e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "DAMAGE", "amount", hp(e, "character:1") + 1000, "reason", "An overwhelming blow."));
			assertEquals(true, massive.get("massive_damage"));
			assertEquals("MASSIVE_DAMAGE", massive.get("death_cause"));
		}
	}

	private static String ready(Engine e) {
		String c = committedCampaign(e);
		e.sessions().bootstrap(op(), c, null);
		return c;
	}

	private static String npc(Engine e, String c, String kind) {
		return (String) e.runtime().materialize(op(), c, kind, kind, null, null, null, null, false).get("character");
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}

	private static String start(Engine e, String c, String hero, String foe) {
		return (String) e.encounters()
				.start(op(), c, map("party", List.of(hero), "foes", List.of(foe)), null, null, null, null, null,
						map("placements", List.of(map("character", hero, "x_ft", 0, "y_ft", 0),
								map("character", foe, "x_ft", 5, "y_ft", 0))))
				.get("encounter");
	}

	private static Map<String, Object> attack(String target, String weapon) {
		return weapon == null ? map("kind", "ATTACK", "target", target)
				: map("kind", "ATTACK", "target", target, "weapon", weapon);
	}

	private static void endTurn(Engine e, String c, String encounter, String actor) {
		e.encounters().perform(op(), c, encounter, actor, map("kind", "END_TURN"));
	}

	private static long hp(Engine e, String character) {
		return e.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).lng("current_hp"));
	}

	private static long rollCount(Engine e) {
		return e.db().read(tx -> tx.count("SELECT COUNT(*) FROM roll"));
	}

	private static long seconds(Engine e, String c) {
		return e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(c, Ref.CAMPAIGN)));
	}

	private static boolean prone(Engine e) {
		return e.db().read(tx -> tx.count(
				"SELECT COUNT(*) FROM active_effect WHERE character_id=1 AND condition_ref='srd5e:condition/prone'") > 0);
	}

	private static String membership(Engine e, String character) {
		return e.db().read(
				tx -> tx.queryOne("SELECT state FROM party_membership WHERE character_id=? ORDER BY id DESC LIMIT 1",
						Ref.id(character, Ref.CHARACTER)).orElseThrow().str("state"));
	}

	private static void kill(Engine e, String c, String character) {
		e.runtime().applyRuntimeChange(op(), c, character, map("kind", "DAMAGE", "amount", hp(e, character) + 1000,
				"reason", "A lethal event for the revival regression."));
	}

	private static void revive(Engine e, String c, String character) {
		e.rest().override(op(), c, "SET_LIFE_STATE", character, map("life_state", "ALIVE", "hp", 3),
				"The adjudicated revival restores this same character.", null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Object v) {
		return (Map<String, Object>) v;
	}
}
