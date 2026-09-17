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
import se.hirt.mcp.rpg.character.DeepMerge;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * A profile MERGE never loses what a campaign has already written down: a nested list or map given
 * in a later update joins the stored one at every depth instead of replacing it. Found in play when
 * a relationship update carrying {preferences: {likes: [one new like]}} wiped the likes mapped over
 * a hundred days, and a {terms: {a map}} wiped twelve standing terms.
 */
class ProfileMergeTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object o) {
		return (List<Object>) o;
	}

	@Test
	void aRelationshipProfileMergeKeepsEveryNestedListAndTerm() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("profile-merge"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String pc = "character:1";
			String ysolde = (String) engine.runtime()
					.materialize(op(), campaign, "Commoner", "Ysolde", "the steward", "exact", null, null, false)
					.get("character");
			engine.party().updateMembership(op(), campaign, ysolde, "JOIN", null);

			engine.party().updateRelationship(op(), campaign, ysolde, pc, map("trust", 5), "Of the House.", null,
					"asked in", false, null,
					map("terms", List.of("Not a ring, not a wife.", "Held some nights by whoever she asks."),
							"preferences",
							map("likes", List.of("holding him while the others do"), "limits",
									List.of("nothing with her own body yet")),
							"notes", List.of("says frightened instead of counting sheep")),
					null);

			// One new like, a term given as a map, a note as a bare string: everything stored survives.
			Map<String, Object> second = engine.party().updateRelationship(op(), campaign, ysolde, pc, null, null, null,
					"the foot night", false, null,
					map("preferences", map("likes", List.of("her feet rubbed, harder")), "terms",
							map("daylight_touch", "A foot in daylight is not that."), "notes",
							"rubs feet badly and frowns at it"),
					"MERGE");
			Map<String, Object> profile = m(list(second.get("relationships")).get(0));
			profile = m(profile.get("profile"));
			assertEquals(List.of("holding him while the others do", "her feet rubbed, harder"),
					m(profile.get("preferences")).get("likes"), "the stored likes are kept and the new one appended");
			assertEquals(List.of("nothing with her own body yet"), m(profile.get("preferences")).get("limits"),
					"a sibling list the update did not mention is untouched");
			assertEquals(
					List.of("Not a ring, not a wife.", "Held some nights by whoever she asks.",
							"A foot in daylight is not that."),
					profile.get("terms"), "a map given for a stored list contributes its values");
			assertEquals(List.of("says frightened instead of counting sheep", "rubs feet badly and frowns at it"),
					profile.get("notes"), "a bare value given for a stored list is appended");

			// A null inside a nested map removes just that key; a duplicate like is not added twice.
			Map<String, Object> third = engine.party().updateRelationship(op(), campaign, ysolde, pc, null, null, null,
					"a limit lifted", false, null, map("preferences", map("limits", null, "likes",
							List.of("her feet rubbed, harder"), "dislikes", List.of("being rushed"))),
					"MERGE");
			profile = m(m(list(third.get("relationships")).get(0)).get("profile"));
			assertNull(m(profile.get("preferences")).get("limits"));
			assertEquals(2, list(m(profile.get("preferences")).get("likes")).size());
			assertEquals(List.of("being rushed"), m(profile.get("preferences")).get("dislikes"));
			assertEquals(3, list(profile.get("terms")).size(), "terms untouched by a preferences-only update");

			// A plain value where a map is stored is refused rather than flattening the map.
			RpgException flat = assertThrows(RpgException.class,
					() -> engine.party().updateRelationship(op(), campaign, ysolde, pc, null, null, null, "a slip",
							false, null, map("preferences", "likes her feet rubbed"), "MERGE"));
			assertEquals(ErrorCode.INVALID_ARGUMENT, flat.code());
			assertTrue(flat.getMessage().contains("preferences"), flat.getMessage());

			// REPLACE is still the only way to start over.
			Map<String, Object> replaced = engine.party().updateRelationship(op(), campaign, ysolde, pc, null, null,
					null, "starting over", false, null, map("wants", List.of("to be needed for what she knows")),
					"REPLACE");
			profile = m(m(list(replaced.get("relationships")).get(0)).get("profile"));
			assertNull(profile.get("preferences"));
			assertNull(profile.get("terms"));
		}
	}

	@Test
	void anIntimateProfileMergesItsNestedMapsToo() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("intimacy-merge"))) {
			String campaign = TestCampaigns.committedCampaign(engine);
			engine.sessions().bootstrap(op(), campaign, null);
			String ilsa = (String) engine.runtime()
					.materialize(op(), campaign, "Guard", "Ilsa", "the wick", "dry", null, null, false)
					.get("character");
			engine.party().updateMembership(op(), campaign, ilsa, "JOIN", null);

			engine.characters().updateCharacter(op(), campaign, ilsa, null,
					map("intimacy", map("body", map("marks", List.of("the wrists"), "hands", "always warm"), "likes",
							List.of("being asked out loud"))));
			engine.characters().updateCharacter(op(), campaign, ilsa, null, map("intimacy",
					map("body", map("marks", List.of("a burn on the palm")), "likes", List.of("her warm hands used"))));
			Map<String, Object> intimacy = m(
					m(engine.characters().characterSheet(campaign, ilsa, "FULL").get("intimacy")).get("profile"));
			assertEquals(List.of("the wrists", "a burn on the palm"), m(intimacy.get("body")).get("marks"),
					"a nested list inside a map appends");
			assertEquals("always warm", m(intimacy.get("body")).get("hands"), "a sibling key inside the map survives");
			assertEquals(List.of("being asked out loud", "her warm hands used"), intimacy.get("likes"));

			// A single entry given for a dated list is that list's one new entry, stamped.
			engine.characters().updateCharacter(op(), campaign, ilsa, null,
					map("biography", map("state", map("note", "carrying a child"))));
			engine.characters().updateCharacter(op(), campaign, ilsa, null,
					map("biography", map("state", map("note", "an arm in a sling"))));
			Map<String, Object> biography = m(
					engine.characters().characterSheet(campaign, ilsa, "FULL").get("biography"));
			assertEquals(2, list(biography.get("state")).size());
			assertTrue(m(list(biography.get("state")).get(0)).containsKey("since"));
		}
	}

	@Test
	void theRuleItselfAtEveryDepth() {
		UnaryOperator<Object> id = UnaryOperator.identity();
		assertEquals(List.of("a", "b"), DeepMerge.merge(List.of("a"), List.of("a", "b"), "x", id, DeepMerge.NO_KEY));
		assertEquals(List.of("a", "b"), DeepMerge.merge(List.of("a"), "b", "x", id, DeepMerge.NO_KEY));
		assertEquals(List.of("a", "b", "c"),
				DeepMerge.merge(List.of("a"), map("one", "b", "two", List.of("c")), "x", id, DeepMerge.NO_KEY));
		assertEquals(map("k", List.of("a", "b"), "j", "kept"), DeepMerge.merge(map("k", List.of("a"), "j", "kept"),
				map("k", List.of("b")), "x", id, DeepMerge.NO_KEY));
		assertEquals(map("j", "kept"),
				DeepMerge.merge(map("k", List.of("a"), "j", "kept"), map("k", null), "x", id, DeepMerge.NO_KEY));
		assertEquals(map("k", "v"), DeepMerge.merge("plain", map("k", "v"), "x", id, DeepMerge.NO_KEY));
		assertEquals("new", DeepMerge.merge("old", "new", "x", id, DeepMerge.NO_KEY));
		assertNull(DeepMerge.merge(map("k", "v"), null, "x", id, DeepMerge.NO_KEY));
		assertThrows(RpgException.class, () -> DeepMerge.merge(map("k", "v"), "flat", "x", id, DeepMerge.NO_KEY));
		// Keyed items: a new entry with the same note replaces the old one, at any depth.
		var noteOf = (java.util.function.Function<Object, String>) o -> o instanceof Map<?, ?> mm
				&& mm.get("note") != null ? mm.get("note").toString() : null;
		Object merged = DeepMerge.merge(map("wants", List.of(map("note", "a", "status", "OPEN"))),
				map("wants", List.of(map("note", "a", "status", "DONE"))), "x", id, noteOf);
		assertEquals("DONE", m(list(m(merged).get("wants")).get(0)).get("status"));
	}
}
