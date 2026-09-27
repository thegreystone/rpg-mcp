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
package se.hirt.mcp.rpg.inventory;

import se.hirt.mcp.rpg.content.ContentService;
import se.hirt.mcp.rpg.content.ContentService.Item;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.rules.Derived;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Magic items (SRD 5.2.1 "Magic Items", seeded from the Magic Items A–Z as {@code ITEM} content
 * with a {@code magic} block: category, rarity, attunement, and for the few the engine enforces a
 * {@code bonus_by_rarity}, {@code modifiers} or {@code consumable} mechanic; RULES_ENGINE.md §10).
 * <p>
 * A magic weapon, armor, shield or ammunition entry in the SRD describes <em>any</em> base item of
 * its kind ("Weapon, +1, +2, or +3", "Flame Tongue (Any Sword)"), so such entries are
 * <em>templates</em>: they are never granted as they are but instantiated on a base item into a
 * campaign-owned definition ("+1 Longsword", "Flame Tongue (Longsword)") that carries the base's
 * mechanics plus the template's magic. Everything else (rings, wondrous items, potions, staffs…) is
 * granted directly.
 */
public final class MagicItems {

	/**
	 * Values of the SRD "Magic Item Rarities and Values" table in copper; consumables are worth
	 * half.
	 */
	public static final Map<String, Long> VALUE_CP = Map.of("COMMON", 10_000L, "UNCOMMON", 40_000L, "RARE", 400_000L,
			"VERY_RARE", 4_000_000L, "LEGENDARY", 20_000_000L, "ARTIFACT", 0L, "VARIES", 0L);

	public static final List<String> RARITIES = List.of("COMMON", "UNCOMMON", "RARE", "VERY_RARE", "LEGENDARY",
			"ARTIFACT");

	/** The SRD allows a creature to be attuned to at most three items at a time ("Attunement"). */
	public static final int ATTUNEMENT_LIMIT = 3;

	private static final Pattern PLUS_FORM = Pattern.compile("^\\+(\\d)\\s+(.+)$");

	private MagicItems() {
	}

