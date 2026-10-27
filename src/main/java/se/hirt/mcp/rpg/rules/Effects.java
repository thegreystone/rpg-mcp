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
import java.util.Optional;

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
		/** Flat bonus to damage rolls (a weapon-bound Magic Weapon's +N, folded per weapon). */
		public int damageBonusFlat;
		/**
		 * Overrides a weapon-bound spell imposes (Shillelagh): the die, the ability used, the
		 * damage type.
		 */
		public String weaponDie;
		public String useAbility;
		public String weaponDamageType;
	}

	/**
	 * Modifiers from active effects alone; {@link #modifiers(Tx, RulesData, long)} adds equipped
	 * magic items.
	 */
	public static Modifiers modifiers(Tx tx, long characterId) {
		return modifiers(tx, null, characterId);
	}

	/**
	 * Merged modifiers from every active effect plus every equipped magic item whose definition
	 * carries {@code modifiers} in the same vocabulary (a Ring of Protection's AC and saving-throw
	 * bonus; RULES_ENGINE.md §10). With {@code rules} null the items are skipped.
	 */
	public static Modifiers modifiers(Tx tx, se.hirt.mcp.rpg.content.RulesData rules, long characterId) {
		var m = new Modifiers();
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ?", characterId)) {
			if (e.isNull("modifier_json")) {
				continue;
			}
			Map<String, Object> mod = e.map("modifier_json");
			if (mod.get("weapon_entry_id") != null) {
				continue; // bound to one carried weapon: see weaponModifiers
			}
			fold(m, mod, e.str("source_description"));
		}
		if (rules != null) {
			for (Row e : tx.query("SELECT * FROM inventory_entry WHERE character_id = ? AND equipped = 1 ORDER BY id",
					characterId)) {
				var item = se.hirt.mcp.rpg.content.ContentService.itemForEntry(tx, rules, e);
				// Damage dice on a weapon or ammunition ride on attacks with that item (Combat), not on everything the
				// wearer does; the rest (AC, saves, speed, resistance) applies while the item is equipped.
				var mod = new LinkedHashMap<>(se.hirt.mcp.rpg.inventory.MagicItems.modifiers(item.payload()));
				mod.remove("damage_bonus_dice");
				mod.remove("damage_bonus_type");
				if (!mod.isEmpty()) {
					fold(m, mod, item.name());
				}
			}
		}
		return m;
	}

	/**
	 * Modifiers of the effects bound to one carried weapon (Magic Weapon on that sword, Shillelagh
	 * on that staff): they ride only on attacks with it, never on the wielder's other attacks.
	 */
	public static Modifiers weaponModifiers(Tx tx, long characterId, long entryId) {
		var m = new Modifiers();
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ? ORDER BY id", characterId)) {
			if (e.isNull("modifier_json")) {
				continue;
			}
			Map<String, Object> mod = e.map("modifier_json");
			if (mod.get("weapon_entry_id") instanceof Number n && n.longValue() == entryId) {
				fold(m, mod, e.str("source_description"));
			}
		}
		return m;
	}

	/**
	 * A weapon a spell conjured for the character (Flame Blade), by name, while the effect lasts.
	 */
	@SuppressWarnings("unchecked")
	public static Optional<Map<String, Object>> conjuredWeapon(Tx tx, long characterId, String name) {
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ? ORDER BY id", characterId)) {
			if (e.isNull("modifier_json")) {
				continue;
			}
			Map<String, Object> mod = e.map("modifier_json");
			if (mod.get("conjured_weapon") instanceof Map<?, ?> w
					&& name.equalsIgnoreCase(String.valueOf(((Map<String, Object>) w).get("name")))) {
				return Optional.of((Map<String, Object>) w);
			}
		}
		return Optional.empty();
	}

	@SuppressWarnings("unchecked")
	private static void fold(Modifiers m, Map<String, Object> mod, String source) {
		{
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
				d.put("source", source);
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
			if (mod.get("damage_bonus") instanceof Number n) {
				m.damageBonusFlat += n.intValue();
			}
			if (mod.get("weapon_die") instanceof String s) {
				m.weaponDie = s;
			}
			if (mod.get("use_ability") instanceof String s) {
				m.useAbility = s;
			}
			if (mod.get("weapon_damage_type") instanceof String s) {
				m.weaponDamageType = s;
			}
		}
	}

	/**
	 * Ends one effect. A {@code max_hp} modifier was applied to the character's maximum when the
	 * effect began (so the stored maximum is always the one in force and the I-13 check holds);
	 * ending it takes the change back and clamps current HP to the new maximum (SRD 5.2.1 "Hit
	 * Point Maximum").
	 */
	public static void end(Tx tx, Row effect) {
		if (Exhaustion.CONDITION_REF.equals(effect.str("condition_ref"))) {
			Exhaustion.normalize(tx, effect.lng("character_id"));
			return;
		}
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
	 * Ends every effect on a character (death or an explicit full reset), reversing maximum-HP
	 * changes on the way.
	 */
	public static int removeAll(Tx tx, long characterId) {
		Exhaustion.normalize(tx, characterId);
		int removed = 0;
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ?", characterId)) {
			end(tx, e);
			removed++;
		}
		return removed;
	}

	/**
	 * Ends the effects that last until a Long Rest ({@code duration.until = LONG_REST}), and those
	 * waiting for a Short Rest, which a Long Rest also completes; returns their descriptions.
	 */
	public static List<String> expireOnLongRest(Tx tx, long characterId) {
		Exhaustion.normalize(tx, characterId);
		return expireUntil(tx, characterId, java.util.Set.of("LONG_REST", "SHORT_REST"));
	}

	/**
	 * Ends the effects that last until the end of a Short Rest ({@code duration.until =
	 * SHORT_REST}: a knocked-out creature's Unconscious condition, SRD 5.2.1 "Knocking Out a
	 * Creature"); returns their descriptions.
	 */
	public static List<String> expireOnShortRest(Tx tx, long characterId) {
		return expireUntil(tx, characterId, java.util.Set.of("SHORT_REST"));
	}

	private static List<String> expireUntil(Tx tx, long characterId, java.util.Set<String> milestones) {
		var ended = new ArrayList<String>();
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ? AND duration_json IS NOT NULL",
				characterId)) {
			if (milestones.contains(String.valueOf(e.map("duration_json").get("until")))) {
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
		long id = tx.insert("active_effect", cols);
		conditionGained(tx, characterId, conditionRef);
		return id;
	}

	/**
	 * Bookkeeping when a creature gains a condition, called by both insertion paths (this class and
	 * RuntimeService.addCondition): Incapacitated, and every condition that includes it, breaks the
	 * creature's Concentration (SRD 5.2.1 "Incapacitated": "No Concentration. Your Concentration is
	 * broken."). Returns the descriptions of the concentration effects that ended.
	 */
	public static List<String> conditionGained(Tx tx, long characterId, String conditionRef) {
		if (conditionRef == null || !conditionRef.startsWith("srd5e:condition/")) {
			return List.of();
		}
		String condition = conditionRef.substring("srd5e:condition/".length()).toUpperCase();
		if ("UNCONSCIOUS".equals(condition)) {
			Row character = tx.get("character", characterId);
			se.hirt.mcp.rpg.character.RuntimeService.ensureProneFromUnconscious(tx, character.lng("campaign_id"),
					characterId);
		}
		return Combat.INCAPACITATING_CONDITIONS.contains(condition) ? breakConcentration(tx, characterId) : List.of();
	}

	private static final java.util.regex.Pattern DURATION_TEXT = java.util.regex.Pattern
			.compile("(?i)\\s*(\\d+)\\s*(round|minute|min|hour|day)s?\\s*");

	/**
	 * A duration given by the GM (ADD_CONDITION), normalized to the shapes the engine expires: a
	 * number is minutes; "3 rounds", "1 minute", "2 hours", "1 day" parse; "long rest" / LONG_REST,
	 * "restored" / RESTORED and "start of turn" / START_OF_TURN are milestones; a map with
	 * expires_seq, expires_round or until passes through; {minutes: n} / {rounds: n} / {hours: n}
	 * are converted. Start-of-turn durations bind to the recipient's active encounter participant
	 * and expire on their next turn or when that encounter ends. Null (or "until removed") means no
	 * expiry.
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
				if (!java.util.Set.of("LONG_REST", "SHORT_REST", "RESTORED", "START_OF_TURN", "HP_ABOVE_ZERO")
						.contains(until)) {
					throw se.hirt.mcp.rpg.protocol.RpgException.invalidArgument(
							"duration.until must be LONG_REST, SHORT_REST, RESTORED, START_OF_TURN or HP_ABOVE_ZERO.");
				}
				m.put("until", until);
				m.putIfAbsent("label", label);
				return "START_OF_TURN".equals(until) ? startOfTurn(tx, campaignId, c, m) : m;
			}
			if (m.get("expires_seq") instanceof Number || m.get("expires_seconds") instanceof Number
					|| m.get("expires_round") instanceof Number) {
				if (m.get("expires_round") instanceof Number && encounterId != null) {
					m.putIfAbsent("encounter", encounterId);
				}
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
			return startOfTurn(tx, campaignId, c, until("START_OF_TURN", label));
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

	private static Map<String, Object> startOfTurn(Tx tx, long campaignId, Row c, Map<String, Object> duration) {
		Row participant = tx.queryOne(
				"SELECT p.* FROM encounter_participant p JOIN encounter e ON e.id = p.encounter_id WHERE p.character_id = ? AND p.encounter_id = ? AND p.status = 'ACTIVE' AND e.campaign_id = ? AND e.status IN ('RUNNING', 'WAITING_CHOICE')",
				c.id(), c.lng("encounter_id"), campaignId)
				.orElseThrow(() -> se.hirt.mcp.rpg.protocol.RpgException.invalidArgument(
						"A start-of-turn duration requires an active encounter participant; use minutes or 'until removed' outside encounters."));
		long encounterId = participant.lng("encounter_id");
		if (!matchesScope(duration.get("participant"), participant.id())
				|| !matchesScope(duration.get("encounter"), encounterId)) {
			throw se.hirt.mcp.rpg.protocol.RpgException.invalidArgument(
					"A start-of-turn duration must refer to the recipient's current participant and encounter.");
		}
		duration.put("kind", "UNTIL");
		duration.put("participant", participant.id());
		duration.put("encounter", encounterId);
		return duration;
	}

	private static boolean matchesScope(Object scope, long id) {
		return scope == null || scope instanceof Number n && n.longValue() == id && n.doubleValue() == id;
	}

	/** Duration in game minutes from now. */
	public static Map<String, Object> minutes(Tx tx, long campaignId, long minutes, String label) {
		var d = new LinkedHashMap<String, Object>();
		d.put("kind", "MINUTES");
		d.put("label", label);
		d.put("expires_seq", GameTime.currentSeq(tx, campaignId) + minutes);
		d.put("expires_seconds",
				Math.addExact(GameTime.currentSeconds(tx, campaignId), Math.multiplyExact(minutes, 60)));
		return d;
	}

	/** Minutes on the campaign clock for a number of rounds: 6 seconds each, rounded up. */
	public static long minutesForRounds(long rounds) {
		return Math.max(1, (rounds * SECONDS_PER_ROUND + 59) / 60);
	}

	/** "A round represents about 6 seconds in the game world" (SRD 5.2.1 "Combat"). */
	public static final int SECONDS_PER_ROUND = 6;

	/**
	 * Duration in encounter rounds (expires when that round begins); outside encounters, the same
	 * time on the clock (6 seconds per round).
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
			d.put("expires_seq", GameTime.currentSeq(tx, campaignId) + minutesForRounds(rounds));
			d.put("expires_seconds", Math.addExact(GameTime.currentSeconds(tx, campaignId),
					Math.multiplyExact(rounds, SECONDS_PER_ROUND)));
		}
		return d;
	}

	/** Removes effects whose game-time expiry has passed; returns the number removed. */
	public static int expireByTime(Tx tx, long campaignId, long nowSeq) {
		Row clock = GameTime.clock(tx, campaignId);
		long nowSeconds = Math.addExact(Math.multiplyExact(nowSeq, 60),
				clock.lng("seq") == nowSeq ? clock.intOr("second_offset", 0) : 0);
		int removed = 0;
		for (Row e : tx.query("SELECT * FROM active_effect WHERE campaign_id = ? AND duration_json IS NOT NULL",
				campaignId)) {
			Map<String, Object> d = e.map("duration_json");
			boolean due = !e.isNull("end_seconds") ? e.lng("end_seconds") <= nowSeconds
					: d.get("expires_seq") instanceof Number n && n.longValue() <= nowSeq;
			if (due) {
				end(tx, e);
				removed++;
			}
		}
		return removed;
	}

	/**
	 * Removes round-scoped effects of an encounter that expire at or before the given round. The
	 * duration owns the scope even when its recipient has left the encounter or never joined it.
	 * Older untagged durations fall back to the encounter's persistent participant records.
	 */
	public static int expireByRound(Tx tx, long encounterId, long round) {
		int removed = 0;
		for (Row e : roundEffects(tx, encounterId)) {
			if (e.map("duration_json").get("expires_round") instanceof Number n && n.longValue() <= round) {
				end(tx, e);
				removed++;
			}
		}
		return removed;
	}

	/** The round-scoped effects owned by an encounter (see {@link #expireByRound}). */
	private static List<Row> roundEffects(Tx tx, long encounterId) {
		long campaignId = tx.get("encounter", encounterId).lng("campaign_id");
		var out = new ArrayList<Row>();
		for (Row e : tx.query(
				"SELECT a.*, p.id AS round_participant_id FROM active_effect a LEFT JOIN encounter_participant p ON p.character_id = a.character_id AND p.encounter_id = ? WHERE a.campaign_id = ? AND a.duration_json IS NOT NULL",
				encounterId, campaignId)) {
			Map<String, Object> d = e.map("duration_json");
			Object scope = d.get("encounter");
			boolean belongs = scope == null ? !e.isNull("round_participant_id")
					: scope instanceof Number n && n.longValue() == encounterId;
			if (belongs && d.get("expires_round") instanceof Number) {
				out.add(e);
			}
		}
		return out;
	}

	/**
	 * At the end of an encounter the rounds an effect has left become game time: "A round
	 * represents about 6 seconds in the game world" (SRD 5.2.1 "Combat"), and nothing in the SRD
	 * ends a 10-round condition because the fight ended. The remaining rounds, counted from the
	 * encounter's final round, retain their exact six-second duration on the clock ({@code nowSeq}:
	 * the clock after the encounter's own time has elapsed). Effects already due are ended. Returns
	 * the number converted.
	 */
	public static int convertRoundsAtEncounterEnd(Tx tx, long encounterId, long finalRound, long nowSeq) {
		int converted = 0;
		for (Row e : roundEffects(tx, encounterId)) {
			Map<String, Object> d = new LinkedHashMap<>(e.map("duration_json"));
			long remaining = ((Number) d.get("expires_round")).longValue() - finalRound;
			if (remaining <= 0) {
				end(tx, e);
				continue;
			}
			d.put("kind", "MINUTES");
			d.put("expires_seq", nowSeq + minutesForRounds(remaining));
			d.put("expires_seconds", Math.addExact(GameTime.currentSeconds(tx, e.lng("campaign_id")),
					Math.multiplyExact(remaining, SECONDS_PER_ROUND)));
			d.put("converted_from_rounds", remaining);
			d.remove("expires_round");
			d.remove("encounter");
			tx.update("active_effect", e.id(), Map.of("duration_json", Json.write(d)));
			converted++;
		}
		return converted;
	}

	/** Older unbound start-of-turn durations follow their recipient's next reached turn. */
	public static int expireAtTurnStart(Tx tx, long encounterId, long participantId) {
		int removed = 0;
		for (Row e : tx.query(
				"SELECT * FROM active_effect WHERE character_id = (SELECT character_id FROM encounter_participant WHERE id = ? AND encounter_id = ?) AND duration_json IS NOT NULL",
				participantId, encounterId)) {
			Map<String, Object> d = e.map("duration_json");
			if ("START_OF_TURN".equals(d.get("until")) && matchesScope(d.get("encounter"), encounterId)
					&& matchesScope(d.get("participant"), participantId)) {
				end(tx, e);
				removed++;
			}
		}
		return removed;
	}

	/**
	 * Clears the start-of-turn durations owned by the encounter, including those on removed
	 * participants: the turn they wait for never comes, and the round it would have fallen in lies
	 * inside the minute the encounter's end already advances the clock by. Round durations are not
	 * touched here; see {@link #convertRoundsAtEncounterEnd}.
	 */
	public static int expireOnEncounterEnd(Tx tx, long encounterId) {
		int removed = 0;
		long campaignId = tx.get("encounter", encounterId).lng("campaign_id");
		for (Row e : tx.query(
				"SELECT a.*, p.id AS turn_participant_id FROM active_effect a LEFT JOIN encounter_participant p ON p.character_id = a.character_id AND p.encounter_id = ? WHERE a.campaign_id = ? AND a.duration_json IS NOT NULL",
				encounterId, campaignId)) {
			Map<String, Object> d = e.map("duration_json");
			if (!"START_OF_TURN".equals(d.get("until"))) {
				continue;
			}
			boolean belongs;
			if (d.get("encounter") != null) {
				belongs = matchesScope(d.get("encounter"), encounterId);
			} else if (d.get("participant") instanceof Number n) {
				belongs = tx.find("encounter_participant", n.longValue())
						.filter(p -> p.lng("encounter_id") == encounterId && matchesScope(n, p.id())).isPresent();
			} else {
				belongs = d.get("participant") == null && !e.isNull("turn_participant_id");
			}
			if (belongs) {
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
