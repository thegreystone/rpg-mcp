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
package se.hirt.mcp.rpg.dice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An authoritative random result with its auditable breakdown (MCP_PROTOCOL.md §7.3).
 *
 * @param expression
 *            the expression as rolled
 * @param dice
 *            every kept die value, in roll order
 * @param dropped
 *            die values discarded by keep/drop rules
 * @param modifier
 *            the constant part of the expression
 * @param total
 *            kept dice + modifier
 */
public record Roll(String expression, List<Integer> dice, List<Integer> dropped, int modifier, int total) {

	public Map<String, Object> toMap() {
		var m = new LinkedHashMap<String, Object>();
		m.put("expression", expression);
		m.put("dice", dice);
		if (!dropped.isEmpty()) {
			m.put("dropped", dropped);
		}
		m.put("modifier", modifier);
		m.put("total", total);
		return m;
	}
}
