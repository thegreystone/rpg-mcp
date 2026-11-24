/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg.world;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Combat;
import se.hirt.mcp.rpg.rules.Effects;
import se.hirt.mcp.rpg.rules.Exhaustion;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.spatial.Geometry.Footprint;
import se.hirt.mcp.rpg.spatial.SpatialRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved routines for existing actors. Consequential interruptions suspend routines rather than
 * respawn guards.
 */
public final class PatrolService {

	private final Database db;

	public PatrolService(Database db) {
		this.db = db;
	}

	public Map<String, Object> define(String op, String campaignRef, Map<String, Object> spec) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(Database.Mutation.of("define_patrol", cid, op, "GM", spec), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "define_patrol");
			List<?> actors = spec.get("actors") instanceof List<?> a ? a : List.of();
			List<Map<String, Object>> route = SceneService.maps(spec.get("route"));
			if (actors.isEmpty() || actors.size() > 20 || route.size() < 2 || route.size() > 100)
				throw RpgException.invalidArgument("A patrol needs 1–20 existing actors and 2–100 waypoints.");
			for (Object actor : actors) {
				Row c = CharacterService.character(tx, cid, String.valueOf(actor));
				if (!"ACTIVE".equals(c.str("lifecycle")) || !"ALIVE".equals(c.str("life_state")))
					throw RpgException.invalidArgument("Patrol members must be active living actors.");
				for (Row other : tx.query(
						"SELECT * FROM patrol_plan WHERE campaign_id=? AND status IN ('ACTIVE','PAUSED')", cid)) {
					if (other.list("actors_json").contains(String.valueOf(actor)))
						throw RpgException.conflict("This actor already belongs to patrol:" + other.id()
								+ "; change that duty instead of creating a duplicate patrol.");
				}
			}
			for (Map<String, Object> waypoint : route) {
				if (waypoint.get("location_ref") != null)
					WorldService.location(tx, cid, waypoint.get("location_ref").toString());
				if (waypoint.get("map_ref") != null) {
					SceneService.scene(tx, cid, waypoint.get("map_ref").toString());
					if (!(waypoint.get("x_ft") instanceof Number) || !(waypoint.get("y_ft") instanceof Number))
						throw RpgException.invalidArgument("Mapped patrol waypoints need x_ft and y_ft.");
				}
				long travel = WorldTimeline.number(waypoint, "travel_seconds", 60),
						dwell = WorldTimeline.number(waypoint, "dwell_seconds", 0);
				if (travel < 1 || travel > 86400 || dwell < 0 || dwell > 86400)
					throw RpgException
							.invalidArgument("Waypoint travel must be 1–86400 seconds and dwell 0–86400 seconds.");
			}
			long first = GameTime.currentSeconds(tx, cid) + WorldTimeline.number(route.get(0), "dwell_seconds", 0)
					+ WorldTimeline.number(route.get(1), "travel_seconds", 60);
			Map<String, Object> properties = SceneService.map(spec.get("properties"));
			Long region = spec.get("region_ref") != null
					? WorldService.location(tx, cid, spec.get("region_ref").toString()).id()
					: deriveRegion(tx, campaign, route);
			var columns = new LinkedHashMap<String, Object>();
			columns.put("campaign_id", cid);
			columns.put("name", String.valueOf(spec.getOrDefault("name", "Patrol")));
			columns.put("actors_json", Json.write(actors));
			columns.put("route_json", Json.write(route));
			columns.put("properties_json", Json.write(properties));
			columns.put("next_due_seconds", first);
			columns.put("region_id", region);
			long id = tx.insert("patrol_plan", columns);
			reconcileRegionalTimers(tx, cid);
			return Map.of("patrol", "patrol:" + id, "next_due_seconds", first, "audience", "GM", "meta",
					Harness.meta(campaign, null));
		});
	}

	public Map<String, Object> state(String campaignRef, String reference) {
		return db.read(tx -> {
			long cid = Harness.campaign(tx, campaignRef).id();
			Row p = tx.get("patrol_plan", Ref.id(reference, "patrol"));
			if (p.lng("campaign_id") != cid)
				throw RpgException.invalidArgument("Patrol belongs to another campaign.");
			var result = new LinkedHashMap<String, Object>(
					Map.of("patrol", reference, "name", p.str("name"), "status", p.str("status"), "actors",
							p.list("actors_json"), "waypoint_index", p.lng("waypoint_index"), "next_due_seconds",
							p.lng("next_due_seconds"), "properties", p.map("properties_json"), "audience", "GM"));
			result.put("region", Ref.ofNullable(Ref.LOCATION, p.lng("region_id")));
			result.put("timer_active", p.bool("timer_active"));
			result.put("remaining_seconds", p.lng("remaining_seconds"));
			result.put("revision", p.lng("revision"));
			return result;
		});
	}

	/**
	 * Change a saved duty after an explicit world decision; never replace its roster with fresh
	 * actors.
	 */
	public Map<String, Object> update(
		String op, String campaignRef, String reference, Long expectedRevision, Map<String, Object> changes) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(
				Database.Mutation.of("update_patrol", cid, op, "GM", Map.of("patrol", reference, "changes", changes)),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "update_patrol");
					Row plan = tx.get("patrol_plan", Ref.id(reference, "patrol"));
					if (plan.lng("campaign_id") != cid)
						throw RpgException.invalidArgument("Patrol belongs to another campaign.");
					Harness.requireRevision(plan, reference, expectedRevision);
					for (String field : changes.keySet())
						if (!List.of("status", "properties", "reason").contains(field))
							throw RpgException.invalidArgument(
									"Patrol updates accept status, properties and reason; reinforcement rosters require a new explicit world event.");
					if (changes.get("reason") == null || changes.get("reason").toString().isBlank())
						throw RpgException.invalidArgument("Explain the world decision that changes this duty.");
					String status = String.valueOf(changes.getOrDefault("status", plan.str("status")))
							.toUpperCase(java.util.Locale.ROOT);
					if (!List.of("ACTIVE", "PAUSED", "DONE").contains(status))
						throw RpgException.invalidArgument("Patrol status must be ACTIVE, PAUSED or DONE.");
					var properties = new LinkedHashMap<>(plan.map("properties_json"));
					properties.putAll(SceneService.map(changes.get("properties")));
					properties.put("last_duty_change", changes.get("reason"));
					if (status.equals("ACTIVE")) {
						boolean available = false;
						for (Object ref : plan.list("actors_json"))
							if (!unavailable(tx, CharacterService.character(tx, cid, String.valueOf(ref)),
									properties)) {
								available = true;
								break;
							}
						if (!available)
							throw RpgException.notAllowed(
									"The patrol has no available members; only a resolved change to its existing actors or an explicit reinforcement event can restore a duty.");
					}
					var columns = new LinkedHashMap<String, Object>();
					columns.put("status", status);
					columns.put("properties_json", Json.write(properties));
					columns.put("revision", plan.lng("revision") + 1);
					if (status.equals("PAUSED") && plan.str("status").equals("ACTIVE"))
						columns.put("remaining_seconds",
								Math.max(0, plan.lng("next_due_seconds") - GameTime.currentSeconds(tx, cid)));
					if (status.equals("ACTIVE") && !plan.str("status").equals("ACTIVE")) {
						List<Map<String, Object>> route = SceneService.maps(plan.list("route_json"));
						long delay = plan.lng("remaining_seconds") == null
								? WorldTimeline.number(route.get((plan.intOr("waypoint_index", 0) + 1) % route.size()),
										"travel_seconds", 60)
								: plan.lng("remaining_seconds");
						columns.put("next_due_seconds", GameTime.currentSeconds(tx, cid) + delay);
						columns.put("remaining_seconds", null);
						columns.put("timer_active", 1);
					}
					tx.update("patrol_plan", plan.id(), columns);
					reconcileRegionalTimers(tx, cid);
					return Map.of("patrol", reference, "status", tx.get("patrol_plan", plan.id()).str("status"),
							"revision", tx.get("patrol_plan", plan.id()).lng("revision"), "meta",
							Harness.meta(campaign, null));
				});
	}

	static Long nextDue(Tx tx, long cid, long until) {
		return tx.queryOne(
				"SELECT MIN(next_due_seconds) AS due FROM patrol_plan WHERE campaign_id=? AND status='ACTIVE' AND timer_active=1 AND next_due_seconds<=?",
				cid, until).filter(r -> !r.isNull("due")).map(r -> r.lng("due")).orElse(null);
	}

	static List<Map<String, Object>> advanceAt(Tx tx, long cid, long now) {
		var cues = new ArrayList<Map<String, Object>>();
		for (Row plan : tx.query(
				"SELECT * FROM patrol_plan WHERE campaign_id=? AND status='ACTIVE' AND timer_active=1 AND next_due_seconds<=? ORDER BY next_due_seconds,id",
				cid, now)) {
			List<Map<String, Object>> route = SceneService.maps(plan.list("route_json"));
			int next = (plan.intOr("waypoint_index", 0) + 1) % route.size();
			Map<String, Object> waypoint = route.get(next);
			var members = new ArrayList<Row>();
			boolean disrupted = false;
			boolean temporarilyBlocked = false;
			for (Object ref : plan.list("actors_json")) {
				Row actor = CharacterService.character(tx, cid, String.valueOf(ref));
				if (removedFromDuty(actor, plan.map("properties_json")))
					continue;
				if (movementBlocked(tx, actor)) {
					temporarilyBlocked = true;
					continue;
				}
				if (!actor.isNull("encounter_id")) {
					disrupted = true;
					break;
				}
				members.add(actor);
			}
			if (members.isEmpty() && !disrupted) {
				if (temporarilyBlocked)
					pauseBlockedDuty(tx, plan, now);
				else
					tx.update("patrol_plan", plan.id(),
							Map.of("status", "DONE", "timer_active", 0, "revision", plan.lng("revision") + 1));
				continue;
			}
			if (waypoint.get("connection_ref") != null) {
				Row edge = tx.get("location_connection",
						Ref.id(waypoint.get("connection_ref").toString(), Ref.CONNECTION));
				if (edge.lng("campaign_id") != cid || !"OPEN".equals(edge.map("state_json").get("state")))
					disrupted = true;
			}
			if (disrupted) {
				tx.update("patrol_plan", plan.id(), Map.of("status", "PAUSED", "revision", plan.lng("revision") + 1));
				continue;
			}
			Row scene = waypoint.get("map_ref") == null ? null
					: SceneService.scene(tx, cid, waypoint.get("map_ref").toString());
			var placements = new ArrayList<Map<String, Object>>();
			Row leader = members.isEmpty() ? null
					: tx.queryOne("SELECT * FROM map_actor WHERE campaign_id=? AND character_id=?", cid,
							members.getFirst().id()).orElse(null);
			for (Row member : members) {
				Row placement = tx
						.queryOne("SELECT * FROM map_actor WHERE campaign_id=? AND character_id=?", cid, member.id())
						.orElse(null);
				if (scene == null && placement != null && waypoint.get("location_ref") != null && !waypoint
						.get("location_ref").equals(Ref.ofNullable(Ref.LOCATION, member.lng("location_id")))) {
					disrupted = true;
					break;
				}
				if (scene != null) {
					double x = SceneService.number(waypoint, "x_ft", 0).doubleValue(),
							y = SceneService.number(waypoint, "y_ft", 0).doubleValue();
					if (leader != null && placement != null) {
						x += SceneService.number(placement.asMap(), "x_ft", 0).doubleValue()
								- SceneService.number(leader.asMap(), "x_ft", 0).doubleValue();
						y += SceneService.number(placement.asMap(), "y_ft", 0).doubleValue()
								- SceneService.number(leader.asMap(), "y_ft", 0).doubleValue();
					}
					int level = SceneService.number(waypoint, "level", scene.intOr("level", 0)).intValue(),
							size = placement == null ? 1 : SceneService.footprint(placement).columns();
					Footprint footprint = new Footprint(SceneService.point(x, y), size, size);
					if (!SpatialRules.canPlace(SceneService.geometry(SceneService.document(tx, scene), level),
							footprint, List.of())) {
						disrupted = true;
						break;
					}
					if (placement != null && placement.lng("scene_id") == scene.id()
							&& placement.intOr("level", 0) == level) {
						var path = SpatialRules.path(SceneService.geometry(SceneService.document(tx, scene), level),
								SceneService.footprint(placement), footprint.origin(), List.of(), 10_000, 1_000_000);
						long minimumSeconds = path.found()
								? (long) Math
										.ceil(path.costInches() / 12.0 * 6 / Math.max(1, movementSpeed(tx, member)))
								: Long.MAX_VALUE;
						if (!path.found() || minimumSeconds > WorldTimeline.number(waypoint, "travel_seconds", 60)) {
							disrupted = true;
							break;
						}
					} else if (placement != null && waypoint.get("connection_ref") == null) {
						disrupted = true;
						break;
					}
					placements.add(Map.of("character", member.id(), "existing", placement == null ? 0 : placement.id(),
							"x", x, "y", y, "level", level));
				}
			}
			if (disrupted) {
				tx.update("patrol_plan", plan.id(), Map.of("status", "PAUSED", "revision", plan.lng("revision") + 1));
				continue;
			}
			for (Map<String, Object> p : placements) {
				long existing = ((Number) p.get("existing")).longValue();
				var cols = new LinkedHashMap<String, Object>();
				cols.put("scene_id", scene.id());
				cols.put("x_ft", p.get("x"));
				cols.put("y_ft", p.get("y"));
				cols.put("level", p.get("level"));
				if (existing == 0) {
					cols.put("campaign_id", cid);
					cols.put("character_id", p.get("character"));
					tx.insert("map_actor", cols);
				} else {
					cols.put("revision", tx.get("map_actor", existing).lng("revision") + 1);
					tx.update("map_actor", existing, cols);
				}
			}
			if (waypoint.get("location_ref") != null)
				for (Row member : members)
					tx.update("character", member.id(),
							Map.of("location_id", Ref.id(waypoint.get("location_ref").toString(), Ref.LOCATION),
									"revision", member.lng("revision") + 1));
			long due = plan.lng("next_due_seconds") + WorldTimeline.number(waypoint, "dwell_seconds", 0)
					+ WorldTimeline.number(route.get((next + 1) % route.size()), "travel_seconds", 60);
			var properties = new LinkedHashMap<>(plan.map("properties_json"));
			if (scene != null) {
				List<Map<String, Object>> observations = observe(tx, cid, scene, members, properties, now);
				cues.addAll(observations);
				SceneService.remember(tx, scene);
			}
			tx.update("patrol_plan", plan.id(), Map.of("waypoint_index", next, "next_due_seconds", due,
					"properties_json", Json.write(properties), "revision", plan.lng("revision") + 1));
		}
		return cues;
	}

	/** Regional time is active while any party member, including a separated one, remains there. */
	public static void reconcileRegionalTimers(Tx tx, long cid) {
		long now = GameTime.currentSeconds(tx, cid);
		for (String table : List.of("patrol_plan", "timeline_event")) {
			String status = table.equals("patrol_plan") ? "ACTIVE" : "OPEN";
			for (Row row : tx.query("SELECT * FROM " + table + " WHERE campaign_id=? AND status=?"
					+ (table.equals("patrol_plan") ? "" : " AND region_id IS NOT NULL"), cid, status)) {
				if (table.equals("patrol_plan")) {
					boolean available = false, mobile = false;
					for (Object ref : row.list("actors_json")) {
						Row actor = CharacterService.character(tx, cid, String.valueOf(ref));
						if (!removedFromDuty(actor, row.map("properties_json"))) {
							available = true;
							mobile |= !movementBlocked(tx, actor);
						}
					}
					if (!available) {
						tx.update(table, row.id(),
								Map.of("status", "DONE", "timer_active", 0, "revision", row.lng("revision") + 1));
						continue;
					}
					if (!mobile) {
						pauseBlockedDuty(tx, row, now);
						continue;
					}
				}
				if (row.isNull("region_id"))
					continue;
				boolean present = partyPresent(tx, cid, row.lng("region_id"));
				boolean active = row.bool("timer_active");
				String dueColumn = table.equals("patrol_plan") ? "next_due_seconds" : "due_seconds";
				if (active && !present) {
					tx.update(table, row.id(), Map.of("remaining_seconds", Math.max(0, row.lng(dueColumn) - now),
							"timer_active", 0, "revision", row.lng("revision") + 1));
				} else if (!active && present) {
					var changes = new LinkedHashMap<String, Object>();
					changes.put(dueColumn,
							now + (row.lng("remaining_seconds") == null ? 0 : row.lng("remaining_seconds")));
					changes.put("remaining_seconds", null);
					changes.put("timer_active", 1);
					changes.put("revision", row.lng("revision") + 1);
					tx.update(table, row.id(), changes);
				}
			}
		}
	}

	private static boolean partyPresent(Tx tx, long cid, long region) {
		List<Row> party = tx.query(
				"SELECT DISTINCT c.* FROM character c LEFT JOIN party_membership p ON p.character_id=c.id AND p.campaign_id=c.campaign_id LEFT JOIN player_control_assignment pc ON pc.character_id=c.id AND pc.campaign_id=c.campaign_id WHERE c.campaign_id=? AND c.lifecycle='ACTIVE' AND c.life_state<>'DEAD' AND (p.state IN ('ACTIVE','GUEST','SEPARATED') OR pc.active=1)",
				cid);
		Row journey = tx
				.queryOne("SELECT * FROM travel_journey WHERE campaign_id=? AND status IN ('ACTIVE','INTERRUPTED')",
						cid)
				.orElse(null);
		for (Row actor : party) {
			if (actor.isNull("location_id") || !WorldService.lineage(tx, actor.lng("location_id")).contains(region))
				continue;
			if (journey != null && journey.list("movers_json").contains("character:" + actor.id())
					&& !WorldService.lineage(tx, journey.lng("to_location_id")).contains(region))
				continue;
			return true;
		}
		return false;
	}

	private static boolean unavailable(Tx tx, Row actor, Map<String, Object> properties) {
		return removedFromDuty(actor, properties) || movementBlocked(tx, actor);
	}

	private static boolean removedFromDuty(Row actor, Map<String, Object> properties) {
		if (!"ACTIVE".equals(actor.str("lifecycle")) || !"ALIVE".equals(actor.str("life_state"))
				|| actor.intOr("current_hp", 0) <= 0)
			return true;
		if (properties.get("unavailable_actors") instanceof List<?> refs && refs.contains("character:" + actor.id()))
			return true;
		return Boolean.TRUE.equals(actor.map("agenda_json").get("captured"));
	}

	private static boolean movementBlocked(Tx tx, Row actor) {
		var conditions = Combat.conditionsOf(tx, actor);
		return Combat.hasAny(conditions, Combat.INCAPACITATING_CONDITIONS)
				|| Combat.hasAny(conditions, Combat.SPEED_ZERO_CONDITIONS) || movementSpeed(tx, actor) <= 0;
	}

	private static int movementSpeed(Tx tx, Row actor) {
		return Math.max(0, actor.intOr("speed", 0) + Effects.modifiers(tx, actor.id()).speedBonus
				- 5 * Exhaustion.level(tx, actor));
	}

	private static void pauseBlockedDuty(Tx tx, Row plan, long now) {
		long remaining = !plan.bool("timer_active") && !plan.isNull("remaining_seconds") ? plan.lng("remaining_seconds")
				: Math.max(0, plan.lng("next_due_seconds") - now);
		tx.update("patrol_plan", plan.id(), Map.of("status", "PAUSED", "timer_active", 0, "remaining_seconds",
				remaining, "revision", plan.lng("revision") + 1));
	}

	private static Long deriveRegion(Tx tx, Row campaign, List<Map<String, Object>> route) {
		Long location = campaign.lng("current_location_id");
		Map<String, Object> first = route.getFirst();
		if (first.get("location_ref") != null)
			location = WorldService.location(tx, campaign.id(), first.get("location_ref").toString()).id();
		else if (first.get("map_ref") != null)
			location = SceneService.scene(tx, campaign.id(), first.get("map_ref").toString()).lng("location_id");
		if (location == null)
			return null;
		List<Long> lineage = WorldService.lineage(tx, location);
		for (long id : lineage)
			if ("REGION".equals(tx.get("location", id).str("kind")))
				return id;
		return lineage.getLast();
	}

	private static List<Map<String, Object>> observe(
		Tx tx, long cid, Row scene, List<Row> patrol, Map<String, Object> properties, long now) {
		var cues = new ArrayList<Map<String, Object>>();
		Map<String, Object> document = SceneService.document(tx, scene);
		List<Row> party = tx.query(
				"SELECT a.* FROM map_actor a JOIN party_membership p ON p.character_id=a.character_id WHERE a.scene_id=? AND p.campaign_id=? AND p.state IN ('ACTIVE','GUEST')",
				scene.id(), cid);
		boolean seen = false, heard = false, detects = false;
		var evidence = new ArrayList<Map<String, Object>>();
		for (Row guard : patrol) {
			Row gp = SceneService.actor(tx, cid, "character:" + guard.id());
			for (Row observer : party) {
				if (gp.intOr("level", 0) != observer.intOr("level", 0))
					continue;
				var geometry = SceneService.geometry(document, gp.intOr("level", 0));
				Row pc = tx.get("character", observer.lng("character_id"));
				seen |= canSee(geometry, pc, observer, gp, document);
				boolean contact = canSee(geometry, guard, gp, observer, document);
				detects |= contact;
				if (contact)
					evidence.add(Map.of("observer", "character:" + guard.id(), "target", "character:" + pc.id(),
							"last_seen_ft", SceneService.feet(SceneService.center(observer)), "at_seconds", now));
				double noise = SceneService.number(properties, "noise_range_ft", 0).doubleValue();
				var gc = SceneService.center(gp);
				var pcCenter = SceneService.center(observer);
				long distance = Math.max(Math.abs(gc.x() - pcCenter.x()), Math.abs(gc.y() - pcCenter.y()));
				heard |= noise > 0
						&& distance <= noise * 12 * (SpatialRules.lineOfSight(geometry, gc, pcCenter) ? 1 : .5);
			}
		}
		if (!evidence.isEmpty())
			properties.put("awareness", evidence);
		if (seen)
			cues.add(Map.of("kind", "PATROL_SEEN", "at_seconds", now, "visibility", "PARTY_KNOWN", "actors",
					patrol.stream().map(a -> "character:" + a.id()).toList()));
		else if (heard)
			cues.add(Map.of("kind", "PATROL_HEARD", "at_seconds", now, "visibility", "PARTY_KNOWN", "description",
					"Approaching footsteps are audible."));
		if (detects && !seen)
			cues.add(Map.of("kind", "PATROL_DETECTED_PARTY", "at_seconds", now, "visibility", "GM_ONLY"));
		return cues;
	}

	private static boolean canSee(
		se.hirt.mcp.rpg.spatial.Geometry.Scene geometry, Row character, Row from, Row target,
		Map<String, Object> document) {
		Map<String, Object> senses = character.map("senses_json");
		long range = Math.round(SceneService.number(senses, "sight_range_ft", 120).doubleValue() * 12), dark = Math
				.round(SceneService.number(senses, "darkvision", SceneService.number(senses, "darkvision_ft", 0))
						.doubleValue() * 12);
		return SpatialRules
				.perceive(geometry, new SpatialRules.Observer(SceneService.center(from), range, dark),
						SceneService.center(target), SpatialRules.Illumination.valueOf(SceneService
								.string(document, "illumination", "BRIGHT").toUpperCase(java.util.Locale.ROOT)))
				.visible();
	}
}
