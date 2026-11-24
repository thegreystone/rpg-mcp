/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg.world;

import se.hirt.mcp.rpg.economy.Scheduler;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Effects;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One precise, interruptible clock path for travel, rests, searches and tactical rounds. */
public final class WorldTimeline {

	public static final long MAX_ADVANCE_SECONDS = 366L * 24 * 60 * 60;
	private final Database db;

	public WorldTimeline(Database db) {
		this.db = db;
	}

	public record Progress(long fromSeconds, long toSeconds, long elapsedSeconds, boolean completed,
			boolean interrupted, List<Object> consequences, List<Map<String, Object>> interruptions,
			int expiredEffects) {
		public Map<String, Object> toMap() {
			var result = new LinkedHashMap<String, Object>();
			result.put("from_seconds", fromSeconds);
			result.put("to_seconds", toSeconds);
			result.put("elapsed_seconds", elapsedSeconds);
			result.put("elapsed_minutes", elapsedSeconds / 60.0);
			result.put("completed", completed);
			result.put("interrupted", interrupted);
			result.put("consequences", consequences);
			result.put("interruptions", interruptions);
			result.put("expired_effects", expiredEffects);
			return result;
		}
	}

	public record ActionProgress(String reference, long completedSeconds, long requiredSeconds, Progress progress) {
		public boolean completed() {
			return completedSeconds >= requiredSeconds && !progress.interrupted();
		}
	}

