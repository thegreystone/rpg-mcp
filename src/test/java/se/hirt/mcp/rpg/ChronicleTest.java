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
import se.hirt.mcp.rpg.session.ChronicleService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * The chronicle (MCP_PROTOCOL.md §11.6): chapters and a synopsis written by a delegated summarizer
 * from material the server cuts, closed at a marker so play may continue meanwhile; bootstrap's
 * story section stays the same size for a campaign of any length; the triggers are sizes, not
 * counts, and reach a long session through the results of play.
 */
class ChronicleTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	private static String detail(int n) {
		return ("Day by day the House kept the coals warm and said the No at dusk; entry " + n + ". ").repeat(30);
	}

	@Test
	void chaptersCloseAtTheMarkerAndTheSynopsisRollsUp() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("chronicle"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";

			// Enough ledger text to owe a chapter: the warning reaches the GM in a record_memory result.
			Map<String, Object> last = null;
			for (int i = 0; i < 12; i++) {
				last = engine.ledger().record(op(), campaign, "PLOT_EVENT", "The coal number " + i + " was found.",
						List.of(pc), "CRITICAL", "PARTY_KNOWN", "GM", null, null, detail(i), null);
			}
			assertTrue(m(last.get("meta")).get("warnings").toString().contains("CHAPTER_DUE"),
					"a long session hears about it without a bootstrap");
			Map<String, Object> boot = engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> due = m(m(boot.get("chronicle")).get("due"));
			assertEquals(true, due.get("chapter"));
			assertEquals(false, due.get("synopsis"));
			assertTrue(due.get("how").toString().contains("get_chronicle_material"));
			assertTrue(((Number) m(m(boot.get("chronicle")).get("since_last_chapter")).get("omitted_for_budget"))
					.intValue() > 0, "the tail is digested under the budget, never shipped whole");

			// The material is cut at a marker; an event recorded while the summarizer works stays uncovered.
			Map<String, Object> material = engine.chronicle().material(campaign, "CHAPTER", null);
			int uncovered = ((Number) material.get("events_uncovered")).intValue();
			assertTrue(uncovered >= 12, "twelve finds at least");
			assertEquals(uncovered, list(material.get("events")).size(), "one page holds it all");
			assertTrue(list(material.get("events")).stream()
					.anyMatch(e -> String.valueOf(e.get("detail")).contains("entry 0")));
			assertNotNull(m(material.get("voice")).get("campaign_title"));
			assertEquals(ChronicleService.CHAPTER_TARGET_CHARS, m(material.get("target")).get("chars"));
			long through = ((Number) m(material.get("through")).get("journal_id")).longValue();
			engine.ledger().record(op(), campaign, "PLOT_EVENT", "Meanwhile, a rider came.", List.of(pc), "MAJOR",
					"PARTY_KNOWN", "GM", null, null, null, null);
			Map<String, Object> written = engine.chronicle().write(op(), campaign, "CHAPTER", "The twelve coals",
					"Twelve coals were found, one a day.", through);
			Map<String, Object> chapter = m(written.get("chronicle"));
			assertEquals("CHAPTER", chapter.get("kind"));
			assertNotNull(chapter.get("written_at_game_time"));
			assertTrue(chapter.get("covers").toString().startsWith("Day 1"));
			assertEquals(false, m(written.get("due")).get("chapter"));

			boot = engine.sessions().bootstrap(op(), campaign, null);
			Map<String, Object> chronicle = m(boot.get("chronicle"));
			assertNull(chronicle.get("synopsis"));
			assertEquals(1, list(chronicle.get("chapters_since_synopsis")).size());
			assertEquals("Twelve coals were found, one a day.",
					list(chronicle.get("chapters_since_synopsis")).get(0).get("summary"));
			Map<String, Object> tail = m(chronicle.get("since_last_chapter"));
			assertEquals(1, tail.get("events"), "only the rider is uncovered");
			assertTrue(tail.toString().contains("rider"));

			// A stale or future marker is refused; a chapter that says nothing new is refused; so is a bloated one.
			RpgException stale = assertThrows(RpgException.class,
					() -> engine.chronicle().write(op(), campaign, "CHAPTER", null, "again", through));
			assertEquals(ErrorCode.CONFLICT, stale.code());
			RpgException bloated = assertThrows(RpgException.class, () -> engine.chronicle().write(op(), campaign,
					"CHAPTER", null, "x".repeat(ChronicleService.CHAPTER_MAX_CHARS + 1), null));
			assertEquals(ErrorCode.INVALID_ARGUMENT, bloated.code());
			engine.chronicle().write(op(), campaign, "CHAPTER", "The rider", "A rider came.", null);
			RpgException empty = assertThrows(RpgException.class,
					() -> engine.chronicle().write(op(), campaign, "CHAPTER", null, "nothing happened", null));
			assertEquals(ErrorCode.OPERATION_NOT_ALLOWED, empty.code());

			// Five chapters owe a synopsis; the synopsis covers them and bootstrap shrinks to it.
			for (int i = 0; i < 3; i++) {
				engine.ledger().record(op(), campaign, "PLOT_EVENT", "Chapter fodder " + i, List.of(pc), "MAJOR",
						"PARTY_KNOWN", "GM", null, null, null, null);
				engine.chronicle().write(op(), campaign, "CHAPTER", "Fodder " + i, "Fodder " + i + " happened.", null);
			}
			boot = engine.sessions().bootstrap(op(), campaign, null);
			due = m(m(boot.get("chronicle")).get("due"));
			assertEquals(true, due.get("synopsis"));
			assertEquals(5, list(m(boot.get("chronicle")).get("chapters_since_synopsis")).size());
			Map<String, Object> synopsisMaterial = engine.chronicle().material(campaign, "SYNOPSIS", null);
			assertNull(synopsisMaterial.get("current_synopsis"));
			assertEquals(5, list(synopsisMaterial.get("chapters_since")).size());
			long lastChapter = ((Number) m(synopsisMaterial.get("through")).get("chapter_id")).longValue();
			// A chapter written while the synopsis is being composed waits for the next rewrite.
			engine.ledger().record(op(), campaign, "PLOT_EVENT", "Late news.", List.of(pc), "MAJOR", "PARTY_KNOWN",
					"GM", null, null, null, null);
			engine.chronicle().write(op(), campaign, "CHAPTER", "Late", "Late news came.", null);
			Map<String, Object> synopsis = engine.chronicle().write(op(), campaign, "SYNOPSIS", "The story so far",
					"Twelve coals, a rider, and three days of fodder. Settled: the coals are warm.", lastChapter);
			assertEquals("SYNOPSIS", m(synopsis.get("chronicle")).get("kind"));
			boot = engine.sessions().bootstrap(op(), campaign, null);
			chronicle = m(boot.get("chronicle"));
			assertTrue(m(chronicle.get("synopsis")).get("summary").toString().startsWith("Twelve coals"));
			assertEquals(1, list(chronicle.get("chapters_since_synopsis")).size(), "only the late chapter");
			assertEquals("Late news came.", list(chronicle.get("chapters_since_synopsis")).get(0).get("summary"));
			assertEquals(false, m(chronicle.get("due")).get("synopsis"));
			assertEquals(0, m(chronicle.get("since_last_chapter")).get("events"));

			// A quest closing since the synopsis makes a rewrite due even with one chapter.
			engine.ledger().record(op(), campaign, "QUEST_COMPLETED", "The coals are given.", List.of(pc), "MAJOR",
					"PARTY_KNOWN", "GM", null, null, null, null);
			due = engine.db().read(tx -> ChronicleService.due(tx, 1L));
			assertEquals(true, due.get("synopsis"));
			assertTrue(due.get("reasons").toString().contains("quest closed"));

			// The previous synopsis is superseded, never returned; the new one carries the whole span.
			Map<String, Object> next = engine.chronicle().material(campaign, "SYNOPSIS", null);
			assertTrue(m(next.get("current_synopsis")).get("summary").toString().startsWith("Twelve coals"));
			assertEquals(1, list(next.get("chapters_since")).size());
			assertFalse(boot.toString().contains("previous_session_summary"));
		}
	}

	@Test
	void suspendWritesAChapterAndBootstrapHonoursItsBudget() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("chronicle-suspend"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			for (int i = 0; i < 6; i++) {
				engine.ledger().record(op(), campaign, "PLOT_EVENT", "Thing " + i, List.of(pc), "CRITICAL",
						"PARTY_KNOWN", "GM", null, null, detail(i), null);
			}
			Map<String, Object> suspended = engine.sessions().suspend(op(), campaign, "Six things happened.");
			assertEquals("Six things happened.", m(suspended.get("chapter")).get("summary"));
			Map<String, Object> small = engine.sessions().bootstrap(op(), campaign, 4000);
			Map<String, Object> budget = m(small.get("budget"));
			assertEquals(4000, budget.get("tokens"));
			assertEquals(12_000, budget.get("chars_target"));
			assertEquals("Six things happened.",
					list(m(small.get("chronicle")).get("chapters_since_synopsis")).get(0).get("summary"));
			assertEquals(0, m(m(small.get("chronicle")).get("since_last_chapter")).get("events"));
			// Suspending without a summary is allowed and writes nothing.
			Map<String, Object> quiet = engine.sessions().suspend(op(), campaign, null);
			assertNull(quiet.get("chapter"));
			assertEquals(Boolean.TRUE, quiet.get("session_closed"));
		}
	}
}
