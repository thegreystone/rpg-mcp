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

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.content.ContentService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.economy.Scheduler;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Effects;
import se.hirt.mcp.rpg.session.GameTime;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Optional, once-per-battle searches for ammunition whose expenditure the engine recorded. */
public final class AmmunitionRecovery {

	private static final String PLUS_AMMUNITION = "srd5e:item/ammunition-plus-1-plus-2-or-plus-3";
	private static final String RECOVERED = "AMMUNITION_RECOVERED";
	private static final Set<String> POLICIES = Set.of("ORIGINAL", "MUNDANE_ON_HIT", "GM");

	private AmmunitionRecovery() {
	}

	/** Captures physical item identity independently of this attack's active magic modifiers. */
	public static Map<String, Object> snapshot(ContentService.Item item) {
		var result = new LinkedHashMap<String, Object>();
		result.put("item", item.display());
		Map<String, Object> magic = MagicItems.magic(item.payload());
		if (magic == null) {
			result.put("policy", "ORIGINAL");
		} else {
			if (magic.get("base_ref") instanceof String base) {
				result.put("base_item", base);
			}
			boolean standard = PLUS_AMMUNITION.equals(magic.get("template_ref")) && result.containsKey("base_item");
			result.put("policy", standard ? "MUNDANE_ON_HIT" : "GM");
			if (!standard && item.payload().get("text") instanceof String text) {
				result.put("text", text);
			}
		}
		return result;
	}

	/** Read-only offers; ending combat never searches or returns items on the player's behalf. */
	public static List<Map<String, Object>> offers(Tx tx, RulesData rules, Row encounter) {
		Row live = tx.get("encounter", encounter.id());
		if (!battleAvailable(tx, live)) {
			return List.of();
		}
		var result = new ArrayList<Map<String, Object>>();
		for (Row participant : tx.query("SELECT * FROM encounter_participant WHERE encounter_id = ? ORDER BY id",
				live.id())) {
			Row character = tx.get("character", participant.lng("character_id"));
			if (!shooterAvailable(tx, live, character)) {
				continue;
			}
			List<Candidate> candidates = candidates(tx, live, character.id());
			long used = candidates.stream().mapToLong(c -> c.quantity).sum();
			if (used < 2) {
				continue;
			}
			var offer = new LinkedHashMap<String, Object>();
			offer.put("character", Ref.of(Ref.CHARACTER, character.id()));
			offer.put("name", character.str("name"));
			offer.put("used", used);
			offer.put("recoverable", used / 2);
			offer.put("elapsed_minutes", 1);
			offer.put("optional", true);
			offer.put("recover_with", "recover_ammunition");
			offer.put("prompt",
					"Ask the player whether to spend one minute searching for ammunition, if the battlefield is accessible.");
			offer.put("selection_required", candidates.stream().anyMatch(c -> "GM".equals(c.recoverAs)));
			offer.put("default_method", "RANDOM");
			offer.put("candidates", candidates.stream().map(Candidate::view).toList());
			result.add(offer);
		}
		return result;
	}

