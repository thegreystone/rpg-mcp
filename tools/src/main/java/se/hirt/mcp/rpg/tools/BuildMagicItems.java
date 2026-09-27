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
package se.hirt.mcp.rpg.tools;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regenerates {@code seed/srd5e/magic-items.json} from the SRD 5.2.1 "Magic Items A–Z" section (pp.
 * 209–253), lifting every item's rules text verbatim like {@link BuildRules} does for the glossary.
 * The structured part (category, rarity, attunement, wearable slot) is parsed from each entry's
 * type line; the few mechanics the engine enforces (the +N of a magic weapon, armor or shield, the
 * Armor Class and saving-throw bonus of a Ring or Cloak of Protection, the healing dice of a
 * potion) come from the {@link #MECHANICS} overlay below, so a regeneration never loses them.
 */
final class BuildMagicItems {
	private static final String CATEGORIES = "Armor|Weapon|Wondrous Item|Ring|Rod|Staff|Wand|Potion|Scroll";
	private static final Pattern HEAD_START = Py.re("^(" + CATEGORIES + ")(?: \\(|,)");
	private static final Pattern HEAD = Py
			.re("^(" + CATEGORIES + ")(?: \\(([^)]*)\\))?, (.+?)\\s*(\\((Requires Attunement[^)]*)\\))?\\s*$");
	private static final Pattern RARITY = Py
			.re("\\b(Very Rare|Rarity Varies|Common|Uncommon|Rare|Legendary|Artifact)\\b(?: \\(\\+(\\d)\\))?");
	private static final Pattern CONTINUATION = Py
			.re("^([(]|or |Attunement|by |Rare|Very Rare|Legendary|Uncommon|Common|Artifact)");
	private static final String RIGHT_QUOTE = Character.toString(0x2019);
	private static final String LEFT_DQUOTE = Character.toString(0x201C);
	private static final String RIGHT_DQUOTE = Character.toString(0x201D);
	private static final String MINUS = Character.toString(0x2212);
	private static final String EN_DASH = Character.toString(0x2013);
	private static final String ELLIPSIS = Character.toString(0x2026);

	/** Values of the "Magic Item Rarities and Values" table (p. 206), in copper. */
	private static final Map<String, Long> VALUE_CP = Map.of("COMMON", 10_000L, "UNCOMMON", 40_000L, "RARE", 400_000L,
			"VERY_RARE", 4_000_000L, "LEGENDARY", 20_000_000L, "ARTIFACT", 0L, "VARIES", 0L);

	/**
	 * Mechanics the engine applies, keyed by item name. {@code modifiers} uses the vocabulary of
	 * {@code active_effect.modifier_json} (ac_bonus, save_bonus, ac_bonus_unarmored, resistance,
	 * speed_bonus …) and is folded into a character's modifiers while the item is equipped;
	 * {@code consumable.heal} is rolled by USE_ITEM.
	 */
	private static final Map<String, Map<String, Object>> MECHANICS = new LinkedHashMap<>();
	static {
		MECHANICS.put("Ring of Protection", Map.of("modifiers", Map.of("ac_bonus", 1, "save_bonus", 1)));
		MECHANICS.put("Cloak of Protection", Map.of("modifiers", Map.of("ac_bonus", 1, "save_bonus", 1)));
		MECHANICS.put("Bracers of Defense", Map.of("modifiers", Map.of("ac_bonus_unarmored", 2)));
		MECHANICS.put("Potion of Healing", Map.of("consumable", Map.of("heal", "2d4+2")));
		MECHANICS.put("Potion of Healing (Greater)", Map.of("consumable", Map.of("heal", "4d4+4")));
		MECHANICS.put("Potion of Healing (Superior)", Map.of("consumable", Map.of("heal", "8d4+8")));
		MECHANICS.put("Potion of Healing (Supreme)", Map.of("consumable", Map.of("heal", "10d4+20")));
	}

	/**
	 * Wearable slot by the words of a Wondrous Item's name; unlisted items are carried, not worn.
	 */
	private static final List<Map.Entry<Pattern, String>> SLOTS = List.of(
			Map.entry(Py.re("\\b(boots|slippers)\\b"), "BOOTS"), Map.entry(Py.re("\\b(cloak|cape|mantle)\\b"), "CLOAK"),
			Map.entry(Py.re("\\brobe\\b"), "BODY"), Map.entry(Py.re("\\b(belt|girdle)\\b"), "BELT"),
			Map.entry(Py.re("\\b(amulet|necklace|periapt|medallion|brooch|scarab|talisman|pendant)\\b"), "NECK"),
			Map.entry(Py.re("\\b(gloves|gauntlets)\\b"), "HANDS"), Map.entry(Py.re("\\bbracers\\b"), "WRISTS"),
			Map.entry(Py.re("\\b(helm|hat|headband|circlet|cap|crown|mask)\\b"), "HEAD"),
			Map.entry(Py.re("\\b(goggles|eyes|lenses)\\b"), "EYES"));

	/**
	 * Categories whose entries describe any base item of the kind and are instantiated on a base.
	 */
	private static final Set<String> TEMPLATE_TYPES = Set.of("ARMOR", "SHIELD", "WEAPON", "AMMUNITION");

	private final String txt;

	BuildMagicItems(String txt) {
		this.txt = txt;
	}

	private record Entry(String name, String header, String body) {
	}

	void run(Path out) throws IOException {
		int start = txt.indexOf("=== PAGE 209 ===");
		int end = start < 0 ? -1 : txt.indexOf("=== PAGE 254 ===", start);
		if (start < 0 || end < 0) {
			throw new IllegalStateException("could not locate Magic Items A-Z (pp. 209-253)");
		}
		String g = txt.substring(start, end);
		g = g.replaceAll("=== PAGE \\d+ ===\n", "");
		g = g.replaceAll("System Reference Document 5\\.2\\.1\n\\d+\n", "");
		g = g.replaceAll("\\d+\nSystem Reference Document 5\\.2\\.1\n", "");
		g = g.replace(RIGHT_QUOTE, "'").replace(LEFT_DQUOTE, "\"").replace(RIGHT_DQUOTE, "\"");
		g = Py.re("(\\w)\\s*[-" + MINUS + EN_DASH + "]\\s*\n(\\w)").matcher(g).replaceAll("$1$2");
		g = g.replace(MINUS, "-");

		List<String> lines = List.of(g.split("\n", -1));
		List<Entry> entries = new ArrayList<>();
		int i = 0;
		int bodyFrom = -1;
		Entry open = null;
		while (i < lines.size()) {
			String line = lines.get(i).strip();
			if (SrdText.match(HEAD_START, line) != null) {
				// A type line may wrap onto the next one or two lines: "Uncommon (+1), Rare" / "(+2), or Very Rare (+3)".
				String header = line;
				int consumed = 1;
				while (consumed < 4 && i + consumed < lines.size()
						&& continues(header, lines.get(i + consumed).strip())) {
					header = header + " " + lines.get(i + consumed).strip();
					consumed++;
				}
				Matcher m = SrdText.match(HEAD, header);
				String name = previousNonEmpty(lines, i);
				if (m != null && isName(name) && SrdText.search(RARITY, m.group(3)) != null) {
					if (open != null) {
						entries.add(new Entry(open.name(), open.header(), body(lines, bodyFrom, i)));
					}
					open = new Entry(name, header.replaceAll("\\s+", " "), null);
					i += consumed;
					bodyFrom = i;
					continue;
				}
			}
			i++;
		}
		if (open != null) {
			entries.add(new Entry(open.name(), open.header(), body(lines, bodyFrom, lines.size())));
		}

		// "Potion of Healing" is one entry with a table of four potencies; the engine needs one item per potency.
		List<Entry> expanded = new ArrayList<>();
		for (Entry e : entries) {
			if (e.name().equals("Potions of Healing")) {
				expanded.add(new Entry("Potion of Healing", "Potion, Common", e.body()));
				expanded.add(new Entry("Potion of Healing (Greater)", "Potion, Uncommon", e.body()));
				expanded.add(new Entry("Potion of Healing (Superior)", "Potion, Rare", e.body()));
				expanded.add(new Entry("Potion of Healing (Supreme)", "Potion, Very Rare", e.body()));
			} else {
				expanded.add(e);
			}
		}
		entries = expanded;

		ObjectMapper mapper = new ObjectMapper();
		List<ObjectNode> nodes = new ArrayList<>();
		Map<String, Integer> byRarity = new TreeMap<>();
		for (Entry e : entries) {
			Matcher m = SrdText.match(HEAD, e.header());
			String category = m.group(1);
			String subtype = m.group(2);
			String rarityText = m.group(3);
			String attunement = m.group(5);

			ObjectNode payload = mapper.createObjectNode();
			ObjectNode magic = mapper.createObjectNode();
			String type = switch (category) {
			case "Armor" -> "Shield".equals(subtype) ? "SHIELD" : "ARMOR";
			case "Weapon" -> subtype != null && subtype.startsWith("Any Ammunition") ? "AMMUNITION" : "WEAPON";
			case "Wondrous Item" -> "WONDROUS";
			default -> category.toUpperCase();
			};
			magic.put("category", category);
			if (subtype != null) {
				magic.put("subtype", subtype);
			}
			Map<String, Integer> bonusByRarity = new LinkedHashMap<>();
			List<String> rarities = new ArrayList<>();
			Matcher r = RARITY.matcher(rarityText);
			while (r.find()) {
				String rarity = r.group(1).equals("Rarity Varies") ? "VARIES"
						: r.group(1).toUpperCase().replace(' ', '_');
				rarities.add(rarity);
				if (r.group(2) != null) {
					bonusByRarity.put(rarity, Integer.parseInt(r.group(2)));
				}
			}
			String rarity = rarities.size() == 1 ? rarities.get(0) : "VARIES";
			magic.put("rarity", rarity);
			if (rarities.size() > 1) {
				ArrayNode arr = magic.putArray("rarities");
				rarities.forEach(arr::add);
			}
			if (!bonusByRarity.isEmpty()) {
				ObjectNode b = magic.putObject("bonus_by_rarity");
				bonusByRarity.forEach(b::put);
			}
			magic.put("attunement", attunement != null);
			if (attunement != null && attunement.length() > "Requires Attunement".length()) {
				magic.put("attunement_by", attunement.substring("Requires Attunement by ".length()).strip());
			}
			boolean template = TEMPLATE_TYPES.contains(type);
			if (template) {
				magic.put("template", true);
				magic.put("applies_to", subtype == null ? "Any" : subtype);
			}
			boolean consumable = type.equals("POTION") || type.equals("SCROLL") || type.equals("AMMUNITION");
			if (consumable) {
				magic.put("consumable", true);
			}
			Map<String, Object> mech = MECHANICS.get(e.name());
			// A +N is applied by the engine on weapons, armor, shields and ammunition; a wand's spell-attack bonus is not.
			magic.put("enforcement", mech != null || !bonusByRarity.isEmpty() && template ? "ENGINE" : "GM");

			payload.put("type", type);
			long value = VALUE_CP.getOrDefault(rarity, 0L);
			if (consumable && !e.name().equals("Spell Scroll")) {
				value /= 2;
			}
			payload.put("cost_cp", value);
			payload.put("weight_lb", type.equals("POTION") ? 0.5 : 0);
			String slot = slotFor(type, e.name());
			if (slot != null) {
				payload.put("slot", slot);
			}
			payload.set("magic", magic);
			if (mech != null) {
				mech.forEach((k, v) -> payload.set(k, mapper.valueToTree(v)));
			}
			String first200 = Py.head(e.body(), 200);
			int cut = first200.lastIndexOf(' ');
			payload.put("summary",
					(cut >= 0 ? first200.substring(0, cut) : first200) + (e.body().length() > 200 ? ELLIPSIS : ""));
			payload.put("text", e.body());
			payload.put("text_is_paraphrase", false);

			ObjectNode node = mapper.createObjectNode();
			node.put("id", "srd5e:item/" + slug(e.name()));
			node.put("name", e.name());
			node.set("payload", payload);
			nodes.add(node);
			byRarity.merge(rarity, 1, Integer::sum);
		}
		nodes.sort((a, b) -> a.get("name").asText().toLowerCase().compareTo(b.get("name").asText().toLowerCase()));

		ObjectNode doc = mapper.createObjectNode();
		doc.put("kind", "ITEM");
		doc.put("citation",
				"SRD 5.2.1, Magic Items - Magic Items A-Z (pp. 209-253); values from Magic Item Rarities and Values (p. 206)");
		doc.put("verify", "Lifted verbatim from the SRD 5.2.1 PDF text (CC-BY-4.0) on " + LocalDate.now()
				+ " by the tools/ magic-item builder (SrdTool build-magic-items): each entry's text is the item's own wording "
				+ "with the two-column hyphenation repaired, not a paraphrase; category, rarity and attunement are parsed from "
				+ "the type line; cost_cp is the rarity's listed value (halved for consumables other than Spell Scrolls); the "
				+ "engine-applied mechanics (bonus_by_rarity, modifiers, consumable.heal) are the builder's overlay.");
		doc.putObject("units").put("cost", "cost_cp (canonical copper: 1 GP = 100 CP)").put("weight",
				"weight_lb (unknown for most magic items; 0 unless the text says otherwise)");
		ArrayNode arr = doc.putArray("entries");
		nodes.forEach(arr::add);
		Files.writeString(out, mapper.writer(pythonStyle()).writeValueAsString(doc) + "\n", StandardCharsets.UTF_8);
		System.out.println("wrote " + nodes.size() + " magic items to " + out);
		byRarity.forEach((k, v) -> System.out.printf("  %-10s %3d%n", k, v));
	}

	/** Whether the next line still belongs to a type line that wrapped. */
	private static boolean continues(String header, String next) {
		if (next.isEmpty()) {
			return false;
		}
		long open = header.chars().filter(c -> c == '(').count();
		long close = header.chars().filter(c -> c == ')').count();
		return open > close || header.endsWith(",") || SrdText.match(CONTINUATION, next) != null;
	}

	private static String previousNonEmpty(List<String> lines, int i) {
		for (int j = i - 1; j >= 0; j--) {
			String s = lines.get(j).strip();
			if (!s.isEmpty()) {
				return s;
			}
		}
		return "";
	}

	/** An item name: a short title-cased line that is not a sentence. */
	private static boolean isName(String s) {
		return s.length() >= 3 && s.length() <= 60 && Character.isUpperCase(s.charAt(0)) && !s.endsWith(".")
				&& !s.endsWith(",") && !s.endsWith(":") && !isTypeLine(s);
	}

	/**
	 * A type line names a rarity; "Weapon, +1, +2, or +3" is an item name, "Weapon (Any), Uncommon"
	 * is not.
	 */
	private static boolean isTypeLine(String s) {
		Matcher m = SrdText.match(HEAD, s);
		return m != null && SrdText.search(RARITY, m.group(3)) != null;
	}

	/** The lines between two headers, minus the next entry's name line, joined into one string. */
	private static String body(List<String> lines, int from, int toExclusive) {
		List<String> slice = new ArrayList<>(SrdText.slice(lines, from, toExclusive));
		// The last non-empty line of the span is the next entry's name.
		for (int j = slice.size() - 1; j >= 0; j--) {
			if (!slice.get(j).strip().isEmpty()) {
				slice.remove(j);
				break;
			}
		}
		return String.join(" ", slice).replaceAll("[ \t]+", " ").strip();
	}

	private static String slotFor(String type, String name) {
		switch (type) {
		case "RING":
			return "RING";
		case "ROD", "STAFF", "WAND":
			return "ONE_HAND";
		case "WONDROUS":
			String lower = name.toLowerCase();
			for (Map.Entry<Pattern, String> e : SLOTS) {
				if (SrdText.search(e.getKey(), lower) != null) {
					return e.getValue();
				}
			}
			return null;
		default:
			return null;
		}
	}

	private static String slug(String name) {
		return Py.strip(name.toLowerCase().replace("+", "plus-").replaceAll("[^a-z0-9]+", "-"), '-');
	}

	private static DefaultPrettyPrinter pythonStyle() {
		DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
		DefaultIndenter indent = new DefaultIndenter("  ", "\n");
		pp.indentObjectsWith(indent);
		pp.indentArraysWith(indent);
		return pp
				.withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER)
						.withObjectEmptySeparator("").withArrayEmptySeparator(""));
	}
}
