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

import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generic effects system (DESIGN.md §14, DOMAIN_MODEL.md §9): active effects carry a condition
 * and/or a modifier spec, a duration, and an optional concentration link. This class merges the
 * mechanical modifiers for a character and expires effects on time and round boundaries (I-28,
 * I-29).
 */
public final class Effects {

	private Effects() {
	}

	/** Merged mechanical modifiers from every active effect on a character. */
	public static final class Modifiers {
		public Integer acBase;
		public int acBonus;
		public Integer acFloor;
		public final List<String> attackBonusDice = new ArrayList<>();
		public int attackBonus;
		public final List<String> saveBonusDice = new ArrayList<>();
		public int saveBonus;
		public final List<String> checkBonusDice = new ArrayList<>();
		public final List<Map<String, Object>> damageBonus = new ArrayList<>();
		public boolean disadvantageOnAttacksAgainst;
		public boolean advantageOnAttacksAgainst;
		public final List<String> immuneConditions = new ArrayList<>();
		public final List<String> resistances = new ArrayList<>();
		public int speedBonus;
		public boolean deathWard;
		/**
		 * Signed change to the hit point maximum already applied to the stored maximum (Aid +5, a
		 * Life Drain −16).
		 */
		public int maxHp;
	}

	@SuppressWarnings("unchecked")
	public static Modifiers modifiers(Tx tx, long characterId) {
		var m = new Modifiers();
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ?", characterId)) {
			if (e.isNull("modifier_json")) {
				continue;
			}
			Map<String, Object> mod = e.map("modifier_json");
			if (mod.get("ac_base") instanceof Number n) {
				m.acBase = m.acBase == null ? n.intValue() : Math.max(m.acBase, n.intValue());
			}
			if (mod.get("ac_bonus") instanceof Number n) {
				m.acBonus += n.intValue();
			}
			if (mod.get("ac_floor") instanceof Number n) {
				m.acFloor = m.acFloor == null ? n.intValue() : Math.max(m.acFloor, n.intValue());
			}
			if (mod.get("attack_bonus_dice") instanceof String s) {
				m.attackBonusDice.add(s);
			}
			if (mod.get("attack_bonus") instanceof Number n) {
				m.attackBonus += n.intValue();
			}
			if (mod.get("save_bonus_dice") instanceof String s) {
				m.saveBonusDice.add(s);
			}
			if (mod.get("save_bonus") instanceof Number n) {
				m.saveBonus += n.intValue();
			}
			if (mod.get("check_bonus_dice") instanceof String s) {
				m.checkBonusDice.add(s);
			}
			if (mod.get("damage_bonus_dice") instanceof String s) {
				var d = new LinkedHashMap<String, Object>();
				d.put("dice", s);
				d.put("type", mod.getOrDefault("damage_bonus_type", "force"));
				d.put("against", mod.get("against_target_id"));
				d.put("source", e.str("source_description"));
				m.damageBonus.add(d);
			}
			if (Boolean.TRUE.equals(mod.get("disadvantage_on_attacks_against"))) {
				m.disadvantageOnAttacksAgainst = true;
			}
			if (Boolean.TRUE.equals(mod.get("advantage_on_attacks_against"))) {
				m.advantageOnAttacksAgainst = true;
			}
			if (mod.get("immune_conditions") instanceof List<?> l) {
				for (Object o : l) {
					m.immuneConditions.add(o.toString().toUpperCase());
				}
			}
			if (mod.get("resistance") instanceof String s) {
				m.resistances.add(s.toLowerCase());
			}
			if (mod.get("speed_bonus") instanceof Number n) {
				m.speedBonus += n.intValue();
			}
			if (Boolean.TRUE.equals(mod.get("death_ward"))) {
				m.deathWard = true;
			}
			if (mod.get("max_hp") instanceof Number n) {
				m.maxHp += n.intValue();
			}
		}
		return m;
	}

	/**
	 * Ends one effect. A {@code max_hp} modifier was applied to the character's maximum when the
	 * effect began (so the stored maximum is always the one in force and the I-13 check holds);
	 * ending it takes the change back and clamps current HP to the new maximum (SRD 5.2.1 "Hit
	 * Point Maximum").
	 */
	public static void end(Tx tx, Row effect) {
		if (!effect.isNull("modifier_json") && effect.map("modifier_json").get("max_hp") instanceof Number n
				&& n.intValue() != 0) {
			tx.find("character", effect.lng("character_id")).ifPresent(c -> {
				int max = Math.max(0, c.intOr("max_hp", 0) - n.intValue());
				var cols = new LinkedHashMap<String, Object>();
				cols.put("max_hp", max);
				cols.put("current_hp", Math.min(c.intOr("current_hp", 0), max));
				cols.put("revision", c.lng("revision") + 1);
				tx.update("character", c.id(), cols);
			});
		}
		tx.delete("active_effect", effect.id());
	}

	/**
	 * Ends every effect on a character (death, a revival by fiat), reversing maximum-HP changes on
	 * the way.
	 */
	public static int removeAll(Tx tx, long characterId) {
		int removed = 0;
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ?", characterId)) {
			end(tx, e);
			removed++;
		}
		return removed;
	}

	/**
	 * Ends the effects that last until a Long Rest ({@code duration.until = LONG_REST}); returns
	 * their descriptions.
	 */
	public static List<String> expireOnLongRest(Tx tx, long characterId) {
		var ended = new ArrayList<String>();
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ? AND duration_json IS NOT NULL",
				characterId)) {
			if ("LONG_REST".equals(e.map("duration_json").get("until"))) {
				ended.add(e.str("source_description"));
				end(tx, e);
			}
		}
		return ended;
	}

	/** Adds an effect row; returns its id. */
	public static long add(
		Tx tx, long campaignId, long characterId, Long sourceCharacterId, String sourceContentRef, String description,
		String conditionRef, Map<String, Object> modifiers, Map<String, Object> duration, Long concentrationCharacterId,
		String stackingKey, String provenance) {
		// Same-source stacking: replace an existing effect with the same stacking key on this character.
		if (stackingKey != null) {
			for (Row old : tx.query("SELECT * FROM active_effect WHERE character_id = ? AND stacking_key = ?",
					characterId, stackingKey)) {
				end(tx, old);
			}
		}
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("character_id", characterId);
		cols.put("source_character_id", sourceCharacterId);
		cols.put("source_content_ref", sourceContentRef);
		cols.put("source_description", description);
		cols.put("provenance", provenance);
		cols.put("condition_ref", conditionRef);
		cols.put("modifier_json", modifiers == null || modifiers.isEmpty() ? null : Json.write(modifiers));
		cols.put("start_seq", GameTime.currentSeq(tx, campaignId));
		cols.put("start_journal_id", tx.journalId());
		cols.put("duration_json", duration == null ? null : Json.write(duration));
		cols.put("concentration_character_id", concentrationCharacterId);
		cols.put("stacking_key", stackingKey);
		return tx.insert("active_effect", cols);
	}

	private static final java.util.regex.Pattern DURATION_TEXT = java.util.regex.Pattern
			.compile("(?i)\\s*(\\d+)\\s*(round|minute|min|hour|day)s?\\s*");

	/**
	 * A duration given by the GM (ADD_CONDITION), normalized to the shapes the engine expires: a
	 * number is minutes; "3 rounds", "1 minute", "2 hours", "1 day" parse; "long rest" / LONG_REST,
	 * "restored" / RESTORED and "start of turn" / START_OF_TURN are milestones; a map with
	 * expires_seq, expires_round or until passes through; {minutes: n} / {rounds: n} / {hours: n}
	 * are converted. Null (or "until removed") means no expiry.
	 */
	public static Map<String, Object> parseDuration(Tx tx, long campaignId, Row c, Object duration, String label) {
		if (duration == null) {
			return null;
		}
		Long encounterId = c.lng("encounter_id");
		long round = encounterId == null ? 0 : tx.get("encounter", encounterId).lng("round");
		if (duration instanceof Number n) {
			return minutes(tx, campaignId, Math.max(1, n.longValue()), label);
		}
		if (duration instanceof Map<?, ?> given) {
			var m = new LinkedHashMap<String, Object>();
			for (var e : given.entrySet()) {
				m.put(String.valueOf(e.getKey()), e.getValue());
			}
			if (m.get("minutes") instanceof Number n) {
				return minutes(tx, campaignId, Math.max(1, n.longValue()), label);
			}
			if (m.get("hours") instanceof Number n) {
				return minutes(tx, campaignId, Math.max(1, n.longValue()) * 60, label);
			}
			if (m.get("rounds") instanceof Number n) {
				return rounds(tx, campaignId, encounterId, round, Math.max(1, n.longValue()), label);
			}
			if (m.get("until") != null) {
				String until = String.valueOf(m.get("until")).toUpperCase();
				if (!java.util.Set.of("LONG_REST", "RESTORED", "START_OF_TURN", "HP_ABOVE_ZERO").contains(until)) {
					throw se.hirt.mcp.rpg.protocol.RpgException.invalidArgument(
							"duration.until must be LONG_REST, RESTORED, START_OF_TURN or HP_ABOVE_ZERO.");
				}
				m.put("until", until);
				m.putIfAbsent("label", label);
				return m;
			}
			if (m.get("expires_seq") instanceof Number || m.get("expires_round") instanceof Number) {
				m.putIfAbsent("label", label);
				return m;
			}
			throw se.hirt.mcp.rpg.protocol.RpgException.invalidArgument(
					"duration must be minutes (a number or '1 minute'), rounds ('3 rounds'), hours, days, 'long rest', 'restored', 'start of turn', or a map with minutes/rounds/hours/until.");
		}
		String text = String.valueOf(duration).trim();
		String key = text.toUpperCase().replace(' ', '_').replace('-', '_');
		switch (key) {
		case "LONG_REST", "UNTIL_LONG_REST" -> {
			return until("LONG_REST", label);
		}
		case "RESTORED", "UNTIL_RESTORED" -> {
			return until("RESTORED", label);
		}
		case "START_OF_TURN", "UNTIL_START_OF_TURN", "UNTIL_START_OF_NEXT_TURN" -> {
			return until("START_OF_TURN", label);
		}
		case "UNTIL_REMOVED", "INDEFINITE", "PERMANENT", "NONE" -> {
			return null;
		}
		default -> {
			var m = DURATION_TEXT.matcher(text);
			if (!m.matches()) {
				throw se.hirt.mcp.rpg.protocol.RpgException.invalidArgument("Cannot read the duration '" + text
						+ "': use '3 rounds', '1 minute', '2 hours', '1 day', 'long rest', 'restored', 'start of turn', 'until removed', a number of minutes, or a map.");
			}
			long n = Long.parseLong(m.group(1));
			return switch (m.group(2).toLowerCase()) {
			case "round" -> rounds(tx, campaignId, encounterId, round, n, label);
			case "hour" -> minutes(tx, campaignId, n * 60, label);
			case "day" -> minutes(tx, campaignId, n * 60 * 24, label);
			default -> minutes(tx, campaignId, n, label);
			};
		}
		}
	}

	private static Map<String, Object> until(String milestone, String label) {
		var d = new LinkedHashMap<String, Object>();
		d.put("kind", "UNTIL");
		d.put("label", label);
		d.put("until", milestone);
		return d;
	}

	/** Duration in game minutes from now. */
	public static Map<String, Object> minutes(Tx tx, long campaignId, long minutes, String label) {
		var d = new LinkedHashMap<String, Object>();
		d.put("kind", "MINUTES");
		d.put("label", label);
		d.put("expires_seq", GameTime.currentSeq(tx, campaignId) + minutes);
		return d;
	}

	/**
	 * Duration in encounter rounds (expires when that round begins); outside encounters, one
	 * minute.
	 */
	public static Map<String, Object> rounds(
		Tx tx, long campaignId, Long encounterId, long currentRound, long rounds, String label) {
		var d = new LinkedHashMap<String, Object>();
		d.put("label", label);
		if (encounterId != null) {
			d.put("kind", "ROUNDS");
			d.put("encounter", encounterId);
			d.put("expires_round", currentRound + rounds);
		} else {
			d.put("kind", "MINUTES");
			d.put("expires_seq", GameTime.currentSeq(tx, campaignId) + Math.max(1, rounds / 10));
		}
		return d;
	}

	/** Removes effects whose game-time expiry has passed; returns the number removed. */
	public static int expireByTime(Tx tx, long campaignId, long nowSeq) {
		int removed = 0;
		for (Row e : tx.query("SELECT * FROM active_effect WHERE campaign_id = ? AND duration_json IS NOT NULL",
				campaignId)) {
			Map<String, Object> d = e.map("duration_json");
			if (d.get("expires_seq") instanceof Number n && n.longValue() <= nowSeq) {
				end(tx, e);
				removed++;
			}
		}
		return removed;
	}

	/** Removes round-scoped effects of an encounter that expire at or before the given round. */
	public static int expireByRound(Tx tx, long encounterId, long round) {
		int removed = 0;
		for (Row e : tx.query(
				"SELECT a.* FROM active_effect a JOIN character c ON c.id = a.character_id WHERE c.encounter_id = ? AND a.duration_json IS NOT NULL",
				encounterId)) {
			Map<String, Object> d = e.map("duration_json");
			if (d.get("expires_round") instanceof Number n && n.longValue() <= round) {
				end(tx, e);
				removed++;
			}
		}
		return removed;
	}

	/**
	 * Ends every effect the character is concentrating on (I-29). Returns the removed effects'
	 * descriptions.
	 */
	public static List<String> breakConcentration(Tx tx, long characterId) {
		var ended = new ArrayList<String>();
		for (Row e : tx.query("SELECT * FROM active_effect WHERE concentration_character_id = ?", characterId)) {
			ended.add(e.str("source_description"));
			tx.delete("active_effect", e.id());
		}
		return ended;
	}

	public static boolean isConcentrating(Tx tx, long characterId) {
		return tx.count("SELECT COUNT(*) FROM active_effect WHERE concentration_character_id = ?", characterId) > 0;
	}

	public static List<Map<String, Object>> view(Tx tx, long characterId) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ? ORDER BY id", characterId)) {
			var m = new LinkedHashMap<String, Object>();
			String ref = e.str("condition_ref");
			m.put("condition", ref == null ? null : ref.substring(ref.lastIndexOf('/') + 1).toUpperCase());
			m.put("source", e.str("source_description"));
			if (!e.isNull("modifier_json")) {
				m.put("modifiers", e.map("modifier_json"));
			}
			if (!e.isNull("duration_json")) {
				m.put("duration", e.map("duration_json"));
			}
			if (!e.isNull("concentration_character_id")) {
				m.put("concentration_by", "character:" + e.lng("concentration_character_id"));
			}
			out.add(m);
		}
		return out;
	}
}
