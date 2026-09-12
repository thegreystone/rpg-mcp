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
package se.hirt.mcp.rpg.character;

import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.harness.HarnessState;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.progression.LevelUpService;
import se.hirt.mcp.rpg.progression.PartyXp;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Ability;
import se.hirt.mcp.rpg.rules.CheckService;
import se.hirt.mcp.rpg.rules.Combat;
import se.hirt.mcp.rpg.rules.Rules;
import se.hirt.mcp.rpg.session.GameTime;

import java.time.Instant;
import java.util.*;

/**
 * Character runtime outside the encounter loop: materializing creatures into characters, named rules-aware runtime
 * changes, XP awards, and player-control transfer (MCP_PROTOCOL.md §10.8, §13.3, §13.5, §19.4).
 */
public final class RuntimeService {

	public static final Set<String> CONDITIONS = Set.of("BLINDED", "CHARMED", "DEAFENED", "EXHAUSTION", "FRIGHTENED",
			"GRAPPLED", "INCAPACITATED", "INVISIBLE", "PARALYZED", "PETRIFIED", "POISONED", "PRONE", "RESTRAINED",
			"STUNNED", "UNCONSCIOUS");
	public static final String UNCONSCIOUS_REF = "srd5e:condition/unconscious";
	public static final Set<String> XP_SOURCES = Set.of("QUEST", "MILESTONE", "ROLEPLAY", "EXPLORATION",
			"CLEVER_SOLUTION", "DISCRETIONARY");

	private final Database db;
	private final RulesData rules;
	private final RollService roller;
	private final CharacterService characters;

	public RuntimeService(Database db, RulesData rules, RollService roller, CharacterService characters) {
		this.db = db;
		this.rules = rules;
		this.roller = roller;
		this.characters = characters;
	}

	// ── materialize_character ──────────────────────────────────────────

