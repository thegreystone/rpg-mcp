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

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.session.GameTime;

import java.time.Instant;
import java.util.*;

/**
 * Rests (MCP_PROTOCOL.md §13.4; SRD 5.2.1 Rules Glossary "Short Rest" / "Long Rest" — [verify]) and the explicit,
 * audited GM override (§20, DESIGN.md §15.1): the only general exceptional mutation, always labeled.
 */
public final class RestService {

	public static final String HIT_DICE = "hit_dice";
	public static final Set<String> OVERRIDE_KINDS = Set.of("ADJUST_HP", "SET_MAX_HP", "SET_ARMOR_CLASS",
			"SET_CAMPAIGN_RULE", "SET_LIFE_STATE", "SET_MONEY", "SET_XP",
			"SET_ABILITY_SCORE", "REMOVE_ENCOUNTER_PARTICIPANT", "SET_LOCATION", "SET_CONNECTION_STATE");

	private final Database db;
	private final RulesData rules;
	private final RollService roller;
	private final CharacterService characters;

	public RestService(Database db, RulesData rules, RollService roller, CharacterService characters) {
		this.db = db;
		this.rules = rules;
		this.roller = roller;
		this.characters = characters;
	}

	// ── perform_rest ───────────────────────────────────────────────────

	public Map<String, Object> rest(
			String operationId, String campaignRef, String kind, Map<String, Object> hitDice,
			List<String> characterRefs) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("kind", kind);
		args.put("hit_dice", hitDice);
		args.put("characters", characterRefs);
		return db.mutate(Database.Mutation.of("perform_rest", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "perform_rest");
			String k = kind == null ? "" : kind.toUpperCase();
			if (!k.equals("SHORT") && !k.equals("LONG")) {
				throw RpgException.invalidArgument("kind must be SHORT or LONG.");
			}
			var resters = new ArrayList<Row>();
			if (characterRefs == null || characterRefs.isEmpty()) {
				for (Row m : tx.query(
						"SELECT c.* FROM party_membership m JOIN character c ON c.id = m.character_id WHERE m.campaign_id = ? " + "AND m.state IN ('ACTIVE','GUEST') AND c.lifecycle = 'ACTIVE' AND c.life_state <> 'DEAD' ORDER BY m.id",
						campaignId)) {
					resters.add(m);
				}
			} else {
				for (String ref : characterRefs) {
					resters.add(CharacterService.character(tx, campaignId, ref));
				}
			}
			var results = new ArrayList<Map<String, Object>>();
			for (Row c : resters) {
				if (!"ACTIVE".equals(c.str("lifecycle")) || "DEAD".equals(c.str("life_state"))) {
					continue;
				}
				results.add(k.equals("SHORT") ? shortRest(tx, campaignId, c, hitDice) : longRest(tx, campaignId, c));
			}
			long minutes = k.equals("SHORT") ? 60 : 8 * 60;
			Row clock = GameTime.clock(tx, campaignId);
			long newSeq = clock.lng("seq") + minutes;
			tx.update("game_clock", clock.id(), Map.of("seq", newSeq, "instant", GameTime.render(newSeq)));
			int expired = Effects.expireByTime(tx, campaignId, newSeq);
			LedgerService.append(tx, campaignId, new LedgerService.EventSpec("REST",
					(k.equals("SHORT") ? "The party took a short rest." : "The party took a long rest."),
					resters.stream().map(Row::id).toList(), "MINOR", "PARTY_KNOWN", "GM", null,
					campaign.lng("current_location_id"), null, Map.of("kind", k)));
			var result = new LinkedHashMap<String, Object>();
			result.put("kind", k);
			result.put("completed", true);
			result.put("interrupted", false);
			result.put("elapsed_minutes", minutes);
			result.put("characters", results);
			result.put("game_time", GameTime.toMap(newSeq));
			result.put("expired_effects", expired);
			result.put("note",
					"Interruptions (REST_INTERRUPT) are not modelled yet: if the fiction interrupts a rest, do not call perform_rest for it.");
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	/** Hit Point Dice pool: max = character level, initialized lazily. */
	static Row hitDicePool(Tx tx, Row c, int level) {
		Optional<Row> pool = tx.queryOne("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref = ?",
				c.id(), HIT_DICE);
		if (pool.isPresent()) {
			if (pool.get().intOr("max", 0) != level) {
				tx.update("resource_state", pool.get().id(), Map.of("max", level, "current", Math.min(level,
						pool.get().intOr("current", 0) + Math.max(0, level - pool.get().intOr("max", 0)))));
				return tx.get("resource_state", pool.get().id());
			}
			return pool.get();
		}
		var cols = new LinkedHashMap<String, Object>();
		cols.put("character_id", c.id());
		cols.put("resource_ref", HIT_DICE);
		cols.put("current", level);
		cols.put("max", level);
		cols.put("recharge", "LONG_REST_HALF");
		return tx.get("resource_state", tx.insert("resource_state", cols));
	}

	private Map<String, Object> shortRest(Tx tx, long campaignId, Row c, Map<String, Object> hitDice) {
		var m = new LinkedHashMap<String, Object>();
		m.put("character", Ref.of(Ref.CHARACTER, c.id()));
		m.put("name", c.str("name"));
		Optional<Row> cls = tx.queryOne("SELECT * FROM character_class WHERE character_id = ? ORDER BY id LIMIT 1",
				c.id());
		int spend =
				hitDice == null ? 0 : hitDice.get(Ref.of(Ref.CHARACTER, c.id())) instanceof Number n ? n.intValue() : 0;
		if (spend > 0) {
			if (cls.isEmpty()) {
				throw RpgException.notAllowed(
						c.str("name") + " has no Hit Point Dice (creatures advance by stat block).");
			}
			int level = cls.get().intOr("level", 1);
			Row pool = hitDicePool(tx, c, level);
			if (pool.intOr("current", 0) < spend) {
				throw RpgException.insufficientResource(c.str("name") + " has " + pool.intOr("current",
						0) + " Hit Point Dice left, not " + spend + ".");
			}
			int hitDie = ((Number) rules.require(cls.get().str("class_ref"), "CLASS").payload()
					.get("hit_die")).intValue();
			int conMod = Rules.modifier(c.intOr("con_score", 10));
			int healed = 0;
			var rolls = new ArrayList<Map<String, Object>>();
			for (int i = 0; i < spend; i++) {
				Roll roll = roller.roll("1d" + hitDie + (conMod >= 0 ? "+" + conMod : Integer.toString(conMod)));
				CharacterService.recordRoll(tx, campaignId, "hit point die " + Ref.of(Ref.CHARACTER, c.id()), roll);
				healed += Math.max(0, roll.total());
				rolls.add(roll.toMap());
			}
			tx.update("resource_state", pool.id(), Map.of("current", pool.intOr("current", 0) - spend));
			Map<String, Object> heal = RuntimeService.heal(tx, tx.get("character", c.id()), healed, "short rest");
			m.put("hit_dice_spent", spend);
			m.put("hit_dice_left", pool.intOr("current", 0) - spend);
			m.put("rolls", rolls);
			m.putAll(heal);
		} else {
			m.put("hit_dice_spent", 0);
			cls.ifPresent(r -> m.put("hit_dice_left", hitDicePool(tx, c, r.intOr("level", 1)).intOr("current", 0)));
		}
		for (Row res : tx.query("SELECT * FROM resource_state WHERE character_id = ? AND recharge = 'SHORT_REST'",
				c.id())) {
			tx.update("resource_state", res.id(), Map.of("current", res.intOr("max", 0)));
		}
		Row after = tx.get("character", c.id());
		m.put("hp", Map.of("current", after.intOr("current_hp", 0), "max", after.intOr("max_hp", 0)));
		return m;
	}

	private Map<String, Object> longRest(Tx tx, long campaignId, Row c) {
		var m = new LinkedHashMap<String, Object>();
		m.put("character", Ref.of(Ref.CHARACTER, c.id()));
		m.put("name", c.str("name"));
		int before = c.intOr("current_hp", 0);
		int max = c.intOr("max_hp", 0);
		Map<String, Object> heal = RuntimeService.heal(tx, c, Math.max(0, max - before), "long rest");
		Row after = tx.get("character", c.id());
		var cols = new LinkedHashMap<String, Object>();
		// Exhaustion drops by one level (SRD 5.2.1 "Long Rest").
		if (after.intOr("exhaustion", 0) > 0) {
			cols.put("exhaustion", after.intOr("exhaustion", 0) - 1);
		}
		cols.put("revision", after.lng("revision") + 1);
		tx.update("character", c.id(), cols);
		Optional<Row> cls = tx.queryOne("SELECT * FROM character_class WHERE character_id = ? ORDER BY id LIMIT 1",
				c.id());
		cls.ifPresent(r -> {
			int level = r.intOr("level", 1);
			Row pool = hitDicePool(tx, c, level);
			// Regain spent Hit Point Dice equal to half the maximum, minimum 1 (SRD 5.2.1 "Long Rest").
			int regain = Math.max(1, level / 2);
			int current = Math.min(level, pool.intOr("current", 0) + regain);
			tx.update("resource_state", pool.id(), Map.of("current", current, "max", level));
			m.put("hit_dice", Map.of("current", current, "max", level));
		});
		// Other resources with a recharge rule recharge fully.
		for (Row res : tx.query("SELECT * FROM resource_state WHERE character_id = ? AND resource_ref <> ?", c.id(),
				HIT_DICE)) {
			tx.update("resource_state", res.id(), Map.of("current", res.intOr("max", 0)));
		}
		m.putAll(heal);
		m.put("hp", Map.of("current", after.intOr("current_hp", 0), "max", max));
		return m;
	}

	// ── apply_gm_override ──────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	public Map<String, Object> override(
			String operationId, String campaignRef, String kind, String targetRef, Map<String, Object> effect,
			String reason, Long expectedRevision) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("kind", kind);
		args.put("target", targetRef);
		args.put("effect", effect);
		args.put("reason", reason);
		return db.mutate(
				Database.Mutation.of("apply_gm_override", campaignId, operationId, "ADMINISTRATIVE_OVERRIDE", args),
				tx -> {
					Row campaign = Harness.requireMutation(tx, campaignRef, "apply_gm_override");
					Map<String, Object> policy = campaign.isNull("gm_override_policy_json") ? Map.of()
							: campaign.map("gm_override_policy_json");
					if (!"EXPLICIT_AUDITED".equals(policy.get("policy"))) {
						throw RpgException.policyDenied(
								"This campaign's GM override policy is " + policy.getOrDefault("policy",
										"unset") + "; overrides are not permitted.");
					}
					String k = kind == null ? "" : kind.toUpperCase();
					if (!OVERRIDE_KINDS.contains(k)) {
						throw RpgException.invalidArgument(
								"kind must be one of " + OVERRIDE_KINDS.stream().sorted().toList() + ".");
					}
					if (reason == null || reason.isBlank()) {
						throw RpgException.invalidArgument("An override needs a human-readable reason; it is audited.");
					}
					Map<String, Object> e = effect == null ? Map.of() : effect;
					var before = new LinkedHashMap<String, Object>();
					var after = new LinkedHashMap<String, Object>();
					String label;
					switch (k) {
					case "SET_CAMPAIGN_RULE" -> {
						// House rules are decided at setup, but a campaign in play is allowed to change its mind;
						// the change is audited like any other override (RULES_ENGINE.md §6).
						String key = String.valueOf(e.get("rule"));
						if (!se.hirt.mcp.rpg.campaign.SetupDraft.RULE_KEYS.contains(key)) {
							throw RpgException.invalidArgument(
									"effect.rule must be one of " + se.hirt.mcp.rpg.campaign.SetupDraft.RULE_KEYS.stream()
											.sorted().toList() + ".");
						}
						String value = e.get("value") == null ? null : String.valueOf(e.get("value")).toUpperCase();
						List<String> legal = switch (key) {
							case "hp_progression" -> se.hirt.mcp.rpg.campaign.SetupDraft.HP_PROGRESSIONS;
							case "xp_policy" -> se.hirt.mcp.rpg.campaign.SetupDraft.XP_POLICIES;
							case "companion_level_up" -> se.hirt.mcp.rpg.campaign.SetupDraft.COMPANION_LEVEL_UP;
							case "progression" -> se.hirt.mcp.rpg.campaign.SetupDraft.PROGRESSION;
							case "gm_override_policy" -> se.hirt.mcp.rpg.campaign.SetupDraft.OVERRIDE_POLICIES;
							default -> null;
						};
						if (legal == null) {
							throw RpgException.invalidArgument(
									"'" + key + "' is fixed once the campaign is committed; only " + "hp_progression, xp_policy, companion_level_up, progression and gm_override_policy may be retuned.");
						}
						if (value == null || !legal.contains(value)) {
							throw RpgException.invalidArgument(
									"effect.value for " + key + " must be one of " + legal + ".");
						}
						Map<String, Object> prefs = campaign.isNull("preferences_json") ? new LinkedHashMap<>()
								: new LinkedHashMap<>(campaign.map("preferences_json"));
						Map<String, Object> houseRules = prefs.get("rules") instanceof Map<?, ?> rc
								? new LinkedHashMap<>((Map<String, Object>) rc) : new LinkedHashMap<>();
						before.put(key, houseRules.get(key));
						houseRules.put(key, value);
						prefs.put("rules", houseRules);
						after.put(key, value);
						tx.update("campaign", campaignId,
								Map.of("preferences_json", Json.write(prefs), "revision",
										campaign.lng("revision") + 1));
						label = "campaign rule " + key + " " + before.get(key) + " → " + value;
					}
					case "ADJUST_HP", "SET_MAX_HP", "SET_ARMOR_CLASS", "SET_LIFE_STATE", "SET_MONEY", "SET_XP", "SET_ABILITY_SCORE", "SET_LOCATION" -> {
						Row c = CharacterService.character(tx, campaignId, targetRef);
						Harness.requireRevision(c, targetRef, expectedRevision);
						var cols = new LinkedHashMap<String, Object>();
						switch (k) {
						case "ADJUST_HP" -> {
							int max = c.intOr("max_hp", 0);
							int current = c.intOr("current_hp", 0);
							int target = e.get("set_to") instanceof Number n ? n.intValue()
									: current + (e.get("amount") instanceof Number a ? a.intValue() : 0);
							int hp = Math.max(0, Math.min(max, target));
							before.put("current_hp", current);
							after.put("current_hp", hp);
							cols.put("current_hp", hp);
							if (hp > 0 && !"ALIVE".equals(c.str("life_state")) && !"DEAD".equals(c.str("life_state"))) {
								cols.put("life_state", "ALIVE");
								cols.put("death_saves_json", null);
								for (Row fx : tx.query(
										"SELECT id FROM active_effect WHERE character_id = ? AND condition_ref = ?",
										c.id(), RuntimeService.UNCONSCIOUS_REF)) {
									tx.delete("active_effect", fx.id());
								}
							}
							label = c.str("name") + " HP " + current + " → " + hp;
						}
						case "SET_MAX_HP" -> {
							// The only way to record a maximum granted outside the level-up path: a subclass
							// feature, a boon, a permanent injury. Current HP moves with the maximum.
							int max = c.intOr("max_hp", 0);
							int target = e.get("set_to") instanceof Number n ? n.intValue()
									: max + (e.get("amount") instanceof Number a ? a.intValue() : 0);
							if (target < 1) {
								throw RpgException.invalidArgument("A maximum of hit points must be at least 1.");
							}
							int current = c.intOr("current_hp", 0);
							int hp = target > max ? current + (target - max) : Math.min(current, target);
							before.put("max_hp", max);
							before.put("current_hp", current);
							after.put("max_hp", target);
							after.put("current_hp", hp);
							cols.put("max_hp", target);
							cols.put("current_hp", hp);
							label = c.str("name") + " maximum hit points " + max + " → " + target;
						}
						case "SET_ARMOR_CLASS" -> {
							// A fixed Armor Class outside the equipment path: a subclass feature the engine does not
							// model yet (Draconic Resilience, Unarmored Defense), a boon. Effect bonuses and floors
							// still apply on top; {clear: true} returns the character to equipment-derived AC.
							Integer target = e.get("armor_class") instanceof Number n ? n.intValue() : null;
							if (target == null && !Boolean.TRUE.equals(e.get("clear"))) {
								throw RpgException.invalidArgument(
										"effect.armor_class (1–30) or effect.clear = true is required.");
							}
							if (target != null && (target < 1 || target > 30)) {
								throw RpgException.invalidArgument("effect.armor_class must be between 1 and 30.");
							}
							before.put("armor_class_override", c.integer("armor_class_override"));
							after.put("armor_class_override", target);
							cols.put("armor_class_override", target);
							label = c.str("name") + " Armor Class " + (target == null ? "derives from equipment again"
									: "fixed at " + target + " by fiat");
						}
						case "SET_LIFE_STATE" -> {
							String state = String.valueOf(e.get("life_state")).toUpperCase();
							if (!Set.of("ALIVE", "DYING", "DEAD").contains(state)) {
								throw RpgException.invalidArgument("effect.life_state must be ALIVE, DYING or DEAD.");
							}
							before.put("life_state", c.str("life_state"));
							after.put("life_state", state);
							cols.put("life_state", state);
							if (state.equals("ALIVE")) {
								int hp = Math.max(1, e.get("hp") instanceof Number n ? n.intValue()
										: Math.max(1, c.intOr("current_hp", 0)));
								cols.put("current_hp", Math.min(c.intOr("max_hp", hp), hp));
								cols.put("death_saves_json", null);
								for (Row fx : tx.query("SELECT id FROM active_effect WHERE character_id = ?", c.id())) {
									tx.delete("active_effect", fx.id());
								}
								// A revived former member rejoins nothing automatically; use update_party_membership.
							} else if (state.equals("DEAD")) {
								cols.put("current_hp", 0);
								RuntimeService.endMemberships(tx, campaignId, c.id(), "DEAD");
							} else {
								cols.put("current_hp", 0);
								cols.put("death_saves_json", Json.write(Combat.freshDeathSaves()));
							}
							label = c.str("name") + " life state " + c.str("life_state") + " → " + state;
						}
						case "SET_MONEY" -> {
							long cp = Money.parseCp(e.get("money"));
							before.put("money_cp", c.lng("money_cp"));
							after.put("money_cp", cp);
							cols.put("money_cp", cp);
							label = c.str("name") + " money " + Money.format(c.lng("money_cp")) + " → " + Money.format(
									cp);
						}
						case "SET_XP" -> {
							if (!(e.get("xp") instanceof Number n) || n.longValue() < 0) {
								throw RpgException.invalidArgument("effect.xp must be a non-negative integer.");
							}
							before.put("xp", c.lng("xp"));
							after.put("xp", n.longValue());
							cols.put("xp", n.longValue());
							label = c.str("name") + " XP " + c.lng("xp") + " → " + n.longValue();
						}
						case "SET_ABILITY_SCORE" -> {
							Ability a = Ability.parse(String.valueOf(e.get("ability")));
							if (!(e.get("score") instanceof Number n) || n.intValue() < 1 || n.intValue() > 30) {
								throw RpgException.invalidArgument("effect.score must be between 1 and 30.");
							}
							before.put(a.name(), c.integer(a.column()));
							after.put(a.name(), n.intValue());
							cols.put(a.column(), n.intValue());
							label = c.str("name") + " " + a.fullName() + " " + c.integer(
									a.column()) + " → " + n.intValue();
						}
						default -> {
							Row l = se.hirt.mcp.rpg.world.WorldService.location(tx, campaignId,
									String.valueOf(e.get("location")));
							before.put("location_id", c.lng("location_id"));
							after.put("location_id", l.id());
							cols.put("location_id", l.id());
							if (tx.count(
									"SELECT COUNT(*) FROM player_control_assignment WHERE campaign_id = ? AND character_id = ? AND active = 1",
									campaignId, c.id()) > 0) {
								tx.update("campaign", campaignId, Map.of("current_location_id", l.id()));
							}
							label = c.str("name") + " moved to " + l.str("name") + " by fiat";
						}
						}
						cols.put("revision", c.lng("revision") + 1);
						tx.update("character", c.id(), cols);
						tx.touched(Ref.of(Ref.CHARACTER, c.id()), c.lng("revision") + 1);
					}
					case "REMOVE_ENCOUNTER_PARTICIPANT" -> {
						Row c = CharacterService.character(tx, campaignId, targetRef);
						Row p = tx.queryOne(
										"SELECT p.* FROM encounter_participant p JOIN encounter e ON e.id = p.encounter_id WHERE p.character_id = ? AND e.status IN ('RUNNING','WAITING_CHOICE')",
										c.id())
								.orElseThrow(() -> RpgException.notFound(c.str("name") + " in a running encounter"));
						before.put("status", p.str("status"));
						after.put("status", "REMOVED");
						tx.update("encounter_participant", p.id(), Map.of("status", "REMOVED"));
						var cols = new LinkedHashMap<String, Object>();
						cols.put("encounter_id", null);
						tx.update("character", c.id(), cols);
						Row enc = tx.get("encounter", p.lng("encounter_id"));
						if (enc.lng("turn_participant_id") != null && enc.lng("turn_participant_id") == p.id()) {
							var ecols = new LinkedHashMap<String, Object>();
							ecols.put("turn_participant_id", null);
							tx.update("encounter", enc.id(), ecols);
						}
						label = c.str("name") + " removed from the encounter";
					}
					default -> {
						Row a = se.hirt.mcp.rpg.world.WorldService.location(tx, campaignId, targetRef);
						Row b = se.hirt.mcp.rpg.world.WorldService.location(tx, campaignId,
								String.valueOf(e.get("to")));
						String state = String.valueOf(e.get("state")).toUpperCase();
						if (!se.hirt.mcp.rpg.world.WorldService.CONNECTION_STATES.contains(state)) {
							throw RpgException.invalidArgument(
									"effect.state must be one of " + se.hirt.mcp.rpg.world.WorldService.CONNECTION_STATES + ".");
						}
						Row conn = tx.queryOne(
								"SELECT * FROM location_connection WHERE campaign_id = ? AND location_a_id = ? AND location_b_id = ?",
								campaignId, Math.min(a.id(), b.id()), Math.max(a.id(), b.id())).orElseThrow(
								() -> RpgException.notFound(
										"A connection between " + targetRef + " and " + e.get("to")));
						Map<String, Object> st = conn.map("state_json");
						before.put("state", st.get("state"));
						st.put("state", state);
						after.put("state", state);
						tx.update("location_connection", conn.id(), Map.of("state_json", Json.write(st)));
						label = "connection " + a.str("name") + " – " + b.str("name") + " set to " + state;
					}
					}
					var audit = new LinkedHashMap<String, Object>();
					audit.put("campaign_id", campaignId);
					audit.put("kind", "GM_OVERRIDE");
					audit.put("actor", "gm");
					audit.put("provenance", "ADMINISTRATIVE_OVERRIDE");
					audit.put("reason", k + " " + targetRef + ": " + reason);
					audit.put("before_json", Json.write(before));
					audit.put("after_json", Json.write(after));
					audit.put("recorded_at", Instant.now().toString());
					tx.rawInsert("audit_record", audit);
					LedgerService.append(tx, campaignId,
							new LedgerService.EventSpec("GM_OVERRIDE", "GM override — " + label + " (" + reason + ")",
									targetRef != null && targetRef.startsWith(Ref.CHARACTER + ":") ? List.of(
											Ref.id(targetRef, Ref.CHARACTER)) : List.of(), "NOTABLE", "GM_ONLY",
									"ADMINISTRATIVE_OVERRIDE", null, campaign.lng("current_location_id"), null,
									Map.of("kind", k, "before", before, "after", after)));
					var result = new LinkedHashMap<String, Object>();
					result.put("override", true);
					result.put("kind", k);
					result.put("target", targetRef);
					result.put("before", before);
					result.put("after", after);
					result.put("reason", reason);
					result.put("audited", true);
					result.put("label", "OVERRIDE: " + label);
					result.put("meta", Harness.meta(tx.get("campaign", campaignId),
							List.of("This result is an override, not a rules-derived or random outcome.")));
					return result;
				});
	}
}
