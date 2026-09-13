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
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * What a client needs to play a person after a context reset (MCP_PROTOCOL.md §11.2 INTIMACY,
 * §12.7): the biography and intimate profile on the character, the INTIMACY context scope, lookup
 * by name, whereabouts that follow the party only when the person is with it, and a hit point
 * maximum that can be raised or lowered for a while.
 */
class BiographyTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	@Test
	void biographyAndIntimacyMergeAndTheIntimacyScopeGathersTheBed() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("biography"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String ilsa = (String) engine.runtime().materialize(op(), campaign, "Guard", "Ilsa",
					"The wick who walked out.", "exact, dry", "CHAOTIC_GOOD", null, false).get("character");
			engine.party().updateMembership(op(), campaign, ilsa, "JOIN", "asked, not sent");

			// Biography: dated lines are stamped, voice lines de-duplicate, a state can be ended by note.
			Map<String, Object> updated = engine.characters().updateCharacter(op(), campaign, ilsa, null, map("age", 21,
					"appearance", "A low bun with two pens in it. Marks at the wrists.", "biography",
					map("timeline",
							List.of("Taken by the Choir at eleven",
									map("note", "Unbound in the cellar", "game_time", "Day 22, 08:16")),
							"voice", List.of("Noted.", "Go on.", "Noted."), "state",
							List.of("palms marked and always warm", "a sprained wrist"), "marks",
							List.of("Choir marks at both wrists"), "wants", List.of("a house with her name in the book",
									map("note", "to find who kept her aunt", "status", "abandoned")))));
			Map<String, Object> bio = m(m(updated.get("sheet")).get("biography"));
			assertEquals(2, list(bio.get("timeline")).size());
			assertEquals("Day 22, 08:16", list(bio.get("timeline")).get(1).get("game_time"));
			assertNotNull(list(bio.get("timeline")).get(0).get("game_time"), "stamped with the clock");
			assertEquals(List.of("Noted.", "Go on."), bio.get("voice"));
			assertEquals(2, list(bio.get("state")).size());
			assertEquals("OPEN", list(bio.get("wants")).get(0).get("status"));
			assertEquals("ABANDONED", list(bio.get("wants")).get(1).get("status"));
			RpgException badStatus = assertThrows(RpgException.class,
					() -> engine.characters().updateCharacter(op(), campaign, ilsa, null,
							map("biography", map("wants", List.of(map("note", "x", "status", "MAYBE"))))));
			assertEquals(ErrorCode.INVALID_ARGUMENT, badStatus.code());
			engine.characters().updateCharacter(op(), campaign, ilsa, null,
					map("biography", map("state", List.of(map("note", "a sprained wrist", "until", "Day 9, 08:00")))));
			Map<String, Object> summary = engine.characters().characterSheet(campaign, ilsa, "SUMMARY");
			assertEquals(List.of("palms marked and always warm"), summary.get("state"), "ended states leave the brief");
			assertEquals(List.of("a house with her name in the book"), summary.get("wants"), "open drives ride along");
			assertEquals(21, summary.get("age"));
			assertEquals("A low bun with two pens in it.", summary.get("appearance_brief"));
			assertEquals("location:1", summary.get("location"));

			// Intimacy (PEGI_18 here): own profile, wants with a status, household terms naming others.
			engine.characters().updateCharacter(op(), campaign, ilsa, null,
					map("intimacy",
							map("likes", List.of("her hand flat on his chest"), "limits",
									List.of("nothing the three did not ask for together"), "wants",
									List.of(map("note", "the window seat, in daylight", "with", List.of(pc))),
									"household_terms", List.of(map("note",
											"she walks down the passage; she is never fetched", "with", List.of(pc))),
									"voice_in_bed", List.of("nobody SAID"))));
			Map<String, Object> full = engine.characters().characterSheet(campaign, ilsa, "FULL");
			Map<String, Object> intimacy = m(m(full.get("intimacy")).get("profile"));
			assertEquals("OPEN", list(intimacy.get("wants")).get(0).get("status"));
			assertNotNull(list(intimacy.get("wants")).get(0).get("since"));
			assertNull(full.get("intimacy_json"));
			// Marking a want done replaces the entry with the same note.
			engine.characters().updateCharacter(op(), campaign, ilsa, null,
					map("intimacy", map("wants", List.of(map("note", "the window seat, in daylight", "status", "DONE")),
							"body", "slight, strong hands")));
			intimacy = m(m(engine.characters().characterSheet(campaign, ilsa, "FULL").get("intimacy")).get("profile"));
			assertEquals(1, list(intimacy.get("wants")).size());
			assertEquals("DONE", list(intimacy.get("wants")).get(0).get("status"));
			assertEquals("slight, strong hands", intimacy.get("body"));

			// The pair: attraction, pairwise preferences, and a night on the ledger with detail.
			engine.party().updateRelationship(op(), campaign, pc, ilsa, map("attraction", 4, "affection", 4),
					"The fourth of his House, on her terms.", null, "midsummer", true, "GM",
					map("preferences", map("likes", List.of("watching her face find things out"))), "MERGE");
			engine.ledger().record(op(), campaign, "RELATIONSHIP_MILESTONE", "Ilsa's first night.", List.of(pc, ilsa),
					"CRITICAL", "CHARACTER_KNOWN", "GM", null, null,
					"She kept her eyes open the whole time and laughed after.", null);
			engine.characters().updateCharacter(op(), campaign, ilsa, null, map("intimacy",
					map("wants", List.of(map("note", "asking him, not being asked", "with", List.of(pc))))));

			Map<String, Object> ctx = engine.narrative().context(campaign, "INTIMACY", ilsa, null, null, null);
			assertEquals("INTIMATE", ctx.get("layer"));
			assertNotNull(ctx.get("guidance"));
			Map<String, Object> focal = m(ctx.get("character"));
			assertEquals(21, focal.get("age"));
			assertEquals(List.of("Noted.", "Go on."), m(focal.get("biography")).get("voice"));
			assertEquals(1, list(m(focal.get("biography")).get("state")).size());
			assertEquals(1, list(m(focal.get("biography")).get("wants")).size(), "the open general drive");
			assertEquals("slight, strong hands", m(focal.get("intimate_profile")).get("body"));
			assertEquals(1, list(focal.get("intimate_wants")).size(), "only the open intimate want");
			assertEquals("asking him, not being asked", list(focal.get("intimate_wants")).get(0).get("note"));
			List<Map<String, Object>> partners = list(ctx.get("partners"));
			assertEquals(1, partners.size());
			assertEquals("Richard Greystone", partners.get(0).get("name"));
			assertEquals("watching her face find things out", list(
					m(m(m(partners.get(0).get("partner_to_focal")).get("profile")).get("preferences")).get("likes"))
					.get(0));
			assertEquals(1, list(ctx.get("household_terms")).size());
			assertTrue(list(ctx.get("household_terms")).get(0).get("held_by").toString().contains("Ilsa"));
			assertEquals(1, list(ctx.get("intimate_events")).size());
			assertTrue(list(ctx.get("intimate_events")).get(0).get("detail").toString().contains("eyes open"));

			// Bootstrap keeps profiles out and says they exist; the CHARACTER scope carries the membership history.
			Map<String, Object> boot = engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> rel = list(boot.get("relationships")).get(0);
			assertNull(rel.get("profile"));
			assertNotNull(m(rel.get("profile_available")).get("preferences"));
			assertTrue(list(boot.get("party")).stream().allMatch(p -> p.containsKey("with_party")));
			assertNotNull(m(boot.get("budget")).get("chars_used"));
			Map<String, Object> character = engine.narrative().context(campaign, "CHARACTER", ilsa, null, null, null);
			assertEquals("ACTIVE", list(character.get("membership_history")).get(0).get("state"));
		}
	}

	@Test
	void belowPegi18TheIntimateLayerIsRefusedAndStripped() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("romance"))) {
			String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), campaign, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
							map("premise", "x", "opening_location", "Bellhaven", "immediate_goal", "y")));
			String pc = (String) engine.characters().createDraft(op(), campaign, map("name", "Richard", "species",
					"Human", "class", "Sorcerer", "ability_scores",
					map("CHA", 15, "CON", 14, "DEX", 13, "INT", 12, "WIS", 10, "STR", 8), "background", "Criminal",
					"background_ability_scores", map("CON", 2, "INT", 1), "species_skill", "Insight", "origin_feat",
					map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")), "skills",
					List.of("Deception", "Persuasion"), "personality", "Confident.", "starting_equipment", "A"), true)
					.get("character");
			engine.characters().commitDraft(op(), campaign, pc, null);
			engine.campaigns().commitSetup(op(), campaign, null);
			engine.sessions().bootstrap(op(), campaign, null);
			String mara = (String) engine.runtime()
					.materialize(op(), campaign, "Guard", "Mara", null, "dry", "LAWFUL_GOOD", null, false)
					.get("character");
			RpgException denied = assertThrows(RpgException.class, () -> engine.characters().updateCharacter(op(),
					campaign, mara, null, map("intimacy", map("likes", List.of("x")))));
			assertEquals(ErrorCode.POLICY_DENIED, denied.code());
			engine.party().updateRelationship(op(), campaign, pc, mara, map("attraction", 3), "Fond.", null, "a look",
					true, "GM", map("preferences", map("likes", List.of("secret")), "wants", List.of("a house")),
					"MERGE");
			Map<String, Object> ctx = engine.narrative().context(campaign, "INTIMACY", mara, pc, null, null);
			assertEquals("ROMANCE_ONLY", ctx.get("layer"));
			assertNull(ctx.get("guidance"));
			assertNull(ctx.get("household_terms"));
			Map<String, Object> profile = m(m(list(ctx.get("partners")).get(0).get("partner_to_focal")).get("profile"));
			assertNull(profile.get("preferences"), "preferences never leave the server below PEGI_18");
			assertEquals(List.of("a house"), profile.get("wants"));
			assertFalse(ctx.toString().contains("secret"));
		}
	}

	@Test
	void findByNameAndWhereaboutsThatDoNotTeleport() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("find"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String bellhaven = "location:1";
			String hollin = (String) engine.runtime()
					.materialize(op(), campaign, "Commoner", "Piers Hollin",
							"The Caldmere ferryman, called Tilda by nobody.", "taciturn", "NEUTRAL", null, false)
					.get("character");
			engine.party().updateMembership(op(), campaign, hollin, "JOIN", "rows for the House");
			Map<String, Object> town = engine.world().materialize(op(), campaign, bellhaven,
					map("description", "Slate roofs.", "connections", List.of(map("to",
							map("name", "Ruined Tower", "kind", "SITE"), "kind", "ROAD", "travel_minutes", 180))),
					"GM");
			String tower = (String) list(town.get("new_connections")).get(0).get("to");

			// find: exact, prefix and description matches, ranked; locations too.
			Map<String, Object> hits = engine.sessions().find(campaign, "CHARACTER", "hollin", null);
			assertEquals(1, hits.get("total_matches"));
			assertEquals(hollin, list(hits.get("hits")).get(0).get("ref"));
			assertEquals("ACTIVE", list(hits.get("hits")).get(0).get("membership"));
			assertEquals("name", list(hits.get("hits")).get(0).get("matched"));
			Map<String, Object> byDescription = engine.sessions().find(campaign, "CHARACTER", "tilda", null);
			assertEquals("description", list(byDescription.get("hits")).get(0).get("matched"));
			Map<String, Object> places = engine.sessions().find(campaign, "LOCATION", "tow", 5);
			assertEquals("Ruined Tower", list(places.get("hits")).get(0).get("name"));
			assertThrows(RpgException.class, () -> engine.sessions().find(campaign, "CHARACTER", " ", null));

			// Richard rides ahead alone; the party's location follows the player character.
			engine.world().move(op(), campaign, tower, List.of(pc), false, null, "riding ahead");
			Map<String, Object> party = engine.sessions().party(campaign, "SUMMARY");
			assertEquals("Ruined Tower", m(party.get("location")).get("name"));
			Map<String, Object> ferryman = list(party.get("members")).get(1);
			assertEquals(Boolean.FALSE, ferryman.get("with_party"));
			assertEquals("Bellhaven - South Gate", ferryman.get("location_name"));
			// A default move takes whoever is at the tower; the ferryman stays where he is and is reported.
			Map<String, Object> back = engine.world().move(op(), campaign, bellhaven, null, false, null, null);
			assertEquals(List.of(pc), back.get("moved"));
			assertEquals(hollin, list(back.get("left_behind")).get(0).get("character"));
			// Now everyone is at Bellhaven again and a default move takes both.
			Map<String, Object> together = engine.world().move(op(), campaign, tower, null, false, null, null);
			assertEquals(2, ((List<?>) together.get("moved")).size());
			assertNull(together.get("left_behind"));
		}
	}

	@Test
	void theHitPointMaximumCanBeLoweredAndRaisedForAWhile() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("maxhp"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			int base = ((Number) m(engine.characters().characterSheet(campaign, pc, "PLAY").get("hp")).get("max"))
					.intValue();

			// A Life Drain: the maximum falls, current HP is clamped, the sheet shows the base and the reason.
			Map<String, Object> drained = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADJUST_MAX_HP", "amount", -2, "reason", "the Drowned King's hand"));
			Map<String, Object> hp = m(drained.get("hp"));
			assertEquals(base - 2, hp.get("max"));
			assertEquals(base - 2, hp.get("current"));
			assertEquals(base, hp.get("max_base"));
			assertEquals("the Drowned King's hand", list(hp.get("max_adjustments")).get(0).get("source"));
			assertEquals("LONG_REST", m(list(hp.get("max_adjustments")).get(0).get("until")).get("until"));
			assertEquals(base - 2, m(engine.characters().characterSheet(campaign, pc, "SUMMARY").get("hp")).get("max"));

			// Aid for an hour: raised, then gone when the clock passes, and current HP is clamped back.
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADJUST_MAX_HP", "amount", 8, "minutes", 60, "reason", "Aid"));
			engine.runtime().applyRuntimeChange(op(), campaign, pc, map("kind", "HEAL", "amount", 50));
			hp = m(engine.characters().characterSheet(campaign, pc, "PLAY").get("hp"));
			assertEquals(base + 6, hp.get("max"));
			assertEquals(base + 6, hp.get("current"));
			engine.sessions().advanceTime(op(), campaign, 61, "an hour passes");
			hp = m(engine.characters().characterSheet(campaign, pc, "PLAY").get("hp"));
			assertEquals(base - 2, hp.get("max"));
			assertEquals(base - 2, hp.get("current"), "clamped when the bonus ended");

			// A long rest lifts the drain; a RESTORED reduction survives it until RESTORE_MAX_HP.
			engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "ADJUST_MAX_HP", "amount", -1, "until", "RESTORED", "reason", "a curse"));
			Map<String, Object> rested = engine.rest().rest(op(), campaign, "LONG", null, null);
			Map<String, Object> richard = list(rested.get("characters")).get(0);
			assertTrue(richard.get("effects_ended").toString().contains("Drowned King"));
			assertEquals(base - 1, m(richard.get("hp")).get("max"));
			Map<String, Object> restored = engine.runtime().applyRuntimeChange(op(), campaign, pc,
					map("kind", "RESTORE_MAX_HP"));
			assertEquals(1, restored.get("reductions_lifted"));
			assertEquals(base, m(restored.get("hp")).get("max"));
			assertNull(m(restored.get("hp")).get("max_adjustments"));

			// A maximum of 0 kills.
			String guard = (String) engine.runtime()
					.materialize(op(), campaign, "Guard", "Wat", null, null, null, null, false).get("character");
			Map<String, Object> dead = engine.runtime().applyRuntimeChange(op(), campaign, guard,
					map("kind", "ADJUST_MAX_HP", "amount", -99, "reason", "a wraith"));
			assertEquals(Boolean.TRUE, dead.get("died"));
			assertEquals("DEAD", dead.get("life_state"));
			RpgException zero = assertThrows(RpgException.class, () -> engine.runtime().applyRuntimeChange(op(),
					campaign, pc, map("kind", "ADJUST_MAX_HP", "amount", 0)));
			assertEquals(ErrorCode.INVALID_ARGUMENT, zero.code());
		}
	}
}
