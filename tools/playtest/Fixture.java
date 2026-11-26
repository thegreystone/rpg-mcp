/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.TravelJourneyService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Opt-in fixture and independent state grader, loaded against either revision's compiled engine. */
public final class Fixture {

	public static void main(String[] args) throws Exception {
		String mode = args[0], scenario = args[1];
		Path directory = Path.of(args[2]).toAbsolutePath();
		long seed = Long.parseLong(args[3]);
		int step = args.length > 4 ? Integer.parseInt(args[4]) : Integer.MAX_VALUE;
		Files.createDirectories(directory);
		Path database = directory.resolve("rpg.db");
		if (mode.equals("create") && Files.exists(database))
			throw new IllegalStateException("Refusing to replace a campaign: " + database);
		if (!mode.equals("create") && (!Set.of("grade", "exercise").contains(mode) || !Files.exists(database)))
			throw new IllegalArgumentException("Expected create for a new database, or grade/exercise for an existing fixture.");
		try (Engine engine = new Engine(database, new ScriptedRollService(seed), "local-playtest")) {
			if (mode.equals("create")) {
				prepare(engine, scenario, directory);
				Files.writeString(directory.resolve("before.json"), Json.write(snapshot(engine)));
			} else {
				Map<String, Object> before = Json.readMap(Files.readString(directory.resolve("before.json")));
				if (mode.equals("exercise")) exercise(engine, scenario);
				Map<String, Object> after = snapshot(engine);
				Map<String, Object> checkpoint = Files.exists(directory.resolve("checkpoint-state.json"))
						? Json.readMap(Files.readString(directory.resolve("checkpoint-state.json"))) : Map.of();
				Map<String, Object> grade = grade(scenario, before, after, checkpoint, step);
				Files.writeString(directory.resolve("after.json"), Json.write(after));
				Files.writeString(directory.resolve("grade.json"), Json.write(grade));
				System.out.println(Json.write(grade));
				if (mode.equals("exercise") && !Boolean.TRUE.equals(grade.get("passed"))) System.exit(2);
			}
		}
	}

