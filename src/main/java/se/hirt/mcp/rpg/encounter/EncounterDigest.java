package se.hirt.mcp.rpg.encounter;

import se.hirt.mcp.rpg.maps.GridCombat;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.math.BigDecimal;

/** One saved mechanical fight record, with a separate frozen party-knowledge projection. */
public final class EncounterDigest {

	private static final int MAX_HIGHLIGHTS = 8;
	private static final int NARRATION_LIMIT = 300;
	public static final int ANNOTATION_SLOTS_MAX_BYTES = 2000;

	private EncounterDigest() {
	}

	public static void validateNarration(Map<String, Object> action) {
		if (action != null && action.get("narration") != null && !(action.get("narration") instanceof String))
			throw RpgException.invalidArgument("action.narration must be text.");
	}

	/**
	 * Match this invocation's action row, never the previous turn or a reaction by another actor.
	 */
	public static void captureNarration(
		Tx tx, long encounterId, long actorId, Map<String, Object> action, Map<String, Object> result) {
		validateNarration(action);
		String kind = String.valueOf(action.getOrDefault("kind", "")).toUpperCase(Locale.ROOT);
		String logKind = "OPPORTUNITY_ATTACK".equals(result.get("reaction")) ? "OPPORTUNITY_ATTACK"
				: "DASH".equals(kind) ? "MOVE" : kind;
		var found = tx.queryOne(
				"SELECT * FROM encounter_log WHERE encounter_id=? AND journal_id=? "
						+ "AND actor_character_id=? AND kind=? ORDER BY id DESC LIMIT 1",
				encounterId, tx.journalId(), actorId, logKind);
		if (found.isEmpty()) {
			if (Boolean.TRUE.equals(result.get("paused_for_reaction")) && Set.of("MOVE", "DASH").contains(kind)) {
				Row encounter = tx.get("encounter", encounterId);
				var environment = new LinkedHashMap<>(encounter.map("environment_json"));
				environment.put("record_pending_movement", Map.of("actor", actorId, "action", action));
				tx.update("encounter", encounterId, Map.of("environment_json", environment));
			}
			return;
		}
		Row row = found.get();
		if (!row.map("payload_json").containsKey("party_observed")) {
			// SpellService writes CAST directly; capture its observation while this action is resolving.
			recorded(tx, encounterId, row.id(), actorId, row.str("kind"));
			row = tx.get("encounter_log", row.id());
		}
		var payload = new LinkedHashMap<>(row.map("payload_json"));
		var input = new LinkedHashMap<>(action);
		input.remove("narration");
		payload.put("action", input);
		payload.put("result", mechanicalResult(result));
		var changes = new LinkedHashMap<String, Object>();
		changes.put("payload_json", payload);
		if (action.get("narration") instanceof String narration && !narration.isBlank()) {
			changes.put("narration", clipped(narration, NARRATION_LIMIT));
			if (narration.trim().length() > NARRATION_LIMIT) {
				payload.put("narration_note", "Narration trimmed to 300 characters.");
				result.put("narration_note", "Narration trimmed to 300 characters.");
			}
		}
		tx.update("encounter_log", row.id(), changes);
	}

	/** The log helper also captures deferred movement once its actual MOVE row is emitted. */
	static void recorded(Tx tx, long encounterId, long logId, Long actorId, String kind) {
		Row encounter = tx.get("encounter", encounterId);
		if ("ENCOUNTER_STARTED".equals(kind)) {
			var baseline = snapshot(tx, encounter, false);
			baseline.put("journal_id", tx.journalId());
			tx.update("encounter", encounterId, Map.of("record_json", Map.of("baseline", baseline)));
			return;
		}
		Row log = tx.get("encounter_log", logId);
		var payload = new LinkedHashMap<>(log.map("payload_json"));
		if (actorId != null)
			payload.put("actor_after", character(tx, actorId));
		Long target = characterId(payload.get("target"));
		if (target != null && tx.find("character", target).isPresent())
			payload.put("target_after", character(tx, target));
		payload.put("party_known", known(tx, encounter).stream().map(id -> Ref.of(Ref.CHARACTER, id)).toList());
		Set<Long> observed = GridCombat.knownToParty(tx, encounter);
		payload.put("party_observed",
				(actorId == null || observed.contains(actorId)) && (target == null || observed.contains(target)));
		tx.update("encounter_log", logId, Map.of("payload_json", payload));
		if ("MOVE".equals(kind) && actorId != null) {
			var environment = new LinkedHashMap<>(encounter.map("environment_json"));
			Map<String, Object> completed = SceneService.map(environment.remove("record_completed_movement"));
			if (!completed.isEmpty()) {
				tx.update("encounter", encounterId, Map.of("environment_json", environment));
				if (Objects.equals(characterId(completed.get("actor")), actorId)
						&& completed.get("completion_journal") instanceof Number journal
						&& journal.longValue() == tx.journalId())
					captureNarration(tx, encounterId, actorId, SceneService.map(completed.get("action")), payload);
			}
		}
	}

	/** A cancelled continuation may emit no MOVE row; its words expire with this invocation. */
	static void movementContinuationCleared(Map<String, Object> environment, long journalId) {
		Map<String, Object> pending = SceneService.map(environment.remove("record_pending_movement"));
		if (!pending.isEmpty()) {
			var completed = new LinkedHashMap<>(pending);
			completed.put("completion_journal", journalId);
			environment.put("record_completed_movement", completed);
		}
	}