	public Map<String, Object> materialize(
			String operationId, String campaignRef, String source, String name,
			String description, String personality, String alignment, String locationRef, boolean rollHp) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("source", source);
		args.put("name", name);
		args.put("location", locationRef);
		args.put("roll_hp", rollHp);
		return db.mutate(Database.Mutation.of("materialize_character", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "materialize_character");
			if (source != null && source.startsWith(Ref.SEED + ":")) {
				throw RpgException.capabilityUnavailable(
						"Materializing Director companion seeds arrives with the Director milestone; use an installed creature definition.");
			}
			RulesData.Definition def = rules.resolve("CREATURE", source).orElseThrow(() -> RpgException.invalidArgument(
					"Unknown creature '" + source + "'; see get_content_definitions with kind CREATURE."));
			Map<String, Object> p = def.payload();
			Long locationId = campaign.lng("current_location_id");
			if (locationRef != null && !locationRef.isBlank()) {
				Row loc = tx.get("location", Ref.id(locationRef, Ref.LOCATION));
				if (loc.lng("campaign_id") != campaignId) {
					throw RpgException.invalidArgument(locationRef + " belongs to another campaign.");
				}
				locationId = loc.id();
			}
			@SuppressWarnings("unchecked") Map<String, Object> hp = (Map<String, Object>) p.get("hp");
			int maxHp;
			Roll hpRoll = null;
			if (rollHp && hp.get("dice") != null) {
				hpRoll = roller.roll(String.valueOf(hp.get("dice")));
				maxHp = Math.max(1, hpRoll.total());
			} else {
				maxHp = ((Number) hp.get("average")).intValue();
			}
			@SuppressWarnings("unchecked") Map<String, Object> abilities = (Map<String, Object>) p.get("abilities");
			var cols = new LinkedHashMap<String, Object>();
			cols.put("campaign_id", campaignId);
			cols.put("lifecycle", "ACTIVE");
			cols.put("life_state", "ALIVE");
			cols.put("name", name == null || name.isBlank() ? def.name() : name.trim());
			cols.put("description", description);
			cols.put("personality", personality);
			// A companion authored with a moral centre should carry it from the moment they exist, not acquire one
			// later by hand; the stat block's own alignment line is flavour for a monster, not a character.
			if (alignment != null && !alignment.isBlank()) {
				String a = alignment.trim().toUpperCase().replace(' ', '_');
				if (!CharacterService.ALIGNMENTS.contains(a)) {
					throw RpgException.invalidArgument(
							"alignment must be one of " + CharacterService.ALIGNMENTS + ".");
				}
				cols.put("alignment", a);
			}
			for (Ability a : Ability.values()) {
				cols.put(a.column(), ((Number) abilities.getOrDefault(a.name(), 10)).intValue());
			}
			cols.put("max_hp", maxHp);
			cols.put("current_hp", maxHp);
			@SuppressWarnings("unchecked") Map<String, Object> speed = (Map<String, Object>) p.getOrDefault("speed",
					Map.of("walk", 30));
			cols.put("speed", ((Number) speed.getOrDefault("walk", 30)).intValue());
			cols.put("senses_json", p.get("senses") == null ? null : Json.write(p.get("senses")));
			cols.put("origin_content_ref", def.id());
			cols.put("creation_json", "{}");
			cols.put("location_id", locationId);
			cols.put("revision", 0);
			cols.put("created_at", Instant.now().toString());
			long id = tx.insert("character", cols);
			if (hpRoll != null) {
				CharacterService.recordRoll(tx, campaignId, "hit points " + Ref.of(Ref.CHARACTER, id), hpRoll);
			}
			tx.touched(Ref.of(Ref.CHARACTER, id), 0);
			var result = new LinkedHashMap<String, Object>();
			result.put("character", Ref.of(Ref.CHARACTER, id));
			result.put("source", def.id());
			result.put("sheet", characters.sheet(tx, tx.get("character", id), "PLAY"));
			result.put("note",
					"Materialized creatures are ordinary characters: rename, befriend, recruit or kill them; the identity stays stable.");
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── apply_runtime_change ───────────────────────────────────────────

	public Map<String, Object> applyRuntimeChange(
			String operationId, String campaignRef, String characterRef, Map<String, Object> change) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("character", characterRef);
		args.put("change", change);
		return db.mutate(Database.Mutation.of("apply_runtime_change", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "apply_runtime_change");
			Row c = CharacterService.character(tx, campaignId, characterRef);
			if (!"ACTIVE".equals(c.str("lifecycle"))) {
				throw RpgException.notAllowed(characterRef + " is not an active character.");
			}
			if (change == null || change.get("kind") == null) {
				throw RpgException.invalidArgument(
						"change.kind is required: HEAL, DAMAGE, SET_TEMP_HP, ADD_CONDITION, REMOVE_CONDITION, STABILIZE, USE_RESOURCE, RESTORE_RESOURCE, REDUCE_MAX_HP, RESTORE_MAX_HP, CREATE_SPELL_SLOT, CONVERT_SPELL_SLOT.");
			}
			String kind = String.valueOf(change.get("kind")).toUpperCase();
			String reason = change.get("reason") == null ? null : change.get("reason").toString();
			var result = new LinkedHashMap<String, Object>();
			result.put("character", Ref.of(Ref.CHARACTER, c.id()));
			result.put("kind", kind);
			switch (kind) {
			case "HEAL" -> {
				int amount = amount(change, "amount");
				result.putAll(heal(tx, c, amount, reason));
			}
			case "DAMAGE" -> {
				// Either a fixed amount or a dice expression the server rolls and journals ("2d6" for a fall,
				// "3d8" for a creature ending its turn inside Spirit Guardians).
				Roll damageRoll;
				if (change.get("dice") instanceof String dice && !dice.isBlank()) {
					damageRoll = roller.roll(dice);
					CheckService.recordRoll(tx, campaignId, "damage", damageRoll, c.id(), reason);
					result.put("roll", damageRoll.toMap());
				} else {
					int amount = amount(change, "amount");
					damageRoll = new Roll(Integer.toString(amount), List.of(), List.of(), amount, amount);
				}
				int amount = Math.max(0, damageRoll.total());
				String type = change.get("damage_type") == null ? "bludgeoning"
						: change.get("damage_type").toString().toLowerCase();
				var rolled = List.of(new Combat.RolledDamage(type, damageRoll, amount));
				Combat.DamageResult dr = Combat.applyDamage(c.intOr("current_hp", 0), c.intOr("temp_hp", 0),
						Math.max(1, effectiveMaxHp(tx, c)), rolled, defensesOf(tx, rules, c));
				result.putAll(applyDamageResult(tx, rules, roller, c, dr, false, null, reason));
			}
			case "ADJUST_MAX_HP" -> {
				// A signed, temporary change to the hit point maximum (SRD 5.2.1 "Hit Point Maximum"): a Life
				// Drain's -16, Aid's +5. An active effect with a max_hp modifier; ends with a Long Rest by default,
				// after `minutes`, or only when RESTORE_MAX_HP lifts it (until = RESTORED: Greater Restoration in
				// the fiction). A reduction clamps current HP; a maximum of 0 kills.
				if (!(change.get("amount") instanceof Number n) || n.intValue() == 0) {
					throw RpgException.invalidArgument("change.amount must be a non-zero integer (negative reduces).");
				}
				result.putAll(adjustMaxHp(tx, campaignId, c, n.intValue(), change, reason));
			}
			case "RESTORE_MAX_HP" -> {
				// Lifts every reduction of the maximum (bonuses stay), whatever it was waiting for.
				int lifted = 0;
				for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ? AND modifier_json IS NOT NULL",
						c.id())) {
					if (e.map("modifier_json").get("max_hp") instanceof Number n && n.intValue() < 0) {
						se.hirt.mcp.rpg.rules.Effects.end(tx, e);
						lifted++;
					}
				}
				result.put("reductions_lifted", lifted);
			}
			case "SET_TEMP_HP" -> {
				int amount = amount(change, "amount");
				// Temporary HP doesn't stack: keep the higher value (SRD 5.2.1 "Temporary Hit Points").
				int newTemp = Math.max(c.intOr("temp_hp", 0), amount);
				tx.update("character", c.id(), Map.of("temp_hp", newTemp, "revision", c.lng("revision") + 1));
				result.put("temp_hp", newTemp);
			}
			case "ADD_CONDITION" -> {
				String condition = condition(change);
				if (tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id = ? AND condition_ref = ?", c.id(),
						conditionRef(condition)) > 0) {
					result.put("already_present", true);
				} else {
					long id = addCondition(tx, campaignId, c.id(), condition, change.get("duration"), reason, "GM");
					result.put("effect", id);
				}
				result.put("condition", condition);
			}
			case "REMOVE_CONDITION" -> {
				String condition = condition(change);
				int removed = 0;
				for (Row e : tx.query("SELECT id FROM active_effect WHERE character_id = ? AND condition_ref = ?",
						c.id(), conditionRef(condition))) {
					tx.delete("active_effect", e.id());
					removed++;
				}
				result.put("condition", condition);
				result.put("removed", removed);
			}
			case "STABILIZE" -> {
				if (!"DYING".equals(c.str("life_state"))) {
					throw RpgException.notAllowed(c.str("name") + " is not dying.");
				}
				Map<String, Object> saves = Combat.freshDeathSaves();
				saves.put("stable", true);
				tx.update("character", c.id(),
						Map.of("death_saves_json", Json.write(saves), "revision", c.lng("revision") + 1));
				result.put("stable", true);
				result.put("note",
						"Stable at 0 HP and Unconscious; regains 1 HP after 1d4 hours unless healed (SRD 5.2.1).");
			}
			case "USE_RESOURCE", "RESTORE_RESOURCE" -> {
				// Species-trait and feat uses (Breath Weapon, Heroic Inspiration, ...) — tracked by the
				// engine, triggered by the GM; the sheet's `resources` block lists the refs.
				String ref = change.get("resource") == null ? "" : change.get("resource").toString();
				Row res = tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?",
						c.id(), ref).orElseThrow(() -> RpgException.invalidArgument(
						"Unknown resource '" + ref + "' for " + c.str(
								"name") + "; the character sheet's resources block lists the legal refs."));
				int current = res.intOr("current", 0);
				int max = res.intOr("max", 0);
				int n = change.get("amount") instanceof Number a ? Math.max(1, a.intValue()) : 1;
				if (kind.equals("USE_RESOURCE")) {
					if (current < n) {
						throw RpgException.insufficientResource(
								c.str("name") + " has " + current + " use(s) of " + ref + " left.");
					}
					current -= n;
				} else {
					current = Math.min(max, current + n);
				}
				tx.update("resource_state", res.id(), Map.of("current", current));
				tx.update("character", c.id(), Map.of("revision", c.lng("revision") + 1));
				result.put("resource",
						Map.of("ref", ref, "current", current, "max", max, "recharge", res.str("recharge")));
			}
			case "CREATE_SPELL_SLOT", "CONVERT_SPELL_SLOT" ->
				// Font of Magic (Sorcerer): sorcery points into a slot, or a slot into points; a Bonus Action.
					result.putAll(se.hirt.mcp.rpg.magic.Metamagic.fontOfMagic(tx, rules, c, kind, change));
			default -> throw RpgException.invalidArgument("Unknown change kind '" + kind + "'.");
			}
			Row after = tx.get("character", c.id());
			tx.touched(Ref.of(Ref.CHARACTER, c.id()), after.lng("revision"));
			result.put("hp", hpView(tx, after));
			result.put("life_state", after.str("life_state"));
			result.put("conditions", conditions(tx, c.id()));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	/** {current, max, temp}, plus the unadjusted maximum and the adjustments when any are in force. */
	public static Map<String, Object> hpView(Tx tx, Row c) {
		var hp = new LinkedHashMap<String, Object>();
		hp.put("current", c.intOr("current_hp", 0));
		hp.put("max", c.intOr("max_hp", 0));
		hp.put("temp", c.intOr("temp_hp", 0));
		List<Map<String, Object>> adjustments = maxHpAdjustments(tx, c.id());
		if (!adjustments.isEmpty()) {
			int adjusted = adjustments.stream().mapToInt(a -> ((Number) a.get("amount")).intValue()).sum();
			hp.put("max_base", c.intOr("max_hp", 0) - adjusted);
			hp.put("max_adjustments", adjustments);
		}
		return hp;
	}

	/** The active effects that change the hit point maximum: {amount, source, until}. */
	public static List<Map<String, Object>> maxHpAdjustments(Tx tx, long characterId) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ? AND modifier_json IS NOT NULL ORDER BY id",
				characterId)) {
			if (e.map("modifier_json").get("max_hp") instanceof Number n && n.intValue() != 0) {
				var m = new LinkedHashMap<String, Object>();
				m.put("amount", n.intValue());
				m.put("source", e.str("source_description"));
				if (!e.isNull("duration_json")) {
					m.put("until", e.map("duration_json"));
				}
				out.add(m);
			}
		}
		return out;
	}

	/**
	 * The hit point maximum in force. Temporary changes (Aid, a Life Drain) are applied to the stored maximum when
	 * their effect begins and taken back when it ends ({@link se.hirt.mcp.rpg.rules.Effects#end}), so the column is
	 * always the number that counts and I-13 holds in the database; the effects list says what is temporary.
	 */
	public static int effectiveMaxHp(Tx tx, Row c) {
		return Math.max(0, c.intOr("max_hp", 0));
	}

	/**
	 * Adds a signed max_hp effect and applies it to the maximum. A bonus raises the maximum (current HP is unchanged:
	 * Aid also heals, separately); a reduction clamps current HP to the new maximum, and a maximum of 0 kills
	 * (SRD 5.2.1 "Hit Point Maximum").
	 */
	public static Map<String, Object> adjustMaxHp(
			Tx tx, long campaignId, Row c, int amount, Map<String, Object> change, String reason) {
		Map<String, Object> duration;
		String until = change.get("until") == null ? null : change.get("until").toString().trim().toUpperCase();
		if (change.get("minutes") instanceof Number mins && mins.longValue() > 0) {
			duration = se.hirt.mcp.rpg.rules.Effects.minutes(tx, campaignId, mins.longValue(),
					mins.longValue() + " minutes");
		} else if (until == null || "LONG_REST".equals(until)) {
			duration = Map.of("kind", "UNTIL", "until", "LONG_REST", "label", "until a Long Rest");
		} else if ("RESTORED".equals(until)) {
			duration = Map.of("kind", "UNTIL", "until", "RESTORED", "label", "until restored");
		} else {
			throw RpgException.invalidArgument("change.until must be LONG_REST (default) or RESTORED, or give minutes.");
		}
		String source = reason == null || reason.isBlank()
				? (amount < 0 ? "hit point maximum reduced" : "hit point maximum raised") : reason;
		String stackingKey = change.get("stacking_key") == null ? null : change.get("stacking_key").toString();
		long effectId = se.hirt.mcp.rpg.rules.Effects.add(tx, campaignId, c.id(), null, null, source, null,
				Map.of("max_hp", amount), duration, null, stackingKey, "GM");
		var out = new LinkedHashMap<String, Object>();
		out.put("effect", effectId);
		out.put("adjustment", Map.of("amount", amount, "duration", duration));
		int effective = Math.max(0, c.intOr("max_hp", 0) + amount);
		var cols = new LinkedHashMap<String, Object>();
		cols.put("max_hp", effective);
		cols.put("current_hp", Math.min(c.intOr("current_hp", 0), effective));
		if (effective == 0 && !"DEAD".equals(c.str("life_state"))) {
			// Dead: the effects go, and with them the maximum they changed; write the death on the row we have.
			tx.update("character", c.id(), Map.of("max_hp", effective, "current_hp", 0));
			se.hirt.mcp.rpg.rules.Effects.breakConcentration(tx, c.id());
			se.hirt.mcp.rpg.rules.Effects.removeAll(tx, c.id());
			cols.remove("max_hp");
			cols.put("current_hp", 0);
			cols.put("life_state", "DEAD");
			cols.put("death_saves_json", null);
			endMemberships(tx, campaignId, c.id(), "DEAD");
			LedgerService.append(tx, campaignId, new LedgerService.EventSpec("CHARACTER_DIED",
					c.str("name") + " died: hit point maximum reduced to 0" + (reason == null ? "" : " (" + reason + ")") + ".",
					List.of(c.id()), isPartyMember(tx, campaignId, c.id()) ? "CRITICAL" : "NOTABLE", "PARTY_KNOWN",
					"MECHANICAL_CONSEQUENCE", null, c.lng("location_id"), null, Map.of("max_hp_adjustment", amount)));
			out.put("died", true);
		}
		cols.put("revision", c.lng("revision") + 1);
		tx.update("character", c.id(), cols);
		return out;
	}

	private static int amount(Map<String, Object> change, String key) {
		if (!(change.get(key) instanceof Number n) || n.intValue() <= 0) {
			throw RpgException.invalidArgument("change." + key + " must be a positive integer.");
		}
		return n.intValue();
	}

	private static String condition(Map<String, Object> change) {
		String condition = change.get("condition") == null ? "" : change.get("condition").toString().toUpperCase();
		if (!CONDITIONS.contains(condition)) {
			throw RpgException.invalidArgument(
					"condition must be one of " + CONDITIONS.stream().sorted().toList() + ".");
		}
		return condition;
	}

	public static String conditionRef(String condition) {
		return "srd5e:condition/" + condition.toLowerCase();
	}

	public static long addCondition(
			Tx tx, long campaignId, long characterId, String condition, Object duration,
			String reason, String provenance) {
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("character_id", characterId);
		cols.put("source_description", reason);
		cols.put("provenance", provenance);
		cols.put("condition_ref", conditionRef(condition));
		cols.put("start_seq", GameTime.currentSeq(tx, campaignId));
		cols.put("start_journal_id", tx.journalId());
		cols.put("duration_json", duration == null ? null : Json.write(duration));
		cols.put("stacking_key", condition);
		return tx.insert("active_effect", cols);
	}

	public static List<Map<String, Object>> conditions(Tx tx, long characterId) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ? ORDER BY id", characterId)) {
			var m = new LinkedHashMap<String, Object>();
			String ref = e.str("condition_ref");
			m.put("condition", ref == null ? null : ref.substring(ref.lastIndexOf('/') + 1).toUpperCase());
			m.put("source", e.str("source_description"));
			if (!e.isNull("duration_json")) {
				m.put("duration", e.map("duration_json"));
			}
			out.add(m);
		}
		return out;
	}

	/** Healing (SRD 5.2.1 "Healing"): HP can't exceed max; a creature at 0 HP that regains HP is no longer dying. */
	public static Map<String, Object> heal(Tx tx, Row c, int amount, String reason) {
		int max = effectiveMaxHp(tx, c);
		int before = c.intOr("current_hp", 0);
		int after = Math.min(max, before + amount);
		var cols = new LinkedHashMap<String, Object>();
		cols.put("current_hp", after);
		cols.put("revision", c.lng("revision") + 1);
		boolean revived = false;
		if ("DYING".equals(c.str("life_state")) && after > 0) {
			cols.put("life_state", "ALIVE");
			cols.put("death_saves_json", null);
			for (Row e : tx.query("SELECT id FROM active_effect WHERE character_id = ? AND condition_ref = ?", c.id(),
					UNCONSCIOUS_REF)) {
				tx.delete("active_effect", e.id());
			}
			revived = true;
		}
		tx.update("character", c.id(), cols);
		var m = new LinkedHashMap<String, Object>();
		m.put("healed", after - before);
		m.put("hp_before", before);
		m.put("hp_after", after);
		if (revived) {
			m.put("revived", true);
		}
		return m;
	}

	public static Combat.Defenses defensesOf(Tx tx, RulesData rules, Row c) {
		if (!c.isNull("origin_content_ref")) {
			return rules.find(c.str("origin_content_ref"))
					.map(d -> new Combat.Defenses(lower(d.payload().get("damage_resistances")),
							lower(d.payload().get("damage_vulnerabilities")),
							lower(d.payload().get("damage_immunities")))).orElse(Combat.Defenses.NONE);
		}
		// Built characters: resistances from species traits and the chosen lineage/ancestry (SRD 5.2.1).
		Set<String> resistances = Origins.speciesResistances(tx, rules, c);
		return resistances.isEmpty() ? Combat.Defenses.NONE : new Combat.Defenses(resistances, Set.of(), Set.of());
	}

	private static Set<String> lower(Object list) {
		if (!(list instanceof List<?> l)) {
			return Set.of();
		}
		var s = new java.util.HashSet<String>();
		for (Object o : l) {
			s.add(o.toString().toLowerCase());
		}
		return s;
	}

	/**
	 * Commits a damage result to the character: HP/temp HP, dropping to 0 (party members start dying, others die),
	 * death-save failures while already at 0, massive damage. Returns the state changes for narration.
	 */
	public static Map<String, Object> applyDamageResult(
			Tx tx, RulesData rules, RollService roller, Row c, Combat.DamageResult dr, boolean critical, Long sourceId,
			String reason) {
		long campaignId = c.lng("campaign_id");
		var out = new LinkedHashMap<String, Object>();
		// Death Ward: the first drop to 0 becomes 1 HP instead and the ward is spent (SRD 5.2.1 "Death Ward").
		if (dr.droppedToZero() && se.hirt.mcp.rpg.rules.Effects.modifiers(tx, c.id()).deathWard) {
			for (Row e : tx.query(
					"SELECT * FROM active_effect WHERE character_id = ? AND modifier_json LIKE '%death_ward%'",
					c.id())) {
				tx.delete("active_effect", e.id());
			}
			dr = new Combat.DamageResult(dr.totalDealt(), dr.breakdown(), dr.tempHpBefore(), dr.tempHpAfter(),
					dr.hpBefore(), 1, false, false);
			out.put("death_ward", "the ward absorbed the fall to 0 HP");
		}
		// Relentless Endurance (Orc): a drop to 0 HP that is not outright death becomes 1 HP; once per Long Rest.
		if (dr.droppedToZero() && !dr.massiveDamage() && Origins.spendRelentlessEndurance(tx, rules, c)) {
			dr = new Combat.DamageResult(dr.totalDealt(), dr.breakdown(), dr.tempHpBefore(), dr.tempHpAfter(),
					dr.hpBefore(), 1, false, false);
			out.put("relentless_endurance", "dropped to 1 HP instead of 0; the use returns on a Long Rest");
		}
		// Concentration: taking damage forces a Constitution save, DC 10 or half the damage (SRD 5.2.1 "Concentration").
		if (dr.totalDealt() > 0 && se.hirt.mcp.rpg.rules.Effects.isConcentrating(tx, c.id())) {
			int dc = Math.max(10, dr.totalDealt() / 2);
			int bonus = se.hirt.mcp.rpg.magic.SpellService.saveBonus(tx, rules, c, Ability.CON);
			Roll save = roller.roll("1d20" + (bonus >= 0 ? "+" + bonus : Integer.toString(bonus)));
			CharacterService.recordRoll(tx, campaignId, "concentration save " + Ref.of(Ref.CHARACTER, c.id()), save);
			var conc = new LinkedHashMap<String, Object>();
			conc.put("dc", dc);
			conc.put("roll", save.toMap());
			conc.put("kept", save.total() >= dc);
			if (save.total() < dc) {
				conc.put("ended", se.hirt.mcp.rpg.rules.Effects.breakConcentration(tx, c.id()));
			}
			out.put("concentration", conc);
		}
		out.put("damage", dr.totalDealt());
		out.put("breakdown", dr.breakdown());
		out.put("temp_hp_absorbed", dr.tempHpBefore() - dr.tempHpAfter());
		out.put("hp_before", dr.hpBefore());
		out.put("hp_after", dr.hpAfter());
		var cols = new LinkedHashMap<String, Object>();
		cols.put("current_hp", dr.hpAfter());
		cols.put("temp_hp", dr.tempHpAfter());
		cols.put("revision", c.lng("revision") + 1);
		String life = c.str("life_state");
		boolean partyMember = isPartyMember(tx, campaignId, c.id());
		if (dr.droppedToZero()) {
			se.hirt.mcp.rpg.rules.Effects.breakConcentration(tx, c.id());
		}
		if ("DYING".equals(life) && dr.totalDealt() > 0) {
			// Damage at 0 HP: one death-save failure, two on a critical hit; massive damage kills (SRD 5.2.1).
			Map<String, Object> saves =
					c.isNull("death_saves_json") ? Combat.freshDeathSaves() : c.map("death_saves_json");
			int failures = ((Number) saves.getOrDefault("failures", 0)).intValue() + (critical ? 2 : 1);
			saves.put("failures", Math.min(3, failures));
			saves.put("stable", false);
			if (failures >= 3 || dr.massiveDamage()) {
				life = "DEAD";
			}
			cols.put("death_saves_json", Json.write(saves));
			out.put("death_saves", saves);
		} else if (dr.droppedToZero()) {
			if (partyMember && !dr.massiveDamage()) {
				life = "DYING";
				cols.put("death_saves_json", Json.write(Combat.freshDeathSaves()));
				addCondition(tx, campaignId, c.id(), "UNCONSCIOUS", Map.of("until", "HP_ABOVE_ZERO"), "dropped to 0 HP",
						"MECHANICAL_CONSEQUENCE");
			} else {
				life = "DEAD";
			}
		}
		if ("DEAD".equals(life) && !"DEAD".equals(c.str("life_state"))) {
			out.put("died", true);
			se.hirt.mcp.rpg.rules.Effects.breakConcentration(tx, c.id());
			se.hirt.mcp.rpg.rules.Effects.removeAll(tx, c.id());
			cols.put("death_saves_json", null);
			endMemberships(tx, campaignId, c.id(), "DEAD");
			LedgerService.append(tx, campaignId, new LedgerService.EventSpec("CHARACTER_DIED",
					c.str("name") + " died" + (reason == null ? "" : " (" + reason + ")") + ".",
					sourceId == null ? List.of(c.id()) : List.of(c.id(), sourceId),
					partyMember ? "CRITICAL" : "NOTABLE", "PARTY_KNOWN", "MECHANICAL_CONSEQUENCE", null,
					c.lng("location_id"), null, Map.of("massive_damage", dr.massiveDamage())));
		}
		cols.put("life_state", life);
		tx.update("character", c.id(), cols);
		out.put("life_state", life);
		if (dr.droppedToZero() && "DYING".equals(life)) {
			out.put("dropped_to_zero", true);
		}
		return out;
	}

	public static boolean isPartyMember(Tx tx, long campaignId, long characterId) {
		return tx.count(
				"SELECT COUNT(*) FROM party_membership WHERE campaign_id = ? AND character_id = ? AND state IN ('ACTIVE','SEPARATED','GUEST')",
				campaignId, characterId) > 0 || tx.count(
				"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id = ? AND character_id = ? AND active = 1",
				campaignId, characterId) > 0;
	}

	public static void endMemberships(Tx tx, long campaignId, long characterId, String state) {
		long seq = GameTime.currentSeq(tx, campaignId);
		for (Row m : tx.query(
				"SELECT * FROM party_membership WHERE campaign_id = ? AND character_id = ? AND state IN ('ACTIVE','SEPARATED','GUEST')",
				campaignId, characterId)) {
			tx.update("party_membership", m.id(),
					Map.of("state", state, "left_time", GameTime.render(seq), "left_seq", seq));
		}
	}

	// ── award_xp ───────────────────────────────────────────────────────

	public Map<String, Object> awardXp(
			String operationId, String campaignRef, List<String> characterRefs, int amount,
			String source, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("characters", characterRefs);
		args.put("amount", amount);
		args.put("source", source);
		args.put("reason", reason);
		String src = source == null ? "DISCRETIONARY" : source.toUpperCase();
		return db.mutate(Database.Mutation.of("award_xp", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "award_xp");
			if (!XP_SOURCES.contains(src)) {
				throw RpgException.invalidArgument(
						"source must be one of " + XP_SOURCES.stream().sorted().toList() + ".");
			}
			if (amount <= 0) {
				throw RpgException.invalidArgument("amount must be positive.");
			}
			if (reason == null || reason.isBlank()) {
				throw RpgException.invalidArgument("A reason is required for XP awards.");
			}
			if (src.equals("DISCRETIONARY")) {
				Map<String, Object> policy =
						campaign.isNull("gm_override_policy_json") ? Map.of() : campaign.map("gm_override_policy_json");
				if ("DISABLED".equals(policy.get("policy"))) {
					throw RpgException.policyDenied(
							"Discretionary XP is disabled by this campaign's GM override policy.");
				}
			}
			// With no explicit list the award follows the campaign's xp_policy, so party experience cannot drift
			// apart just because a companion was forgotten in one call (RULES_ENGINE.md §6).
			var targets = new ArrayList<Row>();
			if (characterRefs == null || characterRefs.isEmpty()) {
				targets.addAll(PartyXp.recipients(tx, campaign, null));
				if (targets.isEmpty()) {
					throw RpgException.invalidArgument(
							"There is no party to award experience to; name at least one character.");
				}
			} else {
				for (String ref : characterRefs) {
					Row c = CharacterService.character(tx, campaignId, ref);
					if (!"ACTIVE".equals(c.str("lifecycle"))) {
						throw RpgException.notAllowed(ref + " is not an active character.");
					}
					targets.add(c);
				}
			}
			var awarded = new ArrayList<Map<String, Object>>();
			var actorIds = new ArrayList<Long>();
			for (Row c : targets) {
				awarded.add(grantXp(tx, rules, c, amount));
				actorIds.add(c.id());
			}
			awarded.addAll(PartyXp.lockstep(tx, rules, campaign));
			LedgerService.append(tx, campaignId,
					new LedgerService.EventSpec("XP_AWARDED", amount + " XP awarded (" + src + "): " + reason, actorIds,
							"MINOR", "PARTY_KNOWN", src.equals("DISCRETIONARY") ? "ADMINISTRATIVE_OVERRIDE" : "GM",
							null, campaign.lng("current_location_id"), null, Map.of("amount", amount, "source", src)));
			if (src.equals("DISCRETIONARY")) {
				var audit = new LinkedHashMap<String, Object>();
				audit.put("campaign_id", campaignId);
				audit.put("kind", "DISCRETIONARY_XP");
				audit.put("actor", "gm");
				audit.put("provenance", "ADMINISTRATIVE_OVERRIDE");
				audit.put("reason", reason);
				audit.put("after_json", Json.write(Map.of("amount", amount, "characters", characterRefs)));
				audit.put("recorded_at", Instant.now().toString());
				tx.rawInsert("audit_record", audit);
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("source", src);
			result.put("xp_policy", PartyXp.policy(campaign));
			result.put("awarded", awarded);
			result.put("audited", src.equals("DISCRETIONARY"));
			LevelUpService.companionAdvancement(tx, rules, roller, campaign, result);
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return result;
		});
	}

	/** Adds XP and reports level-up eligibility (thresholds from the advancement table). */
	public static Map<String, Object> grantXp(Tx tx, RulesData rules, Row c, long amount) {
		long xp = c.lng("xp") + amount;
		tx.update("character", c.id(), Map.of("xp", xp, "revision", c.lng("revision") + 1));
		int level = tx.query("SELECT level FROM character_class WHERE character_id = ?", c.id()).stream()
				.mapToInt(r -> r.intOr("level", 1)).sum();
		var m = new LinkedHashMap<String, Object>();
		m.put("character", Ref.of(Ref.CHARACTER, c.id()));
		m.put("name", c.str("name"));
		m.put("xp_gained", amount);
		m.put("xp", xp);
		m.put("level", level == 0 ? null : level);
		m.put("level_up_eligible", level > 0 && rules.levelForXp(xp) > level);
		return m;
	}

	// ── transfer_player_control ────────────────────────────────────────

	public Map<String, Object> transferControl(String operationId, String campaignRef, String toRef, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("to", toRef);
		args.put("reason", reason);
		return db.mutate(Database.Mutation.of("transfer_player_control", campaignId, operationId, "PLAYER", args),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "transfer_player_control");
					Row target = CharacterService.character(tx, campaignId, toRef);
					if (!"ACTIVE".equals(target.str("lifecycle")) || "DEAD".equals(target.str("life_state"))) {
						throw RpgException.notAllowed(toRef + " is not a living, active character.");
					}
					if (!isPartyMember(tx, campaignId, target.id())) {
						throw RpgException.notAllowed(target.str(
								"name") + " is not a party member; only companions can become the player character.");
					}
					Optional<Row> current = tx.queryOne(
							"SELECT * FROM player_control_assignment WHERE campaign_id = ? AND seat = 'player-1' AND active = 1",
							campaignId);
					if (current.isPresent() && current.get().lng("character_id") == target.id()) {
						throw RpgException.invalidArgument(toRef + " is already the player character.");
					}
					Long previousId = current.map(r -> r.lng("character_id")).orElse(null);
					current.ifPresent(r -> tx.update("player_control_assignment", r.id(), Map.of("active", 0)));
					var cols = new LinkedHashMap<String, Object>();
					cols.put("campaign_id", campaignId);
					cols.put("seat", "player-1");
					cols.put("character_id", target.id());
					cols.put("since_journal_id", tx.journalId());
					cols.put("active", 1);
					tx.insert("player_control_assignment", cols);
					var actors = new ArrayList<Long>();
					actors.add(target.id());
					String previousName = previousId == null ? null : tx.get("character", previousId).str("name");
					if (previousId != null) {
						actors.add(previousId);
					}
					LedgerService.append(tx, campaignId, new LedgerService.EventSpec("PLAYER_CONTROL_TRANSFERRED",
							"The player now controls " + target.str("name") + (previousName == null ? ""
									: " (formerly " + previousName + ")") + (reason == null ? "" : ": " + reason) + ".",
							actors, "MAJOR", "PARTY_KNOWN", "PLAYER", null, campaign.lng("current_location_id"), null,
							null));
					HarnessState state = Harness.state(campaign);
					if (state == HarnessState.PLAYER_CHARACTER_TRANSFER || state == HarnessState.CHECKPOINT_DECISION) {
						boolean encounterRunning = tx.count(
								"SELECT COUNT(*) FROM encounter WHERE campaign_id = ? AND status IN ('RUNNING','WAITING_CHOICE')",
								campaignId) > 0;
						tx.update("campaign", campaignId, Map.of("harness_state",
								encounterRunning ? HarnessState.ENCOUNTER.name() : HarnessState.EXPLORATION.name(),
								"revision", campaign.lng("revision") + 1));
					}
					Row after = tx.get("campaign", campaignId);
					var result = new LinkedHashMap<String, Object>();
					result.put("player_character", Ref.of(Ref.CHARACTER, target.id()));
					result.put("name", target.str("name"));
					result.put("previous", Ref.ofNullable(Ref.CHARACTER, previousId));
					result.put("sheet", characters.sheet(tx, target, "PLAY"));
					result.put("meta", Harness.meta(after, List.of("Identity is unchanged: " + target.str(
							"name") + " keeps every relationship, memory and item.")));
					return result;
				});
	}

	/** Proficiency bonus for any character: by level for classed characters, by CR for creatures. */
	public static int proficiencyBonus(Tx tx, RulesData rules, Row c) {
		int level = tx.query("SELECT level FROM character_class WHERE character_id = ?", c.id()).stream()
				.mapToInt(r -> r.intOr("level", 1)).sum();
		if (level > 0) {
			return rules.proficiencyBonus(level);
		}
		if (usesStatBlock(tx, c)) {
			return rules.find(c.str("origin_content_ref")).map(d -> d.payload().get("proficiency_bonus"))
					.map(p -> ((Number) p).intValue()).orElse(2);
		}
		return 2;
	}

	/**
	 * Whether a character's numbers still come from the creature definition it was materialized from. True only while
	 * it has no class levels: taking a class turns a stat block into a character, whose Armor Class, skills, saves and
	 * attacks are all derived from its own abilities, proficiencies and equipment (RULES_ENGINE.md §3). Anything that
	 * would otherwise read a value straight off the stat block must ask this first, or a promoted companion silently
	 * keeps rolling the monster's numbers and every point of the build is thrown away.
	 */
	public static boolean usesStatBlock(Tx tx, Row c) {
		return !c.isNull("origin_content_ref")
				&& tx.count("SELECT COUNT(*) FROM character_class WHERE character_id = ?", c.id()) == 0;
	}

	/** Armor Class: creature stat block value for materialized creatures, derived from equipment otherwise. */
	public static int armorClass(Tx tx, RulesData rules, Row c) {
		if (!c.isNull("armor_class_override")) {
			// apply_gm_override SET_ARMOR_CLASS: fixed by fiat, effect bonuses and floors still apply.
			se.hirt.mcp.rpg.rules.Effects.Modifiers mods = se.hirt.mcp.rpg.rules.Effects.modifiers(tx, c.id());
			int value = c.integer("armor_class_override") + mods.acBonus;
			return mods.acFloor != null ? Math.max(value, mods.acFloor) : value;
		}
		if (usesStatBlock(tx, c)) {
			Optional<Integer> ac = rules.find(c.str("origin_content_ref")).map(d -> d.payload().get("ac"))
					.map(a -> ((Number) a).intValue());
			if (ac.isPresent()) {
				se.hirt.mcp.rpg.rules.Effects.Modifiers mods = se.hirt.mcp.rpg.rules.Effects.modifiers(tx, c.id());
				int value = ac.get() + mods.acBonus;
				return mods.acFloor != null ? Math.max(value, mods.acFloor) : value;
			}
		}
		return ((Number) se.hirt.mcp.rpg.inventory.InventoryService.armorClass(tx, rules, c).get("value")).intValue();
	}

	static int dexModifier(Row c) {
		return Rules.modifier(c.intOr("dex_score", 10));
	}
}