	public Map<String, Object> schedule(String op, String campaignRef, Map<String, Object> spec) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(Database.Mutation.of("schedule_world_event", cid, op, "GM", spec), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "schedule_world_event");
			long now = GameTime.currentSeconds(tx, cid);
			long due = spec.get("due_seconds") instanceof Number n ? n.longValue()
					: Math.addExact(now, number(spec, "after_seconds", 0));
			if (due < now || due - now > MAX_ADVANCE_SECONDS)
				throw RpgException.invalidArgument("Event time must be within the next year.");
			String kind = String.valueOf(spec.getOrDefault("kind", "WORLD_EVENT"));
			if (kind.isBlank() || kind.length() > 80)
				throw RpgException.invalidArgument("Event kind must be a short nonempty string.");
			var columns = new LinkedHashMap<String, Object>();
			columns.put("campaign_id", cid);
			columns.put("due_seconds", due);
			columns.put("kind", kind);
			columns.put("payload_json", Json.write(spec.getOrDefault("payload", Map.of())));
			columns.put("interrupting", !Boolean.FALSE.equals(spec.get("interrupting")));
			columns.put("visibility", String.valueOf(spec.getOrDefault("visibility", "PARTY_KNOWN")));
			if (spec.get("region_ref") != null)
				columns.put("region_id", WorldService.location(tx, cid, spec.get("region_ref").toString()).id());
			long id = tx.insert("timeline_event", columns);
			PatrolService.reconcileRegionalTimers(tx, cid);
			return Map.of("event", "scheduled_event:" + id, "due_seconds", due, "meta", Harness.meta(campaign, null));
		});
	}

	/**
	 * Generic timed work supplies progress; completed game rewards still use their canonical
	 * services.
	 */
	public Map<String, Object> perform(
		String op, String campaignRef, String kind, long durationSeconds, String actionKey,
		Map<String, Object> params) {
		long cid = Ref.id(campaignRef, Ref.CAMPAIGN);
		Map<String, Object> arguments = Map.of("kind", kind, "duration_seconds", durationSeconds, "action_key",
				actionKey == null ? "" : actionKey, "parameters", params == null ? Map.of() : params);
		return db.mutate(Database.Mutation.of("perform_timed_action", cid, op, "GM", arguments), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "perform_timed_action");
			if (!List.of("SEARCH", "WAIT", "WORK").contains(kind))
				throw RpgException.invalidArgument("Timed kind must be SEARCH, WAIT or WORK.");
			ActionProgress action = runAction(tx, cid, actionKey == null ? Json.hash(arguments) : actionKey, kind,
					durationSeconds, params == null ? Map.of() : params);
			var result = new LinkedHashMap<>(action.progress().toMap());
			result.put("action", action.reference());
			result.put("completed_seconds", action.completedSeconds());
			result.put("required_seconds", action.requiredSeconds());
			result.put("remaining_seconds", Math.max(0, action.requiredSeconds() - action.completedSeconds()));
			result.put("completed", action.completed());
			result.put("game_time", GameTime.toMap(tx, cid, GameTime.currentSeq(tx, cid)));
			result.put("meta", Harness.meta(tx.get("campaign", cid), null));
			return result;
		});
	}

	public static ActionProgress runAction(
		Tx tx, long cid, String key, String kind, long required, Map<String, Object> parameters) {
		if (required <= 0 || required > MAX_ADVANCE_SECONDS || key == null || key.isBlank() || key.length() > 250)
			throw RpgException.invalidArgument("Timed work needs a key and a positive duration of at most one year.");
		Row action = tx.queryOne(
				"SELECT * FROM action_progress WHERE campaign_id=? AND action_key=? AND status IN ('OPEN','PAUSED') ORDER BY id DESC LIMIT 1",
				cid, key).orElse(null);
		if (action == null) {
			long id = tx.insert("action_progress", Map.of("campaign_id", cid, "action_key", key, "kind", kind,
					"required_seconds", required, "parameters_json", Json.write(parameters)));
			action = tx.get("action_progress", id);
		} else if (!kind.equals(action.str("kind")) || required != action.lng("required_seconds")
				|| !Json.hash(parameters).equals(Json.hash(action.map("parameters_json"))))
			throw RpgException.conflict("Resume the saved action with the same parameters, or use a new action key.");
		long done = action.lng("completed_seconds");
		Progress progress = advanceSeconds(tx, cid, Math.max(0, required - done), kind, true);
		done += progress.elapsedSeconds();
		tx.update("action_progress", action.id(),
				Map.of("completed_seconds", done, "status",
						done >= required && !progress.interrupted() ? "COMPLETED" : "PAUSED", "revision",
						action.lng("revision") + 1));
		return new ActionProgress("action:" + action.id(), done, required, progress);
	}

	/** Called once when the round boundary is crossed, never once per combatant. */
	public static Progress advanceRound(Tx tx, long campaignId) {
		return advanceSeconds(tx, campaignId, 6, "COMBAT_ROUND", false);
	}

	public static Progress advanceSeconds(Tx tx, long cid, long seconds, String reason, boolean stopAtInterrupt) {
		if (seconds < 0 || seconds > MAX_ADVANCE_SECONDS)
			throw RpgException.invalidArgument("Elapsed seconds must be between zero and one year.");
		long from = GameTime.currentSeconds(tx, cid), target = Math.addExact(from, seconds), now = from;
		if (seconds > 0)
			se.hirt.mcp.rpg.rules.DyingProgression.initializeDeadlines(tx, cid, from);
		PatrolService.reconcileRegionalTimers(tx, cid);
		var consequences = new ArrayList<Object>();
		var interruptions = new ArrayList<Map<String, Object>>();
		int expired = 0;
		int guard = 0;
		boolean mortalityStopped = false;
		while (true) {
			Long eventDue = tx.queryOne(
					"SELECT MIN(due_seconds) AS due FROM timeline_event WHERE campaign_id=? AND status='OPEN' AND timer_active=1 AND due_seconds<=?",
					cid, target).filter(r -> !r.isNull("due")).map(r -> r.lng("due")).orElse(null);
			Long patrolDue = PatrolService.nextDue(tx, cid, target);
			Long recoveryDue = se.hirt.mcp.rpg.rules.StableRecovery.nextDue(tx, cid, target);
			Long deathSaveDue = se.hirt.mcp.rpg.rules.DyingProgression.nextDue(tx, cid, target);
			Long next = null;
			for (Long due : java.util.Arrays.asList(eventDue, patrolDue, recoveryDue, deathSaveDue))
				if (due != null && (next == null || due < next))
					next = due;
			long to = next == null ? target : Math.max(now, next);
			long beforeMinutes = now / 60;
			if (to != now) {
				Row clock = GameTime.clock(tx, cid);
				tx.update("game_clock", clock.id(),
						Map.of("seq", to / 60, "second_offset", to % 60, "instant", GameTime.render(to / 60)));
			}
			expired += Effects.expireByTime(tx, cid, to / 60);
			if (to / 60 > beforeMinutes) {
				consequences.addAll(Scheduler.onClockAdvance(tx, cid, beforeMinutes, to / 60));
			}
			now = to;
			consequences.addAll(se.hirt.mcp.rpg.rules.StableRecovery.recoverDue(tx, cid, now));
			var deathSaves = se.hirt.mcp.rpg.rules.DyingProgression.resolveDue(tx, cid, now);
			consequences.addAll(deathSaves);
			for (var save : deathSaves) {
				if (Boolean.TRUE.equals(save.get("interrupting")))
					interruptions.add(save);
				if (save.containsKey("harness_state"))
					mortalityStopped = true;
			}
			if (next == null)
				break;
			for (Row event : tx.query(
					"SELECT * FROM timeline_event WHERE campaign_id=? AND status='OPEN' AND timer_active=1 AND due_seconds<=? ORDER BY due_seconds,id",
					cid, now)) {
				tx.update("timeline_event", event.id(),
						Map.of("status", "FIRED", "revision", event.lng("revision") + 1));
				var cue = new LinkedHashMap<String, Object>(event.map("payload_json"));
				cue.put("event", "scheduled_event:" + event.id());
				cue.put("kind", event.str("kind"));
				cue.put("at_seconds", now);
				cue.put("visibility", event.str("visibility"));
				consequences.add(cue);
				if (event.bool("interrupting"))
					interruptions.add(cue);
			}
			List<Map<String, Object>> patrolCues = PatrolService.advanceAt(tx, cid, now);
			consequences.addAll(patrolCues);
			interruptions.addAll(patrolCues);
			if (mortalityStopped || stopAtInterrupt && !interruptions.isEmpty())
				break;
			if (++guard > 20000)
				throw RpgException.invalidArgument("Too many world events for one advance; use a smaller interval.");
			if (now >= target)
				break;
		}
		boolean interrupted = mortalityStopped || stopAtInterrupt && !interruptions.isEmpty();
		return new Progress(from, now, now - from, now == target && !interrupted, interrupted,
				List.copyOf(consequences), List.copyOf(interruptions), expired);
	}

	static long number(Map<String, Object> map, String key, long fallback) {
		return map.get(key) instanceof Number n ? n.longValue() : fallback;
	}
}