	// ── payload accessors ──────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	public static Map<String, Object> magic(Map<String, Object> payload) {
		return payload != null && payload.get("magic") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
	}

	public static boolean isMagic(Map<String, Object> payload) {
		return magic(payload) != null;
	}

	public static boolean isTemplate(Map<String, Object> payload) {
		Map<String, Object> m = magic(payload);
		return m != null && Boolean.TRUE.equals(m.get("template"));
	}

	public static boolean requiresAttunement(Map<String, Object> payload) {
		Map<String, Object> m = magic(payload);
		return m != null && Boolean.TRUE.equals(m.get("attunement"));
	}

	public static String rarity(Map<String, Object> payload) {
		Map<String, Object> m = magic(payload);
		return m == null ? null : String.valueOf(m.getOrDefault("rarity", "VARIES"));
	}

	/**
	 * The +N of an instantiated magic weapon, armor, shield or piece of ammunition; 0 otherwise.
	 */
	public static int bonus(Map<String, Object> payload) {
		Map<String, Object> m = magic(payload);
		return m != null && m.get("bonus") instanceof Number n ? n.intValue() : 0;
	}

	/**
	 * Modifiers applied while the item is equipped, in the {@code active_effect.modifier_json}
	 * vocabulary.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> modifiers(Map<String, Object> payload) {
		return payload != null && payload.get("modifiers") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}

	/** Whether the engine applies this item's mechanics or the GM adjudicates its text. */
	public static String adjudication(Map<String, Object> payload) {
		Map<String, Object> m = magic(payload);
		return m != null && "ENGINE".equals(m.get("enforcement")) ? "engine" : "GM";
	}

	/** "Rare, requires attunement" — the one-line label the sheets and lists show. */
	public static String label(Map<String, Object> payload) {
		Map<String, Object> m = magic(payload);
		if (m == null) {
			return null;
		}
		String r = String.valueOf(m.getOrDefault("rarity", "VARIES")).toLowerCase().replace('_', ' ');
		String s = r.substring(0, 1).toUpperCase() + r.substring(1);
		if (Boolean.TRUE.equals(m.get("attunement"))) {
			s += ", requires attunement" + (m.get("attunement_by") != null ? " by " + m.get("attunement_by") : "");
		}
		return s;
	}

	/** The compact view of an item's magic for sheets, inventories and definition lists. */
	public static Map<String, Object> view(Map<String, Object> payload) {
		Map<String, Object> m = magic(payload);
		if (m == null) {
			return null;
		}
		var v = new LinkedHashMap<String, Object>();
		v.put("rarity", m.getOrDefault("rarity", "VARIES"));
		v.put("attunement", Boolean.TRUE.equals(m.get("attunement")));
		if (m.get("attunement_by") != null) {
			v.put("attunement_by", m.get("attunement_by"));
		}
		if (Boolean.TRUE.equals(m.get("template"))) {
			v.put("template", true);
			v.put("applies_to", m.get("applies_to"));
			if (m.get("bonus_by_rarity") != null) {
				v.put("bonus_by_rarity", m.get("bonus_by_rarity"));
			}
		}
		if (m.get("bonus") instanceof Number n) {
			v.put("bonus", n.intValue());
		}
		if (m.get("base_ref") != null) {
			v.put("base", m.get("base_ref"));
		}
		if (Boolean.TRUE.equals(m.get("consumable"))) {
			v.put("consumable", true);
		}
		v.put("adjudication", adjudication(payload));
		Map<String, Object> mods = modifiers(payload);
		if (!mods.isEmpty()) {
			v.put("modifiers", mods);
		}
		if (payload.get("consumable") instanceof Map<?, ?> c) {
			v.put("effect", c);
		}
		return v;
	}

	// ── resolution and instantiation ───────────────────────────────────

	/**
	 * Resolves an item as named by a loot or purchase spec, instantiating a magic template on its
	 * base when needed. Accepted forms: {@code {item: "Ring of Protection"}} (a concrete item),
	 * {@code {item: "+1 Longsword"}} (the +N form: the base and the bonus in one string),
	 * {@code {item: "Weapon, +1, +2, or +3", base: "Longsword", bonus: 2}} and
	 * {@code {item: "Flame Tongue", base: "Longsword", name: "Ember"}}.
	 */
	public static Item resolve(Tx tx, RulesData rules, long campaignId, Map<String, Object> spec) {
		String text = spec.get("item") == null ? null : spec.get("item").toString().trim();
		String base = spec.get("base") == null ? null : spec.get("base").toString().trim();
		Integer bonus = spec.get("bonus") instanceof Number n ? n.intValue() : null;
		String name = spec.get("name") == null ? null : spec.get("name").toString().trim();
		if (text == null || text.isBlank()) {
			throw RpgException
					.invalidArgument("An item is required (name, 'srd5e:item/...', 'content:N' or 'custom:...').");
		}
		if (spec.get("magic") instanceof Map<?, ?> enchantment) {
			// A GM-made enchantment on a mundane base: {"item": "Arrow", "magic": {"name": "Arrow of Fire", ...}}.
			@SuppressWarnings("unchecked")
			Map<String, Object> e = (Map<String, Object>) enchantment;
			return enchant(tx, campaignId, ContentService.resolveItem(tx, rules, campaignId, text), e);
		}
		Matcher plus = PLUS_FORM.matcher(text);
		if (plus.matches()) {
			// "+1 Longsword": the base names the template kind, and a base given beside it must agree.
			if (base != null && !base.equalsIgnoreCase(plus.group(2).trim())) {
				throw RpgException
						.invalidArgument("'" + text + "' already names its base; base '" + base + "' contradicts it.");
			}
			bonus = bonus == null ? Integer.parseInt(plus.group(1)) : bonus;
			base = plus.group(2).trim();
			// A definition of that exact name may already exist in the campaign.
			Optional<Row> existing = tx.queryOne(
					"SELECT * FROM custom_content WHERE campaign_id = ? AND kind = 'ITEM' AND LOWER(name) = LOWER(?)",
					campaignId, text);
			if (existing.isPresent()) {
				return ContentService.fromCustomRow(existing.get());
			}
			Item baseItem = ContentService.resolveItem(tx, rules, campaignId, base);
			text = switch (baseItem.type()) {
			case "WEAPON" -> "Weapon, +1, +2, or +3";
			case "ARMOR" -> "Armor, +1, +2, or +3";
			case "SHIELD" -> "Shield, +1, +2, or +3";
			case "AMMUNITION" -> "Ammunition, +1, +2, or +3";
			default -> throw RpgException.invalidArgument("'" + text + "': " + baseItem.name() + " is a "
					+ baseItem.type().toLowerCase() + "; only a weapon, armor, shield or ammunition takes a +N.");
			};
		}
		Item item = ContentService.resolveItem(tx, rules, campaignId, text);
		if (!isTemplate(item.payload())) {
			if (base != null) {
				throw RpgException.invalidArgument(item.name() + " is a complete item; it takes no base.");
			}
			return item;
		}
		if (base == null) {
			throw RpgException.invalidArgument("'" + item.name() + "' describes any "
					+ magic(item.payload()).getOrDefault("applies_to", "item of its kind")
					+ "; name the base it is made on, e.g. {\"item\": \"" + item.name()
					+ "\", \"base\": \"Longsword\"} or, for a +N item, \"+1 Longsword\".");
		}
		Item baseItem = ContentService.resolveItem(tx, rules, campaignId, base);
		return instantiate(tx, campaignId, item, baseItem, bonus, name);
	}

	/**
	 * Makes a campaign-owned definition of {@code template} on {@code base}: the base's mechanics
	 * (damage, armor, slot, weight) plus the template's magic, rarity and text. A definition of the
	 * same name in the campaign is reused, so ten "+1 Arrow"s are one definition.
	 */
	public static Item instantiate(Tx tx, long campaignId, Item template, Item base, Integer bonus, String name) {
		Map<String, Object> tm = magic(template.payload());
		if (tm == null || !Boolean.TRUE.equals(tm.get("template"))) {
			throw RpgException.invalidArgument(template.name() + " is not a magic item template.");
		}
		if (isMagic(base.payload())) {
			throw RpgException.invalidArgument(base.name() + " is already magical; a template needs a mundane base.");
		}
		if (!template.type().equals(base.type())) {
			throw RpgException.validation(List.of(new Violation("base", "BASE_TYPE", template.name() + " is made on "
					+ template.type().toLowerCase() + "; " + base.name() + " is " + base.type().toLowerCase() + ".")));
		}
		String appliesTo = String.valueOf(tm.getOrDefault("applies_to", "Any"));
		if (!appliesTo.startsWith("Any") && !appliesTo.toLowerCase().contains(base.name().toLowerCase())) {
			throw RpgException.validation(List.of(new Violation("base", "BASE_KIND",
					template.name() + " is made on " + appliesTo + ", not on " + base.name() + ".")));
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> byRarity = tm.get("bonus_by_rarity") instanceof Map<?, ?> b ? (Map<String, Object>) b
				: null;
		String rarity = String.valueOf(tm.getOrDefault("rarity", "VARIES"));
		if (byRarity != null) {
			int n = bonus == null ? 1 : bonus;
			rarity = byRarity.entrySet().stream().filter(e -> ((Number) e.getValue()).intValue() == n)
					.map(Map.Entry::getKey).findFirst().orElseThrow(() -> RpgException
							.invalidArgument(template.name() + " comes as " + byRarity.values() + ", not +" + n + "."));
			bonus = n;
		} else if (bonus != null) {
			throw RpgException.invalidArgument(template.name() + " has no +N; leave bonus out.");
		}
		String itemName = name != null && !name.isBlank() ? name
				: bonus != null ? "+" + bonus + " " + base.name() : template.name() + " (" + base.name() + ")";
		Optional<Row> existing = tx.queryOne(
				"SELECT * FROM custom_content WHERE campaign_id = ? AND kind = 'ITEM' AND LOWER(name) = LOWER(?)",
				campaignId, itemName);
		if (existing.isPresent()) {
			return ContentService.fromCustomRow(existing.get());
		}

		var payload = new LinkedHashMap<String, Object>(base.payload());
		payload.remove("symbolic_id");
		var magic = new LinkedHashMap<String, Object>();
		magic.put("category", tm.get("category"));
		magic.put("rarity", rarity);
		magic.put("attunement", Boolean.TRUE.equals(tm.get("attunement")));
		if (tm.get("attunement_by") != null) {
			magic.put("attunement_by", tm.get("attunement_by"));
		}
		if (Boolean.TRUE.equals(tm.get("consumable"))) {
			magic.put("consumable", true);
		}
		if (bonus != null) {
			magic.put("bonus", bonus);
		}
		magic.put("template_ref", template.display());
		magic.put("base_ref", base.display());
		magic.put("enforcement", tm.getOrDefault("enforcement", "GM"));
		payload.put("magic", magic);
		if (template.payload().get("modifiers") != null) {
			payload.put("modifiers", template.payload().get("modifiers"));
		}
		if (template.payload().get("consumable") != null) {
			payload.put("consumable", template.payload().get("consumable"));
		}
		payload.put("summary", template.payload().get("summary"));
		payload.put("text", template.payload().get("text"));
		payload.put("text_is_paraphrase", false);
		long value = VALUE_CP.getOrDefault(rarity, 0L);
		if (Boolean.TRUE.equals(tm.get("consumable"))) {
			value /= 2;
		}
		long costCp = base.costCp() + value;
		payload.put("cost_cp", costCp);
		double lb = base.payload().get("weight_lb") instanceof Number w ? w.doubleValue() : 0;

		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("kind", "ITEM");
		cols.put("symbolic_id", null);
		cols.put("name", itemName);
		cols.put("payload_json", Json.write(payload));
		cols.put("cost_cp", costCp);
		cols.put("weight_g", Derived.gramsFromLb(lb));
		cols.put("tags_json", Json.write(List.of("magic", rarity.toLowerCase())));
		cols.put("license_json",
				Json.write(Map.of("license", "CC-BY-4.0", "source", template.display(), "provenance", "GM")));
		cols.put("provenance", "GM");
		cols.put("revision", 0);
		cols.put("created_at", Instant.now().toString());
		long id = tx.insert("custom_content", cols);
		tx.touched(Ref.of(Ref.CONTENT, id), 0);
		return ContentService.fromCustomRow(tx.get("custom_content", id));
	}

	/**
	 * The modifier keys an enchantment may carry, in the {@code active_effect.modifier_json}
	 * vocabulary.
	 */
	private static final List<String> ENCHANT_MODIFIERS = List.of("ac_bonus", "ac_bonus_unarmored", "save_bonus",
			"attack_bonus", "speed_bonus", "resistance", "damage_bonus_dice", "damage_bonus_type");

	/**
	 * Makes a campaign-owned magic item the SRD does not list, on a mundane base: the fiction's
	 * Arrow of Fire, a frost blade, a lucky charm. The GM names it, gives it a rarity, a
	 * {@code text} and the mechanics the engine applies: {@code bonus} (the +N of a weapon, armor,
	 * shield or ammunition), {@code damage_bonus_dice} with {@code damage_type} (extra damage on a
	 * hit with the weapon or the piece of ammunition), {@code ac_bonus}, {@code save_bonus},
	 * {@code attack_bonus}, {@code speed_bonus}, {@code resistance} (worn items, while equipped),
	 * {@code attunement}, and {@code consumable: {heal}} for a draught. Anything else stays in the
	 * text for the GM. One definition per name.
	 */
	public static Item enchant(Tx tx, long campaignId, Item base, Map<String, Object> spec) {
		String name = spec.get("name") == null ? null : spec.get("name").toString().trim();
		if (name == null || name.isBlank()) {
			throw RpgException
					.invalidArgument("magic.name is required: what the enchanted " + base.name() + " is called.");
		}
		if (isMagic(base.payload())) {
			throw RpgException.invalidArgument(base.name() + " is already magical; enchant a mundane base.");
		}
		Optional<Row> existing = tx.queryOne(
				"SELECT * FROM custom_content WHERE campaign_id = ? AND kind = 'ITEM' AND LOWER(name) = LOWER(?)",
				campaignId, name);
		if (existing.isPresent()) {
			return ContentService.fromCustomRow(existing.get());
		}
		String rarity = spec.get("rarity") == null ? "UNCOMMON"
				: spec.get("rarity").toString().trim().toUpperCase().replace(' ', '_');
		if (!RARITIES.contains(rarity)) {
			throw RpgException.invalidArgument("magic.rarity must be one of " + RARITIES + ".");
		}
		Integer bonus = spec.get("bonus") instanceof Number n ? n.intValue() : null;
		boolean plusKind = List.of("WEAPON", "ARMOR", "SHIELD", "AMMUNITION").contains(base.type());
		if (bonus != null && !plusKind) {
			throw RpgException.invalidArgument("A +N belongs to a weapon, armor, shield or ammunition, not a "
					+ base.type().toLowerCase() + "; use ac_bonus, save_bonus or attack_bonus for a worn item.");
		}
		if (bonus != null && (bonus < 1 || bonus > 3)) {
			throw RpgException.invalidArgument("magic.bonus must be +1, +2 or +3.");
		}
		var modifiers = new LinkedHashMap<String, Object>();
		for (String k : ENCHANT_MODIFIERS) {
			if (spec.get(k) != null) {
				modifiers.put(k, spec.get(k));
			}
		}
		if (spec.get("damage_type") != null && modifiers.containsKey("damage_bonus_dice")) {
			modifiers.put("damage_bonus_type", spec.get("damage_type").toString().toLowerCase());
		}
		if (modifiers.containsKey("damage_bonus_dice")) {
			se.hirt.mcp.rpg.dice.DiceExpression.parse(String.valueOf(modifiers.get("damage_bonus_dice")));
			if (!plusKind || base.type().equals("ARMOR") || base.type().equals("SHIELD")) {
				throw RpgException.invalidArgument("damage_bonus_dice belongs to a weapon or ammunition.");
			}
		}
		boolean consumable = base.type().equals("AMMUNITION") || spec.get("consumable") instanceof Map<?, ?>;
		String text = spec.get("text") == null ? null : spec.get("text").toString().trim();
		if (text == null || text.isBlank()) {
			throw RpgException.invalidArgument(
					"magic.text is required: what " + name + " does, in the words the sheet will show.");
		}

		var payload = new LinkedHashMap<String, Object>(base.payload());
		payload.remove("symbolic_id");
		var magic = new LinkedHashMap<String, Object>();
		magic.put("category", switch (base.type()) {
		case "WEAPON", "AMMUNITION" -> "Weapon";
		case "ARMOR", "SHIELD" -> "Armor";
		default -> "Wondrous Item";
		});
		magic.put("rarity", rarity);
		magic.put("attunement", Boolean.TRUE.equals(spec.get("attunement")));
		if (consumable) {
			magic.put("consumable", true);
		}
		if (bonus != null) {
			magic.put("bonus", bonus);
		}
		magic.put("base_ref", base.display());
		magic.put("custom", true);
		boolean enforced = bonus != null || !modifiers.isEmpty() || spec.get("consumable") instanceof Map<?, ?>;
		magic.put("enforcement", enforced ? "ENGINE" : "GM");
		payload.put("magic", magic);
		if (!modifiers.isEmpty()) {
			payload.put("modifiers", modifiers);
		}
		if (spec.get("consumable") instanceof Map<?, ?> c) {
			if (c.get("heal") != null) {
				se.hirt.mcp.rpg.dice.DiceExpression.parse(String.valueOf(c.get("heal")));
			}
			payload.put("consumable", c);
		}
		if (spec.get("slot") != null) {
			payload.put("slot", spec.get("slot").toString().toUpperCase());
		}
		payload.put("summary", text.length() > 200 ? text.substring(0, 200) + "…" : text);
		payload.put("text", text);
		payload.put("text_is_paraphrase", true);
		long value = VALUE_CP.getOrDefault(rarity, 0L);
		if (consumable) {
			value /= 2;
		}
		long costCp = base.costCp() + value;
		payload.put("cost_cp", costCp);
		double lb = base.payload().get("weight_lb") instanceof Number w ? w.doubleValue() : 0;

		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("kind", "ITEM");
		cols.put("symbolic_id", null);
		cols.put("name", name);
		cols.put("payload_json", Json.write(payload));
		cols.put("cost_cp", costCp);
		cols.put("weight_g", Derived.gramsFromLb(lb));
		cols.put("tags_json", Json.write(List.of("magic", rarity.toLowerCase(), "homebrew")));
		cols.put("license_json", Json.write(Map.of("license", "campaign-owned", "provenance", "GM")));
		cols.put("provenance", "GM");
		cols.put("revision", 0);
		cols.put("created_at", Instant.now().toString());
		long id = tx.insert("custom_content", cols);
		tx.touched(Ref.of(Ref.CONTENT, id), 0);
		return ContentService.fromCustomRow(tx.get("custom_content", id));
	}

	/**
	 * Extra damage a weapon or piece of ammunition adds on a hit, as {@code {dice, type}}; null
	 * when none.
	 */
	public static Map<String, String> extraDamage(Map<String, Object> payload) {
		Map<String, Object> mods = modifiers(payload);
		if (!(mods.get("damage_bonus_dice") instanceof String dice) || dice.isBlank()) {
			return null;
		}
		return Map.of("dice", dice, "type",
				String.valueOf(mods.getOrDefault("damage_bonus_type", "force")).toLowerCase());
	}

	/** Refuses a template where a concrete item is needed (a purchase, a transfer). */
	public static void requireConcrete(Item item, String what) {
		if (isTemplate(item.payload())) {
			throw RpgException.invalidArgument("'" + item.name() + "' describes any "
					+ magic(item.payload()).getOrDefault("applies_to", "item of its kind") + "; " + what
					+ " needs a concrete item: grant_loot with {\"item\": \"" + item.name()
					+ "\", \"base\": \"Longsword\"} or \"+1 Longsword\" first.");
		}
	}
}
