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

import se.hirt.mcp.rpg.dice.DiceExpression;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.persistence.Row;

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
	@SuppressWarnings("unchecked")
	public static AttackProfile weapon(
		Row character, String name, Map<String, Object> payload, int proficiencyBonus, boolean twoHanded) {
		List<Object> props = payload.get("properties") instanceof List<?> l ? (List<Object>) l : List.of();
		boolean rangedCategory = String.valueOf(payload.get("category")).endsWith("RANGED");
		boolean finesse = props.contains("finesse");
		int str = Rules.modifier(character.intOr("str_score", 10));
		int dex = Rules.modifier(character.intOr("dex_score", 10));
		// Melee weapons use Strength; ranged weapons use Dexterity; finesse lets you pick the better one.
		int abilityMod = rangedCategory ? dex : finesse ? Math.max(str, dex) : str;
		Map<String, Object> damage = (Map<String, Object>) payload.get("damage");
		String dice = String.valueOf(damage.get("dice"));
		if (twoHanded && payload.get("versatile") != null) {
			dice = String.valueOf(payload.get("versatile"));
		}
		boolean ammo = props.contains("ammunition");
		return new AttackProfile(name, rangedCategory, abilityMod + proficiencyBonus,
				List.of(new DamagePart(dice, abilityMod, String.valueOf(damage.get("type")))), ammo,
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
	 * Damage pipeline (SRD 5.2.1 "Damage and Healing"): per type apply immunity (0), resistance
	 * (halve, round down) or vulnerability (double); temporary HP absorbs first; then current HP,
	 * never below 0. Massive damage: if the remaining damage after reaching 0 equals or exceeds the
	 * creature's HP maximum, it dies outright.
	 */
	public static DamageResult applyDamage(
		int currentHp, int tempHp, int maxHp, List<RolledDamage> damages, Defenses defenses) {
		var breakdown = new ArrayList<Map<String, Object>>();
		int total = 0;
		for (RolledDamage d : damages) {
			int amount = d.amount();
			String note = null;
			String type = d.type().toLowerCase();
			if (defenses.immunities().contains(type)) {
				amount = 0;
				note = "immune";
			} else if (defenses.resistances().contains(type)) {
				amount = amount / 2;
				note = "resistant (halved)";
			} else if (defenses.vulnerabilities().contains(type)) {
				amount = amount * 2;
				note = "vulnerable (doubled)";
			}
			var m = new LinkedHashMap<String, Object>();
			m.put("type", d.type());
			m.put("rolled", d.amount());
			m.put("applied", amount);
			if (note != null) {
				m.put("note", note);
			}
			if (d.roll() != null) {
				m.put("roll", d.roll().toMap());
			}
			breakdown.add(m);
			total += amount;
		}
		int absorbed = Math.min(tempHp, total);
		int remaining = total - absorbed;
		int hpAfter = Math.max(0, currentHp - remaining);
		int overflow = remaining - currentHp;
		boolean dropped = currentHp > 0 && hpAfter == 0;
		boolean massive = hpAfter == 0 && overflow >= maxHp && maxHp > 0;
		return new DamageResult(total, breakdown, tempHp, tempHp - absorbed, currentHp, hpAfter, dropped, massive);
	}

	/** Death saving throw bookkeeping (SRD 5.2.1 "Death Saving Throws"). */
	public static Map<String, Object> deathSave(int natural, Map<String, Object> saves) {
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
		} else if (natural >= 10) {
			successes += 1;
			outcome = successes >= 3 ? "STABLE" : "SUCCESS";
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
