/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg.world;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.maps.GridCombat;
import se.hirt.mcp.rpg.rules.Combat;
import se.hirt.mcp.rpg.rules.Exhaustion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Continuous graph journeys with saved risk checks and DM-directed encounter opportunities. */
public final class TravelJourneyService {

	private final Database db;
	private final RollService roller;
	private final RulesData rules;

	public TravelJourneyService(Database db, RollService roller) {
		this(db, new RulesData(db), roller);
	}

	public TravelJourneyService(Database db, RulesData rules, RollService roller) {
		this.db = db;
		this.rules = rules;
		this.roller = roller;
	}

	public Map<String, Object> defineRisk(String op, String campaignRef, String scopeRef, Map<String, Object> policy) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(
				Database.Mutation.of("define_travel_risk", cid, op, "GM", Map.of("scope", scopeRef, "policy", policy)),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "define_travel_risk");
					if (!"DEFAULT".equals(scopeRef)) {
						Ref scope = Ref.parse(scopeRef);
						if (scope.type().equals(Ref.LOCATION))
							WorldService.location(tx, cid, scopeRef);
						else if (scope.type().equals(Ref.CONNECTION)) {
							if (tx.get("location_connection", scope.id()).lng("campaign_id") != cid)
								throw RpgException.invalidArgument("Risk scope belongs to another campaign.");
						} else
							throw RpgException
									.invalidArgument("Risk scope must be DEFAULT, location:N or connection:N.");
					}
					validatePolicy(policy);
					Row existing = tx
							.queryOne("SELECT * FROM travel_risk WHERE campaign_id=? AND scope_ref=?", cid, scopeRef)
							.orElse(null);
					long id;
					if (existing == null)
						id = tx.insert("travel_risk",
								Map.of("campaign_id", cid, "scope_ref", scopeRef, "policy_json", Json.write(policy)));
					else {
						id = existing.id();
						tx.update("travel_risk", id,
								Map.of("policy_json", Json.write(policy), "revision", existing.lng("revision") + 1));
					}
					return Map.of("risk", "travel_risk:" + id, "scope", scopeRef, "policy", policy, "meta",
							Harness.meta(campaign, null));
				});
	}

	public Map<String, Object> state(String campaignRef) {
		return db.read(tx -> {
			long cid = Harness.campaign(tx, campaignRef).id();
			Row row = current(tx, cid);
			return row == null ? Map.of("active", false) : view(tx, row);
		});
	}

	public static Map<String, Object> stateInTx(Tx tx, long campaignId) {
		Row row = current(tx, campaignId);
		return row == null ? Map.of("active", false) : view(tx, row);
	}

	/**
	 * Omitted NPC locations follow the controlled player's place, not another companion's journey.
	 */
	public static Long controlledEffectiveLocation(Tx tx, long campaignId, Long fallback) {
		return controlledTravelling(tx, campaignId) ? effectiveLocation(tx, campaignId, fallback) : fallback;
	}

	public static boolean controlledTravelling(Tx tx, long campaignId) {
		Row journey = current(tx, campaignId);
		var controlled = tx.queryOne(
				"SELECT character_id AS id FROM player_control_assignment WHERE campaign_id=? AND active=1",
				campaignId);
		return journey != null && controlled.isPresent()
				&& roster(tx, journey, null).movers().contains(Ref.of(Ref.CHARACTER, controlled.get().id()));
	}

	/**
	 * A journey contact is a real derived place; "here" must not refer back to the departure inn.
	 */
	public static Long effectiveLocation(Tx tx, long campaignId, Long requested) {
		Row journey = current(tx, campaignId);
		if (journey == null)
			return requested;
		if (requested != null && !Objects.equals(requested, journey.lng("from_location_id")) && !Objects
				.equals(WorldService.travelAnchor(tx, campaignId, requested).id(), journey.lng("from_location_id")))
			return requested;
		Long savedContact = savedContactLocation(tx, journey);
		if (savedContact != null)
			return savedContact;
		Map<String, Object> context = encounterSceneDocument(tx, campaignId, requested);
		String key = String.valueOf(context.get("journey_scene_key"));
		var existing = tx.queryOne(
				"SELECT * FROM location WHERE campaign_id=? AND json_extract(features_json,'$[0].journey_scene_key')=? ORDER BY id LIMIT 1",
				campaignId, key);
		if (existing.isPresent())
			return existing.get().id();
		Long parent = null;
		if (!journey.isNull("from_location_id"))
			for (long ancestor : WorldService.lineage(tx, journey.lng("from_location_id"))) {
				Row location = tx.get("location", ancestor);
				if ("REGION".equals(location.str("kind"))) {
					parent = ancestor;
					break;
				}
			}
		String name = "Travel contact toward " + tx.get("location", journey.lng("to_location_id")).str("name");
		long id = WorldService.insertLocation(tx, campaignId, name, "AREA",
				"A saved position on the current journey; this place refines route progress, not the departure location.",
				parent, "MATERIALIZED", List.of("travel-contact"));
		tx.update("location", id,
				Map.of("features_json", Json.write(List.of(Map.of("kind", "TRAVEL_ANCHOR", "journey_scene_key", key,
						"journey_ref", "journey:" + journey.id(), "anchor", context.get("route_anchor"))))));
		return id;
	}

	public Map<String, Object> resolveOpportunity(String op, String campaignRef, String resolution) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(
				Database.Mutation.of("resolve_travel_opportunity", cid, op, "GM", Map.of("resolution", resolution)),
				tx -> {
					Harness.requireMutation(tx, campaignRef, "resolve_travel_opportunity");
					Row journey = current(tx, cid);
					if (journey == null || journey.map("opportunity_json").isEmpty())
						throw RpgException.notAllowed("There is no unresolved journey opportunity.");
					if (!List.of("RESOLVED", "AVOIDED", "NEGOTIATED", "CONTINUE").contains(resolution))
						throw RpgException
								.invalidArgument("Resolution must be RESOLVED, AVOIDED, NEGOTIATED or CONTINUE.");
					if (tx.count(
							"SELECT COUNT(*) FROM encounter WHERE campaign_id=? AND status IN ('RUNNING','WAITING_CHOICE')",
							cid) > 0)
						throw RpgException.notAllowed("Finish the active encounter before resuming travel.");
					var tactical = new LinkedHashMap<>(journey.map("tactical_anchor_json"));
					if (journey.map("opportunity_json").get("contact_location") instanceof String contact)
						tactical.put("contact_location", contact);
					tx.update("travel_journey", journey.id(), Map.of("opportunity_json", "{}", "status", "ACTIVE",
							"tactical_anchor_json", Json.write(tactical), "revision", journey.lng("revision") + 1));
					return view(tx, tx.get("travel_journey", journey.id()));
				});
	}

	/** Continue an existing journey for a bounded amount of actual travel time. */
	public Map<String, Object> advance(String op, String campaignRef, long seconds) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(Database.Mutation.of("continue_journey", cid, op, "GM", Map.of("seconds", seconds)), tx -> {
			Harness.requireMutation(tx, campaignRef, "continue_journey");
			Row j = current(tx, cid);
			if (j == null)
				throw RpgException.notAllowed("Begin a graph journey first.");
			return progress(tx, tx.get("campaign", cid), j, seconds, rules, roller);
		});
	}

	static Map<String, Object> resumeInTx(
		Tx tx, Row campaign, Row destination, List<String> selected, RulesData rules, RollService roller) {
		Row journey = current(tx, campaign.id());
		if (journey == null)
			return null;
		if (journey.lng("to_location_id") != destination.id())
			throw RpgException.notAllowed("A saved journey toward "
					+ tx.get("location", journey.lng("to_location_id")).str("name")
					+ " is still in progress. Resolve any pending travel opportunity, then resume that destination. To leave an incapacitated companion behind, call move_party to the saved destination with characters containing only the saved travelers who will continue; this preserves progress and risk checks. A different destination cannot turn this saved journey around.")
					.withDetail("saved_destination", Ref.of(Ref.LOCATION, journey.lng("to_location_id")))
					.withDetail("saved_travelers", roster(tx, journey, null).movers());
		return progress(tx, campaign, journey, WorldTimeline.MAX_ADVANCE_SECONDS, rules, roller, selected);
	}

	/**
	 * Reconcile a contact's saved travelers without moving figures, spending time or rolling risk.
	 */
	public static Map<String, Object> reconcileInTx(Tx tx, long campaignId) {
		Row journey = current(tx, campaignId);
		if (journey == null)
			return Map.of();
		Roster roster = roster(tx, journey, null);
		return roster.movers().isEmpty() ? cancelEmptyJourney(tx, journey, roster)
				: view(tx, saveRoster(tx, journey, roster));
	}

	private record Roster(List<String> movers, List<Map<String, Object>> removed) {
	}

	private static Roster roster(Tx tx, Row journey, List<String> selected) {
		List<String> saved = journey.list("movers_json").stream().map(String::valueOf).toList();
		if (selected != null) {
			if (selected.isEmpty())
				throw RpgException.invalidArgument(
						"Select at least one saved traveler in characters; an empty selection cannot resume a journey.");
			for (String ref : selected)
				if (!saved.contains(ref))
					throw RpgException.invalidArgument(ref
							+ " is not a saved traveler. A resumed journey can narrow its travelers, but cannot add someone from another place.")
							.withDetail("saved_travelers", saved);
		}
		var retained = new LinkedHashSet<String>();
		var removed = new ArrayList<Map<String, Object>>();
		for (String ref : saved) {
			Row character = CharacterService.character(tx, journey.lng("campaign_id"), ref);
			String reason = null;
			if ("DEAD".equals(character.str("life_state")))
				reason = "DEAD";
			else if (!"ACTIVE".equals(character.str("lifecycle")))
				reason = "INACTIVE";
			else if (!WorldService.travelMember(tx, journey.lng("campaign_id"), character.id()))
				reason = "NO_CURRENT_PARTY_MEMBERSHIP";
			else if (selected != null && !selected.contains(ref))
				reason = "EXPLICIT_SELECTION";
			if (reason == null)
				retained.add(ref);
			else {
				var removal = new LinkedHashMap<String, Object>();
				removal.put("character", ref);
				removal.put("name", character.str("name"));
				removal.put("reason", reason);
				tx.queryOne("SELECT a.*,s.location_id FROM map_actor a JOIN map_scene s ON s.id=a.scene_id "
						+ "WHERE a.campaign_id=? AND a.character_id=? AND json_extract(s.document_json,'$.journey_ref')=? ORDER BY a.id LIMIT 1",
						journey.lng("campaign_id"), character.id(), "journey:" + journey.id()).ifPresent(actor -> {
							removal.put("map_ref", Ref.of(Ref.MAP, actor.lng("scene_id")));
							removal.put("move_origin_ft", List.of(actor.get("x_ft"), actor.get("y_ft")));
							removal.put("level", actor.intOr("level", 0));
							if (!actor.isNull("location_id"))
								removal.put("left_at", Ref.of(Ref.LOCATION, actor.lng("location_id")));
						});
				if (!removal.containsKey("left_at") && mayInferTravelPosition(tx, journey, character, reason)) {
					Long contact = savedContactLocation(tx, journey);
					if (contact != null)
						removal.put("left_at", Ref.of(Ref.LOCATION, contact));
				}
				removed.add(removal);
			}
		}
		return new Roster(List.copyOf(retained), removed);
	}

	private static boolean mayInferTravelPosition(Tx tx, Row journey, Row character, Object reason) {
		return !"NO_CURRENT_PARTY_MEMBERSHIP".equals(reason)
				|| tx.count("SELECT COUNT(*) FROM party_membership WHERE campaign_id=? AND character_id=?",
						journey.lng("campaign_id"), character.id()) > 0;
	}

	/** Read-only lookup; a removed unplaced traveler has a place, not an invented map position. */
	private static Long savedContactLocation(Tx tx, Row journey) {
		Object contact = journey.map("opportunity_json").getOrDefault("contact_location",
				journey.map("tactical_anchor_json").get("contact_location"));
		if (contact instanceof String ref && ref.startsWith("location:")) {
			Row place = tx.find("location", Ref.id(ref, Ref.LOCATION)).orElse(null);
			if (place != null && place.lng("campaign_id") == journey.lng("campaign_id"))
				return place.id();
		}
		// Legacy resolved opportunities may lack the saved pointer, while their derived place already exists.
		String key = String
				.valueOf(encounterSceneDocument(tx, journey.lng("campaign_id"), null).get("journey_scene_key"));
		return tx.queryOne(
				"SELECT id FROM location WHERE campaign_id=? AND json_extract(features_json,'$[0].journey_scene_key')=? ORDER BY id LIMIT 1",
				journey.lng("campaign_id"), key).map(Row::id).orElse(null);
	}

	private static Row saveRoster(Tx tx, Row journey, Roster roster) {
		if (roster.removed().isEmpty())
			return journey;
		long cid = journey.lng("campaign_id");
		for (Map<String, Object> removal : roster.removed()) {
			Row character = CharacterService.character(tx, cid, removal.get("character").toString());
			Long here = removal.containsKey("left_at") ? Ref.id(removal.get("left_at").toString(), Ref.LOCATION) : null;
			if (here == null && mayInferTravelPosition(tx, journey, character, removal.get("reason"))
					&& ("EXPLICIT_SELECTION".equals(removal.get("reason")) || journey.lng("elapsed_seconds") > 0)) {
				here = effectiveLocation(tx, cid, null);
				removal.put("left_at", Ref.of(Ref.LOCATION, here));
			}
			if (here == null)
				continue;
			if (!Objects.equals(here, character.lng("location_id")))
				tx.update("character", character.id(),
						Map.of("location_id", here, "revision", character.lng("revision") + 1));
			if (tx.count(
					"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id=? AND character_id=? AND active=1",
					cid, character.id()) > 0) {
				Row campaign = tx.get("campaign", cid);
				if (!Objects.equals(here, campaign.lng("current_location_id")))
					tx.update("campaign", cid,
							Map.of("current_location_id", here, "revision", campaign.lng("revision") + 1));
			}
		}
		tx.update("travel_journey", journey.id(),
				Map.of("movers_json", Json.write(roster.movers()), "revision", journey.lng("revision") + 1));
		String names = String.join(", ", roster.removed().stream().map(r -> r.get("name").toString()).toList());
		LedgerService.append(tx, cid,
				new LedgerService.EventSpec("TRAVEL_ROSTER_CHANGED",
						"Journey travelers updated; " + names + " will not continue on this journey.",
						roster.removed().stream().map(r -> Ref.id(r.get("character").toString(), Ref.CHARACTER))
								.toList(),
						"MINOR", "PARTY_KNOWN", "GM", null, null, null,
						Map.of("journey", "journey:" + journey.id(), "movers", roster.movers(), "removed_travelers",
								roster.removed(), "progress_seconds", journey.lng("elapsed_seconds"))));
		return tx.get("travel_journey", journey.id());
	}

	private static List<Map<String, Object>> removedTravelers(Tx tx, Row journey, Roster pending) {
		var removed = new LinkedHashMap<String, Map<String, Object>>();
		for (Row event : tx.query(
				"SELECT payload_json FROM event WHERE campaign_id=? AND type='TRAVEL_ROSTER_CHANGED' "
						+ "AND json_extract(payload_json,'$.journey')=? ORDER BY id",
				journey.lng("campaign_id"), "journey:" + journey.id()))
			for (Map<String, Object> entry : se.hirt.mcp.rpg.maps.SceneService
					.maps(event.map("payload_json").get("removed_travelers")))
				removed.put(entry.get("character").toString(), entry);
		for (Map<String, Object> entry : pending.removed())
			removed.put(entry.get("character").toString(), entry);
		return List.copyOf(removed.values());
	}

	private static Map<String, Object> cancelEmptyJourney(Tx tx, Row journey, Roster roster) {
		journey = saveRoster(tx, journey, roster);
		tx.update("travel_journey", journey.id(),
				Map.of("status", "CANCELLED", "revision", journey.lng("revision") + 1));
		var removed = removedTravelers(tx, journey, roster);
		String names = String.join(", ",
				removed.stream().map(row -> row.get("name") + " (" + row.get("reason") + ")").toList());
		String reason = "No eligible saved travelers remain" + (names.isEmpty() ? "." : ": " + names + ".")
				+ " The saved journey is cancelled at its current progress; no travel time was spent.";
		LedgerService.append(tx, journey.lng("campaign_id"),
				new LedgerService.EventSpec("TRAVEL_JOURNEY_CANCELLED", reason, List.of(), "MINOR", "PARTY_KNOWN", "GM",
						null, null, null,
						Map.of("journey", "journey:" + journey.id(), "reason", "NO_ELIGIBLE_TRAVELERS",
								"progress_seconds", journey.lng("elapsed_seconds"), "removed_travelers", removed)));
		var result = new LinkedHashMap<>(view(tx, tx.get("travel_journey", journey.id())));
		result.put("cancelled_reason", reason);
		result.put("stop_reason", "NO_ELIGIBLE_TRAVELERS");
		result.put("elapsed_seconds", 0L);
		result.put("travel_minutes", 0L);
		result.put("moved", List.of());
		result.put("interrupted", false);
		result.put("game_time",
				GameTime.toMap(tx, journey.lng("campaign_id"), GameTime.currentSeq(tx, journey.lng("campaign_id"))));
		return result;
	}

	/** Read-only gate, before any clock, exposure, random roll or journey mutation. */
	static void requireTravelReady(Tx tx, RulesData rules, List<Row> movers) {
		requireTravelReady(tx, rules, movers, false);
	}

	private static void requireTravelReady(Tx tx, RulesData rules, List<Row> movers, boolean savedJourney) {
		for (Row character : movers) {
			var conditions = Combat.conditionsOf(tx, character);
			int speed = Exhaustion.speed(tx, rules, character);
			if (!"ACTIVE".equals(character.str("lifecycle")) || !"ALIVE".equals(character.str("life_state"))
					|| character.intOr("current_hp", 0) <= 0 || speed <= 0
					|| Combat.hasAny(conditions, Combat.INCAPACITATING_CONDITIONS)
					|| Combat.hasAny(conditions, Combat.SPEED_ZERO_CONDITIONS)) {
				throw RpgException.notAllowed(character.str("name") + " (" + Ref.of(Ref.CHARACTER, character.id())
						+ ") cannot travel unaided while " + character.str("life_state")
						+ ", incapacitated or unable to move. "
						+ (savedJourney
								? "Resolve healing, recovery or the condition preventing movement, or explicitly select the other saved travelers with move_party characters to leave this traveler behind. "
								: "Resolve healing, recovery or the condition preventing movement before departure, or use move_party characters to select the travelers who can depart from their shared location. ")
						+ "No time or journey progress was spent.")
						.withDetail("blocked_character", Ref.of(Ref.CHARACTER, character.id()))
						.withDetail("life_state", character.str("life_state"))
						.withDetail("conditions", conditions.stream().map(Combat.Condition::name).distinct().toList())
						.withDetail("speed_ft", speed);
			}
		}
	}

	/**
	 * Leave a mapped room for its semantic place without inventing an overland route or spending
	 * time.
	 */
	static Map<String, Object> withinPlace(Tx tx, Row campaign, Row from, Row destination, List<Row> movers) {
		long cid = campaign.id();
		se.hirt.mcp.rpg.maps.MapAuthoringService.requireEntryReady(tx, cid, destination);
		var references = movers.stream().map(c -> Ref.of(Ref.CHARACTER, c.id())).toList();
		var preserved = new ArrayList<Map<String, Object>>();
		var preservedLocations = new ArrayList<Map<String, Object>>();
		Long controlledId = tx
				.queryOne("SELECT character_id AS id FROM player_control_assignment WHERE campaign_id=? AND active=1",
						cid)
				.map(Row::id).orElse(null);
		boolean followsMovers = followsMovers(tx, cid, references);
		Long currentLocation = followsMovers ? destination.id() : campaign.lng("current_location_id");
		for (Row character : movers) {
			List<Row> actors = tx.query("SELECT * FROM map_actor WHERE campaign_id=? AND character_id=?", cid,
					character.id());
			boolean authoredRoom = !character.isNull("location_id") && tx.count(
					"SELECT COUNT(*) FROM map_scene s,json_each(s.document_json,'$.rooms') r "
							+ "WHERE s.campaign_id=? AND s.kind IN ('SCENE','DUNGEON') AND json_extract(r.value,'$.location_ref')=?",
					cid, Ref.of(Ref.LOCATION, character.lng("location_id"))) > 0;
			if (!actors.isEmpty() || authoredRoom) {
				Row location = character.isNull("location_id") ? null
						: tx.get("location", character.lng("location_id"));
				boolean ancestor = false;
				for (int depth = 0; location != null && depth < 100; depth++) {
					if (location.id() == destination.id()) {
						ancestor = true;
						break;
					}
					location = location.isNull("parent_id") ? null : tx.get("location", location.lng("parent_id"));
				}
				if (!ancestor)
					throw RpgException.notAllowed("A within-place focus cannot move " + character.str("name")
							+ " into another room. Use movement or an authored passage from the saved board.");
			}
			if (!actors.isEmpty()) {
				for (Row actor : actors)
					preserved.add(Map.of("character", Ref.of(Ref.CHARACTER, character.id()), "map_ref",
							Ref.of(Ref.MAP, actor.lng("scene_id")), "move_origin_ft",
							List.of(actor.get("x_ft"), actor.get("y_ft")), "level", actor.intOr("level", 0), "location",
							Ref.of(Ref.LOCATION, character.lng("location_id"))));
				if (followsMovers && (controlledId == null
						? Objects.equals(character.lng("location_id"), campaign.lng("current_location_id"))
						: character.id() == controlledId))
					currentLocation = character.lng("location_id");
				continue;
			}
			if (authoredRoom) {
				preservedLocations.add(Map.of("character", Ref.of(Ref.CHARACTER, character.id()), "location",
						Ref.of(Ref.LOCATION, character.lng("location_id"))));
				if (followsMovers && (controlledId == null
						? Objects.equals(character.lng("location_id"), campaign.lng("current_location_id"))
						: character.id() == controlledId))
					currentLocation = character.lng("location_id");
				continue;
			}
			tx.update("character", character.id(),
					Map.of("location_id", destination.id(), "revision", character.lng("revision") + 1));
		}
		if (!Objects.equals(campaign.lng("current_location_id"), currentLocation)) {
			tx.update("campaign", cid,
					Map.of("current_location_id", currentLocation, "revision", campaign.lng("revision") + 1));
			LedgerService.append(tx, cid,
					new LedgerService.EventSpec("PARTY_MOVED", "The party arrived at " + destination.str("name") + ".",
							movers.stream().map(Row::id).toList(), "MINOR", "PARTY_KNOWN", "GM", null, destination.id(),
							null, Map.of("within_place", true)));
		}
		var result = new LinkedHashMap<String, Object>();
		result.put("active", false);
		result.put("status", "COMPLETED");
		result.put("within_place", true);
		result.put("from", from == null ? null : Ref.of(Ref.LOCATION, from.id()));
		result.put("destination", Ref.of(Ref.LOCATION, destination.id()));
		result.put("current_location", Ref.ofNullable(Ref.LOCATION, currentLocation));
		result.put("preserved_positions", preserved);
		if (!preservedLocations.isEmpty())
			result.put("preserved_locations", preservedLocations);
		result.put("current_board", GridCombat.currentBoard(tx, cid));
		var placementHint = GridCombat.placementHint(tx, cid);
		if (!placementHint.isEmpty())
			result.put("placement_hint", placementHint);
		if (!preserved.isEmpty() || !preservedLocations.isEmpty())
			result.put("note",
					"The enclosing place is selected without moving the party's figurines or changing their canonical rooms.");
		result.put("travel_minutes", 0L);
		result.put("elapsed_seconds", 0L);
		result.put("interrupted", false);
		result.put("moved", references);
		result.put("route", List.of());
		result.put("consequences", List.of());
		result.put("arrived_at", WorldService.detail(tx, destination));
		result.put("available_maps", se.hirt.mcp.rpg.maps.WorldMapPreparation.coverage(tx, cid, destination.id()));
		result.put("game_time", GameTime.toMap(tx, cid, GameTime.currentSeq(tx, cid)));
		result.put("director_trigger", Map.of("recommended", false, "reasons", List.of(), "urgency", "NONE"));
		result.put("meta", Harness.meta(tx.get("campaign", cid), List.of()));
		return result;
	}

	static Map<String, Object> startOrAdvance(
		Tx tx, Row campaign, Row from, Row routeFrom, Row destination, List<Row> movers, List<Row> path,
		Map<Integer, Long> segmentOrigins, long minutes, RulesData rules, RollService roller) {
		long cid = campaign.id();
		requireTravelReady(tx, rules, movers);
		Row journey = current(tx, cid);
		if (journey != null) {
			if (journey.lng("to_location_id") != destination.id())
				throw RpgException.notAllowed(
						"The party is already traveling; resolve and resume its saved journey before choosing another destination.");
			return progress(tx, campaign, journey, WorldTimeline.MAX_ADVANCE_SECONDS, rules, roller);
		}
		if (minutes <= 0 || minutes > WorldTimeline.MAX_ADVANCE_SECONDS / 60)
			throw RpgException.invalidArgument("Journey duration must be positive and no longer than one year.");
		var legs = new ArrayList<Map<String, Object>>();
		long cursor = routeFrom == null ? destination.id() : routeFrom.id();
		for (int index = 0; index < path.size(); index++) {
			Row edge = path.get(index);
			cursor = segmentOrigins.getOrDefault(index, cursor);
			long next = edge.lng("location_a_id") == cursor ? edge.lng("location_b_id") : edge.lng("location_a_id");
			Map<String, Object> d = edge.map("distance_json");
			var leg = new LinkedHashMap<String, Object>();
			leg.put("connection_ref", "connection:" + edge.id());
			leg.put("from", "location:" + cursor);
			leg.put("to", "location:" + next);
			leg.put("name", tx.get("location", next).str("name"));
			leg.put("minutes", d.getOrDefault("travel_minutes", 60));
			leg.put("via", edge.str("kind"));
			if (d.get("distance_miles") != null)
				leg.put("distance_miles", d.get("distance_miles"));
			legs.add(leg);
			cursor = next;
		}
		return startPreparedJourney(tx, campaign, from, destination, movers, path, legs, minutes * 60, rules, roller);
	}

	private static Map<String, Object> startPreparedJourney(
		Tx tx, Row campaign, Row from, Row destination, List<Row> movers, List<Row> path,
		List<Map<String, Object>> legs, long seconds, RulesData rules, RollService roller) {
		if (seconds <= 0 || seconds > WorldTimeline.MAX_ADVANCE_SECONDS)
			throw RpgException.invalidArgument("Journey duration must be positive and no longer than one year.");
		long cid = campaign.id();
		Map<String, Object> policy = policy(tx, cid, path, destination);
		long frequency = WorldTimeline.number(policy, "frequency_seconds", 14400);
		var columns = new LinkedHashMap<String, Object>();
		columns.put("campaign_id", cid);
		columns.put("from_location_id", from == null ? null : from.id());
		columns.put("to_location_id", destination.id());
		columns.put("route_json", Json.write(legs));
		columns.put("movers_json", Json.write(movers.stream().map(r -> "character:" + r.id()).toList()));
		columns.put("total_seconds", seconds);
		columns.put("policy_json", Json.write(policy));
		columns.put("next_check_seconds", frequency);
		Row exposure = exposure(tx, cid, String.valueOf(policy.getOrDefault("_scope_ref", "DEFAULT")));
		columns.put("exposure_origin_seconds", exposure.lng("exposure_seconds"));
		long id = tx.insert("travel_journey", columns);
		return progress(tx, campaign, tx.get("travel_journey", id), WorldTimeline.MAX_ADVANCE_SECONDS, rules, roller);
	}

	/** A derived contact remains a point on its authored road, rather than a new graph junction. */
	static Map<String, Object> startFromContactInTx(
		Tx tx, Row campaign, Row from, Row destination, List<Row> movers, RulesData rules, RollService roller) {
		Map<String, Object> contact = se.hirt.mcp.rpg.maps.SceneService.maps(from.list("features_json")).stream()
				.filter(feature -> "TRAVEL_ANCHOR".equals(feature.get("kind"))).findFirst().orElse(null);
		if (contact == null)
			return null;
		Map<String, Object> savedAnchor = se.hirt.mcp.rpg.maps.SceneService.map(contact.get("anchor"));
		String connectionRef = String.valueOf(savedAnchor.get("connection_ref"));
		Row source = contact.get("journey_ref") instanceof String ref
				? tx.find("travel_journey", Ref.id(ref, "journey")).orElse(null) : null;
		if (source == null || source.lng("campaign_id") != campaign.id() || !connectionRef.startsWith("connection:"))
			throw RpgException
					.notAllowed("The contact's saved route anchor is incomplete. Establish an authored route from "
							+ from.str("name") + " before travel.");
		Row edge = tx.get("location_connection", Ref.id(connectionRef, Ref.CONNECTION));
		if (edge.lng("campaign_id") != campaign.id() || !WorldService.traversable(edge))
			throw RpgException.notAllowed("The contact's authored connection " + connectionRef
					+ " is no longer traversable. Resolve its passage state before travel.");
		Map<String, Object> sourceLeg = se.hirt.mcp.rpg.maps.SceneService.maps(source.list("route_json")).stream()
				.filter(leg -> connectionRef.equals(leg.get("connection_ref"))).findFirst()
				.orElseThrow(() -> RpgException.notAllowed(
						"The contact's original road leg is unavailable; establish its route before travel."));
		String originalFrom = String.valueOf(sourceLeg.getOrDefault("connection_from", sourceLeg.get("from")));
		double fraction = ((Number) savedAnchor.getOrDefault("leg_fraction", .5)).doubleValue();
		double fromA = Ref.id(originalFrom, Ref.LOCATION) == edge.lng("location_a_id") ? fraction : 1 - fraction;
		ContactRoute best = null;
		boolean blockedRoom = false;
		for (long endpoint : List.of(edge.lng("location_a_id"), edge.lng("location_b_id"))) {
			Row end = tx.get("location", endpoint);
			WorldService.RoutePlan remainder = WorldService.travelRoute(tx, campaign.id(), end, destination);
			if (remainder.path().isEmpty() && !remainder.withinPlace())
				continue;
			// Returning across the same road would retrace the contact; the other endpoint is shorter.
			if (remainder.path().stream().anyMatch(part -> part.id() == edge.id()))
				continue;
			if (!WorldService.roomArrivalAllowed(tx, campaign.id(), destination, remainder.path())) {
				blockedRoom = true;
				continue;
			}
			double start = endpoint == edge.lng("location_b_id") ? fromA : 1 - fromA;
			double roadMinutes = ((Number) edge.map("distance_json").getOrDefault("travel_minutes", 60)).doubleValue();
			long seconds = Math.max(1, Math.round(roadMinutes * (1 - start) * 60));
			var partial = new LinkedHashMap<String, Object>();
			partial.put("connection_ref", connectionRef);
			partial.put("from", Ref.of(Ref.LOCATION, from.id()));
			partial.put("to", Ref.of(Ref.LOCATION, endpoint));
			partial.put("connection_from", Ref.of(Ref.LOCATION,
					endpoint == edge.lng("location_b_id") ? edge.lng("location_a_id") : edge.lng("location_b_id")));
			partial.put("start_fraction", start);
			partial.put("end_fraction", 1d);
			partial.put("minutes", seconds / 60d);
			partial.put("name", end.str("name"));
			partial.put("via", edge.str("kind"));
			if (edge.map("distance_json").get("distance_miles") != null)
				partial.put("distance_miles", edge.map("distance_json").get("distance_miles"));
			var legs = new ArrayList<Map<String, Object>>();
			legs.add(partial);
			var path = new ArrayList<Row>();
			path.add(edge);
			long cursor = endpoint;
			for (int index = 0; index < remainder.path().size(); index++) {
				Row next = remainder.path().get(index);
				cursor = remainder.segmentOrigins().getOrDefault(index, cursor);
				long target = next.lng("location_a_id") == cursor ? next.lng("location_b_id")
						: next.lng("location_a_id");
				Map<String, Object> distance = next.map("distance_json");
				var leg = new LinkedHashMap<String, Object>();
				leg.put("connection_ref", Ref.of(Ref.CONNECTION, next.id()));
				leg.put("from", Ref.of(Ref.LOCATION, cursor));
				leg.put("to", Ref.of(Ref.LOCATION, target));
				leg.put("name", tx.get("location", target).str("name"));
				leg.put("minutes", distance.getOrDefault("travel_minutes", 60));
				leg.put("via", next.str("kind"));
				if (distance.get("distance_miles") != null)
					leg.put("distance_miles", distance.get("distance_miles"));
				seconds += Math.round(((Number) leg.get("minutes")).doubleValue() * 60);
				legs.add(leg);
				path.add(next);
				cursor = target;
			}
			if (best == null || seconds < best.seconds())
				best = new ContactRoute(path, legs, seconds);
		}
		if (best == null && blockedRoom)
			throw WorldService.roomArrivalRefusal(tx, campaign.id(), destination);
		if (best == null)
			throw RpgException.notAllowed("No known traversable route from the saved contact " + from.str("name")
					+ " to " + destination.str("name")
					+ ". Establish the connecting route; authorized_route cannot replace the contact's authored road.");
		return startPreparedJourney(tx, campaign, from, destination, movers, best.path(), best.legs(), best.seconds(),
				rules, roller);
	}

	private record ContactRoute(List<Row> path, List<Map<String, Object>> legs, long seconds) {
	}

	private static Map<String, Object> progress(
		Tx tx, Row campaign, Row journey, long maximumSeconds, RulesData rules, RollService roller) {
		return progress(tx, campaign, journey, maximumSeconds, rules, roller, null);
	}

	private static Map<String, Object> progress(
		Tx tx, Row campaign, Row journey, long maximumSeconds, RulesData rules, RollService roller,
		List<String> selected) {
		if (maximumSeconds <= 0 || maximumSeconds > WorldTimeline.MAX_ADVANCE_SECONDS)
			throw RpgException.invalidArgument("Travel step seconds must be positive and no longer than one year.");
		Roster roster = roster(tx, journey, selected);
		List<Map<String, Object>> removals = removedTravelers(tx, journey, roster);
		if (roster.movers().isEmpty()) {
			Roster automatic = roster(tx, journey, null);
			if (!automatic.movers().isEmpty())
				throw RpgException.notAllowed(
						"No eligible selected traveler can continue. Select at least one eligible saved traveler in characters; this selection cannot cancel the other travelers' saved journey. No time or journey progress was spent.")
						.withDetail("eligible_saved_travelers", automatic.movers())
						.withDetail("removed_travelers", removals);
			return cancelEmptyJourney(tx, journey, roster);
		}
		try {
			requireTravelReady(tx, rules,
					roster.movers().stream().map(ref -> CharacterService.character(tx, campaign.id(), ref)).toList(),
					true);
		} catch (RpgException refusal) {
			throw refusal.withDetail("removed_travelers", removals).withDetail("saved_travelers", roster.movers());
		}
		journey = saveRoster(tx, journey, roster);
		boolean followsMovers = followsMovers(tx, campaign.id(), roster.movers());
		if (!journey.map("opportunity_json").isEmpty()) {
			var pending = new LinkedHashMap<>(view(tx, journey));
			pending.put("interrupted", true);
			pending.put("elapsed_seconds", 0L);
			pending.put("travel_minutes", 0L);
			pending.put("consequences", List.of(publicAnchorContainer(journey.map("opportunity_json"), "anchor")));
			pending.put("game_time", GameTime.toMap(tx, campaign.id(), GameTime.currentSeq(tx, campaign.id())));
			pending.put("meta", Harness.meta(campaign, null));
			return pending;
		}
		long cid = campaign.id(), start = journey.lng("elapsed_seconds"), elapsed = start,
				total = journey.lng("total_seconds"), limit = Math.min(total, start + maximumSeconds), expired = 0;
		Map<String, Object> policy = journey.map("policy_json");
		long frequency = WorldTimeline.number(policy, "frequency_seconds", 14400);
		boolean shared = !policy.containsKey("authored_at_fraction");
		long base = shared ? journey.lng("exposure_origin_seconds") : 0;
		Row exposure = exposure(tx, cid, String.valueOf(policy.getOrDefault("_scope_ref", "DEFAULT")));
		var consequences = new ArrayList<Object>();
		boolean interrupted = false;
		Map<String, Object> opportunity = Map.of();
		int guard = 0;
		while (elapsed < limit) {
			long block = ((base + elapsed) / frequency) * frequency;
			Row check = tx.queryOne("SELECT * FROM travel_check WHERE journey_id=? AND exposure_seconds=?",
					journey.id(), block).orElse(null);
			Map<String, Object> outcome;
			if (check == null) {
				Row liveExposure = tx.get("travel_exposure", exposure.id());
				if (shared && !liveExposure.isNull("check_block_seconds")
						&& liveExposure.lng("check_block_seconds") == block)
					outcome = liveExposure.map("outcome_json");
				else {
					outcome = sample(tx, cid, journey, policy, block, frequency, shared ? block + frequency : total,
							roller);
					if (shared)
						tx.update("travel_exposure", exposure.id(), Map.of("check_block_seconds", block, "outcome_json",
								Json.write(outcome), "revision", liveExposure.lng("revision") + 1));
				}
				long id = tx.insert("travel_check", Map.of("campaign_id", cid, "journey_id", journey.id(),
						"exposure_seconds", block, "outcome_json", Json.write(outcome)));
				check = tx.get("travel_check", id);
			} else
				outcome = check.map("outcome_json");
			long eventAt = WorldTimeline.number(outcome, "at_seconds", Math.min(total + base, block + frequency))
					- base;
			long stop = Math.min(limit, Math.max(elapsed, Math.min(block + frequency - base, eventAt)));
			WorldTimeline.Progress tick = WorldTimeline.advanceSeconds(tx, cid, stop - elapsed, "TRAVEL", true);
			elapsed += tick.elapsedSeconds();
			expired += tick.expiredEffects();
			consequences.addAll(tick.consequences());
			if (tick.interrupted()) {
				interrupted = true;
				break;
			}
			if (Boolean.TRUE.equals(outcome.get("encounter")) && elapsed >= eventAt
					&& !Boolean.TRUE.equals(outcome.get("consumed"))) {
				var encounter = new LinkedHashMap<String, Object>(outcome);
				encounter.put("kind", "TRAVEL_ENCOUNTER_OPPORTUNITY");
				encounter.put("journey", "journey:" + journey.id());
				encounter.put("anchor", anchor(journey, elapsed));
				encounter.put("at_campaign_seconds", GameTime.currentSeconds(tx, cid));
				encounter.put("requires_grid_before_combat", true);
				opportunity = encounter;
				consequences.add(encounter);
				interrupted = true;
				var consumed = new LinkedHashMap<>(outcome);
				consumed.put("consumed", true);
				tx.update("travel_check", check.id(), Map.of("outcome_json", Json.write(consumed)));
				if (shared)
					tx.update("travel_exposure", exposure.id(), Map.of("outcome_json", Json.write(consumed), "revision",
							tx.get("travel_exposure", exposure.id()).lng("revision") + 1));
				break;
			}
			if (stop == elapsed && elapsed < limit && eventAt <= elapsed) {
				long rest = Math.min(limit, block + frequency - base) - elapsed;
				if (rest > 0) {
					WorldTimeline.Progress remainder = WorldTimeline.advanceSeconds(tx, cid, rest, "TRAVEL", true);
					elapsed += remainder.elapsedSeconds();
					expired += remainder.expiredEffects();
					consequences.addAll(remainder.consequences());
					if (remainder.interrupted()) {
						interrupted = true;
						break;
					}
				}
			}
			if (++guard > 10000)
				throw RpgException.invalidArgument("Journey has too many risk intervals; continue in smaller steps.");
		}
		Row destination = tx.get("location", journey.lng("to_location_id"));
		Map<String, Object> preparationRequired = elapsed >= total && !interrupted
				? se.hirt.mcp.rpg.maps.WorldMapPreparation.pendingEntry(tx, cid, destination.id()) : Map.of();
		if (!preparationRequired.isEmpty())
			interrupted = true;
		boolean arrived = elapsed >= total && !interrupted;
		Row freshExposure = tx.get("travel_exposure", exposure.id());
		tx.update("travel_exposure", exposure.id(),
				Map.of("exposure_seconds", freshExposure.lng("exposure_seconds") + (elapsed - start), "revision",
						freshExposure.lng("revision") + 1));
		tx.update("travel_journey", journey.id(),
				Map.of("elapsed_seconds", elapsed, "exposure_seconds", elapsed, "next_check_seconds",
						(elapsed / frequency + 1) * frequency, "opportunity_json", Json.write(opportunity), "status",
						arrived ? "COMPLETED" : interrupted ? "INTERRUPTED" : "ACTIVE", "revision",
						journey.lng("revision") + 1));
		if (elapsed > start && journey.map("tactical_anchor_json").containsKey("contact_location")) {
			var tactical = new LinkedHashMap<>(journey.map("tactical_anchor_json"));
			tactical.remove("contact_location");
			tx.update("travel_journey", journey.id(), Map.of("tactical_anchor_json", Json.write(tactical)));
		}
		if (!opportunity.isEmpty()) {
			long contactLocation = effectiveLocation(tx, cid, null);
			opportunity.put("contact_location", Ref.of(Ref.LOCATION, contactLocation));
			tx.update("travel_journey", journey.id(), Map.of("opportunity_json", Json.write(opportunity)));
		}
		if (elapsed > start)
			for (Object ref : journey.list("movers_json")) {
				long characterId = Ref.id(String.valueOf(ref), Ref.CHARACTER);
				for (Row placed : tx.query("SELECT id FROM map_actor WHERE campaign_id=? AND character_id=?", cid,
						characterId))
					tx.delete("map_actor", placed.id());
			}
		if (!preparationRequired.isEmpty()) {
			long exterior = effectiveLocation(tx, cid, null);
			for (Object ref : journey.list("movers_json")) {
				Row character = CharacterService.character(tx, cid, String.valueOf(ref));
				tx.update("character", character.id(),
						Map.of("location_id", exterior, "revision", character.lng("revision") + 1));
			}
			Row freshCampaign = tx.get("campaign", cid);
			if (followsMovers)
				tx.update("campaign", cid,
						Map.of("current_location_id", exterior, "revision", freshCampaign.lng("revision") + 1));
		}
		if (arrived) {
			for (Object ref : journey.list("movers_json")) {
				Row c = CharacterService.character(tx, cid, String.valueOf(ref));
				tx.update("character", c.id(),
						Map.of("location_id", destination.id(), "revision", c.lng("revision") + 1));
			}
			if (followsMovers) {
				Row freshCampaign = tx.get("campaign", cid);
				tx.update("campaign", cid,
						Map.of("current_location_id", destination.id(), "revision", freshCampaign.lng("revision") + 1));
			}
			LedgerService.append(tx, cid,
					new LedgerService.EventSpec("PARTY_MOVED",
							"The journey travelers arrived at " + destination.str("name") + ".",
							journey.list("movers_json").stream().map(r -> Ref.id(String.valueOf(r), Ref.CHARACTER))
									.toList(),
							"MINOR", "PARTY_KNOWN", "GM", null, destination.id(), null,
							Map.of("journey", "journey:" + journey.id())));
		}
		PatrolService.reconcileRegionalTimers(tx, cid);
		var result = new LinkedHashMap<>(view(tx, tx.get("travel_journey", journey.id())));
		result.put("travel_minutes", (elapsed - start) / 60);
		result.put("elapsed_seconds", elapsed - start);
		result.put("consequences",
				consequences.stream()
						.map(consequence -> consequence instanceof Map<?, ?>
								? publicAnchorContainer(se.hirt.mcp.rpg.maps.SceneService.map(consequence), "anchor")
								: consequence)
						.toList());
		result.put("expired_effects", expired);
		result.put("moved", journey.list("movers_json"));
		result.put("current_location",
				Ref.ofNullable(Ref.LOCATION, tx.get("campaign", cid).lng("current_location_id")));
		result.put("route", journey.list("route_json"));
		result.put("game_time", GameTime.toMap(tx, cid, GameTime.currentSeq(tx, cid)));
		result.put("interrupted", interrupted);
		if (arrived)
			result.put("arrived_at", WorldService.detail(tx, destination));
		if (arrived)
			result.put("available_maps", se.hirt.mcp.rpg.maps.WorldMapPreparation.coverage(tx, cid, destination.id()));
		if (arrived) {
			var placementHint = GridCombat.placementHint(tx, cid);
			if (!placementHint.isEmpty())
				result.put("placement_hint", placementHint);
		}
		if (!preparationRequired.isEmpty()) {
			result.put("preparation_required", preparationRequired);
			result.put("arrived_at_exterior",
					WorldService.detail(tx, tx.get("location", effectiveLocation(tx, cid, null))));
			result.put("stop_reason", "MAP_PREPARATION_REQUIRED");
		}
		result.put("director_trigger",
				Map.of("recommended", interrupted || total >= 86400, "reasons", interrupted ? List
						.of(preparationRequired.isEmpty() ? "TRAVEL_ENCOUNTER_OPPORTUNITY" : "MAP_PREPARATION_REQUIRED")
						: List.of(), "urgency", interrupted ? "NORMAL" : "NONE"));
		result.put("meta",
				Harness.meta(tx.get("campaign", cid), arrived && "SEMANTIC".equals(destination.str("materialization"))
						&& !se.hirt.mcp.rpg.maps.WorldMapPreparation.hasPlayableCoverage(tx, cid, destination.id())
								? List.of(destination.str("name")
										+ " is only a semantic node; materialize its detail on approach.")
								: List.of()));
		return result;
	}

	private static boolean followsMovers(Tx tx, long campaignId, List<String> movers) {
		var controlled = tx.queryOne(
				"SELECT character_id AS id FROM player_control_assignment WHERE campaign_id=? AND active=1",
				campaignId);
		return controlled.isEmpty() || movers.contains(Ref.of(Ref.CHARACTER, controlled.get().id()));
	}

	private static Map<String, Object> sample(
		Tx tx, long cid, Row journey, Map<String, Object> policy, long block, long frequency, long total,
		RollService roller) {
		var result = new LinkedHashMap<String, Object>();
		result.put("encounter", false);
		result.put("at_seconds", Math.min(total, block + frequency));
		if (policy.get("authored_at_fraction") instanceof Number fraction) {
			long at = authoredAtSeconds(tx, journey, policy, fraction.doubleValue(), total);
			if (at > block && at <= block + frequency) {
				result.put("encounter", true);
				result.put("at_seconds", at);
				result.put("placement_method", "AUTHORED");
			}
		} else {
			double chance = policy.get("chance_percent") instanceof Number n ? n.doubleValue() : 0;
			chance *= Math.min(frequency, total - block) / (double) frequency;
			if (chance > 0) {
				Roll roll = roller.roll("1d10000");
				long id = CharacterService.recordRoll(tx, cid, "journey risk " + journey.id() + " interval " + block,
						roll);
				result.put("roll", "roll:" + id);
				if (roll.total() <= chance * 100) {
					long at = Math.min(total, block + frequency);
					if ("RANDOM".equalsIgnoreCase(String.valueOf(policy.getOrDefault("placement_method", "RANDOM")))) {
						long span = Math.min(frequency, total - block);
						Roll placement = roller.roll("1d1000");
						CharacterService.recordRoll(tx, cid, "journey placement " + journey.id() + " interval " + block,
								placement);
						at = block + Math.max(1, Math.round(span * placement.total() / 1000.0));
					}
					result.put("encounter", true);
					result.put("at_seconds", at);
					result.put("placement_method", policy.getOrDefault("placement_method", "RANDOM"));
				}
			}
		}
		if (Boolean.TRUE.equals(result.get("encounter"))) {
			for (String key : List.of("opposition", "severity", "story", "location_ref", "terrain"))
				if (policy.containsKey(key))
					result.put(key, policy.get(key));
			var hooks = new ArrayList<String>();
			for (Row seed : tx.query(
					"SELECT * FROM director_seed WHERE campaign_id=? AND state='OPEN' AND kind='PRESSURE' AND visibility<>'DIRECTOR_ONLY' ORDER BY id DESC LIMIT 2",
					cid)) {
				Map<String, Object> payload = seed.map("payload_json");
				Object summary = payload.getOrDefault("summary", payload.get("title"));
				if (summary != null)
					hooks.add("seed:" + seed.id() + ": " + summary);
			}
			result.put("hooks", hooks);
		}
		return result;
	}

	/**
	 * A connection-scoped fraction is a point on that road, not a fraction of the remaining trip.
	 */
	private static long authoredAtSeconds(Tx tx, Row journey, Map<String, Object> policy, double fraction, long total) {
		String scope = String.valueOf(policy.getOrDefault("_scope_ref", "DEFAULT"));
		if (!scope.startsWith("connection:"))
			return Math.round(total * fraction);
		Row edge = tx.get("location_connection", Ref.id(scope, Ref.CONNECTION));
		Row feature = se.hirt.mcp.rpg.maps.MapAuthoringService.regionalFeature(tx, journey.lng("campaign_id"), scope)
				.orElse(null);
		String authoredFrom = feature == null ? Ref.of(Ref.LOCATION, edge.lng("location_a_id"))
				: String.valueOf(feature.map("properties_json").getOrDefault("from_location_ref",
						Ref.of(Ref.LOCATION, edge.lng("location_a_id"))));
		List<Map<String, Object>> legs = se.hirt.mcp.rpg.maps.SceneService.maps(journey.list("route_json"));
		double weight = legs.stream().mapToDouble(leg -> ((Number) leg.getOrDefault("minutes", 60)).doubleValue())
				.sum(), before = 0;
		for (var leg : legs) {
			double part = ((Number) leg.getOrDefault("minutes", 60)).doubleValue();
			if (scope.equals(leg.get("connection_ref"))) {
				double along = authoredFrom.equals(leg.getOrDefault("connection_from", leg.get("from"))) ? fraction
						: 1 - fraction;
				double start = ((Number) leg.getOrDefault("start_fraction", 0)).doubleValue(),
						end = ((Number) leg.getOrDefault("end_fraction", 1)).doubleValue();
				double relative = (along - start) / (end - start);
				if (Double.isFinite(relative) && relative > 0 && relative <= 1)
					return Math.round(total * (before + part * relative) / weight);
			}
			before += part;
		}
		return -1;
	}

	private static Map<String, Object> policy(Tx tx, long cid, List<Row> path, Row destination) {
		var scopes = new ArrayList<String>();
		for (Row edge : path)
			scopes.add("connection:" + edge.id());
		scopes.add("location:" + destination.id());
		for (long id : WorldService.lineage(tx, destination.id()))
			scopes.add("location:" + id);
		scopes.add("DEFAULT");
		for (String scope : scopes) {
			Row policy = tx.queryOne("SELECT * FROM travel_risk WHERE campaign_id=? AND scope_ref=?", cid, scope)
					.orElse(null);
			if (policy != null) {
				var selected = new LinkedHashMap<String, Object>(policy.map("policy_json"));
				selected.put("_scope_ref", scope);
				return selected;
			}
		}
		String terrain = "";
		for (Row edge : path) {
			String kind = edge.str("kind");
			if (kind != null
					&& List.of("ROAD", "TRAIL", "PATH", "RIVER", "SEA_ROUTE").contains(kind.toUpperCase(Locale.ROOT)))
				terrain = "road";
		}
		for (Object tag : destination.list("tags_json")) {
			String t = String.valueOf(tag).toLowerCase(Locale.ROOT);
			if (List.of("forest", "hills", "mountain", "marsh", "wilderness", "ruin").contains(t)) {
				terrain = t;
				break;
			}
		}
		double chance = terrain.isEmpty() ? 0 : terrain.equals("road") ? 100d / 12 : 100d / 6;
		return Map.of("frequency_seconds", 14400, "chance_percent", chance, "terrain", terrain, "severity", "STORY",
				"placement_method", "RANDOM", "_scope_ref", "TERRAIN:" + terrain);
	}

	private static Row exposure(Tx tx, long cid, String scope) {
		Row row = tx.queryOne("SELECT * FROM travel_exposure WHERE campaign_id=? AND scope_ref=?", cid, scope)
				.orElse(null);
		if (row != null)
			return row;
		long id = tx.insert("travel_exposure", Map.of("campaign_id", cid, "scope_ref", scope));
		return tx.get("travel_exposure", id);
	}

	/** Refine the saved graph position using the same regional road bends and nearby terrain. */
	public static Map<String, Object> encounterSceneDocument(Tx tx, long cid, Long locationId) {
		Row journey = current(tx, cid);
		if (journey == null)
			return Map.of();
		Map<String, Object> anchor = anchor(journey, journey.lng("elapsed_seconds"));
		Object connection = anchor.get("connection_ref");
		Row feature = connection == null ? null : se.hirt.mcp.rpg.maps.MapAuthoringService
				.regionalFeature(tx, cid, String.valueOf(connection)).orElse(null);
		List<List<Double>> worldPath = feature == null ? List.of()
				: coordinates(feature.map("geometry_json").get("coordinates"));
		double fraction = anchor.get("leg_fraction") instanceof Number n ? n.doubleValue() : .5;
		boolean reverse = false;
		if (connection != null) {
			Row edge = tx.get("location_connection", Ref.id(connection.toString(), Ref.CONNECTION));
			for (Map<String, Object> leg : se.hirt.mcp.rpg.maps.SceneService.maps(journey.list("route_json")))
				if (connection.equals(leg.get("connection_ref"))) {
					Object pathFrom = feature == null ? null : feature.map("properties_json").get("from_location_ref");
					reverse = pathFrom == null
							? Ref.id(leg.getOrDefault("connection_from", leg.get("from")).toString(),
									Ref.LOCATION) == edge.lng("location_b_id")
							: !leg.getOrDefault("connection_from", leg.get("from")).equals(pathFrom);
					break;
				}
		}
		double[] world = worldPath.size() < 2 ? new double[] {0, 0}
				: along(worldPath, reverse ? 1 - fraction : fraction);
		double ox = worldPath.size() < 2 ? 0 : Math.floor(world[0] / 5) * 5 - 50,
				oy = worldPath.size() < 2 ? 0 : Math.floor(world[1] / 5) * 5 - 50;
		var document = new LinkedHashMap<String, Object>();
		document.put("bounds_ft", List.of(0, 0, 100, 100));
		document.put("enclose_rooms", false);
		document.put("rooms", List.of(Map.of("key", "roadside-ground", "rect_ft", List.of(0, 0, 100, 100), "enclosed",
				false, "material", "GRASS")));
		document.put("journey_ref", "journey:" + journey.id());
		document.put("route_anchor", anchor);
		document.put("world_origin_ft", List.of(ox, oy));
		document.put("journey_world_anchor_ft", List.of(world[0], world[1]));
		document.put("journey_entry_origin_ft", List.of(50, 50));
		document.put("route_geometry_known", worldPath.size() >= 2);
		document.put("route_reverse", reverse);
		String coverage = worldPath.size() >= 2 ? Math.round(ox) + ":" + Math.round(oy)
				: anchor.get("distance_along_miles") instanceof Number miles
						? "route-ft:" + (long) Math.floor(miles.doubleValue() * 5280 / 100)
						: "route-fraction:" + (long) Math.floor(fraction * 10000);
		document.put("journey_scene_key", String.valueOf(connection) + ":" + coverage);
		List<List<Double>> localPath = worldPath.size() < 2 ? List.of(List.of(0d, 50d), List.of(100d, 50d))
				: worldPath.stream().map(p -> List.of(p.get(0) - ox, p.get(1) - oy)).toList();
		document.put("route_world_path_ft", worldPath);
		double width = feature == null ? 10 : se.hirt.mcp.rpg.maps.SceneService
				.number(feature.map("properties_json"), "width_ft", 10).doubleValue();
		List<Map<String, Object>> localRoads = se.hirt.mcp.rpg.maps.TacticalGeography.clip(
				List.of(Map.of("kind", "ROAD", "path_ft", localPath, "width_ft", width)), List.of(0d, 0d, 100d, 100d));
		document.put("roads", localRoads);
		document.put("route_path_ft", localRoads.isEmpty() ? List.of() : localRoads.getFirst().get("path_ft"));
		var terrain = new ArrayList<Map<String, Object>>();
		if (feature != null)
			for (Row region : tx.query(
					"SELECT * FROM map_feature WHERE campaign_id=? AND atlas_id=? AND UPPER(kind) IN ('FOREST','FIELD','GRASSLAND','WATER','LAKE','RIVER','MOUNTAIN','HILL') ORDER BY id LIMIT 128",
					cid, feature.lng("atlas_id"))) {
				Map<String, Object> geometry = region.map("geometry_json");
				List<List<Double>> points = coordinates(geometry.get("coordinates"));
				if (points.isEmpty())
					continue;
				List<List<Double>> local = points.stream().map(p -> List.of(p.get(0) - ox, p.get(1) - oy)).toList();
				var item = new LinkedHashMap<String, Object>();
				item.put("kind", region.str("kind"));
				item.put("material", region.str("kind"));
				item.put("PATH".equals(geometry.get("type")) ? "path_ft" : "polygon_ft", local);
				if (region.map("properties_json").get("width_ft") != null)
					item.put("width_ft", region.map("properties_json").get("width_ft"));
				terrain.add(item);
			}
		if (feature == null && "forest".equalsIgnoreCase(String.valueOf(journey.map("policy_json").get("terrain")))) {
			terrain.add(Map.of("kind", "FOREST", "polygon_ft",
					List.of(List.of(0, 0), List.of(100, 0), List.of(100, 40), List.of(0, 40))));
			terrain.add(Map.of("kind", "FOREST", "polygon_ft",
					List.of(List.of(0, 60), List.of(100, 60), List.of(100, 100), List.of(0, 100))));
		}
		document.put("features", se.hirt.mcp.rpg.maps.TacticalGeography.clip(terrain, List.of(0d, 0d, 100d, 100d)));
		return document;
	}

	/**
	 * Record the party's final figurine position before returning to graph travel. Clock/exposure
	 * are unchanged.
	 */
	public static void captureFinalPosition(Tx tx, long cid, long sceneId, String characterRef) {
		Row journey = current(tx, cid);
		if (journey == null || characterRef == null)
			return;
		Row scene = tx.get("map_scene", sceneId);
		Map<String, Object> document = scene.map("document_json");
		if (!("journey:" + journey.id()).equals(document.get("journey_ref")))
			return;
		Row actor = tx.queryOne("SELECT * FROM map_actor WHERE campaign_id=? AND scene_id=? AND character_id=?", cid,
				sceneId, Ref.id(characterRef, Ref.CHARACTER)).orElse(null);
		if (actor == null)
			return;
		List<Double> entry = pair(document.get("journey_entry_origin_ft"), List.of(50d, 50d)),
				worldAnchor = pair(document.get("journey_world_anchor_ft"), List.of(0d, 0d));
		double dx = ((Number) actor.get("x_ft")).doubleValue() - entry.get(0),
				dy = ((Number) actor.get("y_ft")).doubleValue() - entry.get(1);
		var saved = new LinkedHashMap<String, Object>();
		if (!scene.isNull("location_id"))
			saved.put("contact_location", Ref.of(Ref.LOCATION, scene.lng("location_id")));
		saved.put("scene_ref", "map:" + sceneId);
		saved.put("position_ft", List.of(actor.get("x_ft"), actor.get("y_ft")));
		saved.put("offset_from_contact_ft", List.of(dx, dy));
		saved.put("route_anchor", anchor(journey, journey.lng("elapsed_seconds")));
		var changes = new LinkedHashMap<String, Object>();
		long before = journey.lng("elapsed_seconds"), after = before;
		List<List<Double>> path = coordinates(document.get("route_world_path_ft"));
		if (path.size() >= 2) {
			double[] projection = project(path, worldAnchor.get(0) + dx, worldAnchor.get(1) + dy);
			double legFraction = Boolean.TRUE.equals(document.get("route_reverse")) ? 1 - projection[0] : projection[0];
			Map<String, Object> old = se.hirt.mcp.rpg.maps.SceneService.map(document.get("route_anchor"));
			double weight = 0, totalWeight = 0;
			List<Map<String, Object>> legs = se.hirt.mcp.rpg.maps.SceneService.maps(journey.list("route_json"));
			for (Map<String, Object> leg : legs)
				totalWeight += ((Number) leg.getOrDefault("minutes", 60)).doubleValue();
			for (Map<String, Object> leg : legs) {
				double part = ((Number) leg.getOrDefault("minutes", 60)).doubleValue();
				if (java.util.Objects.equals(leg.get("connection_ref"), old.get("connection_ref"))) {
					double startFraction = ((Number) leg.getOrDefault("start_fraction", 0)).doubleValue(),
							endFraction = ((Number) leg.getOrDefault("end_fraction", 1)).doubleValue();
					double segmentFraction = Math.max(0,
							Math.min(1, (legFraction - startFraction) / (endFraction - startFraction)));
					after = Math.round(journey.lng("total_seconds") * (weight + part * segmentFraction)
							/ Math.max(1, totalWeight));
					break;
				}
				weight += part;
			}
			saved.put("world_position_ft", List.of(worldAnchor.get(0) + dx, worldAnchor.get(1) + dy));
			saved.put("off_route_ft", projection[1]);
			changes.put("elapsed_seconds", after);
			if (!journey.map("policy_json").containsKey("authored_at_fraction")) {
				Row exposure = exposure(tx, cid,
						String.valueOf(journey.map("policy_json").getOrDefault("_scope_ref", "DEFAULT")));
				changes.put("exposure_origin_seconds", exposure.lng("exposure_seconds") - after);
			}
		}
		saved.put("progress_before_seconds", before);
		saved.put("progress_after_seconds", after);
		changes.put("tactical_anchor_json", Json.write(saved));
		changes.put("revision", journey.lng("revision") + 1);
		tx.update("travel_journey", journey.id(), changes);
	}

	private static List<Double> pair(Object value, List<Double> fallback) {
		List<List<Double>> points = coordinates(List.of(value == null ? fallback : value));
		return points.isEmpty() ? fallback : points.getFirst();
	}

	private static List<List<Double>> coordinates(Object value) {
		if (!(value instanceof List<?> list))
			return List.of();
		var result = new ArrayList<List<Double>>();
		for (Object p : list) {
			if (!(p instanceof List<?> xy) || xy.size() != 2 || !(xy.get(0) instanceof Number x)
					|| !(xy.get(1) instanceof Number y))
				return List.of();
			result.add(List.of(x.doubleValue(), y.doubleValue()));
		}
		return result;
	}

	private static double[] along(List<List<Double>> path, double fraction) {
		double length = 0;
		for (int i = 1; i < path.size(); i++)
			length += Math.hypot(path.get(i).get(0) - path.get(i - 1).get(0),
					path.get(i).get(1) - path.get(i - 1).get(1));
		double remain = Math.max(0, Math.min(1, fraction)) * length;
		for (int i = 1; i < path.size(); i++) {
			List<Double> a = path.get(i - 1), b = path.get(i);
			double part = Math.hypot(b.get(0) - a.get(0), b.get(1) - a.get(1));
			if (remain <= part && part > 0)
				return new double[] {a.get(0) + (b.get(0) - a.get(0)) * remain / part,
						a.get(1) + (b.get(1) - a.get(1)) * remain / part};
			remain -= part;
		}
		return new double[] {path.getLast().get(0), path.getLast().get(1)};
	}

	private static double[] project(List<List<Double>> path, double x, double y) {
		double length = 0;
		for (int i = 1; i < path.size(); i++)
			length += Math.hypot(path.get(i).get(0) - path.get(i - 1).get(0),
					path.get(i).get(1) - path.get(i - 1).get(1));
		double traveled = 0, best = Double.POSITIVE_INFINITY, along = 0;
		for (int i = 1; i < path.size(); i++) {
			List<Double> a = path.get(i - 1), b = path.get(i);
			double dx = b.get(0) - a.get(0), dy = b.get(1) - a.get(1), square = dx * dx + dy * dy,
					part = Math.sqrt(square);
			double t = square == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.get(0)) * dx + (y - a.get(1)) * dy) / square));
			double distance = Math.hypot(x - a.get(0) - t * dx, y - a.get(1) - t * dy);
			if (distance < best) {
				best = distance;
				along = traveled + t * part;
			}
			traveled += part;
		}
		return new double[] {length == 0 ? 0 : along / length, best};
	}

	private static Map<String, Object> anchor(Row journey, long elapsed) {
		List<Map<String, Object>> legs = se.hirt.mcp.rpg.maps.SceneService.maps(journey.list("route_json"));
		double weight = legs.stream().mapToDouble(l -> ((Number) l.getOrDefault("minutes", 60)).doubleValue()).sum();
		double cursor = 0, fraction = elapsed / (double) journey.lng("total_seconds");
		for (Map<String, Object> leg : legs) {
			double part = ((Number) leg.getOrDefault("minutes", 60)).doubleValue() / Math.max(1, weight);
			if (fraction <= cursor + part || leg == legs.getLast()) {
				double along = Math.max(0, Math.min(1, (fraction - cursor) / part));
				var anchor = new LinkedHashMap<String, Object>();
				anchor.put("connection_ref", leg.get("connection_ref"));
				double startFraction = ((Number) leg.getOrDefault("start_fraction", 0)).doubleValue(),
						endFraction = ((Number) leg.getOrDefault("end_fraction", 1)).doubleValue();
				double roadFraction = startFraction + (endFraction - startFraction) * along;
				anchor.put("leg_fraction", roadFraction);
				anchor.put("journey_fraction", fraction);
				if (leg.get("distance_miles") instanceof Number miles)
					anchor.put("distance_along_miles", miles.doubleValue() * roadFraction);
				return anchor;
			}
			cursor += part;
		}
		return Map.of("journey_fraction", fraction, "from",
				Ref.ofNullable(Ref.LOCATION, journey.lng("from_location_id")) == null ? "UNKNOWN"
						: "location:" + journey.lng("from_location_id"));
	}

	private static Row current(Tx tx, long cid) {
		return tx.queryOne(
				"SELECT * FROM travel_journey WHERE campaign_id=? AND status IN ('ACTIVE','INTERRUPTED') ORDER BY id DESC LIMIT 1",
				cid).orElse(null);
	}

	private static Map<String, Object> publicAnchor(Map<String, Object> raw) {
		var anchor = new LinkedHashMap<>(raw);
		for (String key : List.of("leg_fraction", "journey_fraction"))
			if (anchor.get(key) instanceof Number value)
				anchor.put(key, java.math.BigDecimal.valueOf(value.doubleValue())
						.setScale(12, java.math.RoundingMode.HALF_UP).doubleValue());
		return anchor;
	}

	private static Map<String, Object> publicAnchorContainer(Map<String, Object> raw, String field) {
		var copy = new LinkedHashMap<>(raw);
		if (raw.get(field) instanceof Map<?, ?> anchor)
			copy.put(field, publicAnchor(se.hirt.mcp.rpg.maps.SceneService.map(anchor)));
		return copy;
	}

	private static Map<String, Object> view(Tx tx, Row j) {
		var result = new LinkedHashMap<String, Object>();
		result.put("active", List.of("ACTIVE", "INTERRUPTED").contains(j.str("status")));
		result.put("journey", "journey:" + j.id());
		result.put("status", j.str("status"));
		result.put("from", Ref.ofNullable(Ref.LOCATION, j.lng("from_location_id")));
		result.put("destination", "location:" + j.lng("to_location_id"));
		Roster roster = List.of("COMPLETED", "CANCELLED").contains(j.str("status"))
				? new Roster(j.list("movers_json").stream().map(String::valueOf).toList(), List.of())
				: roster(tx, j, null);
		result.put("movers", roster.movers());
		result.put("removed_travelers", removedTravelers(tx, j, roster));
		if (!roster.removed().isEmpty())
			result.put("roster_reconciliation_pending", true);
		result.put("progress_seconds", j.lng("elapsed_seconds"));
		result.put("total_seconds", j.lng("total_seconds"));
		result.put("remaining_seconds", j.lng("total_seconds") - j.lng("elapsed_seconds"));
		result.put("anchor", publicAnchor(anchor(j, j.lng("elapsed_seconds"))));
		Map<String, Object> opportunity = publicAnchorContainer(j.map("opportunity_json"), "anchor");
		result.put("opportunity", opportunity);
		if (opportunity.get("contact_location") instanceof String contact)
			result.put("contact_location", contact);
		result.put("last_tactical_position", publicAnchorContainer(j.map("tactical_anchor_json"), "route_anchor"));
		if (Boolean.TRUE.equals(result.get("active")) && j.lng("elapsed_seconds") >= j.lng("total_seconds")) {
			var pending = se.hirt.mcp.rpg.maps.WorldMapPreparation.pendingEntry(tx, j.lng("campaign_id"),
					j.lng("to_location_id"));
			if (!pending.isEmpty())
				result.put("preparation_required", pending);
		}
		result.put("revision", j.lng("revision"));
		return result;
	}

	private static void validatePolicy(Map<String, Object> policy) {
		long frequency = WorldTimeline.number(policy, "frequency_seconds", 14400);
		double chance = policy.get("chance_percent") instanceof Number n ? n.doubleValue() : 0;
		if (frequency < 60 || frequency > 86400 || !Double.isFinite(chance) || chance < 0 || chance > 100)
			throw RpgException
					.invalidArgument("Travel risk frequency must be 60–86400 seconds and chance_percent 0–100.");
		if (policy.get("authored_at_fraction") instanceof Number f
				&& (!Double.isFinite(f.doubleValue()) || f.doubleValue() < 0 || f.doubleValue() > 1))
			throw RpgException.invalidArgument("authored_at_fraction must be between zero and one.");
	}
}
