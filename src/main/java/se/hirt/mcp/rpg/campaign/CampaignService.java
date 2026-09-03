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
package se.hirt.mcp.rpg.campaign;

import se.hirt.mcp.rpg.character.CharacterChoices;
import se.hirt.mcp.rpg.checkpoint.CheckpointService;
import se.hirt.mcp.rpg.choice.Decision;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.harness.HarnessState;
import se.hirt.mcp.rpg.inventory.InventoryService;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.session.GameTime;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Campaign lifecycle: discovery, creation, resumable setup, validation, the atomic commit boundary, and termination
 * (MCP_PROTOCOL.md §8, §9, §19.5).
 */
public final class CampaignService {

	public static final String PROTOCOL_VERSION = "0.1.0";

	private final Database db;
	private final RulesData rules;
	private final ServerSession session;
	private final String serverVersion;
	private final CharacterChoices choices;

	public CampaignService(Database db, RulesData rules, ServerSession session, String serverVersion) {
		this.db = db;
		this.rules = rules;
		this.session = session;
		this.serverVersion = serverVersion;
		this.choices = new CharacterChoices(rules);
	}

	// ── get_server_state ───────────────────────────────────────────────

	public Map<String, Object> serverState(boolean includeCampaigns, int limit, String cursor) {
		int max = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
		return db.read(tx -> {
			var result = new LinkedHashMap<String, Object>();
			result.put("protocol_version", PROTOCOL_VERSION);
			result.put("server_version", serverVersion);
			Long activeId = session.activeCampaignId();
			Row active = activeId == null ? null : tx.find("campaign", activeId).orElse(null);
			HarnessState state = active == null ? HarnessState.CAMPAIGN_SELECTION : Harness.state(active);
			result.put("harness_state", state.name());
			result.put("active_campaign", active == null ? null : summary(tx, active));
			result.put("pending_transaction", active == null ? null : pendingTransaction(tx, active.id()));
			if (includeCampaigns) {
				long after = decodeCursor(cursor);
				List<Row> rows = tx.query("SELECT * FROM campaign WHERE id > ? ORDER BY id LIMIT ?", after, max + 1);
				boolean more = rows.size() > max;
				if (more) {
					rows = rows.subList(0, max);
				}
				result.put("campaigns", rows.stream().map(r -> summary(tx, r)).toList());
				result.put("next_campaign_cursor", more ? encodeCursor(rows.get(rows.size() - 1).id()) : null);
			}
			var allowed = new ArrayList<>(Harness.allowedOperations(state));
			if (active != null) {
				allowed.add(0, "create_campaign");
				allowed.add(1, "open_campaign");
			}
			result.put("allowed_operations", allowed.stream().distinct().toList());
			result.put("policy_summary", policySummary(tx, active));
			result.put("rulesets",
					rules.rulesets().stream().map(r -> r.get("namespace") + ":" + r.get("version")).toList());
			return result;
		});
	}

