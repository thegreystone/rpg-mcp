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
package se.hirt.mcp.rpg.content;

import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Derived;
import se.hirt.mcp.rpg.rules.Money;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Content definitions: installed ruleset content (immutable, {@code srd5e:item/longsword}) and campaign-owned custom
 * definitions ({@code content:N}, optional {@code custom:item/...} symbolic id) — MCP_PROTOCOL.md §13.6/§13.7,
 * DOMAIN_MODEL.md §18.
 */
public final class ContentService {

	public static final Set<String> ITEM_TYPES = Set.of("WEAPON", "ARMOR", "SHIELD", "GEAR", "TOOL", "AMMUNITION",
			"FOCUS", "PACK", "CONTAINER", "MOUNT", "TACK", "VEHICLE", "POISON", "TRINKET", "VALUABLE", "CONSUMABLE",
			"DOCUMENT", "OTHER");

	/** A resolved item definition, installed or custom. */
	public record Item(boolean custom, String contentRef, Long customId, String name, Map<String, Object> payload) {

		/** The protocol-facing identifier: {@code srd5e:item/x} or {@code content:N}. */
		public String display() {
			return custom ? Ref.of(Ref.CONTENT, customId) : contentRef;
		}

		public String type() {
			return String.valueOf(payload.getOrDefault("type", "GEAR"));
		}

		public long costCp() {
			Object c = payload.get("cost_cp");
			return c instanceof Number n ? n.longValue() : 0;
		}

		public int bundleSize() {
			Object b = payload.get("bundle_size");
			return b instanceof Number n && n.intValue() > 0 ? n.intValue() : 1;
		}

		/** Weight of one inventory unit (one arrow, one dagger) in pounds. */
		public double unitWeightLb() {
			Object w = payload.get("weight_lb");
			double lb = w instanceof Number n ? n.doubleValue() : 0;
			return lb / bundleSize();
		}

		public Map<String, Object> summary() {
			var m = new LinkedHashMap<String, Object>();
			m.put("id", display());
			m.put("name", name);
			m.put("type", type());
			m.put("cost", Money.format(costCp()));
			m.put("cost_cp", costCp());
			if (bundleSize() > 1) {
				m.put("sold_in_bundles_of", bundleSize());
			}
			m.put("weight_lb", payload.get("weight_lb"));
			for (String k : List.of("category", "damage", "versatile", "properties", "mastery", "range", "armor",
					"ability", "tool_kind", "focus_kind", "capacity_lb", "carrying_capacity_lb", "contents")) {
				if (payload.containsKey(k)) {
					m.put(k, payload.get(k));
				}
			}
			if (payload.get("text") != null) {
				m.put("text", payload.get("text"));
			}
			if (payload.get("description") != null) {
				m.put("description", payload.get("description"));
			}
			if (custom) {
				m.put("custom", true);
				if (payload.get("symbolic_id") != null) {
					m.put("symbolic_id", payload.get("symbolic_id"));
				}
			}
			return m;
		}
	}

	private final Database db;
	private final RulesData rules;

	public ContentService(Database db, RulesData rules) {
		this.db = db;
		this.rules = rules;
	}

	public RulesData rules() {
		return rules;
	}

	// ── resolution ─────────────────────────────────────────────────────

