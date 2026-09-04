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

import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Forward-only, numbered SQL migrations embedded as classpath resources ({@code db/migration/Vnnn__name.sql}), recorded
 * in {@code schema_version} (DATABASE.md §7). Deliberately tiny: no down-migrations, checksums verified so an edited
 * already-applied migration fails loudly.
 */
final class Migrations {

	/** Add new migrations here, in order. Never edit an applied one. */
	static final List<String> MIGRATIONS = List.of("V001__baseline.sql", "V002__encounters.sql", "V003__world.sql",
			"V004__reactions.sql", "V005__origins.sql", "V006__armor_class_override.sql");

	private static final Pattern NAME = Pattern.compile("^V(\\d+)__(.+)\\.sql$");

	private Migrations() {
	}

	static void apply(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			st.execute("""
			           CREATE TABLE IF NOT EXISTS schema_version (
			               id          INTEGER PRIMARY KEY AUTOINCREMENT,
			               version     INTEGER NOT NULL UNIQUE,
			               description TEXT NOT NULL,
			               checksum    TEXT NOT NULL,
			               applied_at  TEXT NOT NULL
			           )""");
		}
		for (String file : MIGRATIONS) {
			var m = NAME.matcher(file);
			if (!m.matches()) {
				throw new IllegalStateException("Bad migration file name: " + file);
			}
			int version = Integer.parseInt(m.group(1));
			String description = m.group(2);
			String sql = load(file);
			String checksum = checksum(sql);

			try (PreparedStatement ps = c.prepareStatement("SELECT checksum FROM schema_version WHERE version = ?")) {
				ps.setInt(1, version);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						if (!checksum.equals(rs.getString(1))) {
							throw new IllegalStateException("Migration " + file + " was modified after being applied.");
						}
						continue;
					}
				}
			}
			try (Statement st = c.createStatement()) {
				for (String statement : splitStatements(sql)) {
					st.execute(statement);
				}
			}
			try (PreparedStatement ps = c.prepareStatement(
					"INSERT INTO schema_version(version, description, checksum, applied_at) VALUES (?,?,?,?)")) {
				ps.setInt(1, version);
				ps.setString(2, description);
				ps.setString(3, checksum);
				ps.setString(4, Instant.now().toString());
				ps.executeUpdate();
			}
			c.commit();
		}
	}

	private static String load(String file) {
		String path = "db/migration/" + file;
		try (InputStream in = Migrations.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("Missing migration resource " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw RpgException.internal("Cannot read migration " + path, e);
		}
	}

	/**
	 * The fingerprint of an applied migration: the statements it actually executes, with comments stripped, runs of
	 * whitespace collapsed to one space, and spaces around {@code ( ) , ;} removed. Reformatting a migration file,
	 * rewrapping it, retyping a comment or checking it out with different line endings therefore leaves the checksum
	 * alone, while any real change to the DDL still trips the guard. Hashing the raw bytes instead did the opposite:
	 * running a SQL formatter over the migrations locked an existing campaign database out of its own schema
	 * (DATABASE.md §2).
	 * <p>
	 * The one thing this cannot see is whitespace inside a string literal that sits next to one of those characters.
	 * No shipped migration contains such a literal; a future one that does must not rely on it.
	 */
	static String checksum(String sql) {
		String canonical = String.join(";", splitStatements(sql)).replaceAll("\\s+", " ")
				.replaceAll("\\s*([(),;])\\s*", "$1");
		return Json.hashBytes(Json.utf8(canonical));
	}

	/** Splits on statement-terminating semicolons; comments are stripped first. Sufficient for our DDL. */
	static List<String> splitStatements(String sql) {
		String noComments = sql.lines().map(line -> {
			int i = line.indexOf("--");
			return i >= 0 ? line.substring(0, i) : line;
		}).reduce(new StringBuilder(), (sb, l) -> sb.append(l).append('\n'), StringBuilder::append).toString();
		return java.util.Arrays.stream(noComments.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}
}
