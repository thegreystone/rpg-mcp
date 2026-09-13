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
import se.hirt.mcp.rpg.choice.*;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The setup interview as an ordered list of {@link Decision}s, derived from what the draft still
 * lacks (MCP_PROTOCOL.md §9.3.1). The first entry is the one to put to the player now; nothing here
 * is authored twice — legal values come from the {@link Described} enums and the rules content.
 */
final class SetupDecisions {

	private static final String SETUP = "update_campaign_setup";

	private SetupDecisions() {
	}

	static List<Decision> of(Tx tx, long campaignId, SetupDraft draft, CharacterChoices choices) {
		var out = new ArrayList<Decision>();
		Map<String, Object> profileSection = draft.section("content_profile");
		boolean ageKnown = profileSection != null && profileSection.get("player_constraints") != null;
		if (draft.profile() == null && !ageKnown) {
			out.add(Decision
					.of("player_age", "How old is the player? Only the derived content cap is stored, never the age.")
					.recordedBy(SETUP, "changes.player_age").custom().optional(null)
					.note("Skip if the player prefers not to say; the cap then stays at the server maximum."));
		}
		if (draft.profile() == null) {
			String cap = draft.maxProfile();
			var options = new ArrayList<Option>();
			for (ContentProfile p : ContentProfile.values()) {
				if (!SetupDraft.exceedsCap(p.name(), cap)) {
					options.add(Option.of(p));
				}
			}
			out.add(Decision.of("content_profile", "Which content profile should the campaign use?")
					.recordedBy(SETUP, "changes.content_profile").legal(options).detail("player_profile_cap", cap)
					.note("The default experience is a Baldur's Gate-style fantasy epic, written for PEGI 18 — the rating "
							+ "Baldur's Gate III carries — so PEGI 18 is the recommendation for adult players; lower profiles "
							+ "play the same epic with the mature material scaled down."));
		}
		Map<String, Object> experience = draft.section("experience");
		if (experience == null) {
			out.add(Decision
					.of("experience.authorship", "How much of the story does the player want to define themselves?")
					.recordedBy(SETUP, "changes.experience.authorship").suggestions(Described.options(Authorship.class))
					.surpriseMe());
		}
		if (experience != null && !SetupDraft.SURPRISE_ME.equals(experience.get("authorship"))) {
			if (experience.get("tone") == null && experience.get("fantasy_style") == null) {
				out.add(Decision
						.of("experience.fantasy_style",
								"What flavour of fantasy? The default is a Baldur's Gate-style fantasy epic.")
						.recordedBy(SETUP, "changes.experience.fantasy_style")
						.suggestions(Described.options(FantasyStyle.class))
						.optional(Described.recommended(FantasyStyle.class)).surpriseMe()
						.note("Tell the player the default is the fantasy epic: skipping the question or 'surprise me' gets "
								+ "exactly that. Store a listed value or the player's own words; also record tone and themes "
								+ "from the answer."));
			}
			if (experience.get("relationship_focus") == null) {
				out.add(Decision
						.of("experience.relationship_focus", "How important are relationships between characters?")
						.recordedBy(SETUP, "changes.experience.relationship_focus").optional("CENTRAL").surpriseMe()
						.suggestions(List.of(Option.of("CENTRAL", "Central",
								"Friendship, rivalry and romance are a main reason to play — the fantasy epic's default.")
								.recommended(true), Option.of("PRESENT", "Present", "They matter, but the plot leads."),
								Option.of("COMRADESHIP", "Comradeship only", "Keep romance out of it."))));
			}
			if (experience.get("excluded_themes") == null) {
				out.add(Decision
						.of("experience.excluded_themes",
								"Anything the player specifically does not want in the story?")
						.recordedBy(SETUP, "changes.experience.excluded_themes").optional("[]").custom()
						.note("Record a list of themes to exclude, or an empty list."));
			}
		}
		Map<String, Object> rules = draft.section("rules");
		if (rules == null || rules.get("ability_generation") == null) {
			out.add(Decision.of("rules.ability_generation", "How should ability scores be generated?")
					.recordedBy(SETUP, "changes.rules.ability_generation")
					.legal(Described.options(AbilityGeneration.class))
					.optional(Described.recommended(AbilityGeneration.class)));
		}
		if (rules == null || rules.get("progression") == null) {
			out.add(Decision.of("rules.progression", "How do characters advance in level?")
					.recordedBy(SETUP, "changes.rules.progression").legal(Described.options(Progression.class))
					.optional(Described.recommended(Progression.class)));
		}
		if (rules == null || rules.get("hp_progression") == null) {
			out.add(Decision.of("rules.hp_progression", "How are hit points gained at each level?")
					.recordedBy(SETUP, "changes.rules.hp_progression").legal(Described.options(HpProgression.class))
					.optional(Described.recommended(HpProgression.class))
					.note("The Constitution modifier and species bonuses (e.g. Dwarven Toughness) always apply on top."));
		}
		if (rules == null || rules.get("xp_policy") == null) {
			out.add(Decision.of("rules.xp_policy", "Who earns the experience the party wins?")
					.recordedBy(SETUP, "changes.rules.xp_policy").legal(Described.options(XpPolicy.class))
					.optional(Described.recommended(XpPolicy.class))
					.note("Companions recruited later always join at the party's current experience, so nobody is a permanent liability."));
		}
		if (rules == null || rules.get("companion_level_up") == null) {
			out.add(Decision.of("rules.companion_level_up", "Who makes a companion's level-up choices?")
					.recordedBy(SETUP, "changes.rules.companion_level_up")
					.legal(Described.options(CompanionLevelUp.class))
					.optional(Described.recommended(CompanionLevelUp.class))
					.note("A companion who has no class yet always needs one chosen for them; the engine never invents a class."));
		}
		if (rules == null || rules.get("gm_override_policy") == null) {
			out.add(Decision.of("rules.gm_override_policy", "May the GM override the rules in exceptional cases?")
					.recordedBy(SETUP, "changes.rules.gm_override_policy")
					.legal(Described.options(GmOverridePolicy.class))
					.optional(Described.recommended(GmOverridePolicy.class))
					.note("Instructions about how to use overrides belong in adventure.gm_notes."));
		}
		if (draft.continuationPolicy() == null) {
			out.add(Decision.of("continuation", "How unforgiving should death be?")
					.recordedBy(SETUP, "changes.continuation").legal(Described.options(ContinuationPolicy.class)));
		}
		Long pcId = draft.playerCharacterId();
		Row pc = pcId == null ? null : tx.find("character", pcId).orElse(null);
		if (pc == null || "ARCHIVED".equals(pc.str("lifecycle"))) {
			out.add(Decision.of("player_character",
					"Who does the player want to play? A name, a species and class and a sentence of concept — or an archetype, or surprise them.")
					.recordedBy("create_character_draft", "initial").custom().surpriseMe()
					.note("Create the draft with whatever is known; species, class, scores, skills, spells and equipment then follow as their own decisions."));
		} else if ("DRAFT".equals(pc.str("lifecycle"))) {
			out.addAll(choices.decisionsFor(tx, pc, draft.abilityGeneration()));
		}
		if (draft.section("party") == null) {
			out.add(Decision.of("party", "Should the player shape the companions who may join them?")
					.recordedBy("update_party_design", "preferences").suggestions(Described.options(Authorship.class))
					.surpriseMe()
					.note("Record desired_roles, relationship_seeds, companion_agency and personality_distinctiveness from the answer; intentions are never guarantees."));
		}
		if (draft.section("adventure") == null) {
			out.add(Decision.of("adventure",
					"Author the premise, the GM-only background truth, the opening location and the immediate goal.")
					.recordedBy(SETUP, "changes.adventure").gmAuthored().surpriseMe()
					.note("Optionally ask the player one framing question first (where the story opens); a player who chose to author gives the premise here."));
		}
		if (out.isEmpty()) {
			out.add(Decision
					.of("campaign_review",
							"Read the compact review back. Start the campaign, or change something first?")
					.recordedBy("commit_campaign_setup", "campaign")
					.legal(List.of(Option.of("COMMIT", "Start the campaign",
							"validate_campaign_setup, then commit_campaign_setup; then bootstrap_session and play from server state only."),
							Option.of("REVISE", "Change something",
									"Any section may be revisited with update_campaign_setup before commit."))));
		}
		return out;
	}
}
