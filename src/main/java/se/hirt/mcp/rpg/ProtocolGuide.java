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

import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the modular Game Master guide from classpath resources under {@code protocol/}. Clients
 * load {@code CORE} once at session start and pull specialized topic manuals on demand.
 */
final class ProtocolGuide {

	static final List<String> TOPICS = List.of("CORE", "SETUP", "COMBAT", "MAPS", "CHARACTERS", "DIRECTOR", "ECONOMY",
			"ALL");

	private static final Map<String, String> TOPIC_FILES = new LinkedHashMap<>();

	static {
		TOPIC_FILES.put("CORE", "protocol/guide.md");
		TOPIC_FILES.put("SETUP", "protocol/guide-setup.md");
		TOPIC_FILES.put("COMBAT", "protocol/guide-combat.md");
		TOPIC_FILES.put("MAPS", "protocol/guide-maps.md");
		TOPIC_FILES.put("CHARACTERS", "protocol/guide-characters.md");
		TOPIC_FILES.put("DIRECTOR", "protocol/guide-director.md");
		TOPIC_FILES.put("ECONOMY", "protocol/guide-economy.md");
	}

	private ProtocolGuide() {
	}

	static String resolveTopic(String raw) {
		if (raw == null || raw.isBlank()) {
			return "CORE";
		}
		String topic = raw.trim().toUpperCase(Locale.ROOT);
		if (!TOPICS.contains(topic)) {
			throw RpgException.invalidArgument("Unknown guide topic '" + raw + "'. Legal topics: " + TOPICS)
					.withDetail("legal_topics", TOPICS);
		}
		return topic;
	}

	static String read(String rawTopic) {
		String topic = resolveTopic(rawTopic);
		if ("ALL".equals(topic)) {
			var sb = new StringBuilder();
			boolean first = true;
			for (String path : TOPIC_FILES.values()) {
				if (!first) {
					sb.append("\n\n---\n\n");
				}
				sb.append(RpgResources.classpath(path).stripTrailing());
				first = false;
			}
			sb.append('\n');
			return sb.toString();
		}
		return RpgResources.classpath(TOPIC_FILES.get(topic));
	}
}
