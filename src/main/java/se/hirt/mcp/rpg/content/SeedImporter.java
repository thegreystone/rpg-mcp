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
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Imports embedded JSON seed data into {@code installed_ruleset}/{@code installed_content} on first
 * run, or whenever a database lacks the ruleset+version (DESIGN.md §25.1, I-66). Idempotent per
 * version: when the version is already installed, entries whose embedded definition changed (a new
 * {@code summary}, a corrected payload) or that are new are refreshed in place — content ids are
 * stable, so nothing that references them moves. Installed content is not rewindable, so the
 * importer uses the raw write path.
 */
public final class SeedImporter {

	public static final String DEFAULT_RULESET = "srd5e";

	private SeedImporter() {
	}

	/** @return true if an import happened. */
	public static boolean importIfMissing(Database db, String namespace) {
		Map<String, Object> ruleset = Json.readMap(resource("seed/" + namespace + "/ruleset.json"));
		String version = (String) ruleset.get("version");
		boolean present = db
				.read(tx -> tx.count("SELECT COUNT(*) FROM installed_ruleset WHERE namespace = ? AND version = ?",
						namespace, version) > 0);
		if (present) {
			refresh(db, namespace, ruleset);
			return false;
		}
		db.mutate(Database.Mutation.of("import_ruleset", null, null, "ADMINISTRATIVE_OVERRIDE", null), tx -> {
			long rulesetId = insertRuleset(tx, ruleset);
			int count = 0;
			@SuppressWarnings("unchecked")
			List<String> files = (List<String>) ruleset.get("files");
			for (String file : files) {
				count += importFile(tx, rulesetId, "seed/" + namespace + "/" + file);
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("namespace", namespace);
			result.put("version", version);
			result.put("definitions", count);
			return result;
		});
		return true;
	}

	/**
	 * Brings an already-installed version up to date with the embedded seed; a no-op when nothing
	 * differs.
	 */
	@SuppressWarnings("unchecked")
	private static void refresh(Database db, String namespace, Map<String, Object> ruleset) {
		String version = (String) ruleset.get("version");
		List<String> files = (List<String>) ruleset.get("files");
		// Read the diff first so an unchanged seed never opens a mutation.
		List<Object[]> changes = db.read(tx -> {
			long rulesetId = tx.queryOne("SELECT id FROM installed_ruleset WHERE namespace = ? AND version = ?",
					namespace, version).orElseThrow().id();
			var out = new ArrayList<Object[]>();
			for (String file : files) {
				Map<String, Object> f = Json.readMap(resource("seed/" + namespace + "/" + file));
				String kind = (String) f.get("kind");
				for (Map<String, Object> entry : (List<Map<String, Object>>) f.get("entries")) {
					Map<String, Object> cols = columnsFor(entry, kind, rulesetId);
					var existing = tx.queryOne(
							"SELECT id, name, payload_json FROM installed_content WHERE content_id = ?",
							entry.get("id"));
					if (existing.isEmpty()) {
						out.add(new Object[] {null, cols});
					} else if (!Objects.equals(existing.get().str("name"), cols.get("name"))
							|| !Objects.equals(existing.get().str("payload_json"), cols.get("payload_json"))) {
						out.add(new Object[] {existing.get().id(), cols});
					}
				}
			}
			return out;
		});
		if (changes.isEmpty()) {
			return;
		}
		db.mutate(Database.Mutation.of("refresh_ruleset", null, null, "ADMINISTRATIVE_OVERRIDE", null), tx -> {
			int inserted = 0;
			int updated = 0;
			for (Object[] change : changes) {
				Map<String, Object> cols = (Map<String, Object>) change[1];
				if (change[0] == null) {
					tx.rawInsert("installed_content", cols);
					inserted++;
				} else {
					tx.rawUpdate("installed_content", (Long) change[0], cols);
					updated++;
				}
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("namespace", namespace);
			result.put("version", version);
			result.put("inserted", inserted);
			result.put("updated", updated);
			return result;
		});
	}

	private static long insertRuleset(Tx tx, Map<String, Object> ruleset) {
		var cols = new LinkedHashMap<String, Object>();
		cols.put("namespace", ruleset.get("namespace"));
		cols.put("version", ruleset.get("version"));
		cols.put("imported_at", Instant.now().toString());
		cols.put("license", ruleset.get("license"));
		cols.put("attribution", ruleset.get("attribution"));
		return tx.rawInsert("installed_ruleset", cols);
	}

	private static int importFile(Tx tx, long rulesetId, String path) {
		Map<String, Object> file = Json.readMap(resource(path));
		String kind = (String) file.get("kind");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> entries = (List<Map<String, Object>>) file.get("entries");
		int count = 0;
		for (Map<String, Object> entry : entries) {
			tx.rawInsert("installed_content", columnsFor(entry, kind, rulesetId));
			count++;
		}
		return count;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> columnsFor(Map<String, Object> entry, String kind, long rulesetId) {
		Map<String, Object> payload = (Map<String, Object>) entry.getOrDefault("payload", Map.of());
		var cols = new LinkedHashMap<String, Object>();
		cols.put("content_id", entry.get("id"));
		cols.put("ruleset_id", rulesetId);
		cols.put("kind", kind);
		cols.put("name", entry.get("name"));
		cols.put("payload_json", Json.write(payload));
		cols.put("cost_cp", number(payload.get("cost_cp")));
		cols.put("weight_g",
				payload.get("weight_lb") instanceof Number lb
						? Long.valueOf(Math.round(lb.doubleValue() * se.hirt.mcp.rpg.rules.Derived.GRAMS_PER_POUND))
						: number(payload.get("weight_g")));
		cols.put("spell_level", number(payload.get("level")));
		cols.put("cr_times_8", number(payload.get("cr_times_8")));
		cols.put("xp_value", number(payload.get("xp_value")));
		cols.put("tags_json", entry.containsKey("tags") ? Json.write(entry.get("tags")) : null);
		return cols;
	}

	private static Long number(Object o) {
		return o instanceof Number n ? n.longValue() : null;
	}

	static String resource(String path) {
		try (InputStream in = SeedImporter.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("Missing seed resource " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw RpgException.internal("Cannot read seed resource " + path, e);
		}
	}
}
