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
package se.hirt.mcp.rpg.choice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One outstanding decision, rendered for a GM to put to the player: the question, the tool and
 * field that record the answer, every legal option with its description, and whether a free-text or
 * SURPRISE_ME answer is acceptable. The server lists decisions in interview order; the GM asks the
 * first one only, shows all of its options, records the answer and reads the list again
 * (MCP_PROTOCOL.md §9.3.1).
 */
public final class Decision {

	/**
	 * What the {@code options} list is: the closed set of legal values, mere suggestions, or
	 * nothing.
	 */
	public enum OptionsKind {
		LEGAL_VALUES, SUGGESTIONS, NONE
	}

	private final String id;
	private final String question;
	private String tool;
	private String path;
	private int min = 1;
	private int max = 1;
	private List<Option> options = List.of();
	private OptionsKind kind = OptionsKind.NONE;
	private boolean allowCustom;
	private boolean allowSurpriseMe;
	private boolean optional;
	private String defaultValue;
	private String owner = "PLAYER";
	private String note;
	private final Map<String, Object> details = new LinkedHashMap<>();

	private Decision(String id, String question) {
		this.id = id;
		this.question = question;
	}

	public static Decision of(String id, String question) {
		return new Decision(id, question);
	}

	public String id() {
		return id;
	}

	/** The tool and the field (a dotted path into its arguments) that record the answer. */
	public Decision recordedBy(String tool, String path) {
		this.tool = tool;
		this.path = path;
		return this;
	}

	/** The options are the complete set of legal values; anything else is rejected. */
	public Decision legal(List<Option> legal) {
		this.options = List.copyOf(legal);
		this.kind = OptionsKind.LEGAL_VALUES;
		return this;
	}

	/** The options are suggestions to make the question concrete; free text is equally valid. */
	public Decision suggestions(List<Option> suggested) {
		this.options = List.copyOf(suggested);
		this.kind = OptionsKind.SUGGESTIONS;
		this.allowCustom = true;
		return this;
	}

	/** How many options must be picked (default exactly one). */
	public Decision choose(int min, int max) {
		this.min = min;
		this.max = max;
		return this;
	}

	public Decision custom() {
		this.allowCustom = true;
		return this;
	}

	public Decision surpriseMe() {
		this.allowSurpriseMe = true;
		return this;
	}

	/** May be skipped; the default (if any) applies. The GM should still ask. */
	public Decision optional(String defaultValue) {
		this.optional = true;
		this.defaultValue = defaultValue;
		return this;
	}

	/** Authored by the GM rather than asked of the player. */
	public Decision gmAuthored() {
		this.owner = "GM";
		return this;
	}

	public Decision note(String note) {
		this.note = note;
		return this;
	}

	public Decision detail(String key, Object value) {
		if (value != null) {
			details.put(key, value);
		}
		return this;
	}

	public Map<String, Object> toMap() {
		var m = new LinkedHashMap<String, Object>();
		m.put("id", id);
		m.put("owner", owner);
		m.put("question", question);
		m.put("tool", tool);
		m.put("path", path);
		if (kind != OptionsKind.NONE) {
			m.put("choose", Map.of("min", min, "max", max));
			m.put("options_are", kind.name());
			var list = new ArrayList<Map<String, Object>>();
			String recommended = null;
			for (Option o : options) {
				list.add(o.toMap());
				if (o.recommended() && recommended == null) {
					recommended = o.value();
				}
			}
			m.put("options", list);
			if (recommended != null) {
				m.put("recommended", recommended);
			}
		}
		m.put("allow_custom", allowCustom);
		m.put("allow_surprise_me", allowSurpriseMe);
		if (optional) {
			m.put("optional", true);
			if (defaultValue != null) {
				m.put("default", defaultValue);
			}
		}
		if (note != null) {
			m.put("note", note);
		}
		m.putAll(details);
		return m;
	}

	public static List<Map<String, Object>> render(List<Decision> decisions) {
		return decisions.stream().map(Decision::toMap).toList();
	}
}
