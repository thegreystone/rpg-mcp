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
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

/**
 * Companions: stat-block skills and saves are the character's own, party experience follows the
 * campaign's xp_policy, recruits join at the party's total, and a companion recruited as a stat
 * block can take a class and level from there (RULES_ENGINE.md §6).
 */
class CompanionTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	/** A Scout's "Perception +5" is the whole bonus, not something to add a Wisdom modifier to. */
	@Test
	void statBlockSkillsAndSavesBeatBareAbilityModifiers() throws Exception {
		Path db = tempDb("companion-skills");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String scout = (String) engine.runtime()
					.materialize(op(), campaign, "Scout", "Vess", null, null, null, null, false).get("character");

			Map<String, Object> perception = engine.checks().resolveCheck(op(), campaign, scout, "SKILL_CHECK", null,
					"Perception", 14, null, "tailing a dray");
			assertEquals(5, perception.get("modifier"), "the Scout's listed Perception bonus");
			assertEquals("STAT_BLOCK", perception.get("modifier_source"));
			assertEquals(Boolean.TRUE, perception.get("proficient"));

			// A skill the stat block does not list falls back to the plain ability modifier.
			Map<String, Object> arcana = engine.checks().resolveCheck(op(), campaign, scout, "SKILL_CHECK", null,
					"Arcana", 10, null, "reading a seal");
			assertEquals(0, arcana.get("modifier"), "INT 11 with no proficiency");
			assertNull(arcana.get("modifier_source"));
		}
	}

	/**
	 * Under LOCKSTEP the player character earns in full and companions are kept level with them.
	 */
	@Test
	void lockstepKeepsCompanionsLevelWithThePlayerCharacter() throws Exception {
		Path db = tempDb("companion-lockstep");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.rest().override(op(), campaign, "SET_CAMPAIGN_RULE", null,
					map("rule", "xp_policy", "value", "LOCKSTEP"), "companions track the player character", null);
			String pc = (String) engine.sessions().party(campaign, "SUMMARY").get("player_character");
			String vess = (String) engine.runtime()
					.materialize(op(), campaign, "Scout", "Vess", null, null, null, null, false).get("character");
			engine.party().updateMembership(op(), campaign, vess, "JOIN", "recruited at the manor");

			// No character list: the award follows the policy.
			Map<String, Object> award = engine.runtime().awardXp(op(), campaign, null, 300, "QUEST",
					"unmasked the copyist");
			assertEquals("LOCKSTEP", award.get("xp_policy"));
			List<Map<String, Object>> awarded = (List<Map<String, Object>>) award.get("awarded");
			assertEquals(2, awarded.size(), "the player character earns, the companion is brought level");
			for (Map<String, Object> a : awarded) {
				assertEquals(300L, ((Number) a.get("xp")).longValue());
			}
			assertEquals(300L,
					((Number) m(engine.characters().characterSheet(campaign, vess, "PLAY")).get("xp")).longValue());
			assertEquals(300L,
					((Number) m(engine.characters().characterSheet(campaign, pc, "PLAY")).get("xp")).longValue());
		}
	}

	/**
	 * A companion recruited later starts at the party's experience rather than at zero, under every
	 * policy.
	 */
	@Test
	void recruitsJoinAtThePartysExperience() throws Exception {
		Path db = tempDb("companion-join");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.runtime().awardXp(op(), campaign, null, 900, "MILESTONE", "the seal held");
			String maude = (String) engine.runtime()
					.materialize(op(), campaign, "Scout", "Maude Brenn", null, null, null, null, false)
					.get("character");
			Map<String, Object> joined = engine.party().updateMembership(op(), campaign, maude, "JOIN",
					"the fen guide");
			assertNotNull(joined.get("joined_at_party_experience"));
			assertEquals(900L,
					((Number) m(engine.characters().characterSheet(campaign, maude, "PLAY")).get("xp")).longValue());
		}
	}

	/**
	 * A stat-block companion has no class, so their first level-up is the class choice; from there
	 * the engine advances them on its own.
	 */
	@Test
	void aStatBlockCompanionTakesAClassAndThenLevelsItself() throws Exception {
		Path db = tempDb("companion-class");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			engine.rest().override(op(), campaign, "SET_CAMPAIGN_RULE", null,
					map("rule", "companion_level_up", "value", "ENGINE"), "the engine levels companions", null);
			String vess = (String) engine.runtime()
					.materialize(op(), campaign, "Scout", "Vess", null, null, null, null, false).get("character");
			engine.party().updateMembership(op(), campaign, vess, "JOIN", "recruited");
			// Enough for level 3 (2 700 XP is level 4).
			Map<String, Object> award = engine.runtime().awardXp(op(), campaign, null, 2450, "MILESTONE",
					"the covenant plates");
			List<Map<String, Object>> waiting = (List<Map<String, Object>>) award.get("companions_awaiting_level_up");
			assertEquals(1, waiting.size());
			assertEquals("NEEDS_CLASS", waiting.get(0).get("reason"));

			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, vess);
			Map<String, Object> pick = m(begun.get("class_choice"));
			assertEquals("class", pick.get("choice"));
			assertTrue(((List<?>) pick.get("options")).size() >= 12, "every SRD class is offered");
			String transaction = (String) m(begun.get("transaction")).get("ref");
			// The class may be recorded on its own; the concrete skill list arrives once it is known.
			Map<String, Object> withClass = engine.levelUps().update(op(), campaign, transaction, null,
					map("class", "Rogue"));
			assertEquals(4, m(withClass.get("skill_choice")).get("choose"), "the Rogue chooses four skills");
			engine.levelUps().update(op(), campaign, transaction, null,
					map("skills", List.of("Stealth", "Perception", "Deception", "Acrobatics")));
			Map<String, Object> committed = engine.levelUps().commit(op(), campaign, transaction, null);
			assertEquals(1, committed.get("level"));

			Map<String, Object> sheet = engine.characters().characterSheet(campaign, vess, "PLAY");
			assertEquals(List.of("Rogue 1"), sheet.get("classes"));
			// d8 hit die plus CON +1 replaces the stat block's 16 average hit points.
			assertEquals(9, m(sheet.get("hp")).get("max"));
			assertTrue(((List<?>) sheet.get("saving_throw_proficiencies")).contains("DEX"), "the Rogue's saves");
			assertTrue(((List<?>) sheet.get("skill_proficiencies")).contains("Stealth"), "the chosen class skills");
			// The stat block is still there for actions and senses.
			assertNotNull(sheet.get("creature"));
			// But checks are hers now: DEX 14 + proficiency 2, not the Scout's listed Stealth +6.
			Map<String, Object> stealth = engine.checks().resolveCheck(op(), campaign, vess, "SKILL_CHECK", null,
					"Stealth", 10, null, "over the wall");
			assertEquals(4, stealth.get("modifier"));
			assertNull(stealth.get("modifier_source"), "a classed character is no longer a stat block");

			// The next award finds a classed companion and the engine takes them the rest of the way.
			Map<String, Object> more = engine.runtime().awardXp(op(), campaign, null, 50, "ROLEPLAY", "the rooftop");
			List<Map<String, Object>> levels = (List<Map<String, Object>>) more.get("companion_level_ups");
			assertEquals(2, levels.size(), "levels 2 and 3 in one go");
			assertEquals(3, levels.get(1).get("level"));
			assertEquals(3, m(engine.characters().characterSheet(campaign, vess, "PLAY")).get("level"));
		}
	}

	/**
	 * A companion recruited as a stat block can be promoted all the way to a character a player
	 * could inherit: class, species, background and the origin feats they carry, through the same
	 * code the creation draft uses.
	 */
	@Test
	void aPromotedCompanionIsIndistinguishableFromAPlayerCharacter() throws Exception {
		Path db = tempDb("companion-promotion");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String vess = (String) engine.runtime()
					.materialize(op(), campaign, "Scout", "Vess", null, null, "CHAOTIC_GOOD", null, false)
					.get("character");
			engine.party().updateMembership(op(), campaign, vess, "JOIN", "recruited");

			// The party view says what is still missing before that inheritance would be safe.
			List<Map<String, Object>> members = (List<Map<String, Object>>) engine.sessions().party(campaign, "SUMMARY")
					.get("members");
			Map<String, Object> before = members.stream().filter(x -> vess.equals(x.get("ref"))).findFirst()
					.orElseThrow();
			List<String> gaps = (List<String>) before.get("sheet_gaps");
			assertTrue(gaps.containsAll(List.of("class", "species", "background")), "gaps were " + gaps);
			assertFalse(gaps.contains("alignment"), "materialize recorded it");

			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, vess);
			String transaction = (String) m(begun.get("transaction")).get("ref");
			assertNotNull(begun.get("origin_choice"), "species and background are offered with the class");

			engine.levelUps().update(op(), campaign, transaction, null, map("class", "Rogue"));
			engine.levelUps().update(op(), campaign, transaction, null,
					map("species", "Human", "species_skill", "Perception", "origin_feat",
							map("feat", "Skilled", "proficiencies",
									List.of("Athletics", "Investigation", "Persuasion")),
							"background", "Criminal", "skills",
							List.of("Deception", "Insight", "Acrobatics", "Intimidation")));
			engine.levelUps().commit(op(), campaign, transaction, null);

			Map<String, Object> sheet = engine.characters().characterSheet(campaign, vess, "FULL");
			assertEquals("Human", sheet.get("species"));
			assertEquals("Criminal", m(sheet.get("background")).get("name"));
			assertEquals(List.of("Rogue 1"), sheet.get("classes"));
			assertEquals("CHAOTIC_GOOD", sheet.get("alignment"));
			// Criminal's own two skills, the species skill, the origin feat's three, and the four class picks.
			List<String> skills = (List<String>) sheet.get("skill_proficiencies");
			assertTrue(skills.containsAll(List.of("Stealth", "Sleight of Hand")), "background skills: " + skills);
			assertTrue(skills.contains("Perception"), "the species skill: " + skills);
			assertTrue(skills.containsAll(List.of("Deception", "Acrobatics")), "class skills: " + skills);
			assertTrue(((List<?>) sheet.get("saving_throw_proficiencies")).contains("DEX"));
			// Criminal's origin feat is Alert; the species feat is Skilled.
			List<String> feats = ((List<Map<String, Object>>) sheet.get("feats")).stream()
					.map(f -> String.valueOf(f.get("name"))).toList();
			assertTrue(feats.containsAll(List.of("Alert", "Skilled")), "feats were " + feats);
			assertNotNull(sheet.get("species_traits"));

			// Armor Class stops being read off the stat block too: she wears what she is given.
			assertEquals("Unarmored (10 + DEX)", m(sheet.get("armor_class")).get("basis"));
			assertEquals(12, m(sheet.get("armor_class")).get("value"), "10 + DEX 2, not the Scout's flat 13");

			// Nothing is left to flag except the gear she has not been given yet.
			members = (List<Map<String, Object>>) engine.sessions().party(campaign, "SUMMARY").get("members");
			Map<String, Object> after = members.stream().filter(x -> vess.equals(x.get("ref"))).findFirst()
					.orElseThrow();
			assertEquals(List.of("inventory"), after.get("sheet_gaps"));

			// And she can be handed the player's chair.
			Map<String, Object> transferred = engine.runtime().transferControl(op(), campaign, vess,
					"the player character died at the wharf");
			assertEquals(vess, transferred.get("player_character"));
		}
	}

	/** PLAYER hands every companion choice back to the player; the engine only flags them. */
	@Test
	void companionLevelUpPolicyPlayerOnlyFlags() throws Exception {
		Path db = tempDb("companion-player");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String vess = (String) engine.runtime()
					.materialize(op(), campaign, "Scout", "Vess", null, null, null, null, false).get("character");
			engine.party().updateMembership(op(), campaign, vess, "JOIN", "recruited");
			Map<String, Object> begun = engine.levelUps().begin(op(), campaign, vess);
			String transaction = (String) m(begun.get("transaction")).get("ref");
			engine.levelUps().update(op(), campaign, transaction, null,
					map("class", "Rogue", "skills", List.of("Stealth", "Perception", "Deception", "Acrobatics")));
			engine.levelUps().commit(op(), campaign, transaction, null);

			Map<String, Object> award = engine.runtime().awardXp(op(), campaign, null, 900, "QUEST", "the wharf");
			assertNull(award.get("companion_level_ups"), "nothing is levelled automatically");
			List<Map<String, Object>> waiting = (List<Map<String, Object>>) award.get("companions_awaiting_level_up");
			assertEquals("AWAITING_PLAYER", waiting.get(0).get("reason"));
			// PLAYER mode still does the arithmetic: the player is offered a complete proposal to accept or change.
			Map<String, Object> proposal = m(waiting.get(0).get("proposal"));
			// This campaign rolls hit points, so the proposal names the expression rather than pre-rolling it.
			assertEquals("ROLL", m(proposal.get("hit_points")).get("method"));
			assertNotNull(m(proposal.get("hit_points")).get("range"));
		}
	}

	/** SET_MAX_HP is the only way to record a maximum granted outside the level-up path. */
	@Test
	void setMaxHpRaisesTheMaximumAndCarriesCurrentHitPointsWithIt() throws Exception {
		Path db = tempDb("companion-maxhp");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = (String) engine.sessions().party(campaign, "SUMMARY").get("player_character");
			int before = (Integer) m(m(engine.characters().characterSheet(campaign, pc, "PLAY")).get("hp")).get("max");
			engine.rest().override(op(), campaign, "SET_MAX_HP", pc, map("amount", 3),
					"Ember-Blooded subclass: +1 hit point per sorcerer level", null);
			Map<String, Object> hp = m(m(engine.characters().characterSheet(campaign, pc, "PLAY")).get("hp"));
			assertEquals(before + 3, hp.get("max"));
			assertEquals(before + 3, hp.get("current"), "the gain is granted, not just the ceiling");
		}
	}

	/**
	 * A committed campaign may retune its house rules; the change is audited like any other
	 * override.
	 */
	@Test
	void campaignRulesCanBeRetunedAfterCommit() throws Exception {
		Path db = tempDb("companion-retune");
		try (Engine engine = engine(db)) {
			String campaign = committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> changed = engine.rest().override(op(), campaign, "SET_CAMPAIGN_RULE", null,
					map("rule", "xp_policy", "value", "SHARED"), "the player prefers the classic split", null);
			assertEquals("SHARED", m(changed.get("after")).get("xp_policy"));
			assertEquals(Boolean.TRUE, changed.get("audited"));

			String vess = (String) engine.runtime()
					.materialize(op(), campaign, "Scout", "Vess", null, null, null, null, false).get("character");
			engine.party().updateMembership(op(), campaign, vess, "JOIN", "recruited");
			Map<String, Object> award = engine.runtime().awardXp(op(), campaign, null, 100, "QUEST", "a small favour");
			assertEquals("SHARED", award.get("xp_policy"));
			assertEquals(2, ((List<?>) award.get("awarded")).size(), "both members earn under SHARED");

			assertThrows(se.hirt.mcp.rpg.protocol.RpgException.class, () -> engine.rest().override(op(), campaign,
					"SET_CAMPAIGN_RULE", null, map("rule", "xp_policy", "value", "NONSENSE"), "typo", null));
		}
	}
}
