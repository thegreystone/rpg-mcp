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

import java.util.Map;

/**
 * The flavour of fantasy a campaign is played in (DESIGN.md §4.2). Offered as suggestions — the player's own words are
 * equally valid — but with a definite default: {@link #EPIC}, a Baldur's Gate-style fantasy epic, which is what the
 * campaign becomes when the player skips the question or says "surprise me".
 */
public enum FantasyStyle implements Described {

	EPIC("Fantasy epic (the default)",
			"A Baldur's Gate-style fantasy epic: a world-threatening plot, a party of vivid companions with their own "
					+ "pasts, secrets and agendas, romance that develops naturally, hard moral choices with lasting "
					+ "consequences, and a world where almost anyone can die. Written for PEGI 18 (the rating "
					+ "Baldur's Gate III carries); lower profiles play the same epic with the mature material scaled "
					+ "down.",
			true),
	CLASSIC_HEROIC("Classic heroic", "Dungeons, wilderness and a serious plot with room for humour."),
	GRIM("Grim and gritty", "Low magic, moral compromise, scarce resources."),
	INTRIGUE("Intrigue and mystery", "Courts, secrets and investigation over combat."),
	LIGHTHEARTED("Lighthearted romp", "Jokes first, stakes second.");

	/**
	 * How a GM should actually run this style, returned with the campaign so it is in front of the GM every session
	 * rather than only at setup (DESIGN.md §4.2) — the same arrangement as {@link ContentProfile#guidance()}.
	 */
	public String guidance() {
		return switch (this) {
			case EPIC -> "Run a fantasy epic in the spirit of Baldur's Gate III. The plot threatens the world and keeps "
					+ "escalating; set pieces are big and spectacular. The companions are the heart of it: each has a "
					+ "past, a secret, an agenda and opinions about what the player does, and they react — approve, "
					+ "object, fall out, fall in love. Relationships develop naturally out of shared events, never "
					+ "from a menu, and where the content profile allows it, romance and sex are part of the story "
					+ "and are written like everything else. The player is free to solve problems creatively and to "
					+ "make hard moral choices; consequences stick, and almost anyone can die. Play it to the hilt.";
			case CLASSIC_HEROIC -> "Dungeons, wilderness and a serious plot with room for humour; the heroes are "
					+ "heroes and the stakes are honest.";
			case GRIM -> "Low magic and scarce resources; every victory costs something and moral compromise is the "
					+ "norm, not the exception.";
			case INTRIGUE -> "Courts, secrets and investigation; talk and inference resolve more than swords do, and "
					+ "every faction wants something.";
			case LIGHTHEARTED -> "Jokes first, stakes second; peril is real enough to matter but never so heavy it "
					+ "kills the mood.";
		};
	}

	/** The style recorded in an experience section, or null when it is free text or absent. */
	public static FantasyStyle of(Object experience) {
		if (!(experience instanceof Map<?, ?> m) || !(m.get("fantasy_style") instanceof String s)) {
			return null;
		}
		for (FantasyStyle style : values()) {
			if (style.name().equals(s)) {
				return style;
			}
		}
		return null;
	}

	private final String label;
	private final String description;
	private final boolean recommended;

	FantasyStyle(String label, String description) {
		this(label, description, false);
	}

	FantasyStyle(String label, String description, boolean recommended) {
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
