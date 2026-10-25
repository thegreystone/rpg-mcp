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

import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.DiceExpression;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.*;

/**
 * Combat arithmetic (RULES_ENGINE.md §3): attack profiles, the damage pipeline, death saving
 * throws. Citations — SRD 5.2.1 "Combat" (attack rolls, critical hits, unarmed strikes), "Damage
 * and Healing" (resistance/vulnerability/immunity, temporary Hit Points, dropping to 0, death
 * saves). Encoded from the 2024 rules and marked [verify] per RULES_ENGINE.md §1.
 */
public final class Combat {

	public static final Set<String> DAMAGE_TYPES = Set.of("acid", "bludgeoning", "cold", "fire", "force", "lightning",
			"necrotic", "piercing", "poison", "psychic", "radiant", "slashing", "thunder");

	private Combat() {
	}

	/** Conditions that include Incapacitated (SRD 5.2.1 Rules Glossary). */
	public static final List<String> INCAPACITATING_CONDITIONS = List.of("INCAPACITATED", "STUNNED", "PARALYZED",
			"UNCONSCIOUS", "PETRIFIED");
	/** Conditions whose Speed is 0 and can't increase (SRD 5.2.1 Rules Glossary). */
	public static final List<String> SPEED_ZERO_CONDITIONS = List.of("PARALYZED", "UNCONSCIOUS", "PETRIFIED",
			"GRAPPLED", "RESTRAINED");
	/** The Dodge action's effect row, written by the encounter engine. */
	public static final String DODGING_REF = "srd5e:effect/dodging";
	private static final String CONDITION_PREFIX = "srd5e:condition/";

	/**
	 * One condition on a creature and, when the effect recorded it, the creature that caused it:
	 * the grappler of a Grappled creature, the source of a Frightened creature's fear.
	 */
	public record Condition(String name, Long sourceCharacterId) {
	}

	/**
	 * The creature's conditions as upper-case names. A DYING creature is Unconscious (SRD 5.2.1
	 * "Dropping to 0 Hit Points") whether or not the effect row exists.
	 */
	public static List<Condition> conditionsOf(Tx tx, Row c) {
		var out = new ArrayList<Condition>();
		for (Row e : tx.query(
				"SELECT condition_ref, source_character_id FROM active_effect WHERE character_id = ? AND condition_ref LIKE ?",
				c.id(), CONDITION_PREFIX + "%")) {
			out.add(new Condition(e.str("condition_ref").substring(CONDITION_PREFIX.length()).toUpperCase(),
					e.lng("source_character_id")));
		}
		if ("DYING".equals(c.str("life_state"))) {
			out.add(new Condition("UNCONSCIOUS", null));
		}
		return out;
	}

	public static boolean has(List<Condition> conditions, String name) {
		return conditions.stream().anyMatch(c -> c.name().equals(name));
	}

	public static boolean hasAny(List<Condition> conditions, Collection<String> names) {
		return conditions.stream().anyMatch(c -> names.contains(c.name()));
	}

