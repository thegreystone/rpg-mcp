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
package se.hirt.mcp.rpg.magic;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.rules.Ability;
import se.hirt.mcp.rpg.rules.Combat;
import se.hirt.mcp.rpg.rules.Effects;
import se.hirt.mcp.rpg.rules.Rules;

import java.util.*;

/**
 * Spellcasting (DESIGN.md §13): spell slots as resources, prepared spells as traits, and a casting core shared by
 * {@code cast_spell} and the encounter {@code CAST} action. Structured mechanics are executed; everything else is text
 * the GM adjudicates. Citations: SRD 5.2.1 "Spells" chapter and class tables — [verify].
 */
public final class SpellService {

	public static final String SLOT_PREFIX = "slot:";
	public static final String PACT_SLOT = "pact_slot";

	/** Full-caster slots per spell level, indexed by character level 1..20 (SRD 5.2.1 class tables). */
	private static final int[][] FULL = {{2}, {3}, {4, 2}, {4, 3}, {4, 3, 2}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 2},
			{4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2, 1}, {4, 3, 3, 3, 2, 1}, {4, 3, 3, 3, 2, 1, 1},
			{4, 3, 3, 3, 2, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1, 1},
			{4, 3, 3, 3, 3, 1, 1, 1, 1}, {4, 3, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 3, 2, 2, 1, 1}};
	private static final int[][] HALF = {{2}, {2}, {3}, {3}, {4, 2}, {4, 2}, {4, 3}, {4, 3}, {4, 3, 2}, {4, 3, 2},
			{4, 3, 3}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 1}, {4, 3, 3, 2}, {4, 3, 3, 2}, {4, 3, 3, 3, 1},
			{4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2}};

	/** A character's spellcasting profile. */
	public record Casting(String classRef, String classSlug, Ability ability, String progression, int level,
	                      int cantripsKnown, int preparedCount, boolean ritual) {

		public boolean pact() {
			return "PACT".equals(progression);
		}

		/** Slots per spell level (index 0 = level 1). */
		public int[] slots() {
			if (pact()) {
				return new int[0];
			}
			int[][] table = "HALF".equals(progression) ? HALF : FULL;
			return table[Math.max(1, Math.min(20, level)) - 1];
		}

		public int pactSlots() {
			return level >= 17 ? 4 : level >= 11 ? 3 : level >= 2 ? 2 : 1;
		}

		public int pactSlotLevel() {
			return level >= 9 ? 5 : level >= 7 ? 4 : level >= 5 ? 3 : level >= 3 ? 2 : 1;
		}

		public int maxSpellLevel() {
			return pact() ? pactSlotLevel() : slots().length;
		}
	}

	private final Database db;
	private final RulesData rules;
	private final RollService roller;
	private final CharacterService characters;

	public SpellService(Database db, RulesData rules, RollService roller, CharacterService characters) {
		this.db = db;
		this.rules = rules;
		this.roller = roller;
		this.characters = characters;
	}

	// ── profile helpers ────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	public static Optional<Casting> castingOf(Tx tx, RulesData rules, Row c) {
		Optional<Row> cls = tx.queryOne("SELECT * FROM character_class WHERE character_id = ? ORDER BY id LIMIT 1",
				c.id());
		if (cls.isEmpty()) {
			return Optional.empty();
		}
		Optional<RulesData.Definition> def = rules.find(cls.get().str("class_ref"));
		if (def.isEmpty() || !(def.get().payload().get("spellcasting") instanceof Map<?, ?> sc)) {
			return Optional.empty();
		}
		Map<String, Object> s = (Map<String, Object>) sc;
		int level = cls.get().intOr("level", 1);
		List<Object> cantrips = (List<Object>) s.get("cantrips_known");
		List<Object> prepared = (List<Object>) s.get("prepared");
		String id = def.get().id();
		return Optional.of(
				new Casting(id, id.substring(id.lastIndexOf('/') + 1), Ability.parse(String.valueOf(s.get("ability"))),
						String.valueOf(s.get("progression")), level, ((Number) cantrips.get(level - 1)).intValue(),
						((Number) prepared.get(level - 1)).intValue(), Boolean.TRUE.equals(s.get("ritual"))));
	}

	public static int abilityModifier(Row c, Casting casting) {
		return Rules.modifier(c.intOr(casting.ability().column(), 10));
	}

	public static int saveDc(Tx tx, RulesData rules, Row c, Casting casting) {
		return 8 + RuntimeService.proficiencyBonus(tx, rules, c) + abilityModifier(c, casting);
	}

	public static int attackBonus(Tx tx, RulesData rules, Row c, Casting casting) {
		return RuntimeService.proficiencyBonus(tx, rules, c) + abilityModifier(c, casting);
	}

	/** Creates or resizes spell-slot resources for the character's current level (activation and level-up). */
	public static void initializeSlots(Tx tx, RulesData rules, Row c) {
		Optional<Casting> casting = castingOf(tx, rules, c);
		if (casting.isEmpty()) {
			return;
		}
		Casting cast = casting.get();
		if (cast.pact()) {
			upsertResource(tx, c.id(), PACT_SLOT, cast.pactSlots(), "SHORT_REST");
			return;
		}
		int[] slots = cast.slots();
		for (int i = 0; i < 9; i++) {
			int max = i < slots.length ? slots[i] : 0;
			if (max > 0) {
				upsertResource(tx, c.id(), SLOT_PREFIX + (i + 1), max, "LONG_REST");
			}
		}
	}

	private static void upsertResource(Tx tx, long characterId, String ref, int max, String recharge) {
		Optional<Row> existing = tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?",
				characterId, ref);
		if (existing.isPresent()) {
			int delta = max - existing.get().intOr("max", 0);
			int current = Math.max(0, Math.min(max, existing.get().intOr("current", 0) + Math.max(0, delta)));
			tx.update("resource_state", existing.get().id(), Map.of("max", max, "current", current));
		} else {
			var cols = new LinkedHashMap<String, Object>();
			cols.put("character_id", characterId);
			cols.put("resource_ref", ref);
			cols.put("current", max);
			cols.put("max", max);
			cols.put("recharge", recharge);
			tx.insert("resource_state", cols);
		}
	}

	/** The spellcasting block for character sheets. */
	public static Map<String, Object> sheet(Tx tx, RulesData rules, Row c) {
		Optional<Casting> casting = castingOf(tx, rules, c);
		if (casting.isEmpty()) {
			return null;
		}
		Casting cast = casting.get();
		var m = new LinkedHashMap<String, Object>();
		m.put("ability", cast.ability().name());
		m.put("save_dc", saveDc(tx, rules, c, cast));
		m.put("attack_bonus", attackBonus(tx, rules, c, cast));
		m.put("max_spell_level", cast.maxSpellLevel());
		m.put("cantrips_known_limit", cast.cantripsKnown());
		m.put("prepared_limit", cast.preparedCount());
		m.put("ritual_casting", cast.ritual());
		if (cast.pact()) {
			tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?", c.id(), PACT_SLOT)
					.ifPresent(r -> m.put("pact_slots",
							Map.of("current", r.intOr("current", 0), "max", r.intOr("max", 0), "level",
									cast.pactSlotLevel())));
		} else {
			var slots = new LinkedHashMap<String, Object>();
			for (Row r : tx.query(
					"SELECT * FROM resource_state WHERE character_id = ? AND resource_ref LIKE 'slot:%' ORDER BY resource_ref",
					c.id())) {
				slots.put(r.str("resource_ref").substring(SLOT_PREFIX.length()),
						Map.of("current", r.intOr("current", 0), "max", r.intOr("max", 0)));
			}
			m.put("slots", slots);
		}
		m.put("cantrips", spellNames(tx, rules, c.id(), "SPELL_KNOWN"));
		m.put("prepared", spellNames(tx, rules, c.id(), "SPELL_PREPARED"));
		m.put("concentrating_on",
				tx.query("SELECT DISTINCT source_description FROM active_effect WHERE concentration_character_id = ?",
						c.id()).stream().map(r -> r.str("source_description")).toList());
		return m;
	}

	private static List<String> spellNames(Tx tx, RulesData rules, long characterId, String kind) {
		return tx.query("SELECT content_ref FROM character_trait WHERE character_id = ? AND kind = ? ORDER BY id",
				characterId, kind).stream().map(r -> rules.find(r.str("content_ref"))
				.map(d -> d.name() + (kind.equals("SPELL_PREPARED") ? " (" + d.payload().get("level") + ")" : ""))
				.orElse(r.str("content_ref"))).toList();
	}

	/** Validates and stores a spell selection as traits (used by drafts and prepare_spells). */
	@SuppressWarnings("unchecked")
	public static List<Violation> setSpells(
			Tx tx, RulesData rules, Row c, Casting cast, List<Object> cantrips, List<Object> spells) {
		var v = new ArrayList<Violation>();
		if (cantrips != null) {
			var ids = new ArrayList<String>();
			for (Object o : cantrips) {
				RulesData.Definition d = rules.resolve("SPELL", String.valueOf(o))
						.orElseThrow(() -> RpgException.invalidArgument("Unknown spell '" + o + "'."));
				if (((Number) d.payload().get("level")).intValue() != 0) {
					v.add(new Violation("cantrips", "NOT_A_CANTRIP", d.name() + " is not a cantrip."));
				}
				if (!((List<Object>) d.payload().get("classes")).contains(cast.classSlug())) {
					v.add(new Violation("cantrips", "CLASS_LIST",
							d.name() + " is not on the " + cast.classSlug() + " spell list."));
				}
				if (tx.queryOne(
						"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SPELL_KNOWN' AND content_ref = ?",
						c.id(), d.id()).filter(t -> !se.hirt.mcp.rpg.character.Origins.SOURCE_CLASS.equals(
						se.hirt.mcp.rpg.character.Origins.sourceOf(t))).isPresent()) {
					v.add(new Violation("cantrips", "ALREADY_KNOWN",
							d.name() + " is already granted by a species trait or feat; choose a different cantrip (duplicates are re-chosen, SRD 5.2.1)."));
				}
				if (!ids.contains(d.id())) {
					ids.add(d.id());
				}
			}
			if (ids.size() > cast.cantripsKnown()) {
				v.add(new Violation("cantrips", "COUNT",
						"A level " + cast.level() + " " + cast.classSlug() + " knows at most " + cast.cantripsKnown() + " cantrips."));
			}
			if (v.isEmpty()) {
				replaceTraits(tx, c.id(), "SPELL_KNOWN", ids, Map.of("cantrip", true));
			}
		}
		if (spells != null) {
			var ids = new ArrayList<String>();
			for (Object o : spells) {
				RulesData.Definition d = rules.resolve("SPELL", String.valueOf(o))
						.orElseThrow(() -> RpgException.invalidArgument("Unknown spell '" + o + "'."));
				int level = ((Number) d.payload().get("level")).intValue();
				if (level == 0) {
					v.add(new Violation("spells", "CANTRIP", d.name() + " is a cantrip; list it under cantrips."));
				}
				if (level > cast.maxSpellLevel()) {
					v.add(new Violation("spells", "LEVEL",
							d.name() + " is level " + level + "; the highest slot is level " + cast.maxSpellLevel() + "."));
				}
				if (!((List<Object>) d.payload().get("classes")).contains(cast.classSlug())) {
					v.add(new Violation("spells", "CLASS_LIST",
							d.name() + " is not on the " + cast.classSlug() + " spell list."));
				}
				if (tx.queryOne(
						"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SPELL_PREPARED' AND content_ref = ?",
						c.id(), d.id()).filter(t -> !se.hirt.mcp.rpg.character.Origins.SOURCE_CLASS.equals(
						se.hirt.mcp.rpg.character.Origins.sourceOf(t))).isPresent()) {
					v.add(new Violation("spells", "ALREADY_PREPARED",
							d.name() + " is already always prepared from a species trait or feat; choose a different spell."));
				}
				if (!ids.contains(d.id())) {
					ids.add(d.id());
				}
			}
			if (ids.size() > cast.preparedCount()) {
				v.add(new Violation("spells", "COUNT",
						"A level " + cast.level() + " " + cast.classSlug() + " prepares at most " + cast.preparedCount() + " spells."));
			}
			if (v.isEmpty()) {
				replaceTraits(tx, c.id(), "SPELL_PREPARED", ids, null);
			}
		}
		return v;
	}

	private static void replaceTraits(
			Tx tx, long characterId, String kind, List<String> ids, Map<String, Object> payload) {
		// Only the class-chosen list is replaced; species- and feat-granted spells stay.
		for (Row t : tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = ?", characterId, kind)) {
			if (se.hirt.mcp.rpg.character.Origins.SOURCE_CLASS.equals(se.hirt.mcp.rpg.character.Origins.sourceOf(t))) {
				tx.delete("character_trait", t.id());
			}
		}
		for (String id : ids) {
			var cols = new LinkedHashMap<String, Object>();
			cols.put("character_id", characterId);
			cols.put("kind", kind);
			cols.put("content_ref", id);
			cols.put("payload_json", payload == null ? null : Json.write(payload));
			tx.insert("character_trait", cols);
		}
	}

	// ── prepare_spells ─────────────────────────────────────────────────

	public Map<String, Object> prepare(
			String operationId, String campaignRef, String characterRef, List<Object> cantrips, List<Object> spells) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("character", characterRef);
		args.put("cantrips", cantrips);
		args.put("spells", spells);
		return db.mutate(Database.Mutation.of("prepare_spells", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "prepare_spells");
			Row c = CharacterService.character(tx, campaignId, characterRef);
			if (!"ACTIVE".equals(c.str("lifecycle"))) {
				throw RpgException.notAllowed(
						characterRef + " is not an active character (edit drafts with update_character_draft).");
			}
			Casting cast = castingOf(tx, rules, c).orElseThrow(
					() -> RpgException.notAllowed(c.str("name") + " has no spellcasting."));
			if (cantrips == null && spells == null) {
				throw RpgException.invalidArgument("Provide cantrips and/or spells.");
			}
			List<Violation> violations = setSpells(tx, rules, c, cast, cantrips, spells);
			if (!violations.isEmpty()) {
				throw RpgException.validation(violations);
			}
			tx.update("character", c.id(), Map.of("revision", c.lng("revision") + 1));
			tx.touched(Ref.of(Ref.CHARACTER, c.id()), c.lng("revision") + 1);
			var result = new LinkedHashMap<String, Object>();
			result.put("character", Ref.of(Ref.CHARACTER, c.id()));
			result.put("spellcasting", sheet(tx, rules, tx.get("character", c.id())));
			result.put("note",
					"Class rules govern when prepared spells may change (e.g. after a Long Rest); the engine records the current list.");
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── cast_spell (outside encounters) ────────────────────────────────

	public Map<String, Object> cast(
			String operationId, String campaignRef, String casterRef, String spell,
			Integer slotLevel, List<String> targets, Map<String, Object> options) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("caster", casterRef);
		args.put("spell", spell);
		args.put("slot_level", slotLevel);
		args.put("targets", targets);
		args.put("options", options);
		return db.mutate(Database.Mutation.of("cast_spell", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "cast_spell");
			Row caster = CharacterService.character(tx, campaignId, casterRef);
			if (!"ACTIVE".equals(caster.str("lifecycle")) || !"ALIVE".equals(caster.str("life_state"))) {
				throw RpgException.notAllowed(caster.str("name") + " cannot cast right now.");
			}
			Map<String, Object> result = castCore(tx, rules, roller, campaignId, caster, spell, slotLevel, targets,
					options, null, 0);
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── the casting core ───────────────────────────────────────────────

	/**
	 * Validates preparation and slots, spends the slot, resolves the spell's structured mechanics against the targets,
	 * applies effects and concentration, and records every roll. Shared with encounters.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> castCore(
			Tx tx, RulesData rules, RollService roller, long campaignId, Row caster,
			String spellText, Integer slotLevel, List<String> targetRefs, Map<String, Object> options, Long encounterId,
			long round) {
		RulesData.Definition def = rules.resolve("SPELL", spellText).orElseThrow(() -> RpgException.invalidArgument(
				"Unknown spell '" + spellText + "'; see get_content_definitions with kind SPELL."));
		Map<String, Object> p = def.payload();
		int level = ((Number) p.get("level")).intValue();
		Optional<Casting> castingOpt = castingOf(tx, rules, caster);
		String traitKind = level == 0 ? "SPELL_KNOWN" : "SPELL_PREPARED";
		Row trait = tx.queryOne("SELECT * FROM character_trait WHERE character_id = ? AND kind = ? AND content_ref = ?",
				caster.id(), traitKind, def.id()).orElseThrow(() -> RpgException.validation(
				List.of(new Violation("spell", level == 0 ? "NOT_KNOWN" : "NOT_PREPARED",
						caster.str("name") + (level == 0 ? " does not know "
								: " has not prepared ") + def.name() + "."))));
		Map<String, Object> traitPayload = trait.isNull("payload_json") ? Map.of() : trait.map("payload_json");
		// Species- and feat-granted spells use their recorded ability (Magic Initiate, lineages; SRD 5.2.1).
		Ability grantedAbility = traitPayload.get("ability") instanceof String ga ? Ability.parse(ga) : null;
		if (grantedAbility == null && castingOpt.isEmpty() && level == 0) {
			grantedAbility = Ability.CHA;
		}
		Map<String, Object> opts = options == null ? Map.of() : options;
		// Slot — or a free cast granted by the species trait or feat that taught the spell.
		int used = 0;
		Map<String, Object> slotInfo = null;
		if (level > 0) {
			Optional<Row> free = traitPayload.get("free_cast_resource") instanceof String fr ? tx.queryOne(
					"SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?", caster.id(), fr)
					: Optional.empty();
			if (free.isPresent() && free.get().intOr("current", 0) > 0 && (slotLevel == null || slotLevel == level)) {
				used = level;
				tx.update("resource_state", free.get().id(), Map.of("current", free.get().intOr("current", 0) - 1));
				slotInfo = Map.of("free_cast", free.get().str("resource_ref"), "remaining",
						free.get().intOr("current", 0) - 1, "note",
						"cast without a spell slot; the use returns on a Long Rest");
			} else if (castingOpt.isEmpty()) {
				throw RpgException.insufficientResource(caster.str(
						"name") + " has no free cast of " + def.name() + " left (it returns on a Long Rest) and no spell slots to cast it with.");
			} else if (castingOpt.get().pact()) {
				Casting cast = castingOpt.get();
				used = cast.pactSlotLevel();
				if (level > used) {
					throw RpgException.validation(List.of(new Violation("spell", "LEVEL",
							def.name() + " is level " + level + "; pact slots are level " + used + ".")));
				}
				Row res = tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?",
						caster.id(), PACT_SLOT).orElseThrow(
						() -> RpgException.insufficientResource(caster.str("name") + " has no pact slots."));
				if (res.intOr("current", 0) <= 0) {
					throw RpgException.insufficientResource(
							caster.str("name") + " has no pact slots left (they return on a Short Rest).");
				}
				tx.update("resource_state", res.id(), Map.of("current", res.intOr("current", 0) - 1));
				slotInfo = Map.of("pact_slot_level", used, "remaining", res.intOr("current", 0) - 1);
			} else {
				used = slotLevel == null ? level : slotLevel;
				if (used < level) {
					throw RpgException.validation(List.of(new Violation("slot_level", "TOO_LOW",
							def.name() + " needs a slot of level " + level + " or higher.")));
				}
				Optional<Row> res = tx.queryOne(
						"SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?", caster.id(),
						SLOT_PREFIX + used);
				if (res.isEmpty() || res.get().intOr("max", 0) == 0) {
					throw RpgException.validation(List.of(new Violation("slot_level", "NO_SUCH_SLOT",
							caster.str("name") + " has no level " + used + " spell slots.")));
				}
				if (res.get().intOr("current", 0) <= 0) {
					throw RpgException.insufficientResource(
							caster.str("name") + " has no level " + used + " spell slots left.");
				}
				tx.update("resource_state", res.get().id(), Map.of("current", res.get().intOr("current", 0) - 1));
				slotInfo = Map.of("slot_level", used, "remaining", res.get().intOr("current", 0) - 1);
			}
		}
		int upcast = level == 0 ? 0 : Math.max(0, used - level);
		Map<String, Object> mech =
				p.get("mechanics") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("kind", "UTILITY");
		String kind = String.valueOf(mech.getOrDefault("kind", "UTILITY"));
		int prof = RuntimeService.proficiencyBonus(tx, rules, caster);
		int mod = grantedAbility != null ? Rules.modifier(caster.intOr(grantedAbility.column(), 10))
				: castingOpt.map(k -> abilityModifier(caster, k)).orElse(0);
		int dc = 8 + prof + mod;
		int atk = prof + mod;
		boolean concentration = Boolean.TRUE.equals(p.get("concentration"));
		var result = new LinkedHashMap<String, Object>();
		result.put("spell", def.id());
		result.put("name", def.name());
		result.put("level", level);
		result.put("slot", slotInfo);
		result.put("mechanics", kind);
		result.put("save_dc", dc);
		result.put("spell_attack_bonus", atk);
		result.put("concentration", concentration);
		var endedConcentration = new ArrayList<String>();
		if (concentration) {
			endedConcentration.addAll(Effects.breakConcentration(tx, caster.id()));
		}
		var targets = new ArrayList<Row>();
		for (String ref : targetRefs == null ? List.<String> of() : targetRefs) {
			targets.add(CharacterService.character(tx, campaignId, ref));
		}
		var perTarget = new ArrayList<Map<String, Object>>();
		var effectsCreated = new ArrayList<String>();
		String source = def.name() + " (" + caster.str("name") + ")";
		Long concentrator = concentration ? caster.id() : null;
		int casterLevel = castingOpt.map(Casting::level)
				.orElse(Math.max(1, se.hirt.mcp.rpg.character.Origins.characterLevel(tx, caster)));
		switch (kind) {
		case "ATTACK" -> {
			if (targets.isEmpty()) {
				throw RpgException.invalidArgument(def.name() + " needs at least one target.");
			}
			int shots =
					mech.get("rays") instanceof Number r ? r.intValue() + upcastCount(mech, "per_level_rays", upcast)
							: 1;
			if (mech.get("beams_scaling") instanceof Map<?, ?> bs) {
				shots = scaledInt((Map<String, Object>) bs, casterLevel, 1);
			}
			var queue = new ArrayList<Row>();
			for (int i = 0; i < shots; i++) {
				queue.add(targets.get(Math.min(i, targets.size() - 1)));
			}
			if (targets.size() > shots) {
				queue = new ArrayList<>(targets);
			}
			for (Row target : queue) {
				perTarget.add(spellAttack(tx, rules, roller, campaignId, caster, target, def, mech, atk, mod, upcast,
						casterLevel, encounterId, round, concentrator, source, effectsCreated));
			}
		}
		case "SAVE" -> {
			if (targets.isEmpty()) {
				throw RpgException.invalidArgument(
						def.name() + " needs at least one target (the creatures in the area).");
			}
			for (Row target : targets) {
				perTarget.add(
						savingThrow(tx, rules, roller, campaignId, caster, target, def, mech, dc, upcast, casterLevel,
								encounterId, round, concentrator, source, effectsCreated));
			}
		}
		case "HEAL" -> {
			if (targets.isEmpty()) {
				targets.add(caster);
			}
			String dice = scaledDice(String.valueOf(mech.get("dice")), mech, upcast, casterLevel);
			for (Row target : targets) {
				Roll roll = roller.roll(dice);
				CharacterService.recordRoll(tx, campaignId, "healing " + def.name(), roll);
				int amount = Math.max(0, roll.total()) + (Boolean.TRUE.equals(mech.get("add_modifier")) ? mod : 0);
				Map<String, Object> healed = RuntimeService.heal(tx, tx.get("character", target.id()), amount,
						def.name());
				if (mech.get("cures") instanceof List<?> cures) {
					for (Object cond : cures) {
						removeCondition(tx, target.id(), cond.toString());
					}
				}
				var t = targetView(target);
				t.put("roll", roll.toMap());
				t.putAll(healed);
				perTarget.add(t);
			}
		}
		case "AUTO" -> {
			if (targets.isEmpty()) {
				throw RpgException.invalidArgument(def.name() + " needs at least one target.");
			}
			int darts = ((Number) mech.get("darts")).intValue() + upcastCount(mech, "per_level_darts", upcast);
			List<Map<String, Object>> perDart = (List<Map<String, Object>>) mech.get("per_dart");
			for (int i = 0; i < darts; i++) {
				Row target = tx.get("character", targets.get(Math.min(i, targets.size() - 1)).id());
				var rolled = new ArrayList<Combat.RolledDamage>();
				for (Map<String, Object> d : perDart) {
					Roll roll = roller.roll(String.valueOf(d.get("dice")));
					CharacterService.recordRoll(tx, campaignId, def.name() + " dart", roll);
					rolled.add(new Combat.RolledDamage(String.valueOf(d.get("type")), roll, Math.max(0, roll.total())));
				}
				Combat.DamageResult dr = Combat.applyDamage(target.intOr("current_hp", 0), target.intOr("temp_hp", 0),
						target.intOr("max_hp", 1), rolled, RuntimeService.defensesOf(tx, rules, target));
				var t = targetView(target);
				t.put("dart", i + 1);
				t.putAll(RuntimeService.applyDamageResult(tx, rules, roller, target, dr, false, caster.id(),
						"slain by " + def.name()));
				perTarget.add(t);
				markDefeated(tx, encounterId, target.id());
			}
		}
		case "TEMP_HP" -> {
			if (targets.isEmpty()) {
				targets.add(caster);
			}
			String expr = String.valueOf(mech.get("amount"));
			int flat = upcastCount(mech, "per_level_flat", upcast);
			for (Row target : targets) {
				Roll roll = roller.roll(expr);
				int amount = Math.max(0, roll.total()) + flat;
				int newTemp = Math.max(target.intOr("temp_hp", 0), amount);
				tx.update("character", target.id(), Map.of("temp_hp", newTemp, "revision", target.lng("revision") + 1));
				var t = targetView(target);
				t.put("temp_hp", newTemp);
				perTarget.add(t);
			}
		}
		case "BUFF" -> {
			String scope = String.valueOf(mech.getOrDefault("targets", "ONE"));
			if (targets.isEmpty() && (scope.equals("SELF") || scope.equals("ONE") || scope.startsWith(
					"UP_TO") || scope.startsWith("PARTY"))) {
				targets.add(caster);
			}
			Map<String, Object> modifiers =
					mech.get("modifiers") instanceof Map<?, ?> mm ? new LinkedHashMap<>((Map<String, Object>) mm)
							: new LinkedHashMap<>();
			String condition = mech.get("condition") == null ? null : mech.get("condition").toString();
			for (Row target : targets) {
				var mods = new LinkedHashMap<>(modifiers);
				if (Boolean.TRUE.equals(mods.get("against_target")) && opts.get("against") != null) {
					mods.put("against_target_id",
							CharacterService.character(tx, campaignId, opts.get("against").toString()).id());
				}
				Map<String, Object> duration = duration(tx, campaignId, mech, upcast, encounterId, round, def.name());
				long id = Effects.add(tx, campaignId, target.id(), caster.id(), def.id(), source,
						condition == null ? "srd5e:effect/" + String.valueOf(mech.getOrDefault("effect", def.id()))
								.toLowerCase() : RuntimeService.conditionRef(condition), mods, duration, concentrator,
						def.id(), "PLAYER");
				effectsCreated.add(Ref.of("effect", id));
				var t = targetView(target);
				t.put("effect", mech.getOrDefault("effect", condition));
				t.put("duration", duration);
				perTarget.add(t);
			}
		}
		case "CURE" -> {
			if (targets.isEmpty()) {
				targets.add(caster);
			}
			Object wanted = opts.get("condition");
			for (Row target : targets) {
				var removed = new ArrayList<String>();
				for (Object cond : (List<Object>) mech.getOrDefault("conditions", List.of())) {
					if (wanted != null && !wanted.toString().equalsIgnoreCase(cond.toString())) {
						continue;
					}
					if (removeCondition(tx, target.id(), cond.toString())) {
						removed.add(cond.toString());
						if (wanted == null) {
							break;
						}
					}
				}
				var t = targetView(target);
				t.put("removed_conditions", removed);
				perTarget.add(t);
			}
		}
		case "RESURRECT" -> {
			if (targets.isEmpty()) {
				throw RpgException.invalidArgument(def.name() + " needs a dead target.");
			}
			for (Row target : targets) {
				if (!"DEAD".equals(target.str("life_state"))) {
					throw RpgException.validation(
							List.of(new Violation("targets", "NOT_DEAD", target.str("name") + " is not dead.")));
				}
				int hp = "FULL".equals(String.valueOf(mech.get("hp"))) ? target.intOr("max_hp", 1)
						: ((Number) mech.getOrDefault("hp", 1)).intValue();
				var cols = new LinkedHashMap<String, Object>();
				cols.put("life_state", "ALIVE");
				cols.put("current_hp", Math.min(target.intOr("max_hp", hp), hp));
				cols.put("death_saves_json", null);
				cols.put("revision", target.lng("revision") + 1);
				tx.update("character", target.id(), cols);
				se.hirt.mcp.rpg.ledger.LedgerService.append(tx, campaignId,
						new se.hirt.mcp.rpg.ledger.LedgerService.EventSpec("CHARACTER_REVIVED",
								target.str("name") + " was returned to life by " + caster.str(
										"name") + "'s " + def.name() + ".", List.of(target.id(), caster.id()),
								"CRITICAL", "PARTY_KNOWN", "MECHANICAL_CONSEQUENCE", null, target.lng("location_id"),
								null, Map.of("spell", def.id())));
				var t = targetView(target);
				t.put("revived", true);
				t.put("hp", Math.min(target.intOr("max_hp", hp), hp));
				t.put("note",
						"Time-since-death and material components are the GM's to verify; a former party member rejoins via update_party_membership.");
				perTarget.add(t);
			}
		}
		default -> {
			if (Boolean.TRUE.equals(mech.get("stabilize"))) {
				for (Row target : targets) {
					if ("DYING".equals(target.str("life_state"))) {
						Map<String, Object> saves = Combat.freshDeathSaves();
						saves.put("stable", true);
						tx.update("character", target.id(),
								Map.of("death_saves_json", Json.write(saves), "revision", target.lng("revision") + 1));
						var t = targetView(target);
						t.put("stable", true);
						perTarget.add(t);
					}
				}
			}
			if (concentration) {
				long id = Effects.add(tx, campaignId, caster.id(), caster.id(), def.id(), source,
						"srd5e:effect/concentrating", null,
						duration(tx, campaignId, p, upcast, encounterId, round, def.name()), caster.id(), def.id(),
						"PLAYER");
				effectsCreated.add(Ref.of("effect", id));
			}
			result.put("note", "No structured mechanics for " + def.name() + "; adjudicate from the rules text.");
		}
		}
		result.put("targets", perTarget);
		result.put("effects_created", effectsCreated);
		if (!endedConcentration.isEmpty()) {
			result.put("concentration_ended", endedConcentration);
		}
		result.put("rules_text", p.get("text"));
		if (encounterId != null) {
			var log = new LinkedHashMap<String, Object>();
			log.put("campaign_id", campaignId);
			log.put("encounter_id", encounterId);
			log.put("round", round);
			log.put("actor_character_id", caster.id());
			log.put("kind", "CAST");
			log.put("summary",
					caster.str("name") + " casts " + def.name() + (used > level ? " (level " + used + " slot)"
							: "") + ": " + perTarget.stream()
							.map(t -> t.get("name") + (t.get("hit") != null ? (Boolean.TRUE.equals(t.get("hit"))
																			   ? " hit" : " missed")
									: t.get("saved") != null ? (Boolean.TRUE.equals(t.get("saved")) ? " saved"
											: " failed") : "") + (t.get("damage") != null ? " " + t.get(
									"damage") + " dmg" : "") + (t.get("healed") != null ? " +" + t.get("healed") + " HP"
									: "")).toList());
			log.put("payload_json", null);
			log.put("journal_id", tx.journalId());
			tx.insert("encounter_log", log);
		}
		return result;
	}

	private static Map<String, Object> targetView(Row target) {
		var t = new LinkedHashMap<String, Object>();
		t.put("target", Ref.of(Ref.CHARACTER, target.id()));
		t.put("name", target.str("name"));
		return t;
	}

	private static int upcastCount(Map<String, Object> mech, String key, int upcast) {
		if (upcast <= 0 || !(mech.get("upcast") instanceof Map<?, ?> u)) {
			return 0;
		}
		Object per = u.get(key);
		return per instanceof Number n ? n.intValue() * upcast : 0;
	}

	/** Damage/healing dice after cantrip scaling and upcasting. */
	@SuppressWarnings("unchecked")
	static String scaledDice(String base, Map<String, Object> mech, int upcast, int casterLevel) {
		String dice = base;
		if (mech.get("cantrip_scaling") instanceof Map<?, ?> cs) {
			for (var e : ((Map<String, Object>) cs).entrySet()) {
				if (casterLevel >= Integer.parseInt(e.getKey())) {
					dice = String.valueOf(e.getValue());
				}
			}
		}
		if (upcast > 0 && mech.get("upcast") instanceof Map<?, ?> u && ((Map<String, Object>) u).get(
				"per_level_dice") instanceof String per) {
			int step = ((Map<String, Object>) u).get("step") instanceof Number s ? s.intValue() : 1;
			int times = upcast / step;
			var m = java.util.regex.Pattern.compile("^(\\d+)d(\\d+)(.*)$").matcher(per);
			var b = java.util.regex.Pattern.compile("^(\\d+)d(\\d+)(.*)$").matcher(dice);
			if (times > 0 && m.matches() && b.matches() && m.group(2).equals(b.group(2))) {
				dice = (Integer.parseInt(b.group(1)) + times * Integer.parseInt(m.group(1))) + "d" + b.group(
						2) + b.group(3);
			} else if (times > 0) {
				dice = dice + "+" + per.replace("d", "d");
				for (int i = 1; i < times; i++) {
					dice = dice + "+" + per;
				}
			}
		}
		return dice;
	}

	@SuppressWarnings("unchecked")
	private static int scaledInt(Map<String, Object> scaling, int casterLevel, int base) {
		int v = base;
		for (var e : scaling.entrySet()) {
			if (casterLevel >= Integer.parseInt(e.getKey())) {
				v = ((Number) e.getValue()).intValue();
			}
		}
		return v;
	}

	@SuppressWarnings("unchecked")
	private static List<Combat.RolledDamage> rollSpellDamage(
			Tx tx, RollService roller, long campaignId, RulesData.Definition def, Map<String, Object> mech, int upcast,
			int casterLevel, boolean critical, int modifierIfAny, Map<String, Object> opts) {
		var out = new ArrayList<Combat.RolledDamage>();
		Object dmg = mech.get("damage");
		if (!(dmg instanceof List<?> parts)) {
			return out;
		}
		boolean first = true;
		for (Object o : parts) {
			Map<String, Object> part = (Map<String, Object>) o;
			String dice = scaledDice(String.valueOf(part.get("dice")), mech, first ? upcast : 0, casterLevel);
			if (critical) {
				dice = Combat.critical(dice);
			}
			if (first && modifierIfAny != 0) {
				dice = dice + (modifierIfAny > 0 ? "+" + modifierIfAny : Integer.toString(modifierIfAny));
			}
			String type = String.valueOf(part.get("type"));
			if (mech.get("choose_type") instanceof List<?> choices && opts.get(
					"damage_type") != null && choices.contains(opts.get("damage_type").toString().toLowerCase())) {
				type = opts.get("damage_type").toString().toLowerCase();
			}
			Roll roll = roller.roll(dice);
			CharacterService.recordRoll(tx, campaignId, "damage " + def.name() + " (" + type + ")", roll);
			out.add(new Combat.RolledDamage(type, roll, Math.max(0, roll.total())));
			first = false;
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> spellAttack(
			Tx tx, RulesData rules, RollService roller, long campaignId, Row caster, Row targetRow,
			RulesData.Definition def, Map<String, Object> mech, int atk, int mod, int upcast, int casterLevel,
			Long encounterId, long round, Long concentrator, String source, List<String> effectsCreated) {
		Row target = tx.get("character", targetRow.id());
		var t = targetView(target);
		if ("DEAD".equals(target.str("life_state"))) {
			t.put("skipped", "already dead");
			return t;
		}
		Effects.Modifiers casterMods = Effects.modifiers(tx, caster.id());
		Effects.Modifiers targetMods = Effects.modifiers(tx, target.id());
		boolean unconscious = "DYING".equals(target.str("life_state"));
		boolean advantage = unconscious || targetMods.advantageOnAttacksAgainst;
		boolean disadvantage = targetMods.disadvantageOnAttacksAgainst || tx.count(
				"SELECT COUNT(*) FROM active_effect WHERE character_id = ? AND condition_ref = 'srd5e:effect/dodging'",
				target.id()) > 0;
		String dice = advantage && !disadvantage ? "2d20kh1" : disadvantage && !advantage ? "2d20kl1" : "1d20";
		int bonus = atk + casterMods.attackBonus;
		Roll roll = roller.roll(dice + (bonus >= 0 ? "+" + bonus : Integer.toString(bonus)));
		long rollId = CharacterService.recordRoll(tx, campaignId, "spell attack " + def.name(), roll);
		int total = roll.total();
		var bonusDice = new ArrayList<Map<String, Object>>();
		for (String bd : casterMods.attackBonusDice) {
			Roll b = roller.roll(bd);
			total += b.total();
			bonusDice.add(b.toMap());
		}
		int natural = roll.dice().get(0);
		int ac = RuntimeService.armorClass(tx, rules, target);
		boolean melee = "MELEE_SPELL".equals(mech.get("attack"));
		boolean critical = natural == 20 || (unconscious && melee);
		boolean hit = natural != 1 && (natural == 20 || total >= ac);
		var rm = roll.toMap();
		rm.put("roll_ref", Ref.of(Ref.ROLL, rollId));
		t.put("attack_roll", rm);
		if (!bonusDice.isEmpty()) {
			t.put("bonus_dice", bonusDice);
		}
		t.put("total", total);
		t.put("target_armor_class", ac);
		t.put("hit", hit);
		t.put("critical", hit && critical);
		if (hit) {
			List<Combat.RolledDamage> damages = rollSpellDamage(tx, roller, campaignId, def, mech, upcast, casterLevel,
					critical, Boolean.TRUE.equals(mech.get("add_modifier")) ? mod : 0, Map.of());
			if (!damages.isEmpty()) {
				Combat.DamageResult dr = Combat.applyDamage(target.intOr("current_hp", 0), target.intOr("temp_hp", 0),
						target.intOr("max_hp", 1), damages, RuntimeService.defensesOf(tx, rules, target));
				t.putAll(RuntimeService.applyDamageResult(tx, rules, roller, target, dr, critical, caster.id(),
						"slain by " + def.name()));
				markDefeated(tx, encounterId, target.id());
				if (Boolean.TRUE.equals(mech.get("drain_heal_half"))) {
					RuntimeService.heal(tx, tx.get("character", caster.id()), dr.totalDealt() / 2, def.name());
					t.put("caster_healed", dr.totalDealt() / 2);
				}
			}
			if (mech.get("on_hit_condition") instanceof String cond && !"DEAD".equals(
					String.valueOf(t.get("life_state")))) {
				long rounds = mech.get("on_hit_condition_duration_rounds") instanceof Number n ? n.longValue() : 1;
				long id = Effects.add(tx, campaignId, target.id(), caster.id(), def.id(), source,
						RuntimeService.conditionRef(cond), null,
						Effects.rounds(tx, campaignId, encounterId, round, rounds, def.name()), concentrator,
						def.id() + ":" + cond, "PLAYER");
				effectsCreated.add(Ref.of("effect", id));
				t.put("condition", cond);
			}
		}
		return t;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> savingThrow(
			Tx tx, RulesData rules, RollService roller, long campaignId, Row caster, Row targetRow,
			RulesData.Definition def, Map<String, Object> mech, int dc, int upcast, int casterLevel, Long encounterId,
			long round, Long concentrator, String source, List<String> effectsCreated) {
		Row target = tx.get("character", targetRow.id());
		var t = targetView(target);
		if ("DEAD".equals(target.str("life_state"))) {
			t.put("skipped", "already dead");
			return t;
		}
		String saveAbility = String.valueOf(mech.get("save"));
		boolean automatic = Boolean.TRUE.equals(mech.get("automatic")) || "NONE".equals(saveAbility);
		boolean saved = false;
		if (mech.get("automatic_if_hp_at_most") instanceof Number threshold && target.intOr("current_hp",
				0) <= threshold.intValue()) {
			automatic = true;
			t.put("automatic", "HP at or below " + threshold);
		}
		if (!automatic) {
			Ability ability = Ability.parse(saveAbility);
			int bonus = saveBonus(tx, rules, target, ability);
			Effects.Modifiers targetMods = Effects.modifiers(tx, target.id());
			bonus += targetMods.saveBonus;
			Roll roll = roller.roll("1d20" + (bonus >= 0 ? "+" + bonus : Integer.toString(bonus)));
			long rollId = CharacterService.recordRoll(tx, campaignId,
					ability.name() + " save vs " + def.name() + " " + Ref.of(Ref.CHARACTER, target.id()), roll);
			int total = roll.total();
			for (String bd : targetMods.saveBonusDice) {
				total += roller.roll(bd).total();
			}
			saved = total >= dc;
			var rm = roll.toMap();
			rm.put("roll_ref", Ref.of(Ref.ROLL, rollId));
			t.put("save", ability.name());
			t.put("save_roll", rm);
			t.put("save_total", total);
			t.put("dc", dc);
			t.put("saved", saved);
		}
		String onSuccess = String.valueOf(mech.getOrDefault("on_success", "HALF"));
		List<Combat.RolledDamage> damages = rollSpellDamage(tx, roller, campaignId, def, mech, upcast, casterLevel,
				false, 0, Map.of());
		if (!damages.isEmpty() && !(saved && onSuccess.equals("NONE"))) {
			if (saved && onSuccess.equals("HALF")) {
				damages = damages.stream().map(d -> new Combat.RolledDamage(d.type(), d.roll(), d.amount() / 2))
						.toList();
				t.put("halved", true);
			}
			Combat.DamageResult dr = Combat.applyDamage(target.intOr("current_hp", 0), target.intOr("temp_hp", 0),
					target.intOr("max_hp", 1), damages, RuntimeService.defensesOf(tx, rules, target));
			t.putAll(RuntimeService.applyDamageResult(tx, rules, roller, target, dr, false, caster.id(),
					"slain by " + def.name()));
			markDefeated(tx, encounterId, target.id());
		}
		if (!saved && !"DEAD".equals(String.valueOf(t.getOrDefault("life_state", target.str("life_state"))))) {
			var conditions = new ArrayList<String>();
			if (mech.get("on_fail_condition") instanceof String c) {
				conditions.add(c);
			}
			if (mech.get("also_condition") instanceof String c) {
				conditions.add(c);
			}
			Map<String, Object> duration = duration(tx, campaignId, mech, upcast, encounterId, round, def.name());
			// A spell whose description grants the target another save carries that instruction onto the effect,
			// so the encounter can roll it at the end of the target's turn instead of the GM remembering to
			// (SRD 5.2.1 spell descriptions; RULES_ENGINE.md §3).
			Map<String, Object> repeat = null;
			if (mech.get("repeat_save") instanceof String note && mech.get("save") instanceof String ability) {
				var spec = new LinkedHashMap<String, Object>();
				spec.put("ability", ability);
				spec.put("dc", dc);
				spec.put("failures", 1);
				spec.put("note", note);
				spec.put("spell", def.name());
				if (note.toLowerCase().contains("second failure") && mech.get("also") instanceof String escalation) {
					spec.put("on_second_failure", escalation);
				} else if (note.toLowerCase().contains("unconscious")) {
					spec.put("on_second_failure", "UNCONSCIOUS");
				}
				repeat = Map.of("repeat_save", spec);
			}
			for (String cond : conditions) {
				if (Effects.modifiers(tx, target.id()).immuneConditions.contains(cond)) {
					t.put("immune", cond);
					continue;
				}
				long id = Effects.add(tx, campaignId, target.id(), caster.id(), def.id(), source,
						RuntimeService.conditionRef(cond), repeat, duration, concentrator, def.id() + ":" + cond,
						"PLAYER");
				effectsCreated.add(Ref.of("effect", id));
			}
			if (!conditions.isEmpty()) {
				t.put("conditions", conditions);
				t.put("duration", duration);
			}
			if (mech.get("on_fail_effect") instanceof Map<?, ?> fx) {
				Map<String, Object> effect = (Map<String, Object>) fx;
				if (Boolean.TRUE.equals(effect.get("advantage_on_attacks_against"))) {
					long id = Effects.add(tx, campaignId, target.id(), caster.id(), def.id(), source,
							"srd5e:effect/" + def.id().substring(def.id().lastIndexOf('/') + 1),
							Map.of("advantage_on_attacks_against", true), duration, concentrator, def.id(), "PLAYER");
					effectsCreated.add(Ref.of("effect", id));
				}
				if (Boolean.TRUE.equals(effect.get("dies"))) {
					var cols = new LinkedHashMap<String, Object>();
					cols.put("current_hp", 0);
					cols.put("life_state", "DEAD");
					cols.put("revision", target.lng("revision") + 1);
					tx.update("character", target.id(), cols);
					RuntimeService.endMemberships(tx, campaignId, target.id(), "DEAD");
					markDefeated(tx, encounterId, target.id());
					t.put("life_state", "DEAD");
					t.put("died", true);
				}
				t.put("on_fail_effect", effect);
			}
		}
		return t;
	}

	/** Saving throw bonus: ability modifier plus proficiency for classed characters, stat-block value for creatures. */
	@SuppressWarnings("unchecked")
	public static int saveBonus(Tx tx, RulesData rules, Row target, Ability ability) {
		if (RuntimeService.usesStatBlock(tx, target)) {
			Optional<Map<String, Object>> saves = rules.find(target.str("origin_content_ref"))
					.map(d -> (Map<String, Object>) d.payload().get("saves"));
			if (saves.isPresent() && saves.get() != null && saves.get().get(ability.name()) instanceof Number n) {
				return n.intValue();
			}
		}
		int bonus = Rules.modifier(target.intOr(ability.column(), 10));
		if (tx.count(
				"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SAVE' AND content_ref = ?",
				target.id(), ability.name()) > 0) {
			bonus += RuntimeService.proficiencyBonus(tx, rules, target);
		}
		return bonus;
	}

	private static Map<String, Object> duration(
			Tx tx, long campaignId, Map<String, Object> mech, int upcast, Long encounterId, long round, String label) {
		if (mech.get("duration_rounds") instanceof Number n) {
			return Effects.rounds(tx, campaignId, encounterId, round, n.longValue(), label);
		}
		long minutes = mech.get("duration_minutes") instanceof Number n ? n.longValue() : 1;
		return Effects.minutes(tx, campaignId, minutes, label);
	}

	private static boolean removeCondition(Tx tx, long characterId, String condition) {
		boolean removed = false;
		for (Row e : tx.query("SELECT id FROM active_effect WHERE character_id = ? AND condition_ref = ?", characterId,
				RuntimeService.conditionRef(condition))) {
			tx.delete("active_effect", e.id());
			removed = true;
		}
		return removed;
	}

	private static void markDefeated(Tx tx, Long encounterId, long characterId) {
		if (encounterId == null) {
			return;
		}
		Row c = tx.get("character", characterId);
		if ("DEAD".equals(c.str("life_state"))) {
			tx.queryOne("SELECT id FROM encounter_participant WHERE encounter_id = ? AND character_id = ?", encounterId,
							characterId)
					.ifPresent(p -> tx.update("encounter_participant", p.id(), Map.of("status", "DEFEATED")));
		}
	}

	/** Whether the character has any slot of at least the given level (pact slots count at their level). */
	public static boolean hasSlot(Tx tx, long characterId, int minLevel) {
		for (Row r : tx.query(
				"SELECT * FROM resource_state WHERE character_id = ? AND (resource_ref LIKE 'slot:%' OR resource_ref = ?)",
				characterId, PACT_SLOT)) {
			if (r.intOr("current", 0) <= 0) {
				continue;
			}
			int level = r.str("resource_ref").equals(PACT_SLOT) ? 9
					: Integer.parseInt(r.str("resource_ref").substring(SLOT_PREFIX.length()));
			if (level >= minLevel) {
				return true;
			}
		}
		return false;
	}

	/** Spends the lowest available slot of at least the given level; returns {slot_level, remaining} or empty. */
	public static Optional<Map<String, Object>> spendLowestSlot(Tx tx, long characterId, int minLevel) {
		Row best = null;
		int bestLevel = 99;
		for (Row r : tx.query(
				"SELECT * FROM resource_state WHERE character_id = ? AND (resource_ref LIKE 'slot:%' OR resource_ref = ?)",
				characterId, PACT_SLOT)) {
			if (r.intOr("current", 0) <= 0) {
				continue;
			}
			int level = r.str("resource_ref").equals(PACT_SLOT) ? 9
					: Integer.parseInt(r.str("resource_ref").substring(SLOT_PREFIX.length()));
			if (level >= minLevel && level < bestLevel) {
				best = r;
				bestLevel = level;
			}
		}
		if (best == null) {
			return Optional.empty();
		}
		tx.update("resource_state", best.id(), Map.of("current", best.intOr("current", 0) - 1));
		return Optional.of(Map.of("slot", best.str("resource_ref"), "remaining", best.intOr("current", 0) - 1));
	}

	public static Set<String> knownSpellIds(Tx tx, long characterId) {
		var s = new java.util.LinkedHashSet<String>();
		for (Row t : tx.query(
				"SELECT content_ref FROM character_trait WHERE character_id = ? AND kind IN ('SPELL_KNOWN','SPELL_PREPARED')",
				characterId)) {
			s.add(t.str("content_ref"));
		}
		return s;
	}
}
