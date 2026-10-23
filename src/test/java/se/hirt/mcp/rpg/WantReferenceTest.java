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
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/** Wants keep their identity while their wording, status and campaign branch change. */
class WantReferenceTest {

	@Test
	void referencesSupportPartialUpdatesRenamesAndLegacyNoteMatching() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("want-refs"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String character = companion(engine, campaign, "Mara");
			Map<String, Object> changes = map("biography", map("wants",
					List.of(map("note", "visit the observatory", "with", List.of("character:1"), "origin", "director"),
							"recover the star chart")),
					"intimacy", map("wants", List.of(map("note", "a quiet evening", "with", List.of("character:1")))));
			String operation = op();
			Map<String, Object> first = engine.characters().updateCharacter(operation, campaign, character, null,
					changes);
			long identities = engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM want_identity"));
			Map<String, Object> replay = engine.characters().updateCharacter(operation, campaign, character, null,
					changes);
			assertEquals(Boolean.TRUE, replay.get("replayed"));
			assertEquals(Json.write(first.get("sheet")), Json.write(replay.get("sheet")),
					"replaying the operation must not allocate different identities");
			assertEquals(identities,
					engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM want_identity")).longValue());
			List<Map<String, Object>> general = wants(engine, campaign, character, false);
			List<Map<String, Object>> intimate = wants(engine, campaign, character, true);
			var refs = new HashSet<Object>();
			general.forEach(w -> refs.add(w.get("ref")));
			intimate.forEach(w -> refs.add(w.get("ref")));
			assertEquals(3, refs.size(), "wants created together, including across aggregates, have distinct refs");
			assertTrue(refs.stream().allMatch(r -> r instanceof String s && s.matches("want:[1-9][0-9]*")));
			Map<String, Object> original = byNote(general, "visit the observatory");
			String want = (String) original.get("ref");
			String chart = (String) byNote(general, "recover the star chart").get("ref");
			String intimateWant = (String) intimate.get(0).get("ref");
			String event = memory(engine, campaign, character, "The observatory opened.");
			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "before the visit");

			Map<String, Object> closed = updateWant(engine, campaign, character, false,
					map("ref", want, "status", "DONE", "done_note", "Mara opened the dome", "resolved_by", event,
							"_engine", map("ref", chart, "closed_journal_id", Long.MAX_VALUE)));
			assertTrue((Boolean) m(closed.get("director_trigger")).get("recommended"));
			Map<String, Object> done = byRef(wants(engine, campaign, character, false), want);
			assertEquals("visit the observatory", done.get("note"), "a ref-only update retains the prose");
			assertEquals(original.get("since"), done.get("since"));
			assertEquals(original.get("with"), done.get("with"));
			assertEquals("director", done.get("origin"));
			assertEquals(event, done.get("resolved_by"));
			assertNotNull(done.get("done_at"));
			assertEquals(List.of(want), closed(engine, campaign, character).stream().map(w -> w.get("ref")).toList());
			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "the visit considered");

			updateWant(engine, campaign, character, false, map("ref", want, "note", "watch the stars from the dome"));
			Map<String, Object> renamed = byRef(wants(engine, campaign, character, false), want);
			assertEquals("watch the stars from the dome", renamed.get("note"));
			for (String key : List.of("since", "with", "origin", "status", "done_at", "done_note", "resolved_by")) {
				assertEquals(done.get(key), renamed.get(key), "renaming preserves " + key);
			}
			assertTrue(closed(engine, campaign, character).isEmpty(),
					"renaming a reviewed closure is not a new closure");

			updateWant(engine, campaign, character, false, map("note", "recover the star chart", "status", "DONE"));
			assertEquals(chart, byNote(wants(engine, campaign, character, false), "recover the star chart").get("ref"),
					"legacy note-based input updates the existing identity");
			assertEquals(2, wants(engine, campaign, character, false).size());
			updateWant(engine, campaign, character, true, map("ref", intimateWant, "status", "ABANDONED"));
			assertEquals("a quiet evening", byRef(wants(engine, campaign, character, true), intimateWant).get("note"));

