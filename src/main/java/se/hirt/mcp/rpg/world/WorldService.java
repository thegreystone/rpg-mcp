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
package se.hirt.mcp.rpg.world;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.*;

/**
 * Semantic maps and movement (DESIGN.md §20, DOMAIN_MODEL.md §12, MCP_PROTOCOL.md §12.2/§12.5):
 * locations are a containment tree joined by connections; detail is generated when relevant and
 * then persisted (I-39); movement needs a known traversable route or an explicit authorized one
 * (I-40).
 */
public final class WorldService {

	public static final Set<String> KINDS = Set.of("REGION", "SETTLEMENT", "DISTRICT", "SITE", "BUILDING", "AREA");
	public static final Set<String> CONNECTION_KINDS = Set.of("ROAD", "PATH", "TRAIL", "RIVER", "SEA_ROUTE", "STREET",
			"DOOR", "PASSAGE", "STAIRS", "PORTAL", "OTHER");
	public static final Set<String> CONNECTION_STATES = Set.of("OPEN", "LOCKED", "BLOCKED", "SECRET");
	private static final int DEFAULT_TRAVEL_MINUTES = 60;

	private final Database db;
	private final se.hirt.mcp.rpg.content.RulesData rules;
	private final se.hirt.mcp.rpg.dice.RollService roller;

	public WorldService(Database db, se.hirt.mcp.rpg.content.RulesData rules, se.hirt.mcp.rpg.dice.RollService roller) {
		this.db = db;
		this.rules = rules;
		this.roller = roller;
	}

	// ── materialize_location ───────────────────────────────────────────

