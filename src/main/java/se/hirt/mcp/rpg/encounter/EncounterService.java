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
import se.hirt.mcp.rpg.inventory.AmmunitionRecovery;
import se.hirt.mcp.rpg.inventory.MagicItems;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.maps.GridCombat;
import se.hirt.mcp.rpg.maps.SceneService;
import se.hirt.mcp.rpg.party.TacticalControl;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.protocol.Violation;
import se.hirt.mcp.rpg.rules.Combat;
import se.hirt.mcp.rpg.rules.DyingProgression;
import se.hirt.mcp.rpg.rules.Exhaustion;
import se.hirt.mcp.rpg.rules.Rules;
import se.hirt.mcp.rpg.rules.TurnEconomy;
import se.hirt.mcp.rpg.session.GameTime;
import se.hirt.mcp.rpg.world.WorldTimeline;
import se.hirt.mcp.rpg.world.TravelJourneyService;
import se.hirt.mcp.rpg.spatial.Geometry.Footprint;
import se.hirt.mcp.rpg.spatial.Geometry.Point;
import se.hirt.mcp.rpg.spatial.Geometry.Scene;
import se.hirt.mcp.rpg.spatial.SpatialRules;

import java.util.*;

/**
 * The encounter state machine (DESIGN.md §15, DOMAIN_MODEL.md §10, MCP_PROTOCOL.md §15): sides,
 * initiative, rounds and turns owned by the engine; one action resolves atomically (I-32);
 * persisted tactical grid positioning.
 */
public final class EncounterService {