			engine.narrative().commitDirectorChanges(op(), campaign, List.of(), "all wants considered");
			updateWant(engine, campaign, character, false, map("ref", want, "status", "OPEN"));
			Map<String, Object> reopened = byRef(wants(engine, campaign, character, false), want);
			for (String key : List.of("done_at", "done_note", "resolved_by")) {
				assertFalse(reopened.containsKey(key), "reopening clears stale closure information: " + key);
			}
			assertTrue(closed(engine, campaign, character).isEmpty());
			updateWant(engine, campaign, character, false, map("ref", want, "status", "DONE"));
			Map<String, Object> reclosed = byRef(wants(engine, campaign, character, false), want);
			assertNotNull(reclosed.get("done_at"));
			assertFalse(reclosed.containsKey("resolved_by"),
					"a new closure must not silently reuse the old resolution event");
			assertFalse(reclosed.containsKey("done_note"), "a new closure must not silently reuse the old explanation");
			assertEquals(List.of(want), closed(engine, campaign, character).stream().map(w -> w.get("ref")).toList(),
					"reopening and closing again creates a new closure without changing identity or advancing time");
		}
	}

	@Test
	void distinctReferencesRemainActionableWhenWordingBecomesIdentical() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("want-ambiguous"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String character = companion(engine, campaign, "Mara");
			engine.characters().updateCharacter(op(), campaign, character, null,
					map("biography", map("wants", List.of("find the star chart", "find the copper key"))));
			String chart = (String) byNote(wants(engine, campaign, character, false), "find the star chart").get("ref");
			String key = (String) byNote(wants(engine, campaign, character, false), "find the copper key").get("ref");
			updateWant(engine, campaign, character, false, map("ref", chart, "note", "find the copper key"));
			assertEquals(2, wants(engine, campaign, character, false).size(),
					"identical wording does not merge identities");
			assertRejectedAtomically(engine, campaign, character,
					map("biography", map("wants", List.of(map("note", "find the copper key", "status", "DONE")))));
			updateWant(engine, campaign, character, false, map("ref", key, "status", "DONE"));
			assertEquals("OPEN", byRef(wants(engine, campaign, character, false), chart).get("status"));
			assertEquals("DONE", byRef(wants(engine, campaign, character, false), key).get("status"));
		}
	}

	@Test
	void referencesRemainAvailableInCompactContextAndPrivateBookkeepingNeverLeavesTheEngine() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("want-views"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String character = companion(engine, campaign, "Mara");
			engine.characters().updateCharacter(op(), campaign, character, null, map("biography",
					map("wants", List.of("visit the observatory", map("note", "recover the chart", "status", "DONE"))),
					"intimacy", map("wants", List.of(map("note", "an evening together", "status", "DONE")))));
			Map<String, Object> full = engine.characters().characterSheet(campaign, character, "FULL");
			Map<String, Object> open = byNote(wants(engine, campaign, character, false), "visit the observatory");
			String want = (String) open.get("ref");
			Map<String, Object> brief = map("ref", want, "note", open.get("note"));
			assertEquals(List.of(brief),
					engine.characters().characterSheet(campaign, character, "SUMMARY").get("wants"));
			Map<String, Object> party = engine.sessions().party(campaign, "SUMMARY");
			assertEquals(List.of(brief), byRef(list(party.get("members")), character).get("wants"));
			Map<String, Object> bootstrap = engine.sessions().bootstrap(op(), campaign, null);
			assertEquals(List.of(brief), byRef(list(bootstrap.get("party")), character).get("wants"));
			Map<String, Object> scene = engine.narrative().context(campaign, "SCENE", null, null, null, null);
			assertEquals(List.of(brief), byRef(list(scene.get("party")), character).get("wants"));

			Map<String, Object> context = engine.narrative().context(campaign, "WANT", want, null, null, null);
			Map<String, Object> lookedUp = m(context.get("want"));
			assertEquals(want, lookedUp.get("ref"));
			assertEquals(character, lookedUp.get("character"));
			assertEquals("general", lookedUp.get("kind"));
			assertEquals(open.get("note"), lookedUp.get("note"));
			assertEquals(lookedUp,
					engine.narrative().context(campaign, "WANT", " " + want + " ", null, null, null).get("want"),
					"typed references accept surrounding whitespace consistently");
			Map<String, Object> search = engine.sessions().find(campaign, "WANT", "observatory", 1);
			assertEquals(1, search.get("total_matches"));
			assertEquals(want, list(search.get("hits")).get(0).get("ref"));
			assertEquals(character, list(search.get("hits")).get(0).get("character"));

			for (Object view : List.of(full, party, bootstrap, scene, context, search,
					engine.sessions().party(campaign, "FULL"),
					engine.narrative().context(campaign, "CHARACTER", character, null, null, null),
					engine.narrative().context(campaign, "INTIMACY", character, "character:1", null, null),
					engine.narrative().directorContext(campaign, null))) {
				assertNoEngineMetadata(view);
			}
		}
	}

	@Test
	void invalidOrMisdirectedReferencesRejectTheWholeUpdate() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("want-scope"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String character = companion(engine, campaign, "Mara");
			String other = companion(engine, campaign, "Ilsa");
			updateWant(engine, campaign, character, true, map("note", "an evening together"));
			String intimate = (String) wants(engine, campaign, character, true).get(0).get("ref");
			updateWant(engine, campaign, other, false, map("note", "find the copper key"));
			String otherWant = (String) wants(engine, campaign, other, false).get(0).get("ref");
			String foreignCampaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), foreignCampaign, null);
			String foreignCharacter = companion(engine, foreignCampaign, "Tilda");
			updateWant(engine, foreignCampaign, foreignCharacter, false, map("note", "cross the northern pass"));
			String foreign = (String) wants(engine, foreignCampaign, foreignCharacter, false).get(0).get("ref");

			for (String ref : List.of("want:999999999", "character:1", intimate, otherWant, foreign)) {
				assertRejectedAtomically(engine, campaign, character, map("description", "must not be retained",
						"biography",
						map("wants", List.of("must not allocate an identity", map("ref", ref, "status", "DONE")))));
			}
			assertThrows(RpgException.class,
					() -> engine.narrative().context(campaign, "WANT", foreign, null, null, null));
			assertTrue(list(engine.sessions().find(campaign, "WANT", "northern pass", null).get("hits")).isEmpty(),
					"find never crosses the campaign boundary");
		}
	}

	@Test
	void resolvingAWantRequiresAnExistingEventFromItsCampaign() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("want-resolution"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String character = companion(engine, campaign, "Mara");
			updateWant(engine, campaign, character, false, map("note", "find the copper key"));
			String want = (String) wants(engine, campaign, character, false).get(0).get("ref");
			String foreignCampaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), foreignCampaign, null);
			String foreignCharacter = companion(engine, foreignCampaign, "Tilda");
			String foreignEvent = memory(engine, foreignCampaign, foreignCharacter, "A key was found elsewhere.");
			for (String event : List.of("event:999999999", "character:1", foreignEvent)) {
				assertRejectedAtomically(engine, campaign, character, map("biography",
						map("wants", List.of(map("ref", want, "status", "DONE", "resolved_by", event)))));
			}
			String event = memory(engine, campaign, character, "Mara found the copper key.");
			updateWant(engine, campaign, character, false, map("ref", want, "status", "DONE", "resolved_by", event));
			assertEquals(event, m(engine.narrative().context(campaign, "WANT", want, null, null, null).get("want"))
					.get("resolved_by"));
		}
	}

	@Test
	void intimateReferencesRespectTheContentProfileAtLookupAndSearchTime() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("want-privacy"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String character = companion(engine, campaign, "Mara");
			updateWant(engine, campaign, character, false, map("note", "an evening at the observatory"));
			updateWant(engine, campaign, character, true, map("note", "an evening together"));
			String intimate = (String) wants(engine, campaign, character, true).get(0).get("ref");
			assertEquals("intimate",
					m(engine.narrative().context(campaign, "WANT", intimate, null, null, null).get("want"))
							.get("kind"));
			assertEquals(2, engine.sessions().find(campaign, "WANT", "an evening", null).get("total_matches"));
			engine.db().mutate(
					Database.Mutation.of("content_profile_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null),
					tx -> {
						long policy = tx.queryOne("SELECT id FROM policy_state WHERE campaign_id = ?",
								Ref.id(campaign, Ref.CAMPAIGN)).orElseThrow().id();
						tx.update("policy_state", policy, map("content_profile", "PEGI_16"));
						return Map.of();
					});
			RpgException denied = assertThrows(RpgException.class,
					() -> engine.narrative().context(campaign, "WANT", intimate, null, null, null));
			assertEquals(ErrorCode.POLICY_DENIED, denied.code());
			List<Map<String, Object>> hits = list(
					engine.sessions().find(campaign, "WANT", "an evening", null).get("hits"));
			assertEquals(1, hits.size());
			assertEquals("an evening at the observatory", hits.get(0).get("note"));
		}
	}

	@Test
	void identitiesSurviveRestartAndRestoreWithoutBeingReusedForDiscardedWants() throws Exception {
		Path database = TestCampaigns.tempDb("want-restart");
		String campaign;
		String character;
		String want;
		String checkpoint;
		String discarded;
		try (Engine engine = TestCampaigns.engine(database)) {
			campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			character = companion(engine, campaign, "Mara");
			updateWant(engine, campaign, character, false, map("note", "visit the observatory"));
			want = (String) wants(engine, campaign, character, false).get(0).get("ref");
			checkpoint = (String) engine.checkpoints().create(op(), campaign, "before the visit").get("checkpoint");
			updateWant(engine, campaign, character, false,
					map("ref", want, "note", "watch the northern stars", "status", "DONE"));
			updateWant(engine, campaign, character, false, map("note", "the discarded road"));
			discarded = (String) byNote(wants(engine, campaign, character, false), "the discarded road").get("ref");
		}
		try (Engine engine = TestCampaigns.engine(database)) {
			assertEquals("watch the northern stars",
					byRef(wants(engine, campaign, character, false), want).get("note"));
			engine.checkpoints().restore(op(), campaign, checkpoint, "take another road");
			Map<String, Object> restored = byRef(wants(engine, campaign, character, false), want);
			assertEquals("visit the observatory", restored.get("note"));
			assertEquals("OPEN", restored.get("status"));
			assertThrows(RpgException.class,
					() -> engine.narrative().context(campaign, "WANT", discarded, null, null, null),
					"a registry entry alone cannot resurrect a want from a discarded branch");
			updateWant(engine, campaign, character, false, map("note", "a different road"));
			String next = (String) byNote(wants(engine, campaign, character, false), "a different road").get("ref");
			assertNotEquals(discarded, next,
					"a reference already seen by the model must never identify a different want");
			assertNotEquals(want, next);
		}
	}

	@Test
	void upgradingLegacyWantsPreservesStoredJsonAndReferencesThroughHistoricalRestore() throws Exception {
		Path database = TestCampaigns.tempDb("want-legacy");
		String campaign;
		String character;
		String checkpoint;
		String bareWant = "remember the northern road";
		String legacyIntimacy = Json
				.write(map("wants", List.of(map("note", "an evening by the fire", "status", "OPEN"))));
		String unchanged = Json.write(map("note", "keep the copper key", "status", "OPEN", "since", "Day 1, 08:00"));
		String collision = Json.write(map("note", "z collision legacyref", "ref", "want:1", "status", "OPEN"));
		String current = Json.write(map("wants", List.of(Json.readMap(unchanged), bareWant, Json.readMap(collision))));
		String original = Json.write(map("wants",
				List.of(map("note", "visit the old observatory", "status", "OPEN", "since", "Day 1, 08:00"),
						Json.readMap(unchanged), bareWant, Json.readMap(collision))));
		try (Engine engine = TestCampaigns.engine(database)) {
			campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			character = companion(engine, campaign, "Mara");
			legacyBiography(engine, campaign, character, original);
			engine.db().mutate(
					Database.Mutation.of("legacy_intimacy_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null),
					tx -> {
						tx.update("character", Ref.id(character, Ref.CHARACTER), map("intimacy_json", legacyIntimacy));
						return Map.of();
					});
			checkpoint = (String) engine.checkpoints().create(op(), campaign, "the old observatory still matters")
					.get("checkpoint");
			legacyBiography(engine, campaign, character, current);
			// Re-create a V008 database: V009 must discover both the current JSON and journal before-images.
			engine.db().mutate(
					Database.Mutation.of("legacy_schema_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null),
					tx -> {
						tx.rawExecute("DROP TABLE want_identity");
						tx.rawExecute("DELETE FROM schema_version WHERE version = 9");
						return Map.of();
					});
		}
		String preserved;
		String historical;
		String bareReference;
		String intimateReference;
		try (Engine engine = TestCampaigns.engine(database)) {
			assertEquals(current, rawBiography(engine, character), "migration leaves legacy aggregate bytes untouched");
			assertEquals(legacyIntimacy,
					engine.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).str("intimacy_json")));
			intimateReference = (String) wants(engine, campaign, character, true).getFirst().get("ref");
			assertNotNull(intimateReference);
			preserved = (String) wants(engine, campaign, character, false).get(0).get("ref");
			assertNotNull(preserved);
			Map<String, Object> bare = byNote(wants(engine, campaign, character, false), bareWant);
			bareReference = (String) bare.get("ref");
			assertNotNull(bareReference, "legacy bare strings also receive a stable public reference");
			assertEquals(bareWant, m(engine.narrative()
					.context(campaign, "WANT", (String) bare.get("ref"), null, null, null).get("want")).get("note"));
			assertEquals(current, rawBiography(engine, character),
					"reading a reference does not rewrite the aggregate");
			engine.checkpoints().restore(op(), campaign, checkpoint, "return to the old observatory");
			assertEquals(original, rawBiography(engine, character),
					"the checkpoint restores the original legacy JSON exactly");
			List<Map<String, Object>> restored = wants(engine, campaign, character, false);
			assertEquals(restored.size(), new HashSet<>(restored.stream().map(w -> w.get("ref")).toList()).size(),
					"a legacy free-form ref cannot claim another want's newly allocated identity");
			for (Map<String, Object> want : restored) {
				Map<String, Object> resolved = m(engine.narrative()
						.context(campaign, "WANT", (String) want.get("ref"), null, null, null).get("want"));
				assertEquals(want.get("note"), resolved.get("note"),
						"each overlaid reference resolves its own legacy note");
			}
			assertEquals(preserved, byNote(restored, "keep the copper key").get("ref"));
			assertEquals(bareReference, byNote(restored, bareWant).get("ref"));
			historical = (String) byNote(restored, "visit the old observatory").get("ref");
			assertNotNull(historical, "a want found only in historical undo data also has an identity");
			updateWant(engine, campaign, character, false, map("note", "visit the old observatory", "status", "DONE"));
			assertEquals(restored.size(), wants(engine, campaign, character, false).size(),
					"closing one legacy want does not remove siblings with colliding old ref fields");
			List<?> stored = (List<?>) Json.readMap(rawBiography(engine, character)).get("wants");
			assertEquals(historical, byNote(stored, "visit the old observatory").get("ref"));
			assertEquals(Json.readMap(unchanged), byNote(stored, "keep the copper key"),
					"editing one legacy want does not upgrade an untouched sibling");
			assertTrue(stored.contains(bareWant), "an untouched legacy bare string also remains unchanged");
			assertEquals(Json.readMap(collision), byNote(stored, "z collision legacyref"),
					"the legacy free-form field remains intact in canonical storage");
			updateWant(engine, campaign, character, true, map("note", "an evening by the fire", "status", "ABANDONED"));
			assertEquals(intimateReference, wants(engine, campaign, character, true).getFirst().get("ref"));
		}
		try (Engine engine = TestCampaigns.engine(database)) {
			assertEquals(bareReference, byNote(wants(engine, campaign, character, false), bareWant).get("ref"));
			assertEquals(historical,
					byNote(wants(engine, campaign, character, false), "visit the old observatory").get("ref"));
			engine.checkpoints().restore(op(), campaign, checkpoint, "restore the old record again");
			assertEquals(legacyIntimacy,
					engine.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).str("intimacy_json")));
			assertEquals(intimateReference, wants(engine, campaign, character, true).getFirst().get("ref"));
			assertEquals(historical,
					byNote(wants(engine, campaign, character, false), "visit the old observatory").get("ref"));
			assertEquals(preserved,
					byNote(wants(engine, campaign, character, false), "keep the copper key").get("ref"));
		}
	}

	private static String companion(Engine engine, String campaign, String name) {
		String character = (String) engine.runtime().materialize(op(), campaign, "Commoner", name, "the astronomer",
				"curious and patient", null, null, false).get("character");
		engine.party().updateMembership(op(), campaign, character, "JOIN", "travelling together");
		return character;
	}

	private static String memory(Engine engine, String campaign, String character, String summary) {
		return (String) engine.ledger().record(op(), campaign, "EVENT", summary, List.of(character), "NOTABLE",
				"PARTY_KNOWN", "GM", null, null, null, null).get("event");
	}

	private static Map<String, Object> updateWant(
		Engine engine, String campaign, String character, boolean intimate, Map<String, Object> want) {
		return engine.characters().updateCharacter(op(), campaign, character, null,
				map(intimate ? "intimacy" : "biography", map("wants", List.of(want))));
	}

	private static List<Map<String, Object>> wants(Engine engine, String campaign, String character, boolean intimate) {
		Map<String, Object> sheet = engine.characters().characterSheet(campaign, character, "FULL");
		Map<String, Object> profile = intimate ? m(m(sheet.get("intimacy")).get("profile")) : m(sheet.get("biography"));
		return list(profile.get("wants"));
	}

	private static List<Map<String, Object>> closed(Engine engine, String campaign, String character) {
		Map<String, Object> companion = list(engine.narrative().directorContext(campaign, null).get("companion_wants"))
				.stream().filter(c -> character.equals(c.get("character"))).findFirst().orElseThrow();
		return list(companion.get("closed_since_last_review"));
	}

	private static Map<String, Object> byNote(List<?> wants, String note) {
		return wants.stream().filter(w -> w instanceof Map<?, ?> m && note.equals(m.get("note")))
				.map(WantReferenceTest::m).findFirst().orElseThrow();
	}

	private static Map<String, Object> byRef(List<Map<String, Object>> entries, String ref) {
		return entries.stream().filter(w -> ref.equals(w.get("ref"))).findFirst().orElseThrow();
	}

	private static void assertRejectedAtomically(
		Engine engine, String campaign, String character, Map<String, Object> changes) {
		Map<String, Object> before = engine.characters().characterSheet(campaign, character, "FULL");
		long identities = engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM want_identity"));
		long journals = engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM journal_entry"));
		RpgException denied = assertThrows(RpgException.class,
				() -> engine.characters().updateCharacter(op(), campaign, character, null, changes));
		assertEquals(ErrorCode.INVALID_ARGUMENT, denied.code());
		assertEquals(before, engine.characters().characterSheet(campaign, character, "FULL"));
		assertEquals(identities, engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM want_identity")).longValue());
		assertEquals(journals, engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM journal_entry")).longValue());
	}

	private static void assertNoEngineMetadata(Object value) {
		if (value instanceof Map<?, ?> map) {
			assertFalse(map.containsKey("_closed_journal_id"), "closure cursors are private engine bookkeeping");
			assertFalse(map.containsKey("_engine"), "engine metadata is not part of the public narrative record");
			map.values().forEach(WantReferenceTest::assertNoEngineMetadata);
		} else if (value instanceof List<?> list) {
			list.forEach(WantReferenceTest::assertNoEngineMetadata);
		}
	}

	private static void legacyBiography(Engine engine, String campaign, String character, String json) {
		engine.db().mutate(
				Database.Mutation.of("legacy_wants_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null), tx -> {
					tx.update("character", Ref.id(character, Ref.CHARACTER), map("biography_json", json));
					return Map.of();
				});
	}

	private static String rawBiography(Engine engine, String character) {
		return engine.db().read(tx -> tx.get("character", Ref.id(character, Ref.CHARACTER)).str("biography_json"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}
}
