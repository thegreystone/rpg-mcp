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
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** Director review boundaries follow committed changes even when fictional time stands still. */
class DirectorWantOrderingTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	private static Map<String, Object> companion(Engine engine, String campaign, String character) {
		return list(engine.narrative().directorContext(campaign, null).get("companion_wants")).stream()
				.filter(c -> character.equals(c.get("character"))).findFirst().orElseThrow();
	}

	private static List<Map<String, Object>> closed(Engine engine, String campaign, String character) {
		return list(companion(engine, campaign, character).get("closed_since_last_review"));
	}

	private static String joinCompanion(Engine engine, String campaign) {
		String character = (String) engine.runtime().materialize(op(), campaign, "Commoner", "Mara", "the astronomer",
				"curious and patient", null, null, false).get("character");
		engine.party().updateMembership(op(), campaign, character, "JOIN", "travelling together");
		return character;
	}

	@Test
	void closuresAtTheReviewClockRemainRecentUntilTheNextReview() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("wants-ordering"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String character = joinCompanion(engine, campaign);
			String general = "visit the observatory";
			String intimate = "a quiet evening together";
			engine.characters().updateCharacter(op(), campaign, character, null,
					map("biography", map("wants", List.of(map("note", general, "_closed_journal_id", Long.MAX_VALUE))),
							"intimacy", map("wants", List.of(intimate))));
			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "before either want closes");
			Object reviewTime = engine.narrative().directorContext(campaign, null).get("game_time");

			// The supplied date describes the fiction; caller-supplied cursors cannot change review ordering.
			String earlierDate = "Day 1, 08:00";
			Map<String, Object> changed = engine.characters().updateCharacter(op(), campaign, character, null,
					map("biography",
							map("wants", List.of(map("note", general, "status", "DONE", "_closed_journal_id", 0))),
							"intimacy", map("wants", List.of(map("note", intimate, "status", "ABANDONED", "done_at",
									earlierDate, "_closed_journal_id", Long.MAX_VALUE)))));
			assertEquals(reviewTime, engine.narrative().directorContext(campaign, null).get("game_time"),
					"closing the wants and reviewing them need not advance fictional time");
			List<Map<String, Object>> recent = closed(engine, campaign, character);
			assertEquals(List.of(general, intimate), recent.stream().map(w -> w.get("note")).toList());
			assertEquals(List.of("general", "intimate"), recent.stream().map(w -> w.get("kind")).toList());
			assertEquals(earlierDate, recent.get(1).get("done_at"));
			assertTrue(recent.stream().noneMatch(w -> w.containsKey("_closed_journal_id")),
					"Director guidance carries the wants and their fictional dates, without the internal cursor");
			assertEquals(recent, closed(engine, campaign, character), "reading context does not consume closures");

			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "both wants considered");
			assertTrue(closed(engine, campaign, character).isEmpty(), "an empty review still acknowledges closures");

			// Sending the stored aggregates again must not turn old closures into fresh ones.
			Map<String, Object> sheet = m(changed.get("sheet"));
			engine.characters().updateCharacter(op(), campaign, character, null,
					map("biography", sheet.get("biography"), "intimacy", m(sheet.get("intimacy")).get("profile")));
			assertTrue(closed(engine, campaign, character).isEmpty(), "resubmitted closed wants retain their order");

			engine.characters().updateCharacter(op(), campaign, character, null, map("biography", map("wants",
					List.of(map("note", general, "status", "OPEN", "_closed_journal_id", Long.MAX_VALUE)))));
			assertEquals(List.of(general), list(companion(engine, campaign, character).get("open_wants")).stream()
					.map(w -> w.get("note")).toList());
			assertTrue(closed(engine, campaign, character).isEmpty());
			String undatedHistory = "before the campaign began";
			engine.characters().updateCharacter(op(), campaign, character, null, map("biography", map("wants", List
					.of(map("note", general, "status", "DONE", "done_at", undatedHistory, "_closed_journal_id", 0)))));
			List<Map<String, Object>> reclosed = closed(engine, campaign, character);
			assertEquals(1, reclosed.size(), "a reopened want can close again at the same fictional time");
			assertEquals(general, reclosed.get(0).get("note"));
			assertEquals(undatedHistory, reclosed.get(0).get("done_at"));
			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "the renewed want considered");
			assertTrue(closed(engine, campaign, character).isEmpty());
		}
	}

	@Test
	void restoringACheckpointRestoresReviewOrderingAndAllowsANewClosureBranch() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("wants-ordering-restore"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String character = joinCompanion(engine, campaign);
			String want = "recover the missing star chart";
			engine.characters().updateCharacter(op(), campaign, character, null,
					map("biography", map("wants", List.of(want))));
			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "before the chart is recovered");
			Object initialReview = engine.narrative().directorContext(campaign, null).get("last_director_review");
			String beforeClosure = (String) engine.checkpoints().create(op(), campaign, "before recovery")
					.get("checkpoint");
			engine.characters().updateCharacter(op(), campaign, character, null,
					map("biography", map("wants", List.of(map("note", want, "status", "DONE")))));
			List<Map<String, Object>> firstClosure = closed(engine, campaign, character);
			assertEquals(1, firstClosure.size());
			String afterClosure = (String) engine.checkpoints().create(op(), campaign, "after recovery")
					.get("checkpoint");
			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "the chart was recovered");
			assertTrue(closed(engine, campaign, character).isEmpty());

			engine.checkpoints().restore(op(), campaign, afterClosure, "reconsider the review");
			assertEquals(initialReview, engine.narrative().directorContext(campaign, null).get("last_director_review"));
			assertEquals(firstClosure, closed(engine, campaign, character),
					"restoring before the review exposes its unreviewed closure again");

			engine.checkpoints().restore(op(), campaign, beforeClosure, "take a different path to the chart");
			assertTrue(closed(engine, campaign, character).isEmpty());
			assertFalse(list(companion(engine, campaign, character).get("open_wants")).isEmpty());
			engine.characters().updateCharacter(op(), campaign, character, null,
					map("biography", map("wants", List.of(map("note", want, "status", "DONE")))));
			assertEquals(List.of(want), closed(engine, campaign, character).stream().map(w -> w.get("note")).toList(),
					"the new branch's closure follows the restored review even without advancing time");
			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "the new branch considered");
			assertTrue(closed(engine, campaign, character).isEmpty());
		}
	}

	@Test
	void legacyClosuresKeepTheirDateBasedReviewBoundaryWhenResubmitted() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("wants-ordering-legacy"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String character = joinCompanion(engine, campaign);
			String oldWant = "chart the northern sky";
			String recentWant = "find the observatory key";
			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "the first review");
			String closureDate = (String) m(
					engine.sessions().advanceTime(op(), campaign, 10, "searching for the key").get("to"))
					.get("instant");

			// Older databases contain fictional dates but have no engine closure-order cursor.
			engine.db().mutate(
					Database.Mutation.of("legacy_wants_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null),
					tx -> {
						tx.update("character", Ref.id(character, Ref.CHARACTER),
								map("biography_json", Json.write(map("wants", List.of(
										map("note", oldWant, "status", "DONE", "done_at", "Day 1, 16:30"),
										map("note", recentWant, "status", "ABANDONED", "done_at", closureDate))))));
						return Map.of();
					});
			List<Map<String, Object>> recent = closed(engine, campaign, character);
			assertEquals(List.of(recentWant), recent.stream().map(w -> w.get("note")).toList(),
					"unstamped legacy wants still use their fictional closure dates");
			assertEquals(closureDate, recent.get(0).get("done_at"));
			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "the legacy closure considered");
			assertTrue(closed(engine, campaign, character).isEmpty());

			engine.sessions().advanceTime(op(), campaign, 10, "leaving the observatory");
			Map<String, Object> resubmitted = engine.characters().updateCharacter(op(), campaign, character, null,
					map("biography", map("wants", List.of(map("note", recentWant, "status", "ABANDONED")))));
			assertTrue(closed(engine, campaign, character).isEmpty(),
					"resubmitting a legacy closure without its date does not manufacture a newer closure");
			Map<String, Object> retained = list(m(m(resubmitted.get("sheet")).get("biography")).get("wants")).stream()
					.filter(w -> recentWant.equals(w.get("note"))).findFirst().orElseThrow();
			assertEquals(closureDate, retained.get("done_at"), "the original fictional closure date survives");
		}
	}
}
