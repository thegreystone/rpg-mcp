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
import se.hirt.mcp.rpg.inventory.InventoryService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Armor training and weapon proficiency (SRD 5.2.1 "Armor Training", "Weapons"): per class from the
 * Core Traits tables in classes.json ({@code armor_training}: LIGHT, MEDIUM, HEAVY, SHIELDS;
 * {@code weapon_proficiencies}: SIMPLE, MARTIAL, MARTIAL_LIGHT, MARTIAL_FINESSE). A character
 * without class levels (a materialized stat block) is unrestricted: "A monster has training with
 * any armor in its stat block."
 */
public final class Training {

	public static final String SHIELDS = "SHIELDS";
	/**
	 * Worn armor's item categories; a shield's item category is SHIELD and {@link #shieldTrained}
	 * judges it.
	 */
	public static final List<String> BODY_ARMOR_CATEGORIES = List.of("LIGHT", "MEDIUM", "HEAVY");
	public static final List<String> WEAPON_CATEGORIES = List.of("SIMPLE", "MARTIAL", "MARTIAL_LIGHT",
			"MARTIAL_FINESSE");
	/** The drawbacks of wearing armor without training (SRD 5.2.1 "Armor Training"). */
	public static final String UNTRAINED_ARMOR_EFFECT = "Disadvantage on any D20 Test that involves Strength or Dexterity, and no spellcasting";

	private Training() {
	}

	/** The armor categories the character's classes train it in; empty when unrestricted. */
	public static Optional<Set<String>> armorTraining(Tx tx, RulesData rules, Row c) {
		return listed(tx, rules, c, "armor_training");
	}

	/**
	 * The weapon groups the character's classes make it proficient with; empty when unrestricted.
	 */
	public static Optional<Set<String>> weaponProficiencies(Tx tx, RulesData rules, Row c) {
		return listed(tx, rules, c, "weapon_proficiencies");
	}

	private static Optional<Set<String>> listed(Tx tx, RulesData rules, Row c, String key) {
		List<Row> classes = tx.query("SELECT class_ref FROM character_class WHERE character_id = ? ORDER BY id",
				c.id());
		if (classes.isEmpty()) {
			return Optional.empty();
		}
		var out = new LinkedHashSet<String>();
		boolean listed = false;
		for (Row cls : classes) {
			Object value = rules.find(cls.str("class_ref")).map(d -> d.payload().get(key)).orElse(null);
			if (value instanceof List<?> l) {
				listed = true;
				for (Object o : l) {
					out.add(String.valueOf(o).toUpperCase());
				}
			}
		}
		// A class definition without the tables (older custom content) keeps the character unrestricted.
		return listed ? Optional.of(out) : Optional.empty();
	}

	/** "You gain the Armor Class benefit of a Shield only if you have training with it." */
	public static boolean shieldTrained(Tx tx, RulesData rules, Row c) {
		return armorTraining(tx, rules, c).map(t -> t.contains(SHIELDS)).orElse(true);
	}

	/** The equipped Light, Medium or Heavy armor the character lacks training with, by name. */
	public static Optional<String> untrainedArmor(Tx tx, RulesData rules, Row c) {
		Optional<Set<String>> training = armorTraining(tx, rules, c);
		if (training.isEmpty()) {
			return Optional.empty();
		}
		for (Map<String, Object> item : InventoryService.equippedPayloads(tx, rules, c.id())) {
			if (item.get("armor") instanceof Map<?, ?> armor) {
				String category = String.valueOf(armor.get("category")).toUpperCase();
				if (BODY_ARMOR_CATEGORIES.contains(category) && !training.get().contains(category)) {
					return Optional.of(String.valueOf(item.getOrDefault("name", category)));
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Speed reduction for armor with a Strength requirement the wearer does not meet: "that armor
	 * reduces the wearer's speed by 10 feet unless the wearer has a Strength score equal to or
	 * higher than the listed score" (SRD 5.2.1 "Armor").
	 */
	public static int armorSpeedPenalty(Tx tx, RulesData rules, Row c) {
		int strength = c.intOr("str_score", 10);
		for (Map<String, Object> item : InventoryService.equippedPayloads(tx, rules, c.id())) {
			if (item.get("armor") instanceof Map<?, ?> armor && armor.get("strength_requirement") instanceof Number req
					&& strength < req.intValue()) {
				return 10;
			}
		}
		return 0;
	}

	/**
	 * Whether the character adds its Proficiency Bonus to attack rolls with this weapon (SRD 5.2.1
	 * "Weapons"): Simple or Martial by the item's category, with the Monk's and Rogue's Light /
	 * Finesse Martial subsets.
	 */
	public static boolean proficientWithWeapon(Tx tx, RulesData rules, Row c, Map<String, Object> payload) {
		Optional<Set<String>> groups = weaponProficiencies(tx, rules, c);
		if (groups.isEmpty() || !"WEAPON".equals(payload.get("type"))) {
			return true;
		}
		String category = String.valueOf(payload.get("category")).toUpperCase();
		List<?> properties = payload.get("properties") instanceof List<?> l ? l : List.of();
		if (category.startsWith("SIMPLE")) {
			return groups.get().contains("SIMPLE");
		}
		if (category.startsWith("MARTIAL")) {
			return groups.get().contains("MARTIAL")
					|| properties.contains("light") && groups.get().contains("MARTIAL_LIGHT")
					|| properties.contains("finesse") && groups.get().contains("MARTIAL_FINESSE");
		}
		return true;
	}
}
