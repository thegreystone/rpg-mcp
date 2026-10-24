/*
 * Copyright (C) 2026 Marcus Hirt
 *
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.session.ChronicleService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class ChroniclePaginationTest {

	@Test
	void chapterPageMarkerNeverCoversAnUnseenEventFromTheSameJournalEntry() throws Exception {
		try (Engine engine = engine(tempDb("chronicle-group-pagination"))) {
			String campaign = campaign(engine);
			Group group = group(engine, campaign, "Connected discoveries", 20_001, 20_001, 20_001);
			Group later = group(engine, campaign, "Later discovery", 25_000);
			Map<String, List<Map<String, Object>>> beforeRead = snapshot(engine, campaign);
			Map<String, Object> material = engine.chronicle().material(campaign, "CHAPTER", null);
			assertEquals(beforeRead, snapshot(engine, campaign));
			assertEquals(group.ids(), eventIds(material));
			assertEquals(group.journal(), through(material));
			assertEquals(group.ids().getLast().toString(), material.get("next_cursor"));
			assertEquals(4, ((Number) material.get("events_uncovered")).intValue());

			Map<String, Object> written = engine.chronicle().write(op(), campaign, "CHAPTER", "Grouped discoveries",
					"The party discovered the three connected clues.", through(material));
			Map<String, Object> remaining = engine.chronicle().material(campaign, "CHAPTER", null);
			assertEquals(later.ids(), eventIds(remaining));
			assertEquals(true, m(written.get("due")).get("chapter"));
			Map<String, Object> digest = m(
					m(engine.narrative().context(campaign, "SCENE", null, null, null, null).get("chronicle"))
							.get("since_last_chapter"));
			assertEquals(1, ((Number) digest.get("events")).intValue());
			assertEquals(3L, ((Number) latestChapter(engine, campaign).get("events_covered")).longValue());
			assertEquals(60_003L, ((Number) latestChapter(engine, campaign).get("chars_covered")).longValue());
		}
	}

	@Test
	void aPageFinishesTheWholeJournalGroupThatCrossesTheTargetAfterAPrefix() throws Exception {
		try (Engine engine = engine(tempDb("chronicle-prefix-boundary"))) {
			String campaign = campaign(engine);
			Group prefix = group(engine, campaign, "Prefix", 15_000);
			Group crossing = group(engine, campaign, "Crossing group", 13_000, 13_000, 13_000);
			Group later = group(engine, campaign, "Deferred group", 100);

			Map<String, Object> page = engine.chronicle().material(campaign, "CHAPTER", null);

			List<Long> expected = new ArrayList<>(prefix.ids());
			expected.addAll(crossing.ids());
			assertEquals(expected, eventIds(page));
			assertEquals(crossing.journal(), through(page));
			assertEquals(crossing.ids().getLast().toString(), page.get("next_cursor"));
			assertEquals(5, ((Number) page.get("events_uncovered")).intValue());
			Map<String, Object> next = engine.chronicle().material(campaign, "CHAPTER",
					(String) page.get("next_cursor"));
			assertEquals(later.ids(), eventIds(next));
			assertEquals(5, ((Number) next.get("events_uncovered")).intValue());
			assertFalse(next.containsKey("next_cursor"));
		}
	}

	@Test
	void exactTargetStopsAtTheNextJournalBoundaryAndContinuationKeepsTheFullTotal() throws Exception {
		try (Engine engine = engine(tempDb("chronicle-exact-target"))) {
			String campaign = campaign(engine);
			Group exact = group(engine, campaign, "Exact target", ChronicleService.MATERIAL_PAGE_CHARS / 2,
					ChronicleService.MATERIAL_PAGE_CHARS / 2);
			Group later = group(engine, campaign, "After exact target", 100);
			Map<String, Object> page = engine.chronicle().material(campaign, "CHAPTER", null);
			assertEquals(exact.ids(), eventIds(page));
			assertEquals(exact.journal(), through(page));
			assertEquals(3, ((Number) page.get("events_uncovered")).intValue());

			Map<String, Object> next = engine.chronicle().material(campaign, "CHAPTER",
					(String) page.get("next_cursor"));
			assertEquals(later.ids(), eventIds(next));
			assertEquals(later.journal(), through(next));
			assertEquals(3, ((Number) next.get("events_uncovered")).intValue());
			assertEquals(false, next.get("nothing_to_cover"));
			assertFalse(next.containsKey("next_cursor"));
		}
	}

	@Test
	void multiplePagesHaveNoGapsOrDuplicatesAndDoNotMutateState() throws Exception {
		try (Engine engine = engine(tempDb("chronicle-multiple-pages"))) {
			String campaign = campaign(engine);
			List<Group> groups = List.of(group(engine, campaign, "First", 20_000, 20_000),
					group(engine, campaign, "Second", 16_000, 16_000, 16_000), group(engine, campaign, "Third", 40_000),
					group(engine, campaign, "Fourth", 5_000, 5_000));
			List<Long> expected = groups.stream().flatMap(group -> group.ids().stream()).toList();
			Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
			List<Long> seen = new ArrayList<>();
			String cursor = null;
			int pages = 0;
			do {
				Map<String, Object> page = engine.chronicle().material(campaign, "CHAPTER", cursor);
				List<Long> pageIds = eventIds(page);
				assertEquals(groups.get(pages).ids(), pageIds);
				assertEquals(groups.get(pages).journal(), through(page));
				assertEquals(expected.size(), ((Number) page.get("events_uncovered")).intValue());
				seen.addAll(pageIds);
				cursor = (String) page.get("next_cursor");
				if (cursor != null) {
					assertEquals(pageIds.getLast().toString(), cursor);
				}
				pages++;
				assertTrue(pages <= groups.size(), "pagination must make progress");
			} while (cursor != null);
			assertEquals(4, pages);
			assertEquals(expected, seen);
			assertEquals(seen.size(), new HashSet<>(seen).size());
			assertEquals(before, snapshot(engine, campaign));
		}
	}

	@Test
	void minorAndDirectorOnlyEventsDoNotAffectPageCountsOrJournalBoundaries() throws Exception {
		try (Engine engine = engine(tempDb("chronicle-pagination-visibility"))) {
			String campaign = campaign(engine);
			long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
			Map<String, Object> filteredGroup = engine.db().mutate(fixtureMutation(campaign), tx -> {
				long first = LedgerService.append(tx, campaignId,
						event("Visible first", 20_000, "MAJOR", "PARTY_KNOWN"));
				LedgerService.append(tx, campaignId, event("Minor bookkeeping", 70_000, "MINOR", "PARTY_KNOWN"));
				LedgerService.append(tx, campaignId,
						event("Secret future betrayal", 70_000, "CRITICAL", "DIRECTOR_ONLY"));
				long second = LedgerService.append(tx, campaignId, event("Visible second", 20_000, "MAJOR", "GM_ONLY"));
				return map("event_ids", List.of(first, second), "journal_id", tx.journalId());
			});
			Group later = group(engine, campaign, "Visible later", 100);

			Map<String, Object> page = engine.chronicle().material(campaign, "CHAPTER", null);

			assertEquals(ids(filteredGroup.get("event_ids")), eventIds(page));
			assertEquals(((Number) filteredGroup.get("journal_id")).longValue(), through(page));
			assertEquals(3, ((Number) page.get("events_uncovered")).intValue());
			assertFalse(page.toString().contains("Secret future betrayal"));
			assertFalse(page.toString().contains("Minor bookkeeping"));
			engine.chronicle().write(op(), campaign, "CHAPTER", "Visible discoveries",
					"The two visible discoveries were connected.", through(page));
			assertEquals(2L, ((Number) latestChapter(engine, campaign).get("events_covered")).longValue());
			assertEquals(40_000L, ((Number) latestChapter(engine, campaign).get("chars_covered")).longValue());
			assertEquals(later.ids(), eventIds(engine.chronicle().material(campaign, "CHAPTER", null)));
		}
	}

	@Test
	void pageWritesReplayAndRestoreWithoutChangingTheUnpagedSynopsisContract() throws Exception {
		try (Engine engine = engine(tempDb("chronicle-pagination-checkpoint"))) {
			String campaign = campaign(engine);
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "before the discoveries")
					.get("checkpoint");
			Map<String, List<Map<String, Object>>> original = snapshot(engine, campaign);
			Group first = group(engine, campaign, "First discoveries", 17_000, 17_000, 17_000);
			Group second = group(engine, campaign, "Later discoveries", 50_000);
			Map<String, Object> page = engine.chronicle().material(campaign, "CHAPTER", null);
			assertEquals(first.ids(), eventIds(page));
			String writeId = op();
			Map<String, Object> firstWrite = engine.chronicle().write(writeId, campaign, "CHAPTER", "First discoveries",
					"The first connected discoveries changed the party's plans.", through(page));
			Map<String, List<Map<String, Object>>> written = snapshot(engine, campaign);
			assertEquals(true,
					engine.chronicle()
							.write(writeId, campaign, "CHAPTER", "First discoveries",
									"The first connected discoveries changed the party's plans.", through(page))
							.get("replayed"));
			assertEquals(written, snapshot(engine, campaign));
			Map<String, Object> next = engine.chronicle().material(campaign, "CHAPTER", null);
			assertEquals(second.ids(), eventIds(next));
			Map<String, Object> secondWrite = engine.chronicle().write(op(), campaign, "CHAPTER", "Later discoveries",
					"Later discoveries confirmed the connection.", through(next));

			Map<String, List<Map<String, Object>>> beforeSynopsisRead = snapshot(engine, campaign);
			Map<String, Object> synopsis = engine.chronicle().material(campaign, "SYNOPSIS", null);
			List<String> refs = list(synopsis.get("chapters_since")).stream()
					.map(chapter -> (String) chapter.get("ref")).toList();
			assertTrue(refs.contains((String) m(firstWrite.get("chronicle")).get("ref")));
			assertTrue(refs.contains((String) m(secondWrite.get("chronicle")).get("ref")));
			assertFalse(synopsis.containsKey("next_cursor"));
			assertEquals(beforeSynopsisRead, snapshot(engine, campaign));
			engine.chronicle().write(op(), campaign, "SYNOPSIS", "Connected discoveries",
					"The discoveries led to a new course.",
					((Number) m(synopsis.get("through")).get("chapter_id")).longValue());
			assertEquals(true, engine.chronicle().material(campaign, "SYNOPSIS", null).get("nothing_to_cover"));

			engine.checkpoints().restore(op(), campaign, checkpoint, "return before the discoveries and summaries");
			assertEquals(original, snapshot(engine, campaign));
			Map<String, Object> restored = engine.chronicle().material(campaign, "CHAPTER", null);
			assertEquals(List.of(), eventIds(restored));
			assertEquals(true, restored.get("nothing_to_cover"));
			assertEquals(0, ((Number) restored.get("events_uncovered")).intValue());
			assertNull(restored.get("through"));
			assertFalse(restored.containsKey("next_cursor"));
		}
	}

	private record Group(List<Long> ids, long journal) {
	}

	private static String campaign(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		Map<String, Object> initial = engine.chronicle().material(campaign, "CHAPTER", null);
		if (!eventIds(initial).isEmpty()) {
			engine.chronicle().write(op(), campaign, "CHAPTER", "Campaign opening", "The campaign began.",
					through(initial));
		}
		return campaign;
	}

	private static Group group(Engine engine, String campaign, String label, int ... lengths) {
		long campaignId = Ref.id(campaign, Ref.CAMPAIGN);
		Map<String, Object> result = engine.db().mutate(fixtureMutation(campaign), tx -> {
			List<Long> ids = new ArrayList<>();
			for (int i = 0; i < lengths.length; i++) {
				ids.add(LedgerService.append(tx, campaignId,
						event(label + " " + i, lengths[i], "MAJOR", "PARTY_KNOWN")));
			}
			return map("event_ids", ids, "journal_id", tx.journalId());
		});
		return new Group(ids(result.get("event_ids")), ((Number) result.get("journal_id")).longValue());
	}

	private static LedgerService.EventSpec event(String summary, int chars, String importance, String visibility) {
		return new LedgerService.EventSpec("PLOT_EVENT", summary, List.of(1L), importance, visibility, "GM", null, null,
				"x".repeat(chars - summary.length()), null);
	}

	private static Database.Mutation fixtureMutation(String campaign) {
		return Database.Mutation.of("chronicle_pagination_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null);
	}

	private static long through(Map<String, Object> material) {
		return ((Number) m(material.get("through")).get("journal_id")).longValue();
	}

	private static List<Long> eventIds(Map<String, Object> material) {
		return list(material.get("events")).stream().map(event -> ((Number) event.get("id")).longValue()).toList();
	}

	private static Map<String, Object> latestChapter(Engine engine, String campaign) {
		return engine.db()
				.read(tx -> tx.queryOne(
						"SELECT * FROM chronicle WHERE campaign_id = ? AND kind = 'CHAPTER' ORDER BY id DESC LIMIT 1",
						Ref.id(campaign, Ref.CAMPAIGN)).orElseThrow().asMap());
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Engine engine, String campaign) {
		return engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	private static List<Long> ids(Object value) {
		return ((List<?>) value).stream().map(id -> ((Number) id).longValue()).toList();
	}
}