	/**
	 * Resolves {@code srd5e:item/dagger}, {@code Dagger}, {@code content:5} or {@code custom:item/x} within a
	 * campaign.
	 */
	public static Item resolveItem(Tx tx, RulesData rules, long campaignId, String text) {
		if (text == null || text.isBlank()) {
			throw RpgException.invalidArgument(
					"An item is required (name, 'srd5e:item/...', 'content:N' or 'custom:...').");
		}
		String t = text.trim();
		if (t.startsWith(Ref.CONTENT + ":")) {
			long id = Ref.id(t, Ref.CONTENT);
			Row row = tx.find("custom_content", id).orElseThrow(() -> RpgException.notFound("Custom content " + t));
			if (row.lng("campaign_id") != campaignId) {
				throw RpgException.invalidArgument(t + " belongs to another campaign.");
			}
			return fromCustomRow(row);
		}
		if (t.startsWith("custom:")) {
			return tx.queryOne("SELECT * FROM custom_content WHERE campaign_id = ? AND symbolic_id = ?", campaignId, t)
					.map(ContentService::fromCustomRow)
					.orElseThrow(() -> RpgException.notFound("Custom content '" + t + "'"));
		}
		Optional<RulesData.Definition> installed = rules.resolve("ITEM", t);
		if (installed.isPresent()) {
			return fromDefinition(installed.get());
		}
		// Fall back to a custom definition by name.
		return tx.queryOne(
				"SELECT * FROM custom_content WHERE campaign_id = ? AND kind = 'ITEM' AND LOWER(name) = LOWER(?)",
				campaignId, t).map(ContentService::fromCustomRow).orElseThrow(() -> RpgException.notFound(
				"Item '" + t + "' (use get_content_definitions to search, or define_content to create it)"));
	}

	public static Item fromDefinition(RulesData.Definition d) {
		return new Item(false, d.id(), null, d.name(), d.payload());
	}

	public static Item fromCustomRow(Row row) {
		Map<String, Object> payload = row.map("payload_json");
		payload.putIfAbsent("cost_cp", row.lng("cost_cp"));
		if (row.get("symbolic_id") != null) {
			payload.put("symbolic_id", row.str("symbolic_id"));
		}
		return new Item(true, null, row.id(), row.str("name"), payload);
	}

	/** The definition behind an inventory entry row. */
	public static Item itemForEntry(Tx tx, RulesData rules, Row entry) {
		if ("CUSTOM".equals(entry.str("content_ref_kind"))) {
			return fromCustomRow(tx.get("custom_content", entry.lng("custom_content_id")));
		}
		return rules.find(entry.str("content_ref")).map(ContentService::fromDefinition).orElseGet(
				() -> new Item(false, entry.str("content_ref"), null, entry.str("content_ref"),
						Map.of("type", "OTHER")));
	}

	// ── get_content_definitions ────────────────────────────────────────

