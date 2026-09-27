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
import se.hirt.mcp.rpg.choice.ContentProfile;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Companions stay alive between reviews because their wants move (MCP_PROTOCOL.md §12.7, §18): a
 * want closed without a date is stamped by the clock, closing one recommends a Director review, a
 * milestone between people does too, and the Director's view lists each present member's open and
 * recently closed wants with the temperament that should shape the next one.
 */
class CompanionWantsTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	@Test
	void aClosedWantIsStampedAndRecommendsAReview() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("wants-close"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String ysolde = (String) engine.runtime().materialize(op(), campaign, "Commoner", "Ysolde", "the steward",
					"exact; itemises everything", null, null, false).get("character");
			engine.party().updateMembership(op(), campaign, ysolde, "JOIN", null);

			// Opening wants: no review recommended.
			Map<String, Object> opened = engine.characters().updateCharacter(op(), campaign, ysolde, null,
					map("biography", map("wants", List.of("the deeds room open by Lammas")), "intimacy",
							map("wants", List.of(map("note", "to taste him", "with", List.of(pc))))));
			assertFalse((Boolean) m(opened.get("director_trigger")).get("recommended"));

			// Closing one: stamped with the clock, and the Director is asked to look.
			String now = (String) m(engine.sessions().advanceTime(op(), campaign, 90, "the night").get("to"))
					.get("instant");
			Map<String, Object> closed = engine.characters().updateCharacter(op(), campaign, ysolde, null,
					map("intimacy", map("wants",
							List.of(map("note", "to taste him", "status", "DONE", "done_note", "asked by a wife")))));
			Map<String, Object> trigger = m(closed.get("director_trigger"));
			assertTrue((Boolean) trigger.get("recommended"));
			assertEquals(List.of("WANT_CLOSED"), trigger.get("reasons"));
			Map<String, Object> want = list(m(m(m(closed.get("sheet")).get("intimacy")).get("profile")).get("wants"))
					.get(0);
			assertEquals("DONE", want.get("status"));
			assertEquals(now, want.get("done_at"), "stamped by the clock when the caller gave none");
			assertEquals("asked by a wife", want.get("done_note"));

			// A caller's own date is kept.
			Map<String, Object> dated = engine.characters().updateCharacter(op(), campaign, ysolde, null,
					map("biography", map("wants", List.of(map("note", "the deeds room open by Lammas", "status", "DONE",
							"done_at", "Day 182, 12:00")))));
			assertEquals("Day 182, 12:00",
					list(m(m(dated.get("sheet")).get("biography")).get("wants")).get(0).get("done_at"));

			// Relationship-profile wants: the same stamp, the same recommendation.
			Map<String, Object> rel = engine.party().updateRelationship(op(), campaign, ysolde, pc, null, null, null,
					"a want met", false, "GM",
					map("wants", List.of(map("note", "a night held by the Keeper alone", "status", "DONE"))), "MERGE");
			assertEquals(List.of("WANT_CLOSED"), m(rel.get("director_trigger")).get("reasons"));
			Map<String, Object> profile = m(list(rel.get("relationships")).get(0).get("profile"));
			assertEquals(now, list(profile.get("wants")).get(0).get("done_at"));
			Map<String, Object> quiet = engine.party().updateRelationship(op(), campaign, ysolde, pc, map("trust", 5),
					null, null, "a look", false, "GM", null, null);
			assertFalse((Boolean) m(quiet.get("director_trigger")).get("recommended"));

			// Milestones between people recommend a review by their kind and weight.
			Map<String, Object> night = engine.ledger().record(op(), campaign, "INTIMACY", "Her night.",
					List.of(pc, ysolde), "NOTABLE", "PARTY_KNOWN", "GM", null, null, null, null);
			assertEquals(List.of("INTIMATE_MILESTONE"), m(night.get("director_trigger")).get("reasons"));
			Map<String, Object> wedding = engine.ledger().record(op(), campaign, "RELATIONSHIP_MILESTONE", "Wed.",
					List.of(pc, ysolde), "CRITICAL", "PARTY_KNOWN", "GM", null, null, null, null);
			assertEquals(List.of("RELATIONSHIP_MILESTONE"), m(wedding.get("director_trigger")).get("reasons"));
			Map<String, Object> small = engine.ledger().record(op(), campaign, "RELATIONSHIP_MILESTONE", "A look.",
					List.of(pc, ysolde), "NOTABLE", "PARTY_KNOWN", "GM", null, null, null, null);
			assertFalse((Boolean) m(small.get("director_trigger")).get("recommended"));
			Map<String, Object> event = engine.ledger().record(op(), campaign, "EVENT", "Rain.", List.of(pc), "MINOR",
					"PARTY_KNOWN", "GM", null, null, null, null);
			assertFalse((Boolean) m(event.get("director_trigger")).get("recommended"));
		}
	}

	@Test
	void theDirectorSeesEachCompanionsWantsAndWhatClosedSinceTheLastReview() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("wants-director"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String ysolde = (String) engine.runtime().materialize(op(), campaign, "Commoner", "Ysolde", "the steward",
					"exact; itemises everything", null, null, false).get("character");
			engine.party().updateMembership(op(), campaign, ysolde, "JOIN", null);
			engine.characters().updateCharacter(op(), campaign, ysolde, null,
					map("biography",
							map("wants",
									List.of("the deeds room open by Lammas",
											map("note", "to be needed for what she knows", "status", "DONE"))),
							"intimacy", map("wants", List.of(map("note", "to taste him", "with", List.of(pc))))));

			Map<String, Object> ctx = engine.narrative().directorContext(campaign, "CAMPAIGN_REVIEW");
			List<Map<String, Object>> companions = list(ctx.get("companion_wants"));
			assertEquals(2, companions.size(), "the player character and the companion");
			Map<String, Object> her = companions.stream().filter(c -> ysolde.equals(c.get("character"))).findFirst()
					.orElseThrow();
			assertEquals("exact; itemises everything", her.get("personality"));
			assertNotNull(m(her.get("abilities")).get("INT"));
			assertEquals(List.of("the deeds room open by Lammas"),
					list(her.get("open_wants")).stream().map(w -> w.get("note")).toList());
			assertEquals(List.of("to taste him"),
					list(her.get("open_intimate_wants")).stream().map(w -> w.get("note")).toList());
			List<Map<String, Object>> closed = list(her.get("closed_since_last_review"));
			assertEquals(1, closed.size(), "with no review yet, every closed want is recent");
			assertEquals("to be needed for what she knows", closed.get(0).get("note"));
			assertEquals("general", closed.get(0).get("kind"));
			List<String> guidance = (List<String>) ctx.get("guidance");
			assertTrue(guidance.stream().anyMatch(g -> g.contains("companion_wants")), guidance.toString());
			assertTrue(guidance.stream().anyMatch(g -> g.contains("origin: director")), guidance.toString());
			assertTrue(guidance.stream().anyMatch(g -> g.contains("PEGI_18") && g.contains("curiosity")),
					"the intimate line under PEGI_18: " + guidance);

			// After a review only what closed since counts; intimate closures are tagged.
			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "nothing to change");
			engine.sessions().advanceTime(op(), campaign, 600, "a day");
			engine.characters().updateCharacter(op(), campaign, ysolde, null,
					map("intimacy", map("wants", List.of(map("note", "to taste him", "status", "DONE")))));
			Map<String, Object> after = engine.narrative().directorContext(campaign, null);
			Map<String, Object> herAfter = list(after.get("companion_wants")).stream()
					.filter(c -> ysolde.equals(c.get("character"))).findFirst().orElseThrow();
			List<Map<String, Object>> since = list(herAfter.get("closed_since_last_review"));
			assertEquals(1, since.size(), "the want closed before the review has dropped out");
			assertEquals("to taste him", since.get(0).get("note"));
			assertEquals("intimate", since.get(0).get("kind"));
			assertNotNull(since.get(0).get("done_at"));
			assertTrue(list(herAfter.get("open_intimate_wants")).isEmpty(),
					"her every intimate want is DONE: exactly what the guidance asks the Director to notice");
			// The running PEGI 18 guidance says wants move with temperament.
			String pegi = ContentProfile.PEGI_18.guidance();
			assertTrue(pegi.contains("Those wants move"), pegi);
			assertTrue(pegi.contains("curious"), pegi);
		}
	}
}