	/** Validates the player's choice and performs one journaled, one-minute search. */
	public static Map<String, Object> recover(
		Tx tx, RulesData rules, RollService roller, Row campaign, Row encounter, Row character,
		List<Map<String, Object>> selection) {
		Row live = tx.get("encounter", encounter.id());
		Row shooter = tx.get("character", character.id());
		if (live.lng("campaign_id") != campaign.id() || shooter.lng("campaign_id") != campaign.id()
				|| !battleAvailable(tx, live) || !shooterAvailable(tx, live, shooter)) {
			throw RpgException.notAllowed(
					"Ammunition recovery requires an eligible party member at the ended battle's location, with no encounter running and no previous search.");
		}
		List<Candidate> candidates = candidates(tx, live, shooter.id());
		long used = candidates.stream().mapToLong(c -> c.quantity).sum();
		long quota = used / 2;
		if (quota == 0) {
			throw RpgException.notAllowed("There is no recoverable tracked ammunition from this battle.");
		}
		List<Map<String, Object>> chosen = selection == null
				? automaticSelection(tx, rules, roller, campaign.id(), candidates, quota) : selection;
		var byGroup = new LinkedHashMap<Long, Candidate>();
		candidates.forEach(c -> byGroup.put(c.group, c));
		var seen = new HashSet<Long>();
		var normalized = new ArrayList<Map<String, Object>>();
		var items = new LinkedHashMap<String, ContentService.Item>();
		var quantities = new LinkedHashMap<String, Long>();
		long selected = 0;
		long lost = 0;
		for (Map<String, Object> choice : chosen) {
			if (choice == null) {
				throw RpgException.invalidArgument("Each ammunition selection must name a group and quantity.");
			}
			long group = positiveInteger(choice.get("group"), "selection.group");
			long quantity = positiveInteger(choice.get("quantity"), "selection.quantity");
			Candidate candidate = byGroup.get(group);
			if (candidate == null) {
				throw RpgException.invalidArgument("Ammunition group " + group + " is not available to this shooter.");
			}
			if (!seen.add(group)) {
				throw RpgException.invalidArgument("Ammunition group " + group + " is selected more than once.");
			}
			if (quantity > candidate.quantity || quantity > quota - selected) {
				throw RpgException.invalidArgument(
						"The selection exceeds its ammunition group or the battle's recovery allowance of " + quota
								+ ".");
			}
			String recoverAs = recoveryChoice(candidate, choice.get("recover_as"));
			selected += quantity;
			normalized.add(Map.of("group", group, "quantity", quantity, "recover_as", recoverAs));
			if ("LOST".equals(recoverAs)) {
				lost += quantity;
				continue;
			}
			ContentService.Item item = recoveryItem(tx, rules, campaign.id(), candidate, recoverAs);
			String itemRef = item.display();
			items.put(itemRef, item);
			quantities.merge(itemRef, quantity, Long::sum);
		}
		if (selected != quota) {
			throw RpgException.invalidArgument(
					"Select exactly " + quota + " pieces of ammunition, half the " + used + " used (rounded down).");
		}

		var recovered = new ArrayList<Map<String, Object>>();
		for (var quantity : quantities.entrySet()) {
			ContentService.Item item = items.get(quantity.getKey());
			long entry = InventoryService.addItem(tx, campaign.id(), shooter.id(), null, item, quantity.getValue());
			recovered.add(Map.of("entry", Ref.of(Ref.INVENTORY, entry), "item", item.display(), "name", item.name(),
					"quantity", quantity.getValue()));
		}
		Row clock = GameTime.clock(tx, campaign.id());
		long from = clock.lng("seq");
		long to = from + 1;
		tx.update("game_clock", clock.id(), Map.of("seq", to, "instant", GameTime.render(to)));
		int expired = Effects.expireByTime(tx, campaign.id(), to);
		var consequences = Scheduler.onClockAdvance(tx, campaign.id(), from, to);
		Row fresh = tx.get("character", shooter.id());
		long revision = fresh.lng("revision") + 1;
		tx.update("character", fresh.id(), Map.of("revision", revision));
		tx.touched(Ref.of(Ref.CHARACTER, fresh.id()), revision);

		var result = new LinkedHashMap<String, Object>();
		result.put("encounter", Ref.of(Ref.ENCOUNTER, live.id()));
		result.put("character", Ref.of(Ref.CHARACTER, shooter.id()));
		result.put("used", used);
		result.put("recoverable", quota);
		result.put("method", selection == null ? "RANDOM" : "EXPLICIT");
		result.put("selection", normalized);
		result.put("recovered", recovered);
		result.put("lost", lost);
		result.put("elapsed_minutes", 1);
		result.put("from", GameTime.toMap(tx, campaign.id(), from));
		result.put("to", GameTime.toMap(tx, campaign.id(), to));
		result.put("expired_effects", expired);
		result.put("consequences", consequences);
		String summary = shooter.str("name") + " searches the battlefield for one minute and recovers "
				+ quantities.values().stream().mapToLong(Long::longValue).sum() + " pieces of ammunition.";
		long event = LedgerService.append(tx, campaign.id(),
				new LedgerService.EventSpec(RECOVERED, summary, List.of(shooter.id()), "MINOR", "PARTY_KNOWN",
						"MECHANICAL_CONSEQUENCE", null, live.lng("location_id"), null, result));
		result.put("event", Ref.of(Ref.EVENT, event));
		var marker = new LinkedHashMap<String, Object>();
		marker.put("campaign_id", campaign.id());
		marker.put("encounter_id", live.id());
		marker.put("round", live.lng("round"));
		marker.put("actor_character_id", shooter.id());
		marker.put("kind", RECOVERED);
		marker.put("summary", summary);
		marker.put("payload_json", Json.write(result));
		marker.put("journal_id", tx.journalId());
		tx.insert("encounter_log", marker);
		result.put("meta", Harness.meta(tx.get("campaign", campaign.id()), null));
		return result;
	}

