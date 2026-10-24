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
package se.hirt.mcp.rpg.rules;

import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cumulative Exhaustion (SRD 5.2.1): the character column is the level, and the condition exists
 * while that level is positive. Older saves represented it as an ordinary effect; those rows imply
 * one level until a journaled mutation folds them into the column.
 */
public final class Exhaustion {

	public static final String CONDITION_REF = "srd5e:condition/exhaustion";

	private Exhaustion() {
	}

	/** Current level, including legacy effects restored by an older checkpoint. */
	public static int level(Tx tx, long characterId) {
		return level(tx, tx.get("character", characterId));
	}

	/** A legacy effect has no recorded level and therefore supplies a minimum of one. */
	public static int level(Tx tx, Row character) {
		int stored = storedLevel(character);
		return stored > 0 ? stored
				: tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id = ? AND condition_ref = ?",
						character.id(), CONDITION_REF) > 0 ? 1 : 0;
	}

	/**
	 * Lazily folds legacy effects into the column. All changes use the journal so a checkpoint
	 * restore can recover the exact old representation as well as its effective level.
	 */
	public static int normalize(Tx tx, long characterId) {
		Row character = tx.get("character", characterId);
		List<Row> legacy = tx.query("SELECT id FROM active_effect WHERE character_id = ? AND condition_ref = ?",
				characterId, CONDITION_REF);
		int level = Math.max(storedLevel(character), legacy.isEmpty() ? 0 : 1);
		if (!legacy.isEmpty() || character.lng("exhaustion") == null || character.lng("exhaustion") != level) {
			tx.update("character", characterId, Map.of("exhaustion", level, "revision", character.lng("revision") + 1));
			for (Row effect : legacy) {
				tx.delete("active_effect", effect.id());
			}
		}
		return level;
	}

	/** Removes up to the requested number of levels; returns how many were removed. */
	public static int reduce(Tx tx, long characterId, int levels) {
		int before = normalize(tx, characterId);
		int removed = Math.min(before, Math.max(0, levels));
		if (removed > 0) {
			Row character = tx.get("character", characterId);
			tx.update("character", characterId,
					Map.of("exhaustion", before - removed, "revision", character.lng("revision") + 1));
		}
		return removed;
	}

	/** Gains one level, respecting immunity and applying death when the level reaches six. */
	public static Map<String, Object> gain(Tx tx, RulesData rules, Row character, String reason) {
		Row current = tx.get("character", character.id());
		int before = level(tx, current);
		var result = new LinkedHashMap<String, Object>();
		result.put("exhaustion_before", before);
		result.put("exhaustion", before);
		if ("DEAD".equals(current.str("life_state"))) {
			result.put("skipped", "already dead");
			return result;
		}
		if (immune(tx, rules, current)) {
			result.put("immune", "EXHAUSTION");
			result.put("skipped", "immune to Exhaustion");
			return result;
		}
		normalize(tx, current.id());
		current = tx.get("character", current.id());
		int after = Math.min(6, before + 1);
		tx.update("character", current.id(), Map.of("exhaustion", after, "revision", current.lng("revision") + 1));
		result.put("exhaustion", after);
		if (after == 6) {
			long campaignId = current.lng("campaign_id");
			boolean partyMember = RuntimeService.isPartyMember(tx, campaignId, current.id());
			Effects.breakConcentration(tx, current.id());
			Effects.removeAll(tx, current.id());
			var cols = new LinkedHashMap<String, Object>();
			cols.put("current_hp", 0);
			cols.put("life_state", "DEAD");
			cols.put("death_saves_json", null);
			cols.put("revision", tx.get("character", current.id()).lng("revision") + 1);
			tx.update("character", current.id(), cols);
			RuntimeService.endMemberships(tx, campaignId, current.id(), "DEAD");
			RuntimeService.markDefeatedInRunningEncounter(tx, current.id());
			LedgerService.append(tx, campaignId, new LedgerService.EventSpec("CHARACTER_DIED",
					current.str("name") + " died from Exhaustion"
							+ (reason == null || reason.isBlank() ? "" : " (" + reason + ")") + ".",
					List.of(current.id()), partyMember ? "CRITICAL" : "NOTABLE", "PARTY_KNOWN",
					"MECHANICAL_CONSEQUENCE", null, current.lng("location_id"), null, Map.of("exhaustion", after)));
			result.put("died", true);
			result.put("life_state", "DEAD");
		}
		return result;
	}

	/** Amount subtracted from each D20 Test. */
	public static int penalty(Tx tx, long characterId) {
		return 2 * level(tx, characterId);
	}

	/** Walking speed after equipped/effect bonuses and Exhaustion's five feet per level. */
	public static int speed(Tx tx, RulesData rules, Row character) {
		return Math.max(0, character.intOr("speed", 0) + Effects.modifiers(tx, rules, character.id()).speedBonus
				- 5 * level(tx, character));
	}

	private static int storedLevel(Row character) {
		Long stored = character.lng("exhaustion");
		return stored == null ? 0 : (int) Math.max(0, Math.min(6, stored));
	}

	private static boolean immune(Tx tx, RulesData rules, Row character) {
		if (Effects.modifiers(tx, rules, character.id()).immuneConditions.contains("EXHAUSTION")) {
			return true;
		}
		if (rules != null && !character.isNull("origin_content_ref")) {
			Object conditions = rules.find(character.str("origin_content_ref"))
					.map(def -> def.payload().get("condition_immunities")).orElse(null);
			if (conditions instanceof List<?> list) {
				return list.stream().anyMatch(condition -> "EXHAUSTION".equalsIgnoreCase(String.valueOf(condition)));
			}
		}
		return false;
	}
}
