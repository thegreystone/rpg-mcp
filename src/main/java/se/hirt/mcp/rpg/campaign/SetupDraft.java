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

import se.hirt.mcp.rpg.choice.*;
import se.hirt.mcp.rpg.harness.HarnessState;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.Violation;

import java.util.*;

/**
 * The resumable campaign setup draft (DOMAIN_MODEL.md §4.2). A thin view over the draft payload that knows which
 * decisions are outstanding, derives the harness state from completeness, and validates the whole graph before commit.
 */
public final class SetupDraft {

	public static final String SURPRISE_ME = "SURPRISE_ME";

	/** Legal values, in the order they are presented; the enums in {@code se.hirt.mcp.rpg.choice} own them. */
	public static final List<String> PROFILES = Described.names(ContentProfile.class);
	public static final List<String> ABILITY_METHODS = Described.names(AbilityGeneration.class);
	public static final List<String> PROGRESSION = Described.names(Progression.class);
	public static final List<String> OVERRIDE_POLICIES = Described.names(GmOverridePolicy.class);
	public static final List<String> CONTINUATION = Described.names(ContinuationPolicy.class);
	public static final List<String> FANTASY_STYLES = Described.names(FantasyStyle.class);
	public static final Set<String> SECTIONS = Set.of("title", "player_age", "content_profile", "experience", "rules",
			"continuation", "party", "adventure", "player_character");
	public static final Set<String> RULE_KEYS = Set.of("ability_generation", "progression", "gm_override_policy",
			"allow_reroll", "starting_wealth", "hp_progression", "xp_policy", "companion_level_up");
	public static final Set<String> ADVENTURE_KEYS = Set.of("premise", "background_truth", "opening_location",
			"immediate_goal", "start_time", "gm_notes", "region", "tone_notes", "seeds", "story_beats", "factions",
			"world_events", "locations", "quests");

	private final Map<String, Object> payload;

	public SetupDraft(Map<String, Object> payload) {
		this.payload = payload;
	}