	/**
	 * Creates and/or materializes a location: an existing semantic node (by ref) or a new one under
	 * a parent. Features, secrets, child areas and connections are persisted; later reads agree
	 * (I-39).
	 */
	@SuppressWarnings("unchecked")
	public Map<String, Object> materialize(
		String operationId, String campaignRef, String locationRef, Map<String, Object> spec, String provenance) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("location", locationRef);
		args.put("spec", spec);
		String prov = provenance == null || provenance.isBlank() ? "GM" : provenance.toUpperCase();
		return db.mutate(Database.Mutation.of("materialize_location", campaignId, operationId, prov, args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "materialize_location");
			Map<String, Object> s = spec == null ? new LinkedHashMap<>() : spec;
			Row location;
			boolean created = false;
			if (locationRef != null && !locationRef.isBlank()) {
				location = location(tx, campaignId, locationRef);
				if ("MATERIALIZED".equals(location.str("materialization")) && !Boolean.TRUE.equals(s.get("extend"))) {
					throw RpgException.notAllowed(locationRef
							+ " is already materialized; pass extend=true to add detail without contradicting it, "
							+ "or use apply_gm_override to correct committed geography (I-41).");
				}
			} else {
				if (s.get("name") == null) {
					throw RpgException
							.invalidArgument("Provide an existing location reference or a spec with at least a name.");
				}
				Long parentId = null;
				if (s.get("parent") != null) {
					parentId = location(tx, campaignId, String.valueOf(s.get("parent"))).id();
				}
				long id = insertLocation(tx, campaignId, String.valueOf(s.get("name")), kind(s.get("kind"), "SITE"),
						s.get("description") == null ? null : s.get("description").toString(), parentId, "SEMANTIC",
						s.get("tags"));
				location = tx.get("location", id);
				created = true;
			}
			var cols = new LinkedHashMap<String, Object>();
			if (s.get("description") != null && !created) {
				cols.put("description", s.get("description").toString());
			}
			if (s.get("kind") != null && !created) {
				cols.put("kind", kind(s.get("kind"), location.str("kind")));
			}
			// Features: additive; an existing feature name cannot be redefined (that would contradict committed truth).
			List<Map<String, Object>> features = location.isNull("features_json") ? new ArrayList<>()
					: (List<Map<String, Object>>) (List<?>) location.list("features_json");
			var newFeatures = new ArrayList<Map<String, Object>>();
			for (Map<String, Object> f : (List<Map<String, Object>>) s.getOrDefault("features", List.of())) {
				String name = String.valueOf(f.get("name"));
				if (features.stream()
						.anyMatch(existing -> name.equalsIgnoreCase(String.valueOf(existing.get("name"))))) {
					throw RpgException.validation(List
							.of(new Violation("features", "CONTRADICTION", "Feature '" + name + "' already exists at "
									+ location.str("name") + "; committed detail cannot be redefined (I-41).")));
				}
				var feature = new LinkedHashMap<String, Object>();
				feature.put("name", name);
				feature.put("description", f.get("description"));
				feature.put("visibility",
						f.get("visibility") == null ? "PARTY_KNOWN" : f.get("visibility").toString().toUpperCase());
				if (!LedgerService.VISIBILITY.contains(String.valueOf(feature.get("visibility")))) {
					throw RpgException
							.invalidArgument("Feature visibility must be one of " + LedgerService.VISIBILITY + ".");
				}
				features.add(feature);
				newFeatures.add(feature);
			}
			for (Map<String, Object> secret : (List<Map<String, Object>>) s.getOrDefault("secrets", List.of())) {
				var feature = new LinkedHashMap<String, Object>(secret);
				feature.put("visibility", "GM_ONLY");
				features.add(feature);
				newFeatures.add(feature);
			}
			cols.put("features_json", Json.write(features));
			cols.put("materialization", "MATERIALIZED");
			if (s.get("generation_seed") instanceof Number seed) {
				cols.put("generation_seed", seed.longValue());
			}
			cols.put("revision", location.lng("revision") + 1);
			tx.update("location", location.id(), cols);
			var children = new ArrayList<Map<String, Object>>();
			for (Map<String, Object> child : (List<Map<String, Object>>) s.getOrDefault("children", List.of())) {
				NestedLocation resolved = nestedLocation(tx, campaignId, location.id(), child, "AREA", "children");
				long id = resolved.row().id();
				if (resolved.created()) {
					children.add(summary(resolved.row()));
				}
				if (child.get("connection") == null || Boolean.TRUE.equals(child.get("connection"))) {
					boolean newEdge = connection(tx, campaignId, location.id(), id).isEmpty();
					if (newEdge || child.get("connection_kind") != null) {
						connect(tx, campaignId, location.id(), id,
								child.get("connection_kind") == null ? "PASSAGE"
										: String.valueOf(child.get("connection_kind")),
								null, newEdge ? 5 : null, null, null);
					}
				}
			}
			var connections = new ArrayList<Map<String, Object>>();
			for (Map<String, Object> c : (List<Map<String, Object>>) s.getOrDefault("connections", List.of())) {
				long otherId;
				if (c.get("to") instanceof Map<?, ?> other) {
					Map<String, Object> o = (Map<String, Object>) other;
					Long parent = o.get("parent") != null
							? Long.valueOf(location(tx, campaignId, String.valueOf(o.get("parent"))).id())
							: o.get("ref") != null ? location(tx, campaignId, o.get("ref").toString()).lng("parent_id")
									: location.lng("parent_id");
					otherId = nestedLocation(tx, campaignId, parent, o, "SITE", "connections.to").row().id();
				} else {
					otherId = location(tx, campaignId, String.valueOf(c.get("to"))).id();
				}
				Integer minutes = c.get("travel_minutes") instanceof Number n ? n.intValue() : null;
				Object miles = c.get("distance_miles");
				connections.add(connect(tx, campaignId, location.id(), otherId,
						c.get("kind") == null ? null : String.valueOf(c.get("kind")),
						c.get("state") == null ? null : String.valueOf(c.get("state")).toUpperCase(), minutes, miles,
						c.get("visibility") == null ? null : String.valueOf(c.get("visibility")).toUpperCase()));
			}
			Row after = tx.get("location", location.id());
			tx.touched(Ref.of(Ref.LOCATION, after.id()), after.lng("revision"));
			var result = new LinkedHashMap<String, Object>(detail(tx, after));
			result.put("created", created);
			result.put("new_features", newFeatures);
			result.put("new_children", children);
			result.put("new_connections", connections);
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	private record NestedLocation(Row row, boolean created) {
	}

	/**
	 * Nested declarations name existing places; only an explicit top-level create always inserts.
	 */
	private static NestedLocation nestedLocation(
		Tx tx, long campaignId, Long parentId, Map<String, Object> spec, String defaultKind, String path) {
		if (spec.get("parent") != null
				&& !Objects.equals(parentId, location(tx, campaignId, spec.get("parent").toString()).id())) {
			throw RpgException
					.invalidArgument("The explicit parent in " + path + " must match its containing location.");
		}
		if (spec.get("name") != null && !(spec.get("name") instanceof String)) {
			throw RpgException.invalidArgument("Location name in " + path + " must be text.");
		}
		String name = spec.get("name") == null ? null : spec.get("name").toString().strip();
		Row existing;
		if (spec.get("ref") != null) {
			existing = location(tx, campaignId, spec.get("ref").toString());
			if (!Objects.equals(parentId, existing.lng("parent_id"))) {
				throw RpgException.invalidArgument("The location reference in " + path + " must belong to its parent.");
			}
			if (name != null && !name.equalsIgnoreCase(existing.str("name").strip())) {
				throw nestedContradiction(path, "name", existing);
			}
		} else {
			if (name == null || name.isBlank()) {
				throw RpgException.invalidArgument("Provide a nonblank name or location ref in " + path + ".");
			}
			List<Row> matches = tx.query("SELECT * FROM location WHERE campaign_id = ? AND parent_id IS ? ORDER BY id",
					campaignId, parentId).stream().filter(row -> name.equalsIgnoreCase(row.str("name").strip()))
					.toList();
			if (matches.size() > 1) {
				throw RpgException
						.invalidArgument("More than one location named '" + name + "' exists under this parent; "
								+ "supply its location ref in " + path + " to choose one.")
						.withDetail("candidates", matches.stream().map(WorldService::summary).toList());
			}
			existing = matches.isEmpty() ? null : matches.getFirst();
		}
		if (existing == null) {
			long id = insertLocation(tx, campaignId, name, kind(spec.get("kind"), defaultKind),
					spec.get("description") == null ? null : spec.get("description").toString(), parentId, "SEMANTIC",
					spec.get("tags"));
			return new NestedLocation(tx.get("location", id), true);
		}
		if (spec.get("kind") != null && !kind(spec.get("kind"), defaultKind).equals(existing.str("kind"))) {
			throw nestedContradiction(path, "kind", existing);
		}
		if (spec.get("description") != null
				&& !spec.get("description").toString().equals(existing.str("description"))) {
			throw nestedContradiction(path, "description", existing);
		}
		if (spec.get("tags") != null
				&& !spec.get("tags").equals(existing.isNull("tags_json") ? List.of() : existing.list("tags_json"))) {
			throw nestedContradiction(path, "tags", existing);
		}
		return new NestedLocation(existing, false);
	}

	private static RpgException nestedContradiction(String path, String field, Row existing) {
		String ref = Ref.of(Ref.LOCATION, existing.id());
		return RpgException.validation(List.of(new Violation(path + "." + field, "CONTRADICTION",
				"The supplied " + field + " contradicts " + ref
						+ ". Materialize that location by reference to add detail, "
						+ "or use apply_gm_override to correct committed geography (I-41).")));
	}

	public static String kind(Object value, String fallback) {
		if (value == null) {
			return fallback;
		}
		String k = value.toString().toUpperCase();
		if (k.equals("ROOM")) {
			k = "AREA";
		}
		if (!KINDS.contains(k)) {
			throw RpgException.invalidArgument("Location kind must be one of " + KINDS + ".");
		}
		return k;
	}

	public static long insertLocation(
		Tx tx, long campaignId, String name, String kind, String description, Long parentId, String materialization,
		Object tags) {
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("kind", kind);
		cols.put("parent_id", parentId);
		cols.put("name", name);
		cols.put("description", description);
		cols.put("materialization", materialization);
		cols.put("tags_json", tags == null ? null : Json.write(tags));
		cols.put("revision", 0);
		return tx.insert("location", cols);
	}

	/**
	 * One row per undirected edge (a < b); null fields preserve existing values, with defaults only
	 * for new edges.
	 */
	public static Map<String, Object> connect(
		Tx tx, long campaignId, long a, long b, String kind, String state, Integer minutes, Object miles,
		String visibility) {
		if (a == b) {
			throw RpgException.invalidArgument("A location cannot connect to itself.");
		}
		long lo = Math.min(a, b);
		long hi = Math.max(a, b);
		Optional<Row> existing = connection(tx, campaignId, a, b);
		String k = kind == null ? existing.map(row -> row.str("kind")).orElse("PATH") : kind.toUpperCase();
		if (!CONNECTION_KINDS.contains(k)) {
			throw RpgException.invalidArgument("Connection kind must be one of " + CONNECTION_KINDS + ".");
		}
		if (state != null && !CONNECTION_STATES.contains(state)) {
			throw RpgException.invalidArgument("Connection state must be one of " + CONNECTION_STATES + ".");
		}
		var stateJson = new LinkedHashMap<>(existing.map(row -> row.map("state_json")).orElse(Map.of()));
		if (state != null) {
			stateJson.put("state", state);
		}
		if (visibility != null) {
			stateJson.put("visibility", visibility);
		}
		var distance = new LinkedHashMap<>(existing.map(row -> row.map("distance_json")).orElse(Map.of()));
		if (minutes != null) {
			distance.put("travel_minutes", minutes);
		}
		if (miles != null) {
			distance.put("distance_miles", miles);
		}
		long id;
		if (existing.isPresent()) {
			tx.update("location_connection", existing.get().id(),
					Map.of("kind", k, "state_json", Json.write(stateJson), "distance_json", Json.write(distance)));
			id = existing.get().id();
		} else {
			stateJson.putIfAbsent("state", "OPEN");
			stateJson.putIfAbsent("visibility", "PARTY_KNOWN");
			distance.putIfAbsent("travel_minutes", DEFAULT_TRAVEL_MINUTES);
			var cols = new LinkedHashMap<String, Object>();
			cols.put("campaign_id", campaignId);
			cols.put("location_a_id", lo);
			cols.put("location_b_id", hi);
			cols.put("kind", k);
			cols.put("state_json", Json.write(stateJson));
			cols.put("distance_json", Json.write(distance));
			id = tx.insert("location_connection", cols);
		}
		return connectionSummary(tx, tx.get("location_connection", id), a);
	}

	private static Optional<Row> connection(Tx tx, long campaignId, long a, long b) {
		return tx.queryOne(
				"SELECT * FROM location_connection WHERE campaign_id = ? AND location_a_id = ? AND location_b_id = ?",
				campaignId, Math.min(a, b), Math.max(a, b));
	}

	static Map<String, Object> connectionSummary(Tx tx, Row c, long from) {
		long other = c.lng("location_a_id") == from ? c.lng("location_b_id") : c.lng("location_a_id");
		Row o = tx.get("location", other);
		var m = new LinkedHashMap<String, Object>();
		m.put("to", Ref.of(Ref.LOCATION, other));
		m.put("name", o.str("name"));
		m.put("kind", c.str("kind"));
		Map<String, Object> state = c.map("state_json");
		m.put("state", state.get("state"));
		m.put("visibility", state.get("visibility"));
		m.putAll(c.map("distance_json"));
		m.put("materialization", o.str("materialization"));
		return m;
	}

	public static Row location(Tx tx, long campaignId, String ref) {
		Row l = tx.find("location", Ref.id(ref, Ref.LOCATION))
				.orElseThrow(() -> RpgException.notFound("Location " + ref));
		if (l.lng("campaign_id") != campaignId) {
			throw RpgException.invalidArgument(ref + " belongs to another campaign.");
		}
		return l;
	}

	public static Map<String, Object> summary(Row l) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of(Ref.LOCATION, l.id()));
		m.put("name", l.str("name"));
		m.put("kind", l.str("kind"));
		m.put("materialization", l.str("materialization"));
		m.put("parent", Ref.ofNullable(Ref.LOCATION, l.lng("parent_id")));
		return m;
	}

	/**
	 * Full location view for the GM: features with visibility labels, connections, children,
	 * occupants.
	 */
	public static Map<String, Object> detail(Tx tx, Row l) {
		return detail(tx, l, false);
	}

	/** Full location view including Director-only features and connections. */
	public static Map<String, Object> directorDetail(Tx tx, Row l) {
		return detail(tx, l, true);
	}

	private static Map<String, Object> detail(Tx tx, Row l, boolean includeDirector) {
		var m = summary(l);
		m.put("description", l.str("description"));
		m.put("tags", l.isNull("tags_json") ? List.of() : l.list("tags_json"));
		m.put("features",
				l.isNull("features_json") ? List.of()
						: l.list("features_json").stream()
								.filter(f -> includeDirector || !(f instanceof Map<?, ?> feature)
										|| !"DIRECTOR_ONLY".equalsIgnoreCase(String.valueOf(feature.get("visibility"))))
								.toList());
		var path = new ArrayList<String>();
		Row cursor = l;
		while (cursor != null && !cursor.isNull("parent_id")) {
			cursor = tx.find("location", cursor.lng("parent_id")).orElse(null);
			if (cursor != null) {
				path.add(0, cursor.str("name"));
			}
		}
		m.put("within", path);
		m.put("connections", tx.query(
				"SELECT * FROM location_connection WHERE campaign_id = ? AND (location_a_id = ? OR location_b_id = ?) ORDER BY id",
				l.lng("campaign_id"), l.id(), l.id()).stream()
				.filter(c -> includeDirector
						|| !"DIRECTOR_ONLY".equalsIgnoreCase(String.valueOf(c.map("state_json").get("visibility"))))
				.map(c -> connectionSummary(tx, c, l.id())).toList());
		m.put("children", tx.query("SELECT * FROM location WHERE parent_id = ? ORDER BY id", l.id()).stream()
				.map(WorldService::summary).toList());
		m.put("characters_present", tx.query(
				"SELECT id, name, life_state FROM character WHERE location_id = ? AND lifecycle = 'ACTIVE' ORDER BY id",
				l.id()).stream()
				.map(c -> Ref.of(Ref.CHARACTER, c.id()) + " (" + c.str("name")
						+ (!"ALIVE".equals(c.str("life_state")) ? ", " + c.str("life_state").toLowerCase() : "") + ")")
				.toList());
		m.put("items_here", tx.count("SELECT COUNT(*) FROM inventory_entry WHERE location_id = ?", l.id()));
		m.put("revision", l.lng("revision"));
		return m;
	}

	// ── move_party ─────────────────────────────────────────────────────

	public Map<String, Object> move(
		String operationId, String campaignRef, String toRef, List<String> characterRefs, boolean authorizedRoute,
		Integer travelMinutes, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("to", toRef);
		args.put("characters", characterRefs);
		args.put("authorized_route", authorizedRoute);
		args.put("travel_minutes", travelMinutes);
		args.put("reason", reason);
		return db.mutate(Database.Mutation.of("move_party", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "move_party");
			Row destination = location(tx, campaignId, toRef);
			Row from = campaign.isNull("current_location_id") ? null
					: tx.get("location", campaign.lng("current_location_id"));
			if (from != null && from.id() == destination.id()) {
				throw RpgException.invalidArgument("The party is already at " + destination.str("name") + ".");
			}
			var movers = new ArrayList<Row>();
			var leftBehind = new ArrayList<Map<String, Object>>();
			if (characterRefs == null || characterRefs.isEmpty()) {
				// The party is whoever is with it: active members and guests standing where the party stands. A guest
				// left at the manor, or a member who stayed behind without being separated, does not teleport.
				for (Row m : tx.query(
						"SELECT c.* FROM party_membership m JOIN character c ON c.id = m.character_id WHERE m.campaign_id = ? "
								+ "AND m.state IN ('ACTIVE','GUEST') AND c.lifecycle = 'ACTIVE' AND c.life_state <> 'DEAD' ORDER BY m.id",
						campaignId)) {
					boolean here = from == null || m.isNull("location_id") || m.lng("location_id") == from.id();
					if (here) {
						movers.add(m);
					} else {
						var stay = new LinkedHashMap<String, Object>();
						stay.put("character", Ref.of(Ref.CHARACTER, m.id()));
						stay.put("name", m.str("name"));
						stay.put("location", tx.find("location", m.lng("location_id"))
								.map(l -> Ref.of(Ref.LOCATION, l.id()) + " (" + l.str("name") + ")").orElse(null));
						leftBehind.add(stay);
					}
				}
			} else {
				for (String ref : characterRefs) {
					Row c = CharacterService.character(tx, campaignId, ref);
					if (!"ACTIVE".equals(c.str("lifecycle")) || "DEAD".equals(c.str("life_state"))) {
						throw RpgException.notAllowed(ref + " cannot travel.");
					}
					movers.add(c);
				}
			}
			if (movers.isEmpty()) {
				throw RpgException.notAllowed("Nobody can travel.");
			}
			List<Row> path = from == null ? List.of() : route(tx, campaignId, from.id(), destination.id());
			long minutes;
			var hops = new ArrayList<Map<String, Object>>();
			if (from != null && path.isEmpty()) {
				if (!authorizedRoute) {
					throw RpgException.validation(List.of(new Violation("to", "NO_KNOWN_ROUTE",
							"No known traversable route from " + from.str("name") + " to " + destination.str("name")
									+ ". Materialize the connection (materialize_location with connections) or pass authorized_route=true with a reason and travel_minutes (I-40).")));
				}
				if (reason == null || reason.isBlank()) {
					throw RpgException.invalidArgument("An authorized route needs a reason.");
				}
				minutes = travelMinutes == null ? DEFAULT_TRAVEL_MINUTES : Math.max(1, travelMinutes);
				connect(tx, campaignId, from.id(), destination.id(), "OTHER", "OPEN", (int) minutes, null,
						"PARTY_KNOWN");
			} else {
				minutes = 0;
				long cursor = from == null ? destination.id() : from.id();
				for (Row edge : path) {
					long next = edge.lng("location_a_id") == cursor ? edge.lng("location_b_id")
							: edge.lng("location_a_id");
					Map<String, Object> distance = edge.map("distance_json");
					long hop = distance.get("travel_minutes") instanceof Number n ? n.longValue()
							: DEFAULT_TRAVEL_MINUTES;
					minutes += hop;
					hops.add(Map.of("to", Ref.of(Ref.LOCATION, next), "name", tx.get("location", next).str("name"),
							"minutes", hop, "via", edge.str("kind")));
					cursor = next;
				}
				if (travelMinutes != null) {
					minutes = Math.max(1, travelMinutes);
				}
			}
			Row clock = GameTime.clock(tx, campaignId);
			long newSeq = clock.lng("seq") + minutes;
			tx.update("game_clock", clock.id(), Map.of("seq", newSeq, "instant", GameTime.render(newSeq)));
			int expiredEffects = se.hirt.mcp.rpg.rules.Effects.expireByTime(tx, campaignId, newSeq);
			boolean pcMoved = false;
			Long pcId = tx.queryOne(
					"SELECT character_id AS id FROM player_control_assignment WHERE campaign_id = ? AND active = 1",
					campaignId).map(Row::id).orElse(null);
			for (Row c : movers) {
				tx.update("character", c.id(),
						Map.of("location_id", destination.id(), "revision", c.lng("revision") + 1));
				if (pcId != null && c.id() == pcId) {
					pcMoved = true;
				}
			}
			var ccols = new LinkedHashMap<String, Object>();
			if (pcMoved || pcId == null) {
				ccols.put("current_location_id", destination.id());
			}
			ccols.put("revision", campaign.lng("revision") + 1);
			tx.update("campaign", campaignId, ccols);
			String names = String.join(", ", movers.stream().map(c -> c.str("name")).toList());
			long eventId = LedgerService.append(tx, campaignId, new LedgerService.EventSpec("PARTY_MOVED",
					names + " travelled from " + (from == null ? "nowhere" : from.str("name")) + " to "
							+ destination.str("name") + " (" + GameTime.render(newSeq) + ")"
							+ (reason == null || reason.isBlank() ? "." : ": " + reason),
					movers.stream().map(Row::id).toList(), minutes >= GameTime.MINUTES_PER_DAY ? "NOTABLE" : "MINOR",
					"PARTY_KNOWN", "GM", null, destination.id(), null,
					Map.of("from", Ref.ofNullable(Ref.LOCATION, from == null ? null : from.id()), "minutes", minutes)));
			var result = new LinkedHashMap<String, Object>();
			result.put("from", from == null ? null : summary(from));
			result.put("arrived_at", detail(tx, tx.get("location", destination.id())));
			result.put("moved", movers.stream().map(c -> Ref.of(Ref.CHARACTER, c.id())).toList());
			if (!leftBehind.isEmpty()) {
				result.put("left_behind", leftBehind);
			}
			result.put("route", hops);
			result.put("travel_minutes", minutes);
			result.put("game_time", GameTime.toMap(tx, campaignId, newSeq));
			result.put("event", Ref.of(Ref.EVENT, eventId));
			result.put("interrupted", false);
			var consequences = new ArrayList<Object>();
			if (expiredEffects > 0) {
				consequences.add(expiredEffects + " timed effect(s) expired");
			}
			consequences
					.addAll(se.hirt.mcp.rpg.economy.Scheduler.onClockAdvance(tx, campaignId, clock.lng("seq"), newSeq));
			String due = se.hirt.mcp.rpg.session.ChronicleService.dueWarning(tx, campaignId);
			if (due != null) {
				consequences.add(due);
			}
			// Travel encounters (RULES_ENGINE.md, "Travel encounters"): a suggestion the GM may take or ignore; the
			// engine changes no state for it. The roll is made through the RollService so tests can script it.
			Map<String, Object> suggestion = TravelEncounters.suggest(tx, rules, roller, campaignId, movers,
					from == null ? List.of() : path, destination, minutes);
			if (suggestion != null) {
				consequences.add(suggestion);
			}
			result.put("consequences", consequences);
			var trigger = new LinkedHashMap<String, Object>();
			boolean significant = minutes >= GameTime.MINUTES_PER_DAY;
			var reasons = new ArrayList<String>();
			if (significant) {
				reasons.add("SIGNIFICANT_TRAVEL");
			}
			if (suggestion != null) {
				reasons.add("TRAVEL_ENCOUNTER_SUGGESTED");
			}
			trigger.put("recommended", !reasons.isEmpty());
			trigger.put("reasons", reasons);
			trigger.put("urgency", reasons.isEmpty() ? "NONE" : "NORMAL");
			result.put("director_trigger", trigger);
			var warnings = new ArrayList<String>();
			if ("SEMANTIC".equals(destination.str("materialization"))) {
				warnings.add(destination.str("name")
						+ " is only a semantic node; materialize_location it now that the party has arrived.");
			}
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), warnings));
			return result;
		});
	}

	/**
	 * Breadth-first search over traversable, party-known connections; returns the edges of the path
	 * (empty = none).
	 */
	static List<Row> route(Tx tx, long campaignId, long from, long to) {
		Map<Long, List<Row>> adjacency = new HashMap<>();
		for (Row c : tx.query("SELECT * FROM location_connection WHERE campaign_id = ?", campaignId)) {
			Map<String, Object> state = c.map("state_json");
			String s = String.valueOf(state.get("state"));
			String vis = String.valueOf(state.getOrDefault("visibility", "PARTY_KNOWN"));
			boolean traversable = s.equals("OPEN")
					|| (s.equals("SECRET") && !vis.equals("GM_ONLY") && !vis.equals("DIRECTOR_ONLY"));
			if (!traversable) {
				continue;
			}
			adjacency.computeIfAbsent(c.lng("location_a_id"), k -> new ArrayList<>()).add(c);
			adjacency.computeIfAbsent(c.lng("location_b_id"), k -> new ArrayList<>()).add(c);
		}
		Map<Long, Row> via = new HashMap<>();
		Set<Long> seen = new HashSet<>();
		var queue = new ArrayDeque<Long>();
		queue.add(from);
		seen.add(from);
		while (!queue.isEmpty()) {
			long current = queue.poll();
			if (current == to) {
				var path = new ArrayList<Row>();
				long cursor = to;
				while (cursor != from) {
					Row edge = via.get(cursor);
					path.add(0, edge);
					cursor = edge.lng("location_a_id") == cursor ? edge.lng("location_b_id")
							: edge.lng("location_a_id");
				}
				return path;
			}
			for (Row edge : adjacency.getOrDefault(current, List.of())) {
				long next = edge.lng("location_a_id") == current ? edge.lng("location_b_id")
						: edge.lng("location_a_id");
				if (seen.add(next)) {
					via.put(next, edge);
					queue.add(next);
				}
			}
		}
		return List.of();
	}

	/** Ancestor chain including the location itself (innermost first). */
	public static List<Long> lineage(Tx tx, long locationId) {
		var out = new ArrayList<Long>();
		Long cursor = locationId;
		int guard = 0;
		while (cursor != null && guard++ < 32) {
			out.add(cursor);
			Row l = tx.find("location", cursor).orElse(null);
			cursor = l == null ? null : l.lng("parent_id");
		}
		return out;
	}
}
