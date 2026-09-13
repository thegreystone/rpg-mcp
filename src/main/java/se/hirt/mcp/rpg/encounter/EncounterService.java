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
package se.hirt.mcp.rpg.encounter;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.checkpoint.CheckpointService;
import se.hirt.mcp.rpg.content.ContentService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.harness.HarnessState;
import se.hirt.mcp.rpg.inventory.InventoryService;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.rules.Combat;
import se.hirt.mcp.rpg.rules.Rules;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.*;

/**
 * The encounter state machine (DESIGN.md §15, DOMAIN_MODEL.md §10, MCP_PROTOCOL.md §15): sides,
 * initiative, rounds and turns owned by the engine; one action resolves atomically (I-32);
 * zone-level positioning.
 */
public final class EncounterService {

	public static final Set<String> STANCES = Set.of("ALLIED", "HOSTILE", "NEUTRAL", "TEMPORARILY_COOPERATIVE",
			"UNKNOWN");
	public static final Set<String> ACTIONS = Set.of("ATTACK", "CAST", "DODGE", "DASH", "DISENGAGE", "HELP", "HIDE",
			"MOVE", "USE_ITEM", "INTERACT", "OTHER_RULES_ACTION", "END_TURN");
	public static final Set<String> OUTCOMES = Set.of("PARTY_VICTORY", "PARTY_DEFEAT", "PARTY_FLED", "ENEMIES_FLED",
			"NEGOTIATED", "OTHER");
	private static final String DODGING_REF = "srd5e:effect/dodging";
	private static final String DISENGAGING_REF = "srd5e:effect/disengaging";
	private static final String SHIELD_SPELL_REF = "srd5e:effect/shield-spell";
	private static final String SHIELD_SPELL = "srd5e:spell/shield";
	/**
	 * Conditions that deny reactions (SRD 5.2.1 Incapacitated and the conditions that include it).
	 */
	private static final List<String> NO_REACTION_CONDITIONS = List.of("INCAPACITATED", "STUNNED", "PARALYZED",
			"UNCONSCIOUS", "PETRIFIED");
	public static final Set<String> NPC_REACTION_POLICIES = Set.of("AUTO", "ASK");
	private static final int SECONDS_PER_ROUND = 6;

	private final Database db;
	private final RulesData rules;
	private final RollService roller;
	private final CharacterService characters;

	public EncounterService(Database db, RulesData rules, RollService roller, CharacterService characters) {
		this.db = db;
		this.rules = rules;
		this.roller = roller;
		this.characters = characters;
	}

	// ── start_encounter ────────────────────────────────────────────────

	public Map<String, Object> start(
		String operationId, String campaignRef, Map<String, Object> sides, Map<String, Object> stances,
		Map<String, Object> zones, String environment, List<String> objectives, String locationRef) {
		return start(operationId, campaignRef, sides, stances, zones, environment, objectives, locationRef, null);
	}

