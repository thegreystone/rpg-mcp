/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.session.GameTime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static se.hirt.mcp.rpg.TestCampaigns.*;

/** Uses only the API and database columns shipped in v0.2.0; no newer migrations run here. */
public final class LegacyFixture {

	public static void main(String[] args) throws Exception {
		if (!args[0].equals("create") || !args[1].equals("release-upgrade"))
			throw new IllegalArgumentException("The release fixture only creates release-upgrade campaigns.");
		Path directory = Path.of(args[2]).toAbsolutePath(), database = directory.resolve("rpg.db");
		Files.createDirectories(directory);
		if (Files.exists(database)) throw new IllegalStateException("Refusing to replace " + database);
		try (Engine e = new Engine(database, new ScriptedRollService(Long.parseLong(args[3])), "released-fixture")) {
			String c = committedCampaign(e), hero = "character:1";
			e.sessions().bootstrap(op(), c, null);
			e.characters().updateCharacter(op(), c, hero, null, map("name", "Richard"));
			String mira = e.runtime().materialize(op(), c, "Guard", "Mira", null, null, null, null, false).get("character").toString();
			e.party().updateMembership(op(), c, mira, "JOIN", "An established traveling companion.");
			e.inventory().grantLoot(op(), c, hero, List.of(map("item", "Torch", "quantity", 2), map("item", "Potion of Healing", "quantity", 1)), map("gp", 5), "QUEST", "Supplies earned before upgrading.");
			e.checkpoints().create(op(), c, "Released campaign checkpoint");
			Files.writeString(directory.resolve("checkpoint-state.json"), Json.write(snapshot(e)));
			e.runtime().applyRuntimeChange(op(), c, hero, map("kind", "DAMAGE", "amount", 3, "reason", "An earlier scrape."));
			e.runtime().applyRuntimeChange(op(), c, hero, map("kind", "USE_RESOURCE", "resource", "spell_slot:1", "amount", 1, "reason", "An earlier spell."));
			e.runtime().applyRuntimeChange(op(), c, hero, map("kind", "ADD_CONDITION", "condition", "POISONED", "duration", "10 minutes", "reason", "An earlier exposure."));
			e.ledger().record(op(), c, "PROMISE", "Richard promised Mira he would recover the copper key from Old Mill.", List.of(hero, mira), "MAJOR", "PARTY_KNOWN", "PLAYER", null, null, null, null);
			e.inventory().trade(op(), c, hero, "BUY", "Torch", null, 1, null, null, null);
			e.sessions().suspend(op(), c, "Richard and Mira remain at Bellhaven. Richard promised Mira the copper key from Old Mill. Richard is injured and Poisoned and has spent one first-level slot.");
			Files.writeString(directory.resolve("before.json"), Json.write(snapshot(e)));
		}
	}

	private static Map<String, Object> snapshot(Engine e) {
			return e.db().read(tx -> {
				var result = new LinkedHashMap<String, Object>();
				Row clock = GameTime.clock(tx, 1);
				result.put("clock", clock.lng("seq") * 60 + clock.intOr("second_offset", 0));
				result.put("characters", tx.query("SELECT * FROM character ORDER BY id").stream().map(r -> fields(r, "id", "name", "current_hp", "max_hp", "temp_hp", "life_state", "xp", "money_cp", "location_id")).toList());
				result.put("inventory", tx.query("SELECT * FROM inventory_entry ORDER BY id").stream().map(r -> fields(r, "id", "character_id", "location_id", "content_ref", "custom_content_id", "quantity", "equipped")).toList());
				result.put("resources", tx.query("SELECT * FROM resource_state ORDER BY character_id, resource_ref").stream().map(r -> fields(r, "character_id", "resource_ref", "current", "max")).toList());
				result.put("effects", tx.query("SELECT * FROM active_effect ORDER BY id").stream().map(r -> fields(r, "character_id", "condition_ref", "source_content_ref", "payload_json")).toList());
				result.put("primary", tx.query("SELECT * FROM player_control_assignment WHERE active=1 ORDER BY id").stream().map(r -> fields(r, "character_id")).toList());
				result.put("journal", tx.query("SELECT id, operation FROM journal_entry ORDER BY id").stream().map(r -> fields(r, "id", "operation")).toList());
				result.put("memories", tx.query("SELECT * FROM event ORDER BY id").stream().map(r -> fields(r, "type", "summary", "episodic_detail")).toList());
				result.put("checkpoint_count", tx.count("SELECT COUNT(*) FROM checkpoint"));
				result.put("schema_tables", tx.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").stream().map(r -> r.str("name")).toList());
				return result;
			});
	}

	private static Map<String, Object> fields(Row row, String ... keys) {
		var result = new LinkedHashMap<String, Object>();
		for (String key : keys) result.put(key, row.get(key));
		return result;
	}
}
