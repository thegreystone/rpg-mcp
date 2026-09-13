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
 * How experience earned by the party is distributed (campaign house-rule setting; RULES_ENGINE.md
 * §6). Experience is always earned by the party as a whole; this decides who it lands on.
 */
public enum XpPolicy implements Described {

	SHARED("Shared", "Every award is divided evenly among the active party, as the SRD and the classic party RPGs do; a larger party advances more slowly, but everyone advances together.", true),
	LOCKSTEP("Lockstep", "Player characters receive the full award and companions are kept at the same experience total automatically; party size never changes the player's pace."),
	PLAYER_ONLY("Player only", "Only player characters earn experience; companions stay exactly as they were recruited and never level.");

	private final String label;
	private final String description;
	private final boolean recommended;

	XpPolicy(String label, String description) {
		this(label, description, false);
	}

	XpPolicy(String label, String description, boolean recommended) {
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