	private static boolean battleAvailable(Tx tx, Row encounter) {
		if (!"ENDED".equals(encounter.str("status")) || encounter.lng("location_id") == null
				|| !"EXPLORATION".equals(tx.get("campaign", encounter.lng("campaign_id")).str("harness_state"))
				|| tx.count(
						"SELECT COUNT(*) FROM encounter WHERE campaign_id = ? AND status IN ('CREATED','RUNNING','WAITING_CHOICE')",
						encounter.lng("campaign_id")) > 0) {
			return false;
		}
		return true;
	}

	private static boolean shooterAvailable(Tx tx, Row encounter, Row character) {
		if (!"ACTIVE".equals(character.str("lifecycle")) || !"ALIVE".equals(character.str("life_state"))
				|| !Objects.equals(encounter.lng("location_id"), character.lng("location_id"))) {
			return false;
		}
		String partySide = String.valueOf(encounter.map("sides_json").get("party_side"));
		if (tx.count(
				"SELECT COUNT(*) FROM encounter_participant WHERE encounter_id = ? AND character_id = ? AND side = ?",
				encounter.id(), character.id(), partySide) == 0
				|| tx.count(
						"SELECT COUNT(*) FROM encounter_log WHERE encounter_id = ? AND actor_character_id = ? AND kind = ?",
						encounter.id(), character.id(), RECOVERED) > 0) {
			return false;
		}
		return tx.count(
				"SELECT COUNT(*) FROM active_effect WHERE character_id = ? AND condition_ref IN ('srd5e:condition/incapacitated','srd5e:condition/stunned','srd5e:condition/paralyzed','srd5e:condition/unconscious','srd5e:condition/petrified')",
				character.id()) == 0;
	}

	private record Group(String item, boolean hit, String policy) {
	}

	private static final class Candidate {
		final long group;
		final String item;
		final String name;
		final String baseItem;
		final String text;
		final boolean hit;
		final String recoverAs;
		long quantity;

		Candidate(long group, String item, String name, String baseItem, String text, boolean hit, String policy) {
			this.group = group;
			this.item = item;
			this.name = name;
			this.baseItem = baseItem;
			this.text = text;
			this.hit = hit;
			this.recoverAs = "MUNDANE_ON_HIT".equals(policy) ? hit ? "MUNDANE" : "ORIGINAL" : policy;
		}

		Map<String, Object> view() {
			var result = new LinkedHashMap<String, Object>();
			result.put("group", group);
			result.put("item", item);
			result.put("name", name);
			result.put("quantity", quantity);
			result.put("hit", hit);
			result.put("recover_as", recoverAs);
			if (baseItem != null) {
				result.put("base_item", baseItem);
			}
			if ("GM".equals(recoverAs)) {
				result.put("text", text == null
						? "The GM decides whether this custom ammunition survives and retains its magic." : text);
			}
			return result;
		}
	}

	private static List<Candidate> candidates(Tx tx, Row encounter, long characterId) {
		var groups = new LinkedHashMap<Group, Candidate>();
		for (Row log : tx.query(
				"SELECT * FROM encounter_log WHERE encounter_id = ? AND actor_character_id = ? AND kind IN ('ATTACK','OPPORTUNITY_ATTACK') ORDER BY id",
				encounter.id(), characterId)) {
			Map<String, Object> payload = log.map("payload_json");
			if (!(payload.get("hit") instanceof Boolean hit)
					|| !(payload.get("ammunition") instanceof Map<?, ?> ammunition)
					|| !(ammunition.get("recovery") instanceof Map<?, ?> recovery)
					|| !(recovery.get("item") instanceof String item)
					|| !(recovery.get("policy") instanceof String policy) || !POLICIES.contains(policy)) {
				continue;
			}
			Group key = new Group(item, hit, policy);
			Candidate candidate = groups.computeIfAbsent(key,
					ignored -> new Candidate(log.id(), item,
							ammunition.get("name") instanceof String name ? name : item,
							recovery.get("base_item") instanceof String base ? base : null,
							recovery.get("text") instanceof String text ? text : null, hit, policy));
			candidate.quantity++;
		}
		return new ArrayList<>(groups.values());
	}

