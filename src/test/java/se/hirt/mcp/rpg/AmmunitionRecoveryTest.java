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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Effects;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Recovering ammunition is an optional search after combat, with the final shot result preserved.
 */
class AmmunitionRecoveryTest {

	@Test
	void endingCombatOffersRecoveryWithoutReturningArrowsOrSpendingSearchTime() throws Exception {
		try (Fixture fixture = fixture("Arrow", 3)) {
			long before = clock(fixture);
			for (int i = 0; i < 3; i++) {
				fixture.dice.queue(1);
				shoot(fixture, "Arrow", true);
				if (i < 2) {
					endTargetTurn(fixture);
				}
			}
			assertEquals(0, quantity(fixture, "Arrow"));
			Map<String, Object> ended = end(fixture);
			assertEquals(0, quantity(fixture, "Arrow"), "ending combat does not automatically recover ammunition");
			assertEquals(before + ((Number) ended.get("elapsed_minutes")).longValue(), clock(fixture),
					"the optional search has not spent a minute");
			assertEquals("EXPLORATION", m(ended.get("meta")).get("harness_state"));
			assertNotNull(ended.get("ammunition_recovery"), "offer the player an optional search after combat");
			Map<String, Object> offer = offers(ended).getFirst();
			assertEquals(Boolean.TRUE, offer.get("optional"));
			assertEquals(fixture.actor, offer.get("character"));
			assertEquals(3, ((Number) offer.get("used")).intValue());
			assertEquals(1, ((Number) offer.get("recoverable")).intValue());
			assertEquals(1, ((Number) offer.get("elapsed_minutes")).intValue());
			assertTrue(fixture.dice.exhausted());
		}
	}

