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

import se.hirt.mcp.rpg.character.Origins;
import se.hirt.mcp.rpg.content.ContentService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Level-appropriate treasure guidance (RULES_ENGINE.md §10). The SRD 5.2.1 has no treasure tables
 * (those belong to the Dungeon Master's Guide), so the engine's are its own and are suggestions: a
 * completed quest, a major encounter and a bootstrap that finds a party without a single magic item
 * all report what the party's level makes plausible and draw a few candidates through the roller.
 * Nothing is granted; {@code grant_loot} is.
 * <p>
 * The tiers follow the SRD's level bands. A beginning character very rarely finds a permanent item
 * and a Ring of Protection is a stroke of luck; a mid-level party finds one per quest or so; a
 * high-level party finds the exotic.
 */
public final class Treasure {

	/**
	 * One level band: which rarities a hoard draws from, with weights out of 100, and how often.
	 */
	public record Tier(int from, int to, Map<String, Integer> weights, String frequency) {

		public String name() {
			return "levels " + from + "-" + to;
		}
	}

	public static final List<Tier> TIERS = List.of(
			new Tier(1, 4, ordered("COMMON", 45, "UNCOMMON", 45, "RARE", 10),
					"a permanent magic item every second or third completed quest, a consumable in most hoards; "
							+ "a Rare item (a Ring of Protection) at this level is a stroke of luck"),
			new Tier(5, 10, ordered("UNCOMMON", 40, "RARE", 45, "VERY_RARE", 15),
					"about one permanent magic item per completed quest or major encounter, plus consumables"),
			new Tier(11, 16, ordered("RARE", 35, "VERY_RARE", 50, "LEGENDARY", 15),
					"one or two permanent magic items per completed quest; Rare and Very Rare are the norm"),
			new Tier(17, 20, ordered("VERY_RARE", 40, "LEGENDARY", 55, "ARTIFACT", 5),
					"legendary and exotic items are expected, several per story arc"));

	private Treasure() {
	}

	private static Map<String, Integer> ordered(Object ... kv) {
		var m = new LinkedHashMap<String, Integer>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], (Integer) kv[i + 1]);
		}
		return m;
	}

	public static Tier tier(int level) {
		for (Tier t : TIERS) {
			if (level <= t.to()) {
				return t;
			}
		}
		return TIERS.get(TIERS.size() - 1);
	}

	/**
	 * The highest character level in the current party (active members and guests); 1 for a party
	 * of stat blocks.
	 */
	public static int partyLevel(Tx tx, long campaignId) {
		int max = 1;
		for (Row c : partyRows(tx, campaignId)) {
			max = Math.max(max, Origins.characterLevel(tx, c));
		}
		return max;
	}

	private static List<Row> partyRows(Tx tx, long campaignId) {
		return tx.query(
				"SELECT c.* FROM party_membership m JOIN character c ON c.id = m.character_id WHERE m.campaign_id = ? "
						+ "AND m.state IN ('ACTIVE','SEPARATED','GUEST') AND c.lifecycle = 'ACTIVE' ORDER BY m.id",
				campaignId);
	}

	/**
	 * Magic items the party carries (every entry with a magic block, templates aside), by owner.
	 */
	public static List<Map<String, Object>> partyMagicItems(Tx tx, RulesData rules, long campaignId) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row c : partyRows(tx, campaignId)) {
			for (Row e : tx.query("SELECT * FROM inventory_entry WHERE character_id = ? ORDER BY id", c.id())) {
				ContentService.Item item = ContentService.itemForEntry(tx, rules, e);
				if (MagicItems.isMagic(item.payload())) {
					var m = new LinkedHashMap<String, Object>();
					m.put("owner", c.str("name"));
					m.put("name", item.name());
					m.put("rarity", MagicItems.rarity(item.payload()));
					m.put("quantity", e.lng("quantity"));
					out.add(m);
				}
			}
		}
		return out;
	}

	/**
	 * The treasure notice attached to a completed quest, a major encounter or a rest at a
	 * milestone: the party's level band, the rarities it draws from, how often something should
	 * turn up, and a few candidates drawn through the roller. Suggestions only.
	 */
	public static Map<String, Object> nudge(
		Tx tx, RulesData rules, RollService roller, long campaignId, String occasion, int suggestions) {
		int level = partyLevel(tx, campaignId);
		Tier tier = tier(level);
		var m = new LinkedHashMap<String, Object>();
		m.put("occasion", occasion);
		m.put("party_level", level);
		m.put("tier", tier.name());
		m.put("rarities", tier.weights());
		m.put("frequency", tier.frequency());
		m.put("party_magic_items", partyMagicItems(tx, rules, campaignId).size());
		m.put("suggestions", suggest(tx, rules, roller, campaignId, suggestions));
		m.put("note", "Suggestions only, from the party's level: grant with grant_loot (source QUEST or ENCOUNTER), "
				+ "or pick another of that rarity with get_content_definitions {magic: true, rarity}. A weapon, armor, "
				+ "shield or ammunition entry is a template: give a base ({item, base}) or the +N form (\"+1 Longsword\"). "
				+ "Ignore it when the fiction has nothing to give.");
		return m;
	}

	/**
	 * Draws {@code count} distinct installed magic items: a rarity by weight (1d100), then an item
	 * of it (1dN).
	 */
	public static List<Map<String, Object>> suggest(
		Tx tx, RulesData rules, RollService roller, long campaignId, int count) {
		var out = new ArrayList<Map<String, Object>>();
		if (rules == null || roller == null || count <= 0) {
			return out;
		}
		Tier tier = tier(partyLevel(tx, campaignId));
		var taken = new ArrayList<String>();
		for (int i = 0; i < count; i++) {
			String rarity = drawRarity(roller, tier);
			var pool = new ArrayList<RulesData.Definition>();
			for (RulesData.Definition d : rules.ofKind("ITEM")) {
				if (rarity.equals(MagicItems.rarity(d.payload())) && !taken.contains(d.id())) {
					pool.add(d);
				}
			}
			if (pool.isEmpty()) {
				continue;
			}
			Roll pick = roller.roll("1d" + pool.size());
			RulesData.Definition d = pool.get(Math.min(pool.size(), Math.max(1, pick.total())) - 1);
			taken.add(d.id());
			var s = new LinkedHashMap<String, Object>();
			s.put("ref", d.id());
			s.put("name", d.name());
			s.put("type", d.payload().get("type"));
			s.put("rarity", rarity);
			s.put("attunement", MagicItems.requiresAttunement(d.payload()));
			if (MagicItems.isTemplate(d.payload())) {
				s.put("needs_base", MagicItems.magic(d.payload()).get("applies_to"));
			}
			s.put("summary", d.payload().get("summary"));
			out.add(s);
		}
		return out;
	}

	private static String drawRarity(RollService roller, Tier tier) {
		int roll = roller.roll("1d100").total();
		int acc = 0;
		String last = null;
		for (Map.Entry<String, Integer> e : tier.weights().entrySet()) {
			acc += e.getValue();
			last = e.getKey();
			if (roll <= acc) {
				return e.getKey();
			}
		}
		return last;
	}

	/**
	 * A bootstrap warning when a party that has earned treasure owns none: from level 3, or after
	 * two completed quests, a party without a single magic item has been overlooked.
	 */
	public static String bootstrapWarning(Tx tx, RulesData rules, long campaignId) {
		int level = partyLevel(tx, campaignId);
		long quests = tx.count("SELECT COUNT(*) FROM quest WHERE campaign_id = ? AND status = 'COMPLETED'", campaignId);
		if (level < 3 && quests < 2) {
			return null;
		}
		if (!partyMagicItems(tx, rules, campaignId).isEmpty()) {
			return null;
		}
		Tier tier = tier(level);
		return "The party owns no magic items at level " + level + " after " + quests + " completed quest"
				+ (quests == 1 ? "" : "s") + ". At " + tier.name() + " the usual rate is " + tier.frequency()
				+ ". Every completed quest and major encounter now returns `treasure` with level-appropriate "
				+ "suggestions; grant them with grant_loot.";
	}
}
