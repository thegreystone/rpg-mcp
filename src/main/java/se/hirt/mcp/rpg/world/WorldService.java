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
	public static final Set<String> CONNECTION_STATES = Set.of("OPEN", "CLOSED", "LOCKED", "BLOCKED", "SECRET",
			"BREACHED", "DESTROYED");
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
						c.get("visibility") == null ? null : String.valueOf(c.get("visibility")).toUpperCase(),
						c.get("connection_ref") == null ? null : String.valueOf(c.get("connection_ref")),
						Boolean.TRUE.equals(c.get("create_new"))));
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
		return connect(tx, campaignId, a, b, kind, state, minutes, miles, visibility, null, false);
	}

	public static Map<String, Object> connect(
		Tx tx, long campaignId, long a, long b, String kind, String state, Integer minutes, Object miles,
		String visibility, String connectionRef, boolean createNew) {
		if (a == b) {
			throw RpgException.invalidArgument("A location cannot connect to itself.");
		}
		long lo = Math.min(a, b);
		long hi = Math.max(a, b);
		Optional<Row> existing = createNew ? Optional.empty() : connectionRef == null ? connection(tx, campaignId, a, b)
				: tx.find("location_connection", Ref.id(connectionRef, Ref.CONNECTION));
		if (connectionRef != null && (existing.isEmpty() || existing.get().lng("campaign_id") != campaignId
				|| existing.get().lng("location_a_id") != lo || existing.get().lng("location_b_id") != hi))
			throw RpgException.invalidArgument("Passage reference does not match these endpoints/campaign.");
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
		var candidates = tx.query(
				"SELECT * FROM location_connection WHERE campaign_id = ? AND location_a_id = ? AND location_b_id = ?",
				campaignId, Math.min(a, b), Math.max(a, b));
		if (candidates.size() > 1)
			throw RpgException.invalidArgument("Multiple passages connect these locations; supply connection_ref.")
					.withDetail("connections", candidates.stream().map(c -> Ref.of(Ref.CONNECTION, c.id())).toList());
		return candidates.stream().findFirst();
	}

	static Map<String, Object> connectionSummary(Tx tx, Row c, long from) {
		long other = c.lng("location_a_id") == from ? c.lng("location_b_id") : c.lng("location_a_id");
		Row o = tx.get("location", other);
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of(Ref.CONNECTION, c.id()));
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
			Map<String, Object> resumed = TravelJourneyService.resumeInTx(tx, campaign, destination, characterRefs,
					rules, roller);
			if (resumed != null)
				return resumed;
			if (characterRefs != null && characterRefs.isEmpty())
				throw RpgException.invalidArgument(
						"Select at least one character in characters, or omit characters to move the party at its current location.");
			Row from = campaign.isNull("current_location_id") ? null
					: tx.get("location", campaign.lng("current_location_id"));
			var movers = new ArrayList<Row>();
			var leftBehind = new ArrayList<Map<String, Object>>();
			Row departurePlace = from == null ? null : travelAnchor(tx, campaignId, from.id());
			if (characterRefs == null) {
				// The party is whoever is with it: active members and guests standing where the party stands. A guest
				// left at the manor, or a member who stayed behind without being separated, does not teleport.
				for (Row m : tx.query(
						"SELECT c.* FROM party_membership m JOIN character c ON c.id = m.character_id WHERE m.campaign_id = ? "
								+ "AND m.state IN ('ACTIVE','GUEST') AND c.lifecycle = 'ACTIVE' AND c.life_state <> 'DEAD' ORDER BY m.id",
						campaignId)) {
					boolean here = from == null || m.isNull("location_id") || m.lng("location_id") == from.id()
							|| travelAnchor(tx, campaignId, m.lng("location_id")).id() == departurePlace.id();
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
				for (String ref : new LinkedHashSet<>(characterRefs)) {
					Row c = CharacterService.character(tx, campaignId, ref);
					if (!travelMember(tx, campaignId, c.id()))
						throw RpgException.notAllowed(c.str("name") + " (" + ref
								+ ") is not a current party traveler. Use update_party_membership to JOIN or GUEST_ADD a legitimate companion before move_party; no travel time or route was changed.")
								.withDetail("character", ref)
								.withDetail("membership_required", List.of("ACTIVE", "GUEST", "SEPARATED"));
					movers.add(c);
				}
				var origins = new ArrayList<Map<String, Object>>();
				Long shared = null;
				for (Row character : movers) {
					Long actual = actualTravelLocation(tx, campaignId, character);
					var origin = new LinkedHashMap<String, Object>();
					origin.put("character", Ref.of(Ref.CHARACTER, character.id()));
					origin.put("name", character.str("name"));
					origin.put("location", Ref.ofNullable(Ref.LOCATION, actual));
					origins.add(origin);
					if (actual == null)
						throw RpgException
								.notAllowed("The actual travel origin of " + character.str("name")
										+ " is unknown. Establish its location before travel.")
								.withDetail("origins", origins);
					if (shared == null)
						shared = actual;
				}
				if (origins.stream().map(origin -> origin.get("location")).distinct().count() > 1)
					throw RpgException.notAllowed(
							"The named travelers occupy different locations. Move each group from its own shared origin; no traveler was moved and no travel time was spent.")
							.withDetail("origins", origins);
				from = shared == null ? null : tx.get("location", shared);
			}
			if (movers.isEmpty()) {
				throw RpgException.notAllowed("Nobody can travel.");
			}
			if (from != null && from.id() == destination.id())
				throw RpgException
						.invalidArgument("The selected travelers are already at " + destination.str("name") + ".");
			TravelJourneyService.requireTravelReady(tx, rules, movers);
			if (from != null) {
				Map<String, Object> contact = TravelJourneyService.startFromContactInTx(tx, campaign, from, destination,
						movers, rules, roller);
				if (contact != null)
					return contact;
			}
			RoutePlan plan = from == null ? new RoutePlan(null, List.of(), false)
					: travelRoute(tx, campaignId, from, destination);
			if (from != null && plan.path().isEmpty() && sameRoomMap(tx, campaignId, from, destination))
				throw roomArrivalRefusal(tx, campaignId, destination, from);
			if (!roomArrivalAllowed(tx, campaignId, destination, plan.path()))
				throw roomArrivalRefusal(tx, campaignId, destination);
			if (plan.withinPlace()) {
				Map<String, Object> result = TravelJourneyService.withinPlace(tx, campaign, from, destination, movers);
				if (!leftBehind.isEmpty())
					result.put("left_behind", leftBehind);
				return result;
			}
			Row graphFrom = plan.origin();
			List<Row> path = plan.path();
			long minutes;
			if (graphFrom != null && path.isEmpty()) {
				if (!authorizedRoute) {
					throw RpgException.validation(List.of(new Violation("to", "NO_KNOWN_ROUTE",
							"No known traversable route from " + from.str("name") + " to " + destination.str("name")
									+ ". Materialize the connection (materialize_location with connections) or pass authorized_route=true with a reason and travel_minutes (I-40).")));
				}
				if (reason == null || reason.isBlank()) {
					throw RpgException.invalidArgument("An authorized route needs a reason.");
				}
				minutes = travelMinutes == null ? DEFAULT_TRAVEL_MINUTES : Math.max(1, travelMinutes);
				Row graphDestination = travelAnchor(tx, campaignId, destination.id());
				if (tx.count(
						"SELECT COUNT(*) FROM location_connection WHERE campaign_id=? AND location_a_id=? "
								+ "AND location_b_id=?",
						campaignId, Math.min(graphFrom.id(), graphDestination.id()),
						Math.max(graphFrom.id(), graphDestination.id())) > 0)
					throw RpgException.notAllowed("An established connection joins " + graphFrom.str("name") + " and "
							+ graphDestination.str("name")
							+ " but is not traversable. Resolve its passage state, or explicitly "
							+ "materialize a different route; authorized_route does not replace an established connection.");
				connect(tx, campaignId, graphFrom.id(), graphDestination.id(), "OTHER", "OPEN", (int) minutes, null,
						"PARTY_KNOWN");
				path = route(tx, campaignId, graphFrom.id(), graphDestination.id());
				plan = appendInteriorRoute(tx, campaignId, graphFrom, graphDestination, destination, path);
				plan = prependInteriorRoute(tx, campaignId, from, plan);
				graphFrom = plan.origin();
				path = plan.path();
				minutes = path.stream().mapToLong(edge -> ((Number) edge.map("distance_json")
						.getOrDefault("travel_minutes", DEFAULT_TRAVEL_MINUTES)).longValue()).sum();
			} else {
				minutes = 0;
				for (Row edge : path) {
					Map<String, Object> distance = edge.map("distance_json");
					long hop = distance.get("travel_minutes") instanceof Number n ? n.longValue()
							: DEFAULT_TRAVEL_MINUTES;
					minutes += hop;
				}
				if (travelMinutes != null) {
					minutes = Math.max(1, travelMinutes);
				}
			}
			Row journeyFrom = graphFrom != null && from != null && lineage(tx, graphFrom.id()).contains(from.id())
					? from : graphFrom;
			Map<String, Object> result = TravelJourneyService.startOrAdvance(tx, campaign, journeyFrom, graphFrom,
					destination, movers, path, plan.segmentOrigins(), minutes, rules, roller);
			if (!leftBehind.isEmpty())
				result.put("left_behind", leftBehind);
			return result;
		});
	}

	static boolean travelMember(Tx tx, long campaignId, long characterId) {
		return tx.count(
				"SELECT COUNT(*) FROM party_membership WHERE campaign_id=? AND character_id=? AND state IN ('ACTIVE','GUEST','SEPARATED')",
				campaignId, characterId) > 0
				|| tx.count(
						"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id=? AND character_id=? AND active=1",
						campaignId, characterId) > 0;
	}

	/**
	 * Presentation-only presence for an unplaced companion at the building containing the party's
	 * room.
	 */
	public static boolean partyCoLocated(Tx tx, long campaignId, Row character, Long partyLocationId) {
		Map<String, Object> journey = TravelJourneyService.stateInTx(tx, campaignId);
		if (Boolean.TRUE.equals(journey.get("active")) && journey.get("movers") instanceof Collection<?> movers) {
			boolean moving = movers.contains(Ref.of(Ref.CHARACTER, character.id()));
			var controlled = tx.queryOne(
					"SELECT character_id AS id FROM player_control_assignment WHERE campaign_id=? AND active=1",
					campaignId);
			boolean partyMoving = controlled.isEmpty() || movers.contains(Ref.of(Ref.CHARACTER, controlled.get().id()));
			if (moving || partyMoving)
				return moving && partyMoving;
		}
		if (partyLocationId == null || character.isNull("location_id")
				|| partyLocationId.equals(character.lng("location_id")))
			return true;
		if (tx.count("SELECT COUNT(*) FROM map_actor WHERE campaign_id=? AND character_id=?", campaignId,
				character.id()) > 0)
			return false;
		Row place = tx.find("location", character.lng("location_id")).orElse(null);
		if (place == null || !Set.of("SITE", "BUILDING").contains(place.str("kind")))
			return false;
		if (tx.count("SELECT COUNT(*) FROM map_scene s,json_each(s.document_json,'$.rooms') r "
				+ "WHERE s.campaign_id=? AND s.preparation_id IS NOT NULL AND s.kind IN ('SCENE','DUNGEON') AND s.location_id=? "
				+ "AND json_extract(r.value,'$.location_ref')=? AND COALESCE(json_extract(r.value,'$.level'),0)=0",
				campaignId, place.id(), Ref.of(Ref.LOCATION, partyLocationId)) == 0)
			return false;
		Row partyRoom = tx.get("location", partyLocationId);
		if (entryRoutes(tx, campaignId, partyRoom, false, place).stream()
				.noneMatch(entry -> entry.entrance() == partyLocationId))
			return false;
		var entrances = tx
				.query("SELECT * FROM location_connection WHERE campaign_id=? AND (location_a_id=? OR location_b_id=?)",
						campaignId, partyLocationId, partyLocationId)
				.stream().filter(edge -> {
					long other = edge.lng("location_a_id") == partyLocationId ? edge.lng("location_b_id")
							: edge.lng("location_a_id");
					return other == place.id() || !lineage(tx, other).contains(place.id());
				}).toList();
		return entrances.isEmpty() || entrances.stream().anyMatch(edge -> {
			var state = edge.map("state_json");
			// A normal shut street door does not separate arrival presence; this does not open it.
			return Set.of("OPEN", "CLOSED", "BREACHED", "DESTROYED").contains(state.get("state"))
					&& !Boolean.TRUE.equals(state.get("locked")) && !Boolean.TRUE.equals(state.get("sealed"))
					&& !Boolean.TRUE.equals(state.get("secret")) && !Boolean.TRUE.equals(state.get("concealed"));
		});
	}

	private static Long actualTravelLocation(Tx tx, long campaignId, Row character) {
		for (Row actor : tx.query("SELECT * FROM map_actor WHERE campaign_id=? AND character_id=? ORDER BY id",
				campaignId, character.id())) {
			Row scene = tx.get("map_scene", actor.lng("scene_id"));
			if (!List.of("SCENE", "DUNGEON").contains(scene.str("kind")))
				continue;
			for (var room : se.hirt.mcp.rpg.maps.SceneService.maps(scene.map("document_json").get("rooms"))) {
				var polygon = se.hirt.mcp.rpg.maps.SceneService.polygon(room);
				if (room.get("location_ref") instanceof String ref
						&& polygon != null && se.hirt.mcp.rpg.maps.SceneService.number(room, "level", 0)
								.intValue() == actor.intOr("level", 0)
						&& polygon.contains(se.hirt.mcp.rpg.maps.SceneService.footprint(actor).center()))
					return Ref.id(ref, Ref.LOCATION);
			}
			if (scene.map("document_json").containsKey("journey_ref") && !scene.isNull("location_id"))
				return scene.lng("location_id");
		}
		return character.lng("location_id");
	}

	record RoutePlan(Row origin, List<Row> path, boolean withinPlace, Map<Integer, Long> segmentOrigins) {
		RoutePlan(Row origin, List<Row> path, boolean withinPlace) {
			this(origin, path, withinPlace, Map.of());
		}
	}

	/** A tactical room refines its place; it does not create a new disconnected overland origin. */
	public static Row travelAnchor(Tx tx, long campaignId, long locationId) {
		Row current = location(tx, campaignId, Ref.of(Ref.LOCATION, locationId));
		Set<Long> seen = new HashSet<>();
		while (seen.add(current.id()) && "AREA".equals(current.str("kind")) && !current.isNull("parent_id")) {
			if (tx.count(
					"SELECT COUNT(*) FROM map_scene s,json_each(s.document_json,'$.rooms') r "
							+ "WHERE s.campaign_id=? AND s.kind IN ('SCENE','DUNGEON') "
							+ "AND json_extract(r.value,'$.location_ref')=?",
					campaignId, "location:" + current.id()) == 0)
				break;
			long parent = current.lng("parent_id");
			// An explicit room-to-parent passage carries its own state and duration; do not bypass it.
			if (tx.count(
					"SELECT COUNT(*) FROM location_connection WHERE campaign_id=? "
							+ "AND location_a_id=? AND location_b_id=?",
					campaignId, Math.min(current.id(), parent), Math.max(current.id(), parent)) > 0)
				break;
			current = tx.get("location", parent);
		}
		return current;
	}

	static RoutePlan travelRoute(Tx tx, long campaignId, Row from, Row destination) {
		Row origin = travelAnchor(tx, campaignId, from.id()), target = travelAnchor(tx, campaignId, destination.id());
		for (Row start : origin.id() == from.id() ? List.of(from) : List.of(from, origin))
			for (Row end : target.id() == destination.id() ? List.of(destination) : List.of(destination, target)) {
				List<Row> path = route(tx, campaignId, start.id(), end.id());
				if (!path.isEmpty())
					return prependInteriorRoute(tx, campaignId, from,
							appendInteriorRoute(tx, campaignId, start, end, destination, path));
			}
		if (from.id() == origin.id() && origin.id() == target.id() && destination.id() != target.id())
			return appendInteriorRoute(tx, campaignId, from, target, destination, List.of());
		return new RoutePlan(origin, List.of(), origin.id() == target.id());
	}

	private static RoutePlan appendInteriorRoute(
		Tx tx, long campaignId, Row start, Row end, Row destination, List<Row> path) {
		if (end.id() == destination.id())
			return new RoutePlan(start, path, path.isEmpty());
		EntryRoute interior = entryRoutes(tx, campaignId, destination, false).stream()
				.filter(entry -> entry.entrance() == destination.id() || !entry.path().isEmpty())
				.min(Comparator.comparingDouble(entry -> entry.path().stream().mapToDouble(
						edge -> ((Number) edge.map("distance_json").getOrDefault("travel_minutes", 60)).doubleValue())
						.sum()))
				.orElse(null);
		if (interior == null || interior.path().isEmpty())
			return new RoutePlan(start, path, path.isEmpty());
		var combined = new ArrayList<>(path);
		combined.addAll(interior.path());
		return new RoutePlan(start, combined, false, Map.of(path.size(), interior.entrance()));
	}

	private static RoutePlan prependInteriorRoute(Tx tx, long campaignId, Row from, RoutePlan plan) {
		if (from == null || plan.origin() == null || from.id() == plan.origin().id())
			return plan;
		EntryRoute departure = entryRoutes(tx, campaignId, from, false).stream()
				.filter(entry -> entry.entrance() == from.id() || !entry.path().isEmpty())
				.min(Comparator.comparingDouble(entry -> entry.path().stream().mapToDouble(
						edge -> ((Number) edge.map("distance_json").getOrDefault("travel_minutes", 60)).doubleValue())
						.sum()))
				.orElse(null);
		if (departure == null) {
			var blocked = entryRoutes(tx, campaignId, from, true).stream().map(entry -> {
				var reverse = new ArrayList<>(entry.path());
				Collections.reverse(reverse);
				return new EntryRoute(from.id(), reverse);
			}).toList();
			throw interiorPassageRefusal(tx, campaignId, from, blocked, true);
		}
		if (departure.path().isEmpty())
			return plan;
		var prefix = new ArrayList<>(departure.path());
		Collections.reverse(prefix);
		var origins = new LinkedHashMap<Integer, Long>();
		origins.put(prefix.size(), plan.origin().id());
		plan.segmentOrigins().forEach((index, origin) -> origins.put(index + prefix.size(), origin));
		var combined = new ArrayList<>(prefix);
		combined.addAll(plan.path());
		return new RoutePlan(from, combined, false, origins);
	}

	/** A route to a building is not permission to appear beyond its locked interior passages. */
	static boolean roomArrivalAllowed(Tx tx, long campaignId, Row destination, List<Row> path) {
		if (!path.isEmpty()) {
			Row last = path.getLast();
			if (last.lng("location_a_id") == destination.id() || last.lng("location_b_id") == destination.id())
				return true;
		}
		Row place = travelAnchor(tx, campaignId, destination.id());
		if (place.id() == destination.id())
			return true;
		return entryRoutes(tx, campaignId, destination, false).stream()
				.anyMatch(entry -> entry.entrance() == destination.id() || !entry.path().isEmpty());
	}

	private record EntryRoute(long entrance, List<Row> path) {
	}

	private static boolean sameRoomMap(Tx tx, long campaignId, Row from, Row destination) {
		return tx.count(
				"SELECT COUNT(*) FROM map_scene s,json_each(s.document_json,'$.rooms') a,json_each(s.document_json,'$.rooms') b "
						+ "WHERE s.campaign_id=? AND s.kind IN ('SCENE','DUNGEON') AND json_extract(a.value,'$.location_ref')=? AND json_extract(b.value,'$.location_ref')=?",
				campaignId, Ref.of(Ref.LOCATION, from.id()), Ref.of(Ref.LOCATION, destination.id())) > 0;
	}

	private static List<EntryRoute> entryRoutes(Tx tx, long campaignId, Row destination, boolean includeBlocked) {
		Row place = travelAnchor(tx, campaignId, destination.id());
		return entryRoutes(tx, campaignId, destination, includeBlocked, place);
	}

	private static List<EntryRoute> entryRoutes(
		Tx tx, long campaignId, Row destination, boolean includeBlocked, Row place) {
		var realized = tx.queryOne("SELECT s.* FROM map_scene s,json_each(s.document_json,'$.rooms') r "
				+ "WHERE s.campaign_id=? AND s.kind IN ('SCENE','DUNGEON') AND json_extract(r.value,'$.location_ref')=? "
				+ "ORDER BY s.preparation_id IS NULL,s.id DESC LIMIT 1", campaignId,
				Ref.of(Ref.LOCATION, destination.id()));
		if (realized.isEmpty())
			return List.of(new EntryRoute(destination.id(), List.of()));
		Map<String, Object> document = se.hirt.mcp.rpg.maps.SceneService.document(tx, realized.get());
		var rooms = se.hirt.mcp.rpg.maps.SceneService.maps(document.get("rooms")).stream()
				.filter(room -> room.get("location_ref") instanceof String ref
						&& lineage(tx, Ref.id(ref, Ref.LOCATION)).contains(place.id()))
				.toList();
		if (rooms.isEmpty())
			return List.of();
		Set<String> legal = new LinkedHashSet<>();
		rooms.forEach(room -> legal.add(room.get("location_ref").toString()));
		var entries = rooms.stream().filter(room -> Boolean.TRUE.equals(room.get("entry"))
				|| "ENTRY".equalsIgnoreCase(String.valueOf(room.get("role")))).toList();
		if (entries.isEmpty())
			entries = rooms.stream()
					.filter(room -> se.hirt.mcp.rpg.maps.SceneService.maps(document.get("doors")).stream()
							.anyMatch(door -> Objects.equals(room.get("location_ref"), door.get("from_location_ref"))
									&& !legal.contains(door.get("to_location_ref"))
									|| Objects.equals(room.get("location_ref"), door.get("to_location_ref"))
											&& !legal.contains(door.get("from_location_ref"))))
					.toList();
		if (entries.isEmpty())
			entries = List.of(rooms.getFirst());
		var routes = new ArrayList<EntryRoute>();
		for (var entry : entries) {
			long entrance = Ref.id(entry.get("location_ref").toString(), Ref.LOCATION);
			routes.add(new EntryRoute(entrance, route(tx, campaignId, entrance, destination.id(), includeBlocked, legal
					.stream().map(ref -> Ref.id(ref, Ref.LOCATION)).collect(java.util.stream.Collectors.toSet()))));
		}
		return routes;
	}

	static RpgException roomArrivalRefusal(Tx tx, long campaignId, Row destination) {
		return roomArrivalRefusal(tx, campaignId, destination, null);
	}

	private static RpgException roomArrivalRefusal(Tx tx, long campaignId, Row destination, Row from) {
		var candidates = from == null ? entryRoutes(tx, campaignId, destination, true)
				: List.of(new EntryRoute(from.id(), route(tx, campaignId, from.id(), destination.id(), true, null)));
		return interiorPassageRefusal(tx, campaignId, destination, candidates, false);
	}

	private static RpgException interiorPassageRefusal(
		Tx tx, long campaignId, Row destination, List<EntryRoute> candidates, boolean departure) {
		Row blocker = candidates.stream().filter(entry -> !entry.path().isEmpty())
				.min(Comparator.comparingInt(entry -> entry.path().size()))
				.flatMap(entry -> entry.path().stream().filter(edge -> !traversable(edge)).findFirst()).orElse(null);
		String guidance = " Travel to its entry room or resolve the authored interior passage before selecting this destination.";
		var details = new LinkedHashMap<String, Object>();
		details.put(departure ? "origin" : "destination", Ref.of(Ref.LOCATION, destination.id()));
		if (blocker != null) {
			var state = blocker.map("state_json");
			String connection = Ref.of(Ref.CONNECTION, blocker.id());
			String key = connection;
			String mapRef = null;
			for (Row scene : tx.query(
					"SELECT * FROM map_scene WHERE campaign_id=? ORDER BY preparation_id IS NULL,id DESC",
					campaignId)) {
				Map<String, Object> document = se.hirt.mcp.rpg.maps.SceneService.document(tx, scene);
				var openings = new ArrayList<>(se.hirt.mcp.rpg.maps.SceneService.maps(document.get("doors")));
				openings.addAll(se.hirt.mcp.rpg.maps.SceneService.maps(document.get("portals")));
				var opening = openings.stream().filter(door -> connection.equals(door.get("connection_ref")))
						.findFirst();
				if (opening.isPresent()) {
					key = String.valueOf(opening.get().getOrDefault("key", connection));
					mapRef = Ref.of(Ref.MAP, scene.id());
					break;
				}
			}
			details.put("connection_ref", connection);
			details.put("key", key);
			details.put("state", state.get("state"));
			details.put("locked", Boolean.TRUE.equals(state.get("locked")) || "LOCKED".equals(state.get("state")));
			if (mapRef != null)
				details.put("map", mapRef);
			guidance = " Passage '" + key + "' is " + state.get("state")
					+ ("CLOSED".equals(state.get("state")) && Boolean.TRUE.equals(details.get("locked")) ? " and locked"
							: "")
					+ ".";
			if ("CLOSED".equals(state.get("state")) && !Boolean.TRUE.equals(details.get("locked"))) {
				guidance += " Open this unlocked door with update_map_object {key: '" + key
						+ "', changes: {state: 'OPEN'}} before moving through it.";
				details.put("next_tool", "update_map_object");
				details.put("changes", Map.of("state", "OPEN"));
			} else
				guidance += " Resolve that authored passage's lock, concealment or obstruction before moving through it.";
		}
		RpgException refusal = RpgException.notAllowed(departure
				? "The enclosing place's route does not provide an open exit from " + destination.str("name") + "."
						+ guidance
				: "The enclosing place's route does not provide an open entry to " + destination.str("name") + "."
						+ guidance);
		for (var detail : details.entrySet())
			refusal.withDetail(detail.getKey(), detail.getValue());
		return refusal;
	}

	/**
	 * Breadth-first search over traversable, party-known connections; returns the edges of the path
	 * (empty = none).
	 */
	static List<Row> route(Tx tx, long campaignId, long from, long to) {
		return route(tx, campaignId, from, to, false, null);
	}

	private static List<Row> route(
		Tx tx, long campaignId, long from, long to, boolean includeBlocked, Set<Long> allowed) {
		Map<Long, List<Row>> adjacency = new HashMap<>();
		for (Row c : tx.query("SELECT * FROM location_connection WHERE campaign_id = ?", campaignId)) {
			if ((!includeBlocked && !traversable(c)) || allowed != null
					&& (!allowed.contains(c.lng("location_a_id")) || !allowed.contains(c.lng("location_b_id")))) {
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

	static boolean traversable(Row connection) {
		Map<String, Object> state = connection.map("state_json");
		String value = String.valueOf(state.get("state")),
				visibility = String.valueOf(state.getOrDefault("visibility", "PARTY_KNOWN"));
		return Set.of("OPEN", "BREACHED", "DESTROYED").contains(value)
				|| (value.equals("SECRET") && !visibility.equals("GM_ONLY") && !visibility.equals("DIRECTOR_ONLY"));
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
