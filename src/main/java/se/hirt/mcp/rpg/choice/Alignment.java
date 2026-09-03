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
 * The nine SRD alignments with a one-line reading of each.
 */
public enum Alignment implements Described {

	LAWFUL_GOOD("Lawful Good", "Honour, duty and compassion; keeps their word even when it costs them."),
	NEUTRAL_GOOD("Neutral Good", "Does the decent thing without much regard for rules either way."),
	CHAOTIC_GOOD("Chaotic Good",
			"Compassionate, suspicious of rigid authority, breaks rules when the outcome justifies it."),
	LAWFUL_NEUTRAL("Lawful Neutral", "Order, tradition and procedure above personal feeling."),
	NEUTRAL("Neutral", "Pragmatic balance; avoids extremes and grand causes."),
	CHAOTIC_NEUTRAL("Chaotic Neutral", "Personal freedom first; unpredictable."),
	LAWFUL_EVIL("Lawful Evil", "Ambition pursued through structure, hierarchy and contracts."),
	NEUTRAL_EVIL("Neutral Evil", "Whatever serves them, with no loyalty beyond convenience."),
	CHAOTIC_EVIL("Chaotic Evil", "Cruelty and appetite without restraint.");

	private final String label;
	private final String description;
	private final boolean recommended;

	Alignment(String label, String description) {
		this(label, description, false);
	}

	Alignment(String label, String description, boolean recommended) {
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
