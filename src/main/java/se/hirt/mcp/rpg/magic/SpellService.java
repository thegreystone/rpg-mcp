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
import se.hirt.mcp.rpg.maps.GridCombat;
import se.hirt.mcp.rpg.maps.SceneService;
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
import se.hirt.mcp.rpg.rules.Exhaustion;
import se.hirt.mcp.rpg.rules.Rules;
import se.hirt.mcp.rpg.rules.TurnEconomy;

import java.util.*;

/**
 * Spellcasting (DESIGN.md §13): spell slots as resources, prepared spells as traits, and a casting
 * core shared by {@code cast_spell} and the encounter {@code CAST} action. Structured mechanics are
 * executed; everything else is text the GM adjudicates. Citations: SRD 5.2.1 "Spells" chapter and
 * class tables — [verify].
 */
public final class SpellService {

	public static final String SLOT_PREFIX = "slot:";
	public static final String PACT_SLOT = "pact_slot";
	public static final String MAGIC_MISSILE = "srd5e:spell/magic-missile";

	/**
	 * Full-caster slots per spell level, indexed by character level 1..20 (SRD 5.2.1 class tables).
	 */
	private static final int[][] FULL = {{2}, {3}, {4, 2}, {4, 3}, {4, 3, 2}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 2},
			{4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2, 1}, {4, 3, 3, 3, 2, 1}, {4, 3, 3, 3, 2, 1, 1},
			{4, 3, 3, 3, 2, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1, 1},
			{4, 3, 3, 3, 3, 1, 1, 1, 1}, {4, 3, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 3, 2, 2, 1, 1}};
	private static final int[][] HALF = {{2}, {2}, {3}, {3}, {4, 2}, {4, 2}, {4, 3}, {4, 3}, {4, 3, 2}, {4, 3, 2},
			{4, 3, 3}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 1}, {4, 3, 3, 2}, {4, 3, 3, 2}, {4, 3, 3, 3, 1},
			{4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2}};

	/** A character's spellcasting profile. */
	public record Casting(String classRef, String classSlug, Ability ability, String progression, int level,
			int cantripsKnown, int preparedCount) {

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
		return Optional.of(new Casting(id, id.substring(id.lastIndexOf('/') + 1),
				Ability.parse(String.valueOf(s.get("ability"))), String.valueOf(s.get("progression")), level,
				((Number) cantrips.get(level - 1)).intValue(), ((Number) prepared.get(level - 1)).intValue()));
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

	/**
	 * Creates or resizes spell-slot resources to the current class table (activation and Long
	 * Rest).
	 */
	public static void initializeSlots(Tx tx, RulesData rules, Row c) {
		initializeSlots(tx, rules, c, null);
	}

	/**
	 * Resizes slots at level-up while preserving capacity created with Font of Magic. The previous
	 * casting profile must be captured before changing the class level; null resets maxima to the
	 * current class table instead.
	 */
	public static void initializeSlots(Tx tx, RulesData rules, Row c, Casting previousCasting) {
		Optional<Casting> casting = castingOf(tx, rules, c);
		if (casting.isEmpty()) {
			return;
		}
		Casting cast = casting.get();
		if (cast.pact()) {
			upsertResource(tx, c.id(), PACT_SLOT, cast.pactSlots(), "SHORT_REST", null);
			return;
		}
		int[] slots = cast.slots();
		int[] previousSlots = previousCasting == null || previousCasting.pact() ? null : previousCasting.slots();
		for (int i = 0; i < 9; i++) {
			int max = i < slots.length ? slots[i] : 0;
			if (max > 0) {
				Integer previousMax = previousSlots == null ? null : i < previousSlots.length ? previousSlots[i] : 0;
				upsertResource(tx, c.id(), SLOT_PREFIX + (i + 1), max, "LONG_REST", previousMax);
			}
		}
	}

	private static void upsertResource(
		Tx tx, long characterId, String ref, int max, String recharge, Integer previousMax) {
		Optional<Row> existing = tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?",
				characterId, ref);
		if (existing.isPresent()) {
			if (previousMax != null) {
				// Existing campaigns already store created capacity in max, even after those slots are spent.
				max += Math.max(0, existing.get().intOr("max", 0) - previousMax);
			}
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

	/** Explicit stat-block grants only: prose is never interpreted as permission to cast. */
	private record CreatureGrant(String name, Ability ability, List<String> spells, String resourceRef, int uses,
			String recharge, String actionCost) {
	}

	@SuppressWarnings("unchecked")
	private static List<CreatureGrant> creatureGrants(Tx tx, RulesData rules, Row c) {
		if (!RuntimeService.usesStatBlock(tx, c))
			return List.of();
		var grants = new ArrayList<CreatureGrant>();
		Map<String, Object> payload = rules.find(c.str("origin_content_ref")).map(RulesData.Definition::payload)
				.orElse(Map.of());
		for (String section : List.of("actions", "bonus_actions")) {
			if (!(payload.get(section) instanceof List<?> entries))
				continue;
			for (Object entry : entries) {
				if (!(entry instanceof Map<?, ?> action) || !(action.get("spellcasting") instanceof Map<?, ?> raw))
					continue;
				Map<String, Object> sc = (Map<String, Object>) raw;
				if (!(sc.get("ability") instanceof String ability) || !(sc.get("spells") instanceof List<?> spells)
						|| !(sc.get("action_cost") instanceof String cost)
						|| !Set.of("ACTION", "BONUS_ACTION").contains(cost))
					continue;
				boolean atWill = Boolean.TRUE.equals(sc.get("at_will"));
				String resource = sc.get("resource_ref") instanceof String r ? r : null;
				int uses = sc.get("uses") instanceof Number n ? n.intValue() : 0;
				String recharge = String.valueOf(sc.get("recharge"));
				if (!atWill && (resource == null || resource.isBlank() || uses < 1 || uses > 1000
						|| !Set.of("SHORT_REST", "LONG_REST").contains(recharge)))
					continue;
				List<String> refs = spells.stream().map(String::valueOf).map(s -> rules.resolve("SPELL", s))
						.flatMap(Optional::stream).map(RulesData.Definition::id).toList();
				grants.add(new CreatureGrant(String.valueOf(action.get("name")), Ability.parse(ability), refs,
						atWill ? null : resource, atWill ? 0 : uses, recharge, cost));
			}
		}
		return grants;
	}

	private static Map<String, Object> creatureSheet(Tx tx, RulesData rules, Row c) {
		List<CreatureGrant> grants = creatureGrants(tx, rules, c);
		if (grants.isEmpty())
			return null;
		var entries = new ArrayList<Map<String, Object>>();
		int prof = RuntimeService.proficiencyBonus(tx, rules, c);
		for (CreatureGrant grant : grants) {
			var entry = new LinkedHashMap<String, Object>();
			int mod = Rules.modifier(c.intOr(grant.ability().column(), 10));
			entry.put("name", grant.name());
			entry.put("ability", grant.ability().name());
			entry.put("save_dc", 8 + prof + mod);
			entry.put("attack_bonus", prof + mod);
			entry.put("action_cost", grant.actionCost());
			entry.put("spells", grant.spells().stream().map(ref -> rules.require(ref, "SPELL").summary()).toList());
			entry.put("at_will", grant.resourceRef() == null);
			if (grant.resourceRef() != null) {
				// A read-only sheet must not initialize or replenish a creature's finite resource.
				int remaining = tx.queryOne("SELECT * FROM resource_state WHERE character_id=? AND resource_ref=?",
						c.id(), grant.resourceRef()).map(r -> r.intOr("current", 0)).orElse(grant.uses());
				entry.put("resource", Map.of("ref", grant.resourceRef(), "current", remaining, "max", grant.uses(),
						"recharge", grant.recharge()));
			}
			entries.add(entry);
		}
		return Map.of("source", "STAT_BLOCK", "grants", entries, "ritual_casting", false, "upcasting", false);
	}

	/** Read-only action classification shared by encounter admission and casting resolution. */
	public static String castingActionCost(
		Tx tx, RulesData rules, Row caster, String spell, Map<String, Object> options) {
		RulesData.Definition definition = rules.resolve("SPELL", spell)
				.orElseThrow(() -> RpgException.invalidArgument("Unknown spell '" + spell + "'."));
		CreatureGrant grant = creatureGrants(tx, rules, caster).stream()
				.filter(g -> g.spells().contains(definition.id())).findFirst().orElse(null);
		if (grant != null)
			return grant.actionCost();
		return "1 bonus action".equalsIgnoreCase(String.valueOf(definition.payload().get("casting_time")))
				|| Metamagic.requested(options, "quickened") ? "BONUS_ACTION" : "ACTION";
	}

	/** The spellcasting block for character sheets. */
	public static Map<String, Object> sheet(Tx tx, RulesData rules, Row c) {
		Optional<Casting> casting = castingOf(tx, rules, c);
		if (casting.isEmpty()) {
			return creatureSheet(tx, rules, c);
		}
		Casting cast = casting.get();
		var m = new LinkedHashMap<String, Object>();
		m.put("ability", cast.ability().name());
		m.put("save_dc", saveDc(tx, rules, c, cast));
		m.put("attack_bonus", attackBonus(tx, rules, c, cast));
		m.put("max_spell_level", cast.maxSpellLevel());
		m.put("cantrips_known_limit", cast.cantripsKnown());
		m.put("prepared_limit", cast.preparedCount());
		m.put("ritual_casting",
				"any prepared spell with the Ritual tag: cast_spell options.ritual (ten minutes longer, no slot; SRD 5.2.1 \"Rituals\")");
		if (cast.pact()) {
			tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?", c.id(), PACT_SLOT)
					.ifPresent(r -> m.put("pact_slots", Map.of("current", r.intOr("current", 0), "max",
							r.intOr("max", 0), "level", cast.pactSlotLevel())));
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
		Metamagic.appendSheet(tx, rules, c, m);
		return m;
	}

	private static List<String> spellNames(Tx tx, RulesData rules, long characterId, String kind) {
		return tx
				.query("SELECT content_ref FROM character_trait WHERE character_id = ? AND kind = ? ORDER BY id",
						characterId, kind)
				.stream()
				.map(r -> rules.find(r.str("content_ref")).map(
						d -> d.name() + (kind.equals("SPELL_PREPARED") ? " (" + d.payload().get("level") + ")" : ""))
						.orElse(r.str("content_ref")))
				.toList();
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
						c.id(), d.id())
						.filter(t -> !se.hirt.mcp.rpg.character.Origins.SOURCE_CLASS
								.equals(se.hirt.mcp.rpg.character.Origins.sourceOf(t)))
						.isPresent()) {
					v.add(new Violation("cantrips", "ALREADY_KNOWN", d.name()
							+ " is already granted by a species trait or feat; choose a different cantrip (duplicates are re-chosen, SRD 5.2.1)."));
				}
				if (!ids.contains(d.id())) {
					ids.add(d.id());
				}
			}
			if (ids.size() > cast.cantripsKnown()) {
				v.add(new Violation("cantrips", "COUNT", "A level " + cast.level() + " " + cast.classSlug()
						+ " knows at most " + cast.cantripsKnown() + " cantrips."));
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
					v.add(new Violation("spells", "LEVEL", d.name() + " is level " + level
							+ "; the highest slot is level " + cast.maxSpellLevel() + "."));
				}
				if (!((List<Object>) d.payload().get("classes")).contains(cast.classSlug())) {
					v.add(new Violation("spells", "CLASS_LIST",
							d.name() + " is not on the " + cast.classSlug() + " spell list."));
				}
				if (tx.queryOne(
						"SELECT * FROM character_trait WHERE character_id = ? AND kind = 'SPELL_PREPARED' AND content_ref = ?",
						c.id(), d.id())
						.filter(t -> !se.hirt.mcp.rpg.character.Origins.SOURCE_CLASS
								.equals(se.hirt.mcp.rpg.character.Origins.sourceOf(t)))
						.isPresent()) {
					v.add(new Violation("spells", "ALREADY_PREPARED", d.name()
							+ " is already always prepared from a species trait or feat; choose a different spell."));
				}
				if (!ids.contains(d.id())) {
					ids.add(d.id());
				}
			}
			if (ids.size() > cast.preparedCount()) {
				v.add(new Violation("spells", "COUNT", "A level " + cast.level() + " " + cast.classSlug()
						+ " prepares at most " + cast.preparedCount() + " spells."));
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
			Casting cast = castingOf(tx, rules, c)
					.orElseThrow(() -> RpgException.notAllowed(c.str("name") + " has no spellcasting."));
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
		String operationId, String campaignRef, String casterRef, String spell, Integer slotLevel, List<String> targets,
		Map<String, Object> options) {
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
			Map<String, Object> result = castCore(tx, rules, roller, campaignId, caster, spell, slotLevel, targets,
					options, null, 0);
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── the casting core ───────────────────────────────────────────────

	/**
	 * Validate the caster before resolving a spell, initializing a grant or spending a
	 * resource/roll.
	 */
	private static void requireAbleToCast(Tx tx, Row caster) {
		List<String> incapacitating = Combat.conditionsOf(tx, caster).stream().map(Combat.Condition::name)
				.filter(Combat.INCAPACITATING_CONDITIONS::contains).distinct().toList();
		var reasons = new ArrayList<String>();
		if (!"ACTIVE".equals(caster.str("lifecycle")))
			reasons.add("lifecycle " + caster.str("lifecycle"));
		if (!"ALIVE".equals(caster.str("life_state")))
			reasons.add("life state " + caster.str("life_state"));
		if (caster.intOr("current_hp", 0) <= 0)
			reasons.add(caster.intOr("current_hp", 0) + " HP");
		if (!incapacitating.isEmpty())
			reasons.add(String.join(", ", incapacitating) + " (includes Incapacitated)");
		if (!reasons.isEmpty()) {
			String guidance = "DEAD".equals(caster.str("life_state"))
					? "Restore the caster to life before casting; ordinary healing cannot restore a dead creature."
					: !"ACTIVE".equals(caster.str("lifecycle")) ? "Use an active character before casting."
							: "Resolve the caster's recovery or incapacitating condition before casting.";
			throw RpgException
					.notAllowed(caster.str("name") + " cannot cast: " + String.join("; ", reasons) + ". " + guidance
							+ " No spell resource or roll was spent.")
					.withDetail("caster", Ref.of(Ref.CHARACTER, caster.id()))
					.withDetail("life_state", caster.str("life_state")).withDetail("lifecycle", caster.str("lifecycle"))
					.withDetail("current_hp", caster.intOr("current_hp", 0))
					.withDetail("incapacitating_conditions", incapacitating)
					.withDetail("harness_state", tx.get("campaign", caster.lng("campaign_id")).str("harness_state"));
		}
	}

	/**
	 * Validates preparation and slots, spends the slot, resolves the spell's structured mechanics
	 * against the targets, applies effects and concentration, and records every roll. Shared with
	 * encounters.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> castCore(
		Tx tx, RulesData rules, RollService roller, long campaignId, Row caster, String spellText, Integer slotLevel,
		List<String> targetRefs, Map<String, Object> options, Long encounterId, long round) {
		requireAbleToCast(tx, caster);
		RulesData.Definition def = rules.resolve("SPELL", spellText).orElseThrow(() -> RpgException
				.invalidArgument("Unknown spell '" + spellText + "'; see get_content_definitions with kind SPELL."));
		// "If you wear Light, Medium, or Heavy armor and lack training with it ... you can't cast spells"
		// (SRD 5.2.1 "Armor Training").
		se.hirt.mcp.rpg.rules.Training.untrainedArmor(tx, rules, caster).ifPresent(armor -> {
			throw RpgException.notAllowed(caster.str("name") + " is wearing " + armor
					+ " without armor training and can't cast spells (SRD 5.2.1 \"Armor Training\"); take it off first.");
		});
		Map<String, Object> p = def.payload();
		int level = ((Number) p.get("level")).intValue();
		Optional<Casting> castingOpt = castingOf(tx, rules, caster);
		CreatureGrant creatureGrant = creatureGrants(tx, rules, caster).stream()
				.filter(g -> g.spells().contains(def.id())).findFirst().orElse(null);
		String traitKind = level == 0 ? "SPELL_KNOWN" : "SPELL_PREPARED";
		Optional<Row> trait = tx.queryOne(
				"SELECT * FROM character_trait WHERE character_id = ? AND kind = ? AND content_ref = ?", caster.id(),
				traitKind, def.id());
		if (trait.isEmpty() && creatureGrant == null)
			throw RpgException.validation(List.of(new Violation("spell", level == 0 ? "NOT_KNOWN" : "NOT_PREPARED",
					caster.str("name") + (level == 0 ? " does not know " : " has not prepared ") + def.name() + ".")));
		Map<String, Object> traitPayload = trait.filter(t -> !t.isNull("payload_json")).map(t -> t.map("payload_json"))
				.orElse(Map.of());
		// Species- and feat-granted spells use their recorded ability (Magic Initiate, lineages; SRD 5.2.1).
		Ability grantedAbility = creatureGrant != null ? creatureGrant.ability()
				: traitPayload.get("ability") instanceof String ga ? Ability.parse(ga) : null;
		if (grantedAbility == null && castingOpt.isEmpty() && level == 0) {
			grantedAbility = Ability.CHA;
		}
		Map<String, Object> opts = new LinkedHashMap<>(options == null ? Map.of() : options);
		// One spell slot per turn and Quickened Spell's level 1+ clauses (SRD 5.2.1 "One Spell with a Spell Slot per
		// Turn", "Quickened Spell") are judged on the caster's own turn of a running encounter, before any slot,
		// sorcery point or Bonus Action is spent. A reaction on another creature's turn belongs to that turn.
		Row turnEncounter = turnEncounter(tx, caster, encounterId);
		boolean quickenedAsked = Metamagic.requested(opts, "quickened");
		if (turnEncounter != null) {
			if (level > 0 && TurnEconomy.markedThisTurn(tx, turnEncounter, caster.id(), TurnEconomy.QUICKENED)) {
				throw RpgException.notAllowed(caster.str("name")
						+ " already cast a Quickened spell this turn: no level 1+ spell can be cast on the same turn (SRD 5.2.1 \"Quickened Spell\").");
			}
			if (quickenedAsked
					&& TurnEconomy.markedThisTurn(tx, turnEncounter, caster.id(), TurnEconomy.LEVELED_SPELL)) {
				throw RpgException.notAllowed(caster.str("name")
						+ " already cast a level 1+ spell this turn: Quickened Spell cannot modify another spell on the same turn (SRD 5.2.1 \"Quickened Spell\").");
			}
		}
		// Slot — or a free cast granted by the species trait or feat that taught the spell.
		int used = 0;
		Map<String, Object> slotInfo = null;
		boolean slotExpended = false;
		boolean ritual = Boolean.TRUE.equals(opts.get("ritual"))
				|| "true".equalsIgnoreCase(String.valueOf(opts.get("ritual")));
		if (creatureGrant != null) {
			if (ritual || slotLevel != null && slotLevel != level)
				throw RpgException.invalidArgument(creatureGrant.name()
						+ " casts only its listed spells at their base level, without ritual casting or upcasting; omit slot_level and ritual.");
			used = level;
			if (creatureGrant.resourceRef() != null) {
				Optional<Row> saved = tx.queryOne(
						"SELECT * FROM resource_state WHERE character_id=? AND resource_ref=?", caster.id(),
						creatureGrant.resourceRef());
				if (saved.isEmpty()) {
					long id = tx.insert("resource_state",
							Map.of("character_id", caster.id(), "resource_ref", creatureGrant.resourceRef(), "current",
									creatureGrant.uses(), "max", creatureGrant.uses(), "recharge",
									creatureGrant.recharge()));
					saved = Optional.of(tx.get("resource_state", id));
				}
				Row resource = saved.get();
				if (resource.intOr("current", 0) <= 0)
					throw RpgException.insufficientResource(caster.str("name") + " has no " + creatureGrant.name()
							+ " uses left; its listed spells share this resource, which returns on a "
							+ ("LONG_REST".equals(creatureGrant.recharge()) ? "Long Rest." : "Short Rest."));
				tx.update("resource_state", resource.id(), Map.of("current", resource.intOr("current", 0) - 1));
				slotInfo = Map.of("creature_ability", creatureGrant.name(), "resource_ref", creatureGrant.resourceRef(),
						"remaining", resource.intOr("current", 0) - 1, "slot_expended", false);
			} else {
				slotInfo = Map.of("creature_ability", creatureGrant.name(), "at_will", true, "slot_expended", false);
			}
		} else if (ritual) {
			// SRD 5.2.1 "Rituals": a prepared spell with the Ritual tag takes ten minutes longer and spends no spell
			// slot; no class feature is needed (the Wizard's Ritual Adept only relaxes "prepared" to "in the
			// spellbook", a concept the engine does not model). Encounter turns never reach here (the CAST action has
			// no ritual option).
			if (level == 0) {
				throw RpgException.validation(List.of(new Violation("options.ritual", "NOT_A_RITUAL",
						def.name() + " is a cantrip; it is cast without a slot anyway.")));
			}
			if (!Boolean.TRUE.equals(p.get("ritual"))) {
				throw RpgException.validation(List.of(new Violation("options.ritual", "NOT_A_RITUAL",
						def.name() + " does not have the Ritual tag.")));
			}
			if (slotLevel != null && slotLevel != level) {
				throw RpgException.validation(List.of(new Violation("slot_level", "RITUAL",
						"A ritual is cast at the spell's own level; omit slot_level.")));
			}
			used = level;
			slotInfo = Map.of("ritual", true, "level", level, "note",
					"cast as a ritual: ten minutes longer than the casting time, no spell slot spent");
		} else if (level > 0) {
			Optional<Row> free = traitPayload.get("free_cast_resource") instanceof String fr
					? tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?",
							caster.id(), fr)
					: Optional.empty();
			if (free.isPresent() && free.get().intOr("current", 0) > 0 && (slotLevel == null || slotLevel == level)) {
				used = level;
				tx.update("resource_state", free.get().id(), Map.of("current", free.get().intOr("current", 0) - 1));
				slotInfo = Map.of("free_cast", free.get().str("resource_ref"), "remaining",
						free.get().intOr("current", 0) - 1, "note",
						"cast without a spell slot; the use returns on a Long Rest");
			} else if (castingOpt.isEmpty()) {
				throw RpgException.insufficientResource(caster.str("name") + " has no free cast of " + def.name()
						+ " left (it returns on a Long Rest) and no spell slots to cast it with.");
			} else if (castingOpt.get().pact()) {
				Casting cast = castingOpt.get();
				used = cast.pactSlotLevel();
				if (level > used) {
					throw RpgException.validation(List.of(new Violation("spell", "LEVEL",
							def.name() + " is level " + level + "; pact slots are level " + used + ".")));
				}
				Row res = tx
						.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?",
								caster.id(), PACT_SLOT)
						.orElseThrow(
								() -> RpgException.insufficientResource(caster.str("name") + " has no pact slots."));
				if (res.intOr("current", 0) <= 0) {
					throw RpgException.insufficientResource(
							caster.str("name") + " has no pact slots left (they return on a Short Rest).");
				}
				requireNoSlotSpentThisTurn(tx, turnEncounter, caster);
				tx.update("resource_state", res.id(), Map.of("current", res.intOr("current", 0) - 1));
				slotExpended = true;
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
					throw RpgException
							.insufficientResource(caster.str("name") + " has no level " + used + " spell slots left.");
				}
				requireNoSlotSpentThisTurn(tx, turnEncounter, caster);
				tx.update("resource_state", res.get().id(), Map.of("current", res.get().intOr("current", 0) - 1));
				slotExpended = true;
				slotInfo = Map.of("slot_level", used, "remaining", res.get().intOr("current", 0) - 1);
			}
		}
		if (turnEncounter != null) {
			if (slotExpended) {
				TurnEconomy.markThisTurn(tx, turnEncounter, caster.id(), TurnEconomy.SLOT_SPENT);
			}
			if (level > 0) {
				TurnEconomy.markThisTurn(tx, turnEncounter, caster.id(), TurnEconomy.LEVELED_SPELL);
			}
		}
		int upcast = level == 0 ? 0 : Math.max(0, used - level);
		Map<String, Object> mech = p.get("mechanics") instanceof Map<?, ?> m ? (Map<String, Object>) m
				: Map.of("kind", "UTILITY");
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
		Long spatialEncounterId = encounterId == null ? caster.lng("encounter_id") : encounterId;
		boolean areaFromGrid = false;
		if (spatialEncounterId != null) {
			Row spatialEncounter = tx.get("encounter", spatialEncounterId);
			if (GridCombat.isGrid(spatialEncounter)) {
				if (mech.containsKey("area") && !selfCentred(p, mech) && !opts.containsKey("center_ft")
						&& !opts.containsKey("origin_ft") && !targets.isEmpty()) {
					opts.put("center_ft", SceneService.feet(
							SceneService.center(GridCombat.actor(tx, spatialEncounter, targets.getFirst().id()))));
				}
				List<Row> resolvedTargets = List
						.copyOf(GridCombat.spellTargets(tx, spatialEncounter, caster, p, mech, targets, opts));
				targets.clear();
				targets.addAll(resolvedTargets);
				areaFromGrid = mech.containsKey("area") && "SAVE".equals(kind);
			}
		}
		var perTarget = new ArrayList<Map<String, Object>>();
		var effectsCreated = new ArrayList<String>();
		String source = def.name() + " (" + caster.str("name") + ")";
		Long concentrator = concentration ? caster.id() : null;
		// Metamagic (Sorcerer): validated against this spell and paid for before anything is rolled.
		Metamagic.Plan mm = Metamagic.plan(tx, rules, campaignId, caster, opts, def, mech, targets);
		boolean bonusAction = "BONUS_ACTION".equals(castingActionCost(tx, rules, caster, def.id(), opts));
		if (creatureGrant != null) {
			result.put("creature_ability", creatureGrant.name());
			result.put("action_cost", creatureGrant.actionCost());
		}
		if (mm != null && mm.has("quickened") && turnEncounter != null) {
			TurnEconomy.markThisTurn(tx, turnEncounter, caster.id(), TurnEconomy.QUICKENED);
		}
		if (bonusAction) {
			Long bonusEncounterId = encounterId == null ? caster.lng("encounter_id") : encounterId;
			if (bonusEncounterId != null) {
				Row bonusEncounter = tx.get("encounter", bonusEncounterId);
				if (encounterId != null || "RUNNING".equals(bonusEncounter.str("status"))
						|| "WAITING_CHOICE".equals(bonusEncounter.str("status"))) {
					TurnEconomy.spendBonusAction(tx, bonusEncounter, caster.id(), def.name());
				}
			}
			result.put("action_cost", "BONUS_ACTION");
		}
		int casterLevel = castingOpt.map(Casting::level)
				.orElse(Math.max(1, se.hirt.mcp.rpg.character.Origins.characterLevel(tx, caster)));
		switch (kind) {
		case "ATTACK" -> {
			if (targets.isEmpty()) {
				throw RpgException.invalidArgument(def.name() + " needs at least one target.");
			}
			int shots = mech.get("rays") instanceof Number r
					? r.intValue() + upcastCount(mech, "per_level_rays", upcast) : 1;
			if (mech.get("beams_scaling") instanceof Map<?, ?> bs) {
				shots = scaledInt((Map<String, Object>) bs, casterLevel, 1);
			}
			if (targets.size() > shots) {
				throw RpgException.invalidArgument(def.name() + " allows at most " + shots
						+ " target entries (one per attack) at this level; got " + targets.size() + ".");
			}
			if (concentration) {
				// Track the spell even on a miss, before damage can force a save or end concentration.
				long id = Effects.add(tx, campaignId, caster.id(), caster.id(), def.id(), source,
						"srd5e:effect/concentrating", null,
						spellDuration(tx, campaignId, p, mech, upcast, encounterId, round, def.name(), mm), caster.id(),
						def.id(), "PLAYER");
				effectsCreated.add(Ref.of("effect", id));
			}
			var queue = new ArrayList<Row>();
			for (int i = 0; i < shots; i++) {
				queue.add(targets.get(Math.min(i, targets.size() - 1)));
			}
			for (Row target : queue) {
				perTarget.add(spellAttack(tx, rules, roller, campaignId, caster, target, def, mech, atk, mod, upcast,
						casterLevel, encounterId, round, concentrator, source, effectsCreated, opts, mm));
			}
		}
		case "SAVE" -> {
			if (targets.isEmpty() && !selfCentred(p, mech) && !areaFromGrid) {
				throw RpgException
						.invalidArgument(def.name() + " needs at least one target (the creatures in the area).");
			}
			// Simultaneous saves share one damage roll; each target applies its own save and defenses.
			// Populate it lazily so empty areas and dead recipients never roll damage.
			var sharedDamage = new ArrayList<Combat.RolledDamage>();
			for (Row target : targets) {
				perTarget.add(savingThrow(tx, rules, roller, campaignId, caster, target, def, mech, dc, upcast,
						casterLevel, encounterId, round, concentrator, source, effectsCreated, opts, mm, sharedDamage));
			}
			if (targets.isEmpty()) {
				// An emanation or area centred on the caster (Spirit Guardians, Thunderwave) with nobody in it yet:
				// the spell is up and its slot spent; creatures that enter or end their turn in the area later save
				// against save_dc with resolve_check and take the damage with apply_runtime_change {dice}.
				result.put("note", def.name() + " is centred on " + caster.str("name")
						+ " with no creature in its area at casting. Resolve later saves with resolve_check (SAVING_THROW vs save_dc "
						+ dc + ") and damage with apply_runtime_change DAMAGE {dice}.");
			}
			if (concentration && effectsCreated.isEmpty()) {
				// Nothing on a target carries the concentration (damage-only or nobody failed): mark it on the caster
				// so a later concentration spell, damage or a rest ends it properly.
				long id = Effects.add(tx, campaignId, caster.id(), caster.id(), def.id(), source,
						"srd5e:effect/concentrating", null,
						spellDuration(tx, campaignId, p, mech, upcast, encounterId, round, def.name(), mm), caster.id(),
						def.id(), "PLAYER");
				effectsCreated.add(Ref.of("effect", id));
			}
		}
		case "HEAL" -> {
			if (targets.isEmpty()) {
				targets.add(caster);
			}
			String dice = scaledDice(String.valueOf(mech.get("dice")), mech, upcast, casterLevel);
			// A flat amount per slot level above the spell's (Heal: +10 per level above 6; SRD 5.2.1 "Heal").
			int flat = upcastCount(mech, "per_level_flat", upcast);
			for (Row target : targets) {
				Roll roll = roller.roll(dice);
				CharacterService.recordRoll(tx, campaignId, "healing " + def.name(), roll);
				int amount = Math.max(0, roll.total()) + flat
						+ (Boolean.TRUE.equals(mech.get("add_modifier")) ? mod : 0);
				Map<String, Object> healed = RuntimeService.heal(tx, tx.get("character", target.id()), amount,
						def.name());
				if (!"DEAD".equals(target.str("life_state")) && mech.get("cures") instanceof List<?> cures) {
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
			// Shield (SRD 5.2.1): a reaction "when ... targeted by the Magic Missile spell"; the caster of Shield takes
			// no damage from it. Inside an encounter each target that can react is asked (or, by the NPC policy,
			// casts it outright) before any dart lands: its darts are rolled now and applied only on DECLINE.
			boolean magicMissile = MAGIC_MISSILE.equals(def.id());
			var reactions = new LinkedHashMap<Long, String>();
			var deferred = new LinkedHashMap<Long, List<Map<String, Object>>>();
			for (int i = 0; i < darts; i++) {
				Row target = tx.get("character", targets.get(Math.min(i, targets.size() - 1)).id());
				var rolled = new ArrayList<Combat.RolledDamage>();
				for (Map<String, Object> d : perDart) {
					Roll roll = roller.roll(String.valueOf(d.get("dice")));
					CharacterService.recordRoll(tx, campaignId, def.name() + " dart", roll);
					roll = Metamagic.empower(tx, roller, campaignId, mm, String.valueOf(d.get("dice")), roll,
							def.name() + " dart " + (i + 1));
					rolled.add(new Combat.RolledDamage(String.valueOf(d.get("type")), roll, Math.max(0, roll.total())));
				}
				var t = targetView(target);
				t.put("dart", i + 1);
				String reaction = magicMissile && encounterId != null
						? reactions.computeIfAbsent(target.id(), id -> se.hirt.mcp.rpg.encounter.ShieldSpell
								.againstMagicMissile(tx, rules, campaignId, encounterId, round, caster, target))
						: "NONE";
				switch (reaction) {
				case "IMMUNE", "CAST_SHIELD" -> {
					t.put("rolled", rolled.stream().map(d -> dartView(d)).toList());
					t.put("damage", 0);
					t.put("negated", "Shield: no damage from Magic Missile (SRD 5.2.1)");
				}
				case "PENDING" -> {
					var dart = new LinkedHashMap<String, Object>();
					dart.put("dart", i + 1);
					dart.put("breakdown", rolled.stream().map(d -> dartView(d)).toList());
					deferred.computeIfAbsent(target.id(), id -> new ArrayList<>()).add(dart);
					t.put("rolled", dart.get("breakdown"));
					t.put("pending", "SHIELD_SPELL");
				}
				default -> {
					Combat.DamageResult dr = Combat.applyDamage(target.intOr("current_hp", 0),
							target.intOr("temp_hp", 0), Math.max(1, RuntimeService.effectiveMaxHp(tx, target)), rolled,
							RuntimeService.defensesOf(tx, rules, target));
					t.putAll(RuntimeService.applyDamageResult(tx, rules, roller, target, dr, false, caster.id(),
							"slain by " + def.name()));
					markDefeated(tx, encounterId, target.id());
				}
				}
				perTarget.add(t);
			}
			for (var e : deferred.entrySet()) {
				se.hirt.mcp.rpg.encounter.ShieldSpell.openMagicMissileChoice(tx, campaignId, encounterId, caster,
						tx.get("character", e.getKey()), def, e.getValue());
			}
			if (!deferred.isEmpty()) {
				result.put("pending", "SHIELD_SPELL");
				result.put("next_step",
						"Shield is offered against Magic Missile: resolve_pending_choice (CAST_SHIELD negates every dart aimed at the chooser; DECLINE applies the rolled damage).");
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
			if (targets.isEmpty() && (scope.equals("SELF") || scope.equals("ONE") || scope.equals("WEAPON")
					|| scope.startsWith("UP_TO") || scope.startsWith("PARTY"))) {
				targets.add(caster);
			}
			if (scope.equals("WEAPON") && targets.size() > 1) {
				throw RpgException.invalidArgument(
						def.name() + " affects one weapon: target its wielder and name the weapon in options.weapon.");
			}
			Map<String, Object> modifiers = mech.get("modifiers") instanceof Map<?, ?> given
					? new LinkedHashMap<>((Map<String, Object>) given) : new LinkedHashMap<>();
			// A higher slot raises a fixed bonus: "upcast": {"3": "+2", "6": "+3"} (Magic Weapon).
			if (mech.get("upcast") instanceof Map<?, ?> u) {
				int best = 0;
				Object value = null;
				for (var e : ((Map<String, Object>) u).entrySet()) {
					if (e.getKey().matches("\\d+") && Integer.parseInt(e.getKey()) <= used
							&& Integer.parseInt(e.getKey()) > best) {
						best = Integer.parseInt(e.getKey());
						value = e.getValue();
					}
				}
				if (value != null) {
					int n = value instanceof Number num ? num.intValue()
							: Integer.parseInt(value.toString().replace("+", "").trim());
					if (modifiers.containsKey("attack_bonus")) {
						modifiers.put("attack_bonus", n);
					}
					if (modifiers.containsKey("damage_bonus")) {
						modifiers.put("damage_bonus", n);
					}
				}
			}
			// Shillelagh: the weapon's die scales with the caster's level, the spellcasting ability replaces
			// Strength, and the damage may become Force at the caster's choice.
			if (modifiers.get("weapon_die") instanceof String die) {
				modifiers.put("weapon_die", scaledDice(die, mech, 0, casterLevel));
			}
			if ("SPELLCASTING".equals(modifiers.get("use_ability"))) {
				modifiers.put("use_ability",
						castingOpt.map(k -> k.ability().name())
								.orElseThrow(() -> RpgException.validation(List.of(new Violation("spell",
										"NO_SPELLCASTING", caster.str("name") + " has no spellcasting ability.")))));
			}
			if (mech.get("allows_damage_type") != null && opts.get("damage_type") != null && String
					.valueOf(mech.get("allows_damage_type")).equalsIgnoreCase(opts.get("damage_type").toString())) {
				modifiers.put("weapon_damage_type", opts.get("damage_type").toString().toLowerCase());
			}
			// A conjured weapon (Flame Blade): its dice at this slot and the caster's numbers, fixed at casting.
			if (mech.get("conjured_weapon") instanceof Map<?, ?> cw) {
				var conjured = new LinkedHashMap<String, Object>((Map<String, Object>) cw);
				conjured.put("dice", scaledDice(String.valueOf(cw.get("dice")), mech, upcast, casterLevel));
				conjured.put("attack_bonus", atk);
				conjured.put("damage_modifier", mod);
				modifiers.put("conjured_weapon", conjured);
			}
			String condition = mech.get("condition") == null ? null : mech.get("condition").toString();
			for (Row target : targets) {
				var mods = new LinkedHashMap<>(modifiers);
				if (Boolean.TRUE.equals(mods.get("against_target")) && opts.get("against") != null) {
					mods.put("against_target_id",
							CharacterService.character(tx, campaignId, opts.get("against").toString()).id());
				}
				String weaponName = null;
				if (scope.equals("WEAPON")) {
					Row entry = boundWeapon(tx, rules, campaignId, target, def.name(), mech, opts);
					se.hirt.mcp.rpg.content.ContentService.Item item = se.hirt.mcp.rpg.content.ContentService
							.itemForEntry(tx, rules, entry);
					mods.put("weapon_entry_id", entry.id());
					mods.put("weapon_name", item.name());
					weaponName = item.name();
				}
				Map<String, Object> duration = duration(tx, campaignId, mech, upcast, encounterId, round, def.name(),
						mm);
				long id = Effects.add(tx, campaignId, target.id(), caster.id(), def.id(), source,
						condition == null
								? "srd5e:effect/" + String.valueOf(mech.getOrDefault("effect", def.id())).toLowerCase()
								: RuntimeService.conditionRef(condition),
						mods, duration, concentrator, def.id(), "PLAYER");
				effectsCreated.add(Ref.of("effect", id));
				var t = targetView(target);
				t.put("effect", mech.getOrDefault("effect", condition));
				t.put("duration", duration);
				if (weaponName != null) {
					t.put("weapon", weaponName);
				}
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
				if (removed.contains("EXHAUSTION")) {
					t.put("exhaustion", Exhaustion.level(tx, target.id()));
				}
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
				int effectiveMax = Math.max(1, RuntimeService.effectiveMaxHp(tx, target));
				int hp = "FULL".equals(String.valueOf(mech.get("hp"))) ? effectiveMax
						: ((Number) mech.getOrDefault("hp", 1)).intValue();
				Exhaustion.reduce(tx, target.id(), 1);
				var cols = new LinkedHashMap<String, Object>();
				cols.put("life_state", "ALIVE");
				cols.put("current_hp", Math.min(effectiveMax, hp));
				cols.put("death_saves_json", null);
				cols.put("revision", tx.get("character", target.id()).lng("revision") + 1);
				tx.update("character", target.id(), cols);
				se.hirt.mcp.rpg.ledger.LedgerService.append(tx, campaignId,
						new se.hirt.mcp.rpg.ledger.LedgerService.EventSpec("CHARACTER_REVIVED",
								target.str("name") + " was returned to life by " + caster.str("name") + "'s "
										+ def.name() + ".",
								List.of(target.id(), caster.id()), "CRITICAL", "PARTY_KNOWN", "MECHANICAL_CONSEQUENCE",
								null, target.lng("location_id"), null, Map.of("spell", def.id())));
				var t = targetView(target);
				t.put("revived", true);
				t.put("hp", Math.min(effectiveMax, hp));
				t.put("exhaustion", Exhaustion.level(tx, target.id()));
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
						spellDuration(tx, campaignId, p, mech, upcast, encounterId, round, def.name(), mm), caster.id(),
						def.id(), "PLAYER");
				effectsCreated.add(Ref.of("effect", id));
			}
			result.put("note", "No structured mechanics for " + def.name() + "; adjudicate from the rules text.");
		}
		}
		if (mm != null && mm.extended() && concentration) {
			// Extended Spell: Advantage on the saves made to keep this concentration (SRD 5.2.1 "Extended Spell");
			// recorded on the effects that carry the concentration, where the damage hook reads it.
			markExtended(tx, caster.id(), effectsCreated);
		}
		if (mm != null) {
			result.put("metamagic", mm.toMap());
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
			log.put("summary", caster.str("name") + " casts " + def.name()
					+ (used > level ? " (level " + used + " slot)" : "") + (mm == null ? "" : " " + mm.summary()) + ": "
					+ perTarget
							.stream().map(
									t -> t.get("name")
											+ (t.get("hit") != null
													? (Boolean.TRUE.equals(t.get("hit")) ? " hit" : " missed")
													: t.get("saved") != null ? (Boolean.TRUE.equals(t.get("saved"))
															? " saved" : " failed") : "")
											+ (t.get("damage") != null ? " " + t.get("damage") + " dmg" : "")
											+ (t.get("healed") != null ? " +" + t.get("healed") + " HP" : ""))
							.toList());
			log.put("payload_json", Json.write(result));
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

	/** One rolled dart as stored on a deferred Magic Missile choice and shown in the result. */
	private static Map<String, Object> dartView(Combat.RolledDamage d) {
		var m = new LinkedHashMap<String, Object>();
		m.put("type", d.type());
		m.put("roll", d.roll().toMap());
		m.put("amount", d.amount());
		return m;
	}

	/**
	 * The running encounter the casting happens in (the CAST action's, else the caster's own), for
	 * the once-per-turn spell rules; null outside encounters.
	 */
	private static Row turnEncounter(Tx tx, Row caster, Long encounterId) {
		Long id = encounterId != null ? encounterId : caster.lng("encounter_id");
		if (id == null) {
			return null;
		}
		return tx.find("encounter", id)
				.filter(e -> "RUNNING".equals(e.str("status")) || "WAITING_CHOICE".equals(e.str("status")))
				.orElse(null);
	}

	/** SRD 5.2.1 "One Spell with a Spell Slot per Turn": refused before the slot is touched. */
	private static void requireNoSlotSpentThisTurn(Tx tx, Row turnEncounter, Row caster) {
		if (turnEncounter != null
				&& TurnEconomy.markedThisTurn(tx, turnEncounter, caster.id(), TurnEconomy.SLOT_SPENT)) {
			throw RpgException.notAllowed(caster.str("name")
					+ " already expended a spell slot this turn; only one spell slot can be expended per turn (SRD 5.2.1 \"One Spell with a Spell Slot per Turn\"). A cantrip is still allowed.");
		}
	}

	/**
	 * Records Extended Spell on the effects that carry the caster's concentration from this
	 * casting.
	 */
	private static void markExtended(Tx tx, long casterId, List<String> effectsCreated) {
		for (String ref : effectsCreated) {
			Row e = tx.find("active_effect", Ref.id(ref, "effect")).orElse(null);
			if (e == null || e.lng("concentration_character_id") == null
					|| e.lng("concentration_character_id") != casterId) {
				continue;
			}
			var mods = e.isNull("modifier_json") ? new LinkedHashMap<String, Object>()
					: new LinkedHashMap<>(e.map("modifier_json"));
			mods.put("extended_spell", true);
			tx.update("active_effect", e.id(), Map.of("modifier_json", Json.write(mods)));
		}
	}

	/**
	 * The d20 expression for a concentration save: 2d20 keep highest while the concentrated spell
	 * was Extended (SRD 5.2.1 "Extended Spell"), else 1d20.
	 */
	public static String concentrationSaveDice(Tx tx, long characterId) {
		for (Row e : tx.query(
				"SELECT modifier_json FROM active_effect WHERE concentration_character_id = ? AND modifier_json IS NOT NULL",
				characterId)) {
			if (Boolean.TRUE.equals(e.map("modifier_json").get("extended_spell"))) {
				return "2d20kh1";
			}
		}
		return "1d20";
	}

	/**
	 * The carried weapon a WEAPON-scoped buff binds to: {@code options.weapon} (an entry ref or a
	 * name), else the wielder's equipped weapon. Magic Weapon takes a nonmagical one
	 * ({@code requires_nonmagical}); Shillelagh a Club or Quarterstaff ({@code weapon_kinds}).
	 */
	private static Row boundWeapon(
		Tx tx, RulesData rules, long campaignId, Row wielder, String spellName, Map<String, Object> mech,
		Map<String, Object> opts) {
		Row entry = null;
		if (opts.get("weapon") != null) {
			entry = se.hirt.mcp.rpg.inventory.InventoryService.carriedEntry(tx, rules, campaignId, wielder,
					opts.get("weapon").toString());
		} else {
			for (Row e : tx.query(
					"SELECT * FROM inventory_entry WHERE character_id = ? AND equipped = 1 AND slot IN ('ONE_HAND','TWO_HANDS') ORDER BY id",
					wielder.id())) {
				if ("WEAPON".equals(se.hirt.mcp.rpg.content.ContentService.itemForEntry(tx, rules, e).type())) {
					entry = e;
					break;
				}
			}
			if (entry == null) {
				throw RpgException.validation(List.of(new Violation("options.weapon", "NO_WEAPON",
						wielder.str("name") + " wields no weapon; name one in options.weapon.")));
			}
		}
		se.hirt.mcp.rpg.content.ContentService.Item item = se.hirt.mcp.rpg.content.ContentService.itemForEntry(tx,
				rules, entry);
		if (!"WEAPON".equals(item.type())) {
			throw RpgException.validation(
					List.of(new Violation("options.weapon", "NOT_A_WEAPON", item.name() + " is not a weapon.")));
		}
		if (Boolean.TRUE.equals(mech.get("requires_nonmagical"))
				&& se.hirt.mcp.rpg.inventory.MagicItems.isMagic(item.payload())) {
			throw RpgException.validation(List.of(new Violation("options.weapon", "NONMAGICAL",
					spellName + " affects a nonmagical weapon; " + item.name() + " is already magical.")));
		}
		if (mech.get("weapon_kinds") instanceof List<?> kinds) {
			Map<String, Object> magic = se.hirt.mcp.rpg.inventory.MagicItems.magic(item.payload());
			String baseRef = magic != null && magic.get("base_ref") != null ? String.valueOf(magic.get("base_ref"))
					: item.contentRef();
			String baseName = baseRef == null ? item.name()
					: rules.find(baseRef).map(RulesData.Definition::name).orElse(item.name());
			boolean ok = kinds.stream().anyMatch(k -> k.toString().equalsIgnoreCase(baseName));
			if (!ok) {
				throw RpgException.validation(List.of(new Violation("options.weapon", "WEAPON_KIND",
						spellName + " works on a " + String.join(" or ", kinds.stream().map(Object::toString).toList())
								+ ", not " + item.name() + ".")));
			}
		}
		return entry;
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
		if (upcast > 0 && mech.get("upcast") instanceof Map<?, ?> u
				&& ((Map<String, Object>) u).get("per_level_dice") instanceof String per) {
			int step = ((Map<String, Object>) u).get("step") instanceof Number s ? s.intValue() : 1;
			int times = upcast / step;
			var m = java.util.regex.Pattern.compile("^(\\d+)d(\\d+)(.*)$").matcher(per);
			var b = java.util.regex.Pattern.compile("^(\\d+)d(\\d+)(.*)$").matcher(dice);
			if (times > 0 && m.matches() && b.matches() && m.group(2).equals(b.group(2))) {
				dice = (Integer.parseInt(b.group(1)) + times * Integer.parseInt(m.group(1))) + "d" + b.group(2)
						+ b.group(3);
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
		int casterLevel, boolean critical, int modifierIfAny, Map<String, Object> opts, Metamagic.Plan mm) {
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
			if (mech.get("choose_type") instanceof List<?> choices && opts.get("damage_type") != null
					&& choices.contains(opts.get("damage_type").toString().toLowerCase())) {
				type = opts.get("damage_type").toString().toLowerCase();
			}
			type = Metamagic.transmute(mm, type);
			Roll roll = roller.roll(dice);
			CharacterService.recordRoll(tx, campaignId, "damage " + def.name() + " (" + type + ")", roll);
			roll = Metamagic.empower(tx, roller, campaignId, mm, dice, roll, def.name());
			out.add(new Combat.RolledDamage(type, roll, Math.max(0, roll.total())));
			first = false;
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> spellAttack(
		Tx tx, RulesData rules, RollService roller, long campaignId, Row caster, Row targetRow,
		RulesData.Definition def, Map<String, Object> mech, int atk, int mod, int upcast, int casterLevel,
		Long encounterId, long round, Long concentrator, String source, List<String> effectsCreated,
		Map<String, Object> opts, Metamagic.Plan mm) {
		Row target = tx.get("character", targetRow.id());
		var t = targetView(target);
		if ("DEAD".equals(target.str("life_state"))) {
			t.put("skipped", "already dead");
			return t;
		}
		Effects.Modifiers casterMods = Effects.modifiers(tx, rules, caster.id());
		boolean touch = "Touch".equalsIgnoreCase(String.valueOf(def.payload().get("range")))
				&& (mm == null || !mm.has("distant"));
		boolean close = Combat.withinFiveFeet(tx, encounterId, caster.id(), target.id(), touch);
		// The same table as weapon attacks: the caster's and the target's conditions, Dodge, Blur/Faerie Fire and a
		// ranged spell attack within 5 feet of an enemy (SRD 5.2.1 "Ranged Attacks in Close Combat").
		Combat.AttackRollFactors base = Combat.attackRollFactors(tx, rules, encounterId, caster, target, close, !touch,
				false, false);
		boolean grid = encounterId != null && GridCombat.isGrid(tx.get("encounter", encounterId));
		boolean unseenAttacker = grid
				&& !GridCombat.canSee(tx, tx.get("encounter", encounterId), target.id(), caster.id());
		boolean unseenTarget = grid
				&& !GridCombat.canSee(tx, tx.get("encounter", encounterId), caster.id(), target.id());
		var reasons = new ArrayList<String>(base.reasons());
		if (unseenAttacker)
			reasons.add("target cannot see the attacker");
		if (unseenTarget)
			reasons.add("attacker cannot see the target");
		Combat.AttackRollFactors factors = new Combat.AttackRollFactors(base.advantage() || unseenAttacker,
				base.disadvantage() || unseenTarget, base.criticalWithinFiveFeet(), List.copyOf(reasons));
		String dice = factors.form();
		t.put("advantage", factors.label());
		if (!factors.reasons().isEmpty()) {
			t.put("advantage_reasons", factors.reasons());
		}
		int bonus = atk + casterMods.attackBonus - Exhaustion.penalty(tx, caster.id());
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
		int coverBonus = encounterId != null && GridCombat.isGrid(tx.get("encounter", encounterId))
				? GridCombat.coverBonus(tx, tx.get("encounter", encounterId), caster.id(), target.id()) : 0;
		int ac = RuntimeService.armorClass(tx, rules, target) + coverBonus;
		boolean conditionCritical = factors.criticalWithinFiveFeet() && close;
		boolean critical = natural == 20 || conditionCritical;
		boolean hit = natural != 1 && (natural == 20 || total >= ac);
		if (!hit && Metamagic.seek(tx, mm)) {
			// Seeking Spell: the d20 is rerolled and the new roll used (SRD 5.2.1 "Sorcerer").
			var first = roll.toMap();
			first.put("roll_ref", Ref.of(Ref.ROLL, rollId));
			roll = roller.roll(dice + (bonus >= 0 ? "+" + bonus : Integer.toString(bonus)));
			rollId = CharacterService.recordRoll(tx, campaignId, "Seeking reroll " + def.name(), roll);
			total = roll.total();
			for (Map<String, Object> bd : bonusDice) {
				total += ((Number) bd.get("total")).intValue();
			}
			natural = roll.dice().get(0);
			critical = natural == 20 || conditionCritical;
			hit = natural != 1 && (natural == 20 || total >= ac);
			t.put("seeking", Map.of("first_roll", first, "rerolled", true));
		}
		var rm = roll.toMap();
		rm.put("roll_ref", Ref.of(Ref.ROLL, rollId));
		t.put("attack_roll", rm);
		if (!bonusDice.isEmpty()) {
			t.put("bonus_dice", bonusDice);
		}
		t.put("total", total);
		t.put("target_armor_class", ac);
		t.put("cover_bonus", coverBonus);
		t.put("cover", coverBonus >= 5 ? "THREE_QUARTERS" : coverBonus >= 2 ? "HALF" : "NONE");
		t.put("hit", hit);
		t.put("critical", hit && critical);
		if (hit) {
			List<Combat.RolledDamage> damages = rollSpellDamage(tx, roller, campaignId, def, mech, upcast, casterLevel,
					critical, Boolean.TRUE.equals(mech.get("add_modifier")) ? mod : 0, opts, mm);
			if (!damages.isEmpty()) {
				Combat.DamageResult dr = Combat.applyDamage(target.intOr("current_hp", 0), target.intOr("temp_hp", 0),
						Math.max(1, RuntimeService.effectiveMaxHp(tx, target)), damages,
						RuntimeService.defensesOf(tx, rules, target));
				t.putAll(RuntimeService.applyDamageResult(tx, rules, roller, target, dr, critical, caster.id(),
						"slain by " + def.name()));
				markDefeated(tx, encounterId, target.id());
				if (Boolean.TRUE.equals(mech.get("drain_heal_half"))) {
					RuntimeService.heal(tx, tx.get("character", caster.id()), dr.totalDealt() / 2, def.name());
					t.put("caster_healed", dr.totalDealt() / 2);
				}
			}
			if (mech.get("on_hit_condition") instanceof String cond
					&& !"DEAD".equals(String.valueOf(t.get("life_state")))) {
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
		long round, Long concentrator, String source, List<String> effectsCreated, Map<String, Object> opts,
		Metamagic.Plan mm, List<Combat.RolledDamage> sharedDamage) {
		Row target = tx.get("character", targetRow.id());
		var t = targetView(target);
		if ("DEAD".equals(target.str("life_state"))) {
			t.put("skipped", "already dead");
			return t;
		}
		String saveAbility = String.valueOf(mech.get("save"));
		boolean automatic = Boolean.TRUE.equals(mech.get("automatic")) || "NONE".equals(saveAbility);
		boolean saved = false;
		boolean careful = mm != null && mm.carefulFor(target.id());
		if (mech.get("automatic_if_hp_at_most") instanceof Number threshold
				&& target.intOr("current_hp", 0) <= threshold.intValue()) {
			automatic = true;
			t.put("automatic", "HP at or below " + threshold);
		}
		if (careful) {
			// Careful Spell: the named creature succeeds automatically and takes no damage (SRD 5.2.1 "Sorcerer").
			automatic = true;
			saved = true;
			t.put("careful", true);
			t.put("saved", true);
		}
		if (!automatic) {
			Ability ability = Ability.parse(saveAbility);
			int bonus = saveBonus(tx, rules, target, ability);
			int coverBonus = 0;
			if (ability == Ability.DEX && encounterId != null && GridCombat.isGrid(tx.get("encounter", encounterId))) {
				coverBonus = GridCombat.spellCoverBonus(tx, tx.get("encounter", encounterId), caster.id(), target.id(),
						opts);
				bonus += coverBonus;
			}
			t.put("cover_bonus", coverBonus);
			t.put("cover", coverBonus >= 5 ? "THREE_QUARTERS" : coverBonus >= 2 ? "HALF" : "NONE");
			Effects.Modifiers targetMods = Effects.modifiers(tx, rules, target.id());
			bonus += targetMods.saveBonus;
			boolean heightened = mm != null && mm.heightenedTarget() != null && mm.heightenedTarget() == target.id();
			String d20 = heightened ? "2d20kl1" : "1d20";
			if (heightened) {
				t.put("heightened", true);
			}
			Roll roll = roller.roll(d20 + (bonus >= 0 ? "+" + bonus : Integer.toString(bonus)));
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
		String onSuccess = careful ? "NONE" : String.valueOf(mech.getOrDefault("on_success", "HALF"));
		if (sharedDamage.isEmpty()) {
			sharedDamage.addAll(
					rollSpellDamage(tx, roller, campaignId, def, mech, upcast, casterLevel, false, 0, opts, mm));
		}
		List<Combat.RolledDamage> damages = sharedDamage;
		if (!damages.isEmpty() && !(saved && onSuccess.equals("NONE"))) {
			boolean halfBeforeDefenses = saved && onSuccess.equals("HALF");
			if (halfBeforeDefenses) {
				t.put("halved", true);
			}
			Combat.DamageResult dr = Combat.applyDamage(target.intOr("current_hp", 0), target.intOr("temp_hp", 0),
					Math.max(1, RuntimeService.effectiveMaxHp(tx, target)), damages,
					RuntimeService.defensesOf(tx, rules, target), halfBeforeDefenses);
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
			Map<String, Object> duration = duration(tx, campaignId, mech, upcast, encounterId, round, def.name(), mm);
			// A spell whose description grants the target another save carries that instruction onto the effect,
			// so the encounter can roll it at the end of the target's turn instead of the GM remembering to
			// (SRD 5.2.1 spell descriptions; RULES_ENGINE.md §3).
			var effectMods = new LinkedHashMap<String, Object>();
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
				if (Boolean.TRUE.equals(t.get("heightened"))) {
					// Heightened Spell: Disadvantage on saves against the spell, the repeat ones included (SRD 5.2.1).
					spec.put("disadvantage", true);
				}
				effectMods.put("repeat_save", spec);
			}
			if (Boolean.TRUE.equals(mech.get("ends_on_damage"))) {
				// "The spell ends on a target if it takes damage" (Sleep; SRD 5.2.1): the damage pipeline ends it.
				effectMods.put("ends_on_damage", true);
			}
			Map<String, Object> repeat = effectMods.isEmpty() ? null : effectMods;
			for (String cond : conditions) {
				if ("EXHAUSTION".equalsIgnoreCase(cond)) {
					t.putAll(Exhaustion.gain(tx, rules, target, def.name()));
					continue;
				}
				if (Effects.modifiers(tx, rules, target.id()).immuneConditions.contains(cond)) {
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
				if (conditions.stream().anyMatch(condition -> !"EXHAUSTION".equalsIgnoreCase(condition))) {
					t.put("duration", duration);
				}
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

	/**
	 * Saving throw bonus: ability modifier plus proficiency for classed characters, stat-block
	 * value for creatures.
	 */
	@SuppressWarnings("unchecked")
	public static int saveBonus(Tx tx, RulesData rules, Row target, Ability ability) {
		if (RuntimeService.usesStatBlock(tx, target)) {
			Optional<Map<String, Object>> saves = rules.find(target.str("origin_content_ref"))
					.map(d -> (Map<String, Object>) d.payload().get("saves"));
			if (saves.isPresent() && saves.get() != null && saves.get().get(ability.name()) instanceof Number n) {
				return n.intValue() - Exhaustion.penalty(tx, target.id());
			}
		}
		int bonus = Rules.modifier(target.intOr(ability.column(), 10));
		if (tx.count(
				"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SAVE' AND content_ref = ?",
				target.id(), ability.name()) > 0) {
			bonus += RuntimeService.proficiencyBonus(tx, rules, target);
		}
		return bonus - Exhaustion.penalty(tx, target.id());
	}

	private static Map<String, Object> duration(
		Tx tx, long campaignId, Map<String, Object> mech, int upcast, Long encounterId, long round, String label,
		Metamagic.Plan mm) {
		if (mech.get("duration_rounds") instanceof Number n) {
			return Effects.rounds(tx, campaignId, encounterId, round, extended(mm, n.longValue(), true), label);
		}
		long minutes = mech.get("duration_minutes") instanceof Number n ? n.longValue() : 1;
		return Effects.minutes(tx, campaignId, extended(mm, minutes, false), label);
	}

	/**
	 * Extended Spell doubles a duration of a minute or more, to at most 24 hours (SRD 5.2.1
	 * "Sorcerer").
	 */
	private static long extended(Metamagic.Plan mm, long amount, boolean rounds) {
		if (mm == null || !mm.extended()) {
			return amount;
		}
		if (rounds) {
			return amount >= 10 ? amount * 2 : amount;
		}
		return Math.min(24 * 60, amount * 2);
	}

	private static final java.util.regex.Pattern DURATION_TEXT = java.util.regex.Pattern
			.compile("(\\d+)\\s*(round|minute|hour|day)s?", java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * The duration of the spell itself (for the caster's concentration marker): the mechanics'
	 * explicit rounds or minutes when given, else the printed duration ("10 minutes",
	 * "Concentration, up to 1 hour"), else one minute.
	 */
	private static Map<String, Object> spellDuration(
		Tx tx, long campaignId, Map<String, Object> payload, Map<String, Object> mech, int upcast, Long encounterId,
		long round, String label, Metamagic.Plan mm) {
		if (mech.get("duration_rounds") instanceof Number || mech.get("duration_minutes") instanceof Number) {
			return duration(tx, campaignId, mech, upcast, encounterId, round, label, mm);
		}
		var m = DURATION_TEXT.matcher(String.valueOf(payload.getOrDefault("duration", "")));
		if (m.find()) {
			long n = Long.parseLong(m.group(1));
			return switch (m.group(2).toLowerCase()) {
			case "round" -> Effects.rounds(tx, campaignId, encounterId, round, extended(mm, n, true), label);
			case "hour" -> Effects.minutes(tx, campaignId, extended(mm, n * 60, false), label);
			case "day" -> Effects.minutes(tx, campaignId, extended(mm, n * 60 * 24, false), label);
			default -> Effects.minutes(tx, campaignId, extended(mm, n, false), label);
			};
		}
		return Effects.minutes(tx, campaignId, extended(mm, 1, false), label);
	}

	/**
	 * A spell whose area starts at the caster: range "Self (15-foot emanation)", "Self (15-foot
	 * cube)".
	 */
	private static boolean selfCentred(Map<String, Object> payload, Map<String, Object> mech) {
		String range = String.valueOf(payload.getOrDefault("range", "")).toLowerCase();
		String area = String.valueOf(mech.getOrDefault("area", "")).toLowerCase();
		return range.startsWith("self") || area.contains("emanation") || area.contains("around you");
	}

	private static boolean removeCondition(Tx tx, long characterId, String condition) {
		if ("EXHAUSTION".equalsIgnoreCase(condition)) {
			return Exhaustion.reduce(tx, characterId, 1) > 0;
		}
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

	/**
	 * Whether the character has any slot of at least the given level (pact slots count at their
	 * level).
	 */
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

	/**
	 * Spends the lowest available slot of at least the given level; returns {slot_level, remaining}
	 * or empty.
	 */
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