	/**
	 * Whether the Dodge action protects the creature right now: "You lose these benefits if you
	 * have the Incapacitated condition or if your Speed is 0" (SRD 5.2.1 "Dodge").
	 */
	public static boolean dodging(Tx tx, RulesData rules, Row c, List<Condition> conditions) {
		if (tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id = ? AND condition_ref = ?", c.id(),
				DODGING_REF) == 0) {
			return false;
		}
		return !hasAny(conditions, INCAPACITATING_CONDITIONS) && !hasAny(conditions, SPEED_ZERO_CONDITIONS)
				&& Exhaustion.speed(tx, rules, c) > 0;
	}

	/**
	 * Advantage and Disadvantage on one attack roll and whether the target's condition turns a hit
	 * within 5 feet into a critical hit. Any source of each cancels the other to a plain d20 (SRD
	 * 5.2.1 "Advantage and Disadvantage").
	 */
	public record AttackRollFactors(boolean advantage, boolean disadvantage, boolean criticalWithinFiveFeet,
			List<String> reasons) {

		/** The d20 part of the roll expression. */
		public String form() {
			return advantage && !disadvantage ? "2d20kh1" : disadvantage && !advantage ? "2d20kl1" : "1d20";
		}

		public String label() {
			return advantage && !disadvantage ? "ADVANTAGE" : disadvantage && !advantage ? "DISADVANTAGE" : "NONE";
		}
	}

	/**
	 * The condition table for attack rolls (SRD 5.2.1 Rules Glossary), the Dodge action, the
	 * effects on the target (Blur, Faerie Fire) and "Ranged Attacks in Close Combat" (p.15), on top
	 * of what the GM declared. Attacker's conditions: Blinded, Restrained, Prone and Poisoned give
	 * Disadvantage; Grappled gives Disadvantage against any target other than the recorded grappler
	 * (an unrecorded grappler imposes nothing); Frightened gives Disadvantage while the recorded
	 * source of fear is an active participant of the same encounter; Invisible gives Advantage.
	 * Target's conditions: Blinded, Restrained, Stunned, Paralyzed, Petrified and Unconscious grant
	 * Advantage (Paralyzed and Unconscious also the critical hit within 5 feet); Prone grants
	 * Advantage within 5 feet and Disadvantage beyond; Invisible grants Disadvantage. The "if a
	 * creature can somehow see you" exceptions are the GM's call. A ranged attack has Disadvantage
	 * if an enemy who can see the attacker (is not Blinded, and the attacker is not Invisible) and
	 * is not Incapacitated shares the attacker's zone.
	 */
	public static AttackRollFactors attackRollFactors(
		Tx tx, RulesData rules, Long encounterId, Row attacker, Row target, boolean withinFiveFeet, boolean ranged,
		boolean declaredAdvantage, boolean declaredDisadvantage) {
		boolean advantage = declaredAdvantage;
		boolean disadvantage = declaredDisadvantage;
		var reasons = new ArrayList<String>();
		if (declaredAdvantage) {
			reasons.add("advantage declared with the action");
		}
		if (declaredDisadvantage) {
			reasons.add("disadvantage declared with the action");
		}
		List<Condition> mine = conditionsOf(tx, attacker);
		List<Condition> theirs = conditionsOf(tx, target);
		for (String condition : List.of("BLINDED", "RESTRAINED", "PRONE", "POISONED")) {
			if (has(mine, condition)) {
				disadvantage = true;
				reasons.add("attacker is " + condition.toLowerCase());
			}
		}
		List<Long> grapplers = mine.stream().filter(c -> "GRAPPLED".equals(c.name())).map(Condition::sourceCharacterId)
				.filter(Objects::nonNull).toList();
		if (!grapplers.isEmpty() && !grapplers.contains(target.id())) {
			disadvantage = true;
			reasons.add("attacker is grappled by someone other than the target");
		}
		Optional<String> fear = fearInSight(tx, encounterId, mine);
		if (fear.isPresent()) {
			disadvantage = true;
			reasons.add("attacker is frightened of " + fear.get() + ", who is in sight");
		}
		if (has(mine, "INVISIBLE")) {
			advantage = true;
			reasons.add("attacker is invisible");
		}
		for (String condition : List.of("BLINDED", "RESTRAINED", "STUNNED", "PARALYZED", "PETRIFIED", "UNCONSCIOUS")) {
			if (has(theirs, condition)) {
				advantage = true;
				reasons.add("target is " + condition.toLowerCase());
			}
		}
		if (has(theirs, "PRONE")) {
			if (withinFiveFeet) {
				advantage = true;
				reasons.add("target is prone and the attacker is within 5 feet");
			} else {
				disadvantage = true;
				reasons.add("target is prone and the attacker is more than 5 feet away");
			}
		}
		if (has(theirs, "INVISIBLE")) {
			disadvantage = true;
			reasons.add("target is invisible");
		}
		boolean critical = has(theirs, "PARALYZED") || has(theirs, "UNCONSCIOUS");
		if (dodging(tx, rules, target, theirs)) {
			disadvantage = true;
			reasons.add("target is dodging");
		}
		Effects.Modifiers targetMods = Effects.modifiers(tx, rules, target.id());
		if (targetMods.disadvantageOnAttacksAgainst) {
			disadvantage = true;
			reasons.add("target is protected (e.g. Blur)");
		}
		if (targetMods.advantageOnAttacksAgainst) {
			advantage = true;
			reasons.add("target is exposed (e.g. Faerie Fire)");
		}
		if (ranged && !has(mine, "INVISIBLE")) {
			Optional<String> enemy = adjacentEnemyWhoCanSee(tx, encounterId, attacker);
			if (enemy.isPresent()) {
				disadvantage = true;
				reasons.add("ranged attack within 5 feet of an enemy (" + enemy.get() + ")");
			}
		}
		return new AttackRollFactors(advantage, disadvantage, critical, List.copyOf(reasons));
	}

	/**
	 * Advantage and Disadvantage the actor's conditions impose on an ability check or saving throw.
	 */
	public record D20Factors(boolean advantage, boolean disadvantage, List<String> reasons) {
	}

	/**
	 * Conditions on D20 Tests other than attack rolls (SRD 5.2.1 Rules Glossary): Poisoned and
	 * Frightened (source of fear in sight) give Disadvantage on ability checks; Restrained gives
	 * Disadvantage on Dexterity saving throws, which the Dodge action makes with Advantage.
	 */
	public static D20Factors d20TestFactors(Tx tx, RulesData rules, Row actor, boolean savingThrow, String ability) {
		boolean advantage = false;
		boolean disadvantage = false;
		var reasons = new ArrayList<String>();
		List<Condition> mine = conditionsOf(tx, actor);
		if (!savingThrow) {
			if (has(mine, "POISONED")) {
				disadvantage = true;
				reasons.add("poisoned");
			}
			Optional<String> fear = fearInSight(tx, runningEncounterOf(tx, actor.id()), mine);
			if (fear.isPresent()) {
				disadvantage = true;
				reasons.add("frightened of " + fear.get() + ", who is in sight");
			}
		} else if ("DEX".equals(ability)) {
			if (has(mine, "RESTRAINED")) {
				disadvantage = true;
				reasons.add("restrained: Disadvantage on Dexterity saving throws");
			}
			if (dodging(tx, rules, actor, mine)) {
				advantage = true;
				reasons.add("dodging: Dexterity saving throws have Advantage");
			}
		}
		return new D20Factors(advantage, disadvantage, List.copyOf(reasons));
	}

	/** The encounter the character is an active participant of, if one is running. */
	public static Long runningEncounterOf(Tx tx, long characterId) {
		return tx.queryOne(
				"SELECT p.encounter_id FROM encounter_participant p JOIN encounter e ON e.id = p.encounter_id WHERE p.character_id = ? AND p.status = 'ACTIVE' AND e.status IN ('RUNNING','WAITING_CHOICE')",
				characterId).map(r -> r.lng("encounter_id")).orElse(null);
	}

	/**
	 * Frightened: "while the source of fear is within line of sight". Zones have no sight lines, so
	 * the source is in sight while it is an active participant of the same encounter; a fear whose
	 * source was never recorded, or one outside an encounter, is the GM's to adjudicate.
	 */
	private static Optional<String> fearInSight(Tx tx, Long encounterId, List<Condition> conditions) {
		if (encounterId == null) {
			return Optional.empty();
		}
		for (Condition c : conditions) {
			if ("FRIGHTENED".equals(c.name()) && c.sourceCharacterId() != null && tx.count(
					"SELECT COUNT(*) FROM encounter_participant WHERE encounter_id = ? AND character_id = ? AND status = 'ACTIVE'",
					encounterId, c.sourceCharacterId()) > 0) {
				return tx.find("character", c.sourceCharacterId()).map(source -> source.str("name"));
			}
		}
		return Optional.empty();
	}

	/**
	 * "Ranged Attacks in Close Combat" (SRD 5.2.1 p.15): an enemy in the attacker's zone who is
	 * alive, still in the fight, not Incapacitated and not Blinded.
	 */
	@SuppressWarnings("unchecked")
	private static Optional<String> adjacentEnemyWhoCanSee(Tx tx, Long encounterId, Row attacker) {
		if (encounterId == null) {
			return Optional.empty();
		}
		Row me = tx.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
				encounterId, attacker.id()).orElse(null);
		if (me == null || me.str("position_zone") == null) {
			return Optional.empty();
		}
		Row encounter = tx.get("encounter", encounterId);
		Map<String, Object> sides = encounter.isNull("sides_json") ? Map.of() : encounter.map("sides_json");
		Map<String, Object> stances = sides.get("stances") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
		for (Row p : tx.query(
				"SELECT * FROM encounter_participant WHERE encounter_id = ? AND position_zone = ? AND status = 'ACTIVE' AND character_id <> ? ORDER BY id",
				encounterId, me.str("position_zone"), attacker.id())) {
			if (!hostile(stances, me.str("side"), p.str("side"))) {
				continue;
			}
			Row enemy = tx.get("character", p.lng("character_id"));
			if (!"ALIVE".equals(enemy.str("life_state"))) {
				continue;
			}
			List<Condition> theirs = conditionsOf(tx, enemy);
			if (hasAny(theirs, INCAPACITATING_CONDITIONS) || has(theirs, "BLINDED")) {
				continue;
			}
			return Optional.of(enemy.str("name"));
		}
		return Optional.empty();
	}

	/**
	 * Two sides are hostile unless their recorded stance says otherwise; a side is never hostile to
	 * itself, so allies neither provoke nor take opportunity attacks on each other and their stat
	 * blocks never count toward the XP pool. The one reading for attacks, reactions and XP.
	 */
	public static boolean hostile(Map<String, Object> stances, String a, String b) {
		if (a == null || b == null || a.equals(b)) {
			return false;
		}
		Object s = stances.get(a + "|" + b);
		if (s == null) {
			s = stances.get(b + "|" + a);
		}
		return s == null || "HOSTILE".equals(s.toString());
	}

	/**
	 * As with Sneak Attack, a shared encounter zone represents being within 5 feet. Outside an
	 * encounter the caller must establish proximity from the attack itself (an unextended Touch
	 * spell); a melee attack's type alone does not establish distance.
	 */
	public static boolean withinFiveFeet(
		Tx tx, Long encounterId, long attackerId, long targetId, boolean closeOutsideEncounter) {
		if (encounterId == null) {
			return closeOutsideEncounter;
		}
		return tx.count(
				"SELECT COUNT(*) FROM encounter_participant a JOIN encounter_participant t ON t.encounter_id = a.encounter_id WHERE a.encounter_id = ? AND a.character_id = ? AND t.character_id = ? AND a.position_zone IS NOT NULL AND a.position_zone = t.position_zone",
				encounterId, attackerId, targetId) > 0;
	}

	/** One attack option: weapon, unarmed strike or creature action. */
	public record AttackProfile(String name, boolean ranged, int attackBonus, List<DamagePart> damage,
			boolean usesAmmunition, String ammunitionRef, Map<String, Object> range, String source, boolean finesse) {

		/**
		 * Sneak Attack and similar features ask only whether the weapon qualifies (SRD 5.2.1
		 * "Rogue").
		 */
		public boolean finesseOrRanged() {
			return finesse || ranged;
		}
	}

	/** A damage component: a dice expression (may include a constant), a fixed modifier, a type. */
	public record DamagePart(String dice, int modifier, String type) {
	}

	/**
	 * A weapon attack for a character wielding an item definition (SRD 5.2.1 "Attack Rolls",
	 * "Damage Rolls").
	 */
	public static AttackProfile weapon(
		Row character, String name, Map<String, Object> payload, int proficiencyBonus, boolean twoHanded) {
		return weapon(character, name, payload, proficiencyBonus, twoHanded, null, null, null, false);
	}

	/**
	 * A weapon attack with the overrides a spell bound to the weapon imposes (Shillelagh: the
	 * spellcasting ability's modifier, a d8, Force damage); null keeps the weapon's own.
	 */
	public static AttackProfile weapon(
		Row character, String name, Map<String, Object> payload, int proficiencyBonus, boolean twoHanded,
		Integer abilityModOverride, String diceOverride, String typeOverride) {
		return weapon(character, name, payload, proficiencyBonus, twoHanded, abilityModOverride, diceOverride,
				typeOverride, false);
	}

	/**
	 * The extra attack of the Light property omits a positive ability modifier from damage unless a
	 * feature restores it. The attack bonus, negative ability modifiers and magic bonuses stay.
	 */
	@SuppressWarnings("unchecked")
	public static AttackProfile weapon(
		Row character, String name, Map<String, Object> payload, int proficiencyBonus, boolean twoHanded,
		Integer abilityModOverride, String diceOverride, String typeOverride, boolean omitPositiveAbilityDamage) {
		List<Object> props = payload.get("properties") instanceof List<?> l ? (List<Object>) l : List.of();
		boolean rangedCategory = String.valueOf(payload.get("category")).endsWith("RANGED");
		boolean finesse = props.contains("finesse");
		int str = Rules.modifier(character.intOr("str_score", 10));
		int dex = Rules.modifier(character.intOr("dex_score", 10));
		// Melee weapons use Strength; ranged weapons use Dexterity; finesse lets you pick the better one.
		int abilityMod = abilityModOverride != null ? abilityModOverride
				: rangedCategory ? dex : finesse ? Math.max(str, dex) : str;
		Map<String, Object> damage = (Map<String, Object>) payload.get("damage");
		String dice = String.valueOf(damage.get("dice"));
		if (twoHanded && payload.get("versatile") != null) {
			dice = String.valueOf(payload.get("versatile"));
		}
		if (diceOverride != null) {
			dice = diceOverride;
		}
		boolean ammo = props.contains("ammunition");
		// A magic weapon's +N applies to attack and damage rolls (SRD 5.2.1 "Weapon, +1, +2, or +3").
		int magic = se.hirt.mcp.rpg.inventory.MagicItems.bonus(payload);
		int damageAbility = omitPositiveAbilityDamage ? Math.min(0, abilityMod) : abilityMod;
		var parts = new ArrayList<DamagePart>();
		parts.add(new DamagePart(dice, damageAbility + magic,
				typeOverride != null ? typeOverride : String.valueOf(damage.get("type"))));
		// Extra damage bound to the weapon itself (an enchanted blade's fire): its own dice and type.
		Map<String, String> extra = se.hirt.mcp.rpg.inventory.MagicItems.extraDamage(payload);
		if (extra != null) {
			parts.add(new DamagePart(extra.get("dice"), 0, extra.get("type")));
		}
		return new AttackProfile(name, rangedCategory, abilityMod + proficiencyBonus + magic, parts, ammo,
				ammo ? String.valueOf(payload.get("ammunition")) : null,
				payload.get("range") instanceof Map<?, ?> r ? (Map<String, Object>) r : null, "weapon", finesse);
	}

	/**
	 * Unarmed Strike: 1 + Strength modifier Bludgeoning (SRD 5.2.1 Rules Glossary "Unarmed
	 * Strike").
	 */
	public static AttackProfile unarmed(Row character, int proficiencyBonus) {
		int str = Rules.modifier(character.intOr("str_score", 10));
		return new AttackProfile("Unarmed Strike", false, str + proficiencyBonus,
				List.of(new DamagePart("1", str, "bludgeoning")), false, null, null, "unarmed", false);
	}

	/** A creature stat-block action of kind MELEE_ATTACK / RANGED_ATTACK. */
	@SuppressWarnings("unchecked")
	public static AttackProfile creatureAction(Map<String, Object> action) {
		var parts = new ArrayList<DamagePart>();
		for (Map<String, Object> d : (List<Map<String, Object>>) action.getOrDefault("damage", List.of())) {
			parts.add(new DamagePart(String.valueOf(d.get("dice")), 0, String.valueOf(d.get("type"))));
		}
		boolean ranged = "RANGED_ATTACK".equals(action.get("kind"));
		return new AttackProfile(String.valueOf(action.get("name")), ranged,
				((Number) action.getOrDefault("attack_bonus", 0)).intValue(), parts, false, null,
				action.get("range") instanceof Map<?, ?> r ? (Map<String, Object>) r : null, "creature",
				action.get("finesse") instanceof Boolean fin && fin);
	}

	/**
	 * The same attack with the modifiers of the spells bound to this weapon: Magic Weapon's +N on
	 * attack and damage rolls, and any extra damage dice.
	 */
	public static AttackProfile withBuff(AttackProfile p, Effects.Modifiers w) {
		if (w.attackBonus == 0 && w.damageBonusFlat == 0 && w.damageBonus.isEmpty()) {
			return p;
		}
		var parts = new ArrayList<DamagePart>();
		boolean first = true;
		for (DamagePart d : p.damage()) {
			parts.add(new DamagePart(d.dice(), d.modifier() + (first ? w.damageBonusFlat : 0), d.type()));
			first = false;
		}
		for (Map<String, Object> extra : w.damageBonus) {
			parts.add(new DamagePart(String.valueOf(extra.get("dice")), 0, String.valueOf(extra.get("type"))));
		}
		return new AttackProfile(p.name(), p.ranged(), p.attackBonus() + w.attackBonus, parts, p.usesAmmunition(),
				p.ammunitionRef(), p.range(), p.source(), p.finesse());
	}

	/**
	 * A weapon a spell conjured (Flame Blade): a melee spell attack with its own dice and the
	 * caster's modifier.
	 */
	public static AttackProfile conjured(Map<String, Object> w) {
		int attackBonus = w.get("attack_bonus") instanceof Number n ? n.intValue() : 0;
		int modifier = w.get("damage_modifier") instanceof Number n ? n.intValue() : 0;
		return new AttackProfile(String.valueOf(w.get("name")), false, attackBonus,
				List.of(new DamagePart(String.valueOf(w.get("dice")), modifier,
						String.valueOf(w.getOrDefault("type", "fire")))),
				false, null, null, "spell", false);
	}

	/** The same attack with a flat bonus to attack and damage rolls (magic ammunition's +N). */
	public static AttackProfile withBonus(AttackProfile p, int bonus) {
		return withAmmunition(p, bonus, null);
	}

	/**
	 * The same attack fired with a piece of ammunition: its +N on the attack roll and base weapon
	 * damage, plus its own extra damage ({@code {dice, type}}: an Arrow of Fire's fire), if any.
	 */
	public static AttackProfile withAmmunition(AttackProfile p, int bonus, Map<String, String> extra) {
		if (bonus == 0 && extra == null) {
			return p;
		}
		var parts = new ArrayList<DamagePart>();
		boolean first = true;
		for (DamagePart d : p.damage()) {
			parts.add(new DamagePart(d.dice(), d.modifier() + (first ? bonus : 0), d.type()));
			first = false;
		}
		if (extra != null) {
			parts.add(new DamagePart(extra.get("dice"), 0, extra.get("type")));
		}
		return new AttackProfile(p.name(), p.ranged(), p.attackBonus() + bonus, parts, p.usesAmmunition(),
				p.ammunitionRef(), p.range(), p.source(), p.finesse());
	}

	/** Doubles every dice term of an expression (critical hits roll the damage dice twice). */
	public static String critical(String expression) {
		DiceExpression e = DiceExpression.parse(expression);
		var sb = new StringBuilder();
		boolean first = true;
		for (DiceExpression.Term t : e.terms()) {
			if (!first || t.sign() < 0) {
				sb.append(t.sign() < 0 ? "-" : "+");
			}
			first = false;
			if (t.isDice()) {
				sb.append(t.count() * 2).append('d').append(t.sides());
			} else {
				sb.append(t.constant());
			}
		}
		return sb.toString();
	}

	/** Rolled damage for one part. */
	public record RolledDamage(String type, Roll roll, int amount) {
	}

	public static List<RolledDamage> rollDamage(RollService roller, AttackProfile profile, boolean critical) {
		var out = new ArrayList<RolledDamage>();
		for (DamagePart part : profile.damage()) {
			String expr = critical ? critical(part.dice()) : part.dice();
			if (part.modifier() != 0) {
				expr = expr + (part.modifier() > 0 ? "+" + part.modifier() : Integer.toString(part.modifier()));
			}
			Roll roll = roller.roll(expr);
			out.add(new RolledDamage(part.type(), roll, Math.max(0, roll.total())));
		}
		return out;
	}

	/** Damage resistances, vulnerabilities and immunities of a target. */
	public record Defenses(Set<String> resistances, Set<String> vulnerabilities, Set<String> immunities) {

		public static final Defenses NONE = new Defenses(Set.of(), Set.of(), Set.of());
	}

	/** Result of applying damage to a creature's HP pools. */
	public record DamageResult(int totalDealt, List<Map<String, Object>> breakdown, int tempHpBefore, int tempHpAfter,
			int hpBefore, int hpAfter, boolean droppedToZero, boolean massiveDamage) {
	}

	/**
	 * Damage pipeline (SRD 5.2.1 "Damage and Healing"): combine each type within one damage
	 * application, then apply immunity (0), resistance (halve, round down) and vulnerability
	 * (double); temporary HP absorbs first; then current HP, never below 0. Massive damage: if the
	 * remaining damage after reaching 0 equals or exceeds the creature's HP maximum, it dies
	 * outright.
	 */
	public static DamageResult applyDamage(
		int currentHp, int tempHp, int maxHp, List<RolledDamage> damages, Defenses defenses) {
		return applyDamage(currentHp, tempHp, maxHp, damages, defenses, false);
	}

	/** A successful save halves each combined damage type before resistance and vulnerability. */
	public static DamageResult applyDamage(
		int currentHp, int tempHp, int maxHp, List<RolledDamage> damages, Defenses defenses,
		boolean halfBeforeDefenses) {
		var byType = new LinkedHashMap<String, List<RolledDamage>>();
		for (RolledDamage damage : damages) {
			byType.computeIfAbsent(damage.type().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(damage);
		}
		var breakdown = new ArrayList<Map<String, Object>>();
		long total = 0;
		for (var group : byType.entrySet()) {
			List<RolledDamage> parts = group.getValue();
			long raw = parts.stream().mapToLong(RolledDamage::amount).sum();
			long rolled = halfBeforeDefenses ? raw / 2 : raw;
			long amount = rolled;
			String note = null;
			String type = group.getKey();
			if (defenses.immunities().contains(type)) {
				amount = 0;
				note = "immune";
			} else {
				if (defenses.resistances().contains(type)) {
					amount = amount / 2;
					note = "resistant (halved)";
				}
				if (defenses.vulnerabilities().contains(type)) {
					amount = amount * 2;
					note = note == null ? "vulnerable (doubled)" : note + ", vulnerable (doubled)";
				}
			}
			var m = new LinkedHashMap<String, Object>();
			m.put("type", parts.getFirst().type());
			m.put("rolled", damageNumber(rolled));
			m.put("applied", checkedDamage(amount));
			if (halfBeforeDefenses) {
				m.put("before_save", damageNumber(raw));
			}
			if (note != null) {
				m.put("note", note);
			}
			if (parts.size() == 1) {
				if (parts.getFirst().roll() != null) {
					m.put("roll", parts.getFirst().roll().toMap());
				}
			} else {
				m.put("parts", parts.stream().map(d -> {
					var part = new LinkedHashMap<String, Object>();
					part.put("type", d.type());
					part.put("rolled", d.amount());
					if (d.roll() != null) {
						part.put("roll", d.roll().toMap());
					}
					return part;
				}).toList());
			}
			breakdown.add(m);
			total += amount;
		}
		int totalDealt = checkedDamage(total);
		int absorbed = Math.min(tempHp, totalDealt);
		int remaining = totalDealt - absorbed;
		int hpAfter = Math.max(0, currentHp - remaining);
		int overflow = remaining - currentHp;
		boolean dropped = currentHp > 0 && hpAfter == 0;
		boolean massive = hpAfter == 0 && overflow >= maxHp && maxHp > 0;
		return new DamageResult(totalDealt, breakdown, tempHp, tempHp - absorbed, currentHp, hpAfter, dropped, massive);
	}

	private static int checkedDamage(long amount) {
		if (amount < 0 || amount > Integer.MAX_VALUE) {
			throw RpgException
					.invalidArgument("Damage after defenses must be between 0 and " + Integer.MAX_VALUE + ".");
		}
		return (int) amount;
	}

	/** Preserve ordinary breakdown values while allowing larger amounts before reductions. */
	private static Number damageNumber(long amount) {
		if (amount >= Integer.MIN_VALUE && amount <= Integer.MAX_VALUE) {
			return (int) amount;
		}
		return amount;
	}

	/** Death saving throw bookkeeping (SRD 5.2.1 "Death Saving Throws"). */
	public static Map<String, Object> deathSave(int natural, Map<String, Object> saves) {
		return deathSave(natural, natural, saves);
	}

	/**
	 * Natural 1 and 20 keep their special effects even when a modifier changes the total. "The
	 * number of both is reset to zero when you regain any Hit Points or become Stable" (SRD 5.2.1
	 * "Death Saving Throws"): a stable creature that is hurt again starts a fresh count.
	 */
	public static Map<String, Object> deathSave(int natural, int total, Map<String, Object> saves) {
		int successes = ((Number) saves.getOrDefault("successes", 0)).intValue();
		int failures = ((Number) saves.getOrDefault("failures", 0)).intValue();
		String outcome;
		if (natural == 20) {
			outcome = "REGAIN_1_HP";
			successes = 0;
			failures = 0;
		} else if (natural == 1) {
			failures += 2;
			outcome = failures >= 3 ? "DEAD" : "TWO_FAILURES";
		} else if (total >= 10) {
			successes += 1;
			outcome = successes >= 3 ? "STABLE" : "SUCCESS";
			if (successes >= 3) {
				successes = 0;
				failures = 0;
			}
		} else {
			failures += 1;
			outcome = failures >= 3 ? "DEAD" : "FAILURE";
		}
		var m = new LinkedHashMap<String, Object>();
		m.put("successes", Math.min(3, successes));
		m.put("failures", Math.min(3, failures));
		m.put("stable", outcome.equals("STABLE"));
		m.put("outcome", outcome);
		return m;
	}

	public static Map<String, Object> freshDeathSaves() {
		var m = new LinkedHashMap<String, Object>();
		m.put("successes", 0);
		m.put("failures", 0);
		m.put("stable", false);
		return m;
	}
}
