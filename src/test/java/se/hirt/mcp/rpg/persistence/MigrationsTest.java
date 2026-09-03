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
package se.hirt.mcp.rpg.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The applied-migration guard must catch a changed schema and ignore a changed file. Hashing raw bytes did the
 * opposite: reformatting the migration SQL on 2026-09-02 locked an existing campaign database out of its own schema
 * with "V001__baseline.sql was modified after being applied", even though every one of its 58 schema objects was
 * byte-identical once whitespace was ignored (DATABASE.md §2, RULES_ENGINE.md §6).
 */
class MigrationsTest {

	private static final String ORIGINAL = """
	                                       -- Baseline.
	                                       CREATE TABLE campaign(
	                                           id INTEGER PRIMARY KEY AUTOINCREMENT,
	                                           title TEXT NOT NULL
	                                       );
	                                       CREATE INDEX idx_campaign_title ON campaign(title);
	                                       """;

	@Test
	void reformattingAMigrationDoesNotChangeItsChecksum() {
		// What a SQL formatter does: spaces before parentheses, different indentation, rewrapped lines.
		String reformatted = """
		                     -- Baseline.
		                     CREATE TABLE campaign (
		                       id    INTEGER PRIMARY KEY AUTOINCREMENT,
		                       title TEXT    NOT NULL
		                     );

		                     CREATE INDEX idx_campaign_title
		                         ON campaign (title);
		                     """;
		assertEquals(Migrations.checksum(ORIGINAL), Migrations.checksum(reformatted));
	}

	@Test
	void rewritingACommentDoesNotChangeItsChecksum() {
		assertEquals(Migrations.checksum(ORIGINAL),
				Migrations.checksum(ORIGINAL.replace("-- Baseline.", "-- Baseline (see docs/DATABASE.md).")));
	}

	@Test
	void checkingOutWithWindowsLineEndingsDoesNotChangeItsChecksum() {
		String crlf = ORIGINAL.replace("\n", "" + (char) 13 + (char) 10);
		assertEquals(Migrations.checksum(ORIGINAL), Migrations.checksum(crlf));
	}

	@Test
	void changingTheSchemaDoesChangeItsChecksum() {
		assertNotEquals(Migrations.checksum(ORIGINAL),
				Migrations.checksum(ORIGINAL.replace("title TEXT NOT NULL", "title TEXT")));
		assertNotEquals(Migrations.checksum(ORIGINAL),
				Migrations.checksum(ORIGINAL.replace("CREATE INDEX", "CREATE UNIQUE INDEX")));
	}

	/** Prints the fingerprint of every shipped migration, so an existing database can be repaired deliberately. */
	@Test
	void shippedMigrationsHaveStableFingerprints() {
		for (String file : Migrations.MIGRATIONS) {
			String sum = Migrations.checksum(load(file));
			System.out.println("MIGRATION " + file + " " + sum);
			assertEquals(64, sum.length(), file);
		}
	}

	private static String load(String file) {
		try (var in = Migrations.class.getClassLoader().getResourceAsStream("db/migration/" + file)) {
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException(file, e);
		}
	}
}