	public Map<String, Object> payload() {
		return payload;
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> section(String name) {
		Object o = payload.get(name);
		return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
	}

	public Map<String, Object> sectionOrCreate(String name) {
		Map<String, Object> s = section(name);
		if (s == null) {
			s = new LinkedHashMap<>();
			payload.put(name, s);
		}
		return s;
	}

	public String profile() {
		Map<String, Object> s = section("content_profile");
		return s == null ? null : (String) s.get("profile");
	}

	public String maxProfile() {
		Map<String, Object> s = section("content_profile");
		if (s == null) {
			return "PEGI_18";
		}
		Object constraints = s.get("player_constraints");
		if (constraints instanceof Map<?, ?> m && m.get("max_profile") instanceof String cap) {
			return cap;
		}
		return "PEGI_18";
	}

	/**
	 * The experience section as the campaign will run it: whatever the player recorded, with the fantasy style
	 * defaulted to {@link FantasyStyle#EPIC} — a Baldur's Gate-style fantasy epic — when neither a style nor a tone
	 * was given (DESIGN.md §4.2). A player who described the flavour in their own words is never overridden.
	 */
	public Map<String, Object> effectiveExperience() {
		Map<String, Object> s = section("experience");
		if (s == null) {
			return null;
		}
		var out = new LinkedHashMap<>(s);
		if (out.get("fantasy_style") == null && out.get("tone") == null) {
			out.put("fantasy_style", FantasyStyle.EPIC.name());
		}
		return out;
	}

	public String continuationPolicy() {
		Map<String, Object> s = section("continuation");
		return s == null ? null : (String) s.get("policy");
	}

	/** Legal values of rules.hp_progression (RULES_ENGINE.md §6). */
	public static final java.util.List<String> HP_PROGRESSIONS = java.util.List.of("FIRST_3_MAX", "AVERAGE", "ROLL");

	/** Legal values of rules.xp_policy and rules.companion_level_up (RULES_ENGINE.md §6). */
	public static final List<String> XP_POLICIES = Described.names(XpPolicy.class);
	public static final List<String> COMPANION_LEVEL_UP = Described.names(CompanionLevelUp.class);

	public String abilityGeneration() {
		Map<String, Object> s = section("rules");
		Object m = s == null ? null : s.get("ability_generation");
		return m == null ? "STANDARD_ARRAY" : m.toString();
	}

	public String rule(String key, String fallback) {
		Map<String, Object> s = section("rules");
		Object v = s == null ? null : s.get(key);
		return v == null ? fallback : v.toString();
	}

	public boolean allowReroll() {
		return Boolean.TRUE.equals(section("rules") == null ? null : section("rules").get("allow_reroll"));
	}

	public Long playerCharacterId() {
		Object pc = payload.get("player_character");
		return pc == null ? null : Ref.id(pc.toString(), Ref.CHARACTER);
	}

	// ── content profile helpers ────────────────────────────────────────

	/** Least-specific sufficient form of an age (MCP_PROTOCOL.md §22): only the cap is stored. */
	public static String capForAge(int age) {
		return ContentProfile.capForAge(age).name();
	}

	public static int rank(String profile) {
		int i = PROFILES.indexOf(profile);
		return i < 0 ? Integer.MAX_VALUE : i;
	}

	public static boolean exceedsCap(String profile, String cap) {
		return rank(profile) > rank(cap);
	}

	// ── completeness and harness state ─────────────────────────────────

	/** Derives the protocol-visible setup state from what is still outstanding (DOMAIN_MODEL.md §16). */
	public HarnessState deriveState(Tx tx, long campaignId) {
		if (profile() == null) {
			return HarnessState.SETUP_CONTENT_PROFILE;
		}
		if (section("experience") == null) {
			return HarnessState.SETUP_EXPERIENCE;
		}
		if (section("rules") == null) {
			return HarnessState.SETUP_RULES;
		}
		if (continuationPolicy() == null) {
			return HarnessState.SETUP_CONTINUATION;
		}
		Long pcId = playerCharacterId();
		if (pcId == null) {
			return HarnessState.CHARACTER_CONCEPT;
		}
		Row pc = tx.find("character", pcId).orElse(null);
		if (pc == null) {
			return HarnessState.CHARACTER_CONCEPT;
		}
		if ("DRAFT".equals(pc.str("lifecycle"))) {
			return characterState(tx, pc);
		}
		if (section("party") == null) {
			return HarnessState.PARTY_DESIGN;
		}
		if (section("adventure") == null) {
			return HarnessState.ADVENTURE_INITIALIZATION;
		}
		return HarnessState.CAMPAIGN_REVIEW;
	}

	static HarnessState characterState(Tx tx, Row pc) {
		boolean hasClass = tx.count("SELECT COUNT(*) FROM character_class WHERE character_id = ?", pc.id()) > 0;
		if (pc.isNull("species_ref") || !hasClass) {
			return HarnessState.CHARACTER_CONCEPT;
		}
		if (pc.isNull("str_score") || pc.isNull("cha_score")) {
			return HarnessState.CHARACTER_RULES;
		}
		if (pc.isNull("personality")) {
			return HarnessState.CHARACTER_PERSONALITY;
		}
		return HarnessState.CHARACTER_REVIEW;
	}

	/** Human-readable outstanding decisions, in wizard order. */
	public List<String> outstanding(Tx tx, long campaignId) {
		var out = new ArrayList<String>();
		if (profile() == null) {
			out.add("content_profile.profile — choose a PEGI content profile (optionally record player_age first)");
		}
		if (section("experience") == null) {
			out.add("experience — fantasy_style, tone, themes, authorship preferences (SURPRISE_ME is a valid answer; "
					+ "the default is EPIC, a Baldur's Gate-style fantasy epic)");
		}
		if (section("rules") == null) {
			out.add("rules — ability_generation, progression, gm_override_policy (defaults apply if left empty)");
		}
		if (continuationPolicy() == null) {
			out.add("continuation.policy — CHECKPOINT, ENCOUNTER_RETRY or IRONMAN");
		}
		Long pcId = playerCharacterId();
		if (pcId == null) {
			out.add("player_character — create_character_draft for the player's character");
		} else {
			Row pc = tx.find("character", pcId).orElse(null);
			if (pc != null && "DRAFT".equals(pc.str("lifecycle"))) {
				out.add("character " + Ref.of(Ref.CHARACTER,
						pc.id()) + " — complete and commit_character_draft (" + characterState(tx, pc) + ")");
			}
		}
		if (section("party") == null) {
			out.add("party — companion preferences via update_party_design (may be delegated)");
		}
		if (section("adventure") == null) {
			out.add("adventure — premise, opening_location, immediate_goal (the GM authors these)");
		}
		return out;
	}

	/** Whole-graph validation for validate/commit (MCP_PROTOCOL.md §9.5/§9.6). */
	public List<Violation> validate(Tx tx, long campaignId) {
		var v = new ArrayList<Violation>();
		if (profile() == null) {
			v.add(new Violation("content_profile.profile", "REQUIRED", "A content profile must be chosen."));
		} else if (exceedsCap(profile(), maxProfile())) {
			v.add(new Violation("content_profile.profile", "PLAYER_CONSTRAINT",
					"Profile " + profile() + " exceeds the player's cap " + maxProfile() + "."));
		}
		if (section("experience") == null) {
			v.add(new Violation("experience", "REQUIRED",
					"Experience preferences must be recorded (SURPRISE_ME is fine)."));
		}
		if (section("rules") == null) {
			v.add(new Violation("rules", "REQUIRED",
					"Rules configuration must be recorded (an empty object accepts defaults)."));
		}
		if (continuationPolicy() == null) {
			v.add(new Violation("continuation.policy", "REQUIRED", "A continuation policy must be chosen."));
		}
		Long pcId = playerCharacterId();
		if (pcId == null) {
			v.add(new Violation("player_character", "REQUIRED", "A player character draft is required."));
		} else {
			Row pc = tx.find("character", pcId).orElse(null);
			if (pc == null || pc.lng("campaign_id") != campaignId) {
				v.add(new Violation("player_character", "NOT_FOUND",
						"The player character draft does not exist in this campaign."));
			} else if (!"FINALIZED_DRAFT".equals(pc.str("lifecycle"))) {
				v.add(new Violation("player_character", "NOT_FINALIZED",
						"The player character must be finalized with commit_character_draft before campaign commit."));
			}
		}
		for (Row other : tx.query("SELECT id, name FROM character WHERE campaign_id = ? AND lifecycle = 'DRAFT'",
				campaignId)) {
			v.add(new Violation("characters", "UNFINISHED_DRAFT", Ref.of(Ref.CHARACTER, other.id()) + " (" + other.str(
					"name") + ") is still a draft; commit or abandon it."));
		}
		if (section("party") == null) {
			v.add(new Violation("party", "REQUIRED", "Party preferences must be recorded (delegation is fine)."));
		}
		Map<String, Object> adventure = section("adventure");
		if (adventure == null) {
			v.add(new Violation("adventure", "REQUIRED",
					"The adventure must be initialized (premise, opening_location, immediate_goal)."));
		} else {
			if (isBlank(adventure.get("premise"))) {
				v.add(new Violation("adventure.premise", "REQUIRED", "The adventure needs a premise."));
			}
			if (openingLocationName(adventure) == null) {
				v.add(new Violation("adventure.opening_location", "REQUIRED",
						"The adventure needs an opening location."));
			}
			if (isBlank(adventure.get("immediate_goal"))) {
				v.add(new Violation("adventure.immediate_goal", "REQUIRED",
						"The adventure needs an immediate goal for the opening scene."));
			}
		}
		return v;
	}

	/** Values explicitly delegated to the GM. */
	public List<String> delegated() {
		var out = new ArrayList<String>();
		collectDelegated("", payload, out);
		return out;
	}

	private static void collectDelegated(String prefix, Map<String, Object> map, List<String> out) {
		for (var e : map.entrySet()) {
			String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
			if (SURPRISE_ME.equals(e.getValue())) {
				out.add(path);
			} else if (e.getValue() instanceof Map<?, ?> m) {
				@SuppressWarnings("unchecked") Map<String, Object> child = (Map<String, Object>) m;
				collectDelegated(path, child, out);
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static String openingLocationName(Map<String, Object> adventure) {
		Object loc = adventure.get("opening_location");
		if (loc instanceof String s && !s.isBlank()) {
			return s;
		}
		if (loc instanceof Map<?, ?> m && m.get("name") instanceof String s && !s.isBlank()) {
			return s;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> openingLocation(Map<String, Object> adventure) {
		Object loc = adventure.get("opening_location");
		if (loc instanceof Map<?, ?> m) {
			return (Map<String, Object>) m;
		}
		var out = new LinkedHashMap<String, Object>();
		out.put("name", loc);
		return out;
	}

	static boolean isBlank(Object o) {
		return o == null || o.toString().isBlank();
	}

	/** Compact review of the whole setup (never the wizard conversation). */
	public Map<String, Object> review(Tx tx, long campaignId) {
		var r = new LinkedHashMap<String, Object>();
		r.put("content_profile", profile());
		r.put("player_constraints",
				section("content_profile") == null ? null : section("content_profile").get("player_constraints"));
		r.put("experience", effectiveExperience());
		var rules = new LinkedHashMap<String, Object>();
		rules.put("ability_generation", abilityGeneration());
		rules.put("progression", rule("progression", "XP"));
		rules.put("gm_override_policy", rule("gm_override_policy", "EXPLICIT_AUDITED"));
		rules.put("hp_progression", rule("hp_progression", "FIRST_3_MAX"));
		rules.put("xp_policy", rule("xp_policy", "SHARED"));
		rules.put("companion_level_up", rule("companion_level_up", "PLAYER"));
		rules.put("starting_wealth", rule("starting_wealth", "FIXED"));
		r.put("rules", rules);
		r.put("continuation_policy", continuationPolicy());
		Long pcId = playerCharacterId();
		if (pcId != null) {
			tx.find("character", pcId).ifPresent(pc -> {
				var c = new LinkedHashMap<String, Object>();
				c.put("ref", Ref.of(Ref.CHARACTER, pc.id()));
				c.put("name", pc.str("name"));
				c.put("lifecycle", pc.str("lifecycle"));
				r.put("player_character", c);
			});
		}
		r.put("party", section("party"));
		r.put("adventure", section("adventure"));
		r.put("delegated", delegated());
		return r;
	}
}