	private static void prepare(Engine e, String scenario, Path directory) throws Exception {
		String c = committedCampaign(e), hero = "character:1";
		e.sessions().bootstrap(op(), c, null);
		e.characters().updateCharacter(op(), c, hero, null, map("name", "Richard"));
		String mira = e.runtime().materialize(op(), c, "Guard", "Mira", null, null, null, null, false)
				.get("character").toString();
		e.party().updateMembership(op(), c, mira, "JOIN", "Richard's traveling companion.");
		if (scenario.equals("short-rest")) {
			e.runtime().awardXp(op(), c, List.of(hero), 300, "QUEST", "Earlier adventure.");
			String transaction = ((Map<?, ?>) e.levelUps().begin(op(), c, hero).get("transaction")).get("ref").toString();
			e.levelUps().update(op(), c, transaction, null, map("metamagic", List.of("Empowered Spell", "Quickened Spell")));
			e.levelUps().commit(op(), c, transaction, null);
			int hp = ((Number) ((Map<?, ?>) e.characters().characterSheet(c, hero, "FULL").get("hp")).get("current")).intValue();
			e.runtime().applyRuntimeChange(op(), c, hero, map("kind", "DAMAGE", "amount", hp - 2, "reason", "Earlier skirmish."));
		} else if (scenario.equals("level-up") || scenario.equals("upgrade-level-up")) {
			e.runtime().awardXp(op(), c, List.of(hero), 300, "QUEST", "Earlier adventure.");
			if (scenario.equals("upgrade-level-up")) e.levelUps().begin(op(), c, hero);
		} else if (scenario.equals("pending-status") || scenario.equals("combat-reaction")) {
			String bandit = e.runtime().materialize(op(), c, "Bandit", "Road watcher", null, null, null, null, false)
					.get("character").toString();
			e.inventory().grantLoot(op(), c, hero, List.of(map("item", "Club", "quantity", 1)), null, "GM_GRANT", "A carried club.");
			((ScriptedRollService) e.roller()).queue(20, 1);
			String encounter = e.encounters().start(op(), c, map("party", List.of(hero, mira), "watchers", List.of(bandit)),
					null, meleeZones(hero, mira, bandit), null, null, null).get("encounter").toString();
			// Move every actor before the watcher through its turn; the PC's reaction stays available.
			for (int i = 0; i < 4; i++) {
				Map<?, ?> turn = (Map<?, ?>) e.encounters().encounterState(c, encounter, 1, "GM").get("turn");
				String actor = turn.get("character").toString();
				if (actor.equals(bandit)) break;
				e.encounters().perform(op(), c, encounter, actor, map("kind", "END_TURN"), true);
			}
			((ScriptedRollService) e.roller()).queue(1); // Mira's automatic opportunity attack cannot end the fixture early.
			e.encounters().perform(op(), c, encounter, bandit, map("kind", "MOVE", "zone", "far"), true);
			long pending = e.db().read(tx -> tx.count("SELECT COUNT(*) FROM pending_transaction WHERE status='OPEN'"));
			if (pending != 1) throw new IllegalStateException("Expected exactly Richard's reaction, got " + pending);
		} else if (scenario.equals("interrupted-travel")) {
			String home = e.db().read(tx -> "location:" + tx.get("character", 1).lng("location_id"));
			String mill = e.world().materialize(op(), c, null, map("name", "Old Mill", "kind", "SITE", "connections",
					List.of(map("to", home, "kind", "ROAD", "travel_minutes", 50))), "GM").get("ref").toString();
			String road = e.db().read(tx -> "connection:" + tx.queryOne("SELECT id FROM location_connection WHERE campaign_id=1 ORDER BY id DESC LIMIT 1")
					.orElseThrow().id());
			new TravelJourneyService(e.db(), e.rules(), e.roller()).defineRisk(op(), c, road,
					map("authored_at_fraction", .5, "frequency_seconds", 3600, "chance_percent", 0));
			Map<String, Object> journey = e.world().move(op(), c, mill, null, false, null, "Travel toward Old Mill.");
			if (!"INTERRUPTED".equals(journey.get("status")))
				throw new IllegalStateException("Expected an interrupted journey: " + Json.write(journey));
		} else if (scenario.equals("upgrade-recall")) {
			String foe = e.runtime().materialize(op(), c, "Bandit", "Road watcher", null, null, null, null, false)
					.get("character").toString();
			String encounter = e.encounters().start(op(), c, map("party", List.of(hero, mira), "watchers", List.of(foe)),
					null, meleeZones(hero, mira, foe), null, null, null).get("encounter").toString();
			e.runtime().applyRuntimeChange(op(), c, foe, map("kind", "DAMAGE", "amount", 1000, "reason", "Falling masonry ended the battle."));
			e.encounters().end(op(), c, encounter, "PARTY_VICTORY", "The road watcher fell.", "Richard and Mira survived when falling masonry struck the watcher.");
			e.sessions().suspend(op(), c, "Richard and Mira survived the road watcher at Bellhaven. No loot was claimed.");
		} else if (scenario.equals("upgrade-checkpoint")) {
			e.inventory().grantLoot(op(), c, hero, null, map("gp", 5), "QUEST", "Earlier bounty.");
			e.checkpoints().create(op(), c, "Before the toll");
			Files.writeString(directory.resolve("checkpoint-state.json"), Json.write(snapshot(e)));
			e.inventory().trade(op(), c, hero, "BUY", "Torch", null, 1, null, null, null);
			e.runtime().applyRuntimeChange(op(), c, hero, map("kind", "DAMAGE", "amount", 2, "reason", "A later scrape."));
		} else if (scenario.equals("spell-potion")) {
			e.spells().prepare(op(), c, hero, List.of("Fire Bolt", "Ray of Frost", "Shocking Grasp", "Prestidigitation"),
					List.of("Mage Armor", "Shield"));
			e.inventory().grantLoot(op(), c, hero, List.of(map("item", "Potion of Healing", "quantity", 1)), null, "QUEST", "Earlier supplies.");
			int hp = ((Number) ((Map<?, ?>) e.characters().characterSheet(c, hero, "FULL").get("hp")).get("current")).intValue();
			e.runtime().applyRuntimeChange(op(), c, hero, map("kind", "DAMAGE", "amount", hp - 2, "reason", "Earlier scrape."));
		} else if (scenario.equals("commerce-sequence")) {
			e.inventory().grantLoot(op(), c, hero, null, map("gp", 5), "QUEST", "Earlier bounty.");
		} else if (scenario.equals("upgrade-death")) {
			e.runtime().applyRuntimeChange(op(), c, hero, map("kind", "DAMAGE", "amount", 1000, "reason", "Richard fell in an earlier accident."));
		} else if (!Set.of("companion-control", "session-recovery").contains(scenario)) {
			throw new IllegalArgumentException("Unknown scenario: " + scenario);
		}
	}