	/**
	 * @param options
	 *            {@code npc_reactions}: AUTO (default; NPC opportunity attacks and Shield are
	 *            resolved by the engine) or ASK (every reaction becomes a pending choice)
	 */
	@SuppressWarnings("unchecked")
	public Map<String, Object> start(
		String operationId, String campaignRef, Map<String, Object> sides, Map<String, Object> stances,
		Map<String, Object> zones, String environment, List<String> objectives, String locationRef,
		Map<String, Object> options) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		String npcReactions = options == null || options.get("npc_reactions") == null ? "AUTO"
				: options.get("npc_reactions").toString().toUpperCase();
		if (!NPC_REACTION_POLICIES.contains(npcReactions)) {
			throw RpgException.invalidArgument("options.npc_reactions must be AUTO or ASK.");
		}
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("options", options);
		args.put("sides", sides);
		args.put("stances", stances);
		args.put("zones", zones);
		args.put("environment", environment);
		args.put("objectives", objectives);
		return db.mutate(Database.Mutation.of("start_encounter", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "start_encounter");
			if (tx.count(
					"SELECT COUNT(*) FROM encounter WHERE campaign_id = ? AND status IN ('CREATED','RUNNING','WAITING_CHOICE')",
					campaignId) > 0) {
				throw RpgException.notAllowed("An encounter is already running; end it first.");
			}
			if (sides == null || sides.size() < 2) {
				throw RpgException.invalidArgument(
						"sides must map at least two side names to lists of character references, e.g. {\"party\": [\"character:1\"], \"raiders\": [\"character:2\"]}.");
			}
			Long pcId = tx
					.queryOne("SELECT character_id FROM player_control_assignment WHERE campaign_id = ? AND active = 1",
							campaignId)
					.map(r -> r.lng("character_id")).orElse(null);
			var participants = new ArrayList<Map<String, Object>>();
			var seen = new java.util.HashSet<Long>();
			String partySide = null;
			for (var side : sides.entrySet()) {
				if (!(side.getValue() instanceof List<?> members) || members.isEmpty()) {
					throw RpgException.invalidArgument(
							"Side '" + side.getKey() + "' must list at least one character reference.");
				}
				for (Object ref : members) {
					Row c = CharacterService.character(tx, campaignId, String.valueOf(ref));
					if (!"ACTIVE".equals(c.str("lifecycle"))) {
						throw RpgException.validation(List.of(new Violation("sides." + side.getKey(), "NOT_ACTIVE",
								ref + " is not an active character.")));
					}
					if ("DEAD".equals(c.str("life_state"))) {
						throw RpgException.validation(
								List.of(new Violation("sides." + side.getKey(), "DEAD", c.str("name") + " is dead.")));
					}
					if (!seen.add(c.id())) {
						throw RpgException
								.validation(List.of(new Violation("sides", "DUPLICATE", ref + " appears twice.")));
					}
					if (pcId != null && c.id() == pcId) {
						partySide = side.getKey();
					}
					var p = new LinkedHashMap<String, Object>();
					p.put("character", c);
					p.put("side", side.getKey());
					participants.add(p);
				}
			}
			if (partySide == null) {
				partySide = sides.keySet().iterator().next();
			}
			var stanceMap = new LinkedHashMap<String, Object>();
			List<String> names = new ArrayList<>(sides.keySet());
			for (int i = 0; i < names.size(); i++) {
				for (int j = i + 1; j < names.size(); j++) {
					String key = names.get(i) + "|" + names.get(j);
					String alt = names.get(j) + "|" + names.get(i);
					Object given = stances == null ? null : stances.getOrDefault(key, stances.get(alt));
					String stance = given == null ? "HOSTILE" : given.toString().toUpperCase();
					if (!STANCES.contains(stance)) {
						throw RpgException.invalidArgument("stance '" + given + "' must be one of " + STANCES + ".");
					}
					stanceMap.put(key, stance);
				}
			}
			Long locationId = campaign.lng("current_location_id");
			if (locationRef != null && !locationRef.isBlank()) {
				locationId = Ref.id(locationRef, Ref.LOCATION);
			}
			String policy = campaign.str("continuation_policy");
			String retryCheckpoint = null;
			Long retryCheckpointId = null;
			if ("ENCOUNTER_RETRY".equals(policy)) {
				retryCheckpoint = CheckpointService.createInTx(tx, campaignId, "encounter_retry", true);
				retryCheckpointId = Ref.id(retryCheckpoint, Ref.CHECKPOINT);
			}
			var sidesJson = new LinkedHashMap<String, Object>();
			sidesJson.put("names", names);
			sidesJson.put("party_side", partySide);
			sidesJson.put("stances", stanceMap);
			// The XP pool is fixed up front: every hostile participant counts when the encounter is
			// overcome, however it is overcome (RULES_ENGINE.md §6).
			long xpPool = 0;
			for (Map<String, Object> p : participants) {
				Row hostileC = (Row) p.get("character");
				if (hostile(stanceMap, partySide, String.valueOf(p.get("side")))
						&& !hostileC.isNull("origin_content_ref")) {
					xpPool += rules.find(hostileC.str("origin_content_ref")).map(d -> d.payload().get("xp_value"))
							.map(v -> ((Number) v).longValue()).orElse(0L);
				}
			}
			sidesJson.put("xp_pool", xpPool);
			var cols = new LinkedHashMap<String, Object>();
			cols.put("campaign_id", campaignId);
			cols.put("status", "RUNNING");
			cols.put("round", 1);
			cols.put("sides_json", Json.write(sidesJson));
			cols.put("npc_reactions", npcReactions);
			cols.put("environment_json", environment == null ? null : Json.write(Map.of("description", environment)));
			cols.put("objectives_json", objectives == null ? null : Json.write(objectives));
			cols.put("spatial_model", "ZONES");
			cols.put("retry_checkpoint_id", retryCheckpointId);
			cols.put("location_id", locationId);
			cols.put("revision", 0);
			long encounterId = tx.insert("encounter", cols);

			// Initiative: d20 + DEX modifier; ties broken by DEX score, then by a die (SRD 5.2.1 "Initiative").
			var order = new ArrayList<Map<String, Object>>();
			for (Map<String, Object> p : participants) {
				Row c = (Row) p.get("character");
				int dexMod = Rules.modifier(c.intOr("dex_score", 10));
				// Alert (Origin feat): the proficiency bonus is added to initiative (SRD 5.2.1 "Feats").
				int initiativeBonus = dexMod + (se.hirt.mcp.rpg.character.Origins.initiativeProficient(tx, rules, c)
						? RuntimeService.proficiencyBonus(tx, rules, c) : 0);
				Roll roll = roller.roll(
						"1d20" + (initiativeBonus >= 0 ? "+" + initiativeBonus : Integer.toString(initiativeBonus)));
				CharacterService.recordRoll(tx, campaignId, "initiative " + Ref.of(Ref.CHARACTER, c.id()), roll);
				int tiebreak = c.intOr("dex_score", 10) * 100 + roller.roll("1d100").total();
				var pc = new LinkedHashMap<String, Object>();
				pc.put("encounter_id", encounterId);
				pc.put("character_id", c.id());
				pc.put("side", p.get("side"));
				pc.put("initiative", roll.total());
				pc.put("initiative_tiebreak", tiebreak);
				pc.put("position_zone", zones == null || zones.get(Ref.of(Ref.CHARACTER, c.id())) == null ? "near"
						: zones.get(Ref.of(Ref.CHARACTER, c.id())).toString());
				pc.put("status", "ACTIVE");
				long participantId = tx.insert("encounter_participant", pc);
				tx.update("character", c.id(), Map.of("encounter_id", encounterId, "revision", c.lng("revision") + 1));
				var o = new LinkedHashMap<String, Object>();
				o.put("participant", participantId);
				o.put("initiative", roll.total());
				o.put("tiebreak", tiebreak);
				o.put("name", c.str("name"));
				o.put("roll", roll.toMap());
				order.add(o);
			}
			List<Row> ordered = turnOrder(tx, encounterId);
			long first = ordered.get(0).id();
			tx.update("encounter", encounterId, Map.of("turn_participant_id", first, "revision", 1));
			log(tx, campaignId, encounterId, 1, null, "ENCOUNTER_STARTED",
					"Encounter begins. Initiative: " + ordered.stream().map(
							r -> tx.get("character", r.lng("character_id")).str("name") + " " + r.lng("initiative"))
							.toList(),
					null);
			LedgerService.append(tx, campaignId, new LedgerService.EventSpec("ENCOUNTER_STARTED",
					"An encounter began between " + String.join(" and ", names)
							+ (environment == null ? "" : " (" + environment + ")") + ".",
					participants.stream().map(p -> ((Row) p.get("character")).id()).toList(), "NOTABLE", "PARTY_KNOWN",
					"GM", null, locationId, null, Map.of("encounter", Ref.of(Ref.ENCOUNTER, encounterId))));
			tx.update("campaign", campaignId,
					Map.of("harness_state", HarnessState.ENCOUNTER.name(), "revision", campaign.lng("revision") + 1));
			tx.touched(Ref.of(Ref.ENCOUNTER, encounterId), 1);
			Map<String, Object> state = state(tx, tx.get("encounter", encounterId), 10);
			state.put("retry_checkpoint", retryCheckpoint);
			state.put("initiative_rolls", order);
			state.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return state;
		});
	}

	// ── get_encounter_state ────────────────────────────────────────────

	public Map<String, Object> encounterState(String campaignRef, String encounterRef, int logLimit) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Row encounter = encounter(tx, campaign.id(), encounterRef);
			Map<String, Object> state = state(tx, encounter, Math.max(1, Math.min(logLimit <= 0 ? 10 : logLimit, 100)));
			state.put("meta", Harness.meta(campaign, null));
			return state;
		});
	}

	private Row encounter(Tx tx, long campaignId, String encounterRef) {
		if (encounterRef == null || encounterRef.isBlank()) {
			return tx.queryOne(
					"SELECT * FROM encounter WHERE campaign_id = ? AND status IN ('CREATED','RUNNING','WAITING_CHOICE') ORDER BY id DESC LIMIT 1",
					campaignId).orElseThrow(() -> RpgException.notFound("A running encounter"));
		}
		Row e = tx.find("encounter", Ref.id(encounterRef, Ref.ENCOUNTER))
				.orElseThrow(() -> RpgException.notFound("Encounter " + encounterRef));
		if (e.lng("campaign_id") != campaignId) {
			throw RpgException.invalidArgument(encounterRef + " belongs to another campaign.");
		}
		return e;
	}

	private static List<Row> turnOrder(Tx tx, long encounterId) {
		return tx.query(
				"SELECT * FROM encounter_participant WHERE encounter_id = ? ORDER BY initiative DESC, initiative_tiebreak DESC, id",
				encounterId);
	}

	public Map<String, Object> state(Tx tx, Row encounter, int logLimit) {
		Map<String, Object> sides = encounter.map("sides_json");
		String partySide = String.valueOf(sides.get("party_side"));
		var m = new LinkedHashMap<String, Object>();
		m.put("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()));
		m.put("status", encounter.str("status"));
		m.put("round", encounter.lng("round"));
		m.put("sides", sides);
		m.put("environment",
				encounter.isNull("environment_json") ? null : encounter.map("environment_json").get("description"));
		m.put("objectives", encounter.isNull("objectives_json") ? List.of() : encounter.list("objectives_json"));
		Long turnId = encounter.lng("turn_participant_id");
		var participants = new ArrayList<Map<String, Object>>();
		for (Row p : turnOrder(tx, encounter.id())) {
			Row c = tx.get("character", p.lng("character_id"));
			var pm = new LinkedHashMap<String, Object>();
			pm.put("participant", p.id());
			pm.put("character", Ref.of(Ref.CHARACTER, c.id()));
			pm.put("name", c.str("name"));
			pm.put("side", p.str("side"));
			pm.put("initiative", p.lng("initiative"));
			pm.put("zone", p.str("position_zone"));
			pm.put("status", p.str("status"));
			pm.put("life_state", c.str("life_state"));
			boolean party = p.str("side").equals(partySide);
			var hp = new LinkedHashMap<String, Object>();
			hp.put("current", c.integer("current_hp"));
			hp.put("max", c.isNull("max_hp") ? null : RuntimeService.effectiveMaxHp(tx, c));
			hp.put("temp", c.integer("temp_hp"));
			if (!party) {
				hp.put("visibility", "GM_ONLY");
			}
			pm.put("hp", hp);
			pm.put("armor_class", RuntimeService.armorClass(tx, rules, c));
			pm.put("conditions", RuntimeService.conditions(tx, c.id()));
			if (!c.isNull("death_saves_json")) {
				pm.put("death_saves", c.map("death_saves_json"));
			}
			pm.put("current_turn", turnId != null && turnId == p.id());
			pm.put("reaction_available", reactionAvailable(tx, p, c));
			pm.put("attacks", attackOptions(tx, c));
			participants.add(pm);
		}
		m.put("participants", participants);
		m.put("turn", turnId == null ? null : participants.stream()
				.filter(p -> Boolean.TRUE.equals(p.get("current_turn"))).findFirst().orElse(null));
		m.put("recent_log", tx.query("SELECT * FROM encounter_log WHERE encounter_id = ? ORDER BY id DESC LIMIT ?",
				encounter.id(), logLimit).stream().map(EncounterService::logEntry).toList());
		List<Map<String, Object>> pending = pendingChoices(tx, encounter.id());
		m.put("pending_choices", pending);
		m.put("npc_reactions", encounter.str("npc_reactions"));
		m.put("legal_actions",
				pending.isEmpty() ? ACTIONS.stream().sorted().toList() : List.of("resolve_pending_choice"));
		m.put("revision", encounter.lng("revision"));
		return m;
	}

	/** Open pending choices of an encounter, as the protocol presents them. */
	private List<Map<String, Object>> pendingChoices(Tx tx, long encounterId) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row t : tx.query(
				"SELECT * FROM pending_transaction WHERE kind = 'ENCOUNTER_CHOICE' AND status = 'OPEN' ORDER BY id")) {
			Map<String, Object> payload = t.map("payload_json");
			if (!(payload.get("encounter_id") instanceof Number n) || n.longValue() != encounterId) {
				continue;
			}
			out.add(choiceView(tx, t));
		}
		return out;
	}

	private Map<String, Object> choiceView(Tx tx, Row t) {
		Map<String, Object> payload = t.map("payload_json");
		long chooser = ((Number) payload.get("chooser")).longValue();
		var m = new LinkedHashMap<String, Object>();
		m.put("transaction", Ref.of(Ref.TRANSACTION, t.id()));
		m.put("kind", payload.get("choice_kind"));
		m.put("chooser", Ref.of(Ref.CHARACTER, chooser));
		m.put("chooser_name", tx.get("character", chooser).str("name"));
		m.put("player_controlled", playerControlled(tx, t.lng("campaign_id"), chooser));
		m.put("prompt", payload.get("prompt"));
		m.put("options", payload.get("options"));
		m.put("mandatory", true);
		m.put("resolve_with", "resolve_pending_choice");
		return m;
	}

	private static Map<String, Object> logEntry(Row r) {
		var m = new LinkedHashMap<String, Object>();
		m.put("round", r.lng("round"));
		m.put("kind", r.str("kind"));
		m.put("actor", Ref.ofNullable(Ref.CHARACTER, r.lng("actor_character_id")));
		m.put("summary", r.str("summary"));
		return m;
	}

	/**
	 * What a character can attack with: equipped/carried weapons, creature actions, or an unarmed
	 * strike.
	 */
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> attackOptions(Tx tx, Row c) {
		var out = new ArrayList<Map<String, Object>>();
		if (!c.isNull("origin_content_ref")) {
			rules.find(c.str("origin_content_ref")).ifPresent(d -> {
				for (Map<String, Object> a : (List<Map<String, Object>>) d.payload().getOrDefault("actions",
						List.of())) {
					if (String.valueOf(a.get("kind")).endsWith("ATTACK")) {
						var m = new LinkedHashMap<String, Object>();
						m.put("name", a.get("name"));
						m.put("kind", a.get("kind"));
						m.put("attack_bonus", a.get("attack_bonus"));
						m.put("damage", a.get("damage"));
						out.add(m);
					}
				}
				if (d.payload().get("multiattack") != null) {
					out.add(Map.of("name", "Multiattack", "kind", "MULTIATTACK", "detail",
							d.payload().get("multiattack")));
				}
			});
		}
		for (Row e : tx.query("SELECT * FROM inventory_entry WHERE character_id = ? ORDER BY equipped DESC, id",
				c.id())) {
			ContentService.Item item = ContentService.itemForEntry(tx, rules, e);
			if (item.type().equals("WEAPON")) {
				var m = new LinkedHashMap<String, Object>();
				m.put("name", item.name());
				m.put("entry", Ref.of(Ref.INVENTORY, e.id()));
				m.put("kind", String.valueOf(item.payload().get("category")).endsWith("RANGED") ? "RANGED_ATTACK"
						: "MELEE_ATTACK");
				m.put("equipped", e.bool("equipped"));
				m.put("damage", item.payload().get("damage"));
				m.put("properties", item.payload().get("properties"));
				out.add(m);
			}
		}
		if (out.isEmpty()) {
			out.add(Map.of("name", "Unarmed Strike", "kind", "MELEE_ATTACK"));
		}
		return out;
	}

	// ── perform_encounter_action ───────────────────────────────────────

	public Map<String, Object> perform(
		String operationId, String campaignRef, String encounterRef, String actorRef, Map<String, Object> action,
		boolean endTurn) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("encounter", encounterRef);
		args.put("actor", actorRef);
		args.put("action", action);
		args.put("end_turn", endTurn);
		return db.mutate(Database.Mutation.of("perform_encounter_action", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "perform_encounter_action");
			Row encounter = encounter(tx, campaignId, encounterRef);
			if ("WAITING_CHOICE".equals(encounter.str("status"))) {
				throw RpgException.notAllowed("A pending choice must be resolved first (resolve_pending_choice): "
						+ pendingChoices(tx, encounter.id()).stream()
								.map(c -> c.get("transaction") + " for " + c.get("chooser_name")).toList());
			}
			if (!"RUNNING".equals(encounter.str("status"))) {
				throw RpgException.notAllowed("The encounter is " + encounter.str("status") + ".");
			}
			Row actor = CharacterService.character(tx, campaignId, actorRef);
			Row participant = tx
					.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
							encounter.id(), actor.id())
					.orElseThrow(() -> RpgException.invalidArgument(actorRef + " is not part of this encounter."));
			if (action == null || action.get("kind") == null) {
				throw RpgException.invalidArgument("action.kind is required: " + ACTIONS.stream().sorted().toList());
			}
			String kind = String.valueOf(action.get("kind")).toUpperCase();
			if (!ACTIONS.contains(kind)) {
				throw RpgException.invalidArgument(
						"Unknown action kind '" + kind + "'; legal kinds: " + ACTIONS.stream().sorted().toList());
			}
			Long turnId = encounter.lng("turn_participant_id");
			if (turnId == null || turnId != participant.id()) {
				String whose = turnId == null ? "nobody"
						: tx.get("character", tx.get("encounter_participant", turnId).lng("character_id")).str("name");
				throw RpgException.notAllowed("It is " + whose + "'s turn, not " + actor.str("name")
						+ "'s (I-31). Reactions are offered by the engine as pending choices.");
			}
			if (!"ACTIVE".equals(participant.str("status")) || !"ALIVE".equals(actor.str("life_state"))) {
				throw RpgException.notAllowed(actor.str("name") + " cannot act (" + actor.str("life_state") + ").");
			}
			long round = encounter.lng("round");
			var result = new LinkedHashMap<String, Object>();
			result.put("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()));
			result.put("actor", Ref.of(Ref.CHARACTER, actor.id()));
			result.put("kind", kind);
			switch (kind) {
			case "ATTACK" -> result.putAll(attack(tx, campaignId, encounter, actor, participant, action, round));
			case "CAST" -> {
				if (action.get("spell") == null) {
					throw RpgException.invalidArgument("action.spell is required for CAST.");
				}
				@SuppressWarnings("unchecked")
				List<String> targets = action.get("targets") instanceof List<?> l
						? (List<String>) (List<?>) l.stream().map(Object::toString).toList()
						: action.get("target") == null ? List.of() : List.of(action.get("target").toString());
				Integer slot = action.get("slot_level") instanceof Number n ? n.intValue() : null;
				result.putAll(se.hirt.mcp.rpg.magic.SpellService.castCore(tx, rules, roller, campaignId, actor,
						action.get("spell").toString(), slot, targets, action, encounter.id(), round));
			}
			case "DODGE" -> {
				var cols = new LinkedHashMap<String, Object>();
				cols.put("campaign_id", campaignId);
				cols.put("character_id", actor.id());
				cols.put("source_description", "Dodge action");
				cols.put("provenance", "MECHANICAL_CONSEQUENCE");
				cols.put("condition_ref", DODGING_REF);
				cols.put("start_seq", GameTime.currentSeq(tx, campaignId));
				cols.put("start_journal_id", tx.journalId());
				cols.put("duration_json",
						Json.write(Map.of("until", "START_OF_TURN", "participant", participant.id())));
				cols.put("stacking_key", "DODGING");
				tx.insert("active_effect", cols);
				log(tx, campaignId, encounter.id(), round, actor.id(), "DODGE",
						actor.str("name") + " takes the Dodge action.", null);
				result.put("effect", "Attack rolls against " + actor.str("name")
						+ " have Disadvantage until the start of their next turn; Dexterity saves have Advantage.");
			}
			case "MOVE", "DASH" -> {
				String from = participant.str("position_zone");
				String zone = action.get("zone") == null ? from : action.get("zone").toString();
				tx.update("encounter_participant", participant.id(), Map.of("position_zone", zone));
				log(tx, campaignId, encounter.id(), round, actor.id(), kind,
						actor.str("name") + (kind.equals("DASH") ? " dashes" : " moves") + " to " + zone + ".", null);
				result.put("zone", zone);
				if (from != null && !from.equals(zone)) {
					result.putAll(opportunityAttacks(tx, campaignId, encounter, actor, participant, from, round));
				}
			}
			case "HIDE" -> {
				int dexMod = Rules.modifier(actor.intOr("dex_score", 10));
				boolean proficient = tx.count(
						"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SKILL' AND content_ref = 'srd5e:skill/stealth'",
						actor.id()) > 0;
				// One source of truth: the character's own Stealth when they have a sheet, the stat block's when
				// they are still a stat block (RULES_ENGINE.md §3).
				int bonus = creatureSkill(tx, actor, "Stealth");
				Roll roll = roller.roll("1d20" + (bonus >= 0 ? "+" + bonus : Integer.toString(bonus)));
				CharacterService.recordRoll(tx, campaignId, "stealth (hide)", roll);
				boolean hidden = roll.total() >= 15;
				log(tx, campaignId, encounter.id(), round, actor.id(), "HIDE", actor.str("name")
						+ " tries to hide: Stealth " + roll.total() + (hidden ? " (hidden)" : " (not hidden)"), null);
				result.put("roll", roll.toMap());
				result.put("hidden", hidden);
				result.put("note", "Hide DC 15 (SRD 5.2.1); the GM decides who can perceive the hidden creature.");
			}
			case "USE_ITEM" -> result.putAll(useItem(tx, campaignId, encounter, actor, action, round));
			case "DISENGAGE", "HELP", "INTERACT", "OTHER_RULES_ACTION" -> {
				String text = action.get("description") == null ? kind : action.get("description").toString();
				log(tx, campaignId, encounter.id(), round, actor.id(), kind, actor.str("name") + ": " + text, action);
				result.put("recorded", text);
				if (kind.equals("DISENGAGE")) {
					se.hirt.mcp.rpg.rules.Effects.add(tx, campaignId, actor.id(), actor.id(), null, "Disengage action",
							DISENGAGING_REF, null, Map.of("until", "START_OF_TURN", "participant", participant.id()),
							null, "DISENGAGING", "MECHANICAL_CONSEQUENCE");
					result.put("effect",
							actor.str("name") + "'s movement doesn't provoke Opportunity Attacks this turn.");
				}
			}
			case "END_TURN" -> {
				// nothing to resolve
			}
			default -> throw RpgException.invalidArgument("Unhandled action " + kind);
			}
			// A stat block that says "makes two attacks" gets two attacks: the turn does not end under the
			// actor while attacks remain (SRD 5.2.1 "Multiattack"). END_TURN always ends it.
			int remaining = kind.equals("ATTACK") ? attacksRemaining(tx, encounter, actor, round) : 0;
			if (remaining > 0) {
				result.put("attacks_remaining", remaining);
				result.put("next_step", actor.str("name") + " has " + remaining
						+ " attack(s) left this turn: attack again, or END_TURN to finish.");
			}
			boolean advance = kind.equals("END_TURN") || (endTurn && remaining == 0);
			List<Map<String, Object>> pending = pendingChoices(tx, encounter.id());
			if (!pending.isEmpty()) {
				// I-33: the action is on hold until every choice is resolved; the turn advances afterwards if requested.
				tx.update("encounter", encounter.id(), Map.of("status", "WAITING_CHOICE"));
				for (Map<String, Object> c : pending) {
					Row t = tx.get("pending_transaction", Ref.id(c.get("transaction").toString(), Ref.TRANSACTION));
					Map<String, Object> payload = t.map("payload_json");
					payload.put("advance_turn_after", advance);
					tx.update("pending_transaction", t.id(), Map.of("payload_json", Json.write(payload)));
				}
				result.put("pending_choices", pending);
				result.put("turn_advanced", false);
				result.put("next_step", "Ask the chooser(s) and call resolve_pending_choice; the turn "
						+ (advance ? "advances afterwards." : "continues afterwards."));
			} else if (advance) {
				result.put("turn_advanced", true);
				result.putAll(advanceTurn(tx, campaignId, tx.get("encounter", encounter.id())));
			}
			Row after = tx.get("encounter", encounter.id());
			tx.update("encounter", after.id(), Map.of("revision", after.lng("revision") + 1));
			result.put("encounter_revision", after.lng("revision") + 1);
			result.put("state", state(tx, tx.get("encounter", encounter.id()), 5));
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return result;
		});
	}

	/**
	 * How many attacks the actor still has this turn, counting the one just made. Zero for anyone
	 * without a Multiattack, so ordinary attackers behave exactly as before.
	 */
	@SuppressWarnings("unchecked")
	private int attacksRemaining(Tx tx, Row encounter, Row actor, long round) {
		if (!RuntimeService.usesStatBlock(tx, actor)) {
			return 0;
		}
		int count = rules.find(actor.str("origin_content_ref")).map(d -> d.payload().get("multiattack"))
				.filter(m -> m instanceof Map<?, ?>).map(m -> ((Map<String, Object>) m).get("count"))
				.filter(c -> c instanceof Number).map(c -> ((Number) c).intValue()).orElse(1);
		if (count <= 1) {
			return 0;
		}
		Row p = tx.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
				encounter.id(), actor.id()).orElse(null);
		if (p == null) {
			return 0;
		}
		String turnKey = encounter.lng("turn_participant_id") + ":" + round;
		Map<String, Object> flags = p.isNull("once_per_turn_json") ? new LinkedHashMap<>()
				: new LinkedHashMap<>(p.map("once_per_turn_json"));
		int used = turnKey.equals(flags.get("attacks_turn")) && flags.get("attacks_used") instanceof Number n
				? n.intValue() : 0;
		used++;
		flags.put("attacks_turn", turnKey);
		flags.put("attacks_used", used);
		tx.update("encounter_participant", p.id(), Map.of("once_per_turn_json", Json.write(flags)));
		return Math.max(0, count - used);
	}

	/** A rolled feature damage die plus the description the result should carry. */
	private record FeatureDamage(Combat.RolledDamage damage, Map<String, Object> report) {
	}

	/**
	 * Sneak Attack (SRD 5.2.1 "Rogue"), driven entirely by the class definition's feature block.
	 * Applies when the attack used a Finesse or Ranged weapon and either the roll had Advantage, or
	 * an ally is beside the target and the roll did not have Disadvantage. Once per turn, tracked
	 * on the participant like Savage Attacker.
	 * <p>
	 * This engine has zones rather than a grid, so "an ally within 5 feet of the target" is
	 * adjudicated as "a living, non-incapacitated ally of the attacker shares the target's zone"
	 * (RULES_ENGINE.md §6).
	 */
	private Optional<FeatureDamage> sneakAttack(
		Tx tx, long campaignId, Row encounter, long round, Row actor, Row target, Combat.AttackProfile profile,
		String advantage, boolean critical) {
		Optional<se.hirt.mcp.rpg.progression.ClassFeatures.Mechanic> found = se.hirt.mcp.rpg.progression.ClassFeatures
				.mechanic(tx, rules, actor, "SNEAK_ATTACK");
		if (found.isEmpty()) {
			return Optional.empty();
		}
		se.hirt.mcp.rpg.progression.ClassFeatures.Mechanic mech = found.get();
		if (!mech.requiresWeapon().isEmpty() && !profile.finesseOrRanged()) {
			return Optional.empty();
		}
		Row actorParticipant = tx
				.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
						encounter.id(), actor.id())
				.orElse(null);
		if (actorParticipant == null) {
			return Optional.empty();
		}
		String turnKey = encounter.lng("turn_participant_id") + ":" + round;
		Map<String, Object> flags = actorParticipant.isNull("once_per_turn_json") ? new LinkedHashMap<>()
				: actorParticipant.map("once_per_turn_json");
		if (mech.oncePerTurn() && turnKey.equals(flags.get("sneak_attack"))) {
			return Optional.empty();
		}
		boolean qualifies = "ADVANTAGE".equals(advantage);
		String why = "advantage on the attack";
		if (!qualifies && !"DISADVANTAGE".equals(advantage)) {
			Row targetParticipant = tx
					.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
							encounter.id(), target.id())
					.orElse(null);
			if (targetParticipant != null) {
				for (Row p : tx.query(
						"SELECT * FROM encounter_participant WHERE encounter_id = ? AND side = ? AND character_id <> ?",
						encounter.id(), actorParticipant.str("side"), actor.id())) {
					if (!java.util.Objects.equals(p.str("zone"), targetParticipant.str("zone"))
							|| !"ACTIVE".equals(p.str("status"))) {
						continue;
					}
					Row ally = tx.get("character", p.lng("character_id"));
					if ("DEAD".equals(ally.str("life_state")) || RuntimeService.conditions(tx, ally.id()).stream()
							.anyMatch(cond -> String.valueOf(cond).toUpperCase().contains("INCAPACITATED"))) {
						continue;
					}
					qualifies = true;
					why = ally.str("name") + " is beside the target";
					break;
				}
			}
		}
		if (!qualifies) {
			return Optional.empty();
		}
		String dice = mech.scaledDice();
		String expression = critical ? Combat.critical(dice) : dice;
		Roll roll = roller.roll(expression);
		CharacterService.recordRoll(tx, campaignId, mech.name().toLowerCase() + " " + expression, roll);
		if (mech.oncePerTurn()) {
			flags.put("sneak_attack", turnKey);
			tx.update("encounter_participant", actorParticipant.id(), Map.of("once_per_turn_json", Json.write(flags)));
		}
		String type = profile.damage().isEmpty() ? "piercing" : profile.damage().get(0).type();
		var report = new LinkedHashMap<String, Object>();
		report.put("feature", mech.name());
		report.put("dice", expression);
		report.put("damage", roll.total());
		report.put("type", type);
		report.put("qualified_by", why);
		return Optional.of(new FeatureDamage(new Combat.RolledDamage(type, roll, Math.max(0, roll.total())), report));
	}

	@SuppressWarnings("unchecked")
	private int creatureSkill(Tx tx, Row actor, String skill) {
		if (!RuntimeService.usesStatBlock(tx, actor)) {
			// A classed character rolls Stealth from its own sheet like anything else (RULES_ENGINE.md §3).
			Integer own = se.hirt.mcp.rpg.rules.CheckService.characterSkill(tx, rules, actor, skill);
			return own != null ? own : Rules.modifier(actor.intOr("dex_score", 10));
		}
		return rules.find(actor.str("origin_content_ref")).map(d -> (Map<String, Object>) d.payload().get("skills"))
				.filter(s -> s != null && s.get(skill) instanceof Number).map(s -> ((Number) s.get(skill)).intValue())
				.orElse(Rules.modifier(actor.intOr("dex_score", 10)));
	}

	// ── attack resolution ──────────────────────────────────────────────

	/**
	 * The attack profile resolved from the action: creature action, carried weapon, or unarmed
	 * strike.
	 */
	private record Setup(Combat.AttackProfile profile, List<String> warnings) {
	}

	private Setup resolveProfile(Tx tx, Row actor, Map<String, Object> action) {
		var warnings = new ArrayList<String>();
		String attackName = action.get("attack") == null ? null : action.get("attack").toString();
		String weaponText = action.get("weapon") == null ? attackName : action.get("weapon").toString();
		int prof = RuntimeService.proficiencyBonus(tx, rules, actor);
		// A classed character attacks with what it carries, even when the stat block it was materialized from
		// happens to name the same weapon: otherwise a promoted companion keeps the monster's flat numbers and
		// loses its own ability modifier, proficiency and weapon properties (RULES_ENGINE.md §3).
		boolean statBlock = RuntimeService.usesStatBlock(tx, actor);
		Optional<Map<String, Object>> creatureAction = statBlock ? creatureAction(actor, attackName) : Optional.empty();
		Combat.AttackProfile profile;
		if (creatureAction.isPresent()) {
			profile = Combat.creatureAction(creatureAction.get());
		} else if (weaponText != null && !weaponText.isBlank() && !weaponText.equalsIgnoreCase("unarmed")) {
			Row weaponEntry = carriedWeapon(tx, actor, weaponText);
			ContentService.Item item = ContentService.itemForEntry(tx, rules, weaponEntry);
			if (!weaponEntry.bool("equipped")) {
				warnings.add(item.name() + " was not equipped; treating the draw as part of the attack.");
			}
			profile = Combat.weapon(actor, item.name(), item.payload(), prof,
					Boolean.TRUE.equals(action.get("two_handed")));
		} else if (statBlock && creatureAction(actor, null).isPresent()) {
			profile = Combat.creatureAction(creatureAction(actor, null).get());
		} else {
			profile = Combat.unarmed(actor, prof);
		}
		return new Setup(profile, warnings);
	}

	/**
	 * Rolls the attack, then either resolves it (damage, conditions, log) or — when the target
	 * could cast Shield and the decision is a player's — parks it as a pending choice. Reaction
	 * attacks never offer Shield.
	 */
	private Map<String, Object> attack(
		Tx tx, long campaignId, Row encounter, Row actor, Row participant, Map<String, Object> action, long round) {
		String targetRef = action.get("target") == null ? null : action.get("target").toString();
		if (targetRef == null) {
			throw RpgException.invalidArgument("action.target (character reference) is required for ATTACK.");
		}
		Row target = CharacterService.character(tx, campaignId, targetRef);
		Row targetParticipant = tx
				.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
						encounter.id(), target.id())
				.orElseThrow(() -> RpgException.invalidArgument(targetRef + " is not part of this encounter."));
		if ("DEAD".equals(target.str("life_state")) || !"ACTIVE".equals(targetParticipant.str("status"))) {
			throw RpgException.notAllowed(target.str("name") + " is already out of the fight.");
		}
		boolean reaction = Boolean.TRUE.equals(action.get("reaction"));
		Setup setup = resolveProfile(tx, actor, action);
		Combat.AttackProfile profile = setup.profile();
		if (reaction && profile.ranged()) {
			throw RpgException.validation(List
					.of(new Violation("choice.weapon", "NOT_MELEE", "An Opportunity Attack must be a melee attack.")));
		}
		if (Boolean.TRUE.equals(action.get("nonlethal")) && profile.ranged()) {
			throw RpgException.validation(List.of(new Violation("action.nonlethal", "NOT_MELEE",
					"Only a melee attack can knock a creature out (SRD 5.2.1 \"Knocking Out a Creature\").")));
		}
		// Ammunition (SRD 5.2.1 "Ammunition" property): one piece per attack.
		Map<String, Object> ammoUsed = null;
		if (profile.usesAmmunition()) {
			Row ammo = tx.queryOne(
					"SELECT * FROM inventory_entry WHERE character_id = ? AND content_ref = ? ORDER BY id LIMIT 1",
					actor.id(), profile.ammunitionRef())
					.orElseThrow(() -> RpgException.validation(List.of(new Violation("action.weapon", "NO_AMMUNITION",
							actor.str("name") + " has no ammunition for " + profile.name() + "."))));
			InventoryService.removeQuantity(tx, ammo, 1);
			ammoUsed = Map.of("item", profile.ammunitionRef(), "remaining", ammo.lng("quantity") - 1);
		}
		// Advantage / disadvantage sources: explicit, Dodge on the target, unconscious target, effects.
		String adv = action.get("advantage") == null ? "NONE" : action.get("advantage").toString().toUpperCase();
		boolean advantage = adv.equals("ADVANTAGE");
		boolean disadvantage = adv.equals("DISADVANTAGE");
		var reasons = new ArrayList<String>();
		boolean targetDodging = tx.count(
				"SELECT COUNT(*) FROM active_effect WHERE character_id = ? AND condition_ref = ?", target.id(),
				DODGING_REF) > 0;
		if (targetDodging) {
			disadvantage = true;
			reasons.add("target is dodging");
		}
		boolean targetUnconscious = "DYING".equals(target.str("life_state"))
				|| tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id = ? AND condition_ref = ?",
						target.id(), RuntimeService.UNCONSCIOUS_REF) > 0;
		if (targetUnconscious) {
			advantage = true;
			reasons.add("target is unconscious");
		}
		se.hirt.mcp.rpg.rules.Effects.Modifiers actorMods = se.hirt.mcp.rpg.rules.Effects.modifiers(tx, actor.id());
		se.hirt.mcp.rpg.rules.Effects.Modifiers targetMods = se.hirt.mcp.rpg.rules.Effects.modifiers(tx, target.id());
		if (targetMods.disadvantageOnAttacksAgainst) {
			disadvantage = true;
			reasons.add("target is protected (e.g. Blur)");
		}
		if (targetMods.advantageOnAttacksAgainst) {
			advantage = true;
			reasons.add("target is exposed (e.g. Faerie Fire)");
		}
		String dice = advantage && !disadvantage ? "2d20kh1" : disadvantage && !advantage ? "2d20kl1" : "1d20";
		int bonus = profile.attackBonus() + actorMods.attackBonus;
		Roll attackRoll = roller.roll(dice + (bonus >= 0 ? "+" + bonus : Integer.toString(bonus)));
		long rollId = CharacterService.recordRoll(tx, campaignId,
				(reaction ? "opportunity attack " : "attack ") + profile.name(), attackRoll);
		int attackTotal = attackRoll.total();
		var bonusDice = new ArrayList<Map<String, Object>>();
		for (String bd : actorMods.attackBonusDice) {
			Roll b = roller.roll(bd);
			CharacterService.recordRoll(tx, campaignId, "attack bonus die", b);
			attackTotal += b.total();
			bonusDice.add(b.toMap());
		}
		int natural = attackRoll.dice().get(0);
		int targetAc = RuntimeService.armorClass(tx, rules, target);
		boolean critical = natural == 20 || (targetUnconscious && !profile.ranged());
		boolean hit = natural != 1 && (natural == 20 || attackTotal >= targetAc);

		var ctx = new LinkedHashMap<String, Object>();
		ctx.put("attacker", actor.id());
		ctx.put("target", target.id());
		ctx.put("action", action);
		ctx.put("reaction", reaction);
		var roll = attackRoll.toMap();
		roll.put("roll_ref", Ref.of(Ref.ROLL, rollId));
		ctx.put("attack_roll", roll);
		ctx.put("bonus_dice", bonusDice);
		ctx.put("attack_total", attackTotal);
		ctx.put("natural", natural);
		ctx.put("advantage",
				advantage && !disadvantage ? "ADVANTAGE" : disadvantage && !advantage ? "DISADVANTAGE" : "NONE");
		ctx.put("advantage_reasons", reasons);
		ctx.put("target_armor_class", targetAc);
		ctx.put("critical", critical);
		ctx.put("hit", hit);
		ctx.put("ammunition", ammoUsed);
		ctx.put("warnings", setup.warnings());

		// Shield (SRD 5.2.1): a reaction when hit; +5 AC until the start of the caster's next turn, including against the trigger.
		if (hit && natural != 20 && !reaction && attackTotal < targetAc + 5
				&& canCastShield(tx, target, targetParticipant)) {
			if (asksBeforeReacting(tx, campaignId, encounter, target.id())) {
				var options = List.of(Map.of("option", "CAST_SHIELD", "description",
						"Cast Shield as a reaction (spends the lowest available spell slot): AC +5 until the start of "
								+ target.str("name") + "'s next turn, turning this hit into a miss."),
						Map.of("option", "DECLINE", "description", "Take the hit."));
				Map<String, Object> view = openChoice(tx, campaignId, encounter, target.id(), "SHIELD_SPELL",
						actor.str("name") + "'s " + profile.name() + " hits " + target.str("name") + " (" + attackTotal
								+ " vs AC " + targetAc + "). Cast Shield?",
						options, ctx);
				var out = attackView(ctx, profile);
				out.put("resolved", false);
				out.put("pending_choice", view);
				out.put("summary", actor.str("name") + "'s " + profile.name() + " hits " + target.str("name")
						+ " — waiting for a Shield decision.");
				return out;
			}
			Map<String, Object> shield = castShield(tx, campaignId, encounter, target, targetParticipant, round);
			ctx.put("shield", shield);
			ctx.put("target_armor_class", targetAc + 5);
			ctx.put("hit", false);
		}
		return finishAttack(tx, campaignId, encounter, actor, target, targetParticipant, profile, ctx, round);
	}

	private static Map<String, Object> attackView(Map<String, Object> ctx, Combat.AttackProfile profile) {
		var out = new LinkedHashMap<String, Object>();
		out.put("target", Ref.of(Ref.CHARACTER, ((Number) ctx.get("target")).longValue()));
		out.put("attack", profile.name());
		if (Boolean.TRUE.equals(ctx.get("reaction"))) {
			out.put("reaction", "OPPORTUNITY_ATTACK");
		}
		out.put("attack_roll", ctx.get("attack_roll"));
		if (ctx.get("bonus_dice") instanceof List<?> l && !l.isEmpty()) {
			out.put("bonus_dice", l);
		}
		out.put("attack_total", ctx.get("attack_total"));
		out.put("natural", ctx.get("natural"));
		out.put("advantage", ctx.get("advantage"));
		if (ctx.get("advantage_reasons") instanceof List<?> l && !l.isEmpty()) {
			out.put("advantage_reasons", l);
		}
		out.put("target_armor_class", ctx.get("target_armor_class"));
		if (ctx.get("shield") != null) {
			out.put("shield", ctx.get("shield"));
		}
		out.put("hit", ctx.get("hit"));
		out.put("critical", Boolean.TRUE.equals(ctx.get("hit")) && Boolean.TRUE.equals(ctx.get("critical")));
		if (ctx.get("ammunition") != null) {
			out.put("ammunition", ctx.get("ammunition"));
		}
		return out;
	}

	/** Second half of an attack: damage, knock-out, death, and the log line. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> finishAttack(
		Tx tx, long campaignId, Row encounter, Row actor, Row target, Row targetParticipant,
		Combat.AttackProfile profile, Map<String, Object> ctx, long round) {
		target = tx.get("character", target.id());
		Map<String, Object> action = (Map<String, Object>) ctx.get("action");
		boolean hit = Boolean.TRUE.equals(ctx.get("hit"));
		boolean critical = Boolean.TRUE.equals(ctx.get("critical"));
		boolean reaction = Boolean.TRUE.equals(ctx.get("reaction"));
		int attackTotal = ((Number) ctx.get("attack_total")).intValue();
		int targetAc = ((Number) ctx.get("target_armor_class")).intValue();
		var out = attackView(ctx, profile);
		out.put("target_name", target.str("name"));
		out.put("resolved", true);
		String label = (reaction ? "opportunity attack with " : "") + profile.name();
		String summary;
		if (hit) {
			se.hirt.mcp.rpg.rules.Effects.Modifiers actorMods = se.hirt.mcp.rpg.rules.Effects.modifiers(tx, actor.id());
			var damages = new ArrayList<>(Combat.rollDamage(roller, profile, critical));
			// Savage Attacker: once per turn on a weapon hit, roll the weapon's damage dice twice and use
			// the better total (SRD 5.2.1 "Feats"); both rolls are recorded.
			if (se.hirt.mcp.rpg.character.Origins.savageAttacker(tx, rules, actor)) {
				String turnKey = encounter.lng("turn_participant_id") + ":" + round;
				Row actorParticipant = tx
						.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
								encounter.id(), actor.id())
						.orElse(null);
				Map<String, Object> flags = actorParticipant == null || actorParticipant.isNull("once_per_turn_json")
						? new LinkedHashMap<>() : actorParticipant.map("once_per_turn_json");
				if (actorParticipant != null && !turnKey.equals(flags.get("savage_attacker"))) {
					List<Combat.RolledDamage> second = Combat.rollDamage(roller, profile, critical);
					int firstTotal = damages.stream().mapToInt(Combat.RolledDamage::amount).sum();
					int secondTotal = second.stream().mapToInt(Combat.RolledDamage::amount).sum();
					for (Combat.RolledDamage d : second) {
						CharacterService.recordRoll(tx, campaignId,
								"savage attacker reroll " + profile.name() + " (" + d.type() + ")", d.roll());
					}
					if (secondTotal > firstTotal) {
						damages = new ArrayList<>(second);
					}
					out.put("savage_attacker", Map.of("first_total", firstTotal, "second_total", secondTotal, "used",
							Math.max(firstTotal, secondTotal)));
					flags.put("savage_attacker", turnKey);
					tx.update("encounter_participant", actorParticipant.id(),
							Map.of("once_per_turn_json", Json.write(flags)));
				}
			}
			// Sneak Attack and any other ENGINE class feature that adds damage on a qualifying hit
			// (RULES_ENGINE.md §2.2): the feature and its scaling live in the class definition, not here.
			Optional<FeatureDamage> sneak = sneakAttack(tx, campaignId, encounter, round, actor, target, profile,
					String.valueOf(ctx.getOrDefault("advantage", "NONE")), critical);
			if (sneak.isPresent()) {
				damages.add(sneak.get().damage());
				out.put("sneak_attack", sneak.get().report());
			}
			for (Map<String, Object> extra : actorMods.damageBonus) {
				Object against = extra.get("against");
				if (against instanceof Number n && n.longValue() != target.id()) {
					continue;
				}
				String expr = critical ? Combat.critical(String.valueOf(extra.get("dice")))
						: String.valueOf(extra.get("dice"));
				Roll r = roller.roll(expr);
				damages.add(new Combat.RolledDamage(String.valueOf(extra.get("type")), r, Math.max(0, r.total())));
			}
			for (Combat.RolledDamage d : damages) {
				CharacterService.recordRoll(tx, campaignId, "damage " + profile.name() + " (" + d.type() + ")",
						d.roll());
			}
			Combat.DamageResult dr = Combat.applyDamage(target.intOr("current_hp", 0), target.intOr("temp_hp", 0),
					Math.max(1, RuntimeService.effectiveMaxHp(tx, target)), damages,
					RuntimeService.defensesOf(tx, rules, target));
			boolean knockOut = Boolean.TRUE.equals(action.get("nonlethal")) && dr.droppedToZero();
			Map<String, Object> applied;
			if (knockOut) {
				// SRD 5.2.1 "Knocking Out a Creature": 0 HP, Unconscious and Stable instead of dying.
				Map<String, Object> saves = Combat.freshDeathSaves();
				saves.put("stable", true);
				var cols = new LinkedHashMap<String, Object>();
				cols.put("current_hp", 0);
				cols.put("temp_hp", 0);
				cols.put("life_state", "DYING");
				cols.put("death_saves_json", Json.write(saves));
				cols.put("revision", target.lng("revision") + 1);
				tx.update("character", target.id(), cols);
				se.hirt.mcp.rpg.rules.Effects.breakConcentration(tx, target.id());
				RuntimeService.addCondition(tx, campaignId, target.id(), "UNCONSCIOUS",
						Map.of("label", "knocked out; wakes after 1d4 hours or when healed"),
						"knocked out by " + actor.str("name"), "MECHANICAL_CONSEQUENCE");
				applied = new LinkedHashMap<>();
				applied.put("damage", dr.totalDealt());
				applied.put("breakdown", dr.breakdown());
				applied.put("hp_before", dr.hpBefore());
				applied.put("hp_after", 0);
				applied.put("life_state", "DYING");
				applied.put("knocked_out", true);
				tx.update("encounter_participant", targetParticipant.id(), Map.of("status", "DEFEATED"));
			} else {
				applied = RuntimeService.applyDamageResult(tx, rules, roller, target, dr, critical, actor.id(),
						"killed by " + actor.str("name") + "'s " + profile.name());
				if ("DEAD".equals(applied.get("life_state"))) {
					tx.update("encounter_participant", targetParticipant.id(), Map.of("status", "DEFEATED"));
				}
			}
			out.putAll(applied);
			summary = actor.str("name") + " hits " + target.str("name") + " with " + label
					+ (critical ? " (critical)" : "") + " for " + dr.totalDealt() + " damage (" + dr.hpBefore() + " → "
					+ dr.hpAfter() + " HP)"
					+ (knockOut ? " — " + target.str("name") + " is knocked out."
							: Boolean.TRUE.equals(applied.get("died")) ? " — " + target.str("name") + " dies."
									: "DYING".equals(applied.get("life_state"))
											? " — " + target.str("name") + " drops to 0 HP." : ".");
		} else {
			summary = actor.str("name") + " misses " + target.str("name") + " with " + label + " (" + attackTotal
					+ " vs AC " + targetAc + (ctx.get("shield") != null ? ", Shield" : "") + ").";
		}
		out.put("summary", summary);
		if (ctx.get("warnings") instanceof List<?> w && !w.isEmpty()) {
			out.put("warnings", w);
		}
		log(tx, campaignId, encounter.id(), round, actor.id(), reaction ? "OPPORTUNITY_ATTACK" : "ATTACK", summary,
				Map.of("target", target.id(), "hit", hit, "total", attackTotal));
		return out;
	}

	// ── reactions ──────────────────────────────────────────────────────

	static boolean playerControlled(Tx tx, long campaignId, long characterId) {
		return tx.count(
				"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id = ? AND character_id = ? AND active = 1",
				campaignId, characterId) > 0;
	}

	private boolean asksBeforeReacting(Tx tx, long campaignId, Row encounter, long characterId) {
		return playerControlled(tx, campaignId, characterId) || "ASK".equals(encounter.str("npc_reactions"));
	}

	/**
	 * A reaction is available once per round, to conscious, non-incapacitated, active participants
	 * (SRD 5.2.1 "Reactions").
	 */
	private boolean reactionAvailable(Tx tx, Row participant, Row c) {
		if (participant.intOr("reaction_used", 0) != 0 || !"ACTIVE".equals(participant.str("status"))
				|| !"ALIVE".equals(c.str("life_state"))) {
			return false;
		}
		for (String cond : NO_REACTION_CONDITIONS) {
			if (tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id = ? AND condition_ref = ?", c.id(),
					RuntimeService.conditionRef(cond)) > 0) {
				return false;
			}
		}
		return true;
	}

	private boolean canCastShield(Tx tx, Row target, Row targetParticipant) {
		return reactionAvailable(tx, targetParticipant, target) && tx.count(
				"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SPELL_PREPARED' AND content_ref = ?",
				target.id(), SHIELD_SPELL) > 0 && se.hirt.mcp.rpg.magic.SpellService.hasSlot(tx, target.id(), 1);
	}

	private Map<String, Object> castShield(
		Tx tx, long campaignId, Row encounter, Row target, Row targetParticipant, long round) {
		Map<String, Object> slot = se.hirt.mcp.rpg.magic.SpellService.spendLowestSlot(tx, target.id(), 1).orElseThrow(
				() -> RpgException.insufficientResource(target.str("name") + " has no spell slot for Shield."));
		se.hirt.mcp.rpg.rules.Effects.add(tx, campaignId, target.id(), target.id(), SHIELD_SPELL,
				"Shield (" + target.str("name") + ")", SHIELD_SPELL_REF, Map.of("ac_bonus", 5),
				Map.of("until", "START_OF_TURN", "participant", targetParticipant.id(), "label", "Shield"), null,
				"SHIELD", "PLAYER");
		tx.update("encounter_participant", targetParticipant.id(), Map.of("reaction_used", 1));
		log(tx, campaignId, encounter.id(), round, target.id(), "REACTION",
				target.str("name") + " casts Shield as a reaction (AC +5 until their next turn).", null);
		var m = new LinkedHashMap<String, Object>();
		m.put("cast", "Shield");
		m.put("slot", slot);
		m.put("armor_class_bonus", 5);
		return m;
	}

	/**
	 * Opportunity Attacks (SRD 5.2.1): leaving a zone that holds hostile, able creatures lets each
	 * of them make one melee attack as a reaction unless the mover took the Disengage action. NPCs
	 * resolve per the encounter's policy; player-controlled reactors decide via a pending choice.
	 * The move itself has already happened.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> opportunityAttacks(
		Tx tx, long campaignId, Row encounter, Row mover, Row moverParticipant, String fromZone, long round) {
		var out = new LinkedHashMap<String, Object>();
		if (tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id = ? AND condition_ref = ?", mover.id(),
				DISENGAGING_REF) > 0) {
			out.put("opportunity_attacks", "none (Disengage)");
			return out;
		}
		Map<String, Object> sides = encounter.map("sides_json");
		Map<String, Object> stances = (Map<String, Object>) sides.get("stances");
		var reactions = new ArrayList<Map<String, Object>>();
		for (Row p : turnOrder(tx, encounter.id())) {
			if (p.id() == moverParticipant.id() || !fromZone.equals(p.str("position_zone"))
					|| !hostile(stances, p.str("side"), moverParticipant.str("side"))) {
				continue;
			}
			Row reactor = tx.get("character", p.lng("character_id"));
			if (!reactionAvailable(tx, p, reactor)) {
				continue;
			}
			Row moverNow = tx.get("character", mover.id());
			if ("DEAD".equals(moverNow.str("life_state")) || "DYING".equals(moverNow.str("life_state"))) {
				break;
			}
			if (asksBeforeReacting(tx, campaignId, encounter, reactor.id())) {
				var options = List.of(
						Map.of("option", "TAKE", "description",
								"Make one melee attack against " + mover.str("name")
										+ " as a reaction (optionally name the weapon)."),
						Map.of("option", "DECLINE", "description", "Let " + mover.str("name") + " go."));
				var ctx = new LinkedHashMap<String, Object>();
				ctx.put("mover", mover.id());
				ctx.put("from_zone", fromZone);
				reactions.add(openChoice(
						tx, campaignId, encounter, reactor.id(), "OPPORTUNITY_ATTACK", mover.str("name") + " leaves "
								+ reactor.str("name") + "'s reach (zone " + fromZone + "). Opportunity Attack?",
						options, ctx));
			} else {
				reactions.add(reactionAttack(tx, campaignId, encounter, reactor, p, mover, null, round));
			}
		}
		out.put("opportunity_attacks", reactions);
		return out;
	}

	/** Resolves one opportunity attack by the reactor against the mover and spends the reaction. */
	private Map<String, Object> reactionAttack(
		Tx tx, long campaignId, Row encounter, Row reactor, Row reactorParticipant, Row mover, String weapon,
		long round) {
		var action = new LinkedHashMap<String, Object>();
		action.put("kind", "ATTACK");
		action.put("target", Ref.of(Ref.CHARACTER, mover.id()));
		action.put("reaction", true);
		if (weapon != null && !weapon.isBlank()) {
			action.put("weapon", weapon);
		} else {
			defaultMeleeAttack(tx, reactor).ifPresent(w -> action.put("weapon", w));
		}
		tx.update("encounter_participant", reactorParticipant.id(), Map.of("reaction_used", 1));
		Map<String, Object> result = attack(tx, campaignId, encounter, reactor, reactorParticipant, action, round);
		result.put("reactor", Ref.of(Ref.CHARACTER, reactor.id()));
		result.put("reactor_name", reactor.str("name"));
		return result;
	}

	/**
	 * The reactor's default melee weapon: the first equipped melee weapon, else the first carried
	 * one; creatures use their stat block.
	 */
	private Optional<String> defaultMeleeAttack(Tx tx, Row reactor) {
		if (RuntimeService.usesStatBlock(tx, reactor)) {
			return rules.find(reactor.str("origin_content_ref")).flatMap(d -> {
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> actions = (List<Map<String, Object>>) d.payload().getOrDefault("actions",
						List.of());
				return actions.stream().filter(a -> "MELEE_ATTACK".equals(String.valueOf(a.get("kind"))))
						.map(a -> String.valueOf(a.get("name"))).findFirst();
			});
		}
		for (Row e : tx.query("SELECT * FROM inventory_entry WHERE character_id = ? ORDER BY equipped DESC, id",
				reactor.id())) {
			ContentService.Item item = ContentService.itemForEntry(tx, rules, e);
			if (item.type().equals("WEAPON") && !String.valueOf(item.payload().get("category")).endsWith("RANGED")) {
				return Optional.of(Ref.of(Ref.INVENTORY, e.id()));
			}
		}
		return Optional.empty();
	}

	private Map<String, Object> openChoice(
		Tx tx, long campaignId, Row encounter, long chooserId, String choiceKind, String prompt,
		List<Map<String, String>> options, Map<String, Object> context) {
		var payload = new LinkedHashMap<String, Object>();
		payload.put("choice_kind", choiceKind);
		payload.put("encounter_id", encounter.id());
		payload.put("chooser", chooserId);
		payload.put("prompt", prompt);
		payload.put("options", options);
		payload.put("context", context);
		payload.put("round", encounter.lng("round"));
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("kind", "ENCOUNTER_CHOICE");
		cols.put("status", "OPEN");
		cols.put("controlled_refs_json",
				Json.write(List.of(Ref.of(Ref.ENCOUNTER, encounter.id()), Ref.of(Ref.CHARACTER, chooserId))));
		cols.put("payload_json", Json.write(payload));
		cols.put("revision", 0);
		long id = tx.insert("pending_transaction", cols);
		return choiceView(tx, tx.get("pending_transaction", id));
	}

	// ── resolve_pending_choice ─────────────────────────────────────────

	@SuppressWarnings("unchecked")
	public Map<String, Object> resolveChoice(
		String operationId, String campaignRef, String transactionRef, Map<String, Object> choice) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("transaction", transactionRef);
		args.put("choice", choice);
		return db.mutate(Database.Mutation.of("resolve_pending_choice", campaignId, operationId, "GM", args), tx -> {
			Harness.requireMutation(tx, campaignRef, "resolve_pending_choice");
			Row t = tx.find("pending_transaction", Ref.id(transactionRef, Ref.TRANSACTION))
					.orElseThrow(() -> RpgException.notFound("Pending choice " + transactionRef));
			if (t.lng("campaign_id") != campaignId || !"ENCOUNTER_CHOICE".equals(t.str("kind"))) {
				throw RpgException.invalidArgument(transactionRef + " is not an encounter choice of this campaign.");
			}
			if (!"OPEN".equals(t.str("status"))) {
				throw RpgException.notAllowed(transactionRef + " is already " + t.str("status") + ".");
			}
			Map<String, Object> payload = t.map("payload_json");
			Row encounter = tx.get("encounter", ((Number) payload.get("encounter_id")).longValue());
			if ("ENDED".equals(encounter.str("status"))) {
				throw RpgException.notAllowed("The encounter already ended.");
			}
			String option = choice == null || choice.get("option") == null ? null
					: choice.get("option").toString().toUpperCase();
			List<Map<String, Object>> options = (List<Map<String, Object>>) payload.get("options");
			if (option == null || options.stream().noneMatch(o -> option.equals(o.get("option")))) {
				throw RpgException.invalidArgument(
						"choice.option must be one of " + options.stream().map(o -> o.get("option")).toList() + ".");
			}
			Row chooser = tx.get("character", ((Number) payload.get("chooser")).longValue());
			Row chooserParticipant = tx
					.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
							encounter.id(), chooser.id())
					.orElseThrow();
			Map<String, Object> ctx = (Map<String, Object>) payload.get("context");
			long round = encounter.lng("round");
			String kind = String.valueOf(payload.get("choice_kind"));
			var result = new LinkedHashMap<String, Object>();
			result.put("transaction", Ref.of(Ref.TRANSACTION, t.id()));
			result.put("kind", kind);
			result.put("chooser", Ref.of(Ref.CHARACTER, chooser.id()));
			result.put("option", option);
			switch (kind) {
			case "OPPORTUNITY_ATTACK" -> {
				Row mover = tx.get("character", ((Number) ctx.get("mover")).longValue());
				if (option.equals("TAKE")) {
					if (!reactionAvailable(tx, chooserParticipant, chooser)) {
						throw RpgException.notAllowed(chooser.str("name") + " no longer has a reaction available.");
					}
					String weapon = choice.get("weapon") == null ? null : choice.get("weapon").toString();
					result.put("attack", reactionAttack(tx, campaignId, encounter, chooser, chooserParticipant, mover,
							weapon, round));
				} else {
					log(tx, campaignId, encounter.id(), round, chooser.id(), "REACTION",
							chooser.str("name") + " lets " + mover.str("name") + " go.", null);
				}
			}
			case "SHIELD_SPELL" -> {
				Row attacker = tx.get("character", ((Number) ctx.get("attacker")).longValue());
				Row target = tx.get("character", ((Number) ctx.get("target")).longValue());
				Row targetParticipant = chooserParticipant;
				Combat.AttackProfile profile = resolveProfile(tx, attacker, (Map<String, Object>) ctx.get("action"))
						.profile();
				var live = new LinkedHashMap<String, Object>(ctx);
				if (option.equals("CAST_SHIELD")) {
					if (!canCastShield(tx, target, targetParticipant)) {
						throw RpgException
								.notAllowed(target.str("name") + " can no longer cast Shield (no reaction or slot).");
					}
					live.put("shield", castShield(tx, campaignId, encounter, target, targetParticipant, round));
					int ac = ((Number) ctx.get("target_armor_class")).intValue() + 5;
					live.put("target_armor_class", ac);
					live.put("hit", ((Number) ctx.get("natural")).intValue() == 20
							|| ((Number) ctx.get("attack_total")).intValue() >= ac);
				}
				result.put("attack", finishAttack(tx, campaignId, encounter, attacker, target, targetParticipant,
						profile, live, round));
			}
			default -> throw RpgException.invalidArgument("Unknown choice kind " + kind);
			}
			tx.update("pending_transaction", t.id(), Map.of("status", "COMMITTED", "revision", t.lng("revision") + 1));
			List<Map<String, Object>> remaining = pendingChoices(tx, encounter.id());
			result.put("remaining_choices", remaining);
			if (remaining.isEmpty()) {
				tx.update("encounter", encounter.id(), Map.of("status", "RUNNING"));
				if (Boolean.TRUE.equals(payload.get("advance_turn_after"))) {
					result.put("turn_advanced", true);
					result.putAll(advanceTurn(tx, campaignId, tx.get("encounter", encounter.id())));
				}
			}
			Row after = tx.get("encounter", encounter.id());
			tx.update("encounter", after.id(), Map.of("revision", after.lng("revision") + 1));
			result.put("encounter_revision", after.lng("revision") + 1);
			result.put("state", state(tx, tx.get("encounter", encounter.id()), 5));
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return result;
		});
	}

	@SuppressWarnings("unchecked")
	private Optional<Map<String, Object>> creatureAction(Row actor, String name) {
		if (actor.isNull("origin_content_ref")) {
			return Optional.empty();
		}
		// Attacks stay available to a promoted companion only through equipment; see resolveProfile.
		return rules.find(actor.str("origin_content_ref")).flatMap(d -> {
			List<Map<String, Object>> actions = (List<Map<String, Object>>) d.payload().getOrDefault("actions",
					List.of());
			return actions.stream().filter(a -> String.valueOf(a.get("kind")).endsWith("ATTACK")).filter(
					a -> name == null || name.isBlank() || String.valueOf(a.get("name")).equalsIgnoreCase(name.trim()))
					.findFirst();
		});
	}

	private Row carriedWeapon(Tx tx, Row actor, String text) {
		if (text.startsWith(Ref.INVENTORY + ":")) {
			Row e = tx.get("inventory_entry", Ref.id(text, Ref.INVENTORY));
			if (e.lng("character_id") == null || e.lng("character_id") != actor.id()) {
				throw RpgException.invalidArgument(text + " is not carried by " + actor.str("name") + ".");
			}
			return e;
		}
		ContentService.Item item = ContentService.resolveItem(tx, rules, actor.lng("campaign_id"), text);
		if (!item.type().equals("WEAPON")) {
			throw RpgException.validation(
					List.of(new Violation("action.weapon", "NOT_A_WEAPON", item.name() + " is not a weapon.")));
		}
		List<Row> rows = item.custom() ? tx.query(
				"SELECT * FROM inventory_entry WHERE character_id = ? AND custom_content_id = ? ORDER BY equipped DESC, id",
				actor.id(), item.customId())
				: tx.query(
						"SELECT * FROM inventory_entry WHERE character_id = ? AND content_ref = ? ORDER BY equipped DESC, id",
						actor.id(), item.contentRef());
		if (rows.isEmpty()) {
			throw RpgException.validation(List.of(new Violation("action.weapon", "NOT_CARRIED",
					actor.str("name") + " does not carry a " + item.name() + ".")));
		}
		return rows.get(0);
	}

	// ── items in combat ────────────────────────────────────────────────

	private Map<String, Object> useItem(
		Tx tx, long campaignId, Row encounter, Row actor, Map<String, Object> action, long round) {
		String text = action.get("item") == null ? null : action.get("item").toString();
		if (text == null) {
			throw RpgException.invalidArgument(
					"action.item is required for USE_ITEM (e.g. 'Potion of Healing' or 'inventory:12').");
		}
		Row entry = text.startsWith(Ref.INVENTORY + ":") ? tx.get("inventory_entry", Ref.id(text, Ref.INVENTORY))
				: carriedAny(tx, actor, text);
		if (entry.lng("character_id") == null || entry.lng("character_id") != actor.id()) {
			throw RpgException.invalidArgument(text + " is not carried by " + actor.str("name") + ".");
		}
		ContentService.Item item = ContentService.itemForEntry(tx, rules, entry);
		var out = new LinkedHashMap<String, Object>();
		out.put("item", item.display());
		out.put("name", item.name());
		String targetRef = action.get("target") == null ? Ref.of(Ref.CHARACTER, actor.id())
				: action.get("target").toString();
		Row target = CharacterService.character(tx, campaignId, targetRef);
		if (item.contentRef() != null && item.contentRef().equals("srd5e:item/potion-of-healing")) {
			Roll roll = roller.roll("2d4+2");
			CharacterService.recordRoll(tx, campaignId, "potion of healing", roll);
			InventoryService.removeQuantity(tx, entry, 1);
			out.putAll(RuntimeService.heal(tx, target, roll.total(), "Potion of Healing"));
			out.put("roll", roll.toMap());
			out.put("target", Ref.of(Ref.CHARACTER, target.id()));
			log(tx, campaignId, encounter.id(), round, actor.id(), "USE_ITEM", actor.str("name")
					+ " uses a Potion of Healing on " + target.str("name") + ": +" + out.get("healed") + " HP.", null);
		} else {
			boolean consume = action.get("consume") == null || Boolean.TRUE.equals(action.get("consume"));
			if (consume) {
				InventoryService.removeQuantity(tx, entry, 1);
			}
			String desc = action.get("description") == null ? "uses " + item.name()
					: action.get("description").toString();
			log(tx, campaignId, encounter.id(), round, actor.id(), "USE_ITEM", actor.str("name") + " " + desc + ".",
					null);
			out.put("consumed", consume);
			out.put("note", "No mechanical effect is encoded for " + item.name() + "; the GM adjudicates.");
		}
		return out;
	}

	private Row carriedAny(Tx tx, Row actor, String text) {
		ContentService.Item item = ContentService.resolveItem(tx, rules, actor.lng("campaign_id"), text);
		List<Row> rows = item.custom()
				? tx.query("SELECT * FROM inventory_entry WHERE character_id = ? AND custom_content_id = ? ORDER BY id",
						actor.id(), item.customId())
				: tx.query("SELECT * FROM inventory_entry WHERE character_id = ? AND content_ref = ? ORDER BY id",
						actor.id(), item.contentRef());
		if (rows.isEmpty()) {
			throw RpgException.validation(List.of(new Violation("action.item", "NOT_CARRIED",
					actor.str("name") + " does not carry " + item.name() + ".")));
		}
		return rows.get(0);
	}

	/**
	 * Spells such as Sleep, Hold Person and Blindness/Deafness let the target try again at the end
	 * of each of its turns. The instruction rides on the effect (see SpellService), so the engine
	 * rolls it here rather than the GM remembering to - and a second failure escalates where the
	 * spell says it does (SRD 5.2.1 spell descriptions).
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> endOfTurnSaves(Tx tx, long campaignId, Row encounter) {
		if (encounter.lng("turn_participant_id") == null) {
			return null;
		}
		Row p = tx.find("encounter_participant", encounter.lng("turn_participant_id")).orElse(null);
		if (p == null) {
			return null;
		}
		Row c = tx.get("character", p.lng("character_id"));
		var saves = new ArrayList<Map<String, Object>>();
		for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ? AND modifier_json IS NOT NULL",
				c.id())) {
			Map<String, Object> mods = e.map("modifier_json");
			if (!(mods.get("repeat_save") instanceof Map<?, ?> raw)) {
				continue;
			}
			var spec = new LinkedHashMap<String, Object>((Map<String, Object>) raw);
			se.hirt.mcp.rpg.rules.Ability ability = se.hirt.mcp.rpg.rules.Ability
					.parse(String.valueOf(spec.get("ability")));
			int dc = spec.get("dc") instanceof Number n ? n.intValue() : 10;
			int bonus = se.hirt.mcp.rpg.magic.SpellService.saveBonus(tx, rules, c, ability);
			Roll roll = roller.roll("1d20" + (bonus >= 0 ? "+" + bonus : Integer.toString(bonus)));
			CharacterService.recordRoll(tx, campaignId,
					"repeat save " + spec.get("spell") + " " + ability.name() + " " + Ref.of(Ref.CHARACTER, c.id()),
					roll);
			var entry = new LinkedHashMap<String, Object>();
			entry.put("character", Ref.of(Ref.CHARACTER, c.id()));
			entry.put("name", c.str("name"));
			entry.put("spell", spec.get("spell"));
			entry.put("ability", ability.name());
			entry.put("dc", dc);
			entry.put("roll", roll.toMap());
			boolean saved = roll.total() >= dc;
			entry.put("saved", saved);
			if (saved) {
				tx.delete("active_effect", e.id());
				entry.put("effect_ended", true);
				log(tx, campaignId, encounter.id(), encounter.lng("round"), c.id(), "SAVE", c.str("name")
						+ " shakes off " + spec.get("spell") + " (" + roll.total() + " vs DC " + dc + ").", null);
			} else {
				int failures = (spec.get("failures") instanceof Number n ? n.intValue() : 1) + 1;
				spec.put("failures", failures);
				tx.update("active_effect", e.id(), Map.of("modifier_json", Json.write(Map.of("repeat_save", spec))));
				entry.put("failures", failures);
				String escalation = spec.get("on_second_failure") == null ? null
						: String.valueOf(spec.get("on_second_failure"));
				if (failures >= 2 && escalation != null) {
					se.hirt.mcp.rpg.rules.Effects.add(tx, campaignId, c.id(), e.lng("source_character_id"),
							e.str("source_content_ref"), e.str("source_description"),
							RuntimeService.conditionRef(escalation), null,
							e.isNull("duration_json") ? null : e.map("duration_json"),
							e.lng("concentration_character_id"), e.str("source_content_ref") + ":" + escalation,
							"MECHANICAL_CONSEQUENCE");
					entry.put("escalated_to", escalation);
					log(tx, campaignId, encounter.id(), encounter.lng("round"), c.id(), "SAVE",
							c.str("name") + " fails a second save against " + spec.get("spell") + " and is "
									+ escalation.toLowerCase() + ".",
							null);
				}
			}
			saves.add(entry);
		}
		return saves.isEmpty() ? null : Map.of("saves", saves);
	}

	// ── turn advancement ───────────────────────────────────────────────

	/**
	 * Moves to the next participant who can act, rolling death saves for dying party members whose
	 * turn comes up and expiring "until start of turn" effects. Returns the new turn (or none if
	 * nobody can act).
	 */
	private Map<String, Object> advanceTurn(Tx tx, long campaignId, Row encounter) {
		Map<String, Object> ending = endOfTurnSaves(tx, campaignId, encounter);
		List<Row> order = turnOrder(tx, encounter.id());
		int index = 0;
		for (int i = 0; i < order.size(); i++) {
			if (encounter.lng("turn_participant_id") != null
					&& order.get(i).id() == encounter.lng("turn_participant_id")) {
				index = i;
			}
		}
		long round = encounter.lng("round");
		var events = new ArrayList<Map<String, Object>>();
		for (int step = 0; step < order.size() * 2; step++) {
			index = (index + 1) % order.size();
			if (index == 0) {
				round++;
				tx.update("encounter", encounter.id(), Map.of("round", round));
				log(tx, campaignId, encounter.id(), round, null, "ROUND", "Round " + round + " begins.", null);
				se.hirt.mcp.rpg.rules.Effects.expireByRound(tx, encounter.id(), round);
			}
			Row p = tx.get("encounter_participant", order.get(index).id());
			if (!"ACTIVE".equals(p.str("status"))) {
				continue;
			}
			Row c = tx.get("character", p.lng("character_id"));
			// Effects that last until the start of this participant's turn expire now.
			for (Row e : tx.query("SELECT * FROM active_effect WHERE character_id = ? AND duration_json IS NOT NULL",
					c.id())) {
				Map<String, Object> d = e.map("duration_json");
				if ("START_OF_TURN".equals(d.get("until")) && d.get("participant") instanceof Number n
						&& n.longValue() == p.id()) {
					tx.delete("active_effect", e.id());
				}
			}
			if ("DYING".equals(c.str("life_state"))) {
				Map<String, Object> saves = c.isNull("death_saves_json") ? Combat.freshDeathSaves()
						: c.map("death_saves_json");
				if (!Boolean.TRUE.equals(saves.get("stable"))) {
					Roll roll = roller.roll("1d20");
					CharacterService.recordRoll(tx, campaignId, "death saving throw " + Ref.of(Ref.CHARACTER, c.id()),
							roll);
					Map<String, Object> after = Combat.deathSave(roll.total(), saves);
					String outcome = String.valueOf(after.get("outcome"));
					var ev = new LinkedHashMap<String, Object>();
					ev.put("character", Ref.of(Ref.CHARACTER, c.id()));
					ev.put("name", c.str("name"));
					ev.put("death_save", roll.total());
					ev.put("outcome", outcome);
					ev.put("successes", after.get("successes"));
					ev.put("failures", after.get("failures"));
					events.add(ev);
					if (outcome.equals("REGAIN_1_HP")) {
						RuntimeService.heal(tx, c, 1, "natural 20 on a death saving throw");
						log(tx, campaignId, encounter.id(), round, c.id(), "DEATH_SAVE",
								c.str("name") + " rolls a natural 20 on a death save and regains 1 HP!", null);
						c = tx.get("character", c.id());
					} else if (outcome.equals("DEAD")) {
						var cols = new LinkedHashMap<String, Object>();
						cols.put("life_state", "DEAD");
						cols.put("death_saves_json", null);
						cols.put("revision", c.lng("revision") + 1);
						tx.update("character", c.id(), cols);
						for (Row e : tx.query("SELECT id FROM active_effect WHERE character_id = ?", c.id())) {
							tx.delete("active_effect", e.id());
						}
						RuntimeService.endMemberships(tx, campaignId, c.id(), "DEAD");
						tx.update("encounter_participant", p.id(), Map.of("status", "DEFEATED"));
						LedgerService.append(tx, campaignId,
								new LedgerService.EventSpec("CHARACTER_DIED",
										c.str("name") + " died of their wounds (three failed death saves).",
										List.of(c.id()), "CRITICAL", "PARTY_KNOWN", "MECHANICAL_CONSEQUENCE", null,
										c.lng("location_id"), null, null));
						log(tx, campaignId, encounter.id(), round, c.id(), "DEATH_SAVE",
								c.str("name") + " fails a third death save and dies.", null);
						continue;
					} else {
						tx.update("character", c.id(),
								Map.of("death_saves_json", Json.write(after), "revision", c.lng("revision") + 1));
						log(tx, campaignId, encounter.id(), round, c.id(), "DEATH_SAVE",
								c.str("name") + " death save " + roll.total() + ": " + outcome + " ("
										+ after.get("successes") + " successes, " + after.get("failures")
										+ " failures).",
								null);
					}
				}
				if ("DYING".equals(c.str("life_state"))) {
					continue; // unconscious creatures skip their turn
				}
			}
			tx.update("encounter", encounter.id(), Map.of("turn_participant_id", p.id()));
			tx.update("encounter_participant", p.id(), Map.of("reaction_used", 0));
			var out = new LinkedHashMap<String, Object>();
			if (ending != null) {
				out.put("repeat_saves", ending.get("saves"));
			}
			out.put("round", round);
			out.put("next_turn", Map.of("participant", p.id(), "character", Ref.of(Ref.CHARACTER, c.id()), "name",
					c.str("name"), "side", p.str("side")));
			if (!events.isEmpty()) {
				out.put("death_saves", events);
			}
			return out;
		}
		var cols = new LinkedHashMap<String, Object>();
		cols.put("turn_participant_id", null);
		tx.update("encounter", encounter.id(), cols);
		var out = new LinkedHashMap<String, Object>();
		if (ending != null) {
			out.put("repeat_saves", ending.get("saves"));
		}
		out.put("round", round);
		out.put("next_turn", null);
		out.put("note", "Nobody can act; call end_encounter.");
		if (!events.isEmpty()) {
			out.put("death_saves", events);
		}
		return out;
	}

	// ── end_encounter ──────────────────────────────────────────────────

	public Map<String, Object> end(
		String operationId, String campaignRef, String encounterRef, String outcome, String summary) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("encounter", encounterRef);
		args.put("outcome", outcome);
		args.put("summary", summary);
		return db.mutate(Database.Mutation.of("end_encounter", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "end_encounter");
			Row encounter = encounter(tx, campaignId, encounterRef);
			if ("ENDED".equals(encounter.str("status"))) {
				throw RpgException.notAllowed("The encounter already ended.");
			}
			if ("WAITING_CHOICE".equals(encounter.str("status"))) {
				throw RpgException.notAllowed("A pending choice must be resolved before the encounter can end (I-33).");
			}
			String o = outcome == null ? "OTHER" : outcome.toUpperCase();
			if (!OUTCOMES.contains(o)) {
				throw RpgException
						.invalidArgument("outcome must be one of " + OUTCOMES.stream().sorted().toList() + ".");
			}
			Map<String, Object> sides = encounter.map("sides_json");
			String partySide = String.valueOf(sides.get("party_side"));
			@SuppressWarnings("unchecked")
			Map<String, Object> stances = (Map<String, Object>) sides.get("stances");
			List<Row> participants = tx.query("SELECT * FROM encounter_participant WHERE encounter_id = ? ORDER BY id",
					encounter.id());

			// Mechanically defined XP: the pool fixed at start pays out when the encounter is overcome,
			// however it was overcome (RULES_ENGINE.md §6); defeated hostiles are still itemized.
			long defeatedXp = 0;
			var defeated = new ArrayList<String>();
			var recipients = new ArrayList<Row>();
			for (Row p : participants) {
				Row c = tx.get("character", p.lng("character_id"));
				if (p.str("side").equals(partySide)) {
					// Everyone who stood on the party's side earns, class or stat block alike; who the award
					// actually lands on is the campaign's xp_policy (RULES_ENGINE.md §6).
					if (!"DEAD".equals(c.str("life_state"))) {
						recipients.add(c);
					}
				} else if ("DEFEATED".equals(p.str("status")) && hostile(stances, partySide, p.str("side"))) {
					long xp = c.isNull("origin_content_ref") ? 0 : rules.find(c.str("origin_content_ref"))
							.map(d -> d.payload().get("xp_value")).map(v -> ((Number) v).longValue()).orElse(0L);
					defeatedXp += xp;
					defeated.add(c.str("name") + " (" + xp + " XP)");
				}
				var clear = new LinkedHashMap<String, Object>();
				clear.put("encounter_id", null);
				tx.update("character", c.id(), clear);
				for (Row e : tx.query(
						"SELECT id FROM active_effect WHERE character_id = ? AND condition_ref IN (?, ?, ?)", c.id(),
						DODGING_REF, DISENGAGING_REF, SHIELD_SPELL_REF)) {
					tx.delete("active_effect", e.id());
				}
			}
			boolean overcome = Set.of("PARTY_VICTORY", "NEGOTIATED", "ENEMIES_FLED").contains(o);
			long xpPool = !overcome ? 0 : sides.get("xp_pool") instanceof Number pool ? pool.longValue() : defeatedXp;
			var awards = new ArrayList<Map<String, Object>>();
			List<Row> earners = se.hirt.mcp.rpg.progression.PartyXp.recipients(tx, campaign, recipients);
			if (xpPool > 0 && !earners.isEmpty()) {
				long share = xpPool / earners.size();
				for (Row c : earners) {
					awards.add(RuntimeService.grantXp(tx, rules, tx.get("character", c.id()), share));
				}
				awards.addAll(se.hirt.mcp.rpg.progression.PartyXp.lockstep(tx, rules, campaign));
			}
			// Time: six seconds per round.
			long rounds = encounter.lng("round");
			long minutes = Math.max(1, (rounds * SECONDS_PER_ROUND + 59) / 60);
			Row clock = GameTime.clock(tx, campaignId);
			long newSeq = clock.lng("seq") + minutes;
			tx.update("game_clock", clock.id(), Map.of("seq", newSeq, "instant", GameTime.render(newSeq)));
			se.hirt.mcp.rpg.rules.Effects.expireByTime(tx, campaignId, newSeq);
			for (Row p : participants) {
				se.hirt.mcp.rpg.rules.Effects.expireByRound(tx, encounter.id(), Long.MAX_VALUE);
			}

			var cols = new LinkedHashMap<String, Object>();
			cols.put("status", "ENDED");
			cols.put("turn_participant_id", null);
			cols.put("revision", encounter.lng("revision") + 1);
			tx.update("encounter", encounter.id(), cols);
			log(tx, campaignId, encounter.id(), rounds, null, "ENCOUNTER_ENDED",
					"Encounter ends: " + o + (summary == null ? "" : " — " + summary), null);

			// Player character fate decides the harness state.
			Optional<Row> pc = tx.queryOne(
					"SELECT c.* FROM player_control_assignment p JOIN character c ON c.id = p.character_id WHERE p.campaign_id = ? AND p.active = 1",
					campaignId);
			boolean pcDead = pc.isPresent() && "DEAD".equals(pc.get().str("life_state"));
			String policy = campaign.str("continuation_policy");
			HarnessState next = HarnessState.EXPLORATION;
			String campaignStatus = campaign.str("status");
			var continuation = new LinkedHashMap<String, Object>();
			if (pcDead) {
				boolean survivors = tx.count(
						"SELECT COUNT(*) FROM party_membership m JOIN character c ON c.id = m.character_id WHERE m.campaign_id = ? "
								+ "AND m.state IN ('ACTIVE','SEPARATED') AND c.life_state <> 'DEAD' AND c.lifecycle = 'ACTIVE'",
						campaignId) > 0;
				if ("IRONMAN".equals(policy)) {
					if (survivors) {
						next = HarnessState.PLAYER_CHARACTER_TRANSFER;
						continuation.put("options", List.of("transfer_player_control", "complete_campaign"));
					} else {
						next = HarnessState.CAMPAIGN_FAILED;
						campaignStatus = "FAILED";
						continuation.put("options", List.of());
						continuation.put("note", "IRONMAN: no playable character survives; the campaign has ended.");
						LedgerService.append(tx, campaignId,
								new LedgerService.EventSpec("CAMPAIGN_FAILED", "The party was destroyed.", List.of(),
										"CRITICAL", "PARTY_KNOWN", "MECHANICAL_CONSEQUENCE", null,
										encounter.lng("location_id"), null, null));
					}
				} else {
					next = HarnessState.CHECKPOINT_DECISION;
					continuation.put("options",
							survivors ? List.of("restore_checkpoint", "transfer_player_control", "complete_campaign")
									: List.of("restore_checkpoint", "complete_campaign"));
				}
				continuation.put("player_character_status", "DEAD");
				continuation.put("continuation_policy", policy);
			}
			long xpTotal = awards.stream().mapToLong(a -> ((Number) a.get("xp_gained")).longValue()).sum();
			LedgerService.append(tx, campaignId,
					new LedgerService.EventSpec("ENCOUNTER_RESOLVED",
							"Encounter " + o.toLowerCase().replace('_', ' ')
									+ (summary == null || summary.isBlank() ? "" : ": " + summary)
									+ (defeated.isEmpty() ? "" : " Defeated: " + String.join(", ", defeated) + "."),
							participants.stream().map(p -> p.lng("character_id")).toList(),
							pcDead ? "MAJOR" : "NOTABLE", "PARTY_KNOWN", "MECHANICAL_CONSEQUENCE", null,
							encounter.lng("location_id"), null,
							Map.of("outcome", o, "xp_awarded", xpTotal, "rounds", rounds)));
			var ccols = new LinkedHashMap<String, Object>();
			ccols.put("harness_state", next.name());
			ccols.put("status", campaignStatus);
			ccols.put("revision", campaign.lng("revision") + 1);
			tx.update("campaign", campaignId, ccols);

			var result = new LinkedHashMap<String, Object>();
			result.put("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()));
			result.put("outcome", o);
			result.put("rounds", rounds);
			result.put("elapsed_minutes", minutes);
			result.put("defeated", defeated);
			result.put("xp_pool", xpPool);
			result.put("xp_policy", se.hirt.mcp.rpg.progression.PartyXp.policy(campaign));
			result.put("xp_awarded", awards);
			se.hirt.mcp.rpg.progression.LevelUpService.companionAdvancement(tx, rules, roller,
					tx.get("campaign", campaignId), result);
			result.put("level_up_eligible", awards.stream().filter(a -> Boolean.TRUE.equals(a.get("level_up_eligible")))
					.map(a -> a.get("character")).toList());
			result.put("game_time", GameTime.toMap(tx, campaignId, newSeq));
			if (!continuation.isEmpty()) {
				result.put("continuation", continuation);
			}
			var trigger = new LinkedHashMap<String, Object>();
			boolean major = pcDead || xpPool >= 200;
			trigger.put("recommended", major);
			trigger.put("reasons",
					major ? List.of(pcDead ? "PLAYER_CHARACTER_DIED" : "MAJOR_ENCOUNTER_COMPLETED") : List.of());
			trigger.put("urgency", major ? "NORMAL" : "NONE");
			result.put("director_trigger", trigger);
			result.put("note",
					"Loot is not automatic: use grant_loot with source ENCOUNTER for what the fallen carried.");
			String due = se.hirt.mcp.rpg.session.ChronicleService.dueWarning(tx, campaignId);
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), due == null ? null : List.of(due)));
			return result;
		});
	}

	private static boolean hostile(Map<String, Object> stances, String a, String b) {
		Object s = stances.get(a + "|" + b);
		if (s == null) {
			s = stances.get(b + "|" + a);
		}
		return s == null || "HOSTILE".equals(s.toString());
	}

	// ── log ────────────────────────────────────────────────────────────

	static void log(
		Tx tx, long campaignId, long encounterId, long round, Long actorId, String kind, String summary,
		Object payload) {
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("encounter_id", encounterId);
		cols.put("round", round);
		cols.put("actor_character_id", actorId);
		cols.put("kind", kind);
		cols.put("summary", summary);
		cols.put("payload_json", payload == null ? null : Json.write(payload));
		cols.put("journal_id", tx.journalId());
		tx.insert("encounter_log", cols);
	}
}