	private static List<Map<String, Object>> automaticSelection(
		Tx tx, RulesData rules, RollService roller, long campaignId, List<Candidate> candidates, long quota) {
		if (candidates.stream().anyMatch(c -> "GM".equals(c.recoverAs))) {
			throw RpgException
					.invalidArgument("Custom ammunition needs an explicit GM recovery selection before the search.");
		}
		// Validate the entire pool before drawing or writing audit rolls.
		for (Candidate candidate : candidates) {
			recoveryItem(tx, rules, campaignId, candidate, candidate.recoverAs);
		}
		var remaining = new LinkedHashMap<Candidate, Long>();
		var selected = new LinkedHashMap<Long, Long>();
		long total = 0;
		for (Candidate candidate : candidates) {
			remaining.put(candidate, candidate.quantity);
			total += candidate.quantity;
		}
		for (long drawn = 0; drawn < quota;) {
			if (remaining.size() == 1) {
				Candidate only = remaining.keySet().iterator().next();
				selected.merge(only.group, quota - drawn, Long::sum);
				break;
			}
			long pick = randomPick(tx, roller, campaignId, total);
			Candidate chosen = null;
			for (var entry : remaining.entrySet()) {
				pick -= entry.getValue();
				if (pick <= 0) {
					chosen = entry.getKey();
					break;
				}
			}
			selected.merge(chosen.group, 1L, Long::sum);
			long left = remaining.get(chosen) - 1;
			if (left == 0) {
				remaining.remove(chosen);
			} else {
				remaining.put(chosen, left);
			}
			total--;
			drawn++;
		}
		return selected.entrySet().stream()
				.map(entry -> Map.<String, Object> of("group", entry.getKey(), "quantity", entry.getValue())).toList();
	}

	private static long randomPick(Tx tx, RollService roller, long campaignId, long bound) {
		if (bound <= 10_000) {
			Roll roll = roller.roll("1d" + bound);
			CharacterService.recordRoll(tx, campaignId, "ammunition recovery selection", roll);
			return roll.total();
		}
		// The dice parser caps sides at 10,000. Compose unbiased larger draws from those dice.
		BigInteger base = BigInteger.valueOf(10_000);
		BigInteger limit = BigInteger.valueOf(bound);
		BigInteger range = BigInteger.ONE;
		int digits = 0;
		while (range.compareTo(limit) < 0) {
			range = range.multiply(base);
			digits++;
		}
		BigInteger accepted = range.subtract(range.remainder(limit));
		for (;;) {
			BigInteger value = BigInteger.ZERO;
			for (int digit = 0; digit < digits; digit++) {
				Roll roll = roller.roll("1d10000");
				CharacterService.recordRoll(tx, campaignId, "ammunition recovery selection digit", roll);
				value = value.multiply(base).add(BigInteger.valueOf(roll.total() - 1));
			}
			if (value.compareTo(accepted) < 0) {
				return value.remainder(limit).longValueExact() + 1;
			}
		}
	}

	private static ContentService.Item recoveryItem(
		Tx tx, RulesData rules, long campaignId, Candidate candidate, String recoverAs) {
		String itemRef = "MUNDANE".equals(recoverAs) ? candidate.baseItem : candidate.item;
		if (itemRef == null) {
			throw RpgException
					.invalidArgument("Ammunition group " + candidate.group + " has no recorded mundane base item.");
		}
		ContentService.Item item = ContentService.resolveItem(tx, rules, campaignId, itemRef);
		if (!"AMMUNITION".equals(item.type()) || "MUNDANE".equals(recoverAs) && MagicItems.isMagic(item.payload())) {
			throw RpgException.invalidArgument(
					"The recorded recovery item for group " + candidate.group + " is not suitable ammunition.");
		}
		return item;
	}

	private static String recoveryChoice(Candidate candidate, Object requested) {
		String choice = requested instanceof String text ? text.toUpperCase(Locale.ROOT) : null;
		if ("GM".equals(candidate.recoverAs)) {
			if (choice == null || !Set.of("ORIGINAL", "MUNDANE", "LOST").contains(choice)) {
				throw RpgException.invalidArgument("Custom ammunition group " + candidate.group
						+ " needs recover_as ORIGINAL, MUNDANE or LOST, as adjudicated by the GM.");
			}
			return choice;
		}
		if (requested != null && !candidate.recoverAs.equals(choice)) {
			throw RpgException.invalidArgument(
					"Ammunition group " + candidate.group + " must be recovered as " + candidate.recoverAs + ".");
		}
		return candidate.recoverAs;
	}

	private static long positiveInteger(Object value, String field) {
		if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.longValue() <= 0
				|| number.doubleValue() != number.longValue()) {
			throw RpgException.invalidArgument(field + " must be a positive integer.");
		}
		return number.longValue();
	}
}