	private static void exercise(Engine e, String scenario) {
		String c = "campaign:1", hero = "character:1";
		e.sessions().bootstrap(freshOp(), c, null);
		switch (scenario) {
		case "short-rest" -> e.rest().rest(freshOp(), c, "SHORT", map(hero, 2), List.of(hero));
		case "companion-control" -> e.tacticalControl().set(freshOp(), c, List.of("character:2"), "PLAYER", "UNTIL_RELEASED", null, "Player request.");
		case "pending-status" -> e.sessions().party(c, "FULL");
		case "combat-reaction" -> {
			String transaction = e.db().read(tx -> "transaction:" + tx.queryOne("SELECT id FROM pending_transaction WHERE status='OPEN'").orElseThrow().id());
			e.encounters().resolveChoice(freshOp(), c, transaction, map("option", "TAKE", "weapon", "Club"));
		}
		case "level-up", "upgrade-level-up" -> {
			String transaction = scenario.equals("level-up")
					? ((Map<?, ?>) e.levelUps().begin(freshOp(), c, hero).get("transaction")).get("ref").toString()
					: e.db().read(tx -> "transaction:" + tx.queryOne("SELECT id FROM pending_transaction WHERE status='OPEN' AND kind='LEVEL_UP'").orElseThrow().id());
			e.levelUps().update(freshOp(), c, transaction, null, map("metamagic", List.of("Empowered Spell", "Quickened Spell")));
			e.levelUps().commit(freshOp(), c, transaction, null);
		}
		case "interrupted-travel" -> {
			TravelJourneyService journeys = new TravelJourneyService(e.db(), e.rules(), e.roller());
			journeys.resolveOpportunity(freshOp(), c, "NEGOTIATED");
			journeys.advance(freshOp(), c, 1500);
		}
		case "upgrade-recall" -> e.encounters().encounterState(c, "encounter:1", 100, "GM");
		case "upgrade-checkpoint" -> {
			String checkpoint = e.db().read(tx -> "checkpoint:" + tx.queryOne("SELECT id FROM checkpoint WHERE reason='Before the toll'").orElseThrow().id());
			e.checkpoints().restore(freshOp(), c, checkpoint, "Player requested a rewind.");
			e.sessions().bootstrap(freshOp(), c, null);
		}
		case "spell-potion" -> {
			e.spells().cast(freshOp(), c, hero, "Mage Armor", 1, List.of(hero), Map.of());
			e.runtime().applyRuntimeChange(freshOp(), c, hero, map("kind", "USE_ITEM", "item", "Potion of Healing", "reason", "Player request."));
		}
		case "commerce-sequence" -> {
			e.inventory().trade(freshOp(), c, hero, "BUY", "Torch", null, 2, null, null, null);
			String torch = e.db().read(tx -> "inventory:" + tx.queryOne("SELECT id FROM inventory_entry WHERE character_id=1 AND content_ref='srd5e:item/torch' ORDER BY id DESC LIMIT 1").orElseThrow().id());
			e.inventory().transfer(freshOp(), c, torch, "character:2", 1, "Give Mira a torch.");
		}
		case "session-recovery" -> {
			e.tacticalControl().set(freshOp(), c, List.of("character:2"), "PLAYER", "UNTIL_RELEASED", null, "Player request.");
			e.ledger().record(freshOp(), c, "PROMISE", "Richard promised Mira her silver brooch from Old Mill.", List.of(hero, "character:2"), "MAJOR", "PARTY_KNOWN", "PLAYER", null, null, null, null);
			e.sessions().suspend(freshOp(), c, "Richard promised Mira her silver brooch from Old Mill, and the player controls Mira.");
			e.sessions().bootstrap(freshOp(), c, null);
		}
		case "upgrade-death" -> e.runtime().transferControl(freshOp(), c, "character:2", "Continue with the survivor.");
		case "release-upgrade" -> {
			e.sessions().party(c, "FULL");
			String checkpoint = e.db().read(tx -> "checkpoint:" + tx.queryOne("SELECT id FROM checkpoint WHERE reason='Released campaign checkpoint'").orElseThrow().id());
			e.checkpoints().restore(freshOp(), c, checkpoint, "Restore the released checkpoint.");
			e.sessions().bootstrap(freshOp(), c, null);
		}
		default -> throw new IllegalArgumentException(scenario);
		}
	}

