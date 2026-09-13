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
import se.hirt.mcp.rpg.session.SessionService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * The continuity milestone: no explicit suspend is needed, "since you last played" is derived from
 * the ledger, house rules travel with the campaign, former members say where they are, and a
 * relationship carries a profile.
 */
class ContinuityTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	@Test
	void recapIsDerivedFromTheLedgerWithoutASuspend() throws Exception {
		Path db = TestCampaigns.tempDb("continuity");
		Duration gap = SessionService.SESSION_GAP;
		try (Engine engine = TestCampaigns.engine(db)) {
			String campaign = TestCampaigns.committedCampaign(engine);
			String pc = "character:1";
			Map<String, Object> first = engine.sessions().bootstrap(op(), campaign, null);
			assertNull(m(first.get("chronicle")).get("synopsis"), "no synopsis before anyone wrote one");
			assertTrue(list(m(first.get("chronicle")).get("chapters_since_synopsis")).isEmpty());
			String session = (String) first.get("session");

			engine.ledger().record(op(), campaign, "RELATIONSHIP_MILESTONE", "Richard married Vess and Maren.",
					List.of(pc), "CRITICAL", "PARTY_KNOWN", "GM", null, null,
					"Under the ash tree at Greyfall, with the whole household present.", null);
			engine.ledger().record(op(), campaign, "QUEST_COMPLETED", "The Treasury Bell was returned.", List.of(pc),
					"MAJOR", "PARTY_KNOWN", "GM", null, null, null, null);
			engine.ledger().record(op(), campaign, "PROMISE", "Richard promised the sergeant a map.", List.of(pc),
					"NOTABLE", "PARTY_KNOWN", "GM", null, null, null, null);
			for (int i = 0; i < 3; i++) {
				engine.ledger().record(op(), campaign, "NOTE", "Small talk " + i, List.of(pc), "MINOR", "PARTY_KNOWN",
						"GM", null, null, null, null);
			}
			// Events are stamped with the session they were written in.
			long stamped = engine.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM event WHERE campaign_id = ? AND session_id = ?",
							Long.parseLong(campaign.substring(campaign.indexOf(':') + 1)),
							Long.parseLong(session.substring(session.indexOf(':') + 1))));
			assertEquals(6L, stamped);

			// A quick reconnect resumes the same session…
			Map<String, Object> again = engine.sessions().bootstrap(op(), campaign, null);
			assertEquals(true, again.get("session_resumed"));
			assertEquals(session, again.get("session"));

			// …a long gap opens a new one and the recap covers the previous session, by importance.
			SessionService.SESSION_GAP = Duration.ZERO;
			Map<String, Object> next = engine.sessions().bootstrap(op(), campaign, null);
			assertEquals(false, next.get("session_resumed"));
			assertNotEquals(session, next.get("session"));
			// The recap is the ledger since the last chapter (all of it, before the first chapter), by importance.
			Map<String, Object> recap = m(m(next.get("chronicle")).get("since_last_chapter"));
			assertNotNull(recap, "since_last_chapter");
			assertEquals(1, list(recap.get("critical")).size());
			assertTrue(String.valueOf(list(recap.get("critical")).get(0).get("detail")).contains("ash tree"),
					"critical events keep their episodic detail");
			assertTrue(list(recap.get("major")).stream()
					.anyMatch(e -> String.valueOf(e.get("summary")).contains("Treasury Bell")));
			assertTrue(
					((List<?>) recap.get("notable")).stream().anyMatch(n -> n.toString().contains("sergeant a map")));
			assertEquals(3, m(recap.get("minor_by_type")).get("NOTE"));
			assertNull(next.get("previous_session_summary"), "the pinned session summary is gone; chapters replace it");
			assertEquals(false, m(m(next.get("chronicle")).get("due")).get("chapter"),
					"six events are not a chapter's worth");
		} finally {
			SessionService.SESSION_GAP = gap;
		}
	}

	@Test
	void houseRulesAreShownAtBootstrap() throws Exception {
		Path db = TestCampaigns.tempDb("house-rules");
		try (Engine engine = TestCampaigns.engine(db)) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> added = engine.sessions().updateHouseRules(op(), campaign,
					List.of("No firearms in this world.", "Monsters are outside the never-kill rule."), null);
			assertEquals(2, ((List<?>) added.get("house_rules")).size());
			assertTrue(String.valueOf(added.get("event")).startsWith("event:"), "audited");
			engine.sessions().updateHouseRules(op(), campaign, List.of("No firearms in this world."), "ADD");
			assertEquals(2,
					((List<?>) engine.sessions()
							.updateHouseRules(op(), campaign, List.of("Probationers are asked daily."), "REMOVE")
							.get("house_rules")).size(),
					"no duplicates, removing an unknown rule is harmless");
			Map<String, Object> context = engine.sessions().bootstrap(op(), campaign, null);
			assertEquals(List.of("No firearms in this world.", "Monsters are outside the never-kill rule."),
					m(context.get("campaign")).get("house_rules"));
			assertEquals(List.of("Ilsa is nineteen."), engine.sessions()
					.updateHouseRules(op(), campaign, List.of("Ilsa is nineteen."), "REPLACE").get("house_rules"));
			assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(RpgException.class,
					() -> engine.sessions().updateHouseRules(op(), campaign, List.of(), "ADD")).code());
		}
	}

	@Test
	void relationshipProfileMergesAndFormerMembersSayWhereTheyAre() throws Exception {
		Path db = TestCampaigns.tempDb("profile");
		try (Engine engine = TestCampaigns.engine(db)) {
			String campaign = TestCampaigns.committedCampaign(engine);
			String pc = "character:1";
			engine.sessions().bootstrap(op(), campaign, null);
			String vess = (String) engine.runtime()
					.materialize(op(), campaign, "Commoner", "Vess", "a fen-born wife", "wry, brave", null, null, false)
					.get("character");
			engine.party().updateMembership(op(), campaign, vess, "JOIN", null);

			Map<String, Object> first = engine.party().updateRelationship(op(), campaign, pc, vess, map("affection", 5),
					"Married.", null, "the wedding", true, null,
					map("milestones", List.of(map("kind", "WEDDING", "note", "Under the ash tree.")), "terms",
							List.of("Tell me before I ask."), "preferences", map("likes", List.of("being held first"))),
					null);
			Map<String, Object> profile = m(list(first.get("relationships")).get(0).get("profile"));
			assertNotNull(m(list(profile.get("milestones")).get(0)).get("game_time"), "milestones are dated");
			assertEquals("WEDDING", list(profile.get("milestones")).get(0).get("kind"));

			Map<String, Object> second = engine.party().updateRelationship(op(), campaign, pc, vess, null, null, null,
					"a promise on the road", false, null,
					map("milestones", List.of(map("kind", "OATH", "note", "Never a life that can be avoided.")),
							"terms", List.of("Tell me before I ask.", "Not again till the summer."), "preferences",
							map("dislikes", List.of("being lied to")), "hard_lines", List.of("No secrets from Maren.")),
					"MERGE");
			profile = m(list(second.get("relationships")).get(0).get("profile"));
			assertEquals(2, list(profile.get("milestones")).size(), "milestones append");
			assertEquals(2, ((List<?>) profile.get("terms")).size(), "terms append without duplicates");
			assertEquals(List.of("being held first"), m(profile.get("preferences")).get("likes"), "maps overlay");
			assertEquals(List.of("being lied to"), m(profile.get("preferences")).get("dislikes"));
			assertEquals(List.of("No secrets from Maren."), profile.get("hard_lines"));

			// Both views carry the profile: get_relationship and the compact context list.
			Map<String, Object> rel = engine.party().relationship(campaign, pc, vess);
			assertEquals(2, list(m(m(rel.get("a_to_b")).get("profile")).get("milestones")).size());
			assertEquals(1, list(m(m(rel.get("b_to_a")).get("profile")).get("milestones")).size(),
					"the mutual first update wrote the wedding on both sides; the second was one-way");
			Map<String, Object> context = engine.sessions().bootstrap(op(), campaign, null);
			// Bootstrap stays compact: it says a profile exists and how big; the scopes carry it.
			assertTrue(list(context.get("relationships")).stream().noneMatch(r -> r.containsKey("profile")));
			assertEquals(2, m(list(context.get("relationships")).get(0).get("profile_available")).get("milestones"));

			// REPLACE starts over.
			Map<String, Object> replaced = engine.party().updateRelationship(op(), campaign, pc, vess, null, null, null,
					"starting over", false, null, map("wants", List.of("a child")), "REPLACE");
			profile = m(list(replaced.get("relationships")).get(0).get("profile"));
			assertNull(profile.get("milestones"));
			assertEquals(List.of("a child"), profile.get("wants"));

			// A member who leaves is listed with the note that explains it (and her whereabouts when known).
			engine.party().updateMembership(op(), campaign, vess, "LEAVE", "stayed at Sterncliff to keep the hearth");
			List<Map<String, Object>> former = list(engine.sessions().party(campaign, "SUMMARY").get("former_members"));
			assertEquals(1, former.size());
			assertEquals("LEFT", former.get(0).get("membership"));
			assertNotNull(former.get(0).get("since"));
			assertTrue(list(engine.sessions().bootstrap(op(), campaign, null).get("former_members")).size() == 1,
					"bootstrap lists former members too");
		}
	}
}
