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
 * Who makes a companion's level-up choices (campaign house-rule setting; RULES_ENGINE.md §6). A
 * companion who has no class yet always needs a class chosen for them: the engine never invents
 * one.
 */
public enum CompanionLevelUp implements Described {

	PLAYER("You level companions", "A companion who reaches a new level is flagged together with the choices the engine would make for them, so you can accept the proposal as it stands or change any part of it before it is committed.", true),
	ENGINE("The engine levels companions", "When a companion earns enough experience the server levels them immediately and silently: hit points by the campaign's hp_progression, ability improvements into the class's primary ability.");

	private final String label;
	private final String description;
	private final boolean recommended;

	CompanionLevelUp(String label, String description) {
		this(label, description, false);
	}

	CompanionLevelUp(String label, String description, boolean recommended) {
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
