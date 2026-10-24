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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.character.Biography;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.hirt.mcp.rpg.TestCampaigns.committedCampaign;
import static se.hirt.mcp.rpg.TestCampaigns.engine;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;
import static se.hirt.mcp.rpg.TestCampaigns.tempDb;

class BiographyValidationTest {

	@Test
	void nullBiographyListEntryReturnsInvalidArgument() throws Exception {
		try (Engine engine = engine(tempDb("biography-null-entry"))) {
			String campaign = campaign(engine);
			assertInvalid(engine, campaign, map("biography", map("timeline", Arrays.asList("valid note", null))),
					"biography.timeline[1]");
		}
	}

	@Test
	void everyBiographyListRejectsNullEntriesIncludingWants() throws Exception {
		try (Engine engine = engine(tempDb("biography-null-all-keys"))) {
			String campaign = campaign(engine);
			for (String key : Biography.BIOGRAPHY_KEYS.stream().sorted().toList()) {
				assertInvalid(engine, campaign, map("biography", map(key, Arrays.asList("valid note", null))),
						"biography." + key + "[1]");
			}
			assertEquals(0L, wantCount(engine, campaign));
		}
	}

	@Test
	void everyIntimacyListRejectsNullEntriesIncludingWants() throws Exception {
		try (Engine engine = engine(tempDb("intimacy-null-all-keys"))) {
			String campaign = campaign(engine);
			for (String key : Biography.INTIMACY_KEYS.stream().sorted().toList()) {
				assertInvalid(engine, campaign, map("intimacy", map(key, Arrays.asList("valid note", null))),
						"intimacy." + key + "[1]");
			}
			assertEquals(0L, wantCount(engine, campaign));
		}
	}

	@Test
	void nestedNullEntriesReportTheFullMapAndListPath() throws Exception {
		try (Engine engine = engine(tempDb("biography-null-nested-paths"))) {
			String campaign = campaign(engine);
			assertInvalid(engine, campaign,
					map("biography",
							map("timeline",
									List.of(map("note", "a milestone", "witnesses", Arrays.asList("Mara", null))))),
					"biography.timeline[0].witnesses[1]");
			assertInvalid(engine, campaign,
					map("biography",
							map("replace", true, "voice", map("custom", List.of(Arrays.asList("a line", null))))),
					"biography.voice.custom[0][1]");
			assertInvalid(engine, campaign,
					map("intimacy",
							map("wants",
									List.of(map("note", "a shared plan", "with", Arrays.asList("character:1", null))))),
					"intimacy.wants[0].with[1]");
			assertInvalid(engine, campaign,
					map("intimacy", map("likes", map("preferences", map("notes", Arrays.asList("kind words", null))))),
					"intimacy.likes.preferences.notes[1]");
		}
	}

	@Test
	void nullMapValuesStillRemoveFieldsWantMetadataAndWholeProfiles() throws Exception {
		try (Engine engine = engine(tempDb("biography-null-deletion"))) {
			String campaign = campaign(engine);
			engine.characters().updateCharacter(op(), campaign, "character:1", null,
					map("biography",
							map("timeline", List.of("an old milestone"), "voice",
									map("formal", List.of("Good day"), "casual", List.of("Hello")), "wants",
									List.of(map("note", "learn the old songs", "deadline", "Day 2"))),
							"intimacy", map("likes", map("gentle", List.of("kind words"), "remove", List.of("other")),
									"wants", List.of(map("note", "a shared plan", "with", List.of("character:1"))))));
			String generalWant = (String) list(biography(engine, campaign).get("wants")).getFirst().get("ref");
			String intimateWant = (String) list(intimacy(engine, campaign).get("wants")).getFirst().get("ref");

			engine.characters().updateCharacter(op(), campaign, "character:1", null, map("biography",
					map("voice", map("formal", null), "wants", List.of(map("ref", generalWant, "deadline", null))),
					"intimacy",
					map("likes", map("remove", null), "wants", List.of(map("ref", intimateWant, "with", null)))));

			assertEquals(map("casual", List.of("Hello")), biography(engine, campaign).get("voice"));
			assertEquals(map("gentle", List.of("kind words")), intimacy(engine, campaign).get("likes"));
			Map<String, Object> general = list(biography(engine, campaign).get("wants")).getFirst();
			Map<String, Object> intimate = list(intimacy(engine, campaign).get("wants")).getFirst();
			assertEquals(generalWant, general.get("ref"));
			assertEquals(intimateWant, intimate.get("ref"));
			assertEquals("learn the old songs", general.get("note"));
			assertEquals("a shared plan", intimate.get("note"));
			assertFalse(general.containsKey("deadline"));
			assertFalse(intimate.containsKey("with"));

			engine.characters().updateCharacter(op(), campaign, "character:1", null,
					map("biography", map("voice", null), "intimacy", map("likes", null)));
			assertFalse(biography(engine, campaign).containsKey("voice"));
			assertFalse(intimacy(engine, campaign).containsKey("likes"));
			assertEquals(1, list(biography(engine, campaign).get("timeline")).size());
			assertEquals(intimateWant, list(intimacy(engine, campaign).get("wants")).getFirst().get("ref"));

			engine.characters().updateCharacter(op(), campaign, "character:1", null,
					map("biography", null, "intimacy", null));
			Map<String, Object> cleared = engine.characters().characterSheet(campaign, "character:1", "FULL");
			assertFalse(cleared.containsKey("biography"));
			assertFalse(cleared.containsKey("intimacy"));
			engine.db().read(tx -> {
				assertNull(tx.get("character", 1).str("biography_json"));
				assertNull(tx.get("character", 1).str("intimacy_json"));
				return null;
			});
		}
	}