	private static String freshOp() { return java.util.UUID.randomUUID().toString(); }

	private static Map<String, Object> snapshot(Engine e) {
		return e.db().read(tx -> {
			var state = new LinkedHashMap<String, Object>();
			state.put("clock", GameTime.currentSeconds(tx, 1));
			state.put("harness", tx.get("campaign", 1).str("harness_state"));
			state.put("characters", tx.query("SELECT * FROM character WHERE campaign_id=1 ORDER BY id").stream()
					.map(r -> fields(r, "id", "name", "current_hp", "max_hp", "temp_hp", "life_state", "xp", "money_cp", "location_id")).toList());
			state.put("inventory", tx.query("SELECT * FROM inventory_entry ORDER BY id").stream()
					.map(r -> fields(r, "id", "character_id", "location_id", "content_ref", "custom_content_id", "quantity", "equipped")).toList());
			state.put("classes", tx.query("SELECT * FROM character_class ORDER BY id").stream()
					.map(r -> fields(r, "character_id", "class_ref", "level")).toList());
			state.put("metamagic", se.hirt.mcp.rpg.magic.Metamagic.known(tx, 1));
			state.put("spells", tx.query("SELECT * FROM character_trait WHERE kind IN ('SPELL_KNOWN', 'SPELL_PREPARED') ORDER BY id")
					.stream().map(r -> fields(r, "character_id", "kind", "content_ref")).toList());
			state.put("resources", tx.query("SELECT * FROM resource_state ORDER BY character_id, resource_ref").stream()
					.map(r -> fields(r, "character_id", "resource_ref", "current", "max")).toList());
			state.put("effects", tx.query("SELECT * FROM active_effect ORDER BY id").stream()
					.map(r -> fields(r, "character_id", "condition_ref", "source_content_ref", "payload_json")).toList());
			state.put("control", tx.query("SELECT * FROM tactical_control ORDER BY id").stream()
					.map(r -> fields(r, "character_id", "controller", "scope", "encounter_id")).toList());
			state.put("primary", tx.query("SELECT * FROM player_control_assignment WHERE active=1 ORDER BY id").stream()
					.map(r -> fields(r, "character_id")).toList());
			state.put("pending", tx.query("SELECT * FROM pending_transaction WHERE status='OPEN' ORDER BY id").stream()
					.map(r -> fields(r, "id", "kind", "status")).toList());
			state.put("journeys", tx.query("SELECT * FROM travel_journey ORDER BY id").stream()
					.map(r -> fields(r, "to_location_id", "status", "elapsed_seconds", "total_seconds")).toList());
			state.put("encounters", tx.query("SELECT * FROM encounter ORDER BY id").stream()
					.map(r -> fields(r, "id", "status", "round", "turn_participant_id")).toList());
			state.put("journal", tx.query("SELECT id, operation FROM journal_entry ORDER BY id").stream()
					.map(r -> fields(r, "id", "operation")).toList());
			state.put("reactions", tx.query("SELECT id, result_json FROM journal_entry WHERE operation='resolve_pending_choice' ORDER BY id")
					.stream().map(r -> map("id", r.id(), "attack", r.map("result_json").get("attack"))).toList());
			state.put("encounter_records", tx.query("SELECT id, record_json FROM encounter ORDER BY id").stream()
					.map(r -> fields(r, "id", "record_json")).toList());
			state.put("memories", tx.query("SELECT * FROM event WHERE campaign_id=1 ORDER BY id").stream()
					.map(r -> fields(r, "type", "summary", "episodic_detail")).toList());
			state.put("sessions", tx.query("SELECT * FROM session WHERE campaign_id=1 ORDER BY id").stream()
					.map(r -> map("ended", !r.isNull("ended_at"))).toList());
			state.put("checkpoint_count", tx.count("SELECT COUNT(*) FROM checkpoint"));
			state.put("schema_tables", tx.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").stream().map(r -> r.str("name")).toList());
			return state;
		});
	}

	private static Map<String, Object> fields(Row row, String ... keys) {
		var result = new LinkedHashMap<String, Object>();
		for (String key : keys) result.put(key, row.get(key));
		return result;
	}

	private static Map<String, Object> grade(String scenario, Map<String, Object> before, Map<String, Object> after,
			Map<String, Object> checkpoint, int step) {
		var checks = new LinkedHashMap<String, Boolean>();
		List<Map<String, Object>> journals = maps(after.get("journal"));
		long marker = maps(before.get("journal")).stream().mapToLong(r -> number(r.get("id"))).max().orElse(0);
		List<String> mutations = journals.stream().filter(r -> number(r.get("id")) > marker)
				.map(r -> r.get("operation").toString()).toList();
		Set<String> allowed = switch (scenario) {
		case "short-rest" -> Set.of("bootstrap_session", "perform_rest");
		case "companion-control" -> Set.of("bootstrap_session", "set_tactical_control");
		case "pending-status" -> Set.of("bootstrap_session");
		case "combat-reaction" -> Set.of("bootstrap_session", "resolve_pending_choice");
		case "level-up" -> Set.of("bootstrap_session", "begin_level_up", "update_level_up", "commit_level_up");
		case "upgrade-level-up" -> Set.of("bootstrap_session", "update_level_up", "commit_level_up");
		case "interrupted-travel" -> Set.of("bootstrap_session", "resolve_travel_opportunity", "continue_journey", "move_party");
		case "upgrade-recall" -> Set.of("bootstrap_session");
		case "upgrade-checkpoint" -> Set.of("bootstrap_session", "restore_checkpoint");
		case "spell-potion" -> Set.of("bootstrap_session", "cast_spell", "apply_runtime_change");
		case "commerce-sequence" -> Set.of("bootstrap_session", "trade", "transfer_item");
		case "session-recovery" -> Set.of("bootstrap_session", "set_tactical_control", "record_memory", "suspend_session", "update_relationship");
		case "upgrade-death" -> Set.of("bootstrap_session", "transfer_player_control");
		case "release-upgrade" -> Set.of("bootstrap_session", "restore_checkpoint");
		default -> throw new IllegalArgumentException(scenario);
		};
		// The new binary may refresh installed seed content once when opening an old database.
		checks.put("only_requested_mutations", mutations.stream().allMatch(operation -> operation.equals("refresh_ruleset") || allowed.contains(operation)));
		checks.put("bounded_seed_refresh", mutations.stream().filter("refresh_ruleset"::equals).count() <= 1);
		if (!scenario.equals("upgrade-death")) checks.put("primary_character_unchanged", equal(before, after, "primary"));
		if (scenario.equals("short-rest")) {
			checks.put("one_hour_elapsed", number(after.get("clock")) - number(before.get("clock")) == 3600);
			checks.put("exactly_two_hit_dice_spent", maps(after.get("resources")).stream().anyMatch(r -> number(r.get("character_id")) == 1
					&& "hit_dice".equals(r.get("resource_ref")) && number(r.get("current")) == 0 && number(r.get("max")) == 2));
			checks.put("richard_healed", number(maps(after.get("characters")).getFirst().get("current_hp")) > number(maps(before.get("characters")).getFirst().get("current_hp")));
			checks.put("mira_unchanged", Json.write(maps(before.get("characters")).get(1)).equals(Json.write(maps(after.get("characters")).get(1))));
		} else if (scenario.equals("companion-control")) {
			checks.put("mira_player_control_until_released", maps(after.get("control")).stream().anyMatch(r ->
					(r.get("character_id") == null || number(r.get("character_id")) == 2) && "PLAYER".equals(r.get("controller"))
					&& "UNTIL_RELEASED".equals(r.get("scope"))));
			checks.put("characters_unchanged", equal(before, after, "characters"));
			checks.put("no_time_elapsed", equal(before, after, "clock"));
		} else if (scenario.equals("pending-status")) {
			for (String key : List.of("clock", "characters", "effects", "resources", "control", "pending", "encounters"))
				checks.put(key + "_unchanged", equal(before, after, key));
		} else if (scenario.equals("combat-reaction")) {
			checks.put("pending_reaction_resolved", maps(after.get("pending")).isEmpty());
			checks.put("exactly_one_reaction", mutations.stream().filter("resolve_pending_choice"::equals).count() == 1);
			checks.put("club_attack_taken", maps(after.get("reactions")).stream().anyMatch(r -> number(r.get("id")) > marker
					&& r.get("attack") instanceof Map<?, ?> attack && "Club".equals(attack.get("attack"))
					&& "OPPORTUNITY_ATTACK".equals(attack.get("reaction"))));
			checks.put("encounter_still_running", maps(after.get("encounters")).stream().allMatch(r -> "RUNNING".equals(r.get("status"))));
		} else if (scenario.equals("level-up") || scenario.equals("upgrade-level-up")) {
			checks.put("richard_level_two", maps(after.get("classes")).stream().anyMatch(r -> number(r.get("character_id")) == 1 && number(r.get("level")) == 2));
			checks.put("no_open_transaction", maps(after.get("pending")).isEmpty());
			checks.put("chosen_metamagic", Set.of("empowered", "quickened").equals(new java.util.HashSet<>((List<?>) after.get("metamagic"))));
			checks.put("spells_retained", equal(before, after, "spells"));
			checks.put("no_time_elapsed", equal(before, after, "clock"));
		} else if (scenario.equals("interrupted-travel")) {
			Map<String, Object> trip = maps(after.get("journeys")).getFirst();
			checks.put("journey_completed", "COMPLETED".equals(trip.get("status")));
			checks.put("only_remaining_time_elapsed", number(after.get("clock")) - number(before.get("clock")) == 1500);
			checks.put("party_arrived", maps(after.get("characters")).stream().allMatch(r -> number(r.get("location_id")) == number(trip.get("to_location_id"))));
		} else if (scenario.equals("upgrade-recall")) {
			for (String key : List.of("clock", "characters", "effects", "resources", "control", "inventory", "encounter_records"))
				checks.put(key + "_preserved", equal(before, after, key));
		} else if (scenario.equals("upgrade-checkpoint")) {
			for (String key : List.of("clock", "characters", "effects", "resources", "inventory", "primary"))
				checks.put(key + "_restored", equal(checkpoint, after, key));
			checks.put("one_restore_only", mutations.stream().filter("restore_checkpoint"::equals).count() == 1);
		} else if (scenario.equals("spell-potion")) {
			checks.put("one_first_level_slot_spent", resource(before, "slot:1") - resource(after, "slot:1") == 1);
			checks.put("potion_consumed_once", quantity(before, 1, "potion-of-healing") - quantity(after, 1, "potion-of-healing") == 1);
			checks.put("healed", number(maps(after.get("characters")).getFirst().get("current_hp")) > number(maps(before.get("characters")).getFirst().get("current_hp")));
			checks.put("mage_armor_saved", Json.write(after.get("effects")).toLowerCase().contains("mage-armor"));
			checks.put("one_cast_only", mutations.stream().filter("cast_spell"::equals).count() == 1);
			checks.put("one_item_use_only", mutations.stream().filter("apply_runtime_change"::equals).count() == 1);
		} else if (scenario.equals("commerce-sequence")) {
			checks.put("richard_one_more_torch", quantity(after, 1, "torch") - quantity(before, 1, "torch") == 1);
			checks.put("mira_one_more_torch", quantity(after, 2, "torch") - quantity(before, 2, "torch") == 1);
			checks.put("paid_two_copper", number(maps(before.get("characters")).getFirst().get("money_cp")) - number(maps(after.get("characters")).getFirst().get("money_cp")) == 2);
			checks.put("no_time_elapsed", equal(before, after, "clock"));
		} else if (scenario.equals("session-recovery")) {
			checks.put("saved_mira_control", maps(after.get("control")).stream().anyMatch(r -> (r.get("character_id") == null || number(r.get("character_id")) == 2)
					&& "PLAYER".equals(r.get("controller")) && "UNTIL_RELEASED".equals(r.get("scope"))));
			checks.put("promise_recorded", Json.write(after.get("memories")).toLowerCase().contains("silver brooch"));
			checks.put("characters_unchanged", equal(before, after, "characters"));
			checks.put("no_time_elapsed", equal(before, after, "clock"));
			if (step >= 1) checks.put("session_was_suspended", mutations.contains("suspend_session"));
			if (step == 1) checks.put("session_closed", "SESSION_SUSPEND".equals(after.get("harness")));
			if (step >= 2) checks.put("session_resumed", "EXPLORATION".equals(after.get("harness")));
		} else if (scenario.equals("upgrade-death")) {
			checks.put("mira_is_primary", maps(after.get("primary")).size() == 1 && number(maps(after.get("primary")).getFirst().get("character_id")) == 2);
			checks.put("richard_stays_dead", "DEAD".equals(maps(after.get("characters")).getFirst().get("life_state")));
			checks.put("characters_preserved", equal(before, after, "characters"));
			checks.put("no_time_elapsed", equal(before, after, "clock"));
		} else if (scenario.equals("release-upgrade")) {
			Map<String, Object> expected = step == 0 ? before : checkpoint;
			for (String key : List.of("clock", "characters", "inventory", "primary", "checkpoint_count"))
				checks.put(key + "_preserved_or_restored", equal(expected, after, key));
			checks.put("old_resources_preserved_or_restored", containsRows(expected.get("resources"), after.get("resources")));
			checks.put("old_conditions_preserved_or_restored", equal(expected, after, "effects"));
			checks.put("old_memories_preserved", containsRows(expected.get("memories"), after.get("memories")));
			checks.put("old_tables_preserved", ((List<?>) after.get("schema_tables")).containsAll((List<?>) before.get("schema_tables")));
			checks.put("spatial_migrations_applied", ((List<?>) after.get("schema_tables")).containsAll(List.of("map_scene", "tactical_control", "travel_journey")));
			if (step >= 1) {
				checks.put("one_released_checkpoint_restore", mutations.stream().filter("restore_checkpoint"::equals).count() == 1);
				checks.put("rolled_back_promise_removed", !Json.write(after.get("memories")).toLowerCase().contains("copper key"));
			}
		}
		return map("passed", checks.values().stream().allMatch(Boolean::booleanValue), "checks", checks, "mutations", mutations);
	}

	private static long resource(Map<String, Object> state, String ref) {
		return maps(state.get("resources")).stream().filter(r -> number(r.get("character_id")) == 1 && ref.equals(r.get("resource_ref")))
				.mapToLong(r -> number(r.get("current"))).sum();
	}

	private static boolean containsRows(Object original, Object current) {
		// SQLite driver numbers and JSON-decoded numbers may use different Java Number classes.
		List<String> rows = maps(current).stream().map(Json::write).toList();
		return maps(original).stream().map(Json::write).allMatch(rows::contains);
	}

	private static long quantity(Map<String, Object> state, long character, String slug) {
		return maps(state.get("inventory")).stream().filter(r -> number(r.get("character_id")) == character && String.valueOf(r.get("content_ref")).endsWith("/" + slug))
				.mapToLong(r -> number(r.get("quantity"))).sum();
	}

	private static boolean equal(Map<String, Object> before, Map<String, Object> after, String key) {
		return Json.write(before.get(key)).equals(Json.write(after.get(key)));
	}

	private static long number(Object value) { return value instanceof Number n ? n.longValue() : Long.MIN_VALUE; }

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }
}
