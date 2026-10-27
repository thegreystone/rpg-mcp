/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class Run13DeathReadabilityTest {

	@Test
	void exactDyingCountersAndNextSaveAreReadableAtEveryDetailAndInPartyAndBootstrapWithoutMutatingOrRolling()
			throws Exception {
		var file = tempDb("run13-dying-readability");
		String campaign, companion;
		long due;
		try (Engine e = engine(file)) {
			campaign = ready(e);
			companion = companion(e, campaign);
			drop(e, campaign, "character:1");
			drop(e, campaign, companion);
			dice(e).queue(5, 11, 12, 5);
			assertEquals(true, waitSix(e, campaign).get("interrupted"));
			assertEquals(true, waitSix(e, campaign).get("interrupted"));
			due = now(e, campaign) + 6;
			assertTrue(dice(e).exhausted());
			for (String character : List.of("character:1", companion)) {
				Map<String, Object> expected = map("successes", 1, "failures", 1, "stable", false, "next_save_seconds",
						due);
				assertReads(e, campaign, character, expected, false);
			}
			var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
			dice(e).queue(17);
			for (String character : List.of("character:1", companion))
				allProjections(e, campaign, character);
			assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
			assertFalse(dice(e).exhausted(), "Reading counters never rolls a death save or recovery die.");
			assertEquals(17, dice(e).roll("1d20").total());
			var characters = characterRows(e, campaign);
			long clock = now(e, campaign), rolls = rollCount(e, campaign);
			var bootstrap = e.sessions().bootstrap(op(), campaign, 6000);
			assertEquals(1, number(SceneService.map(bootstrap.get("player_character")).get("death_saves"), "failures"));
			assertEquals(due,
					number(member(bootstrap.get("party"), companion).get("death_saves"), "next_save_seconds"));
			assertEquals(characters, characterRows(e, campaign),
					"Bootstrap may refresh session metadata, but cannot advance death mechanics.");
			assertEquals(clock, now(e, campaign));
			assertEquals(rolls, rollCount(e, campaign));
		}
		try (Engine e = engine(file)) {
			for (String character : List.of("character:1", companion))
				assertReads(e, campaign, character,
						map("successes", 1, "failures", 1, "stable", false, "next_save_seconds", due), false);
		}
	}

	@Test
	void stableRecoveryDeadlineIsCompactButDetailedEvidenceSurvivesAndHealingOrNaturalRecoveryClearsTheProjection()
			throws Exception {
		try (Engine e = engine(tempDb("run13-stable-readability"))) {
			String campaign = ready(e), companion = companion(e, campaign);
			drop(e, campaign, "character:1");
			drop(e, campaign, companion);
			dice(e).queue(1, 2);
			e.runtime().applyRuntimeChange(op(), campaign, "character:1",
					map("kind", "STABILIZE", "reason", "Adjudicated first aid."));
			e.runtime().applyRuntimeChange(op(), campaign, companion,
					map("kind", "STABILIZE", "reason", "Adjudicated first aid."));
			long started = now(e, campaign);
			for (String character : List.of("character:1", companion)) {
				long hours = character.equals("character:1") ? 1 : 2;
				Map<String, Object> expected = map("successes", 0, "failures", 0, "stable", true,
						"recovery_started_seconds", started, "recovery_hours", hours, "recovery_due_seconds",
						started + hours * 3600);
				assertReads(e, campaign, character, expected, true);
				var full = SceneService
						.map(e.characters().characterSheet(campaign, character, "FULL").get("death_saves"));
				assertEquals(hours, ((Number) SceneService.map(full.get("recovery_roll")).get("total")).longValue());
				assertTrue(SceneService.map(full.get("recovery_roll")).get("roll_ref").toString().startsWith("roll:"));
			}
			e.runtime().applyRuntimeChange(op(), campaign, companion,
					map("kind", "HEAL", "amount", 1, "reason", "A real healing effect restores consciousness."));
			assertAbsentEverywhere(e, campaign, companion);
			var recovery = e.sessions().advanceTime(op(), campaign, 60,
					"Wait for the saved one-hour natural recovery.");
			assertEquals("STABLE_RECOVERY", SceneService.maps(recovery.get("consequences")).getFirst().get("kind"));
			assertAbsentEverywhere(e, campaign, "character:1");
			assertEquals(1,
					((Number) SceneService
							.map(e.characters().characterSheet(campaign, "character:1", "SUMMARY").get("hp"))
							.get("current")).intValue());
			assertTrue(Json.write(e.characters().characterSheet(campaign, "character:1", "PLAY").get("conditions"))
					.contains("PRONE"));
		}
	}

	@Test
	void legacyMissingOrPartialSavesDefaultOnlyForActuallyDyingZeroHpAndNeverRepairTheDatabaseOnRead()
			throws Exception {
		try (Engine e = engine(tempDb("run13-legacy-death-readability"))) {
			String campaign = ready(e);
			drop(e, campaign, "character:1");
			legacy(e, campaign, "character:1", "DYING", null);
			assertReads(e, campaign, "character:1", map("successes", 0, "failures", 0, "stable", false), false);
			legacy(e, campaign, "character:1", "DYING", map("successes", 2));
			assertReads(e, campaign, "character:1", map("successes", 2, "failures", 0, "stable", false), false);
			legacy(e, campaign, "character:1", "ALIVE", map("successes", 2, "failures", 1, "stable", false));
			assertAbsentEverywhere(e, campaign, "character:1");
			assertEquals("ALIVE", e.characters().characterSheet(campaign, "character:1", "SUMMARY").get("life_state"));
			assertEquals(0,
					((Number) SceneService
							.map(e.characters().characterSheet(campaign, "character:1", "SUMMARY").get("hp"))
							.get("current")).intValue(),
					"A legacy ALIVE0 knockout is not reclassified as Dying by a read.");
			legacy(e, campaign, "character:1", "DEAD", map("successes", 2, "failures", 3, "stable", false));
			assertAbsentEverywhere(e, campaign, "character:1");
			assertEquals("DEAD", e.characters().characterSheet(campaign, "character:1", "PLAY").get("life_state"));
		}
	}

	private static void assertReads(
		Engine e, String campaign, String character, Map<String, Object> expected, boolean stable) {
		var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
		for (var projection : allProjections(e, campaign, character)) {
			var saves = SceneService.map(projection.get("death_saves"));
			for (var field : expected.entrySet())
				if (field.getValue() instanceof Number expectedNumber)
					assertEquals(expectedNumber.longValue(), number(saves, field.getKey()),
							field.getKey() + " in " + projection);
				else
					assertEquals(field.getValue(), saves.get(field.getKey()), field.getKey() + " in " + projection);
			assertEquals("DYING", projection.get("life_state"));
			if (stable)
				assertFalse(saves.containsKey("next_save_seconds"));
			else
				assertFalse(saves.containsKey("recovery_due_seconds"));
		}
		for (String detail : List.of("SUMMARY")) {
			var compact = SceneService
					.map(e.characters().characterSheet(campaign, character, detail).get("death_saves"));
			assertEquals(expected.keySet(), compact.keySet(),
					"Compact state carries only counters and actual saved deadlines.");
			assertFalse(compact.containsKey("recovery_roll"));
			assertFalse(compact.containsKey("outcome"));
			assertTrue(Json.utf8(Json.write(compact)).length <= 256,
					"Death state stays cheap in party/bootstrap context.");
		}
		assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static void assertAbsentEverywhere(Engine e, String campaign, String character) {
		var before = e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
		for (var projection : allProjections(e, campaign, character))
			assertFalse(projection.containsKey("death_saves"), projection.toString());
		assertEquals(before, e.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static List<Map<String, Object>> allProjections(Engine e, String campaign, String character) {
		var out = new ArrayList<Map<String, Object>>();
		for (String detail : List.of("SUMMARY", "PLAY", "FULL")) {
			out.add(e.characters().characterSheet(campaign, character, detail));
			out.add(member(e.sessions().party(campaign, detail).get("members"), character));
		}
		var context = e.db()
				.read(tx -> e.sessions().context(tx, tx.get("campaign", Ref.id(campaign, Ref.CAMPAIGN)), 6000));
		out.add(member(context.get("party"), character));
		if ("character:1".equals(character))
			out.add(SceneService.map(context.get("player_character")));
		return out;
	}

	private static Map<String, Object> member(Object rows, String character) {
		return SceneService.maps(rows).stream().filter(row -> character.equals(row.get("ref"))).findFirst()
				.orElseThrow();
	}

	private static long number(Object object, String key) {
		Object value = SceneService.map(object).get(key);
		assertInstanceOf(Number.class, value, key + " in " + object);
		return ((Number) value).longValue();
	}

	private static void legacy(Engine e, String campaign, String character, String life, Map<String, Object> saves) {
		e.db().mutate(Database.Mutation.of("run13_legacy_death_read_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(),
				"GM", Map.of()), tx -> {
					tx.update("character", Ref.id(character, Ref.CHARACTER), map("current_hp", 0, "life_state", life,
							"death_saves_json", saves == null ? null : Json.write(saves)));
					return Map.of("ok", true);
				});
	}

	private static String characterRows(Engine e, String campaign) {
		return e.db().read(tx -> Json.write(
				tx.query("SELECT * FROM character WHERE campaign_id=? ORDER BY id", Ref.id(campaign, Ref.CAMPAIGN))
						.stream().map(row -> row.asMap()).toList()));
	}

	private static long rollCount(Engine e, String campaign) {
		return e.db()
				.read(tx -> tx.count("SELECT COUNT(*) FROM roll WHERE campaign_id=?", Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static long now(Engine e, String campaign) {
		return e.db().read(tx -> GameTime.currentSeconds(tx, Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static Map<String, Object> waitSix(Engine e, String campaign) {
		return e.sessions().advanceTime(op(), campaign, 1, "Wait for the actual next six-second death-save boundary.");
	}

	private static void drop(Engine e, String campaign, String character) {
		int hp = e.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).integer("current_hp"));
		e.runtime().applyRuntimeChange(op(), campaign, character,
				map("kind", "DAMAGE", "amount", hp, "reason", "A previously adjudicated wound."));
	}

	private static String companion(Engine e, String campaign) {
		String companion = e.runtime()
				.materialize(op(), campaign, "Guard", "Wounded companion", null, null, null, null, false)
				.get("character").toString();
		e.party().updateMembership(op(), campaign, companion, "JOIN", "A real current companion.");
		return companion;
	}

	private static String ready(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static ScriptedRollService dice(Engine e) {
		return (ScriptedRollService) e.roller();
	}
}