	/**
	 * Ranked free-text search across every installed definition and the campaign's own custom content
	 * (MCP_PROTOCOL.md §13.8). This is how a rules question gets an answer with a citation instead of a recollection:
	 * the SRD's Rules Glossary is installed as {@code RULE} content, so conditions, actions, hazards, cover, resting
	 * and the rest are searchable next to the spells and items they interact with.
	 */
	public Map<String, Object> search(String campaignRef, String query, String kind, int limit) {
		if (query == null || query.isBlank()) {
			throw RpgException.invalidArgument("A search query is required.");
		}
		int max = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 50));
		String k = kind == null || kind.isBlank() ? null : kind.toUpperCase();
		List<String> terms = java.util.Arrays.stream(query.toLowerCase().trim().split("\\s+"))
				.filter(t -> t.length() > 1).toList();
		if (terms.isEmpty()) {
			terms = List.of(query.toLowerCase().trim());
		}
		List<String> needles = terms;
		return db.read(tx -> {
			Row campaign = campaignRef == null || campaignRef.isBlank() ? null : Harness.campaign(tx, campaignRef);
			var hits = new ArrayList<Map<String, Object>>();
			for (RulesData.Definition d : rules.all()) {
				if (k != null && !k.equals(d.kind())) {
					continue;
				}
				scoreOne(d.id(), d.kind(), d.name(), d.payload(), needles).ifPresent(hits::add);
			}
			if (campaign != null) {
				for (Row row : tx.query("SELECT * FROM custom_content WHERE campaign_id = ? ORDER BY id",
						campaign.id())) {
					if (k != null && !k.equals(row.str("kind"))) {
						continue;
					}
					Item item = fromCustomRow(row);
					scoreOne(item.contentRef(), row.str("kind"), item.name(), item.payload(), needles)
							.ifPresent(hits::add);
				}
			}
			hits.sort((a, b) -> Integer.compare((Integer) b.get("score"), (Integer) a.get("score")));
			var result = new LinkedHashMap<String, Object>();
			result.put("query", query);
			result.put("total", hits.size());
			result.put("results", new ArrayList<>(hits.subList(0, Math.min(max, hits.size()))));
			result.put("kinds", rules.kinds().stream().sorted().toList());
			result.put("note",
					"Rules text is the SRD 5.2.1 wording (CC-BY-4.0). Cite the returned ref when you apply a rule; "
							+ "if nothing matches, say so rather than recalling a rule from memory.");
			if (campaign != null) {
				result.put("meta", Harness.meta(campaign, null));
			}
			return result;
		});
	}

	/** Scores one definition against the search terms and builds its hit, or nothing when it does not match. */
	private Optional<Map<String, Object>> scoreOne(
			String id, String kind, String name, Map<String, Object> payload, List<String> terms) {
		String lowerName = name.toLowerCase();
		String text = String.valueOf(payload.getOrDefault("text", ""));
		String summary = String.valueOf(payload.getOrDefault("summary", ""));
		String haystack = (lowerName + " " + summary + " " + text + " " + payload).toLowerCase();
		int score = 0;
		for (String t : terms) {
			if (!haystack.contains(t)) {
				return Optional.empty();
			}
			if (lowerName.equals(t)) {
				score += 100;
			} else if (lowerName.startsWith(t)) {
				score += 40;
			} else if (lowerName.contains(t)) {
				score += 20;
			}
			if (summary.toLowerCase().contains(t)) {
				score += 5;
			}
			score += Math.min(10, countOf(text.toLowerCase(), t));
		}
		if ("RULE".equals(kind)) {
			score += 3;
		}
		var hit = new LinkedHashMap<String, Object>();
		hit.put("ref", id);
		hit.put("kind", kind);
		hit.put("name", name);
		if (payload.get("tag") != null) {
			hit.put("tag", payload.get("tag"));
		}
		hit.put("score", score);
		hit.put("snippet", snippet(text.isBlank() ? summary : text, terms));
		return Optional.of(hit);
	}

	private static int countOf(String haystack, String needle) {
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
			n++;
		}
		return n;
	}

	/** A window of text around the first matching term, so a hit can be judged without a second call. */
	private static String snippet(String text, List<String> terms) {
		if (text.isBlank()) {
			return "";
		}
		String lower = text.toLowerCase();
		int at = -1;
		for (String t : terms) {
			at = lower.indexOf(t);
			if (at >= 0) {
				break;
			}
		}
		if (at < 0) {
			at = 0;
		}
		int from = Math.max(0, at - 90);
		int to = Math.min(text.length(), at + 250);
		return (from > 0 ? "…" : "") + text.substring(from, to).trim() + (to < text.length() ? "…" : "");
	}

	public Map<String, Object> definitions(
			String campaignRef, String kind, String itemType, String text,
			Object minCost, Object maxCost, String cursor, int limit, String detail) {
		int max = Math.max(1, Math.min(limit <= 0 ? 25 : limit, 100));
		String k = kind == null || kind.isBlank() ? "ITEM" : kind.toUpperCase();
		String type = itemType == null || itemType.isBlank() ? null : itemType.toUpperCase();
		Long min = minCost == null ? null : Money.parseCp(minCost);
		Long maxC = maxCost == null ? null : Money.parseCp(maxCost);
		String needle = text == null || text.isBlank() ? null : text.trim().toLowerCase();
		boolean full = "FULL".equalsIgnoreCase(detail);
		return db.read(tx -> {
			Row campaign = campaignRef == null || campaignRef.isBlank() ? null : Harness.campaign(tx, campaignRef);
			var all = new ArrayList<Map<String, Object>>();
			for (RulesData.Definition d : rules.ofKind(k)) {
				Map<String, Object> entry = k.equals("ITEM") ? fromDefinition(d).summary() : genericSummary(d);
				if (matches(entry, d.payload(), type, needle, min, maxC)) {
					all.add(full ? withPayload(entry, d.payload()) : entry);
				}
			}
			if (campaign != null) {
				for (Row row : tx.query("SELECT * FROM custom_content WHERE campaign_id = ? AND kind = ? ORDER BY id",
						campaign.id(), k)) {
					Item item = fromCustomRow(row);
					Map<String, Object> entry = item.summary();
					if (matches(entry, item.payload(), type, needle, min, maxC)) {
						all.add(full ? withPayload(entry, item.payload()) : entry);
					}
				}
			}
			int offset = decodeCursor(cursor);
			var page = all.subList(Math.min(offset, all.size()), Math.min(offset + max, all.size()));
			var result = new LinkedHashMap<String, Object>();
			result.put("kind", k);
			result.put("total", all.size());
			result.put("items", new ArrayList<>(page));
			result.put("next_cursor", offset + max < all.size() ? encodeCursor(offset + max) : null);
			if (k.equals("ITEM")) {
				result.put("item_types", ITEM_TYPES.stream().sorted().toList());
			}
			if (campaign != null) {
				result.put("meta", Harness.meta(campaign, null));
			}
			return result;
		});
	}

	private static Map<String, Object> genericSummary(RulesData.Definition d) {
		var m = d.summary();
		m.put("kind", d.kind());
		if (d.kind().equals("SPELL")) {
			Map<String, Object> p = d.payload();
			m.put("level", p.get("level"));
			m.put("school", p.get("school"));
			m.put("classes", p.get("classes"));
			if (Boolean.TRUE.equals(p.get("concentration"))) {
				m.put("concentration", true);
			}
			if (Boolean.TRUE.equals(p.get("ritual"))) {
				m.put("ritual", true);
			}
			m.put("summary", p.get("text"));
		}
		if (d.kind().equals("FEAT")) {
			m.put("category", d.payload().get("category"));
			if (d.payload().get("prerequisite") != null) {
				m.put("prerequisite", d.payload().get("prerequisite"));
			}
		}
		if (d.kind().equals("BACKGROUND")) {
			m.put("ability_scores", d.payload().get("ability_scores"));
			m.put("skills", d.payload().get("skills"));
			m.put("feat", d.payload().get("feat"));
		}
		if (d.kind().equals("SPECIES")) {
			m.put("size", d.payload().get("size"));
			m.put("speed", d.payload().get("speed"));
		}
		return m;
	}

	private static Map<String, Object> withPayload(Map<String, Object> entry, Map<String, Object> payload) {
		var m = new LinkedHashMap<>(entry);
		m.put("payload", payload);
		return m;
	}

	private static boolean matches(
			Map<String, Object> entry, Map<String, Object> payload, String type, String needle, Long min, Long max) {
		if (type != null && !type.equals(String.valueOf(payload.get("type")))) {
			return false;
		}
		if (needle != null) {
			String hay = (entry.get("name") + " " + entry.get("id") + " " + payload.getOrDefault("text",
					"") + " " + payload.getOrDefault("description", "") + " " + payload.getOrDefault("properties",
					"") + " " + payload.getOrDefault("category", "") + " " + payload.getOrDefault("classes", "") + (
					payload.get("level") == null ? "" : " level " + payload.get("level") + " ") + payload.getOrDefault(
					"school", "")).toLowerCase();
			for (String word : needle.split("\\s+")) {
				if (!hay.contains(word)) {
					return false;
				}
			}
		}
		Object c = payload.get("cost_cp");
		long cost = c instanceof Number n ? n.longValue() : 0;
		if (min != null && cost < min) {
			return false;
		}
		return max == null || cost <= max;
	}

	private static int decodeCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return 0;
		}
		try {
			return Integer.parseInt(
					new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).substring(4));
		} catch (RuntimeException e) {
			throw RpgException.invalidArgument("Invalid cursor.");
		}
	}

	private static String encodeCursor(int offset) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(("off:" + offset).getBytes(StandardCharsets.UTF_8));
	}

	// ── define_content ─────────────────────────────────────────────────

	public Map<String, Object> define(
			String operationId, String campaignRef, String kind, String name, String symbolicId, String description,
			String itemType, Object cost, Double weightLb, Map<String, Object> properties, List<String> tags,
			String provenance) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("kind", kind);
		args.put("name", name);
		args.put("symbolic_id", symbolicId);
		args.put("item_type", itemType);
		args.put("cost", cost);
		args.put("weight_lb", weightLb);
		args.put("properties", properties);
		String prov = provenance == null || provenance.isBlank() ? "GM" : provenance.toUpperCase();
		return db.mutate(Database.Mutation.of("define_content", campaignId, operationId, prov, args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "define_content");
			String k = kind == null || kind.isBlank() ? "ITEM" : kind.toUpperCase();
			if (!k.equals("ITEM")) {
				throw RpgException.capabilityUnavailable("Only ITEM custom content is supported in this milestone.");
			}
			if (name == null || name.isBlank()) {
				throw RpgException.invalidArgument("A name is required.");
			}
			String type = itemType == null || itemType.isBlank() ? "GEAR" : itemType.toUpperCase();
			if (!ITEM_TYPES.contains(type)) {
				throw RpgException.invalidArgument(
						"item_type must be one of " + ITEM_TYPES.stream().sorted().toList() + ".");
			}
			if (Set.of("WEAPON", "ARMOR", "SHIELD", "PACK").contains(type)) {
				throw RpgException.capabilityUnavailable(
						"Custom " + type + " definitions need mechanical schemas that arrive with the encounter milestone; use GEAR/VALUABLE/CONSUMABLE/DOCUMENT/OTHER for now.");
			}
			String symbolic = null;
			if (symbolicId != null && !symbolicId.isBlank()) {
				symbolic = symbolicId.trim();
				if (!symbolic.matches("custom:[a-z]+/[a-z0-9][a-z0-9-]*")) {
					throw RpgException.invalidArgument(
							"symbolic_id must look like 'custom:item/bellhaven-broadsheet'.");
				}
				if (tx.count("SELECT COUNT(*) FROM custom_content WHERE campaign_id = ? AND symbolic_id = ?",
						campaignId, symbolic) > 0) {
					throw RpgException.conflict("symbolic_id '" + symbolic + "' already exists in this campaign.");
				}
			}
			long costCp = cost == null ? 0 : Money.parseCp(cost);
			double lb = weightLb == null ? 0 : weightLb;
			if (lb < 0) {
				throw RpgException.invalidArgument("weight_lb cannot be negative.");
			}
			var payload = new LinkedHashMap<String, Object>();
			payload.put("type", type);
			payload.put("cost_cp", costCp);
			payload.put("weight_lb", lb);
			if (description != null && !description.isBlank()) {
				payload.put("description", description.trim());
			}
			if (properties != null) {
				for (var e : properties.entrySet()) {
					if (!Set.of("type", "cost_cp", "weight_lb", "symbolic_id").contains(e.getKey())) {
						payload.put(e.getKey(), e.getValue());
					}
				}
			}
			var cols = new LinkedHashMap<String, Object>();
			cols.put("campaign_id", campaignId);
			cols.put("kind", k);
			cols.put("symbolic_id", symbolic);
			cols.put("name", name.trim());
			cols.put("payload_json", Json.write(payload));
			cols.put("cost_cp", costCp);
			cols.put("weight_g", Derived.gramsFromLb(lb));
			cols.put("tags_json", tags == null ? null : Json.write(tags));
			cols.put("license_json", Json.write(Map.of("license", "campaign-owned", "provenance", prov)));
			cols.put("provenance", prov);
			cols.put("revision", 0);
			cols.put("created_at", Instant.now().toString());
			long id = tx.insert("custom_content", cols);
			tx.touched(Ref.of(Ref.CONTENT, id), 0);
			var result = new LinkedHashMap<String, Object>();
			result.put("content", Ref.of(Ref.CONTENT, id));
			result.put("symbolic_id", symbolic);
			result.put("definition", fromCustomRow(tx.get("custom_content", id)).summary());
			result.put("note",
					"Defining content grants it to nobody; use trade, grant_loot or transfer_item to put it into play.");
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}
}
