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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Party membership as history (I-17..I-19) and episodic relationship memory (MCP_PROTOCOL.md §25.6): a new AI can
 * retrieve a proposal by relationship without loading it during routine turns.
 */
class PartyRelationshipTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	@Test
	void membershipTransitions() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("membership"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String mara = (String) engine.runtime()
					.materialize(op(), campaign, "Guard", "Mara", null, null, null, null, false).get("character");

			RpgException notMember = assertThrows(RpgException.class,
					() -> engine.party().updateMembership(op(), campaign, mara, "LEAVE", null));
			assertEquals(ErrorCode.VALIDATION_FAILED, notMember.code());
			Map<String, Object> joined = engine.party()
					.updateMembership(op(), campaign, mara, "JOIN", "she had nowhere else to go");
			assertEquals("ACTIVE", joined.get("membership_state"));
			assertEquals(2, list(joined.get("party")).size());
			assertTrue(joined.get("event").toString().startsWith("event:"));
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class,
					() -> engine.party().updateMembership(op(), campaign, mara, "JOIN", null)).code());

			engine.party().updateMembership(op(), campaign, mara, "SEPARATE", "scouting ahead");
			assertEquals(2, list(engine.sessions().party(campaign, "SUMMARY").get("members")).size(),
					"separated members are still party");
			engine.party().updateMembership(op(), campaign, mara, "REJOIN", null);
			Map<String, Object> left = engine.party()
					.updateMembership(op(), campaign, mara, "LEAVE", "a fight about the notebook");
			assertEquals("LEFT", left.get("membership_state"));
			assertEquals(1, list(left.get("party")).size());
			Map<String, Object> party = engine.sessions().party(campaign, "SUMMARY");
			assertEquals("Mara", list(party.get("former_members")).get(0).get("name"));
			// A new membership episode is a new row referencing the same character (I-19).
			engine.party().updateMembership(op(), campaign, mara, "REJOIN", "reconciled");
			long rows = engine.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM party_membership WHERE character_id = 2"));
			assertEquals(2, rows);
			long open = engine.db().read(tx -> tx.count(
					"SELECT COUNT(*) FROM party_membership WHERE character_id = 2 AND state IN ('ACTIVE','SEPARATED','GUEST')"));
			assertEquals(1, open, "at most one open membership (I-17)");

			// The player character cannot leave.
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, assertThrows(RpgException.class,
					() -> engine.party().updateMembership(op(), campaign, pc, "LEAVE", null)).code());
			// Guests.
			String guide = (String) engine.runtime()
					.materialize(op(), campaign, "Scout", "Pell", null, null, null, null, false).get("character");
			engine.party().updateMembership(op(), campaign, guide, "GUEST_ADD", "hired for the road north");
			assertEquals(3, list(engine.sessions().party(campaign, "SUMMARY").get("members")).size());
			engine.party().updateMembership(op(), campaign, guide, "GUEST_REMOVE", "paid and gone");
			assertEquals(2, list(engine.sessions().party(campaign, "SUMMARY").get("members")).size());
			List<Map<String, Object>> maraEvents = list(
					engine.ledger().queryTimeline(campaign, null, null, List.of(mara), null, null, false, 20)
							.get("events"));
			assertTrue(maraEvents.size() >= 5, "expected join/separate/rejoin/leave/rejoin, got " + maraEvents);
		}
	}

	@Test
	void episodicRelationshipMemorySurvivesANewSession() throws Exception {
		Path db = TestCampaigns.tempDb("relationship");
		String campaign;
		String pc = "character:1";
		String elara;
		try (Engine engine = TestCampaigns.engine(db)) {
			campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			elara = (String) engine.runtime()
					.materialize(op(), campaign, "Commoner", "Elara", "a scholar", "sharp, warm, allergic to nonsense", null,
							null, false).get("character");
			engine.party().updateMembership(op(), campaign, elara, "JOIN", null);

			// A cause is required (I-20); a linked event must involve both (I-21).
			assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(RpgException.class, () -> engine.party()
					.updateRelationship(op(), campaign, pc, elara, map("trust", 1), "wary", null, null, false,
							null)).code());
			Map<String, Object> first = engine.party()
					.updateRelationship(op(), campaign, pc, elara, map("trust", 1, "respect", "+2"), "Wary curiosity.",
							null, "first conversation", true, null);
			assertEquals(2, list(first.get("relationships")).size(), "mutual");
			assertEquals(2,
					m(m(list(first.get("relationships")).get(0).get("dimensions")).get("respect")).get("value"));

			Map<String, Object> proposal = engine.ledger().record(op(), campaign, "RELATIONSHIP_MILESTONE",
					"Richard proposed to Elara on the observatory roof.", List.of(pc, elara), "CRITICAL", "PARTY_KNOWN",
					"GM", "Day 3, 22:14", null,
					"After the siege, Richard took Elara to the restored observatory where they had first spoken honestly about their fears. He proposed using his mother's ring; she thought he was joking, then said yes while laughing and crying.",
					null);
			String event = (String) proposal.get("event");
			Map<String, Object> engaged = engine.party()
					.updateRelationship(op(), campaign, pc, elara, map("affection", 5, "trust", "+9", "attraction", 4),
							"Engaged; deeply affectionate, strong mutual trust.", event, null, true, null);
			assertEquals(1L, list(engaged.get("relationships")).get(0).get("significant_events"));
			assertEquals(5, m(m(list(engaged.get("relationships")).get(0).get("dimensions")).get("trust")).get("value"),
					"deltas clamp at +5");
			// An event not involving both cannot be linked.
			String other = (String) engine.ledger()
					.record(op(), campaign, "PROMISE", "Richard promised the innkeeper a favour.", List.of(pc), null,
							null, null, null, null, null, null).get("event");
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class, () -> engine.party()
					.updateRelationship(op(), campaign, pc, elara, null, null, other, null, false, null)).code());
			engine.sessions().suspend(op(), campaign, "Richard proposed; Elara said yes.");
		}
		// Years later, a fresh AI: routine context carries only the compact state…
		try (Engine engine = TestCampaigns.engine(db)) {
			Map<String, Object> ctx = engine.sessions().bootstrap(op(), campaign, null);
			List<Map<String, Object>> rels = list(ctx.get("relationships"));
			assertEquals(1, rels.size());
			assertEquals("Elara", rels.get(0).get("name"));
			assertTrue(rels.get(0).get("summary").toString().startsWith("Engaged"));
			assertEquals(1L, rels.get(0).get("significant_events"));
			assertFalse(ctx.toString().contains("mother's ring"),
					"the episodic detail is not loaded during routine turns");
			// …and the proposal is retrievable on demand, in detail.
			Map<String, Object> rel = engine.party().relationship(campaign, pc, elara);
			List<Map<String, Object>> events = list(m(rel.get("a_to_b")).get("significant_events"));
			assertEquals(1, events.size());
			assertEquals("RELATIONSHIP_MILESTONE", events.get(0).get("type"));
			assertTrue(events.get(0).get("detail").toString().contains("mother's ring"));
			assertEquals("Day 3, 22:14", events.get(0).get("game_time"));
			assertEquals("very strong", m(m(m(rel.get("b_to_a")).get("dimensions")).get("affection")).get("label"));
			// Structured search finds it too.
			Map<String, Object> memories = engine.ledger()
					.queryMemories(campaign, List.of(pc, elara), null, "proposed", null, 5);
			assertEquals(1, list(memories.get("events")).size());
		}
	}
}
