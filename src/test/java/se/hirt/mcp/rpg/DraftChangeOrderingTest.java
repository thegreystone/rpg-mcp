/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class DraftChangeOrderingTest {
	@Test
	void humanSoldierIsNotOfferedItsNonrepeatableBackgroundFeatAgain() throws Exception {
		try (Engine e = engine(tempDb("run3-duplicate-origin-offer"))) {
			String campaign = campaign(e);
			String character = e.characters()
					.createDraft(op(), campaign,
							map("name", "Tam", "species", "Human", "class", "Fighter", "background", "Soldier"), true)
					.get("character").toString();
			var offered = e.characters().choices(campaign, "FEAT", character);
			assertFalse(castList(offered.get("feats")).stream()
					.anyMatch(feat -> "Savage Attacker".equals(feat.get("label"))));
			var decisions = castList(offered.get("decisions"));
			var origin = decisions.stream().filter(decision -> "origin_feat".equals(decision.get("id"))).findFirst()
					.orElseThrow();
			assertFalse(Json.write(origin).contains("Savage Attacker"));
			assertTrue(Json.write(e.characters().choices(campaign, "FEAT", null)).contains("Savage Attacker"),
					"The full catalog remains available without a character filter.");
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> castList(Object value) {
		return (List<Map<String, Object>>) value;
	}

	@Test
	void combinedMcpUpdatesResolveOwnersBeforeAnswersRegardlessOfJsonFieldOrder() throws Exception {
		Map<String, Object> forward = completeHuman();
		Map<String, Object> expected = null;
		for (Map<String, Object> changes : List.of(forward, reverse(forward))) {
			try (Engine e = engine(tempDb("draft-json-order"))) {
				String campaign = campaign(e);
				String character = (String) e.characters().createDraft(op(), campaign, null, true).get("character");
				RpgTools tools = new RpgTools();
				tools.engine = e;
				String operation = op();
				var response = tools.updateCharacterDraft(operation, campaign, character, Optional.empty(), changes);
				assertFalse(response.isError(), response.firstContent().asText().text());
				Map<String, Object> validated = e.characters().validateDraft(campaign, character);
				assertEquals(true, validated.get("valid"), validated.toString());
				Map<String, Object> signature = signature(e, campaign, character);
				if (expected == null)
					expected = signature;
				else
					assertEquals(expected, signature);
				// A replay and a fresh application cannot duplicate grants or compound the background increase.
				var replay = tools.updateCharacterDraft(operation, campaign, character, Optional.empty(), changes);
				assertFalse(replay.isError());
				assertEquals(true,
						cast(Json.readMap(replay.firstContent().asText().text()).get("result")).get("replayed"));
				e.characters().updateDraft(op(), campaign, character, null, reverse(changes));
				assertEquals(signature, signature(e, campaign, character));
				long id = Ref.id(character, Ref.CHARACTER);
				assertEquals(16, e.db().read(tx -> tx.get("character", id).intOr("dex_score", 0)).intValue());
				assertEquals(14, e.db().read(tx -> tx.get("character", id).intOr("con_score", 0)).intValue());
				assertEquals(0L, e.db().read(tx -> tx.count(
						"SELECT COUNT(*) FROM (SELECT kind,content_ref FROM character_trait WHERE character_id=? GROUP BY kind,content_ref HAVING COUNT(*)>1)",
						id)).longValue());
			}
		}
	}

	@Test
	void initialCreationResolvesLineageDefaultAbilityAndToolAndClassSpellAnswers() throws Exception {
		Map<String, Object> forward = map("name", "Nyx", "species", "Tiefling", "class", "Warlock", "background",
				"Soldier", "ability_scores", map("CHA", 15, "CON", 14, "DEX", 13, "INT", 12, "WIS", 10, "STR", 8),
				"background_ability_scores", map("STR", 2, "CON", 1), "background_tool", "srd5e:item/gaming-set-dice",
				"species_choice", "infernal", "skills", List.of("Arcana", "Deception"), "cantrips",
				List.of("Eldritch Blast", "Minor Illusion"), "spells", List.of("Hex", "Charm Person"), "personality",
				"Curious and patient.");
		Map<String, Object> expected = null;
		for (Map<String, Object> initial : List.of(forward, reverse(forward))) {
			try (Engine e = engine(tempDb("draft-initial-order"))) {
				String campaign = campaign(e);
				String character = (String) e.characters().createDraft(op(), campaign, initial, true).get("character");
				Map<String, Object> validated = e.characters().validateDraft(campaign, character);
				assertEquals(true, validated.get("valid"), validated.toString());
				Map<String, Object> signature = signature(e, campaign, character);
				if (expected == null)
					expected = signature;
				else
					assertEquals(expected, signature);
				assertEquals("CHA", e.db().read(tx -> tx.queryOne(
						"SELECT * FROM character_trait WHERE character_id=? AND content_ref LIKE '%#fiendish_legacy'",
						Ref.id(character, Ref.CHARACTER)).orElseThrow().map("payload_json").get("ability")));
			}
		}
	}

	private static Map<String, Object> completeHuman() {
		return map("name", "Irena", "personality", "Curious and patient.", "species", "Human", "class", "Sorcerer",
				"background", "Criminal", "ability_scores",
				map("STR", 8, "DEX", 14, "CON", 13, "INT", 10, "WIS", 12, "CHA", 15), "background_ability_scores",
				map("DEX", 2, "CON", 1), "species_skill", "Athletics", "origin_feat", "Skilled", "feat_choices",
				map("feat", "Skilled", "proficiencies", List.of("History", "Nature", "Medicine")), "skills",
				List.of("Arcana", "Persuasion"), "cantrips",
				List.of("Fire Bolt", "Mage Hand", "Ray of Frost", "Prestidigitation"), "spells",
				List.of("Magic Missile", "Shield"), "starting_equipment", "B", "background_equipment", "B");
	}

	private static String campaign(Engine e) {
		String campaign = (String) e.campaigns().create(op(), null, null).get("campaign");
		e.campaigns().updateSetup(op(), campaign, null, map("content_profile", "PEGI_16", "experience", "SURPRISE_ME",
				"rules", map("ability_generation", "STANDARD_ARRAY"), "continuation", "CHECKPOINT", "party",
				"SURPRISE_ME", "adventure",
				map("premise", "An expedition.", "opening_location", "Gate", "immediate_goal", "Find a guide.")));
		return campaign;
	}

	private static Map<String, Object> signature(Engine e, String campaign, String character) {
		Map<String, Object> sheet = e.characters().characterSheet(campaign, character, "FULL");
		var result = new LinkedHashMap<String, Object>();
		for (String key : List.of("abilities", "classes", "background", "skills", "tool_proficiencies", "feats",
				"species_traits", "spellcasting", "creation", "starting_equipment"))
			result.put(key, sheet.get(key));
		return result;
	}

	private static Map<String, Object> reverse(Map<String, Object> source) {
		var keys = new ArrayList<>(source.keySet());
		Collections.reverse(keys);
		var result = new LinkedHashMap<String, Object>();
		for (String key : keys)
			result.put(key, source.get(key));
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Object v) {
		return (Map<String, Object>) v;
	}
}