	@Test
	void invalidMixedUpdateRollsBackWantIdentityAndAllowsRetryReplayAndCheckpointRestore() throws Exception {
		try (Engine engine = engine(tempDb("biography-null-atomicity"))) {
			String campaign = campaign(engine);
			String checkpoint = (String) engine.checkpoints().create(op(), campaign, "before the narrative update")
					.get("checkpoint");
			Map<String, List<Map<String, Object>>> original = snapshot(engine, campaign);
			long originalWants = wantCount(engine, campaign);
			String operation = op();
			Map<String, Object> invalid = map("appearance", "A blue scarf.", "biography",
					map("wants", List.of("learn the old songs")), "intimacy",
					map("likes", Arrays.asList("kind words", null)));
			RpgException failure = assertThrows(RpgException.class,
					() -> engine.characters().updateCharacter(operation, campaign, "character:1", null, invalid));
			assertEquals(ErrorCode.INVALID_ARGUMENT, failure.code());
			assertEquals("intimacy.likes[1]", failure.details().get("path"));
			assertEquals(original, snapshot(engine, campaign));
			assertEquals(originalWants, wantCount(engine, campaign), "the earlier want allocation rolls back too");
			long failedEntries = engine.db()
					.read(tx -> tx.count("SELECT COUNT(*) FROM journal_entry WHERE operation_id = ?", operation));
			assertEquals(0L, failedEntries);

			Map<String, Object> corrected = map("appearance", "A blue scarf.", "biography",
					map("wants", List.of("learn the old songs")), "intimacy", map("likes", List.of("kind words")));
			engine.characters().updateCharacter(operation, campaign, "character:1", null, corrected);
			Map<String, List<Map<String, Object>>> after = snapshot(engine, campaign);
			assertEquals("A blue scarf.",
					engine.characters().characterSheet(campaign, "character:1", "FULL").get("appearance"));
			assertEquals(originalWants + 1, wantCount(engine, campaign));
			assertEquals(true, engine.characters().updateCharacter(operation, campaign, "character:1", null, corrected)
					.get("replayed"));
			assertEquals(after, snapshot(engine, campaign));
			assertEquals(originalWants + 1, wantCount(engine, campaign));

			engine.checkpoints().restore(op(), campaign, checkpoint, "undo the valid narrative update");
			assertEquals(original, snapshot(engine, campaign));
		}
	}

	@Test
	void reopenedLegacyProfilesKeepStoredNullEntriesWhileValidNewFieldsRemainEditable() throws Exception {
		Path database = tempDb("biography-null-legacy-reopen");
		String campaign;
		Map<String, Object> legacyBiography = map("voice", map("legacy", Arrays.asList(null, "an old line")));
		Map<String, Object> legacyIntimacy = map("body", map("legacy", Arrays.asList(null, "an old detail")));
		try (Engine engine = engine(database)) {
			campaign = campaign(engine);
			engine.db().mutate(
					Database.Mutation.of("legacy_biography_fixture", Ref.id(campaign, Ref.CAMPAIGN), op(), "GM", null),
					tx -> {
						tx.update("character", 1, map("biography_json", Json.write(legacyBiography), "intimacy_json",
								Json.write(legacyIntimacy)));
						return Map.of();
					});
		}
		try (Engine reopened = engine(database)) {
			reopened.sessions().bootstrap(op(), campaign, null);
			assertEquals(legacyBiography, biography(reopened, campaign));
			assertEquals(legacyIntimacy, intimacy(reopened, campaign));
			reopened.characters().updateCharacter(op(), campaign, "character:1", null, map("biography",
					map("marks", List.of("a new mark")), "intimacy", map("likes", List.of("kind words"))));
			assertEquals(legacyBiography.get("voice"), biography(reopened, campaign).get("voice"));
			assertEquals(List.of("a new mark"), biography(reopened, campaign).get("marks"));
			assertEquals(legacyIntimacy.get("body"), intimacy(reopened, campaign).get("body"));
			assertEquals(List.of("kind words"), intimacy(reopened, campaign).get("likes"));
			assertInvalid(reopened, campaign, map("intimacy", map("body", legacyIntimacy.get("body"))),
					"intimacy.body.legacy[0]");
		}
	}

	private static String campaign(Engine engine) {
		String campaign = committedCampaign(engine);
		engine.sessions().bootstrap(op(), campaign, null);
		return campaign;
	}

	private static void assertInvalid(Engine engine, String campaign, Map<String, Object> changes, String path) {
		Map<String, List<Map<String, Object>>> before = snapshot(engine, campaign);
		RpgException failure = assertThrows(RpgException.class,
				() -> engine.characters().updateCharacter(op(), campaign, "character:1", null, changes), path);
		assertEquals(ErrorCode.INVALID_ARGUMENT, failure.code(), path);
		assertEquals(path, failure.details().get("path"));
		assertEquals(before, snapshot(engine, campaign), "invalid input must leave no partial changes");
	}

	private static Map<String, Object> biography(Engine engine, String campaign) {
		return m(engine.characters().characterSheet(campaign, "character:1", "FULL").get("biography"));
	}

	private static Map<String, Object> intimacy(Engine engine, String campaign) {
		return m(m(engine.characters().characterSheet(campaign, "character:1", "FULL").get("intimacy")).get("profile"));
	}

	private static long wantCount(Engine engine, String campaign) {
		return engine.db().read(tx -> tx.count("SELECT COUNT(*) FROM want_identity WHERE campaign_id = ?",
				Ref.id(campaign, Ref.CAMPAIGN)));
	}

	private static Map<String, List<Map<String, Object>>> snapshot(Engine engine, String campaign) {
		return engine.db().snapshot(Ref.id(campaign, Ref.CAMPAIGN));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object value) {
		return (Map<String, Object>) value;
	}
}