	static Map<String, Object> build(
		Tx tx, Row encounter, String outcome, String summary, String narrative, long xpPool,
		List<Map<String, Object>> awards) {
		Map<String, Object> baseline = SceneService.map(encounter.map("record_json").get("baseline"));
		boolean baselineAvailable = baseline.get("seconds") instanceof Number && baseline.containsKey("roster")
				&& baseline.containsKey("resources");
		recordRuntimeCare(tx, encounter, baseline);
		Map<String, Object> after = snapshot(tx, encounter, true);
		var record = new LinkedHashMap<String, Object>();
		record.put("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()));
		record.put("outcome", outcome);
		record.put("summary", summary);
		record.put("rounds", encounter.lng("round"));
		record.put("baseline_status", baselineAvailable ? "AVAILABLE" : "UNAVAILABLE");
		if (!baselineAvailable)
			record.put("baseline_note",
					"This encounter began before initial state was recorded. Initial roster, resource and map-state deltas are unknown.");
		record.put("started_seconds", baseline.get("seconds"));
		record.put("ended_seconds", after.get("seconds"));
		if (baseline.get("seconds") instanceof Number start)
			record.put("elapsed_seconds", ((Number) after.get("seconds")).longValue() - start.longValue());
		record.put("final_roster", after.get("roster"));
		if (after.containsKey("position_status")) {
			record.put("position_status", after.get("position_status"));
			// Keep these frozen observations separate from the compact ledger projection.
			record.put("party_final_positions", after.get("party_final_positions"));
			record.put("party_owned_refs_at_end", after.get("party_owned_refs_at_end"));
		}
		if (baselineAvailable) {
			record.put("initial_roster", baseline.get("roster"));
			record.put("resources", differences(SceneService.maps(baseline.get("resources")),
					SceneService.maps(after.get("resources")), "key"));
			record.put("map_changes", differences(SceneService.maps(baseline.get("map_objects")),
					SceneService.maps(after.get("map_objects")), "key"));
		}
		record.put("xp", Map.of("pool", xpPool, "awards", awards, "total",
				awards.stream().mapToLong(a -> ((Number) a.get("xp_gained")).longValue()).sum()));
		record.put("loot", Map.of("tool", "grant_loot", "source", "ENCOUNTER", "source_ref",
				Ref.of(Ref.ENCOUNTER, encounter.id()), "automatic", false));
		List<Row> logs = tx.query("SELECT * FROM encounter_log WHERE encounter_id=? ORDER BY id", encounter.id());
		var facts = new LinkedHashMap<String, List<Map<String, Object>>>();
		for (String key : List.of("drops_to_zero", "deaths", "knockouts", "healing_and_stabilization", "captures",
				"flights", "critical_hits", "spells", "concentration_ended"))
			facts.put(key, new ArrayList<>());
		var scored = new ArrayList<Map<String, Object>>();
		List<Row> hits = logs.stream().filter(row -> Boolean.TRUE.equals(row.map("payload_json").get("hit"))).toList();
		Set<Long> boundaryHits = new HashSet<>();
		if (!hits.isEmpty()) {
			boundaryHits.add(hits.getFirst().id());
			boundaryHits.add(hits.getLast().id());
		}
		for (Row row : logs) {
			Map<String, Object> payload = row.map("payload_json");
			var entry = logFact(row);
			Map<String, Object> result = SceneService.map(payload.get("result"));
			Map<String, Object> mechanics = result.isEmpty() ? payload : result;
			entry.put("mechanics", mechanicalResult(mechanics));
			int score = collectFacts(facts, row, mechanics, entry);
			if ("CAST".equals(row.str("kind"))) {
				facts.get("spells").add(entry);
				score = Math.max(score, 60);
			}
			if ("DEATH_SAVE".equals(row.str("kind"))
					&& "DEAD".equals(SceneService.map(payload.get("actor_after")).get("life_state"))) {
				var death = new LinkedHashMap<>(entry);
				death.put("cause", "THREE_FAILED_DEATH_SAVES");
				facts.get("deaths").add(death);
				score = 100;
			}
			if (boundaryHits.contains(row.id()))
				score = Math.max(score, 30);
			if (score > 0) {
				var highlight = new LinkedHashMap<>(entry);
				highlight.put("score", score);
				highlight.put("slot", "log:" + row.id());
				highlight.put("narration", row.str("narration") == null ? "" : row.str("narration"));
				scored.add(highlight);
			}
		}
		facts.forEach(record::put);
		if (Set.of("PARTY_FLED", "ENEMIES_FLED").contains(outcome))
			facts.get("flights").add(Map.of("outcome", outcome, "scope", "ENCOUNTER_OUTCOME"));
		var highlights = scored.stream()
				.sorted(Comparator.<Map<String, Object>> comparingInt(h -> ((Number) h.get("score")).intValue())
						.reversed().thenComparingLong(h -> ((Number) h.get("log_id")).longValue()))
				.limit(MAX_HIGHLIGHTS).sorted(Comparator.comparingLong(h -> ((Number) h.get("log_id")).longValue()))
				.map(h -> {
					var copy = new LinkedHashMap<>(h);
					copy.remove("score");
					return (Map<String, Object>) copy;
				}).toList();
		record.put("highlights", highlights);
		if (narrative != null && !narrative.isBlank()) {
			record.put("narrative", clipped(narrative, 4000));
			if (narrative.trim().length() > 4000)
				record.put("narrative_note", "Narrative trimmed to 4000 characters.");
		}
		Set<Long> partyKnown = known(tx, encounter);
		for (Object ref : list(after.get("party_owned_refs_at_end"))) {
			Long id = characterId(ref);
			if (id != null)
				partyKnown.add(id);
		}
		for (Row row : logs)
			for (Object ref : list(row.map("payload_json").get("party_known"))) {
				Long id = characterId(ref);
				if (id != null)
					partyKnown.add(id);
			}
		record.put("party_known_refs", partyKnown.stream().map(id -> Ref.of(Ref.CHARACTER, id)).toList());
		record.put("party_initial_refs", baseline.getOrDefault("known_refs", List.of()));
		record.put("party_current_refs", after.getOrDefault("known_refs", List.of()));
		String partySide = String.valueOf(encounter.map("sides_json").get("party_side"));
		record.put("party_resource_refs",
				SceneService.maps(after.get("roster")).stream().filter(person -> partySide.equals(person.get("side")))
						.map(person -> person.get("character")).toList());
		Set<Object> changedObjects = new HashSet<>();
		SceneService.maps(record.get("map_changes")).forEach(change -> changedObjects.add(change.get("key")));
		record.put("party_map_changes",
				differences(SceneService.maps(baseline.get("party_map_objects")),
						SceneService.maps(after.get("party_map_objects")), "key").stream()
						.filter(change -> changedObjects.contains(change.get("key"))).toList());
		var privacyTerms = new LinkedHashSet<>(privateTerms(tx, encounter, partyKnown, baseline));
		privacyTerms.addAll(privateTerms(tx, encounter, partyKnown, after));
		record.put("privacy_terms", List.copyOf(privacyTerms));
		record.put("party_digest", partyProjection(record));
		return record;
	}

	static Map<String, Object> annotate(Tx tx, Row encounter, Map<String, String> narrations) {
		if (!"ENDED".equals(encounter.str("status")) || encounter.map("record_json").get("resolved_event") == null)
			throw RpgException.notAllowed("End this encounter before annotating its selected highlights.");
		if (narrations == null || narrations.isEmpty() || narrations.size() > MAX_HIGHLIGHTS)
			throw RpgException.invalidArgument("Supply 1–8 selected highlight slots and their narration.");
		var record = new LinkedHashMap<>(encounter.map("record_json"));
		var highlights = new ArrayList<Map<String, Object>>(SceneService.maps(record.get("highlights")));
		var notes = new ArrayList<String>();
		for (var annotation : narrations.entrySet()) {
			Map<String, Object> highlight = highlights.stream().filter(h -> annotation.getKey().equals(h.get("slot")))
					.findFirst().orElseThrow(() -> RpgException
							.invalidArgument("Not a selected highlight in this fight: " + annotation.getKey()));
			if (annotation.getValue() == null || annotation.getValue().isBlank())
				throw RpgException.invalidArgument("Narration is required for " + annotation.getKey());
			Row log = tx.get("encounter_log", ((Number) highlight.get("log_id")).longValue());
			if (log.lng("encounter_id") != encounter.id())
				throw RpgException.invalidArgument("Highlight log belongs to another encounter.");
			String narration = clipped(annotation.getValue(), NARRATION_LIMIT);
			highlight.put("narration", narration);
			tx.update("encounter_log", log.id(), Map.of("narration", narration));
			if (annotation.getValue().trim().length() > NARRATION_LIMIT)
				notes.add(annotation.getKey() + " narration trimmed to 300 characters.");
		}
		record.put("highlights", highlights);
		record.put("party_digest", partyProjection(record));
		long eventId = Ref.id(record.get("resolved_event").toString(), Ref.EVENT);
		Row event = tx.get("event", eventId);
		if (!Objects.equals(event.lng("campaign_id"), encounter.lng("campaign_id"))
				|| !Ref.of(Ref.ENCOUNTER, encounter.id()).equals(event.map("payload_json").get("encounter")))
			throw RpgException.invalidArgument("Resolved event does not belong to this encounter.");
		var payload = new LinkedHashMap<>(event.map("payload_json"));
		payload.put("digest", record.get("party_digest"));
		tx.update("event", eventId, Map.of("payload_json", payload, "episodic_detail",
				story(SceneService.map(record.get("party_digest")))));
		tx.update("encounter", encounter.id(),
				Map.of("record_json", record, "revision", encounter.lng("revision") + 1));
		var response = new LinkedHashMap<String, Object>();
		response.put("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()));
		response.put("record", gmRecord(record));
		response.put("resolved_event", record.get("resolved_event"));
		response.put("notes", notes);
		return response;
	}

	public static Map<String, Object> gmRecord(Map<String, Object> stored) {
		var result = new LinkedHashMap<>(stored);
		for (String key : List.of("baseline", "party_digest", "party_known_refs", "party_initial_refs",
				"party_current_refs", "party_resource_refs", "party_map_changes", "party_final_positions",
				"party_owned_refs_at_end", "privacy_terms"))
			result.remove(key);
		return result;
	}

	static List<Map<String, Object>> annotationSlots(Map<String, Object> record) {
		var slots = new ArrayList<Map<String, Object>>();
		for (var highlight : SceneService.maps(record.get("highlights"))) {
			if (!String.valueOf(highlight.getOrDefault("narration", "")).isBlank())
				continue;
			var slot = new LinkedHashMap<String, Object>();
			for (String key : List.of("slot", "round", "kind", "actor", "target"))
				if (highlight.get(key) != null)
					slot.put(key, highlight.get(key));
			if (highlight.get("summary") instanceof String summary)
				slot.put("summary", briefSummary(summary, 120));
			slot.put("narration", "");
			slots.add(slot);
			if (slots.size() == MAX_HIGHLIGHTS)
				break;
		}
		while (Json.utf8(Json.write(slots)).length > ANNOTATION_SLOTS_MAX_BYTES) {
			Map<String, Object> longest = slots.stream().filter(slot -> slot.get("summary") instanceof String)
					.max(Comparator.comparingInt(slot -> Json.utf8(String.valueOf(slot.get("summary"))).length))
					.orElse(null);
			if (longest == null)
				break; // Engine-generated slot/ref/kind identifiers fit without prose.
			String summary = longest.get("summary").toString();
			int remaining = summary.codePointCount(0, summary.length()) - 16;
			if (remaining <= 0)
				longest.remove("summary");
			else
				longest.put("summary", briefSummary(summary, remaining));
		}
		return slots;
	}

	private static String briefSummary(String text, int codePoints) {
		String trimmed = text.trim();
		return trimmed.substring(0,
				trimmed.offsetByCodePoints(0, Math.min(codePoints, trimmed.codePointCount(0, trimmed.length()))));
	}

	private static Map<String, Object> mechanicalResult(Map<String, Object> result) {
		var copy = new LinkedHashMap<>(result);
		for (String key : List.of("state", "meta", "gm_handoff", "pending_choices", "legal_actions", "narration"))
			copy.remove(key);
		return copy;
	}

	private static Map<String, Object> snapshot(Tx tx, Row encounter, boolean finalPositions) {
		var snapshot = new LinkedHashMap<String, Object>();
		snapshot.put("seconds", GameTime.currentSeconds(tx, encounter.lng("campaign_id")));
		var roster = new ArrayList<Map<String, Object>>();
		var resources = new ArrayList<Map<String, Object>>();
		Row scene = GridCombat.isGrid(encounter) ? GridCombat.scene(tx, encounter) : null;
		var actors = new LinkedHashMap<Long, Row>();
		if (scene != null && finalPositions)
			for (Row actor : tx.query("SELECT * FROM map_actor WHERE scene_id=?", scene.id()))
				actors.put(actor.lng("character_id"), actor);
		String partySide = String.valueOf(encounter.map("sides_json").get("party_side"));
		List<Row> observers = tx
				.query("SELECT character_id FROM encounter_participant WHERE encounter_id=? AND side=?", encounter.id(),
						partySide)
				.stream().map(p -> actors.get(p.lng("character_id"))).filter(Objects::nonNull)
				.filter(actor -> "ALIVE".equals(tx.get("character", actor.lng("character_id")).str("life_state")))
				.toList();
		var partyPositions = new ArrayList<Map<String, Object>>();
		Set<Long> owned = finalPositions ? partyOwnedAtEnd(tx, encounter) : Set.of();
		for (Row participant : tx.query("SELECT * FROM encounter_participant WHERE encounter_id=? ORDER BY id",
				encounter.id())) {
			long id = participant.lng("character_id");
			var person = character(tx, id);
			person.put("side", participant.str("side"));
			person.put("status", participant.str("status"));
			if (scene != null && finalPositions) {
				Row actor = actors.get(id);
				person.put("position_status", actor == null ? "NOT_RECORDED" : "ENCOUNTER_END");
				if (actor != null) {
					var position = new LinkedHashMap<String, Object>();
					position.put("character", Ref.of(Ref.CHARACTER, id));
					position.put("map_ref", Ref.of(Ref.MAP, scene.id()));
					position.put("map_name", scene.str("name"));
					position.put("level", actor.intOr("level", 0));
					position.put("move_origin_ft", SceneService.feet(SceneService.footprint(actor).origin()));
					position.put("position_ft", position.get("move_origin_ft"));
					position.put("footprint_ft", actor.get("footprint_ft"));
					person.putAll(position);
					if (owned.contains(id)
							|| observers.stream().anyMatch(observer -> SceneService.canSee(tx, scene, observer, actor)))
						partyPositions.add(position);
				}
			}
			roster.add(person);
			for (Row resource : tx.query("SELECT * FROM resource_state WHERE character_id=? ORDER BY resource_ref", id))
				resources.add(Map.of("key", Ref.of(Ref.CHARACTER, id) + "/" + resource.str("resource_ref"), "character",
						Ref.of(Ref.CHARACTER, id), "resource", resource.str("resource_ref"), "current",
						resource.lng("current"), "max", resource.lng("max")));
			for (Row inventory : tx.query(
					"WITH RECURSIVE carried(id) AS (SELECT id FROM inventory_entry WHERE character_id=? "
							+ "UNION SELECT item.id FROM inventory_entry item JOIN carried parent ON item.container_entry_id=parent.id) "
							+ "SELECT item.* FROM inventory_entry item JOIN carried ON carried.id=item.id ORDER BY item.id",
					id))
				resources.add(Map.of("key", Ref.of(Ref.INVENTORY, inventory.id()), "character",
						Ref.of(Ref.CHARACTER, id), "resource",
						inventory.isNull("content_ref") ? Ref.of(Ref.CONTENT, inventory.lng("custom_content_id"))
								: inventory.str("content_ref"),
						"current", inventory.lng("quantity")));
		}
		snapshot.put("roster", roster);
		snapshot.put("resources", resources);
		Set<Long> known = new LinkedHashSet<>(GridCombat.knownToParty(tx, encounter));
		known.addAll(owned);
		snapshot.put("known_refs", known.stream().map(id -> Ref.of(Ref.CHARACTER, id)).toList());
		if (GridCombat.isGrid(encounter)) {
			if (finalPositions) {
				snapshot.put("position_status", "ENCOUNTER_END");
				snapshot.put("party_final_positions", partyPositions);
				snapshot.put("party_owned_refs_at_end", owned.stream().map(id -> Ref.of(Ref.CHARACTER, id)).toList());
			}
			snapshot.put("map_objects", mapObjects(SceneService.document(tx, scene)));
			snapshot.put("party_map_objects",
					mapObjects(SceneService.projection(tx, scene, "PLAYER", scene.intOr("level", 0))));
		}
		return snapshot;
	}

	private static Set<Long> partyOwnedAtEnd(Tx tx, Row encounter) {
		long campaignId = encounter.lng("campaign_id");
		var controlled = new HashSet<Long>();
		tx.query("SELECT character_id FROM player_control_assignment WHERE campaign_id=? AND active=1", campaignId)
				.forEach(row -> controlled.add(row.lng("character_id")));
		var memberships = new LinkedHashMap<Long, String>();
		tx.query("SELECT character_id,state FROM party_membership WHERE campaign_id=? ORDER BY id", campaignId)
				.forEach(row -> memberships.put(row.lng("character_id"), row.str("state")));
		String partySide = String.valueOf(encounter.map("sides_json").get("party_side"));
		var owned = new LinkedHashSet<Long>();
		for (Row participant : tx.query("SELECT character_id,side FROM encounter_participant WHERE encounter_id=?",
				encounter.id())) {
			long id = participant.lng("character_id");
			String membership = memberships.getOrDefault(id, "NON_PARTY");
			if (controlled.contains(id) || Set.of("ACTIVE", "GUEST", "SEPARATED").contains(membership)
					|| "DEAD".equals(membership) && partySide.equals(participant.str("side"))
							&& "DEAD".equals(tx.get("character", id).str("life_state")))
				owned.add(id);
		}
		return owned;
	}

	private static Map<String, Object> character(Tx tx, long id) {
		Row c = tx.get("character", id);
		var value = new LinkedHashMap<String, Object>();
		value.put("character", Ref.of(Ref.CHARACTER, id));
		for (String key : List.of("name", "life_state", "current_hp", "temp_hp", "exhaustion"))
			value.put(key, c.get(key));
		value.put("death_saves", c.map("death_saves_json"));
		value.put("conditions", tx.query(
				"SELECT condition_ref FROM active_effect WHERE character_id=? AND condition_ref IS NOT NULL ORDER BY id",
				id).stream().map(effect -> effect.str("condition_ref")).toList());
		return value;
	}

	private static void recordRuntimeCare(Tx tx, Row encounter, Map<String, Object> baseline) {
		if (!(baseline.get("journal_id") instanceof Number start))
			return;
		Set<Long> participants = new HashSet<>();
		tx.query("SELECT character_id FROM encounter_participant WHERE encounter_id=?", encounter.id())
				.forEach(row -> participants.add(row.lng("character_id")));
		for (Row journal : tx.query(
				"SELECT * FROM journal_entry WHERE campaign_id=? AND id>? AND id<? "
						+ "AND operation='apply_runtime_change' ORDER BY id",
				encounter.lng("campaign_id"), start.longValue(), tx.journalId())) {
			Map<String, Object> result = journal.map("result_json");
			Long target = characterId(result.get("character"));
			if (target == null || !participants.contains(target)
					|| !Set.of("HEAL", "DAMAGE", "STABILIZE", "USE_ITEM").contains(result.get("kind")))
				continue;
			var payload = new LinkedHashMap<String, Object>();
			payload.put("target", target);
			payload.put("result", mechanicalResult(result));
			payload.put("source_journal", journal.id());
			payload.put("care_provider", "NOT_RECORDED");
			EncounterService.log(tx, encounter.lng("campaign_id"), encounter.id(), encounter.lng("round"), null,
					"RUNTIME_" + result.get("kind"),
					"Recorded " + result.get("kind") + " for " + tx.get("character", target).str("name") + ".",
					payload);
		}
	}

	private static List<Map<String, Object>> mapObjects(Map<String, Object> document) {
		var entries = new ArrayList<Map<String, Object>>();
		for (String layer : List.of("doors", "objects"))
			for (var object : SceneService.maps(document.get(layer))) {
				if (object.get("key") == null)
					continue;
				var saved = new LinkedHashMap<String, Object>();
				saved.put("key", layer + ":" + object.get("key"));
				for (String key : List.of("name", "label", "state", "locked", "sealed", "hit_points", "hp"))
					if (object.containsKey(key))
						saved.put(key, object.get(key));
				entries.add(saved);
			}
		return entries;
	}

	private static List<Map<String, Object>> differences(
		List<Map<String, Object>> before, List<Map<String, Object>> after, String field) {
		var first = new LinkedHashMap<Object, Map<String, Object>>();
		var last = new LinkedHashMap<Object, Map<String, Object>>();
		before.forEach(row -> first.put(row.get(field), row));
		after.forEach(row -> last.put(row.get(field), row));
		var keys = new LinkedHashSet<>(first.keySet());
		keys.addAll(last.keySet());
		var changes = new ArrayList<Map<String, Object>>();
		for (Object key : keys)
			if (!Json.write(canonical(first.get(key))).equals(Json.write(canonical(last.get(key))))) {
				var change = new LinkedHashMap<String, Object>();
				change.put("key", key);
				change.put("before", first.get(key));
				change.put("after", last.get(key));
				changes.add(change);
			}
		return changes;
	}

	private static Object canonical(Object value) {
		if (value instanceof Number number)
			return new BigDecimal(number.toString()).stripTrailingZeros();
		if (value instanceof Map<?, ?> map) {
			var sorted = new TreeMap<String, Object>();
			map.forEach((key, child) -> sorted.put(key.toString(), canonical(child)));
			return sorted;
		}
		if (value instanceof Collection<?> collection)
			return collection.stream().map(EncounterDigest::canonical).toList();
		return value;
	}

	private static Map<String, Object> logFact(Row row) {
		var fact = new LinkedHashMap<String, Object>();
		fact.put("log_id", row.id());
		fact.put("round", row.lng("round"));
		fact.put("kind", row.str("kind"));
		fact.put("actor", Ref.ofNullable(Ref.CHARACTER, row.lng("actor_character_id")));
		fact.put("summary", row.str("summary"));
		fact.put("party_observed", Boolean.TRUE.equals(row.map("payload_json").get("party_observed")));
		Long target = characterId(row.map("payload_json").get("target"));
		if (target != null)
			fact.put("target", Ref.of(Ref.CHARACTER, target));
		return fact;
	}

	private static int collectFacts(
		Map<String, List<Map<String, Object>>> facts, Row row, Map<String, Object> mechanics,
		Map<String, Object> entry) {
		int score = 0;
		var found = new LinkedHashSet<String>();
		for (Map<String, Object> result : nestedMaps(mechanics)) {
			if (Boolean.TRUE.equals(result.get("dropped_to_zero"))
					|| result.get("hp_before") instanceof Number before && before.longValue() > 0
							&& result.get("hp_after") instanceof Number after && after.longValue() == 0)
				found.add("drops_to_zero");
			if (Boolean.TRUE.equals(result.get("died")))
				found.add("deaths");
			if (Boolean.TRUE.equals(result.get("knocked_out")))
				found.add("knockouts");
			if (Boolean.TRUE.equals(result.get("captured")) || Boolean.TRUE.equals(result.get("surrendered")))
				found.add("captures");
			if (Boolean.TRUE.equals(result.get("fled")))
				found.add("flights");
			if (Boolean.TRUE.equals(result.get("critical")) && Boolean.TRUE.equals(result.get("hit")))
				found.add("critical_hits");
			if (result.get("healed") instanceof Number healed && healed.longValue() > 0
					|| Boolean.TRUE.equals(result.get("stabilized"))
					|| "STABILIZE".equals(result.get("kind")) && Boolean.TRUE.equals(result.get("stable")))
				found.add("healing_and_stabilization");
			Map<String, Object> concentration = SceneService.map(result.get("concentration"));
			if (Boolean.FALSE.equals(concentration.get("kept")) || concentration.containsKey("ended"))
				found.add("concentration_ended");
			if (result.get("concentration_ended") instanceof Collection<?> ended && !ended.isEmpty())
				found.add("concentration_ended");
		}
		for (String key : found) {
			facts.get(key).add(entry);
			score = Math.max(score, switch (key) {
			case "deaths" -> 100;
			case "drops_to_zero", "knockouts", "captures", "flights" -> 90;
			case "critical_hits", "concentration_ended" -> 80;
			default -> 50;
			});
		}
		return score;
	}

	private static List<Map<String, Object>> nestedMaps(Object value) {
		var result = new ArrayList<Map<String, Object>>();
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> typed = SceneService.map(map);
			result.add(typed);
			map.values().forEach(child -> result.addAll(nestedMaps(child)));
		} else if (value instanceof Collection<?> list)
			list.forEach(child -> result.addAll(nestedMaps(child)));
		return result;
	}

	private static Set<Long> known(Tx tx, Row encounter) {
		var known = new LinkedHashSet<Long>();
		for (Row p : tx.query("SELECT character_id FROM encounter_participant WHERE encounter_id=?", encounter.id()))
			if (GridCombat.nameKnownToParty(tx, encounter, p.lng("character_id")))
				known.add(p.lng("character_id"));
		return known;
	}

	private static List<String> privateTerms(Tx tx, Row encounter, Set<Long> known, Map<String, Object> snapshot) {
		var terms = new LinkedHashSet<String>();
		for (Row p : tx.query("SELECT character_id FROM encounter_participant WHERE encounter_id=?", encounter.id()))
			if (!known.contains(p.lng("character_id"))) {
				terms.add(Ref.of(Ref.CHARACTER, p.lng("character_id")));
				terms.add(tx.get("character", p.lng("character_id")).str("name"));
			}
		Set<Object> visible = new HashSet<>();
		SceneService.maps(snapshot.get("party_map_objects")).forEach(object -> visible.add(object.get("key")));
		for (var object : SceneService.maps(snapshot.get("map_objects")))
			if (!visible.contains(object.get("key")))
				for (String key : List.of("key", "name", "label"))
					if (object.get(key) instanceof String term && !term.isBlank()) {
						terms.add(term);
						if ("key".equals(key) && term.contains(":"))
							terms.add(term.substring(term.indexOf(':') + 1));
					}
		terms.remove(null);
		return List.copyOf(terms);
	}

	private static Map<String, Object> partyProjection(Map<String, Object> record) {
		var publicRecord = new LinkedHashMap<String, Object>();
		Set<String> known = new HashSet<>(list(record.get("party_known_refs")).stream().map(Object::toString).toList());
		List<String> privateTerms = list(record.get("privacy_terms")).stream().map(Object::toString).toList();
		for (String key : List.of("encounter", "outcome", "rounds", "elapsed_seconds", "started_seconds",
				"ended_seconds", "baseline_status", "baseline_note", "xp", "loot"))
			if (record.containsKey(key))
				publicRecord.put(key, record.get(key));
		Set<String> initial = new HashSet<>(
				list(record.get("party_initial_refs")).stream().map(Object::toString).toList());
		Set<String> current = new HashSet<>(
				list(record.get("party_current_refs")).stream().map(Object::toString).toList());
		if (record.containsKey("initial_roster"))
			publicRecord.put("initial_roster", SceneService.maps(record.get("initial_roster")).stream()
					.filter(row -> initial.contains(row.get("character"))).toList());
		publicRecord.put("final_roster", SceneService.maps(record.get("final_roster")).stream()
				.filter(row -> known.contains(row.get("character")))
				.map(row -> current.contains(row.get("character")) ? row : Map.of("character", row.get("character"),
						"name", row.get("name"), "side", row.get("side"), "last_known", true))
				.toList());
		for (String key : List.of("drops_to_zero", "deaths", "knockouts", "healing_and_stabilization", "captures",
				"flights", "critical_hits", "spells", "concentration_ended", "highlights"))
			publicRecord.put(key,
					SceneService.maps(record.get(key)).stream().filter(row -> publicFact(row, known, privateTerms))
							.map(row -> publicHighlight(row, privateTerms)).toList());
		Set<String> resourceOwners = new HashSet<>(
				list(record.get("party_resource_refs")).stream().map(Object::toString).toList());
		if (record.containsKey("resources"))
			publicRecord.put("resources", SceneService.maps(record.get("resources")).stream().filter(row -> {
				Map<String, Object> owner = SceneService.map(row.get("after"));
				if (owner.isEmpty())
					owner = SceneService.map(row.get("before"));
				return resourceOwners.contains(owner.get("character"));
			}).toList());
		if (record.containsKey("map_changes"))
			publicRecord.put("map_changes", record.getOrDefault("party_map_changes", List.of()));
		for (String key : List.of("summary", "narrative"))
			if (record.get(key) instanceof String prose
					&& privateTerms.stream().noneMatch(term -> containsTerm(prose, term)))
				publicRecord.put(key, prose);
		return compactPublic(publicRecord);
	}

	private static boolean publicFact(Map<String, Object> row, Set<String> known, List<String> privateTerms) {
		if (Boolean.FALSE.equals(row.get("party_observed")))
			return false;
		if (row.get("actor") != null && !known.contains(row.get("actor")))
			return false;
		if (row.get("target") != null && !known.contains(row.get("target")))
			return false;
		return !containsPrivateTerm(row, privateTerms);
	}

	private static boolean containsPrivateTerm(Object value, List<String> terms) {
		if (value instanceof String text)
			return terms.stream().anyMatch(term -> containsTerm(text, term));
		if (value instanceof Map<?, ?> map)
			return map.entrySet().stream().anyMatch(entry -> containsPrivateTerm(entry.getKey(), terms)
					|| containsPrivateTerm(entry.getValue(), terms));
		if (value instanceof Collection<?> collection)
			return collection.stream().anyMatch(child -> containsPrivateTerm(child, terms));
		return false;
	}

	private static Map<String, Object> publicHighlight(Map<String, Object> row, List<String> privateTerms) {
		var copy = new LinkedHashMap<String, Object>();
		for (String key : List.of("slot", "log_id", "round", "kind", "actor", "target", "cause", "scope", "outcome"))
			if (row.get(key) != null)
				copy.put(key, row.get(key));
		if (row.get("summary") instanceof String summary)
			copy.put("summary", clipped(summary, 200));
		if (row.get("narration") instanceof String narration && !narration.isBlank())
			copy.put("narration", clipped(narration, NARRATION_LIMIT));
		return copy;
	}

	/**
	 * Compact already-filtered material, also suitable for older ledger digests read after upgrade.
	 */
	public static Map<String, Object> compactPublic(Map<String, Object> digest) {
		var compact = new LinkedHashMap<String, Object>();
		for (String key : List.of("encounter", "outcome", "rounds", "elapsed_seconds", "baseline_status",
				"baseline_note", "loot"))
			if (digest.get(key) != null)
				compact.put(key, digest.get(key));
		var roster = new ArrayList<Map<String, Object>>();
		for (var person : SceneService.maps(digest.get("final_roster"))) {
			var known = new LinkedHashMap<String, Object>();
			for (String key : List.of("character", "name", "side", "status", "life_state", "last_known"))
				if (person.get(key) != null)
					known.put(key, person.get(key));
			roster.add(known);
		}
		compact.put("final_roster", roster);
		compact.put("highlights", SceneService.maps(digest.get("highlights")).stream().limit(MAX_HIGHLIGHTS)
				.map(row -> publicHighlight(row, List.of())).toList());
		for (String key : List.of("drops_to_zero", "deaths", "knockouts", "healing_and_stabilization", "captures",
				"flights", "critical_hits", "spells", "concentration_ended")) {
			List<Map<String, Object>> facts = SceneService.maps(digest.get(key));
			if (!facts.isEmpty())
				compact.put(key, facts.stream().map(row -> publicHighlight(row, List.of())).toList());
		}
		for (String key : List.of("resources", "map_changes"))
			if (digest.containsKey(key))
				compact.put(key, digest.get(key));
		Map<String, Object> xp = SceneService.map(digest.get("xp"));
		if (!xp.isEmpty()) {
			var awards = new ArrayList<Map<String, Object>>();
			for (var award : SceneService.maps(xp.get("awards"))) {
				var gained = new LinkedHashMap<String, Object>();
				for (String key : List.of("character", "xp_gained"))
					if (award.get(key) != null)
						gained.put(key, award.get(key));
				awards.add(gained);
			}
			compact.put("xp", Map.of("total", xp.get("total") == null ? 0 : xp.get("total"), "awards", awards));
		}
		if (digest.get("summary") instanceof String summary && !summary.isBlank())
			compact.put("summary", clipped(summary, 300));
		if (digest.get("narrative") instanceof String narrative && !narrative.isBlank())
			compact.put("narrative", clipped(narrative, 1200));
		return compact;
	}

	/** Human prose for chronicle material; detailed rolls remain in the GM encounter record. */
	public static String story(Map<String, Object> publicDigest) {
		Map<String, Object> digest = compactPublic(publicDigest);
		var text = new StringBuilder("Encounter ").append(
				String.valueOf(digest.getOrDefault("outcome", "OTHER")).toLowerCase(Locale.ROOT).replace('_', ' '))
				.append("; ").append(digest.getOrDefault("rounds", "?")).append(" rounds");
		if (digest.get("elapsed_seconds") != null)
			text.append(", ").append(digest.get("elapsed_seconds")).append(" seconds");
		text.append('.');
		for (String key : List.of("summary", "narrative"))
			if (digest.get(key) instanceof String prose && !prose.isBlank())
				text.append('\n').append(prose);
		List<Map<String, Object>> roster = SceneService.maps(digest.get("final_roster"));
		if (!roster.isEmpty()) {
			text.append("\nKnown roster: ");
			text.append(String.join("; ",
					roster.stream().map(person -> String.valueOf(person.get("name")) + " (" + person.get("side") + "): "
							+ (Boolean.TRUE.equals(person.get("last_known")) ? "last seen"
									: person.getOrDefault("life_state", person.getOrDefault("status", "recorded"))))
							.toList()))
					.append('.');
		}
		for (var highlight : SceneService.maps(digest.get("highlights"))) {
			text.append("\nRound ").append(highlight.getOrDefault("round", "? ")).append(": ")
					.append(highlight.getOrDefault("summary", highlight.getOrDefault("kind", "Recorded highlight")));
			if (highlight.get("narration") instanceof String narration && !narration.isBlank())
				text.append(" — “").append(narration).append('”');
		}
		List<Map<String, Object>> resources = SceneService.maps(digest.get("resources"));
		if (!resources.isEmpty())
			text.append("\nResources: ").append(String.join("; ", resources.stream().map(change -> {
				Map<String, Object> before = SceneService.map(change.get("before")),
						after = SceneService.map(change.get("after"));
				Map<String, Object> entry = after.isEmpty() ? before : after;
				return entry.getOrDefault("character", "recorded actor") + " "
						+ entry.getOrDefault("resource", change.get("key")) + ": "
						+ before.getOrDefault("current", "absent") + " → " + after.getOrDefault("current", "absent");
			}).toList())).append('.');
		List<Map<String, Object>> changes = SceneService.maps(digest.get("map_changes"));
		if (!changes.isEmpty())
			text.append("\nMap changes: ").append(String.join("; ", changes.stream().map(change -> {
				Map<String, Object> before = SceneService.map(change.get("before")),
						after = SceneService.map(change.get("after"));
				Map<String, Object> entry = after.isEmpty() ? before : after;
				return entry.getOrDefault("name", entry.getOrDefault("label", change.get("key"))) + ": "
						+ before.getOrDefault("state", "recorded") + " → " + after.getOrDefault("state", "removed");
			}).toList())).append('.');
		text.append("\nXP awarded: ").append(SceneService.map(digest.get("xp")).getOrDefault("total", 0)).append('.');
		text.append("\nLoot is recorded separately through grant_loot with source ENCOUNTER.");
		return clipped(text.toString(), 6000);
	}

	private static Long characterId(Object value) {
		if (value instanceof Number number)
			return number.longValue();
		if (value instanceof String ref && ref.startsWith("character:"))
			return Ref.id(ref, Ref.CHARACTER);
		return null;
	}

	private static List<?> list(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	private static String clipped(String text, int maximum) {
		String trimmed = text.trim();
		return trimmed.length() <= maximum ? trimmed : trimmed.substring(0, maximum);
	}

	private static boolean containsTerm(String text, String term) {
		return !term.isBlank() && text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
	}
}
