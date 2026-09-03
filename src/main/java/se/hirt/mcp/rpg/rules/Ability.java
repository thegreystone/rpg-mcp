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
package se.hirt.mcp.rpg.rules;

import se.hirt.mcp.rpg.protocol.RpgException;

/**
 * The six abilities (SRD 5.2.1 "The Six Abilities").
 */
public enum Ability {
	STR("Strength", "str_score"),
	DEX("Dexterity", "dex_score"),
	CON("Constitution", "con_score"),
	INT("Intelligence", "int_score"),
	WIS("Wisdom", "wis_score"),
	CHA("Charisma", "cha_score");

	private final String fullName;
	private final String column;

	Ability(String fullName, String column) {
		this.fullName = fullName;
		this.column = column;
	}

	public String fullName() {
		return fullName;
	}

	/** The character table column holding the base score. */
	public String column() {
		return column;
	}

	/** Accepts {@code STR}, {@code strength}, {@code Strength}, … */
	public static Ability parse(String text) {
		if (text == null || text.isBlank()) {
			throw RpgException.invalidArgument("An ability is required (STR, DEX, CON, INT, WIS or CHA).");
		}
		String t = text.trim().toUpperCase();
		for (Ability a : values()) {
			if (a.name().equals(t) || a.fullName.toUpperCase().equals(t)) {
				return a;
			}
		}
		throw RpgException.invalidArgument("Unknown ability '" + text + "'.");
	}
}
