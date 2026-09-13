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

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An already-installed ruleset version is brought up to date with the embedded seed on the next
 * start (a database created before a definition gained its {@code summary} must not keep the stale
 * payload), while an unchanged seed touches nothing.
 */
class SeedRefreshTest {

	@Test
	void staleInstalledContentIsRefreshedOnStartup() throws Exception {
		Path db = TestCampaigns.tempDb("refresh");
		try (Engine first = TestCampaigns.engine(db)) {
			assertTrue(first.rules().find("srd5e:species/human").orElseThrow().payload().containsKey("summary"));
		}
		// Simulate a database seeded by an older build: strip the summary and rename an entry.
		try (Database raw = new Database(db)) {
			raw.mutate(Database.Mutation.of("test_tamper", null, null, "ADMINISTRATIVE_OVERRIDE", null), tx -> {
				tx.rawExecute("UPDATE installed_content SET payload_json = ?, name = ? WHERE content_id = ?",
						"{\"size\":\"Medium\",\"speed\":30,\"creature_type\":\"Humanoid\"}", "Hooman",
						"srd5e:species/human");
				tx.rawExecute("DELETE FROM installed_content WHERE content_id = ?", "srd5e:skill/stealth");
				return Map.of();
			});
		}
		try (Engine second = TestCampaigns.engine(db)) {
			var human = second.rules().find("srd5e:species/human").orElseThrow();
			assertEquals("Human", human.name());
			assertTrue(human.payload().get("summary") instanceof String s && !s.isBlank(), human.payload().toString());
			assertTrue(second.rules().find("srd5e:skill/stealth").isPresent(), "a missing entry is re-imported");
			assertEquals(1, second.rules().rulesets().size(), "no second ruleset row");
		}
	}
}