	private static long decodeCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return 0;
		}
		try {
			String s = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			return Long.parseLong(s.substring("id:".length()));
		} catch (RuntimeException e) {
			throw RpgException.invalidArgument("Invalid campaign cursor.");
		}
	}

	private static String encodeCursor(long lastId) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(("id:" + lastId).getBytes(StandardCharsets.UTF_8));
	}

	static Map<String, Object> summary(Tx tx, Row c) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of(Ref.CAMPAIGN, c.id()));
		m.put("title", c.str("title"));
		m.put("status", c.str("status"));
		m.put("harness_state", c.str("harness_state"));
		m.put("ruleset", c.str("ruleset_namespace") + ":" + c.str("ruleset_version"));
		m.put("player_character", playerCharacterName(tx, c.id()));
		m.put("continuation_policy", c.str("continuation_policy"));
		m.put("last_played_at", c.str("last_played_at"));
		m.put("created_at", c.str("created_at"));
		m.put("revision", c.lng("revision"));
		return m;
	}

	static String playerCharacterName(Tx tx, long campaignId) {
		return tx.queryOne(
				"SELECT c.name FROM player_control_assignment p JOIN character c ON c.id = p.character_id " + "WHERE p.campaign_id = ? AND p.active = 1",
				campaignId).map(r -> r.str("name")).orElseGet(
				() -> tx.queryOne("SELECT payload_json FROM campaign_setup_draft WHERE campaign_id = ?", campaignId)
						.map(r -> new SetupDraft(r.map("payload_json")).playerCharacterId())
						.flatMap(id -> id == null ? Optional.empty() : tx.find("character", id)).map(r -> r.str("name"))
						.orElse(null));
	}

	static Map<String, Object> pendingTransaction(Tx tx, long campaignId) {
		return tx.queryOne("SELECT * FROM pending_transaction WHERE campaign_id = ? AND status = 'OPEN' ORDER BY id",
				campaignId).map(r -> {
			var m = new LinkedHashMap<String, Object>();
			m.put("ref", Ref.of(Ref.TRANSACTION, r.id()));
			m.put("kind", r.str("kind"));
			m.put("revision", r.lng("revision"));
			m.put("status", r.str("status"));
			return (Map<String, Object>) m;
		}).orElse(null);
	}

	private static Map<String, Object> policySummary(Tx tx, Row campaign) {
		var m = new LinkedHashMap<String, Object>();
		m.put("available_profiles", SetupDraft.PROFILES);
		m.put("provider_policy", "externally_enforced");
		if (campaign != null) {
			tx.queryOne("SELECT * FROM policy_state WHERE campaign_id = ?", campaign.id()).ifPresent(p -> {
				m.put("campaign_profile", p.str("content_profile"));
				m.put("player_constraints", p.map("player_constraints_json"));
			});
			m.put("gm_override_policy",
					campaign.isNull("gm_override_policy_json") ? null : campaign.map("gm_override_policy_json"));
		}
		return m;
	}

	// ── create_campaign ────────────────────────────────────────────────

	public Map<String, Object> create(String operationId, String title, String ruleset) {
		var args = new LinkedHashMap<String, Object>();
		args.put("title", title);
		args.put("ruleset", ruleset);
		Map<String, Object> result = db.mutate(
				Database.Mutation.of("create_campaign", null, operationId, "PLAYER", args), tx -> {
					String[] ns = resolveRuleset(ruleset);
					var cols = new LinkedHashMap<String, Object>();
					cols.put("title", title == null || title.isBlank() ? null : title.trim());
					cols.put("ruleset_namespace", ns[0]);
					cols.put("ruleset_version", ns[1]);
					cols.put("status", "SETUP");
					cols.put("harness_state", HarnessState.SETUP_CONTENT_PROFILE.name());
					cols.put("revision", 0);
					cols.put("created_at", Instant.now().toString());
					long id = tx.insert("campaign", cols);
					tx.bindCampaign(id);
					tx.insert("policy_state", Map.of("campaign_id", id, "revision", 0));
					tx.insert("campaign_setup_draft",
							Map.of("campaign_id", id, "payload_json", "{}", "status", "OPEN", "revision", 0));
					tx.touched(Ref.of(Ref.CAMPAIGN, id), 0);
					return setupStateOf(tx, tx.get("campaign", id));
				});
		Object ref = result.get("campaign");
		if (ref != null) {
			session.open(Ref.id(ref.toString(), Ref.CAMPAIGN));
		}
		return result;
	}

	private String[] resolveRuleset(String requested) {
		String ns = requested == null || requested.isBlank() ? "srd5e" : requested.trim();
		String version = null;
		int colon = ns.indexOf(':');
		if (colon > 0) {
			version = ns.substring(colon + 1);
			ns = ns.substring(0, colon);
		}
		for (Map<String, Object> r : rules.rulesets()) {
			if (r.get("namespace").equals(ns) && (version == null || r.get("version").equals(version))) {
				return new String[] {ns, (String) r.get("version")};
			}
		}
		throw RpgException.invalidArgument(
				"Ruleset '" + requested + "' is not installed. Installed: " + rules.rulesets().stream()
						.map(r -> r.get("namespace") + ":" + r.get("version")).toList());
	}

	// ── open_campaign ──────────────────────────────────────────────────

	public Map<String, Object> open(String campaignRef) {
		Map<String, Object> result = db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			HarnessState state = Harness.state(campaign);
			var m = new LinkedHashMap<String, Object>();
			m.put("campaign", summary(tx, campaign));
			String resume = switch (campaign.str("status")) {
				case "SETUP" -> "RESUME_SETUP";
				case "READY_TO_PLAY", "ACTIVE", "SUSPENDED" ->
						state == HarnessState.CHECKPOINT_DECISION ? "RESOLVE_CHECKPOINT_DECISION" : "BOOTSTRAP_SESSION";
				default -> "CAMPAIGN_ENDED";
			};
			m.put("resume", resume);
			m.put("pending_transaction", pendingTransaction(tx, campaign.id()));
			m.put("previous_session_summary", tx.queryOne(
					"SELECT summary FROM session WHERE campaign_id = ? AND ended_at IS NOT NULL ORDER BY id DESC LIMIT 1",
					campaign.id()).map(r -> r.str("summary")).orElse(null));
			m.put("meta", Harness.meta(campaign, null));
			return m;
		});
		session.open(Ref.id(campaignRef, Ref.CAMPAIGN));
		return result;
	}

	// ── setup ──────────────────────────────────────────────────────────

	public Map<String, Object> setupState(String campaignRef) {
		return db.read(tx -> setupStateOf(tx, Harness.campaign(tx, campaignRef)));
	}

	private Map<String, Object> setupStateOf(Tx tx, Row campaign) {
		Row draftRow = draftRow(tx, campaign.id());
		SetupDraft draft = new SetupDraft(draftRow.map("payload_json"));
		var m = new LinkedHashMap<String, Object>();
		m.put("campaign", Ref.of(Ref.CAMPAIGN, campaign.id()));
		m.put("title", campaign.str("title"));
		m.put("status", campaign.str("status"));
		m.put("setup_status", draftRow.str("status"));
		m.put("setup", draft.payload());
		boolean open = "OPEN".equals(draftRow.str("status"));
		m.put("outstanding", open ? draft.outstanding(tx, campaign.id()) : List.of());
		m.put("decisions", open ? Decision.render(SetupDecisions.of(tx, campaign.id(), draft, choices)) : List.of());
		m.put("characters", tx.query(
				"SELECT id, name, lifecycle FROM character WHERE campaign_id = ? AND lifecycle <> 'ARCHIVED' ORDER BY id",
				campaign.id()).stream().map(c -> {
			var cm = new LinkedHashMap<String, Object>();
			cm.put("ref", Ref.of(Ref.CHARACTER, c.id()));
			cm.put("name", c.str("name"));
			cm.put("lifecycle", c.str("lifecycle"));
			return cm;
		}).toList());
		var constraints = new LinkedHashMap<String, Object>();
		constraints.put("profiles",
				SetupDraft.PROFILES.stream().filter(p -> !SetupDraft.exceedsCap(p, draft.maxProfile())).toList());
		constraints.put("player_profile_cap", draft.maxProfile());
		constraints.put("ability_generation", SetupDraft.ABILITY_METHODS);
		constraints.put("progression", SetupDraft.PROGRESSION);
		constraints.put("gm_override_policy", SetupDraft.OVERRIDE_POLICIES);
		constraints.put("continuation_policy", SetupDraft.CONTINUATION);
		constraints.put("fantasy_style", SetupDraft.FANTASY_STYLES);
		constraints.put("surprise_me", "Any creative field may hold the value SURPRISE_ME to delegate it to the GM.");
		m.put("constraints", constraints);
		m.put("interview",
				"Ask decisions[0] only: put its question to the player with every option and description, " + "numbered, plus a custom answer where allow_custom is true; record the answer; read the decisions again.");
		m.put("meta", Harness.meta(campaign, null));
		return m;
	}

	static Row draftRow(Tx tx, long campaignId) {
		return tx.queryOne("SELECT * FROM campaign_setup_draft WHERE campaign_id = ?", campaignId)
				.orElseThrow(() -> RpgException.notFound("Setup draft for campaign " + campaignId));
	}

	public Map<String, Object> updateSetup(
			String operationId, String campaignRef, Long expectedRevision, Map<String, Object> changes) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("changes", changes);
		return db.mutate(Database.Mutation.of("update_campaign_setup", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "update_campaign_setup");
			Harness.requireRevision(campaign, "Campaign", expectedRevision);
			if (changes == null || changes.isEmpty()) {
				throw RpgException.invalidArgument("changes must contain at least one setup section.");
			}
			Row draftRow = draftRow(tx, campaignId);
			SetupDraft draft = new SetupDraft(draftRow.map("payload_json"));
			var campaignCols = new LinkedHashMap<String, Object>();
			var warnings = new ArrayList<String>();
			for (var e : changes.entrySet()) {
				applyChange(tx, campaignId, draft, e.getKey(), e.getValue(), campaignCols, warnings);
			}
			syncPolicy(tx, campaignId, draft);
			tx.update("campaign_setup_draft", draftRow.id(),
					Map.of("payload_json", Json.write(draft.payload()), "revision", draftRow.lng("revision") + 1));
			campaignCols.put("harness_state", draft.deriveState(tx, campaignId).name());
			campaignCols.put("revision", campaign.lng("revision") + 1);
			tx.update("campaign", campaignId, campaignCols);
			tx.touched(Ref.of(Ref.CAMPAIGN, campaignId), campaign.lng("revision") + 1);
			Map<String, Object> state = setupStateOf(tx, tx.get("campaign", campaignId));
			if (!warnings.isEmpty()) {
				@SuppressWarnings("unchecked") Map<String, Object> meta = (Map<String, Object>) state.get("meta");
				meta.put("warnings", warnings);
			}
			return state;
		});
	}

	@SuppressWarnings("unchecked")
	private void applyChange(
			Tx tx, long campaignId, SetupDraft draft, String key, Object value, Map<String, Object> campaignCols,
			List<String> warnings) {
		if (!SetupDraft.SECTIONS.contains(key)) {
			throw RpgException.invalidArgument(
					"Unknown setup section '" + key + "'. Known sections: " + SetupDraft.SECTIONS + ".");
		}
		switch (key) {
		case "title" -> campaignCols.put("title", value == null ? null : value.toString().trim());
		case "player_age" -> {
			if (!(value instanceof Number n) || n.intValue() < 0 || n.intValue() > 150) {
				throw RpgException.invalidArgument("player_age must be a plausible integer age.");
			}
			String cap = SetupDraft.capForAge(n.intValue());
			Map<String, Object> cp = draft.sectionOrCreate("content_profile");
			cp.put("player_constraints", new LinkedHashMap<>(Map.of("max_profile", cap)));
			if (draft.profile() != null && SetupDraft.exceedsCap(draft.profile(), cap)) {
				throw RpgException.policyDenied(
						"The selected profile " + draft.profile() + " exceeds the cap " + cap + " derived from the player's age; choose a lower profile.");
			}
		}
		case "content_profile" -> {
			String profile;
			if (value instanceof Map<?, ?> m) {
				profile = m.get("profile") == null ? null : m.get("profile").toString();
			} else {
				profile = value == null ? null : value.toString();
			}
			if (profile == null || !SetupDraft.PROFILES.contains(profile.toUpperCase())) {
				throw RpgException.invalidArgument(
						"content_profile.profile must be one of " + SetupDraft.PROFILES + ".");
			}
			profile = profile.toUpperCase();
			if (SetupDraft.exceedsCap(profile, draft.maxProfile())) {
				throw RpgException.policyDenied(
						"Profile " + profile + " exceeds the player's cap " + draft.maxProfile() + ".");
			}
			draft.sectionOrCreate("content_profile").put("profile", profile);
		}
		case "experience", "party" -> mergeSection(draft, key, value);
		case "rules" -> {
			Map<String, Object> incoming = asMap(key, value);
			for (var e : incoming.entrySet()) {
				if (!SetupDraft.RULE_KEYS.contains(e.getKey())) {
					throw RpgException.invalidArgument(
							"Unknown rules option '" + e.getKey() + "'. Known: " + SetupDraft.RULE_KEYS + ".");
				}
				String v = e.getValue() == null ? null : e.getValue().toString().toUpperCase();
				switch (e.getKey()) {
				case "ability_generation" -> requireOneOf(v, SetupDraft.ABILITY_METHODS, "rules.ability_generation");
				case "progression" -> requireOneOf(v, SetupDraft.PROGRESSION, "rules.progression");
				case "gm_override_policy" -> requireOneOf(v, SetupDraft.OVERRIDE_POLICIES, "rules.gm_override_policy");
				case "hp_progression" -> requireOneOf(v, SetupDraft.HP_PROGRESSIONS, "rules.hp_progression");
				case "xp_policy" -> requireOneOf(v, SetupDraft.XP_POLICIES, "rules.xp_policy");
				case "companion_level_up" ->
						requireOneOf(v, SetupDraft.COMPANION_LEVEL_UP, "rules.companion_level_up");
				case "starting_wealth" -> {
					if (!"FIXED".equals(v)) {
						throw RpgException.capabilityUnavailable(
								"Only the SRD 5.2.1 fixed starting-wealth option is implemented; rolled starting wealth is a future house rule.");
					}
				}
				default -> {
				}
				}
			}
			Map<String, Object> rulesSection = draft.sectionOrCreate("rules");
			String before = draft.abilityGeneration();
			for (var e : incoming.entrySet()) {
				rulesSection.put(e.getKey(), e.getValue() instanceof Boolean b ? b
						: e.getValue() == null ? null : e.getValue().toString().toUpperCase());
			}
			if (!before.equals(draft.abilityGeneration()) && tx.count(
					"SELECT COUNT(*) FROM character WHERE campaign_id = ? AND str_score IS NOT NULL", campaignId) > 0) {
				warnings.add(
						"The ability generation method changed after scores were assigned; re-validate character drafts.");
			}
		}
		case "continuation" -> {
			String policy = value instanceof Map<?, ?> m ? String.valueOf(m.get("policy")) : String.valueOf(value);
			requireOneOf(policy.toUpperCase(), SetupDraft.CONTINUATION, "continuation.policy");
			draft.sectionOrCreate("continuation").put("policy", policy.toUpperCase());
		}
		case "adventure" -> {
			Map<String, Object> incoming = asMap(key, value);
			for (String k : incoming.keySet()) {
				if (!SetupDraft.ADVENTURE_KEYS.contains(k)) {
					throw RpgException.invalidArgument(
							"Unknown adventure field '" + k + "'. Known: " + SetupDraft.ADVENTURE_KEYS + ".");
				}
			}
			if (incoming.get("start_time") instanceof String s && !s.isBlank()) {
				GameTime.parse(s);
			}
			draft.sectionOrCreate("adventure").putAll(incoming);
		}
		case "player_character" -> {
			long id = Ref.id(String.valueOf(value), Ref.CHARACTER);
			Row c = tx.find("character", id).orElseThrow(() -> RpgException.notFound("Character " + value));
			if (c.lng("campaign_id") != campaignId || "ARCHIVED".equals(c.str("lifecycle"))) {
				throw RpgException.invalidArgument(value + " is not a usable character of this campaign.");
			}
			draft.payload().put("player_character", Ref.of(Ref.CHARACTER, id));
		}
		default -> throw RpgException.invalidArgument("Unhandled setup section '" + key + "'.");
		}
	}

	private static void mergeSection(SetupDraft draft, String key, Object value) {
		Map<String, Object> incoming = asMap(key, value);
		draft.sectionOrCreate(key).putAll(incoming);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(String key, Object value) {
		if (SetupDraft.SURPRISE_ME.equals(value)) {
			var m = new LinkedHashMap<String, Object>();
			m.put("authorship", SetupDraft.SURPRISE_ME);
			return m;
		}
		if (!(value instanceof Map<?, ?>)) {
			throw RpgException.invalidArgument(
					"Setup section '" + key + "' must be an object (or the value SURPRISE_ME).");
		}
		return new LinkedHashMap<>((Map<String, Object>) value);
	}

	private static void requireOneOf(String value, List<String> allowed, String path) {
		if (value == null || !allowed.contains(value)) {
			throw RpgException.invalidArgument(path + " must be one of " + allowed + ".");
		}
	}

	private static void syncPolicy(Tx tx, long campaignId, SetupDraft draft) {
		Row policy = tx.queryOne("SELECT * FROM policy_state WHERE campaign_id = ?", campaignId).orElseThrow();
		Map<String, Object> cp = draft.section("content_profile");
		var cols = new LinkedHashMap<String, Object>();
		cols.put("content_profile", draft.profile());
		cols.put("player_constraints_json",
				cp == null || cp.get("player_constraints") == null ? null : Json.write(cp.get("player_constraints")));
		cols.put("revision", policy.lng("revision") + 1);
		tx.update("policy_state", policy.id(), cols);
	}

	public Map<String, Object> validateSetup(String campaignRef) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			long campaignId = campaign.id();
			Row draftRow = draftRow(tx, campaignId);
			SetupDraft draft = new SetupDraft(draftRow.map("payload_json"));
			List<Violation> violations =
					"OPEN".equals(draftRow.str("status")) ? draft.validate(tx, campaignId) : List.of();
			var m = new LinkedHashMap<String, Object>();
			m.put("valid", violations.isEmpty());
			m.put("violations", violations.stream().map(Violation::toMap).toList());
			m.put("warnings", setupWarnings(draft));
			m.put("delegated", draft.delegated());
			m.put("review", draft.review(tx, campaignId));
			m.put("meta", Harness.meta(campaign, null));
			return m;
		});
	}

	private static List<String> setupWarnings(SetupDraft draft) {
		var w = new ArrayList<String>();
		if (draft.section("adventure") != null && SetupDraft.isBlank(
				draft.section("adventure").get("background_truth"))) {
			w.add("adventure.background_truth is empty; GM-only background truth helps continuity across sessions.");
		}
		if ("IRONMAN".equals(draft.continuationPolicy())) {
			w.add("IRONMAN: no checkpoints will be created; make sure the player understands before commit.");
		}
		return w;
	}

	// ── commit_campaign_setup ──────────────────────────────────────────

	public Map<String, Object> commitSetup(String operationId, String campaignRef, Long expectedRevision) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		return db.mutate(Database.Mutation.of("commit_campaign_setup", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "commit_campaign_setup");
			Harness.requireRevision(campaign, "Campaign", expectedRevision);
			Row draftRow = draftRow(tx, campaignId);
			SetupDraft draft = new SetupDraft(draftRow.map("payload_json"));
			List<Violation> violations = draft.validate(tx, campaignId);
			if (!violations.isEmpty()) {
				throw RpgException.validation(violations);
			}
			Map<String, Object> adventure = draft.section("adventure");

			// Clock
			long startSeq =
					adventure.get("start_time") instanceof String s && !s.isBlank() ? GameTime.parse(s) : 8 * 60;
			var clock = new LinkedHashMap<String, Object>();
			clock.put("campaign_id", campaignId);
			clock.put("calendar_json", Json.write(Map.of("type", "SIMPLE_DAY_CLOCK", "ref", GameTime.CALENDAR)));
			clock.put("instant", GameTime.render(startSeq));
			clock.put("seq", startSeq);
			tx.insert("game_clock", clock);

			// Opening location
			Map<String, Object> loc = SetupDraft.openingLocation(adventure);
			var locCols = new LinkedHashMap<String, Object>();
			locCols.put("campaign_id", campaignId);
			String kind = loc.get("kind") == null ? "SETTLEMENT" : loc.get("kind").toString().toUpperCase();
			locCols.put("kind",
					List.of("REGION", "SETTLEMENT", "DISTRICT", "SITE", "BUILDING", "AREA").contains(kind) ? kind
							: "SETTLEMENT");
			locCols.put("name", loc.get("name"));
			locCols.put("description", loc.get("description"));
			locCols.put("materialization", "SEMANTIC");
			locCols.put("revision", 0);
			long locationId = tx.insert("location", locCols);

			// Promote finalized characters
			var activated = new ArrayList<String>();
			var startingEquipment = new LinkedHashMap<String, Object>();
			long pcId = draft.playerCharacterId();
			for (Row c : tx.query(
					"SELECT * FROM character WHERE campaign_id = ? AND lifecycle = 'FINALIZED_DRAFT' ORDER BY id",
					campaignId)) {
				Map<String, Object> equipment = InventoryService.grantStartingEquipment(tx, rules, campaignId, c);
				startingEquipment.put(Ref.of(Ref.CHARACTER, c.id()), equipment);
				var cols = new LinkedHashMap<String, Object>();
				cols.put("lifecycle", "ACTIVE");
				cols.put("current_hp", c.lng("max_hp"));
				cols.put("money_cp", equipment.get("money_cp"));
				cols.put("location_id", locationId);
				cols.put("revision", c.lng("revision") + 1);
				tx.update("character", c.id(), cols);
				se.hirt.mcp.rpg.magic.SpellService.initializeSlots(tx, rules, tx.get("character", c.id()));
				se.hirt.mcp.rpg.character.Origins.initializeResources(tx, rules, tx.get("character", c.id()));
				activated.add(Ref.of(Ref.CHARACTER, c.id()));
				tx.touched(Ref.of(Ref.CHARACTER, c.id()), c.lng("revision") + 1);
			}

			// Control and party
			var control = new LinkedHashMap<String, Object>();
			control.put("campaign_id", campaignId);
			control.put("seat", "player-1");
			control.put("character_id", pcId);
			control.put("since_journal_id", tx.journalId());
			control.put("active", 1);
			tx.insert("player_control_assignment", control);

			// Setup ledger event first so the membership can reference it (I-18)
			Row pc = tx.get("character", pcId);
			String title = campaign.str("title");
			if (title == null || title.isBlank()) {
				title = generateTitle(pc.str("name"), adventure);
			}
			long eventId = LedgerService.append(tx, campaignId, new LedgerService.EventSpec("CAMPAIGN_STARTED",
					pc.str("name") + " begins the campaign \"" + title + "\" at " + loc.get("name") + ".",
					List.of(pcId), "MAJOR", "PARTY_KNOWN", "GM", null, locationId, null,
					Map.of("immediate_goal", adventure.get("immediate_goal"))));

			var membership = new LinkedHashMap<String, Object>();
			membership.put("campaign_id", campaignId);
			membership.put("character_id", pcId);
			membership.put("state", "ACTIVE");
			membership.put("joined_time", GameTime.render(startSeq));
			membership.put("joined_seq", startSeq);
			membership.put("cause_event_id", eventId);
			membership.put("notes", "player character");
			tx.insert("party_membership", membership);

			// Immediate goal as an available story beat
			var beat = new LinkedHashMap<String, Object>();
			beat.put("campaign_id", campaignId);
			beat.put("visibility", "PLAYER_KNOWN");
			beat.put("provenance", "GM");
			beat.put("revision", 0);
			beat.put("payload_json", Json.write(Map.of("kind", "IMMEDIATE_GOAL")));
			beat.put("created_at", Instant.now().toString());
			beat.put("game_time", GameTime.render(startSeq));
			beat.put("game_seq", startSeq);
			beat.put("title", adventure.get("immediate_goal"));
			beat.put("state", "AVAILABLE");
			long beatId = tx.insert("story_beat", beat);

			// Initial Director material authored during setup becomes canonical narrative state.
			var initial = new LinkedHashMap<String, Object>();
			initial.put("locations", initialLocations(tx, campaignId, locationId, adventure));
			initial.put("seeds", initialNarrative(tx, campaignId, "STORY_SEED", adventure.get("seeds")));
			initial.put("story_beats", initialNarrative(tx, campaignId, "STORY_BEAT", adventure.get("story_beats")));
			initial.put("factions", initialNarrative(tx, campaignId, "FACTION_STATE", adventure.get("factions")));
			initial.put("world_events", initialNarrative(tx, campaignId, "WORLD_EVENT", adventure.get("world_events")));
			initial.put("quests", initialNarrative(tx, campaignId, "QUEST", adventure.get("quests")));

			// Campaign configuration freeze
			var prefs = new LinkedHashMap<String, Object>();
			prefs.put("experience", draft.effectiveExperience());
			prefs.put("party", draft.section("party"));
			prefs.put("rules", draft.review(tx, campaignId).get("rules"));
			var cols = new LinkedHashMap<String, Object>();
			cols.put("title", title);
			cols.put("status", "READY_TO_PLAY");
			cols.put("harness_state", HarnessState.READY_TO_PLAY.name());
			cols.put("continuation_policy", draft.continuationPolicy());
			cols.put("gm_override_policy_json",
					Json.write(Map.of("policy", draft.rule("gm_override_policy", "EXPLICIT_AUDITED"))));
			cols.put("preferences_json", Json.write(prefs));
			cols.put("adventure_json", Json.write(adventure));
			cols.put("current_location_id", locationId);
			cols.put("revision", campaign.lng("revision") + 1);
			tx.update("campaign", campaignId, cols);
			tx.update("campaign_setup_draft", draftRow.id(),
					Map.of("status", "CONSUMED", "revision", draftRow.lng("revision") + 1));
			tx.touched(Ref.of(Ref.CAMPAIGN, campaignId), campaign.lng("revision") + 1);

			String checkpointRef = null;
			if (!"IRONMAN".equals(draft.continuationPolicy())) {
				checkpointRef = CheckpointService.createInTx(tx, campaignId, "campaign_start");
			}

			Row committed = tx.get("campaign", campaignId);
			var result = new LinkedHashMap<String, Object>();
			result.put("campaign", Ref.of(Ref.CAMPAIGN, campaignId));
			result.put("title", title);
			result.put("status", "READY_TO_PLAY");
			result.put("player_character", Ref.of(Ref.CHARACTER, pcId));
			result.put("activated_characters", activated);
			result.put("starting_equipment", startingEquipment);
			result.put("opening_location", Ref.of(Ref.LOCATION, locationId));
			result.put("immediate_goal", Ref.of(Ref.STORY_BEAT, beatId));
			result.put("checkpoint_created", checkpointRef);
			result.put("initial_narrative_state", initial);
			result.put("setup_locked", true);
			result.put("next_state", HarnessState.SESSION_BOOTSTRAP.name());
			result.put("requires_context_reset", true);
			var summary = new LinkedHashMap<String, Object>();
			summary.put("premise", adventure.get("premise"));
			summary.put("opening_location", loc.get("name"));
			summary.put("immediate_goal", adventure.get("immediate_goal"));
			summary.put("game_time", GameTime.toMap(startSeq));
			result.put("summary", summary);
			result.put("meta", Harness.meta(committed, null));
			return result;
		});
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> initialNarrative(Tx tx, long campaignId, String kind, Object entries) {
		var out = new ArrayList<Map<String, Object>>();
		if (!(entries instanceof List<?> list)) {
			return out;
		}
		for (Object o : list) {
			if (!(o instanceof Map<?, ?> m)) {
				throw RpgException.invalidArgument("adventure." + kind.toLowerCase() + " entries must be objects.");
			}
			var body = new LinkedHashMap<String, Object>((Map<String, Object>) m);
			if (kind.equals("STORY_SEED") && body.get("kind") != null && body.get("seed_kind") == null) {
				body.put("seed_kind", body.remove("kind"));
			}
			out.add(se.hirt.mcp.rpg.narrative.NarrativeService.applyOne(tx, campaignId, kind, null, body, "DIRECTOR"));
		}
		return out;
	}

	/** Semantic map nodes authored during setup: siblings/children of the opening location, connected to it. */
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> initialLocations(
			Tx tx, long campaignId, long openingId, Map<String, Object> adventure) {
		var out = new ArrayList<Map<String, Object>>();
		if (!(adventure.get("locations") instanceof List<?> list)) {
			return out;
		}
		var byName = new LinkedHashMap<String, Long>();
		byName.put(tx.get("location", openingId).str("name").toLowerCase(), openingId);
		for (Object o : list) {
			Map<String, Object> l = (Map<String, Object>) o;
			String name = String.valueOf(l.get("name"));
			Long parent = l.get("parent") == null ? null : byName.get(String.valueOf(l.get("parent")).toLowerCase());
			long id = se.hirt.mcp.rpg.world.WorldService.insertLocation(tx, campaignId, name,
					se.hirt.mcp.rpg.world.WorldService.kind(l.get("kind"), "SITE"),
					l.get("description") == null ? null : l.get("description").toString(), parent, "SEMANTIC",
					l.get("tags"));
			byName.put(name.toLowerCase(), id);
			out.add(se.hirt.mcp.rpg.world.WorldService.summary(tx.get("location", id)));
		}
		for (Object o : list) {
			Map<String, Object> l = (Map<String, Object>) o;
			long id = byName.get(String.valueOf(l.get("name")).toLowerCase());
			Object connectTo = l.get("connected_to");
			List<Object> targets = connectTo instanceof List<?> t ? (List<Object>) t
					: connectTo == null ? List.of("__opening__") : List.of(connectTo);
			for (Object target : targets) {
				Long other =
						"__opening__".equals(target) ? openingId : byName.get(String.valueOf(target).toLowerCase());
				if (other == null || other == id) {
					continue;
				}
				Integer minutes = l.get("travel_minutes") instanceof Number n ? n.intValue() : null;
				se.hirt.mcp.rpg.world.WorldService.connect(tx, campaignId, id, other,
						l.get("connection_kind") == null ? "ROAD" : String.valueOf(l.get("connection_kind")), "OPEN",
						minutes, l.get("distance_miles"), "PARTY_KNOWN");
			}
		}
		return out;
	}

	static String generateTitle(String pcName, Map<String, Object> adventure) {
		String premise = adventure.get("premise") == null ? "" : adventure.get("premise").toString().trim();
		String base = pcName == null || pcName.isBlank() ? "Untitled Campaign" : pcName + "'s Campaign";
		if (premise.isEmpty()) {
			return base;
		}
		int end = premise.indexOf('.');
		String first = end > 0 ? premise.substring(0, end) : premise;
		if (first.length() > 60) {
			first = first.substring(0, 57).trim() + "...";
		}
		return base + ": " + first;
	}

	// ── complete_campaign ──────────────────────────────────────────────

	public Map<String, Object> complete(
			String operationId, String campaignRef, String outcome, String reason, String summary) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("outcome", outcome);
		args.put("reason", reason);
		return db.mutate(Database.Mutation.of("complete_campaign", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "complete_campaign");
			String status = outcome == null ? "" : outcome.toUpperCase();
			if (!List.of("COMPLETED", "FAILED", "ABANDONED").contains(status)) {
				throw RpgException.invalidArgument("outcome must be COMPLETED, FAILED or ABANDONED.");
			}
			if (reason == null || reason.isBlank()) {
				throw RpgException.invalidArgument("An explicit reason is required.");
			}
			boolean wasSetup = "SETUP".equals(campaign.str("status"));
			if (wasSetup) {
				for (Row c : tx.query(
						"SELECT * FROM character WHERE campaign_id = ? AND lifecycle IN ('DRAFT','FINALIZED_DRAFT')",
						campaignId)) {
					tx.update("character", c.id(), Map.of("lifecycle", "ARCHIVED", "revision", c.lng("revision") + 1));
				}
				Row draftRow = draftRow(tx, campaignId);
				tx.update("campaign_setup_draft", draftRow.id(),
						Map.of("status", "ABANDONED", "revision", draftRow.lng("revision") + 1));
			} else {
				tx.queryOne("SELECT * FROM session WHERE campaign_id = ? AND ended_at IS NULL", campaignId).ifPresent(
						s -> tx.rawUpdate("session", s.id(),
								Map.of("ended_at", Instant.now().toString(), "end_journal_id", tx.journalId(),
										"summary", summary == null ? reason : summary)));
				LedgerService.append(tx, campaignId, new LedgerService.EventSpec("CAMPAIGN_" + status,
						summary == null || summary.isBlank() ? reason : summary, List.of(), "CRITICAL", "PARTY_KNOWN",
						"GM", null, campaign.lng("current_location_id"), null, Map.of("reason", reason)));
			}
			var cols = new LinkedHashMap<String, Object>();
			cols.put("status", status);
			cols.put("harness_state", "CAMPAIGN_" + status);
			cols.put("active_session_id", null);
			cols.put("revision", campaign.lng("revision") + 1);
			tx.update("campaign", campaignId, cols);
			var audit = new LinkedHashMap<String, Object>();
			audit.put("campaign_id", campaignId);
			audit.put("kind", "ADMIN_EDIT");
			audit.put("actor", "player");
			audit.put("provenance", "PLAYER");
			audit.put("reason", "complete_campaign " + status + ": " + reason);
			audit.put("before_json", Json.write(Map.of("status", campaign.str("status"))));
			audit.put("after_json", Json.write(Map.of("status", status)));
			audit.put("recorded_at", Instant.now().toString());
			tx.rawInsert("audit_record", audit);
			var result = new LinkedHashMap<String, Object>();
			result.put("campaign", Ref.of(Ref.CAMPAIGN, campaignId));
			result.put("status", status);
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return result;
		});
	}
}
