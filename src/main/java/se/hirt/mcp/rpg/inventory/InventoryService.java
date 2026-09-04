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
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.rules.Derived;
import se.hirt.mcp.rpg.rules.Money;

import java.time.Instant;
import java.util.*;

/**
 * Inventory and economy (DESIGN.md §12, DOMAIN_MODEL.md §8, MCP_PROTOCOL.md §14). The GM narrates commerce; the engine
 * performs the accounting: quantities are positive (I-23), every transfer and trade is atomic (I-25), equipment is
 * slot-validated (I-26), carry weight is derived (I-27).
 */
public final class InventoryService {

	public static final Set<String> LOOT_SOURCES = Set.of("ENCOUNTER", "QUEST", "WORLD", "GM_GRANT");
	private static final Set<String> EQUIPPABLE = Set.of("WEAPON", "ARMOR", "SHIELD", "FOCUS");

	private final Database db;
	private final RulesData rules;

	public InventoryService(Database db, RulesData rules) {
		this.db = db;
		this.rules = rules;
	}

	// ── read helpers used by sheets and context ────────────────────────

	public static List<Map<String, Object>> entries(Tx tx, RulesData rules, long characterId) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row e : tx.query("SELECT * FROM inventory_entry WHERE character_id = ? ORDER BY equipped DESC, id",
				characterId)) {
			out.add(entrySummary(tx, rules, e));
		}
		return out;
	}

	public static Map<String, Object> entrySummary(Tx tx, RulesData rules, Row e) {
		Item item = ContentService.itemForEntry(tx, rules, e);
		long qty = e.lng("quantity");
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of(Ref.INVENTORY, e.id()));
		m.put("item", item.display());
		m.put("name", item.name());
		m.put("type", item.type());
		m.put("quantity", qty);
		if (e.bool("equipped")) {
			m.put("equipped", true);
			m.put("slot", e.str("slot"));
		}
		m.put("weight_lb", round1(item.unitWeightLb() * qty));
		if (!e.isNull("charges_json")) {
			m.put("charges", e.map("charges_json"));
		}
		return m;
	}

	public static List<Map<String, Object>> equippedPayloads(Tx tx, RulesData rules, long characterId) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row e : tx.query("SELECT * FROM inventory_entry WHERE character_id = ? AND equipped = 1 ORDER BY id",
				characterId)) {
			Item item = ContentService.itemForEntry(tx, rules, e);
			var p = new LinkedHashMap<>(item.payload());
			p.put("name", item.name());
			out.add(p);
		}
		return out;
	}

	public static Map<String, Object> armorClass(Tx tx, RulesData rules, Row character) {
		se.hirt.mcp.rpg.rules.Effects.Modifiers mods = se.hirt.mcp.rpg.rules.Effects.modifiers(tx, character.id());
		if (!character.isNull("armor_class_override")) {
			// apply_gm_override SET_ARMOR_CLASS (MCP_PROTOCOL.md §20.1): a fixed base instead of the equipment.
			int base = character.integer("armor_class_override");
			int value = base + mods.acBonus;
			String basis = "GM override (" + base + ")";
			if (mods.acFloor != null && value < mods.acFloor) {
				value = mods.acFloor;
				basis = basis + ", raised to a floor of " + mods.acFloor;
			}
			var m = new LinkedHashMap<String, Object>();
			m.put("value", value);
			m.put("basis", basis);
			if (mods.acBonus != 0) {
				m.put("bonus", mods.acBonus);
			}
			return m;
		}
		return Derived.armorClass(character.integer("dex_score"), equippedPayloads(tx, rules, character.id()),
				mods.acBase, mods.acBonus, mods.acFloor);
	}

	public static Map<String, Object> carrying(Tx tx, RulesData rules, Row character) {
		double items = 0;
		for (Row e : tx.query("SELECT * FROM inventory_entry WHERE character_id = ?", character.id())) {
			items += ContentService.itemForEntry(tx, rules, e).unitWeightLb() * e.lng("quantity");
		}
		double coins = Money.coinWeightLb(character.lng("money_cp") == null ? 0 : character.lng("money_cp"));
		// Goliath Powerful Build counts as one size larger for carrying capacity (SRD 5.2.1).
		int capacity = Derived.carryCapacityLb(
				character.integer("str_score")) * se.hirt.mcp.rpg.character.Origins.carryCapacityMultiplier(rules,
				character);
		var m = new LinkedHashMap<String, Object>();
		m.put("items_lb", round1(items));
		m.put("coins_lb", round1(coins));
		m.put("total_lb", round1(items + coins));
		m.put("capacity_lb", capacity);
		m.put("over_capacity", items + coins > capacity);
		return m;
	}

	private static double round1(double v) {
		return Math.round(v * 10.0) / 10.0;
	}

	// ── write helpers ──────────────────────────────────────────────────

	/** Adds quantity of an item to a character or location, merging into an existing unequipped stack. */
	public static long addItem(Tx tx, long campaignId, Long characterId, Long locationId, Item item, long quantity) {
		if (quantity <= 0) {
			throw RpgException.invalidArgument("Quantity must be positive.");
		}
		String owner = characterId != null ? "character_id" : "location_id";
		long ownerId = characterId != null ? characterId : locationId;
		Optional<Row> existing = item.custom() ? tx.queryOne(
				"SELECT * FROM inventory_entry WHERE " + owner + " = ? AND custom_content_id = ? AND equipped = 0 AND charges_json IS NULL",
				ownerId, item.customId()) : tx.queryOne(
				"SELECT * FROM inventory_entry WHERE " + owner + " = ? AND content_ref = ? AND equipped = 0 AND charges_json IS NULL",
				ownerId, item.contentRef());
		if (existing.isPresent()) {
			tx.update("inventory_entry", existing.get().id(),
					Map.of("quantity", existing.get().lng("quantity") + quantity));
			return existing.get().id();
		}
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("character_id", characterId);
		cols.put("location_id", locationId);
		cols.put("content_ref_kind", item.custom() ? "CUSTOM" : "INSTALLED");
		cols.put("content_ref", item.custom() ? null : item.contentRef());
		cols.put("custom_content_id", item.custom() ? item.customId() : null);
		cols.put("quantity", quantity);
		cols.put("equipped", 0);
		if (item.payload().get("charges") instanceof Number charges) {
			cols.put("charges_json", Json.write(Map.of("current", charges.intValue(), "max", charges.intValue())));
		}
		return tx.insert("inventory_entry", cols);
	}

	/** Removes quantity from a stack; zero-quantity entries are deleted (I-23). */
	public static void removeQuantity(Tx tx, Row entry, long quantity) {
		long have = entry.lng("quantity");
		if (quantity <= 0 || quantity > have) {
			throw RpgException.invalidArgument("Cannot remove " + quantity + " from a stack of " + have + ".");
		}
		if (quantity == have) {
			tx.delete("inventory_entry", entry.id());
		} else {
			tx.update("inventory_entry", entry.id(), Map.of("quantity", have - quantity));
		}
	}

	/** Grants a list of {@code {item, quantity}} (or {@code {choice, default}}) entries; packs are expanded. */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> grantBundle(
			Tx tx, RulesData rules, long campaignId, long characterId,
			List<Map<String, Object>> items, Map<String, Object> choices) {
		var granted = new ArrayList<Map<String, Object>>();
		for (Map<String, Object> spec : items) {
			String ref = (String) spec.get("item");
			if (spec.get("choice") != null) {
				Object chosen = choices == null ? null : choices.get(String.valueOf(spec.get("choice")));
				ref = chosen == null ? (String) spec.get("default") : chosen.toString();
			}
			long qty = spec.get("quantity") instanceof Number n ? n.longValue() : 1;
			Item item = ContentService.resolveItem(tx, rules, campaignId, ref);
			if (item.type().equals("PACK")) {
				List<Map<String, Object>> contents = (List<Map<String, Object>>) item.payload()
						.getOrDefault("contents", List.of());
				for (int i = 0; i < qty; i++) {
					granted.addAll(grantBundle(tx, rules, campaignId, characterId, contents, choices));
				}
				continue;
			}
			addItem(tx, campaignId, characterId, null, item, qty);
			var g = new LinkedHashMap<String, Object>();
			g.put("item", item.display());
			g.put("name", item.name());
			g.put("quantity", qty);
			granted.add(g);
		}
		return granted;
	}

	/**
	 * Resolves and grants a class's starting equipment for a character being activated (SRD 5.2.1 class "Starting
	 * Equipment": Option A bundle + gold, or Option B gold only). Returns the money in copper.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> grantStartingEquipment(Tx tx, RulesData rules, long campaignId, Row character) {
		var result = new LinkedHashMap<String, Object>();
		Optional<Row> cls = tx.queryOne(
				"SELECT class_ref FROM character_class WHERE character_id = ? ORDER BY id LIMIT 1", character.id());
		Optional<RulesData.Definition> def = cls.flatMap(r -> rules.find(r.str("class_ref")));
		if (def.isEmpty()) {
			result.put("money_cp", 0L);
			result.put("items", List.of());
			return result;
		}
		Map<String, Object> options = (Map<String, Object>) def.get().payload().get("starting_equipment");
		Map<String, Object> creation = character.map("creation_json");
		Map<String, Object> chosen =
				creation.get("starting_equipment") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
		String optionKey = chosen == null ? goldOnlyOption(options) : String.valueOf(chosen.get("option"));
		Map<String, Object> option = (Map<String, Object>) options.get(optionKey);
		if (option == null) {
			throw RpgException.validation(List.of(new Violation("starting_equipment.option", "UNKNOWN_OPTION",
					"Starting equipment option '" + optionKey + "' does not exist for " + def.get().name() + ".")));
		}
		Map<String, Object> choices = chosen == null ? null : (Map<String, Object>) chosen.get("choices");
		List<Map<String, Object>> items = (List<Map<String, Object>>) option.getOrDefault("items", List.of());
		long gold = ((Number) option.getOrDefault("gold_gp", 0)).longValue() * Money.GP;
		result.put("option", optionKey);
		var granted = new java.util.ArrayList<Map<String, Object>>(
				grantBundle(tx, rules, campaignId, character.id(), items, choices));
		// The background provides starting equipment too (SRD 5.2.1 "Choose Starting Equipment").
		Optional<RulesData.Definition> bg = se.hirt.mcp.rpg.character.Origins.backgroundOf(tx, rules, character);
		if (bg.isPresent() && bg.get().payload().get("starting_equipment") instanceof Map<?, ?> bgOptionsRaw) {
			Map<String, Object> bgOptions = (Map<String, Object>) bgOptionsRaw;
			Map<String, Object> bgChosen =
					creation.get("background_equipment") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
			String bgKey = bgChosen == null ? goldOnlyOption(bgOptions) : String.valueOf(bgChosen.get("option"));
			Map<String, Object> bgOption = (Map<String, Object>) bgOptions.get(bgKey);
			if (bgOption == null) {
				throw RpgException.validation(List.of(new Violation("background_equipment.option", "UNKNOWN_OPTION",
						"Background equipment option '" + bgKey + "' does not exist for " + bg.get().name() + ".")));
			}
			var bgChoices = new LinkedHashMap<String, Object>();
			if (bgChosen != null && bgChosen.get("choices") instanceof Map<?, ?> cm) {
				cm.forEach((k, v) -> bgChoices.put(String.valueOf(k), v));
			}
			// A GAMING_SET bundle choice defaults to the gaming set chosen as the background tool proficiency.
			tx.query("SELECT * FROM character_trait WHERE character_id = ? AND kind = 'PROFICIENCY'", character.id())
					.stream().filter(t -> se.hirt.mcp.rpg.character.Origins.SOURCE_BACKGROUND.equals(
							se.hirt.mcp.rpg.character.Origins.sourceOf(t))).filter(t -> rules.find(t.str("content_ref"))
							.map(d -> "GAMING_SET".equals(String.valueOf(d.payload().get("tool_kind")))).orElse(false))
					.findFirst().ifPresent(t -> bgChoices.putIfAbsent("GAMING_SET", t.str("content_ref")));
			gold += ((Number) bgOption.getOrDefault("gold_gp", 0)).longValue() * Money.GP;
			granted.addAll(grantBundle(tx, rules, campaignId, character.id(),
					(List<Map<String, Object>>) bgOption.getOrDefault("items", List.of()), bgChoices));
			result.put("background_option", bgKey);
		}
		result.put("money_cp", gold);
		result.put("items", granted);
		return result;
	}

	public static String goldOnlyOption(Map<String, Object> options) {
		for (var e : options.entrySet()) {
			if (e.getValue() instanceof Map<?, ?> m && !m.containsKey("items")) {
				return e.getKey();
			}
		}
		return options.keySet().iterator().next();
	}

	// ── transfer_item ──────────────────────────────────────────────────

	public Map<String, Object> transfer(
			String operationId, String campaignRef, String entryRef, String toRef, Integer quantity, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("entry", entryRef);
		args.put("to", toRef);
		args.put("quantity", quantity);
		return db.mutate(Database.Mutation.of("transfer_item", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "transfer_item");
			Row entry = entry(tx, campaignId, entryRef);
			Ref target = Ref.parse(toRef);
			Long toCharacter = null;
			Long toLocation = null;
			if (target.type().equals(Ref.CHARACTER)) {
				Row c = activeCharacter(tx, campaignId, toRef);
				toCharacter = c.id();
			} else if (target.type().equals(Ref.LOCATION)) {
				Row l = tx.find("location", target.id()).orElseThrow(() -> RpgException.notFound("Location " + toRef));
				if (l.lng("campaign_id") != campaignId) {
					throw RpgException.invalidArgument(toRef + " belongs to another campaign.");
				}
				toLocation = l.id();
			} else {
				throw RpgException.invalidArgument("to must be a character:N or location:N reference.");
			}
			long have = entry.lng("quantity");
			long qty = quantity == null ? have : quantity;
			if (qty <= 0 || qty > have) {
				throw RpgException.insufficientResource("The stack holds " + have + "; cannot move " + qty + ".");
			}
			if (toCharacter != null && toCharacter.equals(entry.lng("character_id"))) {
				throw RpgException.invalidArgument("The item is already owned by " + toRef + ".");
			}
			Item item = ContentService.itemForEntry(tx, rules, entry);
			String from = entry.isNull("character_id") ? Ref.of(Ref.LOCATION, entry.lng("location_id"))
					: Ref.of(Ref.CHARACTER, entry.lng("character_id"));
			removeQuantity(tx, entry, qty);
			long newEntry = addItem(tx, campaignId, toCharacter, toLocation, item, qty);
			var result = new LinkedHashMap<String, Object>();
			result.put("item", item.display());
			result.put("name", item.name());
			result.put("quantity", qty);
			result.put("from", from);
			result.put("to", toRef);
			result.put("entry", Ref.of(Ref.INVENTORY, newEntry));
			result.put("remaining_in_source", have - qty);
			if (entry.bool("equipped")) {
				result.put("note", "The item was equipped and is now unequipped.");
			}
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── equip_item ─────────────────────────────────────────────────────

	public Map<String, Object> equip(
			String operationId, String campaignRef, String characterRef, String entryRef, boolean equipped) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("character", characterRef);
		args.put("entry", entryRef);
		args.put("equipped", equipped);
		return db.mutate(Database.Mutation.of("equip_item", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "equip_item");
			Row character = activeCharacter(tx, campaignId, characterRef);
			Row entry = entry(tx, campaignId, entryRef);
			if (entry.lng("character_id") == null || entry.lng("character_id") != character.id()) {
				throw RpgException.invalidArgument(entryRef + " is not carried by " + characterRef + ".");
			}
			Item item = ContentService.itemForEntry(tx, rules, entry);
			Map<String, Object> acBefore = armorClass(tx, rules, character);
			var warnings = new ArrayList<String>();
			if (equipped && !entry.bool("equipped")) {
				if (!EQUIPPABLE.contains(item.type())) {
					throw RpgException.validation(List.of(new Violation("entry", "NOT_EQUIPPABLE",
							item.name() + " (" + item.type() + ") cannot be equipped.")));
				}
				String slot = String.valueOf(
						item.payload().getOrDefault("slot", item.type().equals("FOCUS") ? "ONE_HAND" : "ONE_HAND"));
				List<Row> current = tx.query("SELECT * FROM inventory_entry WHERE character_id = ? AND equipped = 1",
						character.id());
				int hands = 0;
				for (Row c : current) {
					String s = c.str("slot");
					if ("BODY".equals(s) && "BODY".equals(slot)) {
						throw RpgException.validation(List.of(new Violation("entry", "SLOT_OCCUPIED",
								"Body armor is already equipped (" + Ref.of(Ref.INVENTORY,
										c.id()) + "); unequip it first.")));
					}
					if ("SHIELD".equals(s) && "SHIELD".equals(slot)) {
						throw RpgException.validation(
								List.of(new Violation("entry", "SLOT_OCCUPIED", "A shield is already equipped.")));
					}
					hands += handsFor(s);
				}
				if (hands + handsFor(slot) > 2) {
					throw RpgException.validation(List.of(new Violation("entry", "HANDS",
							"Not enough free hands to hold " + item.name() + ".")));
				}
				if (item.payload().get("armor") instanceof Map<?, ?> armor && armor.get(
						"strength_requirement") instanceof Number req && character.intOr("str_score",
						10) < req.intValue()) {
					warnings.add(
							item.name() + " requires Strength " + req + "; speed is reduced by 10 feet while wearing it.");
				}
				if (entry.lng("quantity") > 1) {
					// Split one unit off the stack so the equipped state is unambiguous.
					tx.update("inventory_entry", entry.id(), Map.of("quantity", entry.lng("quantity") - 1));
					var cols = new LinkedHashMap<>(entry.asMap());
					cols.remove("id");
					cols.put("quantity", 1);
					cols.put("equipped", 1);
					cols.put("slot", slot);
					long id = tx.insert("inventory_entry", cols);
					entry = tx.get("inventory_entry", id);
				} else {
					tx.update("inventory_entry", entry.id(), Map.of("equipped", 1, "slot", slot));
				}
			} else if (!equipped && entry.bool("equipped")) {
				var cols = new LinkedHashMap<String, Object>();
				cols.put("equipped", 0);
				cols.put("slot", null);
				tx.update("inventory_entry", entry.id(), cols);
			}
			Map<String, Object> acAfter = armorClass(tx, rules, character);
			var result = new LinkedHashMap<String, Object>();
			result.put("entry", Ref.of(Ref.INVENTORY, entry.id()));
			result.put("item", item.display());
			result.put("name", item.name());
			result.put("equipped", equipped);
			result.put("armor_class_before", acBefore.get("value"));
			result.put("armor_class", acAfter);
			result.put("equipped_items",
					entries(tx, rules, character.id()).stream().filter(e -> Boolean.TRUE.equals(e.get("equipped")))
							.toList());
			result.put("meta", Harness.meta(campaign, warnings));
			return result;
		});
	}

	private static int handsFor(String slot) {
		return "TWO_HANDS".equals(slot) ? 2 : "ONE_HAND".equals(slot) || "SHIELD".equals(slot) ? 1 : 0;
	}

	// ── trade ──────────────────────────────────────────────────────────

	public Map<String, Object> trade(
			String operationId, String campaignRef, String characterRef, String kind, String itemText, String entryRef,
			Integer quantity, String merchantRef, Object negotiatedPrice, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("character", characterRef);
		args.put("kind", kind);
		args.put("item", itemText);
		args.put("entry", entryRef);
		args.put("quantity", quantity);
		args.put("merchant", merchantRef);
		args.put("negotiated_price", negotiatedPrice);
		return db.mutate(Database.Mutation.of("trade", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "trade");
			Row character = activeCharacter(tx, campaignId, characterRef);
			String k = kind == null ? "" : kind.toUpperCase();
			if (!k.equals("BUY") && !k.equals("SELL")) {
				throw RpgException.invalidArgument("kind must be BUY or SELL.");
			}
			Long merchantId = null;
			if (merchantRef != null && !merchantRef.isBlank()) {
				merchantId = activeCharacter(tx, campaignId, merchantRef).id();
			}
			Long negotiated = negotiatedPrice == null ? null : Money.parseCp(negotiatedPrice);
			if (negotiated != null && (reason == null || reason.isBlank())) {
				throw RpgException.invalidArgument(
						"A negotiated price requires a reason (it is recorded with GM provenance).");
			}
			long money = character.lng("money_cp");
			var result = new LinkedHashMap<String, Object>();
			result.put("kind", k);
			result.put("character", Ref.of(Ref.CHARACTER, character.id()));
			if (k.equals("BUY")) {
				Item item = ContentService.resolveItem(tx, rules, campaignId, itemText);
				long units = quantity == null ? 1 : quantity;
				if (units <= 0) {
					throw RpgException.invalidArgument("quantity must be positive.");
				}
				long listPrice = item.costCp() * units;
				long price = negotiated != null ? negotiated : listPrice;
				if (price > money) {
					throw RpgException.insufficientResource(
							character.str("name") + " has " + Money.format(money) + " but " + item.name() + (units > 1
									? " ×" + units : "") + " costs " + Money.format(price) + ".");
				}
				long pieces = units * item.bundleSize();
				long entryId;
				if (item.type().equals("PACK")) {
					var granted = grantBundle(tx, rules, campaignId, character.id(),
							List.of(Map.of("item", item.display(), "quantity", units)), null);
					result.put("granted", granted);
					entryId = -1;
				} else {
					entryId = addItem(tx, campaignId, character.id(), null, item, pieces);
				}
				tx.update("character", character.id(),
						Map.of("money_cp", money - price, "revision", character.lng("revision") + 1));
				result.put("item", item.display());
				result.put("name", item.name());
				result.put("quantity", pieces);
				result.put("list_price", Money.format(listPrice));
				result.put("price_paid", Money.format(price));
				if (entryId > 0) {
					result.put("entry", Ref.of(Ref.INVENTORY, entryId));
				}
				result.put("money", Money.render(money - price));
				recordTrade(tx, campaignId, character, merchantId, "ITEM_PURCHASED",
						character.str("name") + " bought " + describe(item, pieces) + " for " + Money.format(
								price) + ".", item, pieces, price, negotiated != null, reason);
			} else {
				Row entry = entryRef != null && !entryRef.isBlank() ? entry(tx, campaignId, entryRef)
						: findOwnedItem(tx, campaignId, character.id(), itemText);
				if (entry.lng("character_id") == null || entry.lng("character_id") != character.id()) {
					throw RpgException.invalidArgument("That item is not carried by " + characterRef + ".");
				}
				Item item = ContentService.itemForEntry(tx, rules, entry);
				long have = entry.lng("quantity");
				long pieces = quantity == null ? have : quantity;
				if (pieces <= 0 || pieces > have) {
					throw RpgException.insufficientResource(
							character.str("name") + " carries " + have + " × " + item.name() + ", not " + pieces + ".");
				}
				long bundles = pieces / item.bundleSize();
				long listPrice = Boolean.TRUE.equals(item.payload().get("valuable")) ? item.costCp() * bundles
						: item.costCp() * bundles / 2;
				long price = negotiated != null ? negotiated : listPrice;
				removeQuantity(tx, entry, pieces);
				tx.update("character", character.id(),
						Map.of("money_cp", money + price, "revision", character.lng("revision") + 1));
				result.put("item", item.display());
				result.put("name", item.name());
				result.put("quantity", pieces);
				result.put("list_price", Money.format(listPrice));
				result.put("price_received", Money.format(price));
				result.put("money", Money.render(money + price));
				recordTrade(tx, campaignId, character, merchantId, "ITEM_SOLD",
						character.str("name") + " sold " + describe(item, pieces) + " for " + Money.format(price) + ".",
						item, pieces, price, negotiated != null, reason);
			}
			tx.touched(Ref.of(Ref.CHARACTER, character.id()), character.lng("revision") + 1);
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	private static String describe(Item item, long pieces) {
		return pieces == 1 ? item.name() : pieces + " × " + item.name();
	}

	private static void recordTrade(
			Tx tx, long campaignId, Row character, Long merchantId, String type, String summary,
			Item item, long pieces, long price, boolean negotiated, String reason) {
		var payload = new LinkedHashMap<String, Object>();
		payload.put("item", item.display());
		payload.put("quantity", pieces);
		payload.put("price_cp", price);
		payload.put("merchant", Ref.ofNullable(Ref.CHARACTER, merchantId));
		if (negotiated) {
			payload.put("negotiated", true);
			payload.put("negotiation_reason", reason);
		}
		var actors = new ArrayList<Long>();
		actors.add(character.id());
		if (merchantId != null) {
			actors.add(merchantId);
		}
		LedgerService.append(tx, campaignId,
				new LedgerService.EventSpec(type, summary, actors, "MINOR", "PARTY_KNOWN", negotiated ? "GM" : "PLAYER",
						null, character.lng("location_id"), null, payload));
	}

	private Row findOwnedItem(Tx tx, long campaignId, long characterId, String itemText) {
		Item item = ContentService.resolveItem(tx, rules, campaignId, itemText);
		List<Row> rows = item.custom() ? tx.query(
				"SELECT * FROM inventory_entry WHERE character_id = ? AND custom_content_id = ? ORDER BY equipped, id",
				characterId, item.customId()) : tx.query(
				"SELECT * FROM inventory_entry WHERE character_id = ? AND content_ref = ? ORDER BY equipped, id",
				characterId, item.contentRef());
		if (rows.isEmpty()) {
			throw RpgException.notFound(item.name() + " in that character's inventory");
		}
		return rows.get(0);
	}

	// ── grant_loot ─────────────────────────────────────────────────────

	public Map<String, Object> grantLoot(
			String operationId, String campaignRef, String toRef,
			List<Map<String, Object>> items, Object money, String source, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("to", toRef);
		args.put("items", items);
		args.put("money", money);
		args.put("source", source);
		args.put("reason", reason);
		String src = source == null ? "GM_GRANT" : source.toUpperCase();
		return db.mutate(Database.Mutation.of("grant_loot", campaignId, operationId,
				src.equals("GM_GRANT") ? "ADMINISTRATIVE_OVERRIDE" : "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "grant_loot");
			if (!LOOT_SOURCES.contains(src)) {
				throw RpgException.invalidArgument("source must be one of " + LOOT_SOURCES + ".");
			}
			if (src.equals("GM_GRANT")) {
				Map<String, Object> policy =
						campaign.isNull("gm_override_policy_json") ? Map.of() : campaign.map("gm_override_policy_json");
				if ("DISABLED".equals(policy.get("policy"))) {
					throw RpgException.policyDenied(
							"This campaign disables GM overrides; arbitrary treasure grants are not permitted. Use an ENCOUNTER, QUEST or WORLD source tied to play.");
				}
				if (reason == null || reason.isBlank()) {
					throw RpgException.invalidArgument("GM_GRANT loot requires a reason; it is audited.");
				}
			}
			if ((items == null || items.isEmpty()) && money == null) {
				throw RpgException.invalidArgument("Grant at least one item or some money.");
			}
			Ref target = Ref.parse(toRef);
			Long toCharacter = null;
			Long toLocation = null;
			Row character = null;
			if (target.type().equals(Ref.CHARACTER)) {
				character = activeCharacter(tx, campaignId, toRef);
				toCharacter = character.id();
			} else if (target.type().equals(Ref.LOCATION)) {
				Row l = tx.find("location", target.id()).orElseThrow(() -> RpgException.notFound("Location " + toRef));
				if (l.lng("campaign_id") != campaignId) {
					throw RpgException.invalidArgument(toRef + " belongs to another campaign.");
				}
				toLocation = l.id();
			} else {
				throw RpgException.invalidArgument("to must be a character:N or location:N reference.");
			}
			var granted = new ArrayList<Map<String, Object>>();
			if (items != null) {
				for (Map<String, Object> spec : items) {
					Item item = ContentService.resolveItem(tx, rules, campaignId, String.valueOf(spec.get("item")));
					long qty = spec.get("quantity") instanceof Number n ? n.longValue() : 1;
					if (item.type().equals("PACK") && toCharacter != null) {
						granted.addAll(grantBundle(tx, rules, campaignId, toCharacter, List.of(spec), null));
						continue;
					}
					long entryId = addItem(tx, campaignId, toCharacter, toLocation, item, qty);
					var g = new LinkedHashMap<String, Object>();
					g.put("entry", Ref.of(Ref.INVENTORY, entryId));
					g.put("item", item.display());
					g.put("name", item.name());
					g.put("quantity", qty);
					granted.add(g);
				}
			}
			long moneyCp = money == null ? 0 : Money.parseCp(money);
			if (moneyCp > 0) {
				if (character == null) {
					throw RpgException.invalidArgument("Money can only be granted to a character.");
				}
				tx.update("character", character.id(),
						Map.of("money_cp", character.lng("money_cp") + moneyCp, "revision",
								character.lng("revision") + 1));
			}
			String summary = (character == null ? "Loot left at " + toRef
					: character.str("name") + " acquired") + " " + granted.stream()
					.map(g -> g.get("quantity") + " × " + g.get("name")).toList() + (moneyCp > 0
					? " and " + Money.format(moneyCp) : "") + " (" + src + (reason == null ? "" : ": " + reason) + ").";
			var payload = new LinkedHashMap<String, Object>();
			payload.put("source", src);
			payload.put("items", granted);
			payload.put("money_cp", moneyCp);
			payload.put("reason", reason);
			long eventId = LedgerService.append(tx, campaignId, new LedgerService.EventSpec("LOOT_ACQUIRED", summary,
					character == null ? List.of() : List.of(character.id()), "NOTABLE", "PARTY_KNOWN",
					src.equals("GM_GRANT") ? "ADMINISTRATIVE_OVERRIDE" : "GM", null,
					character == null ? toLocation : character.lng("location_id"), null, payload));
			if (src.equals("GM_GRANT")) {
				var audit = new LinkedHashMap<String, Object>();
				audit.put("campaign_id", campaignId);
				audit.put("kind", "DISCRETIONARY_LOOT");
				audit.put("actor", "gm");
				audit.put("provenance", "ADMINISTRATIVE_OVERRIDE");
				audit.put("reason", reason);
				audit.put("before_json", null);
				audit.put("after_json", Json.write(payload));
				audit.put("recorded_at", Instant.now().toString());
				tx.rawInsert("audit_record", audit);
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("to", toRef);
			result.put("source", src);
			result.put("granted", granted);
			result.put("money_granted", Money.render(moneyCp));
			if (character != null) {
				result.put("money", Money.render(tx.get("character", character.id()).lng("money_cp")));
			}
			result.put("event", Ref.of(Ref.EVENT, eventId));
			result.put("audited", src.equals("GM_GRANT"));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── helpers ────────────────────────────────────────────────────────

	private static Row entry(Tx tx, long campaignId, String entryRef) {
		long id = Ref.id(entryRef, Ref.INVENTORY);
		Row e = tx.find("inventory_entry", id).orElseThrow(() -> RpgException.notFound("Inventory entry " + entryRef));
		if (e.lng("campaign_id") != campaignId) {
			throw RpgException.invalidArgument(entryRef + " belongs to another campaign.");
		}
		return e;
	}

	private static Row activeCharacter(Tx tx, long campaignId, String ref) {
		long id = Ref.id(ref, Ref.CHARACTER);
		Row c = tx.find("character", id).orElseThrow(() -> RpgException.notFound("Character " + ref));
		if (c.lng("campaign_id") != campaignId) {
			throw RpgException.invalidArgument(ref + " belongs to another campaign.");
		}
		if (!"ACTIVE".equals(c.str("lifecycle"))) {
			throw RpgException.notAllowed(ref + " is not an active character (lifecycle " + c.str("lifecycle") + ").");
		}
		return c;
	}

}