	public static final Set<String> STANCES = Set.of("ALLIED", "HOSTILE", "NEUTRAL", "TEMPORARILY_COOPERATIVE",
			"UNKNOWN");
	public static final Set<String> ACTIONS = Set.of("ATTACK", "CAST", "DODGE", "DASH", "DISENGAGE", "HELP", "HIDE",
			"MOVE", "STAND", "USE_ITEM", "INTERACT", "OTHER_RULES_ACTION", "END_TURN");
	public static final Set<String> OUTCOMES = Set.of("PARTY_VICTORY", "PARTY_DEFEAT", "PARTY_FLED", "ENEMIES_FLED",
			"NEGOTIATED", "OTHER");
	private static final String DODGING_REF = "srd5e:effect/dodging";
	private static final String DISENGAGING_REF = "srd5e:effect/disengaging";
	private static final String SHIELD_SPELL_REF = "srd5e:effect/shield-spell";
	public static final Set<String> NPC_REACTION_POLICIES = Set.of("AUTO", "ASK");
	/**
	 * The kinds that take the turn's Action unless the result says the Bonus Action paid instead.
	 */
	private static final Set<String> ACTION_KINDS = Set.of("ATTACK", "CAST", "DODGE", "DASH", "DISENGAGE", "HELP",
			"HIDE", "USE_ITEM", "OTHER_RULES_ACTION");

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
		List<String> surprised = options == null || options.get("surprised") == null ? List.of()
				: options.get("surprised") instanceof List<?> l ? l.stream().map(String::valueOf).toList()
						: List.of(String.valueOf(options.get("surprised")));
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("options", options);
		args.put("sides", sides);
		args.put("stances", stances);
		args.put("zones", zones);
		args.put("environment", environment);
		args.put("objectives", objectives);
		if (locationRef != null)
			args.put("location", locationRef);
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
			for (String s : surprised) {
				if (!names.contains(s)) {
					throw RpgException.invalidArgument(
							"options.surprised names a side that is not in sides: '" + s + "' (sides: " + names + ").");
				}
			}
			Long locationId = campaign.lng("current_location_id");
			if (locationRef != null && !locationRef.isBlank()) {
				locationId = Ref.id(locationRef, Ref.LOCATION);
			}
			final String encounterPartySide = partySide;
			locationId = GridCombat.encounterLocation(tx, campaignId, locationId,
					participants.stream().map(p -> (Row) p.get("character")).toList(),
					participants.stream().filter(p -> encounterPartySide.equals(p.get("side")))
							.map(p -> Ref.of(Ref.CHARACTER, ((Row) p.get("character")).id())).toList(),
					locationRef != null && !locationRef.isBlank());
			String policy = campaign.str("continuation_policy");
			String retryCheckpoint = null;
			Long retryCheckpointId = null;
			if (!"IRONMAN".equals(policy)) {
				retryCheckpoint = CheckpointService.createInTx(tx, campaignId, "encounter_retry", true);
				retryCheckpointId = Ref.id(retryCheckpoint, Ref.CHECKPOINT);
			}
			var sidesJson = new LinkedHashMap<String, Object>();
			sidesJson.put("names", names);
			sidesJson.put("party_side", partySide);
			sidesJson.put("stances", stanceMap);
			if (!surprised.isEmpty()) {
				sidesJson.put("surprised", surprised);
			}
			// The XP pool is fixed up front: every hostile participant counts when the encounter is
			// overcome, however it is overcome (RULES_ENGINE.md §6).
			long xpPool = 0;
			for (Map<String, Object> p : participants) {
				Row hostileC = (Row) p.get("character");
				if (Combat.hostile(stanceMap, partySide, String.valueOf(p.get("side")))
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
			var environmentState = new LinkedHashMap<String, Object>();
			if (environment != null) {
				environmentState.put("description", environment);
			}
			var gridOptions = new LinkedHashMap<String, Object>(options == null ? Map.of() : options);
			final String deploymentPartySide = partySide;
			gridOptions.put("deployment_party_refs",
					participants.stream().filter(p -> deploymentPartySide.equals(p.get("side")))
							.map(p -> Ref.of(Ref.CHARACTER, ((Row) p.get("character")).id())).toList());
			if (environment != null && !environment.isBlank())
				gridOptions.put("environment_description", environment);
			environmentState.put("grid", GridCombat.ensureEncounterScene(tx, rules, campaignId, locationId,
					participants.stream().map(p -> (Row) p.get("character")).toList(), zones, gridOptions));
			cols.put("environment_json", Json.write(environmentState));
			cols.put("objectives_json", objectives == null ? null : Json.write(objectives));
			cols.put("spatial_model", "GRID");
			cols.put("retry_checkpoint_id", retryCheckpointId);
			cols.put("location_id", locationId);
			cols.put("revision", 0);
			long encounterId = tx.insert("encounter", cols);

			// Initiative: a Dexterity check, d20 + DEX modifier (SRD 5.2.1 "Initiative"). "For a group of identical
			// creatures, the GM makes a single roll, so each member of the group has the same Initiative." Ties are
			// not broken by the engine: "the GM decides the order among tied monsters, and the players decide the
			// order among tied characters"; the provisional order puts the party's side first, then roster order,
			// and the result lists the ties for the table to settle.
			var order = new ArrayList<Map<String, Object>>();
			var groupRolls = new LinkedHashMap<String, Roll>();
			for (Map<String, Object> p : participants) {
				Row c = tx.get("character", ((Row) p.get("character")).id());
				String side = String.valueOf(p.get("side"));
				int dexMod = Rules.modifier(c.intOr("dex_score", 10));
				// Alert (Origin feat): the proficiency bonus is added to initiative (SRD 5.2.1 "Feats").
				int initiativeBonus = dexMod + (se.hirt.mcp.rpg.character.Origins.initiativeProficient(tx, rules, c)
						? RuntimeService.proficiencyBonus(tx, rules, c) : 0);
				initiativeBonus -= Exhaustion.penalty(tx, c.id());
				var reasons = new ArrayList<String>();
				boolean advantage = false;
				boolean disadvantage = false;
				// A surprised side rolls initiative with Disadvantage (SRD 5.2.1 "Surprise").
				boolean isSurprised = surprised.contains(side);
				if (isSurprised) {
					disadvantage = true;
					reasons.add("surprised");
				}
				// "If you're Incapacitated when you roll Initiative, you have Disadvantage on the roll" and "If you're
				// Invisible when you roll Initiative, you have Advantage on the roll" (SRD 5.2.1 Conditions).
				Optional<String> incapacitated = conditionAmong(tx, c, Combat.INCAPACITATING_CONDITIONS);
				if (incapacitated.isPresent()) {
					disadvantage = true;
					reasons.add(incapacitated.get().toLowerCase() + " (Incapacitated)");
				}
				if (conditionAmong(tx, c, List.of("INVISIBLE")).isPresent()) {
					advantage = true;
					reasons.add("invisible");
				}
				// Untrained armor: Disadvantage on any D20 Test involving Dexterity (SRD 5.2.1 "Armor Training").
				Optional<String> untrainedArmor = se.hirt.mcp.rpg.rules.Training.untrainedArmor(tx, rules, c);
				if (untrainedArmor.isPresent()) {
					disadvantage = true;
					reasons.add("wearing " + untrainedArmor.get() + " without armor training");
				}
				String dice = advantage && !disadvantage ? "2d20kh1" : disadvantage && !advantage ? "2d20kl1" : "1d20";
				String expression = dice
						+ (initiativeBonus >= 0 ? "+" + initiativeBonus : Integer.toString(initiativeBonus));
				// Identical creatures (the same stat block on the same side, rolling the same way) share one roll.
				String group = RuntimeService.usesStatBlock(tx, c)
						? side + "|" + c.str("origin_content_ref") + "|" + expression : null;
				boolean sharedRoll = group != null && groupRolls.containsKey(group);
				Roll roll;
				if (sharedRoll) {
					roll = groupRolls.get(group);
				} else {
					roll = roller.roll(expression);
					CharacterService.recordRoll(tx, campaignId, "initiative " + Ref.of(Ref.CHARACTER, c.id()), roll);
					if (group != null) {
						groupRolls.put(group, roll);
					}
				}
				var pc = new LinkedHashMap<String, Object>();
				pc.put("encounter_id", encounterId);
				pc.put("character_id", c.id());
				pc.put("side", side);
				pc.put("initiative", roll.total());
				// No hidden tie-break: a stable provisional order only (the party's side first, then roster order).
				pc.put("initiative_tiebreak", side.equals(partySide) ? 1 : 0);
				pc.put("position_zone", zones == null || zones.get(Ref.of(Ref.CHARACTER, c.id())) == null ? "near"
						: zones.get(Ref.of(Ref.CHARACTER, c.id())).toString());
				pc.put("status", "ACTIVE");
				pc.put("once_per_turn_json", Json.write(Map.of("party_member_at_start",
						RuntimeService.isPartyMember(tx, campaignId, c.id()), "party_membership_at_start",
						tx.queryOne(
								"SELECT state FROM party_membership WHERE campaign_id=? AND character_id=? AND state IN ('ACTIVE','SEPARATED','GUEST') ORDER BY id DESC LIMIT 1",
								campaignId, c.id()).map(m -> m.str("state")).orElse("NON_PARTY"))));
				long participantId = tx.insert("encounter_participant", pc);
				tx.update("character", c.id(), Map.of("encounter_id", encounterId, "revision", c.lng("revision") + 1));
				var o = new LinkedHashMap<String, Object>();
				o.put("participant", participantId);
				o.put("initiative", roll.total());
				o.put("name", c.str("name"));
				o.put("roll", roll.toMap());
				o.put("advantage", advantage && !disadvantage ? "ADVANTAGE"
						: disadvantage && !advantage ? "DISADVANTAGE" : "NONE");
				if (!reasons.isEmpty()) {
					o.put("advantage_reasons", reasons);
				}
				if (isSurprised) {
					o.put("surprised", true);
				}
				if (sharedRoll) {
					o.put("shared_roll", "one roll for a group of identical creatures (SRD 5.2.1 \"Initiative\")");
				}
				order.add(o);
			}
			List<Row> ordered = turnOrder(tx, encounterId);
			List<Map<String, Object>> ties = initiativeTies(tx, ordered, partySide);
			long first = ordered.get(0).id();
			tx.update("encounter", encounterId, Map.of("turn_participant_id", first, "revision", 1));
			log(tx, campaignId, encounterId, 1, null, "ENCOUNTER_STARTED",
					"Encounter begins. Initiative: " + ordered.stream().map(
							r -> tx.get("character", r.lng("character_id")).str("name") + " " + r.lng("initiative"))
							.toList(),
					null);
			Set<Long> observedAtStart = GridCombat.knownToParty(tx, tx.get("encounter", encounterId));
			LedgerService.append(tx, campaignId,
					new LedgerService.EventSpec("ENCOUNTER_STARTED",
							"An encounter began" + (environment == null ? "" : " (" + environment + ")") + ".",
							participants.stream().map(p -> ((Row) p.get("character")).id())
									.filter(observedAtStart::contains).toList(),
							"NOTABLE", "PARTY_KNOWN", "GM", null, locationId, null,
							Map.of("encounter", Ref.of(Ref.ENCOUNTER, encounterId))));
			tx.update("campaign", campaignId, Map.of("harness_state", HarnessState.ENCOUNTER.name(), "revision",
					tx.get("campaign", campaignId).lng("revision") + 1));
			tx.touched(Ref.of(Ref.ENCOUNTER, encounterId), 1);
			Map<String, Object> state = state(tx, tx.get("encounter", encounterId), 10);
			state.put("retry_checkpoint", retryCheckpoint);
			Set<Long> knownAtStart = GridCombat.knownToParty(tx, tx.get("encounter", encounterId));
			Set<Long> knownParticipants = new HashSet<>();
			for (Row p : ordered)
				if (knownAtStart.contains(p.lng("character_id")))
					knownParticipants.add(p.id());
			state.put("initiative_rolls",
					order.stream()
							.filter(roll -> knownParticipants.contains(((Number) roll.get("participant")).longValue()))
							.toList());
			ties = ties.stream().map(tie -> {
				var filtered = new LinkedHashMap<String, Object>(tie);
				filtered.put("participants",
						SceneService.maps(tie.get("participants")).stream().filter(
								member -> knownParticipants.contains(((Number) member.get("participant")).longValue()))
								.toList());
				return (Map<String, Object>) filtered;
			}).filter(tie -> SceneService.maps(tie.get("participants")).size() > 1).toList();
			if (!ties.isEmpty()) {
				state.put("ties", ties);
				state.put("ties_note",
						"Tied Initiative is not broken by the engine (SRD 5.2.1 \"Initiative\"): the GM decides the order among tied monsters and between a monster and a player character, the players among tied characters. The provisional order lists the party's side first; call perform_encounter_action in the agreed order.");
			}
			state.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return state;
		});
	}

	/** Admit finite, already placed reinforcements without resetting any existing combat state. */
	public Map<String, Object> addParticipants(
		String operationId, String campaignRef, String encounterRef, Map<String, List<String>> sides) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(Database.Mutation.of("add_encounter_participants", campaignId, operationId, "GM",
				Map.of("encounter", encounterRef, "sides", sides == null ? Map.of() : sides)), tx -> {
					Harness.requireMutation(tx, campaignRef, "add_encounter_participants");
					Row encounter = encounter(tx, campaignId, encounterRef);
					if ("WAITING_CHOICE".equals(encounter.str("status"))
							|| !pendingChoices(tx, encounter.id()).isEmpty())
						throw RpgException.notAllowed("Resolve the pending choice, then retry adding reinforcements.");
					if (!"RUNNING".equals(encounter.str("status")))
						throw RpgException.notAllowed("Reinforcements can join only a running encounter.");
					Row scene = GridCombat.scene(tx, encounter);
					if (sides == null || sides.isEmpty())
						throw RpgException.invalidArgument(
								"sides must list the existing sides and their arriving character references.");
					Map<String, Object> sideState = new LinkedHashMap<>(encounter.map("sides_json"));
					List<?> sideNames = (List<?>) sideState.get("names");
					String partySide = String.valueOf(sideState.get("party_side"));
					Map<String, Object> stances = SceneService.map(sideState.get("stances"));
					var arrivals = new LinkedHashMap<Long, String>();
					for (var entry : sides.entrySet()) {
						if (!sideNames.contains(entry.getKey()))
							throw RpgException
									.invalidArgument("Reinforcements must join an existing side: " + entry.getKey());
						if (entry.getValue() == null || entry.getValue().isEmpty())
							throw RpgException.invalidArgument("Each arriving side must list at least one character.");
						for (String ref : entry.getValue()) {
							Row actor = CharacterService.character(tx, campaignId, ref);
							if (!"ACTIVE".equals(actor.str("lifecycle")) || !"ALIVE".equals(actor.str("life_state")))
								throw RpgException.notAllowed("Only living active characters can join the encounter.");
							if (arrivals.putIfAbsent(actor.id(), entry.getKey()) != null)
								throw RpgException
										.invalidArgument("An arriving character appears more than once: " + ref);
							if (!actor.isNull("encounter_id") || tx.count(
									"SELECT COUNT(*) FROM encounter_participant WHERE encounter_id=? AND character_id=?",
									encounter.id(), actor.id()) > 0)
								throw RpgException.notAllowed(ref + " is already assigned to an encounter.");
							Row placement = SceneService.actor(tx, campaignId, ref);
							if (placement.lng("scene_id") != scene.id())
								throw RpgException.notAllowed(
										"Move arriving actors onto the encounter's saved coverage before adding them; reinforcement admission does not teleport figurines.");
							if (!SpatialRules.canPlace(
									SceneService.geometry(SceneService.document(tx, scene),
											placement.intOr("level", 0)),
									SceneService.footprint(placement),
									GridCombat.occupied(tx, scene.id(), placement.intOr("level", 0), actor.id())))
								throw RpgException
										.notAllowed("The arriving character's current placement is obstructed.");
						}
					}
					if (arrivals.size() > 128)
						throw RpgException
								.invalidArgument("Add at most 128 existing actors in one reinforcement operation.");
					long xp = SceneService.number(sideState, "xp_pool", 0).longValue();
					var groupRolls = new HashMap<String, Roll>();
					var initiative = new ArrayList<Map<String, Object>>();
					for (var arrival : arrivals.entrySet()) {
						Row actor = tx.get("character", arrival.getKey());
						String side = arrival.getValue();
						int bonus = Rules.modifier(actor.intOr("dex_score", 10))
								+ (se.hirt.mcp.rpg.character.Origins.initiativeProficient(tx, rules, actor)
										? RuntimeService.proficiencyBonus(tx, rules, actor) : 0)
								- Exhaustion.penalty(tx, actor.id());
						boolean advantage = conditionAmong(tx, actor, List.of("INVISIBLE")).isPresent();
						boolean disadvantage = conditionAmong(tx, actor, Combat.INCAPACITATING_CONDITIONS).isPresent()
								|| se.hirt.mcp.rpg.rules.Training.untrainedArmor(tx, rules, actor).isPresent();
						String dice = advantage && !disadvantage ? "2d20kh1"
								: disadvantage && !advantage ? "2d20kl1" : "1d20";
						String expression = dice + (bonus >= 0 ? "+" + bonus : Integer.toString(bonus));
						String group = RuntimeService.usesStatBlock(tx, actor)
								? side + "|" + actor.str("origin_content_ref") + "|" + expression : null;
						Roll roll = group == null ? null : groupRolls.get(group);
						boolean shared = roll != null;
						if (roll == null) {
							roll = roller.roll(expression);
							CharacterService.recordRoll(tx, campaignId,
									"reinforcement initiative " + Ref.of(Ref.CHARACTER, actor.id()), roll);
							if (group != null)
								groupRolls.put(group, roll);
						}
						long id = tx.insert("encounter_participant",
								Map.of("encounter_id", encounter.id(), "character_id", actor.id(), "side", side,
										"initiative", roll.total(), "initiative_tiebreak",
										partySide.equals(side) ? 1 : 0, "status", "ACTIVE"));
						tx.update("character", actor.id(),
								Map.of("encounter_id", encounter.id(), "revision", actor.lng("revision") + 1));
						initiative.add(Map.of("participant", id, "character", Ref.of(Ref.CHARACTER, actor.id()),
								"initiative", roll.total(), "roll", roll.toMap(), "shared_roll", shared));
						if (Combat.hostile(stances, partySide, side) && !actor.isNull("origin_content_ref"))
							xp += rules.find(actor.str("origin_content_ref")).map(d -> d.payload().get("xp_value"))
									.map(v -> ((Number) v).longValue()).orElse(0L);
						log(tx, campaignId, encounter.id(), encounter.lng("round"), actor.id(), "REINFORCEMENT",
								actor.str("name") + " joins the encounter at their existing position.", null);
					}
					sideState.put("xp_pool", xp);
					tx.update("encounter", encounter.id(),
							Map.of("sides_json", Json.write(sideState), "revision", encounter.lng("revision") + 1));
					SceneService.remember(tx, scene);
					Set<Long> known = GridCombat.knownToParty(tx, tx.get("encounter", encounter.id()));
					var result = new LinkedHashMap<String, Object>();
					result.put("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()));
					result.put("applied", true);
					result.put("initiative_rolls",
							initiative.stream()
									.filter(r -> known.contains(Ref.id(r.get("character").toString(), Ref.CHARACTER)))
									.toList());
					result.put("state", state(tx, tx.get("encounter", encounter.id()), 10));
					result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
					return result;
				});
	}

	/**
	 * Groups of participants sharing an Initiative count, with who decides their order (SRD 5.2.1).
	 */
	private static List<Map<String, Object>> initiativeTies(Tx tx, List<Row> ordered, String partySide) {
		var ties = new ArrayList<Map<String, Object>>();
		var byInitiative = new LinkedHashMap<Long, List<Row>>();
		for (Row p : ordered) {
			byInitiative.computeIfAbsent(p.lng("initiative"), k -> new ArrayList<>()).add(p);
		}
		for (var e : byInitiative.entrySet()) {
			if (e.getValue().size() < 2) {
				continue;
			}
			var members = new ArrayList<Map<String, Object>>();
			boolean allParty = true;
			for (Row p : e.getValue()) {
				Row c = tx.get("character", p.lng("character_id"));
				var m = new LinkedHashMap<String, Object>();
				m.put("participant", p.id());
				m.put("character", Ref.of(Ref.CHARACTER, c.id()));
				m.put("name", c.str("name"));
				m.put("side", p.str("side"));
				members.add(m);
				allParty &= partySide.equals(p.str("side"));
			}
			var tie = new LinkedHashMap<String, Object>();
			tie.put("initiative", e.getKey());
			tie.put("participants", members);
			tie.put("decided_by", allParty ? "PLAYERS" : "GM");
			ties.add(tie);
		}
		return ties;
	}

	// ── get_encounter_state ────────────────────────────────────────────

	public Map<String, Object> encounterState(String campaignRef, String encounterRef, int logLimit) {
		return encounterState(campaignRef, encounterRef, logLimit, "PLAYER");
	}

	public Map<String, Object> encounterState(String campaignRef, String encounterRef, int logLimit, String audience) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Row encounter = encounter(tx, campaign.id(), encounterRef);
			Map<String, Object> state = state(tx, encounter, Math.max(1, Math.min(logLimit <= 0 ? 10 : logLimit, 100)),
					audience);
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
		return state(tx, encounter, logLimit, "PLAYER");
	}

	public Map<String, Object> state(Tx tx, Row encounter, int logLimit, String audience) {
		if (!Set.of("GM", "PLAYER").contains(audience.toUpperCase(Locale.ROOT))) {
			throw RpgException.invalidArgument("Encounter audience must be GM or PLAYER.");
		}
		boolean gm = "GM".equalsIgnoreCase(audience);
		if ("ENDED".equals(encounter.str("status")))
			return historicalState(tx, encounter, logLimit, gm);
		Set<Long> known = GridCombat.knownToParty(tx, encounter);
		Map<String, Object> sides = encounter.map("sides_json");
		String partySide = String.valueOf(sides.get("party_side"));
		var m = new LinkedHashMap<String, Object>();
		m.put("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()));
		m.put("status", encounter.str("status"));
		m.put("round", encounter.lng("round"));
		m.put("audience", gm ? "GM" : "PLAYER");
		var visibleSides = new LinkedHashMap<String, Object>(sides);
		if (!gm) {
			if (known.size() < turnOrder(tx, encounter.id()).size())
				visibleSides.remove("xp_pool");
			Set<String> names = new LinkedHashSet<>();
			for (Row p : turnOrder(tx, encounter.id())) {
				if (known.contains(p.lng("character_id")))
					names.add(p.str("side"));
			}
			visibleSides.put("names", List.copyOf(names));
			if (sides.get("surprised") instanceof List<?> surprised)
				visibleSides.put("surprised", surprised.stream().filter(names::contains).toList());
			var stances = new LinkedHashMap<String, Object>();
			SceneService.map(sides.get("stances")).forEach((key, value) -> {
				String[] pair = key.split("\\|", 2);
				if (pair.length == 2 && names.contains(pair[0]) && names.contains(pair[1]))
					stances.put(key, value);
			});
			visibleSides.put("stances", stances);
		}
		m.put("sides", visibleSides);
		m.put("spatial_model", encounter.str("spatial_model"));
		if (GridCombat.isGrid(encounter))
			m.put("grid", GridCombat.binding(encounter));
		m.put("environment",
				encounter.isNull("environment_json") ? null : encounter.map("environment_json").get("description"));
		m.put("objectives", encounter.isNull("objectives_json") ? List.of() : encounter.list("objectives_json"));
		Long turnId = encounter.lng("turn_participant_id");
		var participants = new ArrayList<Map<String, Object>>();
		for (Row p : turnOrder(tx, encounter.id())) {
			Row c = tx.get("character", p.lng("character_id"));
			if (!gm && !known.contains(c.id()))
				continue;
			var pm = new LinkedHashMap<String, Object>();
			pm.put("participant", p.id());
			pm.put("character", Ref.of(Ref.CHARACTER, c.id()));
			pm.put("name", c.str("name"));
			pm.put("side", p.str("side"));
			String membership = tx.queryOne(
					"SELECT state FROM party_membership WHERE campaign_id=? AND character_id=? ORDER BY id DESC LIMIT 1",
					encounter.lng("campaign_id"), c.id()).map(member -> member.str("state")).orElse("NON_PARTY");
			boolean primary = tx.count(
					"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id=? AND character_id=? AND active=1",
					encounter.lng("campaign_id"), c.id()) > 0;
			pm.put("membership", membership);
			pm.put("role", primary ? "PLAYER_CHARACTER"
					: Set.of("ACTIVE", "SEPARATED", "GUEST").contains(membership)
							|| Boolean.TRUE.equals(p.map("once_per_turn_json").get("party_member_at_start"))
									? "COMPANION" : p.str("side").equals(partySide) ? "ENCOUNTER_ALLY" : "NON_PARTY");
			if (!RuntimeService.careGuidance(c).isEmpty())
				pm.put("care", RuntimeService.careGuidance(c));
			pm.put("controller",
					TacticalControl.playerControlled(tx, encounter.lng("campaign_id"), c.id()) ? "PLAYER" : "DM");
			pm.put("tactical_control", TacticalControl.assignment(tx, encounter.lng("campaign_id"), c.id()));
			pm.put("initiative", p.lng("initiative"));
			if (GridCombat.isGrid(encounter)) {
				Row placement = GridCombat.actor(tx, encounter, c.id());
				pm.put("position_ft", List.of(placement.get("x_ft"), placement.get("y_ft")));
				pm.put("level", placement.intOr("level", 0));
				pm.put("footprint_ft", placement.get("footprint_ft"));
				pm.put("movement", GridCombat.movementView(tx, rules, encounter, p, c));
			} else
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
			pm.put("action_available", TurnEconomy.actionAvailable(tx, encounter, c.id()));
			pm.put("bonus_action_available", TurnEconomy.bonusActionAvailable(tx, encounter, c.id()));
			List<String> lightWeapons = lightExtraWeapons(tx, encounter, c);
			if (!lightWeapons.isEmpty()) {
				pm.put("light_extra_attack_available", lightWeapons);
			}
			pm.put("attacks", attackOptions(tx, c));
			participants.add(pm);
		}
		m.put("participants", participants);
		if (gm && "ENDED".equals(encounter.str("status")) && !encounter.map("record_json").isEmpty())
			m.put("record", EncounterDigest.gmRecord(encounter.map("record_json")));
		Map<String, Object> current = turnId == null ? null : participants.stream()
				.filter(p -> Boolean.TRUE.equals(p.get("current_turn"))).findFirst().orElse(null);
		m.put("turn", current);
		if (!gm && turnId != null && current == null)
			m.put("gm_handoff", gmHandoff(encounter));
		boolean playerTurn = current != null && "PLAYER".equals(current.get("controller"));
		m.put("awaiting_player_input", playerTurn);
		if (turnId != null) {
			m.put("awaiting_gm", !playerTurn);
			m.put("turn_guidance", playerTurn
					? "Use the player's declared tactical decision for this character; ask only for meaningful unspecified choices."
					: "The DM resolves this turn from the actor's own perception, personality and applicable player orders.");
		}
		m.put("recent_log",
				tx.query("SELECT * FROM encounter_log WHERE encounter_id = ? ORDER BY id DESC LIMIT ?", encounter.id(),
						logLimit).stream()
						.filter(log -> gm || log.lng("actor_character_id") == null
								|| known.contains(log.lng("actor_character_id")))
						.filter(log -> gm || !"DEATH_SAVE".equals(log.str("kind"))
								|| !Boolean.FALSE.equals(log.map("payload_json").get("party_observed")))
						.map(log -> filteredLogEntry(tx, encounter, log, known, gm)).toList());
		List<Map<String, Object>> allPending = pendingChoices(tx, encounter.id());
		List<Map<String, Object>> pending = allPending;
		if (!gm)
			pending = pending.stream()
					.filter(choice -> known.contains(Ref.id(choice.get("chooser").toString(), Ref.CHARACTER)))
					.map(choice -> playerChoice(tx, encounter, choice, known)).toList();
		if (!gm && pending.size() < allPending.size())
			m.put("gm_handoff", gmHandoff(encounter));
		m.put("pending_choices", pending);
		if (!allPending.isEmpty()) {
			boolean playerDecision = pending.stream()
					.anyMatch(choice -> Boolean.TRUE.equals(choice.get("player_controlled")));
			m.put("awaiting_player_input", playerDecision);
			m.put("awaiting_gm", !playerDecision);
			m.put("turn_guidance",
					playerDecision ? "Resolve the player's offered reaction before continuing the suspended action."
							: "The DM resolves the existing reaction choice before the suspended action continues.");
		}
		if (m.containsKey("gm_handoff"))
			m.put("turn_guidance",
					"The current actor is outside party sight. The DM can read the full turn using get_encounter_state with audience GM; follow gm_handoff. Player knowledge is unchanged.");
		m.put("npc_reactions", encounter.str("npc_reactions"));
		m.put("legal_actions",
				allPending.isEmpty() ? ACTIONS.stream().filter(action -> !"HIDE".equals(action)).sorted().toList()
						: List.of("resolve_pending_choice"));
		m.put("unsupported_actions", Map.of("HIDE",
				"Requires the shared Stealth, concealment and observer discovery lifecycle; resolve_check supports a GM-adjudicated Stealth check."));
		m.put("revision", encounter.lng("revision"));
		List<Map<String, Object>> recovery = AmmunitionRecovery.offers(tx, rules, encounter);
		if (!recovery.isEmpty()) {
			m.put("ammunition_recovery", recovery);
		}
		return m;
	}

	/**
	 * Historical reads use saved end-time facts, never the actors' present boards or later
	 * knowledge.
	 */
	private Map<String, Object> historicalState(Tx tx, Row encounter, int logLimit, boolean gm) {
		Map<String, Object> stored = encounter.map("record_json"),
				digest = SceneService.map(stored.get("party_digest"));
		List<Map<String, Object>> roster = SceneService.maps((gm ? stored : digest).get("final_roster"));
		String partySide = String.valueOf(encounter.map("sides_json").get("party_side"));
		boolean hasSnapshot = (gm ? stored : digest).containsKey("final_roster");
		var participants = new ArrayList<Map<String, Object>>();
		for (Row p : turnOrder(tx, encounter.id())) {
			String ref = Ref.of(Ref.CHARACTER, p.lng("character_id"));
			Map<String, Object> saved = roster.stream().filter(person -> ref.equals(person.get("character")))
					.findFirst().orElse(null);
			if (saved == null && (hasSnapshot || !gm && !partySide.equals(p.str("side"))))
				continue;
			var person = new LinkedHashMap<String, Object>();
			person.put("participant", p.id());
			person.put("character", ref);
			person.put("side", p.str("side"));
			person.put("initiative", p.lng("initiative"));
			person.put("current_turn", false);
			if (saved != null) {
				person.putAll(saved);
				if (!gm)
					SceneService.maps(stored.get("party_final_positions")).stream()
							.filter(position -> ref.equals(position.get("character"))).findFirst()
							.ifPresent(position -> {
								person.putAll(position);
								person.put("position_status", "ENCOUNTER_END");
							});
				if (saved.containsKey("current_hp")) {
					var hp = new LinkedHashMap<String, Object>();
					hp.put("current", saved.get("current_hp"));
					hp.put("temp", saved.get("temp_hp"));
					person.put("hp", hp);
				}
			} else {
				person.put("name", tx.get("character", p.lng("character_id")).str("name"));
				person.put("status", p.str("status"));
				person.put("snapshot_status", "UNAVAILABLE");
			}
			participants.add(person);
		}
		var out = new LinkedHashMap<String, Object>();
		out.put("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()));
		out.put("status", "ENDED");
		out.put("round", encounter.lng("round"));
		out.put("audience", gm ? "GM" : "PLAYER");
		out.put("historical", true);
		out.put("snapshot_status", hasSnapshot ? "ENCOUNTER_END" : "UNAVAILABLE");
		boolean savedPositions = "ENCOUNTER_END".equals(stored.get("position_status"));
		out.put("position_status", savedPositions ? "ENCOUNTER_END" : "NOT_RECORDED");
		out.put("note", savedPositions
				? "This is a saved encounter record. Final positions are frozen at encounter end."
						+ (gm ? "" : " Includes the party's own final positions and other positions seen at that time.")
				: "This is a saved encounter record. Exact final grid positions were not recorded; current figurines and later character changes are not historical evidence.");
		out.put("spatial_model", encounter.str("spatial_model"));
		if (GridCombat.isGrid(encounter))
			out.put("grid", GridCombat.binding(encounter));
		out.put("sides", gm ? encounter.map("sides_json") : Map.of("party_side", partySide, "names",
				participants.stream().map(person -> person.get("side")).distinct().toList()));
		if (stored.get("outcome") != null)
			out.put("outcome", stored.get("outcome"));
		if (stored.get("resolved_event") != null)
			out.put("resolved_event", stored.get("resolved_event"));
		out.put("participants", participants);
		out.put("turn", null);
		out.put("awaiting_player_input", false);
		out.put("awaiting_gm", false);
		out.put("pending_choices", List.of());
		out.put("legal_actions", List.of());
		out.put("revision", encounter.lng("revision"));
		if (gm) {
			if (!stored.isEmpty())
				out.put("record", EncounterDigest.gmRecord(stored));
			out.put("recent_log", tx.query("SELECT * FROM encounter_log WHERE encounter_id=? ORDER BY id DESC LIMIT ?",
					encounter.id(), logLimit).stream().map(EncounterService::logEntry).toList());
		} else {
			out.put("digest", digest);
			// Only the saved, already filtered highlights are safe after the party has left the scene.
			out.put("recent_log", SceneService.maps(digest.get("highlights")).stream().limit(logLimit).toList());
			out.put("log_scope", "SAVED_PARTY_KNOWN_HIGHLIGHTS");
		}
		List<Map<String, Object>> recovery = AmmunitionRecovery.offers(tx, rules, encounter);
		if (!recovery.isEmpty())
			out.put("ammunition_recovery", recovery);
		return out;
	}

	private static Map<String, Object> gmHandoff(Row encounter) {
		return Map.of("tool", "get_encounter_state", "arguments",
				Map.of("campaign", Ref.of(Ref.CAMPAIGN, encounter.lng("campaign_id")), "encounter",
						Ref.of(Ref.ENCOUNTER, encounter.id()), "audience", "GM"),
				"read_only", true, "purpose",
				"DM decision context for the full encounter; do not narrate unseen identities or positions to the player.");
	}

	private static Map<String, Object> filteredLogEntry(Tx tx, Row encounter, Row row, Set<Long> known, boolean gm) {
		Map<String, Object> entry = logEntry(row);
		if (!gm) {
			entry.remove("narration");
			String summary = String.valueOf(entry.get("summary"));
			for (Row p : tx.query("SELECT * FROM encounter_participant WHERE encounter_id=?", encounter.id())) {
				if (!known.contains(p.lng("character_id"))) {
					String name = tx.get("character", p.lng("character_id")).str("name");
					if (name != null && !name.isBlank())
						summary = summary.replace(name, "an unseen creature");
				}
			}
			entry.put("summary", "ENCOUNTER_STARTED".equals(row.str("kind")) ? "An encounter begins." : summary);
		}
		return entry;
	}

	/**
	 * Project offered decisions only; their stored context remains authoritative for resolution.
	 */
	private static Map<String, Object> playerChoice(Tx tx, Row encounter, Map<String, Object> choice, Set<Long> known) {
		var unseen = new LinkedHashMap<String, String>();
		for (Row participant : tx.query("SELECT * FROM encounter_participant WHERE encounter_id=?", encounter.id())) {
			long id = participant.lng("character_id");
			if (!known.contains(id))
				unseen.put(Ref.of(Ref.CHARACTER, id), tx.get("character", id).str("name"));
		}
		var projected = new LinkedHashMap<>(choice);
		if (choice.get("prompt") instanceof String prompt)
			projected.put("prompt", redactChoiceText(prompt, unseen));
		var options = new ArrayList<Map<String, Object>>();
		for (Map<String, Object> option : SceneService.maps(choice.get("options"))) {
			var visibleOption = new LinkedHashMap<>(option);
			if (option.get("description") instanceof String description)
				visibleOption.put("description", redactChoiceText(description, unseen));
			options.add(visibleOption);
		}
		projected.put("options", options);
		return projected;
	}

	/** Human text only: names must never rewrite transaction, option or controller tokens. */
	private static String redactChoiceText(String text, Map<String, String> unseen) {
		for (var hidden : unseen.entrySet()) {
			text = text.replaceAll(java.util.regex.Pattern.quote(hidden.getKey()) + "(?![0-9])", "an unseen creature");
			if (hidden.getValue() != null && !hidden.getValue().isBlank())
				text = text.replace(hidden.getValue(), "an unseen creature");
		}
		return text;
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
		boolean player = playerControlled(tx, t.lng("campaign_id"), chooser);
		m.put("player_controlled", player);
		m.put("decision_controller", player ? "PLAYER" : "DM");
		m.put("tactical_control", TacticalControl.assignment(tx, t.lng("campaign_id"), chooser));
		m.put("awaiting_player_input", player);
		m.put("decision_guidance", player ? "Use the player's reaction decision, then resolve this existing choice."
				: "The DM decides this existing reaction choice; changing control does not resolve or discard it.");
		m.put("prompt", payload.get("prompt"));
		m.put("options", payload.get("options"));
		m.put("mandatory", true);
		m.put("resolve_with", "resolve_pending_choice");
		return m;
	}

	private static Map<String, Object> logEntry(Row r) {
		var m = new LinkedHashMap<String, Object>();
		m.put("log_id", r.id());
		m.put("round", r.lng("round"));
		m.put("kind", r.str("kind"));
		m.put("actor", Ref.ofNullable(Ref.CHARACTER, r.lng("actor_character_id")));
		m.put("summary", r.str("summary"));
		if (r.str("narration") != null)
			m.put("narration", r.str("narration"));
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
		String operationId, String campaignRef, String encounterRef, String actorRef, Map<String, Object> action) {
		return perform(operationId, campaignRef, encounterRef, actorRef, action, null);
	}

	public Map<String, Object> perform(
		String operationId, String campaignRef, String encounterRef, String actorRef, Map<String, Object> action,
		Boolean endTurn) {
		EncounterDigest.validateNarration(action);
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("encounter", encounterRef);
		args.put("actor", actorRef);
		args.put("action", action);
		args.put("end_turn", endTurn);
		return db.mutate(Database.Mutation.of("perform_encounter_action", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "perform_encounter_action");
			Row opened = encounter(tx, campaignId, encounterRef);
			// A turn pointer left on someone who can no longer act (killed out of turn, removed by fiat, or
			// never set) moves on before the actor is judged, so the fight cannot wedge on a corpse.
			List<String> turnsSkipped = "RUNNING".equals(opened.str("status"))
					? skipTurnsNobodyCanTake(tx, campaignId, opened) : List.of();
			Row encounter = turnsSkipped.isEmpty() ? opened : tx.get("encounter", opened.id());
			if ("WAITING_CHOICE".equals(encounter.str("status"))) {
				throw RpgException.notAllowed("A pending choice must be resolved first (resolve_pending_choice): "
						+ pendingChoices(tx, encounter.id()).stream()
								.map(c -> c.get("transaction") + " for " + c.get("chooser_name")).toList());
			}
			if (!"RUNNING".equals(encounter.str("status"))) {
				throw RpgException.notAllowed("The encounter is " + encounter.str("status") + ".");
			}
			Row actor = CharacterService.character(tx, campaignId, actorRef);
			GridCombat.scene(tx, encounter);
			Row participant = tx
					.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
							encounter.id(), actor.id())
					.orElseThrow(() -> RpgException.invalidArgument(actorRef + " is not part of this encounter."));
			if (action == null || action.get("kind") == null) {
				throw RpgException.invalidArgument("action.kind is required: " + ACTIONS.stream().sorted().toList());
			}
			String kind = String.valueOf(action.get("kind")).toUpperCase();
			if (action.containsKey("light_extra_attack") && !(action.get("light_extra_attack") instanceof Boolean)) {
				throw RpgException.invalidArgument("action.light_extra_attack must be a boolean.");
			}
			boolean lightExtra = Boolean.TRUE.equals(action.get("light_extra_attack"));
			if (lightExtra && !"ATTACK".equals(kind)) {
				throw RpgException.invalidArgument("action.light_extra_attack is only valid for ATTACK.");
			}
			if (!ACTIONS.contains(kind)) {
				throw RpgException.invalidArgument(
						"Unknown action kind '" + kind + "'; legal kinds: " + ACTIONS.stream().sorted().toList());
			}
			if ("HIDE".equals(kind)) {
				throw RpgException.capabilityUnavailable(
						"HIDE is not mechanically supported yet: it requires cover or obscurement, enemy sight checks, a Stealth DC and concealment that ends on revealing actions. No Action or roll was spent. The DM can resolve a Dexterity (Stealth) check with resolve_check and adjudicate what observers know; that check alone does not create automatic hidden or Invisible state.")
						.withDetail("supported_alternative", "resolve_check").withDetail("action_spent", false);
			}
			if ("END_TURN".equals(kind) && !turnsSkipped.isEmpty()
					&& Objects.equals(opened.lng("turn_participant_id"), participant.id())) {
				// Older saves can point at a mover already defeated by a reaction. Passing that displayed
				// turn acknowledges the reconciliation, rather than rejecting and rolling it back, or ending
				// the next living creature's turn as well.
				var recovered = new LinkedHashMap<String, Object>();
				recovered.put("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()));
				recovered.put("actor", actorRef);
				recovered.put("kind", kind);
				recovered.put("turn_advanced", true);
				recovered.put("turns_skipped", turnsSkipped);
				Row live = tx.get("encounter", encounter.id());
				tx.update("encounter", live.id(), Map.of("revision", live.lng("revision") + 1));
				recovered.put("encounter_revision", live.lng("revision") + 1);
				Map<String, Object> current = state(tx, tx.get("encounter", encounter.id()), 5);
				recovered.put("state", current);
				recovered.put("next_turn", current.get("turn"));
				recovered.put("round", current.get("round"));
				if (current.containsKey("gm_handoff"))
					recovered.put("gm_handoff", current.get("gm_handoff"));
				recovered.put("note", "The unavailable saved turn was passed; the next creature's turn remains open.");
				automaticDeathReports(tx, campaignId, encounter, recovered);
				recovered.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
				return recovered;
			}
			Long turnId = encounter.lng("turn_participant_id");
			if (turnId == null || turnId != participant.id()) {
				String whose = turnId == null ? "nobody"
						: tx.get("character", tx.get("encounter_participant", turnId).lng("character_id")).str("name");
				if (turnId != null && !GridCombat.knownToParty(tx, encounter)
						.contains(tx.get("encounter_participant", turnId).lng("character_id")))
					whose = "an unobserved participant";
				throw RpgException.notAllowed("It is " + whose + "'s turn, not " + actor.str("name")
						+ "'s (I-31). Reactions are offered by the engine as pending choices. The DM can inspect the full turn with get_encounter_state audience GM.")
						.withDetail("gm_handoff", gmHandoff(encounter));
			}
			if (!"ACTIVE".equals(participant.str("status")) || !"ALIVE".equals(actor.str("life_state"))) {
				throw RpgException.notAllowed(actor.str("name") + " cannot act (" + actor.str("life_state") + ").");
			}
			// Incapacitated denies every action but not movement (standing up included); the speed-0 conditions deny
			// that too. The turn stays open either way, so END_TURN always stands (and rolls any end-of-turn save).
			if (!"END_TURN".equals(kind) && !"MOVE".equals(kind) && !"STAND".equals(kind)) {
				conditionAmong(tx, actor, Combat.INCAPACITATING_CONDITIONS).ifPresent(cond -> {
					throw RpgException.notAllowed(actor.str("name") + " is " + cond
							+ " and cannot take actions (SRD 5.2.1 \"Incapacitated\"); END_TURN passes the turn.");
				});
			}
			if ("MOVE".equals(kind) || "DASH".equals(kind) || "STAND".equals(kind)) {
				conditionAmong(tx, actor, Combat.SPEED_ZERO_CONDITIONS).ifPresent(cond -> {
					throw RpgException.notAllowed(
							actor.str("name") + " is " + cond + ": Speed 0, no movement (SRD 5.2.1 \"" + cond + "\").");
				});
				if (Exhaustion.speed(tx, rules, actor) == 0) {
					throw RpgException
							.notAllowed(actor.str("name") + " has Speed 0 and cannot move, Dash or stand up.");
				}
			}
			long round = encounter.lng("round");
			boolean continuationAttack = "ATTACK".equals(kind) && !lightExtra
					&& TurnEconomy.markedThisTurn(tx, encounter, actor.id(), TurnEconomy.ATTACK_ACTION)
					&& attacksRemaining(tx, encounter, actor, round, false) > 0;
			boolean bonusAction = lightExtra || actionUsesBonusAction(tx, actor, kind, action);
			boolean requiresAction = ACTION_KINDS.contains(kind) && !bonusAction && !continuationAttack;
			if (requiresAction)
				TurnEconomy.spendAction(tx, encounter, actor.id());
			var result = new LinkedHashMap<String, Object>();
			result.put("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()));
			result.put("actor", Ref.of(Ref.CHARACTER, actor.id()));
			result.put("kind", kind);
			result.put("turn_advanced", false);
			if (!turnsSkipped.isEmpty()) {
				result.put("turns_skipped", turnsSkipped);
			}
			switch (kind) {
			case "ATTACK" -> result.putAll(attack(tx, campaignId, encounter, actor, participant, action, round, false));
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
				if ("DASH".equals(kind))
					GridCombat.dash(tx, rules, encounter, participant, actor);
				if (!"DASH".equals(kind) || action.containsKey("x_ft") || action.containsKey("destination_ft")
						|| action.containsKey("zone") || action.containsKey("path_ft")
						|| action.containsKey("portal")) {
					result.putAll(moveOnGrid(tx, campaignId, encounter, actor, participant, action));
				}
				result.put("movement", GridCombat.movementView(tx, rules, encounter, participant, actor));
				if (action.get("zone") != null)
					result.put("zone", action.get("zone"));
				if (conditionAmong(tx, actor, List.of("PRONE")).isPresent())
					result.put("crawling", true);
			}
			case "STAND" -> {
				// "Spend an amount of movement equal to half your Speed (round down) to right yourself and thereby end
				// the condition. If your Speed is 0, you can't right yourself" (SRD 5.2.1 "Prone"). Zones carry no
				// movement budget, so the cost is reported for the GM to hold against this turn's movement.
				List<Row> prone = tx.query("SELECT * FROM active_effect WHERE character_id = ? AND condition_ref = ?",
						actor.id(), RuntimeService.conditionRef("PRONE"));
				if (prone.isEmpty()) {
					throw RpgException.notAllowed(actor.str("name") + " is not Prone.");
				}
				for (Row e : prone) {
					se.hirt.mcp.rpg.rules.Effects.end(tx, e);
				}
				int speed = Exhaustion.speed(tx, rules, actor);
				int cost = speed / 2;
				GridCombat.spendMovement(tx, rules, encounter, participant, actor, cost * 12L);
				log(tx, campaignId, encounter.id(), round, actor.id(), "STAND",
						actor.str("name") + " stands up, spending " + cost + " of " + speed + " feet of movement.",
						null);
				result.put("stood_up", true);
				result.put("speed", speed);
				result.put("movement_cost", cost);
				result.put("note",
						"Prone has ended. Standing up is movement, not an action: it spends half of "
								+ actor.str("name") + "'s Speed (" + cost + " of " + speed
								+ " ft), leaving the rest of this turn's movement (SRD 5.2.1 \"Prone\").");
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
			// A continuing Multiattack spends its original Action; a Light extra attack spends the Bonus Action.
			if ("ATTACK".equals(kind) && !lightExtra)
				TurnEconomy.markThisTurn(tx, encounter, actor.id(), TurnEconomy.ATTACK_ACTION);
			EncounterDigest.captureNarration(tx, encounter.id(), actor.id(), action, result);
			if ("MOVE".equals(kind) || "DASH".equals(kind)) {
				TurnEconomy.markThisTurn(tx, encounter, actor.id(), TurnEconomy.MOVED);
			} else if ("STAND".equals(kind)) {
				TurnEconomy.markThisTurn(tx, encounter, actor.id(), TurnEconomy.STOOD_UP);
			}
			// A stat block that says "makes two attacks" gets two attacks: the turn does not end under the
			// actor while attacks remain (SRD 5.2.1 "Multiattack"). END_TURN always ends it.
			int remaining = kind.equals("ATTACK") ? attacksRemaining(tx, encounter, actor, round, !lightExtra) : 0;
			if (remaining > 0) {
				result.put("attacks_remaining", remaining);
				result.put("next_step", actor.str("name") + " has " + remaining
						+ " attack(s) left this turn: attack again, or END_TURN to finish.");
			}
			List<String> lightWeapons = kind.equals("END_TURN") ? List.of() : lightExtraWeapons(tx, encounter, actor);
			if (!lightWeapons.isEmpty()) {
				result.put("light_extra_attack_available", lightWeapons);
				result.put("next_step", (remaining > 0 ? result.get("next_step") + " " : "") + actor.str("name")
						+ " can make an extra Light-weapon attack: ATTACK with light_extra_attack:true and a listed weapon ref, or END_TURN.");
			}
			boolean finish = endTurn == null
					? !Set.of("MOVE", "STAND").contains(kind) && !"BONUS_ACTION".equals(result.get("action_cost"))
					: endTurn;
			List<Map<String, Object>> pending = pendingChoices(tx, encounter.id());
			Row liveActor = tx.get("character", actor.id()),
					liveParticipant = tx.get("encounter_participant", participant.id());
			boolean forcedEnd = pending.isEmpty()
					&& Objects.equals(tx.get("encounter", encounter.id()).lng("turn_participant_id"), participant.id())
					&& (!"ACTIVE".equals(liveParticipant.str("status"))
							|| !"ALIVE".equals(liveActor.str("life_state")));
			boolean advance = kind.equals("END_TURN") || forcedEnd
					|| (finish && remaining == 0 && lightWeapons.isEmpty());
			if (!advance && pending.isEmpty()) {
				// Facts for the GM, not a prompt for the player: whether to ask about a leftover is a judgment of the
				// declaration and the scene, which the tool description leaves to the GM.
				Map<String, Object> economy = turnEconomy(tx, encounter, actor, participant, remaining);
				result.put("turn_economy", economy);
				if (!result.containsKey("next_step") && "AVAILABLE".equals(economy.get("action"))) {
					result.put("next_step", actor.str("name")
							+ "'s Action is still available this turn (ATTACK, CAST, DODGE, DASH, DISENGAGE, HELP, USE_ITEM); END_TURN when the turn is done.");
				}
			}
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
				if (forcedEnd) {
					result.put("turn_end_reason", "ACTOR_CAN_NO_LONGER_ACT");
					log(tx, campaignId, encounter.id(), encounter.lng("round"), actor.id(), "TURN_SKIPPED",
							actor.str("name") + " can no longer take this turn; the turn moves on.", null);
				}
				result.put("turn_advanced", true);
				result.putAll(advanceTurn(tx, campaignId, tx.get("encounter", encounter.id())));
			}
			Row after = tx.get("encounter", encounter.id());
			tx.update("encounter", after.id(), Map.of("revision", after.lng("revision") + 1));
			result.put("encounter_revision", after.lng("revision") + 1);
			result.put("state", state(tx, tx.get("encounter", encounter.id()), 5));
			automaticDeathReports(tx, campaignId, encounter, result);
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return result;
		});
	}

	private boolean actionUsesBonusAction(Tx tx, Row actor, String kind, Map<String, Object> action) {
		if ("CAST".equals(kind) && action.get("spell") != null)
			return "BONUS_ACTION".equals(se.hirt.mcp.rpg.magic.SpellService.castingActionCost(tx, rules, actor,
					action.get("spell").toString(), action));
		if ("USE_ITEM".equals(kind) && action.get("item") != null) {
			String text = action.get("item").toString();
			Row entry = text.startsWith(Ref.INVENTORY + ":") ? tx.get("inventory_entry", Ref.id(text, Ref.INVENTORY))
					: carriedAny(tx, actor, text);
			return "POTION".equals(ContentService.itemForEntry(tx, rules, entry).payload().get("type"));
		}
		return false;
	}

	private Map<String, Object> moveOnGrid(
		Tx tx, long campaignId, Row encounter, Row actor, Row participant, Map<String, Object> action) {
		if (action.get("portal") != null)
			return moveViaPortal(tx, campaignId, encounter, actor, participant, action.get("portal").toString(),
					new HashSet<>());
		Row placement = GridCombat.actor(tx, encounter, actor.id());
		Footprint start = SceneService.footprint(placement);
		var origins = new ArrayList<Point>();
		if (action.get("path_ft") instanceof List<?> supplied) {
			if (supplied.size() > 1000)
				throw RpgException.invalidArgument("Movement paths are limited to 1000 steps per action.");
			for (Object value : supplied) {
				if (!(value instanceof List<?> pair) || pair.size() != 2 || !(pair.get(0) instanceof Number x)
						|| !(pair.get(1) instanceof Number y))
					throw RpgException.invalidArgument("path_ft requires [x,y] coordinates in feet.");
				Point point = SceneService.point(x.doubleValue(), y.doubleValue());
				start.at(point);
				if (origins.isEmpty() && point.equals(start.origin()))
					continue;
				origins.add(point);
			}
			Point previous = start.origin();
			for (Point point : origins) {
				if (Math.max(Math.abs(point.x() - previous.x()), Math.abs(point.y() - previous.y())) != 60)
					throw RpgException.invalidArgument("Each explicit movement step must be one adjacent 5 ft cell.");
				previous = point;
			}
			if (!origins.isEmpty())
				GridCombat.extendIncidentalCoverage(tx, encounter, start.at(origins.getLast()));
		} else {
			Point destination = GridCombat.destination(action);
			start.at(destination);
			GridCombat.extendIncidentalCoverage(tx, encounter, start.at(destination));
			Row map = GridCombat.scene(tx, encounter);
			Scene geometry = SceneService.geometry(SceneService.document(tx, map), placement.intOr("level", 0));
			int crawl = conditionAmong(tx, actor, List.of("PRONE")).isPresent() ? 2 : 1;
			var path = SpatialRules.path(geometry, start, destination,
					GridCombat.occupied(tx, map.id(), placement.intOr("level", 0), actor.id()), 10000,
					GridCombat.remainingMovement(tx, rules, encounter, participant, actor) / crawl);
			if (path.found())
				origins.addAll(path.origins().subList(1, path.origins().size()));
			else if (path.status() == SpatialRules.PathStatus.INVALID_DESTINATION
					|| path.status() == SpatialRules.PathStatus.NO_PATH) {
				// A hidden obstruction is learned by approaching it, not by a free all-or-nothing validation probe.
				Point cursor = start.origin();
				while (!cursor.equals(destination) && origins.size() < 1000) {
					cursor = cursor.translate(Long.compare(destination.x(), cursor.x()) * 60L,
							Long.compare(destination.y(), cursor.y()) * 60L);
					origins.add(cursor);
				}
				if (!cursor.equals(destination))
					throw RpgException.invalidArgument("Choose a bounded movement destination.");
			} else
				throw RpgException
						.notAllowed("The requested route exceeds this turn's movement or bounded path search.");
		}
		return executeGridPath(tx, campaignId, encounter, actor, participant, origins, new HashSet<>());
	}

	private Map<String, Object> executeGridPath(
		Tx tx, long campaignId, Row encounter, Row actor, Row participant, List<Point> origins,
		Set<Long> handledAtBoundary) {
		var output = new LinkedHashMap<String, Object>();
		var reactions = new ArrayList<Map<String, Object>>();
		var moved = new ArrayList<List<Double>>();
		Row map = GridCombat.scene(tx, encounter);
		Map<String, Object> stances = SceneService.map(encounter.map("sides_json").get("stances"));
		long spent = 0;
		for (int index = 0; index < origins.size(); index++) {
			actor = tx.get("character", actor.id());
			if (!"ALIVE".equals(actor.str("life_state"))) {
				output.put("interruption", "The mover can no longer continue.");
				break;
			}
			Row placement = GridCombat.actor(tx, encounter, actor.id());
			Footprint from = SceneService.footprint(placement), to = from.at(origins.get(index));
			Scene geometry = SceneService.geometry(SceneService.document(tx, map), placement.intOr("level", 0));
			if (!SpatialRules.transition(geometry, from, to,
					GridCombat.occupied(tx, map.id(), placement.intOr("level", 0), actor.id()))) {
				output.put("interruption", "Movement stops at a newly encountered or changed obstruction.");
				break;
			}
			int crawl = conditionAmong(tx, actor, List.of("PRONE")).isPresent() ? 2 : 1;
			long cost = SpatialRules.movementCost(geometry, to) * crawl;
			if (cost > GridCombat.remainingMovement(tx, rules, encounter, participant, actor)) {
				output.put("interruption", "The remaining movement is insufficient for the next step.");
				break;
			}
			boolean disengaged = tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id=? AND condition_ref=?",
					actor.id(), DISENGAGING_REF) > 0;
			if (!disengaged)
				for (Row other : turnOrder(tx, encounter.id())) {
					if (other.id() == participant.id() || handledAtBoundary.contains(other.id())
							|| !Combat.hostile(stances, other.str("side"), participant.str("side")))
						continue;
					Row reactor = tx.get("character", other.lng("character_id"));
					if (!"ACTIVE".equals(other.str("status")) || !reactionAvailable(tx, other, reactor)
							|| !GridCombat.canSee(tx, encounter, reactor.id(), actor.id()))
						continue;
					Row reactorPlacement = GridCombat.actor(tx, encounter, reactor.id());
					if (reactorPlacement.intOr("level", 0) != placement.intOr("level", 0))
						continue;
					var attackAction = new LinkedHashMap<String, Object>();
					attackAction.put("weapon", defaultMeleeAttack(tx, reactor).orElse("unarmed"));
					Combat.AttackProfile profile = resolveProfile(tx, reactor, attackAction).profile();
					if (profile.ranged())
						continue;
					long reach = SceneService.number(profile.range() == null ? Map.of() : profile.range(), "reach", 5)
							.longValue() * 12;
					Footprint enemy = SceneService.footprint(reactorPlacement);
					if (SpatialRules.distance(enemy, from) > reach || SpatialRules.distance(enemy, to) <= reach)
						continue;
					handledAtBoundary.add(other.id());
					if (asksBeforeReacting(tx, campaignId, encounter, reactor.id())) {
						reactions.add(openChoice(tx, campaignId, encounter, reactor.id(), "OPPORTUNITY_ATTACK",
								actor.str("name") + " is about to leave " + reactor.str("name")
										+ "'s reach. Opportunity Attack?",
								List.of(Map.of("option", "TAKE", "description",
										"Make one melee attack before the mover leaves reach; optionally choose weapon and nonlethal:true to knock out."),
										Map.of("option", "DECLINE", "description", "Let the mover continue.")),
								Map.of("mover", actor.id(), "grid", true)));
					} else
						reactions.add(reactionAttack(tx, campaignId, encounter, reactor, other, actor, null,
								encounter.lng("round")));
					if (!pendingChoices(tx, encounter.id()).isEmpty()) {
						saveGridContinuation(tx, encounter, actor.id(), origins.subList(index, origins.size()),
								handledAtBoundary);
						output.put("paused_for_reaction", true);
						output.put("path_ft", moved);
						output.put("movement_cost_ft", spent / 12.0);
						output.put("opportunity_attacks", reactions);
						SceneService.remember(tx, map);
						return output;
					}
					if (!"ALIVE".equals(tx.get("character", actor.id()).str("life_state")))
						break;
				}
			actor = tx.get("character", actor.id());
			if (!"ALIVE".equals(actor.str("life_state"))
					|| !"ACTIVE".equals(tx.get("encounter_participant", participant.id()).str("status"))
					|| conditionAmong(tx, actor, Combat.SPEED_ZERO_CONDITIONS).isPresent()) {
				output.put("interruption", "The mover falls before leaving reach.");
				break;
			}
			cost = SpatialRules.movementCost(geometry, to)
					* (conditionAmong(tx, actor, List.of("PRONE")).isPresent() ? 2 : 1);
			if (cost > GridCombat.remainingMovement(tx, rules, encounter, participant, actor)) {
				output.put("interruption", "A reaction prevents the remaining movement.");
				break;
			}
			GridCombat.spendMovement(tx, rules, encounter, participant, actor, cost);
			GridCombat.moveActor(tx, encounter, placement, to.origin());
			SceneService.remember(tx, map);
			spent += cost;
			moved.add(SceneService.feet(to.origin()));
			handledAtBoundary.clear();
		}
		clearGridContinuation(tx, encounter);
		Row placed = GridCombat.actor(tx, encounter, actor.id());
		output.put("position_ft", List.of(placed.get("x_ft"), placed.get("y_ft")));
		output.put("path_ft", moved);
		output.put("movement_cost_ft", spent / 12.0);
		output.put("completed", !output.containsKey("interruption"));
		output.put("opportunity_attacks",
				reactions.isEmpty()
						&& tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id=? AND condition_ref=?",
								actor.id(), DISENGAGING_REF) > 0 ? "none (Disengage)" : reactions);
		log(tx, campaignId, encounter.id(), encounter.lng("round"), actor.id(), "MOVE",
				actor.str("name") + " moves " + spent / 12.0 + " ft on the battlefield.", output);
		return output;
	}

	private Map<String, Object> moveViaPortal(
		Tx tx, long campaignId, Row encounter, Row actor, Row participant, String key, Set<Long> handled) {
		Row map = GridCombat.scene(tx, encounter), placement = GridCombat.actor(tx, encounter, actor.id());
		SceneService.Transition transition;
		try {
			transition = SceneService.resolveTransition(tx, map, placement, key);
		} catch (RpgException error) {
			if (handled.isEmpty())
				throw error;
			clearGridContinuation(tx, encounter);
			return Map.of("completed", false, "interruption", "The passage changed before movement could continue.");
		}
		long cost = transition.costInches() * (conditionAmong(tx, actor, List.of("PRONE")).isPresent() ? 2 : 1);
		if (cost > GridCombat.remainingMovement(tx, rules, encounter, participant, actor)) {
			if (handled.isEmpty())
				throw RpgException.notAllowed("The passage exceeds this turn's remaining movement.");
			clearGridContinuation(tx, encounter);
			return Map.of("completed", false, "interruption", "A reaction prevents movement through the passage.");
		}
		var reactions = new ArrayList<Map<String, Object>>();
		Footprint from = SceneService.footprint(placement), to = from.at(transition.origin());
		Map<String, Object> stances = SceneService.map(encounter.map("sides_json").get("stances"));
		boolean disengaged = tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id=? AND condition_ref=?",
				actor.id(), DISENGAGING_REF) > 0;
		if (!disengaged)
			for (Row other : turnOrder(tx, encounter.id())) {
				if (other.id() == participant.id() || handled.contains(other.id())
						|| !Combat.hostile(stances, other.str("side"), participant.str("side")))
					continue;
				Row reactor = tx.get("character", other.lng("character_id"));
				if (!"ACTIVE".equals(other.str("status")) || !reactionAvailable(tx, other, reactor)
						|| !GridCombat.canSee(tx, encounter, reactor.id(), actor.id()))
					continue;
				Row enemyPlacement = GridCombat.actor(tx, encounter, reactor.id());
				if (enemyPlacement.intOr("level", 0) != placement.intOr("level", 0))
					continue;
				Combat.AttackProfile profile = resolveProfile(tx, reactor,
						Map.of("weapon", defaultMeleeAttack(tx, reactor).orElse("unarmed"))).profile();
				long reach = SceneService.number(profile.range() == null ? Map.of() : profile.range(), "reach", 5)
						.longValue() * 12;
				Footprint enemy = SceneService.footprint(enemyPlacement);
				if (SpatialRules.distance(enemy, from) > reach || transition.level() == placement.intOr("level", 0)
						&& SpatialRules.distance(enemy, to) <= reach)
					continue;
				handled.add(other.id());
				if (asksBeforeReacting(tx, campaignId, encounter, reactor.id())) {
					reactions.add(openChoice(tx, campaignId, encounter, reactor.id(), "OPPORTUNITY_ATTACK",
							actor.str("name") + " is about to leave reach through the passage. Opportunity Attack?",
							List.of(Map.of("option", "TAKE", "description",
									"Make one melee attack before the mover enters the passage; optionally choose weapon and nonlethal:true to knock out."),
									Map.of("option", "DECLINE", "description", "Let the mover continue.")),
							Map.of("mover", actor.id(), "grid", true)));
				} else
					reactions.add(reactionAttack(tx, campaignId, encounter, reactor, other, actor, null,
							encounter.lng("round")));
				if (!pendingChoices(tx, encounter.id()).isEmpty()) {
					var environment = new LinkedHashMap<>(tx.get("encounter", encounter.id()).map("environment_json"));
					environment.put("portal_continuation", Map.of("actor", actor.id(), "portal", key,
							"handled_participants", handled.stream().sorted().toList()));
					tx.update("encounter", encounter.id(), Map.of("environment_json", Json.write(environment)));
					return Map.of("paused_for_reaction", true, "opportunity_attacks", reactions, "movement_cost_ft",
							0.0);
				}
				if (!"ALIVE".equals(tx.get("character", actor.id()).str("life_state")))
					break;
			}
		actor = tx.get("character", actor.id());
		cost = transition.costInches() * (conditionAmong(tx, actor, List.of("PRONE")).isPresent() ? 2 : 1);
		if (!"ALIVE".equals(actor.str("life_state"))
				|| !"ACTIVE".equals(tx.get("encounter_participant", participant.id()).str("status"))
				|| conditionAmong(tx, actor, Combat.SPEED_ZERO_CONDITIONS).isPresent()
				|| cost > GridCombat.remainingMovement(tx, rules, encounter, participant, actor)) {
			clearGridContinuation(tx, encounter);
			return Map.of("completed", false, "interruption", "A reaction prevents movement through the passage.",
					"opportunity_attacks", reactions);
		}
		GridCombat.spendMovement(tx, rules, encounter, participant, actor, cost);
		tx.update("map_actor", placement.id(),
				Map.of("x_ft", transition.origin().x() / 12.0, "y_ft", transition.origin().y() / 12.0, "level",
						transition.level(), "revision", placement.lng("revision") + 1));
		SceneService.syncLocation(tx, map, tx.get("map_actor", placement.id()));
		SceneService.remember(tx, map);
		clearGridContinuation(tx, encounter);
		log(tx, campaignId, encounter.id(), encounter.lng("round"), actor.id(), "MOVE",
				actor.str("name") + " traverses " + key + ".", Map.of("portal", key, "level", transition.level()));
		return Map.of("completed", true, "portal", key, "level", transition.level(), "position_ft",
				SceneService.feet(transition.origin()), "movement_cost_ft", cost / 12.0, "opportunity_attacks",
				reactions);
	}

	private static void saveGridContinuation(Tx tx, Row encounter, long actor, List<Point> origins, Set<Long> handled) {
		var environment = new LinkedHashMap<>(tx.get("encounter", encounter.id()).map("environment_json"));
		environment.put("movement_continuation",
				Map.of("actor", actor, "origins_inches",
						origins.stream().map(point -> List.of(point.x(), point.y())).toList(), "handled_participants",
						handled.stream().sorted().toList()));
		tx.update("encounter", encounter.id(), Map.of("environment_json", Json.write(environment)));
	}

	private static void clearGridContinuation(Tx tx, Row encounter) {
		var environment = new LinkedHashMap<>(tx.get("encounter", encounter.id()).map("environment_json"));
		boolean changed = environment.remove("movement_continuation") != null;
		changed |= environment.remove("portal_continuation") != null;
		if (changed) {
			EncounterDigest.movementContinuationCleared(environment, tx.journalId());
			tx.update("encounter", encounter.id(), Map.of("environment_json", Json.write(environment)));
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> resumeGridMovement(Tx tx, long campaignId, Row encounter) {
		Map<String, Object> portal = SceneService
				.map(tx.get("encounter", encounter.id()).map("environment_json").get("portal_continuation"));
		if (!portal.isEmpty()) {
			long actor = ((Number) portal.get("actor")).longValue();
			Row participant = tx.queryOne("SELECT * FROM encounter_participant WHERE encounter_id=? AND character_id=?",
					encounter.id(), actor).orElseThrow();
			Set<Long> handled = new HashSet<>();
			for (Number value : (List<Number>) portal.get("handled_participants"))
				handled.add(value.longValue());
			return moveViaPortal(tx, campaignId, encounter, tx.get("character", actor), participant,
					portal.get("portal").toString(), handled);
		}
		Map<String, Object> continuation = SceneService
				.map(tx.get("encounter", encounter.id()).map("environment_json").get("movement_continuation"));
		if (continuation.isEmpty())
			return Map.of();
		long actorId = ((Number) continuation.get("actor")).longValue();
		Row actor = tx.get("character", actorId);
		Row participant = tx.queryOne("SELECT * FROM encounter_participant WHERE encounter_id=? AND character_id=?",
				encounter.id(), actorId).orElseThrow();
		List<Point> origins = ((List<List<Number>>) continuation.get("origins_inches")).stream()
				.map(pair -> new Point(pair.get(0).longValue(), pair.get(1).longValue())).toList();
		Set<Long> handled = new HashSet<>();
		for (Number value : (List<Number>) continuation.get("handled_participants"))
			handled.add(value.longValue());
		return executeGridPath(tx, campaignId, encounter, actor, participant, origins, handled);
	}

	/**
	 * What remains of the actor's turn while it stays open (SRD 5.2.1 "Your Turn"): the Action, the
	 * Bonus Action, the Reaction and movement, which zones carry without a distance.
	 */
	private Map<String, Object> turnEconomy(Tx tx, Row encounter, Row actor, Row participant, int attacksRemaining) {
		var economy = new LinkedHashMap<String, Object>();
		economy.put("action",
				TurnEconomy.markedThisTurn(tx, encounter, actor.id(), TurnEconomy.ACTION) ? "SPENT" : "AVAILABLE");
		economy.put("bonus_action",
				TurnEconomy.bonusActionAvailable(tx, encounter, actor.id()) ? "AVAILABLE" : "SPENT");
		economy.put("reaction", tx.get("encounter_participant", participant.id()).intOr("reaction_used", 0) != 0
				? "SPENT" : "AVAILABLE");
		boolean moved = TurnEconomy.markedThisTurn(tx, encounter, actor.id(), TurnEconomy.MOVED);
		boolean stood = TurnEconomy.markedThisTurn(tx, encounter, actor.id(), TurnEconomy.STOOD_UP);
		economy.put("movement", moved ? "USED" : stood ? "HALF_SPENT" : "AVAILABLE");
		if (GridCombat.isGrid(encounter))
			economy.put("movement_budget", GridCombat.movementView(tx, rules, encounter, participant, actor));
		if (attacksRemaining > 0) {
			economy.put("attacks_remaining", attacksRemaining);
		}
		return economy;
	}

	/**
	 * Attacks remaining in one Attack action, from a creature's Multiattack or reached class Extra
	 * Attack features. Multiclass features use the largest count, never a sum.
	 */
	@SuppressWarnings("unchecked")
	private int attacksRemaining(Tx tx, Row encounter, Row actor, long round, boolean countAttack) {
		int count = 1;
		if (RuntimeService.usesStatBlock(tx, actor)) {
			count = rules.find(actor.str("origin_content_ref")).map(d -> d.payload().get("multiattack"))
					.filter(m -> m instanceof Map<?, ?>).map(m -> ((Map<String, Object>) m).get("count"))
					.filter(c -> c instanceof Number).map(c -> ((Number) c).intValue()).orElse(1);
		} else {
			for (var feature : se.hirt.mcp.rpg.progression.ClassFeatures.featuresOf(tx, rules, actor))
				if (feature.get("mechanic") instanceof Map<?, ?> mechanic && "EXTRA_ATTACK".equals(mechanic.get("kind"))
						&& mechanic.get("attacks") instanceof Number attacks)
					count = Math.max(count, attacks.intValue());
		}
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
		if (countAttack) {
			used++;
			flags.put("attacks_turn", turnKey);
			flags.put("attacks_used", used);
			tx.update("encounter_participant", p.id(), Map.of("once_per_turn_json", Json.write(flags)));
		}
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
			if (targetParticipant != null
					&& !java.util.Objects.equals(targetParticipant.str("side"), actorParticipant.str("side"))) {
				for (Row p : tx.query(
						"SELECT * FROM encounter_participant WHERE encounter_id = ? AND side = ? AND character_id <> ?",
						encounter.id(), actorParticipant.str("side"), actor.id())) {
					// Within 5 feet of the target: in the target's zone (the column is position_zone), still in
					// the fight, alive and not incapacitated (SRD 5.2.1 "Sneak Attack").
					if (!(GridCombat.isGrid(encounter)
							? GridCombat.distance(tx, encounter, p.lng("character_id"), target.id()) <= 60
							: java.util.Objects.equals(p.str("position_zone"), targetParticipant.str("position_zone")))
							|| !"ACTIVE".equals(p.str("status"))) {
						continue;
					}
					Row ally = tx.get("character", p.lng("character_id"));
					if (!"ALIVE".equals(ally.str("life_state")) || RuntimeService.conditions(tx, ally.id()).stream()
							.anyMatch(cond -> Combat.INCAPACITATING_CONDITIONS
									.contains(String.valueOf(cond.get("condition")).toUpperCase()))) {
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

	// ── attack resolution ──────────────────────────────────────────────

	/**
	 * The attack profile resolved from the action: creature action, carried weapon, or unarmed
	 * strike.
	 */
	private record Setup(Combat.AttackProfile profile, List<String> warnings, Long weaponEntryId, boolean light) {
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
		boolean unarmed = "unarmed".equalsIgnoreCase(weaponText);
		String named = weaponText != null && !weaponText.isBlank() && !unarmed ? weaponText : null;
		Combat.AttackProfile profile;
		if (unarmed) {
			profile = Combat.unarmed(actor, prof);
		} else if (statBlock) {
			// A pure stat block: its own actions first (the named one, else the first attack), then whatever
			// it happens to carry.
			Optional<Map<String, Object>> creatureAction = creatureAction(actor, named);
			if (creatureAction.isPresent()) {
				profile = Combat.creatureAction(creatureAction.get(), Boolean.TRUE.equals(action.get("thrown"))
						|| "RANGED".equalsIgnoreCase(String.valueOf(action.get("mode"))));
			} else if (named != null) {
				return carriedProfile(tx, actor, named, prof, action, warnings);
			} else {
				profile = Combat.unarmed(actor, prof);
			}
		} else if (named != null && se.hirt.mcp.rpg.rules.Effects.conjuredWeapon(tx, actor.id(), named).isPresent()) {
			// A weapon a spell conjured (Flame Blade): a melee spell attack for as long as the effect lasts.
			profile = Combat.conjured(se.hirt.mcp.rpg.rules.Effects.conjuredWeapon(tx, actor.id(), named).get());
		} else if (named != null) {
			// A classed character attacks with what it carries. An action of the stat block it was
			// materialized from (a Priest's Radiant Flame, a Scout's Longbow it never owned) stays reachable
			// by name when no carried weapon answers to it.
			Optional<Map<String, Object>> creatureAction = creatureAction(actor, named);
			try {
				return carriedProfile(tx, actor, named, prof, action, warnings);
			} catch (RpgException notCarried) {
				if (creatureAction.isEmpty()) {
					throw notCarried;
				}
				profile = Combat.creatureAction(creatureAction.get(), Boolean.TRUE.equals(action.get("thrown"))
						|| "RANGED".equalsIgnoreCase(String.valueOf(action.get("mode"))));
				warnings.add(named + " is not a carried weapon; using the stat-block action of that name.");
			}
		} else {
			profile = Combat.unarmed(actor, prof);
		}
		return new Setup(profile, warnings, null, false);
	}

	private Setup carriedProfile(
		Tx tx, Row actor, String named, int prof, Map<String, Object> action, List<String> warnings) {
		Row weaponEntry = carriedWeapon(tx, actor, named);
		ContentService.Item item = ContentService.itemForEntry(tx, rules, weaponEntry);
		if (!weaponEntry.bool("equipped")) {
			warnings.add(item.name() + " was not equipped; treating the draw as part of the attack.");
		}
		// "If you have proficiency with a weapon, you add your Proficiency Bonus to attack rolls you make with it"
		// (SRD 5.2.1 "Weapons"); without it, the ability modifier alone.
		if (!se.hirt.mcp.rpg.rules.Training.proficientWithWeapon(tx, rules, actor, item.payload())) {
			warnings.add(actor.str("name") + " is not proficient with the " + item.name()
					+ ": no Proficiency Bonus on the attack roll (SRD 5.2.1 \"Weapons\").");
			prof = 0;
		}
		// Spells bound to this weapon (Magic Weapon's +N, Shillelagh's ability and die) ride only on attacks with it.
		se.hirt.mcp.rpg.rules.Effects.Modifiers bound = se.hirt.mcp.rpg.rules.Effects.weaponModifiers(tx, actor.id(),
				weaponEntry.id());
		Integer abilityOverride = null;
		if (bound.useAbility != null) {
			abilityOverride = se.hirt.mcp.rpg.rules.Rules
					.modifier(actor.intOr(se.hirt.mcp.rpg.rules.Ability.parse(bound.useAbility).column(), 10));
		}
		Combat.AttackProfile profile = Combat.weapon(actor, item.name(), attackPayload(weaponEntry, item, warnings),
				prof, Boolean.TRUE.equals(action.get("two_handed")), abilityOverride, bound.weaponDie,
				bound.weaponDamageType, Boolean.TRUE.equals(action.get("light_extra_attack"))
						&& !se.hirt.mcp.rpg.character.Origins.twoWeaponFighting(tx, rules, actor));
		return new Setup(Combat.withBuff(profile, bound), warnings, weaponEntry.id(), lightWeapon(item.payload()));
	}

	private static boolean lightWeapon(Map<String, Object> payload) {
		return "WEAPON".equals(payload.get("type")) && payload.get("properties") instanceof List<?> properties
				&& properties.contains("light");
	}

	/** The Light property needs a different physical weapon after an Attack action this turn. */
	private void trackLightAttack(Tx tx, Row encounter, Row actor, Row participant, Setup setup, boolean extra) {
		String turnKey = TurnEconomy.turnKey(encounter);
		Row fresh = tx.get("encounter_participant", participant.id());
		Map<String, Object> flags = fresh.isNull("once_per_turn_json") ? new LinkedHashMap<>()
				: new LinkedHashMap<>(fresh.map("once_per_turn_json"));
		var weapons = new ArrayList<Long>();
		if (turnKey.equals(flags.get("light_attack_turn"))
				&& flags.get("light_attack_weapons") instanceof List<?> ids) {
			for (Object id : ids) {
				weapons.add(((Number) id).longValue());
			}
		}
		if (extra) {
			if (!setup.light() || setup.weaponEntryId() == null) {
				throw RpgException.validation(List.of(new Violation("action.weapon", "NOT_LIGHT",
						"The extra attack requires a carried weapon with the Light property.")));
			}
			if (weapons.stream().noneMatch(id -> !id.equals(setup.weaponEntryId()))) {
				throw RpgException.notAllowed("First take the Attack action with a different Light weapon this turn.");
			}
			if (turnKey.equals(flags.get("light_extra_attack_turn"))) {
				throw RpgException.notAllowed("The extra Light-weapon attack has already been used this turn.");
			}
			TurnEconomy.spendBonusAction(tx, encounter, actor.id(), "extra Light-weapon attack");
			// Spending the Bonus Action writes the same flags; keep that update and any other turn features.
			flags = new LinkedHashMap<>(tx.get("encounter_participant", participant.id()).map("once_per_turn_json"));
			flags.put("light_extra_attack_turn", turnKey);
		} else if (setup.light() && setup.weaponEntryId() != null) {
			if (!weapons.contains(setup.weaponEntryId())) {
				weapons.add(setup.weaponEntryId());
			}
			flags.put("light_attack_turn", turnKey);
			flags.put("light_attack_weapons", weapons);
		} else {
			return;
		}
		tx.update("encounter_participant", participant.id(), Map.of("once_per_turn_json", Json.write(flags)));
	}

	private List<String> lightExtraWeapons(Tx tx, Row encounter, Row actor) {
		if (!TurnEconomy.bonusActionAvailable(tx, encounter, actor.id())
				|| conditionAmong(tx, actor, Combat.INCAPACITATING_CONDITIONS).isPresent()) {
			return List.of();
		}
		Row p = tx.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
				encounter.id(), actor.id()).orElseThrow();
		Map<String, Object> flags = p.isNull("once_per_turn_json") ? Map.of() : p.map("once_per_turn_json");
		String turnKey = TurnEconomy.turnKey(encounter);
		if (!turnKey.equals(flags.get("light_attack_turn")) || turnKey.equals(flags.get("light_extra_attack_turn"))
				|| !(flags.get("light_attack_weapons") instanceof List<?> ids)) {
			return List.of();
		}
		var out = new ArrayList<String>();
		for (Row entry : tx.query("SELECT * FROM inventory_entry WHERE character_id = ? ORDER BY equipped DESC, id",
				actor.id())) {
			ContentService.Item item = ContentService.itemForEntry(tx, rules, entry);
			if (lightWeapon(item.payload()) && ids.stream().anyMatch(id -> ((Number) id).longValue() != entry.id())) {
				out.add(Ref.of(Ref.INVENTORY, entry.id()));
			}
		}
		return out;
	}

	/** An unattuned item still has its mundane properties (SRD 5.2.1 "Attunement"). */
	private static Map<String, Object> attackPayload(Row entry, ContentService.Item item, List<String> warnings) {
		Map<String, Object> payload = item.payload();
		// equip_item owns attunement and enforces its limit; drawing a carried item does not attune it.
		if (entry.bool("equipped") || !MagicItems.requiresAttunement(payload)) {
			return payload;
		}
		var mundane = new LinkedHashMap<>(payload);
		mundane.remove("magic");
		mundane.remove("modifiers");
		warnings.add(item.name() + " requires attunement; using only its nonmagical properties.");
		return mundane;
	}

	/**
	 * Rolls the attack, then either resolves it (damage, conditions, log) or — when the target
	 * could cast Shield and the decision is a player's — parks it as a pending choice. Reaction
	 * attacks never offer Shield.
	 */
	private Map<String, Object> attack(
		Tx tx, long campaignId, Row encounter, Row actor, Row participant, Map<String, Object> action, long round,
		boolean opportunity) {
		String targetRef = action.get("target") == null ? null : action.get("target").toString();
		if (targetRef == null) {
			throw RpgException.invalidArgument("action.target (character reference) is required for ATTACK.");
		}
		Row target = CharacterService.character(tx, campaignId, targetRef);
		Row targetParticipant = tx
				.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
						encounter.id(), target.id())
				.orElseThrow(() -> RpgException.invalidArgument(targetRef + " is not part of this encounter."));
		if (action.containsKey("confirm_hostile_ally") && !(action.get("confirm_hostile_ally") instanceof Boolean))
			throw RpgException.invalidArgument("action.confirm_hostile_ally must be a boolean.");
		if (action.containsKey("nonlethal") && !(action.get("nonlethal") instanceof Boolean))
			throw RpgException.invalidArgument("action.nonlethal must be a boolean.");
		boolean sameSide = Objects.equals(participant.str("side"), targetParticipant.str("side"));
		boolean downed = "DYING".equals(target.str("life_state")) || target.intOr("current_hp", 0) == 0
				|| conditionAmong(tx, target, List.of("UNCONSCIOUS")).isPresent();
		if (sameSide && downed && !"DEAD".equals(target.str("life_state"))
				&& !Boolean.TRUE.equals(action.get("confirm_hostile_ally"))) {
			Map<String, Object> care = RuntimeService.careGuidance(target);
			if (care.isEmpty())
				care = Map.of("guidance",
						"Assess the Unconscious condition and use applicable healing or care; a knocked-out creature recovers when its Short Rest ends.");
			throw RpgException.notAllowed(target.str("name")
					+ " is a downed ally on the same encounter side. To help them, use applicable healing, or after successful first aid apply_runtime_change STABILIZE if Dying. An intentional attack requires action.confirm_hostile_ally:true.")
					.withDetail("target", targetRef).withDetail("life_state", target.str("life_state"))
					.withDetail("care", care).withDetail("confirmation_required", "confirm_hostile_ally");
		}
		if ("DEAD".equals(target.str("life_state")) || !"ACTIVE".equals(targetParticipant.str("status"))) {
			String guidance = "DYING".equals(target.str("life_state"))
					? " is at 0 Hit Points and Dying. Healing can restore consciousness; after successful first aid use apply_runtime_change STABILIZE to stop Death Saving Throws."
					: "DEAD".equals(target.str("life_state")) ? sameSide
							? " is Dead; ordinary healing and stabilization cannot revive them. Use an applicable resurrection spell or an explicit audited revival."
							: " is Dead and is no longer a legal attack target."
							: " is already out of the fight (" + targetParticipant.str("status")
									+ "). They remain a saved character and can still receive applicable healing or care.";
			throw RpgException.notAllowed(target.str("name") + guidance).withDetail("target", targetRef)
					.withDetail("life_state", target.str("life_state"));
		}
		boolean reaction = Boolean.TRUE.equals(action.get("reaction"));
		Setup setup = resolveProfile(tx, actor, action);
		Combat.AttackProfile profile = setup.profile();
		GridCombat.AttackGeometry geometry = GridCombat.isGrid(encounter)
				? GridCombat.validateAttack(tx, encounter, actor.id(), target.id(), profile)
				: new GridCombat.AttackGeometry(0, 0, false);
		boolean lightExtra = Boolean.TRUE.equals(action.get("light_extra_attack"));
		if (lightExtra && (opportunity || reaction)) {
			throw RpgException.notAllowed("An extra Light-weapon attack is taken on your turn, not as a reaction.");
		}
		if (!opportunity && !reaction) {
			trackLightAttack(tx, encounter, actor, participant, setup, lightExtra);
		}
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
		Map<String, Object> ammoRecovery = null;
		if (profile.usesAmmunition()) {
			String weaponName = profile.name();
			String ammoRef = profile.ammunitionRef();
			Row ammo;
			if (action.get("ammunition") != null) {
				// Named ammunition, e.g. "+1 Arrow": magic ammunition made on the weapon's own kind.
				ammo = carriedAny(tx, actor, action.get("ammunition").toString());
				ContentService.Item named = ContentService.itemForEntry(tx, rules, ammo);
				Map<String, Object> magic = se.hirt.mcp.rpg.inventory.MagicItems.magic(named.payload());
				String base = magic == null || magic.get("base_ref") == null ? null
						: String.valueOf(magic.get("base_ref"));
				if (!ammoRef.equals(named.contentRef()) && !ammoRef.equals(base)) {
					throw RpgException.validation(List.of(new Violation("action.ammunition", "WRONG_AMMUNITION",
							named.name() + " is not ammunition for " + weaponName + ".")));
				}
			} else {
				ammo = tx.queryOne(
						"SELECT * FROM inventory_entry WHERE character_id = ? AND content_ref = ? ORDER BY id LIMIT 1",
						actor.id(), ammoRef)
						.orElseThrow(() -> RpgException.validation(List.of(new Violation("action.weapon",
								"NO_AMMUNITION", actor.str("name") + " has no ammunition for " + weaponName + "."))));
			}
			ContentService.Item ammoItem = ContentService.itemForEntry(tx, rules, ammo);
			ammoRecovery = AmmunitionRecovery.snapshot(ammoItem);
			Map<String, Object> ammoPayload = attackPayload(ammo, ammoItem, setup.warnings());
			int ammoBonus = MagicItems.bonus(ammoPayload);
			InventoryService.removeQuantity(tx, ammo, 1);
			var used = new LinkedHashMap<String, Object>();
			used.put("item", ammoItem.display());
			used.put("name", ammoItem.name());
			used.put("remaining", ammo.lng("quantity") - 1);
			Map<String, String> ammoExtra = MagicItems.extraDamage(ammoPayload);
			if (ammoBonus != 0 || ammoExtra != null) {
				// Magic ammunition's +N applies to this attack and damage roll (SRD 5.2.1 "Ammunition, +1, +2, or +3");
				// an enchanted piece may add damage of its own (an Arrow of Fire).
				if (ammoBonus != 0) {
					used.put("bonus", ammoBonus);
				}
				if (ammoExtra != null) {
					used.put("extra_damage", ammoExtra);
				}
				profile = Combat.withAmmunition(profile, ammoBonus, ammoExtra);
			}
			ammoUsed = used;
		}
		// Advantage / disadvantage sources (SRD 5.2.1): the GM's declaration, the attacker's and the target's
		// conditions, Dodge, effects on the target (Blur, Faerie Fire) and a ranged attack within 5 feet of an enemy;
		// the table lives in Combat.attackRollFactors so spell attacks read the same one.
		String adv = action.get("advantage") == null ? "NONE" : action.get("advantage").toString().toUpperCase();
		// Opportunity attacks happen before the mover leaves reach, although its zone is already updated.
		boolean close = opportunity || Combat.withinFiveFeet(tx, encounter.id(), actor.id(), target.id(), false);
		Combat.AttackRollFactors factors = Combat.attackRollFactors(tx, rules, encounter.id(), actor, target, close,
				profile.ranged(), adv.equals("ADVANTAGE"), adv.equals("DISADVANTAGE"));
		var reasons = new ArrayList<String>(factors.reasons());
		boolean unseenAttacker = GridCombat.isGrid(encounter)
				&& !GridCombat.canSee(tx, encounter, target.id(), actor.id());
		boolean unseenTarget = GridCombat.isGrid(encounter)
				&& !GridCombat.canSee(tx, encounter, actor.id(), target.id());
		if (unseenAttacker)
			reasons.add("target cannot see the attacker");
		if (unseenTarget)
			reasons.add("attacker cannot see the target");
		if (geometry.longRange())
			reasons.add("target is beyond the weapon's normal range");
		se.hirt.mcp.rpg.rules.Effects.Modifiers actorMods = se.hirt.mcp.rpg.rules.Effects.modifiers(tx, rules,
				actor.id());
		// Untrained armor: "Disadvantage on any D20 Test that involves Strength or Dexterity" (SRD 5.2.1 "Armor
		// Training"), which every weapon and unarmed attack roll does.
		Optional<String> untrainedArmor = se.hirt.mcp.rpg.rules.Training.untrainedArmor(tx, rules, actor);
		if (untrainedArmor.isPresent()) {
			reasons.add("attacker wears " + untrainedArmor.get() + " without armor training");
		}
		final Combat.AttackRollFactors rollFactors = new Combat.AttackRollFactors(factors.advantage() || unseenAttacker,
				factors.disadvantage() || unseenTarget || geometry.longRange() || untrainedArmor.isPresent(),
				factors.criticalWithinFiveFeet(), reasons);
		String dice = rollFactors.form();
		int bonus = profile.attackBonus() + actorMods.attackBonus - Exhaustion.penalty(tx, actor.id());
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
		int targetAc = RuntimeService.armorClass(tx, rules, target) + geometry.coverBonus();
		boolean hit = natural != 1 && (natural == 20 || attackTotal >= targetAc);
		boolean critical = hit && (natural == 20 || (rollFactors.criticalWithinFiveFeet() && close));

		var ctx = new LinkedHashMap<String, Object>();
		ctx.put("attacker", actor.id());
		ctx.put("target", target.id());
		ctx.put("action", action);
		ctx.put("attack_mode", profile.ranged() ? "RANGED" : "MELEE");
		ctx.put("thrown", profile.ranged() && RuntimeService.usesStatBlock(tx, actor)
				&& creatureAction(actor, profile.name())
						.filter(a -> "MELEE_ATTACK".equals(a.get("kind")) && a.get("range") instanceof Map<?, ?>)
						.isPresent());
		ctx.put("reaction", reaction);
		var roll = attackRoll.toMap();
		roll.put("roll_ref", Ref.of(Ref.ROLL, rollId));
		ctx.put("attack_roll", roll);
		ctx.put("bonus_dice", bonusDice);
		ctx.put("attack_total", attackTotal);
		ctx.put("natural", natural);
		ctx.put("advantage", rollFactors.label());
		ctx.put("advantage_reasons", reasons);
		ctx.put("target_armor_class", targetAc);
		ctx.put("cover_bonus", geometry.coverBonus());
		ctx.put("critical", critical);
		ctx.put("hit", hit);
		ctx.put("ammunition", ammoUsed);
		if (ammoRecovery != null) {
			ctx.put("ammunition_recovery", ammoRecovery);
		}
		ctx.put("warnings", setup.warnings());

		// Shield (SRD 5.2.1): "a reaction, which you take when you are hit by an attack roll" — any hit, an Opportunity
		// Attack, a natural 20 or a hit beyond AC + 5 included: the +5 lasts until the start of the caster's next turn,
		// so it can be worth casting even when it cannot turn this hit. A chooser is asked; under the AUTO policy an NPC
		// casts it only when it turns the hit into a miss.
		if (hit && canCastShield(tx, target, targetParticipant)) {
			boolean turnsIntoMiss = natural != 20 && attackTotal < targetAc + 5;
			if (asksBeforeReacting(tx, campaignId, encounter, target.id())) {
				var options = List.of(Map.of("option", "CAST_SHIELD", "description",
						"Cast Shield as a reaction (spends the lowest available spell slot): AC +5 until the start of "
								+ target.str("name") + "'s next turn, "
								+ (turnsIntoMiss ? "turning this hit into a miss."
										: "although this " + (natural == 20 ? "natural 20" : "hit")
												+ " lands regardless.")),
						Map.of("option", "DECLINE", "description", "Take the hit."));
				Map<String, Object> view = openChoice(tx, campaignId, encounter, target.id(), "SHIELD_SPELL",
						actor.str("name") + "'s " + profile.name() + " hits " + target.str("name") + " (" + attackTotal
								+ " vs AC " + targetAc + (natural == 20 ? ", natural 20" : "") + "). Cast Shield?",
						options, ctx);
				var out = attackView(ctx, profile);
				out.put("resolved", false);
				out.put("pending_choice", view);
				out.put("summary", actor.str("name") + "'s " + profile.name() + " hits " + target.str("name")
						+ " — waiting for a Shield decision.");
				return out;
			}
			if (turnsIntoMiss) {
				Map<String, Object> shield = castShield(tx, campaignId, encounter, target, targetParticipant, round);
				ctx.put("shield", shield);
				ctx.put("target_armor_class", targetAc + 5);
				ctx.put("hit", false);
			}
		}
		return finishAttack(tx, campaignId, encounter, actor, target, targetParticipant, profile, ctx, round);
	}

	private static Map<String, Object> attackView(Map<String, Object> ctx, Combat.AttackProfile profile) {
		var out = new LinkedHashMap<String, Object>();
		out.put("target", Ref.of(Ref.CHARACTER, ((Number) ctx.get("target")).longValue()));
		out.put("attack", profile.name());
		out.put("attack_mode", ctx.getOrDefault("attack_mode", profile.ranged() ? "RANGED" : "MELEE"));
		out.put("thrown", Boolean.TRUE.equals(ctx.get("thrown")));
		if (ctx.get("action") instanceof Map<?, ?> declared && Boolean.TRUE.equals(declared.get("nonlethal")))
			out.put("nonlethal", true);
		if (ctx.get("action") instanceof Map<?, ?> action && Boolean.TRUE.equals(action.get("light_extra_attack"))) {
			out.put("light_extra_attack", true);
			out.put("action_cost", "BONUS_ACTION");
		}
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
		int coverBonus = ((Number) ctx.getOrDefault("cover_bonus", 0)).intValue();
		out.put("cover_bonus", coverBonus);
		out.put("cover", coverBonus >= 5 ? "THREE_QUARTERS" : coverBonus >= 2 ? "HALF" : "NONE");
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
		String label = (reaction ? "opportunity attack with " : "") + profile.name()
				+ (Boolean.TRUE.equals(ctx.get("thrown")) ? " (thrown)" : "");
		String summary;
		if (hit) {
			se.hirt.mcp.rpg.rules.Effects.Modifiers actorMods = se.hirt.mcp.rpg.rules.Effects.modifiers(tx, rules,
					actor.id());
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
			if (actorMods.damageBonusFlat != 0 && !damages.isEmpty()) {
				// A flat bonus to damage rolls from an effect on the attacker (not bound to one weapon).
				Combat.RolledDamage head = damages.get(0);
				damages.set(0, new Combat.RolledDamage(head.type(), head.roll(),
						Math.max(0, head.amount() + actorMods.damageBonusFlat)));
				out.put("flat_damage_bonus", actorMods.damageBonusFlat);
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
				// SRD 5.2.1 "Knocking Out a Creature": "reduce the creature to 1 Hit Point and give it the Unconscious
				// condition. It then starts a Short Rest, at the end of which that condition ends on it. The condition
				// ends early if the creature regains any Hit Points or if someone takes an action to administer first
				// aid to it, making a successful DC 10 Wisdom (Medicine) check." Alive at 1 HP: no death saves.
				var cols = new LinkedHashMap<String, Object>();
				cols.put("current_hp", 1);
				cols.put("temp_hp", 0);
				cols.put("life_state", "ALIVE");
				cols.put("death_saves_json", null);
				cols.put("revision", target.lng("revision") + 1);
				tx.update("character", target.id(), cols);
				se.hirt.mcp.rpg.rules.Effects.breakConcentration(tx, target.id());
				var duration = new LinkedHashMap<String, Object>();
				duration.put("kind", "UNTIL");
				duration.put("until", "SHORT_REST");
				duration.put("expires_seq", GameTime.currentSeq(tx, campaignId) + 60);
				duration.put("label",
						"knocked out: until the end of the Short Rest it starts (one hour), or until it regains any Hit Points or receives first aid (DC 10 Wisdom (Medicine), GM: REMOVE_CONDITION)");
				RuntimeService.addCondition(tx, campaignId, target.id(), "UNCONSCIOUS", duration,
						"knocked out by " + actor.str("name"), "MECHANICAL_CONSEQUENCE");
				applied = new LinkedHashMap<>();
				applied.put("damage", dr.totalDealt());
				applied.put("breakdown", dr.breakdown());
				applied.put("hp_before", dr.hpBefore());
				applied.put("hp_after", 1);
				applied.put("life_state", "ALIVE");
				applied.put("knocked_out", true);
				// Out of this fight (a house rule: the engine refuses attacks on DEFEATED participants).
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
					+ (critical ? " (critical)" : "") + " for " + dr.totalDealt() + " damage"
					+ (knockOut
							? " — " + target.str("name")
									+ " is knocked out instead of dropping to 0 HP, and is Unconscious at 1 HP."
							: " (" + dr.hpBefore() + " → " + dr.hpAfter() + " HP)"
									+ (Boolean.TRUE.equals(applied.get("died")) ? " — " + target.str("name") + " dies."
											: "DYING".equals(applied.get("life_state"))
													? " — " + target.str("name") + " drops to 0 HP." : "."));
		} else {
			summary = actor.str("name") + " misses " + target.str("name") + " with " + label + " (" + attackTotal
					+ " vs AC " + targetAc + (ctx.get("shield") != null ? ", Shield" : "") + ").";
		}
		out.put("summary", summary);
		if (ctx.get("warnings") instanceof List<?> w && !w.isEmpty()) {
			out.put("warnings", w);
		}
		var payload = new LinkedHashMap<String, Object>();
		payload.put("target", target.id());
		payload.put("hit", hit);
		payload.put("total", attackTotal);
		payload.put("attack_mode", out.get("attack_mode"));
		payload.put("thrown", out.get("thrown"));
		if (ctx.get("ammunition") instanceof Map<?, ?> ammunition
				&& ctx.get("ammunition_recovery") instanceof Map<?, ?> recovery) {
			var spent = new LinkedHashMap<String, Object>();
			ammunition.forEach((key, value) -> spent.put(key.toString(), value));
			spent.put("recovery", recovery);
			payload.put("ammunition", spent);
		}
		log(tx, campaignId, encounter.id(), round, actor.id(), reaction ? "OPPORTUNITY_ATTACK" : "ATTACK", summary,
				payload);
		EncounterDigest.captureNarration(tx, encounter.id(), actor.id(), action, out);
		return out;
	}

	// ── reactions ──────────────────────────────────────────────────────

	static boolean playerControlled(Tx tx, long campaignId, long characterId) {
		return TacticalControl.playerControlled(tx, campaignId, characterId);
	}

	private boolean asksBeforeReacting(Tx tx, long campaignId, Row encounter, long characterId) {
		return playerControlled(tx, campaignId, characterId) || "ASK".equals(encounter.str("npc_reactions"));
	}

	/**
	 * A reaction is available once per round, to conscious, non-incapacitated, active participants
	 * (SRD 5.2.1 "Reactions"). Opportunity attacks and the Shield spell's offer share this reading.
	 */
	static boolean reactionAvailable(Tx tx, Row participant, Row c) {
		if (participant.intOr("reaction_used", 0) != 0 || !"ACTIVE".equals(participant.str("status"))
				|| !"ALIVE".equals(c.str("life_state"))) {
			return false;
		}
		return conditionAmong(tx, c, Combat.INCAPACITATING_CONDITIONS).isEmpty();
	}

	/** The first of the given conditions the character currently has, if any. */
	private static Optional<String> conditionAmong(Tx tx, Row c, List<String> conditions) {
		for (String cond : conditions) {
			if (tx.count("SELECT COUNT(*) FROM active_effect WHERE character_id = ? AND condition_ref = ?", c.id(),
					RuntimeService.conditionRef(cond)) > 0) {
				return Optional.of(cond);
			}
		}
		return Optional.empty();
	}

	/**
	 * See {@link ShieldSpell#canCast}: reaction, Shield prepared, a slot, no slot spent on the
	 * target's own turn, and no untrained armor.
	 */
	private boolean canCastShield(Tx tx, Row target, Row targetParticipant) {
		return ShieldSpell.canCast(tx, rules, tx.get("encounter", targetParticipant.lng("encounter_id")), target,
				targetParticipant);
	}

	private Map<String, Object> castShield(
		Tx tx, long campaignId, Row encounter, Row target, Row targetParticipant, long round) {
		return ShieldSpell.cast(tx, campaignId, encounter, target, targetParticipant, round);
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
					|| !Combat.hostile(stances, p.str("side"), moverParticipant.str("side"))) {
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
										+ " as a reaction (optionally choose weapon and nonlethal:true to knock out)."),
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
		return reactionAttack(tx, campaignId, encounter, reactor, reactorParticipant, mover, weapon, round, false);
	}

	private Map<String, Object> reactionAttack(
		Tx tx, long campaignId, Row encounter, Row reactor, Row reactorParticipant, Row mover, String weapon,
		long round, boolean nonlethal) {
		var action = new LinkedHashMap<String, Object>();
		action.put("kind", "ATTACK");
		action.put("target", Ref.of(Ref.CHARACTER, mover.id()));
		action.put("reaction", true);
		action.put("nonlethal", nonlethal);
		String selected = weapon != null && !weapon.isBlank() ? weapon
				: defaultMeleeAttack(tx, reactor).orElse("unarmed");
		action.put("weapon", selected);
		tx.update("encounter_participant", reactorParticipant.id(), Map.of("reaction_used", 1));
		Map<String, Object> result = attack(tx, campaignId, encounter, reactor, reactorParticipant, action, round,
				true);
		result.put("reactor", Ref.of(Ref.CHARACTER, reactor.id()));
		result.put("reactor_name", reactor.str("name"));
		return result;
	}

	/**
	 * The reactor's default melee attack: a stat-block melee action, then the first equipped or
	 * carried melee weapon. An empty result uses an Unarmed Strike.
	 */
	private Optional<String> defaultMeleeAttack(Tx tx, Row reactor) {
		if (RuntimeService.usesStatBlock(tx, reactor)) {
			Optional<String> creatureAttack = rules.find(reactor.str("origin_content_ref")).flatMap(d -> {
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> actions = (List<Map<String, Object>>) d.payload().getOrDefault("actions",
						List.of());
				return actions.stream().filter(a -> "MELEE_ATTACK".equals(String.valueOf(a.get("kind"))))
						.map(a -> String.valueOf(a.get("name"))).findFirst();
			});
			if (creatureAttack.isPresent()) {
				return creatureAttack;
			}
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
				Row moverParticipant = tx
						.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
								encounter.id(), mover.id())
						.orElse(null);
				boolean moverDown = !"ALIVE".equals(mover.str("life_state")) || moverParticipant == null
						|| !"ACTIVE".equals(moverParticipant.str("status"));
				if (option.equals("TAKE") && moverDown) {
					// Another reaction already brought the mover down: the choice is void and the reaction kept.
					result.put("cancelled",
							mover.str("name") + " is already down; " + chooser.str("name") + " keeps the reaction.");
					log(tx, campaignId, encounter.id(), round, chooser.id(), "REACTION", chooser.str("name")
							+ " has no one left to strike: " + mover.str("name") + " is already down.", null);
				} else if (option.equals("TAKE")) {
					if (!reactionAvailable(tx, chooserParticipant, chooser)) {
						throw RpgException.notAllowed(chooser.str("name") + " no longer has a reaction available.");
					}
					String weapon = choice.get("weapon") == null ? null : choice.get("weapon").toString();
					if (choice.containsKey("nonlethal") && !(choice.get("nonlethal") instanceof Boolean))
						throw RpgException.invalidArgument("choice.nonlethal must be a boolean.");
					result.put("attack", reactionAttack(tx, campaignId, encounter, chooser, chooserParticipant, mover,
							weapon, round, Boolean.TRUE.equals(choice.get("nonlethal"))));
				} else {
					log(tx, campaignId, encounter.id(), round, chooser.id(), "REACTION",
							chooser.str("name") + " lets " + mover.str("name") + " go.", null);
				}
			}
			case "SHIELD_SPELL" -> {
				Row attacker = tx.get("character", ((Number) ctx.get("attacker")).longValue());
				Row target = tx.get("character", ((Number) ctx.get("target")).longValue());
				Row targetParticipant = chooserParticipant;
				if (Boolean.TRUE.equals(ctx.get("magic_missile"))) {
					// Targeted by Magic Missile (SRD 5.2.1 "Shield"): CAST_SHIELD negates every dart aimed at the
					// chooser and raises AC until their next turn; DECLINE lands the darts rolled at casting.
					if (option.equals("CAST_SHIELD")) {
						if (!canCastShield(tx, target, targetParticipant)) {
							throw RpgException.notAllowed(
									target.str("name") + " can no longer cast Shield (no reaction or slot).");
						}
						result.put("shield", castShield(tx, campaignId, encounter, target, targetParticipant, round));
						result.put("damage", 0);
						result.put("negated", "Shield: no damage from Magic Missile (SRD 5.2.1)");
					} else {
						result.put("darts", ShieldSpell.applyDeferredDarts(tx, rules, roller, campaignId, encounter,
								attacker, target, targetParticipant, ctx, round));
					}
					break;
				}
				Combat.AttackProfile profile = resolveProfile(tx, attacker, (Map<String, Object>) ctx.get("action"))
						.profile();
				// The ammunition is already spent; restore the effective modifiers recorded with the attack.
				if (ctx.get("ammunition") instanceof Map<?, ?> ammunition) {
					int bonus = ammunition.get("bonus") instanceof Number n ? n.intValue() : 0;
					Map<String, String> extra = ammunition.get("extra_damage") instanceof Map<?, ?> damage
							? (Map<String, String>) damage : null;
					profile = Combat.withAmmunition(profile, bonus, extra);
				}
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
			if (remaining.isEmpty() && GridCombat.isGrid(encounter)) {
				Map<String, Object> resumed = resumeGridMovement(tx, campaignId, encounter);
				if (!resumed.isEmpty())
					result.put("movement_resumed", resumed);
				remaining = pendingChoices(tx, encounter.id());
				for (Map<String, Object> pending : remaining) {
					Row transaction = tx.get("pending_transaction",
							Ref.id(pending.get("transaction").toString(), Ref.TRANSACTION));
					var next = new LinkedHashMap<>(transaction.map("payload_json"));
					next.put("advance_turn_after", Boolean.TRUE.equals(payload.get("advance_turn_after")));
					tx.update("pending_transaction", transaction.id(), Map.of("payload_json", Json.write(next)));
				}
			}
			result.put("remaining_choices", remaining);
			if (remaining.isEmpty()) {
				tx.update("encounter", encounter.id(), Map.of("status", "RUNNING"));
				Row live = tx.get("encounter", encounter.id());
				Row currentParticipant = live.isNull("turn_participant_id") ? null
						: tx.get("encounter_participant", live.lng("turn_participant_id"));
				boolean currentCannotAct = currentParticipant != null
						&& (!"ACTIVE".equals(currentParticipant.str("status")) || !"ALIVE"
								.equals(tx.get("character", currentParticipant.lng("character_id")).str("life_state")));
				if (Boolean.TRUE.equals(payload.get("advance_turn_after")) || currentCannotAct) {
					result.put("turn_advanced", true);
					result.putAll(advanceTurn(tx, campaignId, live));
				}
			}
			Row after = tx.get("encounter", encounter.id());
			tx.update("encounter", after.id(), Map.of("revision", after.lng("revision") + 1));
			result.put("encounter_revision", after.lng("revision") + 1);
			result.put("state", state(tx, tx.get("encounter", encounter.id()), 5));
			automaticDeathReports(tx, campaignId, encounter, result);
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return result;
		});
	}

	@SuppressWarnings("unchecked")
	private Optional<Map<String, Object>> creatureAction(Row actor, String name) {
		if (actor.isNull("origin_content_ref")) {
			return Optional.empty();
		}
		// A promoted companion reaches these only by name, and only when it carries no weapon of that name;
		// see resolveProfile.
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
		if ("POTION".equals(item.payload().get("type"))) {
			// "Drinking a potion or administering it to another creature requires a Bonus Action" (SRD 5.2.1
			// "Potions"): the same Bonus Action the Light extra attack and bonus-action spells spend.
			TurnEconomy.spendBonusAction(tx, encounter, actor.id(), "drinking " + item.name());
			out.put("action_cost", "BONUS_ACTION");
		}
		Map<String, Object> used = InventoryService.useConsumable(tx, rules, campaignId, roller, entry, item, target);
		if (used != null) {
			out.putAll(used);
			log(tx, campaignId, encounter.id(), round, actor.id(), "USE_ITEM", actor.str("name") + " uses a "
					+ item.name() + " on " + target.str("name") + ": +" + out.get("healed") + " HP.", null);
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
			// Heightened Spell: Disadvantage on every save against the spell, this repeat included (SRD 5.2.1).
			String d20 = Boolean.TRUE.equals(spec.get("disadvantage")) ? "2d20kl1" : "1d20";
			Roll roll = roller.roll(d20 + (bonus >= 0 ? "+" + bonus : Integer.toString(bonus)));
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
				entry.put("failures", failures);
				String escalation = spec.get("on_second_failure") == null ? null
						: String.valueOf(spec.get("on_second_failure"));
				// The other keys on the effect (ends_on_damage, Shield's modifiers) survive the bookkeeping.
				var kept = new LinkedHashMap<String, Object>(mods);
				kept.put("repeat_save", spec);
				if (failures >= 2 && escalation != null) {
					// Sleep: "repeat the save. If the target fails the second save, the target has the Unconscious
					// condition for the duration" — one repeat, not one per turn: the instruction leaves the effect.
					kept.remove("repeat_save");
					Map<String, Object> escalatedMods = Boolean.TRUE.equals(mods.get("ends_on_damage"))
							? Map.of("ends_on_damage", true) : null;
					se.hirt.mcp.rpg.rules.Effects.add(tx, campaignId, c.id(), e.lng("source_character_id"),
							e.str("source_content_ref"), e.str("source_description"),
							RuntimeService.conditionRef(escalation), escalatedMods,
							e.isNull("duration_json") ? null : e.map("duration_json"),
							e.lng("concentration_character_id"), e.str("source_content_ref") + ":" + escalation,
							"MECHANICAL_CONSEQUENCE");
					entry.put("escalated_to", escalation);
					log(tx, campaignId, encounter.id(), encounter.lng("round"), c.id(), "SAVE",
							c.str("name") + " fails a second save against " + spec.get("spell") + " and is "
									+ escalation.toLowerCase() + ".",
							null);
				}
				var cols = new LinkedHashMap<String, Object>();
				cols.put("modifier_json", kept.isEmpty() ? null : Json.write(kept));
				tx.update("active_effect", e.id(), cols);
			}
			saves.add(entry);
		}
		return saves.isEmpty() ? null : Map.of("saves", saves);
	}

	// ── turn advancement ───────────────────────────────────────────────

	/**
	 * While the turn pointer rests on a participant who cannot take a turn (dead, defeated,
	 * removed, dying, or no pointer at all), moves it on with the ordinary turn advance. Returns
	 * the names skipped, in order.
	 */
	private List<String> skipTurnsNobodyCanTake(Tx tx, long campaignId, Row encounter) {
		var skipped = new ArrayList<String>();
		List<Row> order = turnOrder(tx, encounter.id());
		for (int guard = 0; guard <= order.size(); guard++) {
			Row live = tx.get("encounter", encounter.id());
			Long turnId = live.lng("turn_participant_id");
			if (turnId == null) {
				// Nobody's turn: the first participant in order who can act takes it, without a new round.
				for (Row p : order) {
					Row c = tx.get("character", p.lng("character_id"));
					if ("ACTIVE".equals(p.str("status")) && "ALIVE".equals(c.str("life_state"))) {
						tx.update("encounter", live.id(), Map.of("turn_participant_id", p.id()));
						tx.update("encounter_participant", p.id(), Map.of("reaction_used", 0));
						skipped.add("(nobody's turn) -> " + (GridCombat.knownToParty(tx, live).contains(c.id())
								? c.str("name") : "an unobserved participant"));
						log(tx, campaignId, live.id(), live.lng("round"), null, "TURN_SKIPPED",
								"The turn passes to " + c.str("name") + ".", null);
						return skipped;
					}
				}
				return skipped;
			}
			Row p = tx.get("encounter_participant", turnId);
			Row c = tx.get("character", p.lng("character_id"));
			if ("ACTIVE".equals(p.str("status")) && "ALIVE".equals(c.str("life_state"))) {
				return skipped;
			}
			if ("DEAD".equals(c.str("life_state")) && "ACTIVE".equals(p.str("status"))) {
				tx.update("encounter_participant", p.id(), Map.of("status", "DEFEATED"));
			}
			String why = "ACTIVE".equals(p.str("status")) ? c.str("life_state") : p.str("status");
			skipped.add(
					GridCombat.knownToParty(tx, live).contains(c.id()) ? c.str("name") : "an unobserved participant");
			log(tx, campaignId, live.id(), live.lng("round"), c.id(), "TURN_SKIPPED",
					c.str("name") + " cannot take a turn (" + why + "); the turn moves on.", null);
			advanceTurn(tx, campaignId, live);
		}
		return skipped;
	}

	/**
	 * Collect every save emitted by this operation, including saved-turn reconciliation and
	 * reactions.
	 */
	private static void automaticDeathReports(Tx tx, long campaignId, Row encounter, Map<String, Object> result) {
		var reports = new ArrayList<Map<String, Object>>();
		boolean privateReport = false;
		for (Row entry : tx.query(
				"SELECT * FROM encounter_log WHERE encounter_id=? AND journal_id=? AND kind='DEATH_SAVE' ORDER BY id",
				encounter.id(), tx.journalId())) {
			Map<String, Object> payload = entry.map("payload_json");
			if (!Boolean.TRUE.equals(payload.get("party_observed"))) {
				privateReport = true;
				continue;
			}
			var report = new LinkedHashMap<String, Object>();
			for (String field : List.of("kind", "character", "name", "round", "death_save", "natural", "outcome",
					"successes", "failures", "stable", "life_state", "died", "summary", "at_seconds"))
				if (payload.get(field) != null)
					report.put(field, payload.get(field));
			reports.add(report);
		}
		// advanceTurn also serves internal reconciliation; only this event-time projection is public.
		result.remove("death_saves");
		if (!reports.isEmpty())
			result.put("death_saves", reports);
		var deaths = reports.stream().filter(report -> Boolean.TRUE.equals(report.get("died"))).map(report -> {
			var death = new LinkedHashMap<String, Object>();
			for (String field : List.of("character", "name", "round", "summary"))
				death.put(field, report.get(field));
			death.put("cause", "FAILED_DEATH_SAVES");
			return death;
		}).toList();
		if (!deaths.isEmpty())
			result.put("deaths", deaths);
		if (privateReport)
			result.put("gm_handoff", gmHandoff(encounter));
		tx.queryOne(
				"SELECT c.* FROM player_control_assignment p JOIN character c ON c.id=p.character_id WHERE p.campaign_id=? AND p.active=1",
				campaignId).filter(pc -> "DEAD".equals(pc.str("life_state"))).ifPresent(pc -> {
					result.put("player_character_status", "DEAD");
					result.put("encounter_end_required", true);
					result.put("next_step",
							pc.str("name") + " has died. " + (tx.count(
									"SELECT COUNT(*) FROM pending_transaction WHERE campaign_id=? AND status='OPEN'",
									campaignId) > 0 ? "Resolve the remaining pending choices, then call end_encounter. "
											: "Call end_encounter. ")
									+ "Then use get_continuation_options for the campaign's continuation choices.");
				});
	}

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
				if (GridCombat.isGrid(encounter))
					chargeGridRound(tx, campaignId, encounter, round);
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
			if ("DEAD".equals(c.str("life_state"))) {
				// Killed outside the attack loop (a fall, a fiat): out of the fight from here on.
				tx.update("encounter_participant", p.id(), Map.of("status", "DEFEATED"));
				continue;
			}
			// Effects that last until the start of this participant's turn expire now.
			se.hirt.mcp.rpg.rules.Effects.expireAtTurnStart(tx, encounter.id(), p.id());
			c = tx.get("character", c.id());
			if ("DYING".equals(c.str("life_state"))) {
				Map<String, Object> saves = c.isNull("death_saves_json") ? Combat.freshDeathSaves()
						: c.map("death_saves_json");
				if (!Boolean.TRUE.equals(saves.get("stable"))) {
					boolean observed = GridCombat.knownToParty(tx, encounter).contains(c.id());
					var ev = new LinkedHashMap<>(DyingProgression.resolveSave(tx, roller, campaignId, c,
							observed ? "PARTY_KNOWN" : "GM_ONLY",
							Map.of("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()))));
					ev.put("round", round);
					String summary = c.str("name") + " death saving throw in round " + round + ": rolled "
							+ ev.get("death_save") + " (natural " + ev.get("natural") + "), " + ev.get("outcome") + " ("
							+ ev.get("successes") + " successes, " + ev.get("failures") + " failures)."
							+ (Boolean.TRUE.equals(ev.get("died")) ? " " + c.str("name") + " dies." : "");
					ev.put("summary", summary);
					log(tx, campaignId, encounter.id(), round, c.id(), "DEATH_SAVE", summary, ev);
					if (observed)
						events.add(ev);
					c = tx.get("character", c.id());
					if ("DEAD".equals(c.str("life_state")))
						continue;
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
			boolean player = TacticalControl.playerControlled(tx, campaignId, c.id());
			out.put("next_turn",
					Map.of("participant", p.id(), "character", Ref.of(Ref.CHARACTER, c.id()), "name", c.str("name"),
							"side", p.str("side"), "controller", player ? "PLAYER" : "DM", "tactical_control",
							TacticalControl.assignment(tx, campaignId, c.id())));
			out.put("awaiting_player_input", player);
			out.put("awaiting_gm", !player);
			if (GridCombat.isGrid(encounter)
					&& !GridCombat.nameKnownToParty(tx, tx.get("encounter", encounter.id()), c.id())) {
				out.put("next_turn", null);
				out.put("awaiting_gm", true);
				out.put("gm_handoff", gmHandoff(encounter));
			}
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

	private static void chargeGridRound(Tx tx, long campaignId, Row encounter, long round) {
		var environment = new LinkedHashMap<>(tx.get("encounter", encounter.id()).map("environment_json"));
		var grid = new LinkedHashMap<>(SceneService.map(environment.get("grid")));
		if (SceneService.number(grid, "charged_through_round", 0).longValue() >= round)
			return;
		WorldTimeline.advanceRound(tx, campaignId);
		grid.put("charged_through_round", round);
		environment.put("grid", grid);
		tx.update("encounter", encounter.id(), Map.of("environment_json", Json.write(environment)));
	}

	/**
	 * Explicit, journaled adoption for an already-running legacy encounter; no initiative is
	 * rerolled.
	 */
	public Map<String, Object> convertToGrid(
		String operationId, String campaignRef, String encounterRef, Map<String, Object> options) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("encounter", encounterRef);
		args.put("options", options);
		return db.mutate(Database.Mutation.of("convert_encounter_to_grid", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "convert_encounter_to_grid");
			Row encounter = encounter(tx, campaignId, encounterRef);
			if ("ENDED".equals(encounter.str("status")))
				throw RpgException.notAllowed("Convert a live encounter before its next grid action.");
			if (!GridCombat.isGrid(encounter)) {
				List<Row> participants = tx
						.query("SELECT * FROM encounter_participant WHERE encounter_id=? ORDER BY id", encounter.id());
				for (Map<String, Object> pending : pendingChoices(tx, encounter.id())) {
					if (!"OPPORTUNITY_ATTACK".equals(pending.get("kind")))
						continue;
					Row transaction = tx.get("pending_transaction",
							Ref.id(pending.get("transaction").toString(), Ref.TRANSACTION));
					Map<String, Object> context = SceneService.map(transaction.map("payload_json").get("context"));
					if (context.get("mover") instanceof Number mover
							&& "ALIVE".equals(tx.get("character", mover.longValue()).str("life_state"))
							&& tx.count("SELECT COUNT(*) FROM map_actor WHERE campaign_id=? AND character_id=?",
									campaignId, mover.longValue()) == 0
							&& (options == null || SceneService.maps(options.get("placements")).isEmpty()))
						throw RpgException.validation(List.of(new Violation("options.placements",
								"LEGACY_REACTION_DEPLOYMENT",
								"Place the unrolled opportunity reaction at its pre-departure reach boundary; a legacy destination zone is not an exact historical position.")));
				}
				var zones = new LinkedHashMap<String, Object>();
				for (Row participant : participants)
					zones.put(Ref.of(Ref.CHARACTER, participant.lng("character_id")),
							participant.str("position_zone") == null ? "near" : participant.str("position_zone"));
				var environment = new LinkedHashMap<>(encounter.map("environment_json"));
				var grid = new LinkedHashMap<>(
						GridCombat.ensureEncounterScene(tx, rules, campaignId, encounter.lng("location_id"),
								participants.stream().map(p -> tx.get("character", p.lng("character_id"))).toList(),
								zones, options));
				grid.put("legacy_conversion", true);
				grid.put("placement_provenance",
						"Derived from coarse legacy zones and explicit DM deployment; not historical exact coordinates.");
				grid.put("charged_through_round", Math.max(0, encounter.lng("round") - 1));
				environment.put("grid", grid);
				tx.update("encounter", encounter.id(), Map.of("spatial_model", "GRID", "environment_json",
						Json.write(environment), "revision", encounter.lng("revision") + 1));
				Map<String, Object> remaining = options == null ? Map.of()
						: SceneService.map(options.get("remaining_movement_ft"));
				for (Row participant : participants) {
					var flags = new LinkedHashMap<>(participant.map("once_per_turn_json"));
					String turn = TurnEconomy.turnKey(encounter);
					long budget = Exhaustion.speed(tx, rules, tx.get("character", participant.lng("character_id")))
							* 12L;
					long spent = turn.equals(flags.get(TurnEconomy.MOVED)) ? budget
							: turn.equals(flags.get(TurnEconomy.STOOD_UP)) ? budget / 2 : 0;
					String ref = Ref.of(Ref.CHARACTER, participant.lng("character_id"));
					if (remaining.get(ref) instanceof Number value) {
						double feet = value.doubleValue();
						if (!Double.isFinite(feet) || feet < 0 || feet * 12 > budget)
							throw RpgException.invalidArgument(
									"Legacy remaining_movement_ft must fit the actor's current Speed.");
						spent = budget - Math.round(feet * 12);
					}
					flags.put("grid_movement", Map.of("turn", turn, "spent_inches", spent, "dash_inches", 0L));
					tx.update("encounter_participant", participant.id(),
							Map.of("once_per_turn_json", Json.write(flags)));
				}
			}
			var result = new LinkedHashMap<String, Object>();
			result.put("state", state(tx, tx.get("encounter", encounter.id()), 10));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	public Map<String, Object> end(
		String operationId, String campaignRef, String encounterRef, String outcome, String summary) {
		return end(operationId, campaignRef, encounterRef, outcome, summary, null);
	}

	public Map<String, Object> end(
		String operationId, String campaignRef, String encounterRef, String outcome, String summary, String narrative) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("encounter", encounterRef);
		args.put("outcome", outcome);
		args.put("summary", summary);
		args.put("narrative", narrative);
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
			if (GridCombat.isGrid(encounter)) {
				Optional<Row> controlled = tx.queryOne(
						"SELECT * FROM player_control_assignment WHERE campaign_id=? AND active=1", campaignId);
				if (controlled.isPresent())
					TravelJourneyService.captureFinalPosition(tx, campaignId, GridCombat.scene(tx, encounter).id(),
							Ref.of(Ref.CHARACTER, controlled.get().lng("character_id")));
			}

			// Mechanically defined XP: the pool fixed at start pays out when the encounter is overcome,
			// however it was overcome (RULES_ENGINE.md §6); defeated hostiles are still itemized.
			long defeatedXp = 0;
			var defeated = new ArrayList<String>();
			var recipients = new ArrayList<Row>();
			// Start-of-turn effects die with their encounter, including those on removed participants; round
			// durations are converted to game time once the clock has moved (below).
			se.hirt.mcp.rpg.rules.Effects.expireOnEncounterEnd(tx, encounter.id());
			for (Row p : participants) {
				Row c = tx.get("character", p.lng("character_id"));
				if (p.str("side").equals(partySide)) {
					// Everyone who stood on the party's side earns, class or stat block alike; who the award
					// actually lands on is the campaign's xp_policy (RULES_ENGINE.md §6).
					if (!"DEAD".equals(c.str("life_state"))) {
						recipients.add(c);
					}
				} else if (("DEFEATED".equals(p.str("status")) || "DEAD".equals(c.str("life_state")))
						&& !"REMOVED".equals(p.str("status")) && Combat.hostile(stances, partySide, p.str("side"))) {
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
			long xpPool = overcome && sides.get("xp_pool") instanceof Number pool ? pool.longValue() : defeatedXp;
			var awards = new ArrayList<Map<String, Object>>();
			List<Row> earners = se.hirt.mcp.rpg.progression.PartyXp.recipients(tx, campaign, recipients);
			if (xpPool > 0 && !earners.isEmpty()) {
				long share = xpPool / earners.size();
				for (Row c : earners) {
					awards.add(RuntimeService.grantXp(tx, rules, tx.get("character", c.id()), share));
				}
				awards.addAll(se.hirt.mcp.rpg.progression.PartyXp.lockstep(tx, rules, campaign));
			}
			// Grid rounds have already advanced world time; charge the final partial round only once.
			long rounds = encounter.lng("round");
			long minutes = GridCombat.isGrid(encounter) ? 0 : se.hirt.mcp.rpg.rules.Effects.minutesForRounds(rounds);
			WorldTimeline.Progress legacyTime = null;
			if (GridCombat.isGrid(encounter))
				chargeGridRound(tx, campaignId, encounter, rounds);
			else
				legacyTime = WorldTimeline.advanceSeconds(tx, campaignId, Math.multiplyExact(minutes, 60),
						"LEGACY_COMBAT", false);
			long newSeq = GameTime.currentSeq(tx, campaignId);
			// A 10-round condition is a 1-minute condition (SRD 5.2.1 "Combat"): the rounds it has left after the
			// final round continue on the clock instead of ending with the fight.
			int converted = se.hirt.mcp.rpg.rules.Effects.convertRoundsAtEncounterEnd(tx, encounter.id(), rounds,
					newSeq);
			se.hirt.mcp.rpg.rules.Effects.expireByTime(tx, campaignId, newSeq);

			var cols = new LinkedHashMap<String, Object>();
			cols.put("status", "ENDED");
			cols.put("turn_participant_id", null);
			cols.put("revision", encounter.lng("revision") + 1);
			tx.update("encounter", encounter.id(), cols);
			DyingProgression.resumeAfterEncounter(tx, campaignId, encounter.id());
			TacticalControl.endEncounter(tx, campaignId, encounter.id());
			log(tx, campaignId, encounter.id(), rounds, null, "ENCOUNTER_ENDED",
					"Encounter ends: " + o + (summary == null ? "" : " — " + summary), Map.of("outcome", o));

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
			Map<String, Object> travelRoster = TravelJourneyService.reconcileInTx(tx, campaignId);
			Map<String, Object> record = EncounterDigest.build(tx, tx.get("encounter", encounter.id()), o, summary,
					narrative, xpPool, awards);
			Map<String, Object> publicDigest = SceneService.map(record.get("party_digest"));
			List<Long> knownParticipants = SceneService.maps(publicDigest.get("final_roster")).stream()
					.map(person -> Ref.id(person.get("character").toString(), Ref.CHARACTER)).toList();
			long resolvedEventId = LedgerService.append(tx, campaignId,
					new LedgerService.EventSpec("ENCOUNTER_RESOLVED",
							"Encounter " + o.toLowerCase().replace('_', ' ') + "; " + rounds + " rounds.",
							knownParticipants, pcDead ? "MAJOR" : "NOTABLE", "PARTY_KNOWN", "MECHANICAL_CONSEQUENCE",
							null, encounter.lng("location_id"), EncounterDigest.story(publicDigest),
							Map.of("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()), "outcome", o, "xp_awarded",
									xpTotal, "rounds", rounds, "digest", publicDigest)));
			record.put("resolved_event", Ref.of(Ref.EVENT, resolvedEventId));
			tx.update("encounter", encounter.id(), Map.of("record_json", record));
			var ccols = new LinkedHashMap<String, Object>();
			ccols.put("harness_state", next.name());
			ccols.put("status", campaignStatus);
			ccols.put("revision", campaign.lng("revision") + 1);
			tx.update("campaign", campaignId, ccols);

			var result = new LinkedHashMap<String, Object>();
			result.put("encounter", Ref.of(Ref.ENCOUNTER, encounter.id()));
			result.put("outcome", o);
			if (!travelRoster.isEmpty())
				result.put("travel_roster", travelRoster);
			result.put("current_board", GridCombat.currentBoard(tx, campaignId));
			result.put("party_positions", tx.query(
					"SELECT a.*,c.name,s.name AS map_name FROM map_actor a JOIN character c ON c.id=a.character_id "
							+ "JOIN map_scene s ON s.id=a.scene_id WHERE a.campaign_id=? AND "
							+ "(EXISTS(SELECT 1 FROM party_membership m WHERE m.character_id=c.id AND m.state IN ('ACTIVE','GUEST','SEPARATED')) "
							+ "OR EXISTS(SELECT 1 FROM player_control_assignment p WHERE p.character_id=c.id AND p.active=1)) ORDER BY c.id",
					campaignId).stream()
					.map(a -> Map.<String, Object> of("character", Ref.of(Ref.CHARACTER, a.lng("character_id")), "name",
							a.str("name"), "map_ref", Ref.of(Ref.MAP, a.lng("scene_id")), "map_name", a.str("map_name"),
							"move_origin_ft", List.of(a.get("x_ft"), a.get("y_ft")), "level", a.intOr("level", 0)))
					.toList());
			result.put("rounds", rounds);
			result.put("record", EncounterDigest.gmRecord(record));
			result.put("party_digest", publicDigest);
			List<Map<String, Object>> annotationSlots = EncounterDigest.annotationSlots(record);
			result.put("highlights_needing_narration", annotationSlots);
			result.put("highlights_needing_narration_bytes", Json.utf8(Json.write(annotationSlots)).length);
			result.put("highlights_needing_narration_max_bytes", EncounterDigest.ANNOTATION_SLOTS_MAX_BYTES);
			result.put("highlights_needing_narration_detail",
					"Brief prompts; full mechanics remain in record.highlights.");
			result.put("full_gm_record",
					Map.of("tool", "get_encounter_state", "arguments", Map.of("campaign", campaignRef, "encounter",
							Ref.of(Ref.ENCOUNTER, encounter.id()), "audience", "GM"), "field", "record"));
			result.put("annotation_tool", "annotate_encounter");
			result.put("resolved_event", Ref.of(Ref.EVENT, resolvedEventId));
			result.put("elapsed_minutes", minutes);
			if (legacyTime != null) {
				result.put("elapsed_seconds", legacyTime.elapsedSeconds());
				result.put("consequences", legacyTime.consequences());
			}
			if (GridCombat.isGrid(encounter)) {
				long start = SceneService.number(GridCombat.binding(encounter), "started_seconds",
						GameTime.currentSeconds(tx, campaignId)).longValue();
				result.put("elapsed_seconds", GameTime.currentSeconds(tx, campaignId) - start);
			}
			if (converted > 0) {
				result.put("round_effects_converted_to_minutes", converted);
			}
			result.put("defeated", defeated);
			result.put("xp_pool", xpPool);
			result.put("xp_award_basis", overcome ? "ENCOUNTER_OVERCOME" : "DEFEATED_HOSTILES");
			result.put("xp_policy", se.hirt.mcp.rpg.progression.PartyXp.policy(campaign));
			result.put("xp_awarded", awards);
			se.hirt.mcp.rpg.progression.LevelUpService.companionAdvancement(tx, rules, roller,
					tx.get("campaign", campaignId), result);
			result.put("level_up_eligible", awards.stream().filter(a -> Boolean.TRUE.equals(a.get("level_up_eligible")))
					.map(a -> a.get("character")).toList());
			result.put("game_time", GameTime.toMap(tx, campaignId, newSeq));
			List<Map<String, Object>> recovery = AmmunitionRecovery.offers(tx, rules,
					tx.get("encounter", encounter.id()));
			if (!recovery.isEmpty()) {
				result.put("ammunition_recovery", recovery);
			}
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
			if (major) {
				// A major encounter is where a level-appropriate magic item turns up (RULES_ENGINE.md §10).
				result.put("treasure",
						se.hirt.mcp.rpg.inventory.Treasure.nudge(tx, rules, roller, campaignId, "MAJOR_ENCOUNTER", 2));
			}
			String due = se.hirt.mcp.rpg.session.ChronicleService.dueWarning(tx, campaignId);
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), due == null ? null : List.of(due)));
			return result;
		});
	}

	public Map<String, Object> annotate(
		String operationId, String campaignRef, String encounterRef, Map<String, String> narrations) {
		if (narrations == null)
			throw RpgException.invalidArgument("Supply selected highlight slots and narration.");
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		return db.mutate(Database.Mutation.of("annotate_encounter", campaignId, operationId, "GM",
				Map.of("campaign", campaignRef, "encounter", encounterRef, "narrations", narrations)), tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "annotate_encounter");
					var response = EncounterDigest.annotate(tx, encounter(tx, campaignId, encounterRef), narrations);
					response.put("meta", Harness.meta(campaign, List.of()));
					return response;
				});
	}

	public Map<String, Object> recoverAmmunition(
		String operationId, String campaignRef, String encounterRef, String characterRef,
		List<Map<String, Object>> selection) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("encounter", encounterRef);
		args.put("character", characterRef);
		args.put("selection", selection);
		return db.mutate(Database.Mutation.of("recover_ammunition", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "recover_ammunition");
			Row encounter = encounter(tx, campaignId, encounterRef);
			Row character = CharacterService.character(tx, campaignId, characterRef);
			return AmmunitionRecovery.recover(tx, rules, roller, campaign, encounter, character, selection);
		});
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
		long logId = tx.insert("encounter_log", cols);
		EncounterDigest.recorded(tx, encounterId, logId, actorId, kind);
	}
}
