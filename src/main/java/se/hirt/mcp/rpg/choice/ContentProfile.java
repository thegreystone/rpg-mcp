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
 * Campaign content profiles, inspired by (not certified by) the PEGI age categories (DESIGN.md §22). The description
 * says what each profile permits so a GM can present the choice.
 */
public enum ContentProfile implements Described {

	PEGI_3("PEGI 3 — all ages",
			"Cartoon peril only: no blood, no frightening scenes, no bad language, no romance beyond friendship."),
	PEGI_7("PEGI 7",
			"Mild fantasy violence and mildly scary moments, nothing graphic; villains are defeated, not killed on-screen."),
	PEGI_12("PEGI 12",
			"Heroic fantasy: fights with real stakes but no gore, mild language, romance stays off-screen, no drugs or explicit horror."),
	PEGI_16("PEGI 16",
			"Mature fantasy: real violence and death, dark and frightening themes, strong language, intoxication, romance and non-explicit sexuality."),
	PEGI_18("PEGI 18 — adult",
			"Graphic violence, horror, adult sexuality and romance, drug use and any other mature subject matter — "
					+ "and, crucially, none of it is skipped: at this profile a scene is PLAYED OUT rather than cut away from. "
					+ "This is the rating Baldur's Gate III carries, and the profile the default fantasy epic is written for.",
			true);

	/**
	 * How a GM should actually run this profile, returned with the campaign so it is in front of the GM every
	 * session rather than only at setup (DESIGN.md §22).
	 */
	public String guidance() {
		return switch (this) {
			case PEGI_18 -> "PEGI 18 means description as realistic and as detailed as you can make it, and it "
					+ "applies to everything equally: a killing, a wound, a grief, a seduction, a night together. "
					+ "The scene is played through to its end. Cutting away at the moment it becomes interesting is "
					+ "the one thing this profile forbids - a fade to black is a failure of nerve, not good taste, "
					+ "and it is not more respectful to the characters than writing them properly.\n"
					+ "If the author will not render something literally, that is a limit on the author and not on "
					+ "the scene: use metaphor and indirection, but STAY IN THE MOMENT AND STAY PHYSICAL. Do not "
					+ "leave the room, do not skip to the morning, do not summarise in the past tense what should "
					+ "have been shown. The reader should finish knowing what it was like for these particular "
					+ "people.\n"
					+ "Most authors find it easy to render a killing in full and hard to render a night together in "
					+ "full. That asymmetry is a habit of the author, not a property of the profile, and for a mature "
					+ "audience it is exactly backwards: intimacy is the more human of the two. Hold both to the same "
					+ "standard of attention, and go as far as your own limits allow rather than stopping at the first "
					+ "sign of difficulty - a scene rendered at eighty percent, in the room and in the body, is what "
					+ "this profile asks for; a scene skipped is not.\n"
					+ "The reference point is Baldur's Gate III: rated PEGI 18, the first game to win Game of the Year "
					+ "at all five major award shows, more than twenty million copies sold. What reviewers singled "
					+ "out was not the combat: companions whose stories were the main motivation for playing, "
					+ "romances that developed naturally and were unabashedly sexual, freedom to solve problems "
					+ "creatively, and choices that mattered because almost anyone could die. That is the bar. An "
					+ "adult who chose this profile chose it for that experience, and a prudish rendering of it is "
					+ "not safer or more tasteful - it is simply a worse game. Not being prudish is what makes this "
					+ "worth playing.\n"
					+ "Consent and the characters' own agency are part of the writing, not a disclaimer wrapped "
					+ "around it; companions are people with their own wants, including the want to stop.";
			case PEGI_16 -> "Real violence and death, dark and frightening material, intoxication and desire are all "
					+ "on the page; sexuality is present and acknowledged but not depicted in detail.";
			case PEGI_12 -> "Fights have real stakes and real losses, but no gore and no lingering cruelty; romance "
					+ "is felt rather than shown.";
			case PEGI_7 -> "Peril and villainy without blood or lasting horror; opponents are beaten, not killed on "
					+ "the page.";
			case PEGI_3 -> "Nothing frightening, nothing cruel, nothing romantic beyond friendship.";
		};
	}

	private final String label;
	private final String description;
	private final boolean recommended;

	ContentProfile(String label, String description) {
		this(label, description, false);
	}

	ContentProfile(String label, String description, boolean recommended) {
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

	/** The profile with this name, or null when the name is not one (a stored value is never trusted blindly). */
	public static ContentProfile of(String name) {
		for (ContentProfile p : values()) {
			if (p.name().equals(name)) {
				return p;
			}
		}
		return null;
	}

	/** Least-specific sufficient cap for an age (MCP_PROTOCOL.md §22): only the cap is ever stored, never the age. */
	public static ContentProfile capForAge(int age) {
		if (age < 7) {
			return PEGI_3;
		}
		if (age < 12) {
			return PEGI_7;
		}
		if (age < 16) {
			return PEGI_12;
		}
		if (age < 18) {
			return PEGI_16;
		}
		return PEGI_18;
	}
}
