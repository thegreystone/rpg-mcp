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
package se.hirt.mcp.rpg.protocol;

import java.util.regex.Pattern;

/**
 * Typed reference at the protocol boundary: {@code <entity-type>:<positive-integer>} (MCP_PROTOCOL.md §5.1). Internally
 * the database uses table-scoped numeric primary keys; this record is only the rendering.
 */
public record Ref(String type, long id) {

	private static final Pattern PATTERN = Pattern.compile("^([a-z_]+):([1-9][0-9]{0,17})$");

	public static final String CAMPAIGN = "campaign";
	public static final String CHARACTER = "character";
	public static final String LOCATION = "location";
	public static final String EVENT = "event";
	public static final String ENCOUNTER = "encounter";
	public static final String CHECKPOINT = "checkpoint";
	public static final String CONTENT = "content";
	public static final String SESSION = "session";
	public static final String DRAFT = "draft";
	public static final String SEED = "seed";
	public static final String TRANSACTION = "transaction";
	public static final String ROLL = "roll";
	public static final String STORY_BEAT = "story_beat";
	public static final String INVENTORY = "inventory";

	public static Ref parse(String text) {
		if (text == null || text.isBlank()) {
			throw RpgException.invalidArgument("A typed reference is required (for example 'campaign:1').");
		}
		var m = PATTERN.matcher(text.trim());
		if (!m.matches()) {
			throw RpgException.invalidArgument(
					"Malformed typed reference '" + text + "'; expected '<type>:<positive-integer>'.");
		}
		return new Ref(m.group(1), Long.parseLong(m.group(2)));
	}

	public static Ref parse(String text, String expectedType) {
		Ref ref = parse(text);
		if (!ref.type.equals(expectedType)) {
			throw RpgException.invalidArgument("Expected a '" + expectedType + ":' reference but got '" + text + "'.");
		}
		return ref;
	}

	public static long id(String text, String expectedType) {
		return parse(text, expectedType).id;
	}

	public static String of(String type, long id) {
		return type + ":" + id;
	}

	public static String ofNullable(String type, Long id) {
		return id == null ? null : of(type, id);
	}

	@Override
	public String toString() {
		return of(type, id);
	}
}
