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

/**
 * What happens after a player-character death or a regretted decision (DESIGN.md §21).
 */
public enum ContinuationPolicy implements Described {

	CHECKPOINT("Checkpoint", "Return to the last major checkpoint after a death — or to undo a decision the player regrets.", true),
	ENCOUNTER_RETRY("Encounter retry", "Retry an encounter that went badly; nothing outside the encounter can be undone."),
	IRONMAN("Ironman", "No rollback. If the player character dies and a companion survives, the player may take over the companion; if everyone dies, the campaign ends.");

	private final String label;
	private final String description;
	private final boolean recommended;

	ContinuationPolicy(String label, String description) {
		this(label, description, false);
	}

	ContinuationPolicy(String label, String description, boolean recommended) {
		this.label = label;
		this.description = description;
		this.recommended = recommended;
	}

	@Override
	public String label() {
		return label;
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public boolean recommended() {
		return recommended;
	}
}
