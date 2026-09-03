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
package se.hirt.mcp.rpg.progression;

import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where experience lands. The campaign's {@code rules.xp_policy} decides whether an award is divided among the party,
 * carried by the player characters with companions kept level, or reserved for player characters alone
 * (RULES_ENGINE.md §6). Every XP-granting operation routes through here so the policy cannot be forgotten in one place
 * and honoured in another.
 */
public final class PartyXp {

	public static final String LOCKSTEP = "LOCKSTEP";
	public static final String SHARED = "SHARED";
	public static final String PLAYER_ONLY = "PLAYER_ONLY";

	private PartyXp() {
	}

	/** One campaign house rule from {@code preferences_json.rules}, upper-cased, or the default when unset. */
	public static String rule(Row campaign, String key, String fallback) {
		Map<String, Object> prefs = campaign.isNull("preferences_json") ? Map.of() : campaign.map("preferences_json");
		if (prefs.get("rules") instanceof Map<?, ?> rc && rc.get(key) != null) {
			return String.valueOf(rc.get(key)).toUpperCase();
		}
		return fallback;
	}

	public static String policy(Row campaign) {
		return rule(campaign, "xp_policy", SHARED);
	}

	public static String companionLevelUp(Row campaign) {
		return rule(campaign, "companion_level_up", "PLAYER");
	}

	public static boolean playerControlled(Tx tx, long campaignId, long characterId) {
		return tx.count(
				"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id = ? AND character_id = ? AND active = 1",
				campaignId, characterId) > 0;
	}

	/** Every living, active member of the party, in join order. */
	public static List<Row> activeParty(Tx tx, long campaignId) {
		var out = new ArrayList<Row>();
		for (Row m : tx.query("SELECT * FROM party_membership WHERE campaign_id = ? AND state = 'ACTIVE' ORDER BY id",
				campaignId)) {
			Row c = tx.get("character", m.lng("character_id"));
			if ("ACTIVE".equals(c.str("lifecycle")) && !"DEAD".equals(c.str("life_state"))) {
				out.add(c);
			}
		}
		return out;
	}

	/**
	 * Who an award reaches under the campaign's xp_policy. {@code extra} are characters that earned it without being
	 * party members (allies who fought alongside the party); they are folded in but never duplicated.
	 */
	public static List<Row> recipients(Tx tx, Row campaign, List<Row> extra) {
		long campaignId = campaign.id();
		String policy = policy(campaign);
		var byId = new LinkedHashMap<Long, Row>();
		for (Row c : activeParty(tx, campaignId)) {
			byId.put(c.id(), c);
		}
		if (extra != null) {
			for (Row c : extra) {
				if ("ACTIVE".equals(c.str("lifecycle")) && !"DEAD".equals(c.str("life_state"))) {
					byId.putIfAbsent(c.id(), c);
				}
			}
		}
		var all = new ArrayList<>(byId.values());
		if (SHARED.equals(policy)) {
			return all;
		}
		var players = all.stream().filter(c -> playerControlled(tx, campaignId, c.id())).toList();
		// A campaign with no assigned player character (setup, tests) falls back to everyone rather than nobody.
		return players.isEmpty() ? all : new ArrayList<>(players);
	}

	/** The highest experience total among the party's player characters. */
	public static long playerXp(Tx tx, long campaignId) {
		long xp = 0;
		for (Row c : activeParty(tx, campaignId)) {
			if (playerControlled(tx, campaignId, c.id())) {
				xp = Math.max(xp, c.lng("xp"));
			}
		}
		return xp;
	}

	/**
	 * Under LOCKSTEP, pulls every companion up to the leading player character's total. Returns one grant record per
	 * companion that moved, so the caller can report it.
	 */
	public static List<Map<String, Object>> lockstep(Tx tx, RulesData rules, Row campaign) {
		var changed = new ArrayList<Map<String, Object>>();
		if (!LOCKSTEP.equals(policy(campaign))) {
			return changed;
		}
		long campaignId = campaign.id();
		long target = playerXp(tx, campaignId);
		if (target <= 0) {
			return changed;
		}
		for (Row c : activeParty(tx, campaignId)) {
			if (playerControlled(tx, campaignId, c.id()) || c.lng("xp") >= target) {
				continue;
			}
			changed.add(RuntimeService.grantXp(tx, rules, c, target - c.lng("xp")));
		}
		return changed;
	}

	/**
	 * The experience a newcomer joins with: the leading player character's total, under every policy. A companion
	 * recruited in act three is someone who has been living in the same world, not a level 1 liability — what the
	 * xp_policy governs is what they earn from here on, not what they arrive with.
	 */
	public static long joiningXp(Tx tx, Row campaign) {
		return playerXp(tx, campaign.id());
	}
}
