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
package se.hirt.mcp.rpg.harness;

/**
 * Protocol-visible harness states (DOMAIN_MODEL.md §16, EXECUTION_MODEL.md §25).
 */
public enum HarnessState {
	CAMPAIGN_SELECTION,

	SETUP_CONTENT_PROFILE,
	SETUP_EXPERIENCE,
	SETUP_RULES,
	SETUP_CONTINUATION,

	CHARACTER_CONCEPT,
	CHARACTER_RULES,
	CHARACTER_PERSONALITY,
	CHARACTER_EQUIPMENT,
	CHARACTER_REVIEW,

	PARTY_DESIGN,
	ADVENTURE_INITIALIZATION,
	CAMPAIGN_REVIEW,
	CAMPAIGN_COMMIT,
	READY_TO_PLAY,

	SESSION_BOOTSTRAP,
	EXPLORATION,
	ENCOUNTER,
	LEVEL_UP,
	CHECKPOINT_DECISION,
	PLAYER_CHARACTER_TRANSFER,
	SESSION_SUSPEND,

	CAMPAIGN_COMPLETED,
	CAMPAIGN_FAILED,
	CAMPAIGN_ABANDONED;

	public boolean isSetup() {
		return ordinal() >= SETUP_CONTENT_PROFILE.ordinal() && ordinal() <= CAMPAIGN_COMMIT.ordinal();
	}

	public boolean isTerminal() {
		return this == CAMPAIGN_COMPLETED || this == CAMPAIGN_FAILED || this == CAMPAIGN_ABANDONED;
	}

	public boolean isGameplay() {
		return ordinal() >= READY_TO_PLAY.ordinal() && ordinal() <= SESSION_SUSPEND.ordinal();
	}
}
