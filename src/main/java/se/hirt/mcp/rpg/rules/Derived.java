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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derived values computed on read from base values + equipment (DOMAIN_MODEL.md §5.5): Armor Class and carrying
 * capacity. Citations: SRD 5.2.1 Equipment "Armor" table (AC formulas) and Rules Glossary "Carrying Capacity" — encoded
 * from the 2024 rules, marked [verify] per RULES_ENGINE.md §1.
 */
public final class Derived {

	public static final double GRAMS_PER_POUND = 453.59237;
	/** Strength score × 15 pounds (SRD 5.2.1 "Carrying Capacity"). */
	public static final int CAPACITY_PER_STRENGTH_POINT = 15;

	private Derived() {
	}

	public static long gramsFromLb(double lb) {
		return Math.round(lb * GRAMS_PER_POUND);
	}

	public static double lbFromGrams(long grams) {
		return Math.round(grams / GRAMS_PER_POUND * 10.0) / 10.0;
	}

	public static int carryCapacityLb(Integer strength) {
		return (strength == null ? 10 : strength) * CAPACITY_PER_STRENGTH_POINT;
	}

	/**
	 * Armor Class from the equipped body armor and shield: unarmored 10 + DEX; light = base + DEX; medium = base +
	 * min(DEX, 2); heavy = base; shield +2 (SRD 5.2.1 Armor table).
	 *
	 * @param dexScore
	 * 		the Dexterity score (null → 10)
	 * @param equipped
	 * 		payloads of every equipped item
	 */
	public static Map<String, Object> armorClass(Integer dexScore, List<Map<String, Object>> equipped) {
		return armorClass(dexScore, equipped, null, 0, null);
	}

	/**
	 * @param unarmoredBase
	 * 		an effect-granted base AC used instead of 10 + DEX when no body armor is worn (Mage Armor)
	 * @param bonus
	 * 		sum of effect AC bonuses (Shield of Faith, Haste, …)
	 * @param floor
	 * 		an effect-granted minimum AC (Barkskin)
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> armorClass(
			Integer dexScore, List<Map<String, Object>> equipped, Integer unarmoredBase, int bonus, Integer floor) {
		int dexMod = Rules.modifier(dexScore == null ? 10 : dexScore);
		int ac = 10 + dexMod;
		String basis = "Unarmored (10 + DEX)";
		boolean wearingArmor = equipped.stream().anyMatch(
				i -> i.get("armor") instanceof Map<?, ?> a && !"SHIELD".equals(String.valueOf(a.get("category"))));
		if (unarmoredBase != null && !wearingArmor) {
			ac = unarmoredBase + dexMod;
			basis = "Magical base " + unarmoredBase + " + DEX";
		}
		int shieldBonus = 0;
		String shield = null;
		for (Map<String, Object> item : equipped) {
			Object armorObj = item.get("armor");
			if (!(armorObj instanceof Map<?, ?>)) {
				continue;
			}
			Map<String, Object> armor = (Map<String, Object>) armorObj;
			String category = String.valueOf(armor.get("category"));
			if (category.equals("SHIELD")) {
				shieldBonus += ((Number) armor.getOrDefault("ac_bonus", 2)).intValue();
				shield = String.valueOf(item.getOrDefault("name", "Shield"));
				continue;
			}
			int base = ((Number) armor.get("base_ac")).intValue();
			String dexRule = String.valueOf(armor.getOrDefault("dex_bonus", "FULL"));
			int dexPart = switch (dexRule) {
				case "NONE" -> 0;
				case "MAX_2" -> Math.min(dexMod, 2);
				default -> dexMod;
			};
			ac = base + dexPart;
			basis = item.getOrDefault("name", category) + " (" + base + (dexRule.equals("NONE") ? ""
					: " + DEX" + (dexRule.equals("MAX_2") ? " max 2" : "")) + ")";
		}
		int value = ac + shieldBonus + bonus;
		if (floor != null && value < floor) {
			value = floor;
			basis = basis + ", raised to a floor of " + floor;
		}
		var m = new LinkedHashMap<String, Object>();
		m.put("value", value);
		m.put("basis", basis);
		if (shield != null) {
			m.put("shield", shield + " (+" + shieldBonus + ")");
		}
		if (bonus != 0) {
			m.put("effect_bonus", bonus);
		}
		return m;
	}
}