	@Test
	void explorationCanContinueBeforeThePlayerChoosesTheOneMinuteSearch() throws Exception {
		try (Fixture fixture = fixture("Arrow", 3)) {
			shots(fixture, "Arrow", false, 3);
			end(fixture);
			long afterCombat = clock(fixture);
			fixture.engine.sessions().advanceTime(op(), fixture.campaign, 2, "talk before choosing whether to search");
			assertEquals(0, quantity(fixture, "Arrow"));
			assertEquals(afterCombat + 2, clock(fixture));
			assertEquals(1, currentOffers(fixture).size(), "the offer does not require an immediate choice");
			recover(fixture, null);
			assertEquals(1, quantity(fixture, "Arrow"), "half of three rounds down to one");
			assertEquals(afterCombat + 3, clock(fixture), "choosing the search spends exactly one more minute");
			assertTrue(currentOffers(fixture).isEmpty());
			assertRecoveryRejected(fixture, fixture.actor, null);
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void fewerThanTwoExpendedPiecesOfferNoRecoveryAndCannotSpendSearchTime(int used) throws Exception {
		try (Fixture fixture = fixture("Arrow", 1)) {
			shots(fixture, "Arrow", false, used);
			Map<String, Object> ended = end(fixture);
			assertTrue(offers(ended).isEmpty());
			assertEquals(1 - used, quantity(fixture, "Arrow"));
			assertRecoveryRejected(fixture, fixture.actor, null);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void standardMagicArrowsRemainMagicOnlyWhenTheFinalAttackMissed(boolean hit) throws Exception {
		try (Fixture fixture = fixture("+1 Arrow", 2)) {
			shots(fixture, "+1 Arrow", hit, 2);
			Map<String, Object> candidate = candidates(offers(end(fixture)).getFirst()).getFirst();
			assertEquals(hit ? "MUNDANE" : "ORIGINAL", candidate.get("recover_as"));
			assertEquals(hit, candidate.get("hit"));
			recover(fixture, null);
			assertEquals(hit ? 0 : 1, quantity(fixture, "+1 Arrow"));
			assertEquals(hit ? 1 : 0, quantity(fixture, "Arrow"));
			assertTrue(fixture.dice.exhausted());
		}
	}

	@Test
	void mixedAmmunitionUsesOneGlobalQuotaAndRejectsInvalidExplicitSelectionsAtomically() throws Exception {
		try (Fixture fixture = fixture(
				List.of(map("item", "Arrow", "quantity", 1), map("item", "+1 Arrow", "quantity", 1)))) {
			shots(fixture, "Arrow", false, 1);
			shots(fixture, "+1 Arrow", false, 1);
			Map<String, Object> offer = offers(end(fixture)).getFirst();
			assertEquals(2, ((Number) offer.get("used")).intValue());
			assertEquals(1, ((Number) offer.get("recoverable")).intValue(),
					"round once across all ammunition, rather than within each type");
			assertEquals(2, candidates(offer).size());
			Map<String, Object> magic = candidates(offer).stream().filter(c -> "+1 Arrow".equals(c.get("name")))
					.findFirst().orElseThrow();
			Object group = magic.get("group");
			assertRecoveryRejected(fixture, fixture.actor, List.of());
			assertRecoveryRejected(fixture, fixture.actor, List.of(map("group", group, "quantity", 0)));
			assertRecoveryRejected(fixture, fixture.actor, List.of(map("group", group, "quantity", 2)));
			assertRecoveryRejected(fixture, fixture.actor,
					List.of(map("group", group, "quantity", 1), map("group", group, "quantity", 1)));
			assertRecoveryRejected(fixture, fixture.actor, List.of(map("group", Long.MAX_VALUE, "quantity", 1)));
			assertRecoveryRejected(fixture, fixture.actor,
					List.of(map("group", group, "quantity", 1, "recover_as", "MUNDANE")));
			fixture.dice.queue(6);
			assertEquals("EXPLICIT", recover(fixture, List.of(map("group", group, "quantity", 1))).get("method"));
			assertEquals(1, quantity(fixture, "+1 Arrow"));
			assertEquals(0, quantity(fixture, "Arrow"));
			assertEquals(6, fixture.dice.roll("1d6").total(), "an explicit selection consumes no recovery randomness");
		}
	}

	@Test
	void mixedKnownAmmunitionIsRandomlyRecoveredWithoutReplacementByDefault() throws Exception {
		try (Fixture fixture = fixture(
				List.of(map("item", "Arrow", "quantity", 3), map("item", "+1 Arrow", "quantity", 1)))) {
			shots(fixture, "Arrow", false, 3);
			shots(fixture, "+1 Arrow", false, 1);
			Map<String, Object> offer = offers(end(fixture)).getFirst();
			assertEquals(Boolean.FALSE, offer.get("selection_required"));
			assertEquals("RANDOM", offer.get("default_method"));
			assertEquals(2, ((Number) offer.get("recoverable")).intValue());
			long rollsBefore = fixture.engine.db().read(tx -> tx
					.count("SELECT COUNT(*) FROM roll WHERE campaign_id = ?", Ref.id(fixture.campaign, Ref.CAMPAIGN)));
			fixture.dice.queue(4); // Three mundane pieces precede the one magic piece in the four-piece pool.
			String operation = op();
			Map<String, Object> recovered = fixture.engine.encounters().recoverAmmunition(operation, fixture.campaign,
					fixture.encounter, fixture.actor, null);
			assertEquals("RANDOM", recovered.get("method"));
			assertEquals(1, quantity(fixture, "+1 Arrow"));
			assertEquals(1, quantity(fixture, "Arrow"),
					"the second draw cannot select the already recovered magic piece");
			long rollsAfter = fixture.engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM roll WHERE campaign_id = ?",
					Ref.id(fixture.campaign, Ref.CAMPAIGN)));
			assertTrue(rollsAfter > rollsBefore, "the observable random choice is recorded as a roll");
			Map<String, List<Map<String, Object>>> after = snapshot(fixture);
			fixture.dice.queue(6);
			assertEquals(Boolean.TRUE,
					fixture.engine.encounters()
							.recoverAmmunition(operation, fixture.campaign, fixture.encounter, fixture.actor, null)
							.get("replayed"));
			assertEquals(after, snapshot(fixture),
					"replaying random recovery changes neither inventory nor the search clock");
			long replayRolls = fixture.engine.db().read(tx -> tx
					.count("SELECT COUNT(*) FROM roll WHERE campaign_id = ?", Ref.id(fixture.campaign, Ref.CAMPAIGN)));
			assertEquals(rollsAfter, replayRolls);
			assertEquals(6, fixture.dice.roll("1d6").total(), "replaying recovery consumes no new randomness");
			assertTrue(fixture.dice.exhausted());
		}
	}

	@Test
	void hitAndMissPoolsForTheSameMagicArrowCannotBeConfused() throws Exception {
		try (Fixture fixture = fixture("+1 Arrow", 2)) {
			shots(fixture, "+1 Arrow", true, 1);
			shots(fixture, "+1 Arrow", false, 1);
			List<Map<String, Object>> candidates = candidates(offers(end(fixture)).getFirst());
			assertEquals(2, candidates.size());
			Map<String, Object> hit = candidates.stream().filter(c -> Boolean.TRUE.equals(c.get("hit"))).findFirst()
					.orElseThrow();
			Map<String, Object> miss = candidates.stream().filter(c -> Boolean.FALSE.equals(c.get("hit"))).findFirst()
					.orElseThrow();
			assertRecoveryRejected(fixture, fixture.actor,
					List.of(map("group", hit.get("group"), "quantity", 1, "recover_as", "ORIGINAL")));
			recover(fixture, List.of(map("group", miss.get("group"), "quantity", 1)));
			assertEquals(1, quantity(fixture, "+1 Arrow"));
			assertEquals(0, quantity(fixture, "Arrow"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"DECLINE", "CAST_SHIELD"})
	void shieldChoicesTrackOnlyTheFinalHitOrMissOnce(String option) throws Exception {
		try (Fixture fixture = fixture("+1 Arrow", 2)) {
			giveTargetShield(fixture);
			for (int i = 0; i < 2; i++) {
				fixture.dice.queue(8);
				Map<String, Object> pending = shoot(fixture, "+1 Arrow", true);
				assertEquals(Boolean.FALSE, pending.get("resolved"));
				if (option.equals("DECLINE")) {
					fixture.dice.queue(2);
				}
				Map<String, Object> resolved = fixture.engine.encounters().resolveChoice(op(), fixture.campaign,
						(String) m(pending.get("pending_choice")).get("transaction"), map("option", option));
				assertEquals(option.equals("DECLINE"), m(resolved.get("attack")).get("hit"));
				endTargetTurn(fixture);
			}
			Map<String, Object> offer = offers(end(fixture)).getFirst();
			assertEquals(2, ((Number) offer.get("used")).intValue(),
					"parking and resolving a shot records one expended piece");
			assertEquals(1, candidates(offer).size());
			assertEquals(option.equals("DECLINE") ? "MUNDANE" : "ORIGINAL",
					candidates(offer).getFirst().get("recover_as"));
			recover(fixture, null);
			assertEquals(option.equals("DECLINE") ? 1 : 0, quantity(fixture, "Arrow"));
			assertEquals(option.equals("CAST_SHIELD") ? 1 : 0, quantity(fixture, "+1 Arrow"));
			assertTrue(fixture.dice.exhausted());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"ORIGINAL", "MUNDANE", "LOST"})
	void customMagicNeedsAnExplicitGmDecisionEvenWhenNothingIsRecovered(String recoverAs) throws Exception {
		try (Fixture fixture = fixture(List.of(map("item", "Arrow", "quantity", 2, "magic",
				map("name", "Ember Arrow", "rarity", "RARE", "bonus", 1, "damage_bonus_dice", "1d4", "damage_type",
						"fire", "text", "An ember glows in the arrowhead."))))) {
			shots(fixture, "Ember Arrow", false, 2);
			Map<String, Object> offer = offers(end(fixture)).getFirst();
			assertEquals(Boolean.TRUE, offer.get("selection_required"));
			Map<String, Object> candidate = candidates(offer).getFirst();
			assertEquals("GM", candidate.get("recover_as"));
			Object group = candidate.get("group");
			fixture.dice.queue(6);
			assertRecoveryRejected(fixture, fixture.actor, null);
			assertRecoveryRejected(fixture, fixture.actor, List.of(map("group", group, "quantity", 1)));
			assertEquals(6, fixture.dice.roll("1d6").total(),
					"unknown magic is rejected before drawing recovery randomness");
			long beforeSearch = clock(fixture);
			recover(fixture, List.of(map("group", group, "quantity", 1, "recover_as", recoverAs)));
			assertEquals(recoverAs.equals("ORIGINAL") ? 1 : 0, quantity(fixture, "Ember Arrow"));
			assertEquals(recoverAs.equals("MUNDANE") ? 1 : 0, quantity(fixture, "Arrow"));
			assertEquals(beforeSearch + 1, clock(fixture),
					"a search costs a minute even when the GM rules the selected piece lost");
			assertTrue(currentOffers(fixture).isEmpty());
			assertRecoveryRejected(fixture, fixture.actor, null);
		}
	}

	@Test
	void replayingShotsEncounterEndAndRecoveryNeverDuplicatesInventoryOrSearchTime() throws Exception {
		try (Fixture fixture = fixture("Arrow", 2)) {
			String attackOperation = op();
			fixture.dice.queue(1);
			shoot(fixture, "Arrow", true, attackOperation);
			Map<String, List<Map<String, Object>>> afterShot = snapshot(fixture);
			assertEquals(Boolean.TRUE, shoot(fixture, "Arrow", true, attackOperation).get("replayed"));
			assertEquals(afterShot, snapshot(fixture));
			endTargetTurn(fixture);
			shots(fixture, "Arrow", false, 1);
			String endOperation = op();
			fixture.engine.encounters().end(endOperation, fixture.campaign, fixture.encounter, "OTHER", "withdraw");
			Map<String, List<Map<String, Object>>> afterEnd = snapshot(fixture);
			assertEquals(Boolean.TRUE, fixture.engine.encounters()
					.end(endOperation, fixture.campaign, fixture.encounter, "OTHER", "withdraw").get("replayed"));
			assertEquals(afterEnd, snapshot(fixture));
			String searchOperation = op();
			fixture.engine.encounters().recoverAmmunition(searchOperation, fixture.campaign, fixture.encounter,
					fixture.actor, null);
			Map<String, List<Map<String, Object>>> afterSearch = snapshot(fixture);
			assertEquals(Boolean.TRUE, fixture.engine.encounters()
					.recoverAmmunition(searchOperation, fixture.campaign, fixture.encounter, fixture.actor, null)
					.get("replayed"));
			assertEquals(afterSearch, snapshot(fixture));
			assertEquals(1, quantity(fixture, "Arrow"));
			assertRecoveryRejected(fixture, fixture.actor, null);
			assertTrue(fixture.dice.exhausted());
		}
	}

	@Test
	void restartingAndRestoringACheckpointPreserveTheOfferAndRewindTheCompletedSearch() throws Exception {
		Fixture original = fixture("+1 Arrow", 2);
		String checkpoint;
		long beforeSearch;
		try (original) {
			shots(original, "+1 Arrow", false, 2);
			end(original);
			beforeSearch = clock(original);
			checkpoint = (String) original.engine.checkpoints()
					.create(op(), original.campaign, "before deciding whether to search").get("checkpoint");
		}
		try (Engine engine = TestCampaigns.engine(original.database)) {
			Fixture reopened = original.withEngine(engine);
			assertEquals(1, currentOffers(reopened).size());
			recover(reopened, null);
			assertEquals(1, quantity(reopened, "+1 Arrow"));
			assertEquals(beforeSearch + 1, clock(reopened));
			assertTrue(currentOffers(reopened).isEmpty());
			engine.checkpoints().restore(op(), reopened.campaign, checkpoint, "choose again");
			assertEquals(0, quantity(reopened, "+1 Arrow"));
			assertEquals(beforeSearch, clock(reopened));
			assertEquals(1, currentOffers(reopened).size());
			recover(reopened, null);
			assertEquals(1, quantity(reopened, "+1 Arrow"));
			assertEquals(beforeSearch + 1, clock(reopened));
		}
	}

	@Test
	void recoveryRequiresAnEndedBattleTheOriginalShooterAndTheSameKnownLocation() throws Exception {
		try (Fixture fixture = fixture("Arrow", 2)) {
			shots(fixture, "Arrow", false, 2);
			assertRecoveryRejected(fixture, fixture.actor, null);
			end(fixture);
			assertRecoveryRejected(fixture, fixture.target, null);
			Long location = fixture.engine.db()
					.read(tx -> tx.get("character", Ref.id(fixture.actor, Ref.CHARACTER)).lng("location_id"));
			updateActor(fixture, map("location_id", null));
			assertRecoveryRejected(fixture, fixture.actor, null);
			long otherLocation = ((Number) fixture.engine.db()
					.mutate(Database.Mutation.of("ammunition_recovery_location_fixture",
							Ref.id(fixture.campaign, Ref.CAMPAIGN), op(), "GM", null),
							tx -> map("location",
									tx.insert("location",
											map("campaign_id", Ref.id(fixture.campaign, Ref.CAMPAIGN), "kind", "SITE",
													"name", "Another field", "materialization", "MATERIALIZED"))))
					.get("location")).longValue();
			updateActor(fixture, map("location_id", otherLocation));
			assertRecoveryRejected(fixture, fixture.actor, null);
			updateActor(fixture, map("location_id", location, "life_state", "DYING"));
			assertRecoveryRejected(fixture, fixture.actor, null);
			updateActor(fixture, map("life_state", "ALIVE", "lifecycle", "ARCHIVED"));
			assertRecoveryRejected(fixture, fixture.actor, null);
			updateActor(fixture, map("lifecycle", "ACTIVE"));
			long effect = ((Number) fixture.engine.db().mutate(
					Database.Mutation.of("ammunition_recovery_condition_fixture",
							Ref.id(fixture.campaign, Ref.CAMPAIGN), op(), "GM", null),
					tx -> map("effect",
							Effects.add(tx, Ref.id(fixture.campaign, Ref.CAMPAIGN),
									Ref.id(fixture.actor, Ref.CHARACTER), null, null, "cannot search while stunned",
									"srd5e:condition/stunned", null, null, null, null, "GM")))
					.get("effect")).longValue();
			assertRecoveryRejected(fixture, fixture.actor, null);
			fixture.engine.db().mutate(Database.Mutation.of("end_ammunition_recovery_condition_fixture",
					Ref.id(fixture.campaign, Ref.CAMPAIGN), op(), "GM", null), tx -> {
						Effects.end(tx, tx.get("active_effect", effect));
						return Map.of();
					});
			recover(fixture, null);
			assertEquals(1, quantity(fixture, "Arrow"));
		}
	}

	@Test
	void legacyAttackLogsWithoutRecoveryMetadataRemainReadableAndCannotInventRecoveredItems() throws Exception {
		try (Fixture fixture = fixture("+1 Arrow", 2)) {
			shots(fixture, "+1 Arrow", false, 2);
			fixture.engine.db().mutate(Database.Mutation.of("legacy_ammunition_log_fixture",
					Ref.id(fixture.campaign, Ref.CAMPAIGN), op(), "GM", null), tx -> {
						for (Row row : tx.query("SELECT * FROM encounter_log WHERE encounter_id = ? AND kind = ?",
								Ref.id(fixture.encounter, Ref.ENCOUNTER), "ATTACK")) {
							Map<String, Object> payload = row.map("payload_json");
							Map<String, Object> ammunition = m(payload.get("ammunition"));
							assertNotNull(ammunition.remove("recovery"),
									"the fixture strips only the new metadata from a real finalized attack");
							tx.update("encounter_log", row.id(), map("payload_json", Json.write(payload)));
						}
						return Map.of();
					});
			assertTrue(offers(end(fixture)).isEmpty());
			assertTrue(currentOffers(fixture).isEmpty());
			assertRecoveryRejected(fixture, fixture.actor, null);
			assertEquals(0, quantity(fixture, "+1 Arrow"));
			assertEquals(0, quantity(fixture, "Arrow"));
		}
	}

	private record Fixture(Engine engine, ScriptedRollService dice, Path database, String campaign, String actor,
			String target, String encounter, String bow) implements AutoCloseable {
		Fixture withEngine(Engine replacement) {
			return new Fixture(replacement, (ScriptedRollService) replacement.roller(), database, campaign, actor,
					target, encounter, bow);
		}

		@Override
		public void close() {
			engine.close();
		}
	}

	private static Fixture fixture(String ammunition, int count) throws Exception {
		return fixture(List.of(map("item", ammunition, "quantity", count)));
	}

	private static Fixture fixture(List<Map<String, Object>> ammunition) throws Exception {
		Path database = TestCampaigns.tempDb("ammunition-recovery");
		Engine engine = TestCampaigns.engine(database);
		try {
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
							map("premise", "an ambush", "opening_location", "Tower", "immediate_goal", "escape")));
			String actor = (String) engine.characters().createDraft(op(), campaign,
					map("name", "Vess", "species", "Human", "class", "Fighter", "ability_scores",
							map("STR", 15, "DEX", 14, "CON", 13, "INT", 12, "WIS", 10, "CHA", 8), "skills",
							List.of("Athletics", "Perception"), "personality", "patient", "background", "Criminal",
							"background_ability_scores", map("CON", 2, "DEX", 1), "species_skill", "Insight",
							"origin_feat",
							map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine"))),
					true).get("character");
			engine.characters().commitDraft(op(), campaign, actor, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			String bow = (String) list(engine.inventory()
					.grantLoot(op(), campaign, actor, List.of(map("item", "Shortbow")), null, "QUEST", null)
					.get("granted")).getFirst().get("entry");
			engine.inventory().equip(op(), campaign, actor, bow, true);
			engine.inventory().grantLoot(op(), campaign, actor, ammunition, null, "QUEST", null);
			String target = (String) engine.runtime()
					.materialize(op(), campaign, "Ogre", "Brawn", null, null, null, null, false).get("character");
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			dice.queue(20, 100, 1, 1);
			Map<String, Object> started = engine.encounters().start(op(), campaign,
					map("party", List.of(actor), "foes", List.of(target)), null, null, null, null, null);
			assertEquals(actor, m(started.get("turn")).get("character"));
			assertTrue(dice.exhausted());
			return new Fixture(engine, dice, database, campaign, actor, target, (String) started.get("encounter"), bow);
		} catch (Exception | Error failure) {
			engine.close();
			throw failure;
		}
	}

	private static Map<String, Object> shoot(Fixture fixture, String ammunition, boolean endTurn) {
		return shoot(fixture, ammunition, endTurn, op());
	}

	private static Map<String, Object> shoot(Fixture fixture, String ammunition, boolean endTurn, String operation) {
		return fixture.engine.encounters().perform(operation, fixture.campaign, fixture.encounter, fixture.actor,
				map("kind", "ATTACK", "weapon", fixture.bow, "ammunition", ammunition, "target", fixture.target),
				endTurn);
	}

	private static void shots(Fixture fixture, String ammunition, boolean hit, int count) {
		for (int i = 0; i < count; i++) {
			if (hit) {
				fixture.dice.queue(19, 2);
			} else {
				fixture.dice.queue(1);
			}
			assertEquals(hit, shoot(fixture, ammunition, true).get("hit"));
			endTargetTurn(fixture);
		}
	}

	private static Map<String, Object> recover(Fixture fixture, List<Map<String, Object>> selection) {
		return fixture.engine.encounters().recoverAmmunition(op(), fixture.campaign, fixture.encounter, fixture.actor,
				selection);
	}

	private static List<Map<String, Object>> currentOffers(Fixture fixture) {
		return offers(fixture.engine.encounters().encounterState(fixture.campaign, fixture.encounter, 3));
	}

	private static List<Map<String, Object>> candidates(Map<String, Object> offer) {
		return list(offer.get("candidates"));
	}

	private static void assertRecoveryRejected(Fixture fixture, String actor, List<Map<String, Object>> selection) {
		Map<String, List<Map<String, Object>>> before = snapshot(fixture);
		assertThrows(RpgException.class, () -> fixture.engine.encounters().recoverAmmunition(op(), fixture.campaign,
				fixture.encounter, actor, selection));
		assertEquals(before, snapshot(fixture),
				"an invalid or repeated recovery changes neither inventory, search time nor recovery eligibility");
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Fixture fixture) {
		return fixture.engine.db().snapshot(Ref.id(fixture.campaign, Ref.CAMPAIGN));
	}

	private static void updateActor(Fixture fixture, Map<String, Object> columns) {
		fixture.engine.db().mutate(Database.Mutation.of("ammunition_recovery_actor_fixture",
				Ref.id(fixture.campaign, Ref.CAMPAIGN), op(), "GM", null), tx -> {
					tx.update("character", Ref.id(fixture.actor, Ref.CHARACTER), columns);
					return Map.of();
				});
	}

	private static void giveTargetShield(Fixture fixture) {
		fixture.engine.db().mutate(Database.Mutation.of("ammunition_recovery_shield_fixture",
				Ref.id(fixture.campaign, Ref.CAMPAIGN), op(), "GM", null), tx -> {
					tx.insert("character_trait", map("character_id", Ref.id(fixture.target, Ref.CHARACTER), "kind",
							"SPELL_PREPARED", "content_ref", "srd5e:spell/shield", "payload_json", "{}"));
					tx.insert("resource_state", map("character_id", Ref.id(fixture.target, Ref.CHARACTER),
							"resource_ref", "slot:1", "current", 2, "max", 2, "recharge", "LONG_REST"));
					tx.update("encounter", Ref.id(fixture.encounter, Ref.ENCOUNTER), map("npc_reactions", "ASK"));
					return Map.of();
				});
	}

	private static void endTargetTurn(Fixture fixture) {
		fixture.engine.encounters().perform(op(), fixture.campaign, fixture.encounter, fixture.target,
				map("kind", "END_TURN"), true);
	}

	private static Map<String, Object> end(Fixture fixture) {
		return fixture.engine.encounters().end(op(), fixture.campaign, fixture.encounter, "OTHER",
				"the enemy withdraws");
	}

	private static int quantity(Fixture fixture, String name) {
		return list(
				fixture.engine.characters().characterSheet(fixture.campaign, fixture.actor, "PLAY").get("inventory"))
				.stream().filter(entry -> name.equals(entry.get("name")))
				.mapToInt(entry -> ((Number) entry.get("quantity")).intValue()).sum();
	}

	private static long clock(Fixture fixture) {
		return fixture.engine.db().read(tx -> tx
				.queryOne("SELECT * FROM game_clock WHERE campaign_id = ?", Ref.id(fixture.campaign, Ref.CAMPAIGN))
				.orElseThrow().lng("seq"));
	}

	private static List<Map<String, Object>> offers(Map<String, Object> result) {
		return result.get("ammunition_recovery") == null ? List.of() : list(result.get("ammunition_recovery"));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}
}
