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

import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.*;

/**
 * Read-mostly view over installed content (DOMAIN_MODEL.md §18). Installed content is immutable at runtime (I-65), so
 * it is loaded once and served from memory.
 */
public final class RulesData {

	/** One installed definition. */
	public record Definition(String id, String kind, String name, Map<String, Object> payload) {

		public Map<String, Object> summary() {
			var m = new LinkedHashMap<String, Object>();
			m.put("id", id);
			m.put("name", name);
			if (payload.get("summary") instanceof String s && !s.isBlank()) {
				m.put("summary", s);
			}
			return m;
		}
	}

	private final Map<String, Definition> byId = new LinkedHashMap<>();
	private final Map<String, List<Definition>> byKind = new LinkedHashMap<>();
	private final List<Map<String, Object>> rulesets = new ArrayList<>();

	public RulesData(Database db) {
		db.read(tx -> {
			for (Row r : tx.query("SELECT * FROM installed_ruleset ORDER BY id")) {
				var m = new LinkedHashMap<String, Object>();
				m.put("namespace", r.str("namespace"));
				m.put("version", r.str("version"));
				m.put("license", r.str("license"));
				m.put("attribution", r.str("attribution"));
				rulesets.add(m);
			}
			for (Row r : tx.query("SELECT * FROM installed_content ORDER BY id")) {
				var d = new Definition(r.str("content_id"), r.str("kind"), r.str("name"), r.map("payload_json"));
				byId.put(d.id(), d);
				byKind.computeIfAbsent(d.kind(), k -> new ArrayList<>()).add(d);
			}
			return null;
		});
	}

	public List<Map<String, Object>> rulesets() {
		return rulesets;
	}

	public Optional<Definition> find(String contentId) {
		return Optional.ofNullable(byId.get(contentId));
	}

	public Definition require(String contentId, String expectedKind) {
		Definition d = byId.get(contentId);
		if (d == null || !d.kind().equals(expectedKind)) {
			throw RpgException.invalidArgument(
					"Unknown " + expectedKind.toLowerCase() + " '" + contentId + "'. Use get_character_choices to list legal options.");
		}
		return d;
	}

	public List<Definition> ofKind(String kind) {
		return byKind.getOrDefault(kind, List.of());
	}

	/** Every kind that actually has content installed, in load order. */
	public java.util.Set<String> kinds() {
		return byKind.keySet();
	}

	/** Every installed definition, across all kinds. */
	public List<Definition> all() {
		return new ArrayList<>(byId.values());
	}

	/** Resolves {@code human}, {@code Human}, {@code srd5e:species/human} → the definition. */
	public Optional<Definition> resolve(String kind, String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String t = text.trim();
		Definition exact = byId.get(t);
		if (exact != null && exact.kind().equals(kind)) {
			return Optional.of(exact);
		}
		String slug = t.toLowerCase().replace(' ', '-');
		return ofKind(kind).stream().filter(d -> d.name().equalsIgnoreCase(t) || d.id().endsWith("/" + slug))
				.findFirst();
	}

	public int proficiencyBonus(int level) {
		return advancementRow(level).map(r -> ((Number) r.get("proficiency_bonus")).intValue()).orElse(2);
	}

	public int xpThreshold(int level) {
		return advancementRow(level).map(r -> ((Number) r.get("xp")).intValue()).orElse(0);
	}

	public int levelForXp(long xp) {
		int level = 1;
		for (Map<String, Object> row : advancementLevels()) {
			if (xp >= ((Number) row.get("xp")).longValue()) {
				level = ((Number) row.get("level")).intValue();
			}
		}
		return level;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> advancementLevels() {
		return find("srd5e:table/character-advancement").map(d -> (List<Map<String, Object>>) d.payload().get("levels"))
				.orElse(List.of());
	}

	private Optional<Map<String, Object>> advancementRow(int level) {
		return advancementLevels().stream().filter(r -> ((Number) r.get("level")).intValue() == level).findFirst();
	}
}
