package se.hirt.mcp.rpg;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import se.hirt.mcp.rpg.world.PatrolService;
import se.hirt.mcp.rpg.world.WorldTimeline;
import se.hirt.mcp.rpg.world.TravelJourneyService;
import java.util.Map;
import java.util.Optional;

/** Authored duties and story-aware travel use the same persisted campaign clock. */
public class LivingWorldTools {
	@Inject
	Engine engine;

	@Tool(name = "update_patrol", description = "MUTATING. Pause, finish or resume an existing finite patrol after an explained world change. changes: status,properties,reason. Does not replace the roster or create reinforcements; exhausted rosters cannot resume.")
	ToolResponse updatePatrol(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Saved patrol reference, for example patrol:2, returned by define_patrol.")
		String patrol,
		@ToolArg(description = "Revision returned by the previous read. Omit to use the latest revision; a supplied stale revision is rejected.")
		Optional<Long> expected_revision,
		@ToolArg(description = "Keyed changes to the current document or state. Preserve stable keys and canonical references; see the tool description for supported fields.")
		Map<String, Object> changes) {
		return ToolSupport.run("update_patrol", () -> new PatrolService(engine.db()).update(operation_id, campaign,
				patrol, expected_revision.orElse(null), changes));
	}

	@Tool(name = "schedule_world_event", description = "MUTATING. Schedule an authored event at due_seconds or after_seconds. spec includes kind,payload,interrupting,visibility. An interrupting event stops timed work before completion benefits; processing is saved and never rerolled.")
	ToolResponse schedule(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Event: {kind,payload,after_seconds} or due_seconds; optional interrupting (default true), visibility, region_ref. Regional events pause when the region has no party members.")
		Map<String, Object> spec) {
		return ToolSupport.run("schedule_world_event",
				() -> new WorldTimeline(engine.db()).schedule(operation_id, campaign, spec));
	}

	@Tool(name = "perform_timed_action", description = "MUTATING. Carry out SEARCH, WAIT or WORK for duration_seconds. Reuse action_key and identical parameters to resume saved partial work after interruption. No completed search reward is granted automatically; resolve canonical discoveries once completed.")
	ToolResponse timed(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Timed activity kind: SEARCH, WAIT or WORK. SEARCH records time/progress; resolve discoveries with the appropriate checks and canonical tools.")
		String kind,
		@ToolArg(description = "Total duration of this work in seconds. Interrupted work resumes with the same action_key and identical parameters.")
		long duration_seconds,
		@ToolArg(description = "Stable campaign-local identifier for this activity. Reuse to resume an interrupted action, not to create another attempt.")
		String action_key,
		@ToolArg(description = "Saved activity details such as actor, area and method. Must remain identical when resuming the same action_key.")
		Optional<Map<String, Object>> parameters) {
		return ToolSupport.run("perform_timed_action", () -> new WorldTimeline(engine.db()).perform(operation_id,
				campaign, kind, duration_seconds, action_key, parameters.orElse(Map.of())));
	}

	@Tool(name = "define_patrol", description = "MUTATING. Author routine duties for existing actors. spec contains name,actors:[character refs],route:[waypoints], waypoint travel/dwell timing and properties. Patrols advance on the world clock; unaware guards keep their duties and react only to their evidence.")
	ToolResponse patrol(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Patrol: {name,actors:[character:N],route:[{location_ref,travel_seconds,dwell_seconds}],region_ref,properties}. Mapped waypoints add map_ref,x_ft,y_ft,level. Needs 1-20 existing actors and 2-100 waypoints; travel 1-86400 seconds, dwell 0-86400.")
		Map<String, Object> spec) {
		return ToolSupport.run("define_patrol",
				() -> new PatrolService(engine.db()).define(operation_id, campaign, spec));
	}

	@Tool(name = "get_patrol_state", description = "Read-only GM patrol phase, duty and next arrival context.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse patrolState(@ToolArg(description = "Campaign reference, for example campaign:1.")
	String campaign, @ToolArg(description = "Saved patrol reference, for example patrol:2, returned by define_patrol.")
	String patrol) {
		return ToolSupport.run("get_patrol_state", () -> new PatrolService(engine.db()).state(campaign, patrol));
	}

	@Tool(name = "define_travel_risk", description = "MUTATING. Set story-aware risk for a location/connection scope. Frequency, eligible opponents and severity are separate policy fields. Includes authored/random placement and precautions; outcomes and no-event exposure are persisted across reloads and short travel calls.")
	ToolResponse risk(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Existing location:N or connection:N reference to which the travel-risk policy applies, or DEFAULT for the campaign fallback.")
		String scope,
		@ToolArg(description = "Risk settings including frequency_seconds, chance_percent, placement_method RANDOM or AUTHORED, optional authored_at_fraction, opposition, terrain, story and severity. For connection scope, authored_at_fraction is a fixed road point measured from the regional feature's from_location_ref, otherwise the connection's first endpoint; partial/reverse trips retain that point. Other scopes use whole-journey fractions. Outcomes persist across short calls.")
		Map<String, Object> policy) {
		return ToolSupport.run("define_travel_risk",
				() -> new TravelJourneyService(engine.db(), engine.rules(), engine.roller()).defineRisk(operation_id,
						campaign, scope, policy));
	}

	@Tool(name = "get_travel_state", description = "Read-only journey progress, retained route and pending contact. New encounter opportunities include contact_location for explicit NPC placement, including an away companion's encounter. Reading never rolls or creates encounters.", annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	ToolResponse travel(@ToolArg(description = "Campaign reference, for example campaign:1.")
	String campaign) {
		return ToolSupport.run("get_travel_state",
				() -> new TravelJourneyService(engine.db(), engine.rules(), engine.roller()).state(campaign));
	}

	@Tool(name = "continue_journey", description = "MUTATING. Continue saved travel for at most seconds, stopping at a consequential event or pending contact. Short calls retain the same risk exposure and outcomes as one long journey.")
	ToolResponse continueJourney(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Maximum additional journey duration in seconds. Travel may stop earlier at a saved contact or consequential event.")
		long seconds) {
		return ToolSupport.run("continue_journey",
				() -> new TravelJourneyService(engine.db(), engine.rules(), engine.roller()).advance(operation_id,
						campaign, seconds));
	}

	@Tool(name = "resolve_travel_opportunity", description = "MUTATING. Resolve pending contact after detection, avoidance, negotiation or a completed encounter. The saved journey can continue from its actual stopped position without rerolling the same opportunity.")
	ToolResponse resolve(
		@ToolArg(description = "Unique idempotency key for this mutation. Reuse only when retrying the same request.")
		String operation_id, @ToolArg(description = "Campaign reference, for example campaign:1.")
		String campaign,
		@ToolArg(description = "Outcome of the pending contact: RESOLVED, AVOIDED, NEGOTIATED or CONTINUE. Apply consequences through canonical gameplay tools first.")
		String resolution) {
		return ToolSupport.run("resolve_travel_opportunity",
				() -> new TravelJourneyService(engine.db(), engine.rules(), engine.roller())
						.resolveOpportunity(operation_id, campaign, resolution));
	}
}
