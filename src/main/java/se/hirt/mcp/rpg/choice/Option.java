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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One presentable alternative of a {@link Decision}: the value to send back, a label, a description and optional
 * structured details (speed, level, cost …) the GM may quote.
 */
public record Option(String value, String label, String description, boolean recommended, Map<String, Object> details) {

	public static Option of(Described d) {
		return new Option(d.name(), d.label(), d.description(), d.recommended(), Map.of());
	}

	public static Option of(String value, String label, String description) {
		return new Option(value, label, description, false, Map.of());
	}

	public Option recommended(boolean flag) {
		return new Option(value, label, description, flag, details);
	}

	public Option details(Map<String, Object> more) {
		var merged = new LinkedHashMap<>(details);
		merged.putAll(more);
		return new Option(value, label, description, recommended, merged);
	}

	public Map<String, Object> toMap() {
		var m = new LinkedHashMap<String, Object>();
		m.put("value", value);
		m.put("label", label);
		m.put("description", description);
		if (recommended) {
			m.put("recommended", true);
		}
		for (var e : details.entrySet()) {
			if (e.getValue() != null) {
				m.putIfAbsent(e.getKey(), e.getValue());
			}
		}
		return m;
	}
}
