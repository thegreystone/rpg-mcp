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
package se.hirt.mcp.rpg.character;

import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves public want references against the character's current, policy-visible state. */
public final class WantLookup {

	private WantLookup() {
	}

	public static Map<String, Object> get(Tx tx, long campaignId, String reference) {
		String ref = Ref.parse(reference, Ref.WANT).toString();
		long id = Ref.id(ref, Ref.WANT);
		Row identity = tx.find("want_identity", id).filter(r -> r.lng("campaign_id") == campaignId)
				.orElseThrow(() -> RpgException.notFound(reference));
		boolean intimate = "intimate".equals(identity.str("kind"));
		if (intimate && !Biography.intimacyAllowed(tx, campaignId)) {
			throw RpgException.policyDenied("Intimate wants are available only under the PEGI_18 content profile.");
		}
		Row character = tx.find("character", identity.lng("character_id"))
				.filter(c -> c.lng("campaign_id") == campaignId).orElseThrow(() -> RpgException.notFound(reference));
		// Registry entries survive restores and removals; only a currently stored want resolves.
		return Biography.wants(tx, character, intimate).stream().filter(w -> ref.equals(w.get("ref")))
				.map(w -> owned(w, character, intimate)).findFirst()
				.orElseThrow(() -> RpgException.notFound(reference));
	}

	public static List<Map<String, Object>> forCharacter(Tx tx, Row character, boolean includeIntimate) {
		var out = new ArrayList<Map<String, Object>>();
		for (Map<String, Object> want : Biography.wants(tx, character, false)) {
			out.add(owned(want, character, false));
		}
		if (includeIntimate) {
			for (Map<String, Object> want : Biography.wants(tx, character, true)) {
				out.add(owned(want, character, true));
			}
		}
		return out;
	}

	private static Map<String, Object> owned(Map<String, Object> want, Row character, boolean intimate) {
		var out = new LinkedHashMap<String, Object>(want);
		out.put("character", Ref.of(Ref.CHARACTER, character.id()));
		out.put("kind", intimate ? "intimate" : "general");
		return out;
	}
}
