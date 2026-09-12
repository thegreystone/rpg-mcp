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
package se.hirt.mcp.rpg;

import se.hirt.mcp.rpg.campaign.CampaignService;
import se.hirt.mcp.rpg.campaign.ServerSession;
import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.checkpoint.CheckpointService;
import se.hirt.mcp.rpg.content.ContentService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.content.SeedImporter;
import se.hirt.mcp.rpg.dice.RandomRollService;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.encounter.EncounterService;
import se.hirt.mcp.rpg.inventory.InventoryService;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.magic.SpellService;
import se.hirt.mcp.rpg.narrative.NarrativeService;
import se.hirt.mcp.rpg.party.PartyService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.progression.LevelUpService;
import se.hirt.mcp.rpg.rules.CheckService;
import se.hirt.mcp.rpg.rules.RestService;
import se.hirt.mcp.rpg.session.SessionService;
import se.hirt.mcp.rpg.world.WorldService;

import java.nio.file.Path;

/**
 * Wires the engine together over one database. Constructed once per process by CDI, or directly by tests (with a
 * scripted roller) without any container.
 */
public final class Engine implements AutoCloseable {

	private final Database db;
	private final RulesData rules;
	private final RollService roller;
	private final ServerSession session = new ServerSession();
	private final CampaignService campaigns;
	private final CharacterService characters;
	private final SessionService sessions;
	private final LedgerService ledger;
	private final CheckpointService checkpoints;
	private final CheckService checks;
	private final ContentService content;
	private final InventoryService inventory;
	private final RuntimeService runtime;
	private final EncounterService encounters;
	private final LevelUpService levelUps;
	private final PartyService party;
	private final WorldService world;
	private final NarrativeService narrative;
	private final RestService rest;
	private final SpellService spells;
	private final se.hirt.mcp.rpg.economy.AccountService accounts;
	private final se.hirt.mcp.rpg.economy.CashFlowService cashFlows;
	private final se.hirt.mcp.rpg.session.ChronicleService chronicle;

	public Engine(Path databaseFile, RollService roller, String serverVersion) {
		this.db = new Database(databaseFile);
		SeedImporter.importIfMissing(db, SeedImporter.DEFAULT_RULESET);
		this.rules = new RulesData(db);
		this.roller = roller == null ? new RandomRollService() : roller;
		this.campaigns = new CampaignService(db, rules, session, serverVersion);
		this.characters = new CharacterService(db, rules, this.roller);
		this.encounters = new EncounterService(db, rules, this.roller, characters);
		this.runtime = new RuntimeService(db, rules, this.roller, characters);
		this.sessions = new SessionService(db, rules, characters, encounters);
		this.levelUps = new LevelUpService(db, rules, this.roller, characters);
		this.party = new PartyService(db, rules, sessions);
		this.world = new WorldService(db, rules, this.roller);
		this.narrative = new NarrativeService(db, sessions, characters);
		this.rest = new RestService(db, rules, this.roller, characters);
		this.spells = new SpellService(db, rules, this.roller, characters);
		this.accounts = new se.hirt.mcp.rpg.economy.AccountService(db);
		this.cashFlows = new se.hirt.mcp.rpg.economy.CashFlowService(db);
		this.chronicle = new se.hirt.mcp.rpg.session.ChronicleService(db);
		this.ledger = new LedgerService(db);
		this.checkpoints = new CheckpointService(db);
		this.checks = new CheckService(db, rules, this.roller);
		this.content = new ContentService(db, rules);
		this.inventory = new InventoryService(db, rules);
	}

	public Database db() {
		return db;
	}

	public RulesData rules() {
		return rules;
	}

	public RollService roller() {
		return roller;
	}

	public ServerSession session() {
		return session;
	}

	public CampaignService campaigns() {
		return campaigns;
	}

	public CharacterService characters() {
		return characters;
	}

	public SessionService sessions() {
		return sessions;
	}

	public LedgerService ledger() {
		return ledger;
	}

	public CheckpointService checkpoints() {
		return checkpoints;
	}

	public CheckService checks() {
		return checks;
	}

	public ContentService content() {
		return content;
	}

	public InventoryService inventory() {
		return inventory;
	}

	public RuntimeService runtime() {
		return runtime;
	}

	public EncounterService encounters() {
		return encounters;
	}

	public LevelUpService levelUps() {
		return levelUps;
	}

	public PartyService party() {
		return party;
	}

	public WorldService world() {
		return world;
	}

	public NarrativeService narrative() {
		return narrative;
	}

	public RestService rest() {
		return rest;
	}

	public SpellService spells() {
		return spells;
	}

	public se.hirt.mcp.rpg.economy.AccountService accounts() {
		return accounts;
	}

	public se.hirt.mcp.rpg.session.ChronicleService chronicle() {
		return chronicle;
	}

	public se.hirt.mcp.rpg.economy.CashFlowService cashFlows() {
		return cashFlows;
	}

	@Override
	public void close() {
		db.close();
	}
}
