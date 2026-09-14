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
package se.hirt.mcp.rpg.progression;

import se.hirt.mcp.rpg.character.CharacterService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.harness.HarnessState;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.*;
import se.hirt.mcp.rpg.rules.Ability;
import se.hirt.mcp.rpg.rules.Rules;

import java.util.*;

/**
 * The level-up transaction (DESIGN.md §17.1, MCP_PROTOCOL.md §16, DOMAIN_MODEL.md I-55..I-57): a
 * pending transaction owns the choices; nothing touches the live character until commit, which is
 * atomic.
 * <p>
 * Rules encoded (SRD 5.2.1 "Level Advancement" — [verify]): one level at a time; HP gain = hit die
 * roll or the fixed value (half the die + 1) plus the Constitution modifier, minimum 1, plus
 * species bonuses such as Dwarven Toughness; at the class's ASI levels either an Ability Score
 * Improvement (+2 to one score or +1 to two, never above 20) or a seeded feat whose prerequisites
 * are met. Species lineage spells (Elven Lineage, Fiendish Legacy) are granted when their level
 * threshold is reached. Subclass features are not seeded yet and are reported as unavailable.
 */
public final class LevelUpService {

	/**
	 * The hit points a change of Constitution modifier adds (or, for a cut, removes) at the given
	 * character level: one per level attained (SRD 5.2.1 "Constitution").
	 */
	public static int constitutionHp(int scoreBefore, int scoreAfter, int level) {
		return (Rules.modifier(scoreAfter) - Rules.modifier(scoreBefore)) * Math.max(0, level);
	}

	private static int constitutionHp(Row c, Map<String, Object> cols, int level) {
		int before = c.intOr("con_score", 10);
		int after = cols.get("con_score") instanceof Number n ? n.intValue() : before;
		return constitutionHp(before, after, level);
	}

	/**
	 * Moves the working max_hp / current_hp columns by a delta the way SET_MAX_HP does: a raise
	 * carries current hit points up with it, a cut clamps them to the new maximum (never below 1).
	 */
	public static void shiftMaxHp(Map<String, Object> cols, int delta) {
		if (delta == 0) {
			return;
		}
		int max = ((Number) cols.get("max_hp")).intValue();
		int current = ((Number) cols.get("current_hp")).intValue();
		int target = Math.max(1, max + delta);
		cols.put("max_hp", target);
		cols.put("current_hp", delta > 0 ? current + delta : Math.max(1, Math.min(current, target)));
	}

	/** Origin answers a promotion may carry, applied at commit in this order. */
	private static final List<String> ORIGIN_CHOICES = List.of("species", "species_skill", "species_choice",
			"origin_feat", "background", "background_tool", "feat_choices");

	public static final List<Integer> DEFAULT_ASI_LEVELS = List.of(4, 8, 12, 16, 19);
	private static final List<String> LEGAL_OPS = List.of("get_level_up_choices", "update_level_up",
			"validate_level_up", "commit_level_up", "abandon_transaction");

	private final Database db;
	private final RulesData rules;
	private final RollService roller;
	private final CharacterService characters;

	public LevelUpService(Database db, RulesData rules, RollService roller, CharacterService characters) {
		this.db = db;
		this.rules = rules;
		this.roller = roller;
		this.characters = characters;
	}

	// ── begin_level_up ─────────────────────────────────────────────────

	public Map<String, Object> begin(String operationId, String campaignRef, String characterRef) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("character", characterRef);
		return db.mutate(Database.Mutation.of("begin_level_up", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "begin_level_up");
			Row c = CharacterService.character(tx, campaignId, characterRef);
			if (!"ACTIVE".equals(c.str("lifecycle")) || "DEAD".equals(c.str("life_state"))) {
				throw RpgException.notAllowed(characterRef + " is not a living, active character.");
			}
			if (tx.count("SELECT COUNT(*) FROM pending_transaction WHERE campaign_id = ? AND status = 'OPEN'",
					campaignId) > 0) {
				throw new RpgException(ErrorCode.TRANSACTION_REQUIRED,
						"Another transaction is open; resolve or abandon it first (I-55).");
			}
			// A companion recruited as a stat block has no class. Their first level-up is the class choice itself
			// (RULES_ENGINE.md §6): the engine never picks a class, but everything after that is ordinary.
			Optional<Row> existing = tx
					.queryOne("SELECT * FROM character_class WHERE character_id = ? ORDER BY id LIMIT 1", c.id());
			int level = existing.map(r -> r.intOr("level", 1)).orElse(0);
			int next = level + 1;
			if (next > 20) {
				throw RpgException.notAllowed(c.str("name") + " is already level 20.");
			}
			long needed = rules.xpThreshold(next);
			if (c.lng("xp") < needed) {
				throw RpgException.validation(List.of(new Violation("xp", "THRESHOLD",
						c.str("name") + " has " + c.lng("xp") + " XP; level " + next + " needs " + needed + ".")));
			}
			RulesData.Definition def = existing.map(r -> rules.require(r.str("class_ref"), "CLASS")).orElse(null);
			var payload = new LinkedHashMap<String, Object>();
			payload.put("character", Ref.of(Ref.CHARACTER, c.id()));
			payload.put("class_ref", def == null ? null : def.id());
			payload.put("class_name", def == null ? null : def.name());
			payload.put("from_level", level);
			payload.put("to_level", next);
			payload.put("hit_die", def == null ? null : ((Number) def.payload().get("hit_die")).intValue());
			payload.put("class_required", def == null);
			payload.put("asi_required", def != null && asiLevels(def).contains(next));
			if (def == null) {
				// Hit points cannot be fixed before the class is known; commit computes them from the hit die.
				payload.put("choices", new LinkedHashMap<String, Object>());
				return openTransaction(tx, campaign, campaignId, c, payload);
			}
			int hitDie = ((Number) def.payload().get("hit_die")).intValue();
			// Hit points are governed by campaign rules.hp_progression (RULES_ENGINE.md §6): FIRST_3_MAX
			// (full die through level 3, rolled after), AVERAGE, or ROLL — plus CON and species bonuses.
			Map<String, Object> prefs = campaign.map("preferences_json");
			String policy = prefs.get("rules") instanceof Map<?, ?> rc && rc.get("hp_progression") != null
					? String.valueOf(rc.get("hp_progression")) : "FIRST_3_MAX";
			int conMod = Rules.modifier(c.intOr("con_score", 10));
			int speciesHp = se.hirt.mcp.rpg.character.Origins.hpPerLevel(rules, c);
			var chosen = new LinkedHashMap<String, Object>();
			if ("FIRST_3_MAX".equals(policy) && next <= 3) {
				chosen.put("hp_method", "MAX");
				chosen.put("hp_gain", Math.max(1, hitDie + conMod) + speciesHp);
			} else if ("AVERAGE".equals(policy)) {
				chosen.put("hp_method", "AVERAGE");
				chosen.put("hp_gain", Math.max(1, hitDie / 2 + 1 + conMod) + speciesHp);
			} else {
				Roll roll = roller.roll("1d" + hitDie);
				CharacterService.recordRoll(tx, campaignId,
						"hit points level " + next + " " + Ref.of(Ref.CHARACTER, c.id()), roll);
				chosen.put("hp_method", "ROLL");
				chosen.put("hp_roll", roll.toMap());
				chosen.put("hp_gain", Math.max(1, roll.total() + conMod) + speciesHp);
			}
			payload.put("hp_progression", policy);
			payload.put("choices", chosen);
			return openTransaction(tx, campaign, campaignId, c, payload);
		});
	}

	/** Opens the LEVEL_UP transaction and returns its choices. */
	private Map<String, Object> openTransaction(
		Tx tx, Row campaign, long campaignId, Row c, Map<String, Object> payload) {
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("kind", "LEVEL_UP");
		cols.put("status", "OPEN");
		cols.put("controlled_refs_json", Json.write(List.of(Ref.of(Ref.CHARACTER, c.id()))));
		cols.put("payload_json", Json.write(payload));
		cols.put("revision", 0);
		long id = tx.insert("pending_transaction", cols);
		tx.update("campaign", campaignId,
				Map.of("harness_state", HarnessState.LEVEL_UP.name(), "revision", campaign.lng("revision") + 1));
		tx.touched(Ref.of(Ref.TRANSACTION, id), 0);
		Map<String, Object> result = choicesOf(tx, tx.get("pending_transaction", id), c);
		result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
		return result;
	}

	/**
	 * The Metamagic options a sorcerer owes at the new level: the class feature's count for that
	 * level minus the options already known (SRD 5.2.1 "Sorcerer": two at level 2, one more at 10
	 * and 17). Empty when nothing is owed.
	 */
	private Optional<Map<String, Object>> metamagicChoice(Tx tx, Map<String, Object> payload, Row c) {
		if (!(payload.get("class_ref") instanceof String classRef)) {
			return Optional.empty();
		}
		Optional<RulesData.Definition> cls = rules.find(classRef);
		if (cls.isEmpty()) {
			return Optional.empty();
		}
		int toLevel = ((Number) payload.get("to_level")).intValue();
		Optional<Map<String, Object>> spec = se.hirt.mcp.rpg.magic.Metamagic.featureSpec(cls.get(), toLevel);
		if (spec.isEmpty()) {
			return Optional.empty();
		}
		List<se.hirt.mcp.rpg.magic.Metamagic.Option> options = se.hirt.mcp.rpg.magic.Metamagic.options(spec.get());
		List<String> known = se.hirt.mcp.rpg.magic.Metamagic.known(tx, c.id());
		int allowed = se.hirt.mcp.rpg.magic.Metamagic.allowed(spec.get(), toLevel);
		int owed = allowed - known.size();
		if (owed <= 0) {
			return Optional.empty();
		}
		var m = new LinkedHashMap<String, Object>();
		m.put("choice", "metamagic");
		m.put("choose", owed);
		m.put("known", known);
		m.put("allowed_at_level", allowed);
		m.put("options", options.stream().filter(o -> !known.contains(o.id()))
				.map(se.hirt.mcp.rpg.magic.Metamagic.Option::toMap).toList());
		m.put("rule",
				"Required: pass choices.metamagic = [\"Empowered Spell\", \"Quickened Spell\"] (" + owed + " option"
						+ (owed == 1 ? "" : "s") + "). One option shapes a spell; Empowered and Seeking may join it.");
		return Optional.of(m);
	}

	@SuppressWarnings("unchecked")
	private static List<Integer> asiLevels(RulesData.Definition cls) {
		Object levels = cls.payload().get("asi_levels");
		if (levels instanceof List<?> l) {
			return l.stream().map(o -> ((Number) o).intValue()).toList();
		}
		return DEFAULT_ASI_LEVELS;
	}

	// ── get_level_up_choices ───────────────────────────────────────────

	public Map<String, Object> choices(String campaignRef, String transactionRef) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Row t = transaction(tx, campaign.id(), transactionRef);
			Map<String, Object> payload = t.map("payload_json");
			Row c = tx.get("character", Ref.id((String) payload.get("character"), Ref.CHARACTER));
			Map<String, Object> result = choicesOf(tx, t, c);
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	private Map<String, Object> choicesOf(Tx tx, Row t, Row c) {
		Map<String, Object> payload = t.map("payload_json");
		var result = new LinkedHashMap<String, Object>();
		result.put("transaction", pending(t));
		result.put("character", payload.get("character"));
		result.put("name", c.str("name"));
		result.put("class", payload.get("class_name"));
		result.put("from_level", payload.get("from_level"));
		result.put("to_level", payload.get("to_level"));
		@SuppressWarnings("unchecked")
		Map<String, Object> chosenSoFar = payload.get("choices") instanceof Map<?, ?> ch ? (Map<String, Object>) ch
				: Map.of();
		var hp = new LinkedHashMap<String, Object>();
		hp.put("policy", payload.getOrDefault("hp_progression", "FIRST_3_MAX"));
		hp.put("method", chosenSoFar.get("hp_method"));
		hp.put("gain", chosenSoFar.get("hp_gain"));
		if (chosenSoFar.get("hp_roll") != null) {
			hp.put("roll", chosenSoFar.get("hp_roll"));
		}
		hp.put("note", Boolean.TRUE.equals(payload.get("class_required"))
				? "Computed at commit from the chosen class's hit die, the CON modifier and species bonuses; it replaces the stat block's hit points."
				: "Fixed by campaign rules.hp_progression; the CON modifier and species bonuses are included.");
		result.put("hit_points", hp);
		if (Boolean.TRUE.equals(payload.get("class_required"))) {
			var pick = new LinkedHashMap<String, Object>();
			pick.put("choice", "class");
			pick.put("rule", c.str("name") + " was recruited as a stat block and has no class yet; choose one and "
					+ "they become an ordinary levelling character. Pass choices.class = \"srd5e:class/rogue\".");
			pick.put("options", rules.ofKind("CLASS").stream().map(d -> {
				var o = new LinkedHashMap<String, Object>();
				o.put("value", d.id());
				o.put("label", d.name());
				o.put("description", d.payload().getOrDefault("summary", ""));
				o.put("hit_die", d.payload().get("hit_die"));
				o.put("primary_abilities", d.payload().get("primary_abilities"));
				o.put("saving_throws", d.payload().get("saving_throws"));
				o.put("skill_choices", d.payload().get("skill_choices"));
				return o;
			}).toList());
			pick.put("note",
					"Stat-block actions and senses are kept, but a classed character rolls checks from its own abilities and proficiencies rather than the stat block's numbers — so the skills below matter.");
			result.put("class_choice", pick);
			rules.find((String) payload.get("class_ref")).ifPresent(cls -> {
				var skills = new LinkedHashMap<String, Object>();
				skills.put("choice", "skills");
				skills.put("choose", skillChoiceCount(cls));
				skills.put("options", skillOptions(rules, cls).stream().map(id -> {
					var o = new LinkedHashMap<String, Object>();
					o.put("value", id);
					o.put("label", rules.find(id).map(RulesData.Definition::name).orElse(id));
					return o;
				}).toList());
				skills.put("rule", "Required with the class: pass choices.skills = [\"Stealth\", ...].");
				result.put("skill_choice", skills);
			});
			var origin = new LinkedHashMap<String, Object>();
			origin.put("choice", "species and background");
			origin.put("rule",
					"Optional, but a companion without them is not a character a player could inherit: "
							+ "pass choices.species and choices.background, plus whatever they ask for in turn "
							+ "(species_skill, species_choice, origin_feat, background_tool, feat_choices). "
							+ "get_character_choices lists the options for each.");
			origin.put("species", c.isNull("species_ref") ? rules.ofKind("SPECIES").stream().map(d -> {
				var o = new LinkedHashMap<String, Object>();
				o.put("value", d.id());
				o.put("label", d.name());
				o.put("description", d.payload().getOrDefault("summary", ""));
				return o;
			}).toList() : "already set: " + c.str("species_ref"));
			origin.put("background",
					c.isNull(
							"background_ref")
									? java.util.stream.Stream
											.concat(rules.ofKind("BACKGROUND").stream(),
													se.hirt.mcp.rpg.character.Origins
															.customBackgrounds(tx, c.lng("campaign_id")).stream())
											.map(d -> {
												var o = new LinkedHashMap<String, Object>();
												o.put("value", d.id());
												o.put("label", d.name());
												o.put("feat", d.payload().get("feat"));
												o.put("skills", d.payload().get("skills"));
												return o;
											}).toList()
									: "already set: " + c.str("background_ref"));
			origin.put("ability_scores",
					"NOT applied. A background's ability-score increase belongs to character creation; a companion "
							+ "being promoted already has the scores they have been played with. Use apply_gm_override "
							+ "SET_ABILITY_SCORE deliberately if they should change.");
			result.put("origin_choice", origin);
		}
		if (Boolean.TRUE.equals(payload.get("asi_required"))) {
			var asi = new LinkedHashMap<String, Object>();
			asi.put("choice", "ability_score_improvement");
			asi.put("rule", "+2 to one ability or +1 to two different abilities; no score above 20.");
			rules.find((String) payload.get("class_ref")).ifPresent(d -> asi.put("recommended", autoAsi(c, d)));
			var scores = new LinkedHashMap<String, Object>();
			for (Ability a : Ability.values()) {
				scores.put(a.name(), c.integer(a.column()));
			}
			asi.put("current_scores", scores);
			asi.put("or_a_feat", "Instead of the improvement, take a feat whose prerequisites are met "
					+ "(get_character_choices scope FEAT lists them): pass choices.feat = {feat, ...its choices}.");
			result.put("ability_score_improvement", asi);
		}
		var pendingFeats = new ArrayList<Map<String, Object>>();
		for (Row f : se.hirt.mcp.rpg.character.Origins.featRows(tx, c)) {
			Map<String, Object> fp = f.map("payload_json");
			if (fp.get("pending") instanceof List<?> pending && !pending.isEmpty()) {
				var pf = new LinkedHashMap<String, Object>();
				pf.put("feat", se.hirt.mcp.rpg.character.Origins.featDefinition(rules, f)
						.map(RulesData.Definition::name).orElse(f.str("content_ref")));
				pf.put("pending", pending);
				pf.put("rule",
						"Complete it with choices.feat_choices = {\"feat\": \"<name>\", ...} — or a list, one object per feat.");
				pendingFeats.add(pf);
			}
		}
		if (!pendingFeats.isEmpty()) {
			result.put("pending_feat_choices", pendingFeats);
		}
		metamagicChoice(tx, payload, c).ifPresent(choice -> result.put("metamagic_choice", choice));
		result.put("automatic", Map.of("proficiency_bonus",
				rules.proficiencyBonus(((Number) payload.get("to_level")).intValue()), "note",
				"Spell slots resize and ENGINE class features (Sneak Attack, Font of Magic, Metamagic) apply at commit; subclass choices and GM-adjudicated features are narrated from the SRD and recorded with record_memory."));
		result.put("chosen", payload.get("choices"));
		result.put("preview", preview(tx, t, c));
		rules.find((String) payload.get("class_ref")).map(d -> d.payload().get("spellcasting"))
				.filter(sc -> sc instanceof Map<?, ?>).ifPresent(sc -> {
					@SuppressWarnings("unchecked")
					Map<String, Object> s = (Map<String, Object>) sc;
					int to = ((Number) payload.get("to_level")).intValue();
					@SuppressWarnings("unchecked")
					List<Object> cantrips = (List<Object>) s.get("cantrips_known");
					@SuppressWarnings("unchecked")
					List<Object> prepared = (List<Object>) s.get("prepared");
					result.put("spellcasting_at_new_level", Map.of("cantrips_known", cantrips.get(to - 1),
							"prepared_spells", prepared.get(to - 1), "note",
							"Spell slots are resized automatically at commit; adjust the prepared list with prepare_spells afterwards."));
				});
		return result;
	}

	private static Map<String, Object> pending(Row t) {
		var m = new LinkedHashMap<String, Object>();
		m.put("ref", Ref.of(Ref.TRANSACTION, t.id()));
		m.put("kind", t.str("kind"));
		m.put("revision", t.lng("revision"));
		m.put("status", t.str("status"));
		m.put("legal_operations", LEGAL_OPS);
		return m;
	}

	/**
	 * Feat prerequisites: category availability, level, ability minimums, repeatability (SRD 5.2.1
	 * "Parts of a Feat").
	 */
	@SuppressWarnings("unchecked")
	private void validateFeatPrerequisites(Tx tx, Row c, RulesData.Definition feat, int toLevel) {
		String category = String.valueOf(feat.payload().get("category"));
		if ("FIGHTING_STYLE".equals(category)) {
			throw RpgException.validation(List.of(new Violation("feat", "PREREQUISITE", feat.name()
					+ " requires the Fighting Style class feature, which is not yet data-driven; choose another feat.")));
		}
		if (feat.payload().get("prerequisite_level") instanceof Number lvl && toLevel < lvl.intValue()) {
			throw RpgException.validation(List.of(new Violation("feat", "PREREQUISITE",
					feat.name() + " requires level " + lvl + "+ (" + feat.payload().get("prerequisite") + ").")));
		}
		if (feat.payload().get("prerequisite_abilities") instanceof Map<?, ?> pre
				&& ((Map<String, Object>) pre).get("any_of") instanceof Map<?, ?> anyOf) {
			boolean met = false;
			for (var e : ((Map<String, Object>) anyOf).entrySet()) {
				if (c.intOr(Ability.parse(e.getKey()).column(), 10) >= ((Number) e.getValue()).intValue()) {
					met = true;
				}
			}
			if (!met) {
				throw RpgException.validation(List.of(new Violation("feat", "PREREQUISITE",
						feat.name() + " requires " + anyOf + " (" + feat.payload().get("prerequisite") + ").")));
			}
		}
		boolean taken = tx.count(
				"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'FEAT' AND content_ref = ?",
				c.id(), feat.id()) > 0;
		if (taken && feat.payload().get("repeatable") == null) {
			throw RpgException.validation(List
					.of(new Violation("feat", "ALREADY_TAKEN", c.str("name") + " already has " + feat.name() + ".")));
		}
	}

	private Row transaction(Tx tx, long campaignId, String transactionRef) {
		Row t;
		if (transactionRef == null || transactionRef.isBlank()) {
			t = tx.queryOne(
					"SELECT * FROM pending_transaction WHERE campaign_id = ? AND kind = 'LEVEL_UP' AND status = 'OPEN' ORDER BY id DESC LIMIT 1",
					campaignId)
					.orElseThrow(() -> new RpgException(ErrorCode.TRANSACTION_REQUIRED,
							"No level-up transaction is open; call begin_level_up first."));
		} else {
			t = tx.find("pending_transaction", Ref.id(transactionRef, Ref.TRANSACTION))
					.orElseThrow(() -> RpgException.notFound("Transaction " + transactionRef));
			if (t.lng("campaign_id") != campaignId) {
				throw RpgException.invalidArgument(transactionRef + " belongs to another campaign.");
			}
		}
		if (!"LEVEL_UP".equals(t.str("kind"))) {
			throw RpgException
					.invalidArgument(Ref.of(Ref.TRANSACTION, t.id()) + " is a " + t.str("kind") + " transaction.");
		}
		return t;
	}

	private Row openTransaction(Tx tx, long campaignId, String transactionRef) {
		Row t = transaction(tx, campaignId, transactionRef);
		if (!"OPEN".equals(t.str("status"))) {
			throw new RpgException(
					"EXPIRED".equals(t.str("status")) ? ErrorCode.TRANSACTION_EXPIRED : ErrorCode.OPERATION_NOT_ALLOWED,
					Ref.of(Ref.TRANSACTION, t.id()) + " is " + t.str("status") + ".");
		}
		return t;
	}

	// ── update_level_up ────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	public Map<String, Object> update(
		String operationId, String campaignRef, String transactionRef, Long expectedRevision,
		Map<String, Object> choices) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("transaction", transactionRef);
		args.put("choices", choices);
		return db.mutate(Database.Mutation.of("update_level_up", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "update_level_up");
			Row t = openTransaction(tx, campaignId, transactionRef);
			Harness.requireRevision(t, "Transaction", expectedRevision);
			if (choices == null || choices.isEmpty()) {
				throw RpgException.invalidArgument("choices must contain hp_method and/or ability_score_improvement.");
			}
			Map<String, Object> payload = t.map("payload_json");
			Map<String, Object> chosen = (Map<String, Object>) payload.get("choices");
			Row c = tx.get("character", Ref.id((String) payload.get("character"), Ref.CHARACTER));
			// The class is settled first: the skill list and everything else depend on knowing it.
			var ordered = new LinkedHashMap<String, Object>();
			if (choices.containsKey("class")) {
				ordered.put("class", choices.get("class"));
			}
			choices.forEach((k2, v2) -> ordered.putIfAbsent(k2, v2));
			for (var e : ordered.entrySet()) {
				switch (e.getKey()) {
				case "hp_method" ->
					throw RpgException.invalidArgument("Hit points are governed by campaign rules.hp_progression ("
							+ payload.getOrDefault("hp_progression", "FIRST_3_MAX")
							+ ") and were fixed when the level-up began; there is nothing to choose.");
				case "ability_score_improvement" -> {
					if (!Boolean.TRUE.equals(payload.get("asi_required"))) {
						throw RpgException
								.validation(List.of(new Violation("ability_score_improvement", "NOT_AVAILABLE",
										"Level " + payload.get("to_level") + " grants no Ability Score Improvement for "
												+ payload.get("class_name") + ".")));
					}
					if (!(e.getValue() instanceof Map<?, ?> m)) {
						throw RpgException.invalidArgument(
								"ability_score_improvement must be like {\"CHA\": 2} or {\"DEX\": 1, \"CON\": 1}.");
					}
					var asi = new LinkedHashMap<String, Object>();
					int total = 0;
					for (var s : m.entrySet()) {
						Ability a = Ability.parse(String.valueOf(s.getKey()));
						if (!(s.getValue() instanceof Number n) || n.intValue() < 1 || n.intValue() > 2) {
							throw RpgException.validation(List.of(new Violation("ability_score_improvement." + a.name(),
									"RANGE", "Increases are +1 or +2.")));
						}
						if (c.intOr(a.column(), 10) + n.intValue() > Rules.MAX_SCORE) {
							throw RpgException.validation(List.of(new Violation("ability_score_improvement." + a.name(),
									"CAP", a.fullName() + " cannot exceed 20.")));
						}
						asi.put(a.name(), n.intValue());
						total += n.intValue();
					}
					if (total != 2
							|| (asi.size() == 2 && asi.values().stream().anyMatch(v -> ((Number) v).intValue() != 1))) {
						throw RpgException.validation(List.of(new Violation("ability_score_improvement", "TOTAL",
								"Choose +2 to one ability or +1 to two.")));
					}
					chosen.put("ability_score_improvement", asi);
					chosen.remove("feat");
				}
				case "feat" -> {
					// A feat may be taken instead of the Ability Score Improvement (SRD 5.2.1 "Feats").
					if (!Boolean.TRUE.equals(payload.get("asi_required"))) {
						throw RpgException.validation(
								List.of(new Violation("feat", "NOT_AVAILABLE", "Level " + payload.get("to_level")
										+ " grants no feat choice for " + payload.get("class_name") + ".")));
					}
					Object featValue = e.getValue() instanceof Map<?, ?> fm ? fm.get("feat") : e.getValue();
					RulesData.Definition feat = rules.resolve("FEAT", String.valueOf(featValue))
							.orElseThrow(() -> RpgException.invalidArgument(
									"Unknown feat '" + featValue + "'; see get_character_choices FEAT."));
					int toLevel = ((Number) payload.get("to_level")).intValue();
					validateFeatPrerequisites(tx, c, feat, toLevel);
					var featChoices = new LinkedHashMap<String, Object>();
					if (e.getValue() instanceof Map<?, ?> fm) {
						fm.forEach((k, val) -> {
							if (!"feat".equals(k)) {
								featChoices.put(String.valueOf(k), val);
							}
						});
					}
					// Every choice the feat requires must be settled inside the transaction.
					if (feat.payload().get("choices") instanceof Map<?, ?> spec) {
						var missing = new ArrayList<String>();
						for (String kk : se.hirt.mcp.rpg.character.Origins
								.pendingOrder(((Map<String, Object>) spec).keySet())) {
							boolean provided = featChoices.containsKey(kk)
									|| ("spells".equals(kk) && featChoices.containsKey("spell"));
							if (!provided) {
								missing.add(kk);
							}
						}
						if (!missing.isEmpty()) {
							throw RpgException.validation(List.of(new Violation("feat", "CHOICES_REQUIRED",
									feat.name() + " needs choices in the same call: " + missing + ".")));
						}
					}
					if (feat.payload().get("asi") instanceof Map<?, ?> asiSpec) {
						Object inc = featChoices.get("ability_increase");
						List<String> allowed = ((List<Object>) ((Map<String, Object>) asiSpec).get("choose_one"))
								.stream().map(Object::toString).toList();
						if (inc == null || !allowed.contains(String.valueOf(inc).toUpperCase())) {
							throw RpgException.validation(List.of(new Violation("feat.ability_increase", "REQUIRED",
									feat.name() + " increases one of " + allowed + " by 1; pass ability_increase.")));
						}
						featChoices.put("ability_increase", String.valueOf(inc).toUpperCase());
					}
					var record = new LinkedHashMap<String, Object>();
					record.put("feat", feat.id());
					record.put("name", feat.name());
					record.put("choices", featChoices);
					chosen.put("feat", record);
					chosen.remove("ability_score_improvement");
				}
				case "metamagic" -> {
					Map<String, Object> choice = metamagicChoice(tx, payload, c).orElseThrow(
							() -> RpgException.validation(List.of(new Violation("metamagic", "NOT_AVAILABLE",
									"Level " + payload.get("to_level") + " grants no new Metamagic option for "
											+ payload.get("class_name") + "."))));
					RulesData.Definition cls = rules.require((String) payload.get("class_ref"), "CLASS");
					int toLevel = ((Number) payload.get("to_level")).intValue();
					Map<String, Object> spec = se.hirt.mcp.rpg.magic.Metamagic.featureSpec(cls, toLevel).orElseThrow();
					List<String> ids = se.hirt.mcp.rpg.magic.Metamagic.validateChoice(
							se.hirt.mcp.rpg.magic.Metamagic.options(spec),
							((Number) choice.get("allowed_at_level")).intValue(),
							se.hirt.mcp.rpg.magic.Metamagic.known(tx, c.id()), e.getValue(), true);
					chosen.put("metamagic", ids);
				}
				case "class" -> {
					if (!Boolean.TRUE.equals(payload.get("class_required"))) {
						throw RpgException.validation(List.of(new Violation("class", "NOT_AVAILABLE",
								c.str("name") + " already has a class; multiclassing is not implemented.")));
					}
					RulesData.Definition picked = rules.resolve("CLASS", String.valueOf(e.getValue()))
							.orElseThrow(() -> RpgException.invalidArgument(
									"Unknown class '" + e.getValue() + "'; see the class_choice options."));
					payload.put("class_ref", picked.id());
					payload.put("class_name", picked.name());
					payload.put("hit_die", ((Number) picked.payload().get("hit_die")).intValue());
					chosen.put("class", picked.id());
				}
				case "species", "species_skill", "species_choice", "origin_feat", "background", "background_tool",
						"feat_choices" -> {
					// Recorded now, applied at commit with everything else: nothing touches the live character
					// until the transaction is committed (MCP_PROTOCOL.md §21).
					if (!Boolean.TRUE.equals(payload.get("class_required"))) {
						// A feat that still owes choices (Magic Initiate's spells) may be completed at any level.
						boolean pendingFeat = e.getKey().equals("feat_choices")
								&& se.hirt.mcp.rpg.character.Origins.featRows(tx, c).stream().anyMatch(
										f -> f.map("payload_json").get("pending") instanceof List<?> p && !p.isEmpty());
						if (!pendingFeat) {
							throw RpgException.validation(List.of(new Violation(e.getKey(), "NOT_AVAILABLE",
									"Species and background are settled at character creation, or with a companion's first class level"
											+ (e.getKey().equals("feat_choices")
													? "; feat_choices later on only completes a feat with pending choices, and none is pending."
													: "."))));
						}
					}
					if (e.getKey().equals("species")) {
						chosen.put("species", rules.resolve("SPECIES", String.valueOf(e.getValue()))
								.orElseThrow(() -> RpgException.invalidArgument(
										"Unknown species '" + e.getValue() + "'; see the origin_choice options."))
								.id());
					} else if (e.getKey().equals("background")) {
						chosen.put("background", se.hirt.mcp.rpg.character.Origins
								.resolveBackground(tx, rules, c.lng("campaign_id"), String.valueOf(e.getValue()))
								.orElseThrow(() -> RpgException.invalidArgument(
										"Unknown background '" + e.getValue() + "'; see the origin_choice options."))
								.id());
					} else {
						chosen.put(e.getKey(), e.getValue());
					}
				}
				case "skills" -> {
					if (!Boolean.TRUE.equals(payload.get("class_required"))) {
						throw RpgException.validation(List.of(new Violation("skills", "NOT_AVAILABLE",
								"Class skill proficiencies are chosen with the first class level only.")));
					}
					if (payload.get("class_ref") == null) {
						throw RpgException.validation(List.of(new Violation("skills", "CLASS_REQUIRED",
								"Choose the class in the same call, before its skills.")));
					}
					RulesData.Definition cls = rules.require((String) payload.get("class_ref"), "CLASS");
					List<String> options = skillOptions(rules, cls);
					int count = skillChoiceCount(cls);
					List<?> list = e.getValue() instanceof List<?> l ? l : List.of(e.getValue());
					var held = se.hirt.mcp.rpg.character.Origins.heldSkills(tx, c.id());
					var picked = new ArrayList<String>();
					for (Object o : list) {
						RulesData.Definition skill = rules.resolve("SKILL", String.valueOf(o))
								.orElseThrow(() -> RpgException.invalidArgument("Unknown skill '" + o + "'."));
						if (!options.contains(skill.id())) {
							throw RpgException.validation(List.of(new Violation("skills", "CLASS_SKILL_LIST",
									skill.name() + " is not on the " + cls.name() + " skill list: " + options)));
						}
						if (held.contains(skill.id())) {
							throw RpgException.validation(List.of(new Violation("skills", "DUPLICATE_PROFICIENCY",
									c.str("name") + " is already proficient in " + skill.name() + ".")));
						}
						if (!picked.contains(skill.id())) {
							picked.add(skill.id());
						}
					}
					if (picked.size() != count) {
						throw RpgException.validation(List.of(new Violation("skills", "SKILL_COUNT",
								cls.name() + " chooses exactly " + count + " skills; got " + picked.size() + ".")));
					}
					chosen.put("skills", picked);
				}
				default -> throw RpgException.invalidArgument("Unknown level-up choice '" + e.getKey() + "'.");
				}
			}
			payload.put("choices", chosen);
			tx.update("pending_transaction", t.id(),
					Map.of("payload_json", Json.write(payload), "revision", t.lng("revision") + 1));
			tx.touched(Ref.of(Ref.TRANSACTION, t.id()), t.lng("revision") + 1);
			Map<String, Object> result = choicesOf(tx, tx.get("pending_transaction", t.id()), c);
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── validate_level_up ──────────────────────────────────────────────

	public Map<String, Object> validate(String campaignRef, String transactionRef) {
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Row t = transaction(tx, campaign.id(), transactionRef);
			Map<String, Object> payload = t.map("payload_json");
			Row c = tx.get("character", Ref.id((String) payload.get("character"), Ref.CHARACTER));
			List<Violation> violations = violations(tx, t, c);
			var result = new LinkedHashMap<String, Object>();
			result.put("transaction", pending(t));
			result.put("valid", violations.isEmpty());
			result.put("violations", violations.stream().map(Violation::toMap).toList());
			result.put("preview", preview(tx, t, c));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	@SuppressWarnings("unchecked")
	private List<Violation> violations(Tx tx, Row t, Row c) {
		Map<String, Object> payload = t.map("payload_json");
		Map<String, Object> chosen = (Map<String, Object>) payload.get("choices");
		var v = new ArrayList<Violation>();
		if (chosen.get("metamagic") == null) {
			metamagicChoice(tx, payload, c).ifPresent(choice -> v.add(new Violation("metamagic", "REQUIRED",
					"Level " + payload.get("to_level") + " grants " + choice.get("choose") + " Metamagic option"
							+ (((Number) choice.get("choose")).intValue() == 1 ? "" : "s")
							+ "; pass choices.metamagic (get_level_up_choices lists them).")));
		}
		if (Boolean.TRUE.equals(payload.get("class_required"))) {
			if (chosen.get("class") == null) {
				v.add(new Violation("class", "REQUIRED",
						c.str("name") + " has no class yet; pass choices.class before committing."));
			} else if (chosen.get("skills") == null) {
				v.add(new Violation("skills", "REQUIRED",
						"A first class level grants its skill proficiencies; pass choices.skills."));
			}
		} else if (chosen.get("hp_gain") == null) {
			v.add(new Violation("hp_gain", "REQUIRED",
					"The hit-point gain was not computed; abandon this transaction and begin the level-up again."));
		}
		if (Boolean.TRUE.equals(payload.get("asi_required")) && chosen.get("ability_score_improvement") == null
				&& chosen.get("feat") == null) {
			v.add(new Violation("ability_score_improvement", "REQUIRED", "Level " + payload.get("to_level")
					+ " requires an Ability Score Improvement — or a feat (choices.feat)."));
		}
		if (c.lng("xp") < rules.xpThreshold(((Number) payload.get("to_level")).intValue())) {
			v.add(new Violation("xp", "THRESHOLD",
					"Not enough XP any more (state changed since the transaction began)."));
		}
		return v;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> preview(Tx tx, Row t, Row c) {
		Map<String, Object> payload = t.map("payload_json");
		Map<String, Object> chosen = (Map<String, Object>) payload.get("choices");
		int toLevel = ((Number) payload.get("to_level")).intValue();
		var before = new LinkedHashMap<String, Object>();
		var after = new LinkedHashMap<String, Object>();
		before.put("level", payload.get("from_level"));
		after.put("level", toLevel);
		before.put("max_hp", c.integer("max_hp"));
		Map<String, Object> asi = chosen.get("ability_score_improvement") instanceof Map<?, ?> m
				? (Map<String, Object>) m : Map.of();
		Object gain = chosen.get("hp_gain");
		int conBefore = c.intOr("con_score", 10);
		int conAfter = conBefore + (asi.get("CON") instanceof Number n ? n.intValue() : 0)
				+ (chosen.get("feat") instanceof Map<?, ?> feat && feat.get("choices") instanceof Map<?, ?> fc
						&& "CON".equalsIgnoreCase(String.valueOf(fc.get("ability_increase"))) ? 1 : 0);
		int constitutionHp = constitutionHp(conBefore, Math.min(Rules.MAX_SCORE, conAfter), toLevel);
		after.put("max_hp", gain == null ? null : c.intOr("max_hp", 0) + ((Number) gain).intValue() + constitutionHp);
		if (constitutionHp != 0) {
			after.put("constitution_hp", constitutionHp);
		}
		before.put("proficiency_bonus", rules.proficiencyBonus(((Number) payload.get("from_level")).intValue()));
		after.put("proficiency_bonus", rules.proficiencyBonus(toLevel));
		var scoresBefore = new LinkedHashMap<String, Object>();
		var scoresAfter = new LinkedHashMap<String, Object>();
		for (Ability a : Ability.values()) {
			int score = c.intOr(a.column(), 10);
			scoresBefore.put(a.name(), score);
			scoresAfter.put(a.name(), score + (asi.get(a.name()) instanceof Number n ? n.intValue() : 0));
		}
		before.put("abilities", scoresBefore);
		after.put("abilities", scoresAfter);
		var m = new LinkedHashMap<String, Object>();
		m.put("before", before);
		m.put("after", after);
		return m;
	}

	// ── commit_level_up ────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	public Map<String, Object> commit(
		String operationId, String campaignRef, String transactionRef, Long expectedRevision) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("transaction", transactionRef);
		return db.mutate(Database.Mutation.of("commit_level_up", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "commit_level_up");
			Row t = openTransaction(tx, campaignId, transactionRef);
			Harness.requireRevision(t, "Transaction", expectedRevision);
			Map<String, Object> payload = t.map("payload_json");
			Row c = tx.get("character", Ref.id((String) payload.get("character"), Ref.CHARACTER));
			List<Violation> violations = violations(tx, t, c);
			if (!violations.isEmpty()) {
				throw RpgException.validation(violations);
			}
			Map<String, Object> chosen = (Map<String, Object>) payload.get("choices");
			int toLevel = ((Number) payload.get("to_level")).intValue();
			RulesData.Definition classDef = rules.require((String) payload.get("class_ref"), "CLASS");
			Row live = c;
			boolean firstClass = Boolean.TRUE.equals(payload.get("class_required"));
			var cols = new LinkedHashMap<String, Object>();
			int gain;
			if (firstClass) {
				// Species and background first: hit points depend on them (Dwarven Toughness), and a background
				// grants an origin feat that may itself grant proficiencies (RULES_ENGINE.md §6).
				applyOrigin(tx, rules, c, chosen);
				Row withOrigin = tx.get("character", c.id());
				// Taking a first class replaces the stat block's hit points with the class's own (RULES_ENGINE.md §6).
				gain = firstLevelHp(rules, withOrigin, classDef);
				tx.insert("character_class", Map.of("character_id", c.id(), "class_ref", classDef.id(), "level", 1));
				grantClassSaves(tx, withOrigin, classDef);
				for (Object skill : (List<Object>) chosen.getOrDefault("skills", List.of())) {
					if (tx.count(
							"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SKILL' AND content_ref = ?",
							c.id(), String.valueOf(skill)) == 0) {
						se.hirt.mcp.rpg.character.Origins.insertTrait(tx, c.id(), "SKILL", String.valueOf(skill),
								Map.of("source", se.hirt.mcp.rpg.character.Origins.SOURCE_CLASS));
					}
				}
				c = withOrigin;
				cols.put("max_hp", gain);
				cols.put("current_hp", Math.min(gain, Math.max(1, c.intOr("current_hp", gain))));
			} else {
				gain = ((Number) chosen.get("hp_gain")).intValue();
				Row cls = tx.queryOne("SELECT * FROM character_class WHERE character_id = ? AND class_ref = ?", c.id(),
						(String) payload.get("class_ref")).orElseThrow();
				tx.update("character_class", cls.id(), Map.of("level", toLevel));
				cols.put("max_hp", c.intOr("max_hp", 0) + gain);
				// Raising the maximum raises current HP by the same amount (SRD 5.2.1 "Hit Points" at level advancement).
				cols.put("current_hp", c.intOr("current_hp", 0) + gain);
				if (chosen.get("feat_choices") != null) {
					// Completing a feat's outstanding choices (Magic Initiate's spells) at a later level.
					se.hirt.mcp.rpg.character.Origins.applyFeatChoices(tx, rules, c, chosen.get("feat_choices"));
				}
			}
			// Metamagic options chosen at this level become METAMAGIC traits (SRD 5.2.1 "Sorcerer").
			var metamagicNames = new ArrayList<String>();
			if (chosen.get("metamagic") instanceof List<?> ids) {
				long characterId = c.id();
				se.hirt.mcp.rpg.magic.Metamagic.featureSpec(classDef, toLevel).ifPresent(spec -> {
					List<se.hirt.mcp.rpg.magic.Metamagic.Option> options = se.hirt.mcp.rpg.magic.Metamagic
							.options(spec);
					List<String> chosenIds = ids.stream().map(String::valueOf).toList();
					se.hirt.mcp.rpg.magic.Metamagic.addKnown(tx, characterId, chosenIds, options);
					for (String id : chosenIds) {
						options.stream().filter(o -> o.id().equals(id)).findFirst()
								.ifPresent(o -> metamagicNames.add(o.name()));
					}
				});
			}
			Map<String, Object> asi = chosen.get("ability_score_improvement") instanceof Map<?, ?> m
					? (Map<String, Object>) m : Map.of();
			for (var e : asi.entrySet()) {
				Ability a = Ability.parse(e.getKey());
				cols.put(a.column(), c.intOr(a.column(), 10) + ((Number) e.getValue()).intValue());
			}
			// A feat taken instead of the ASI (SRD 5.2.1): granted with its choices; a feat ability increase applies now.
			Map<String, Object> featRecord = chosen.get("feat") instanceof Map<?, ?> fm ? (Map<String, Object>) fm
					: null;
			String featName = null;
			if (featRecord != null) {
				RulesData.Definition feat = rules.require(String.valueOf(featRecord.get("feat")), "FEAT");
				featName = feat.name();
				Map<String, Object> featChoices = featRecord.get("choices") instanceof Map<?, ?> fc
						? new LinkedHashMap<>((Map<String, Object>) fc) : new LinkedHashMap<>();
				Object increase = featChoices.remove("ability_increase");
				if (increase != null) {
					Ability a = Ability.parse(String.valueOf(increase));
					cols.put(a.column(), Math.min(Rules.MAX_SCORE, c.intOr(a.column(), 10) + 1));
				}
				se.hirt.mcp.rpg.character.Origins.grantFeat(tx, rules, c, feat, "level_up", featChoices);
			}
			// A higher Constitution modifier raises the maximum by one for every level attained, this one
			// included (SRD 5.2.1 "Constitution": the increase is retroactive).
			int constitutionHp = constitutionHp(c, cols, toLevel);
			shiftMaxHp(cols, constitutionHp);
			cols.put("revision", c.lng("revision") + 1);
			tx.update("character", c.id(), cols);
			se.hirt.mcp.rpg.magic.SpellService.initializeSlots(tx, rules, tx.get("character", c.id()));
			List<String> lineageSpells = se.hirt.mcp.rpg.character.Origins.grantSpeciesSpells(tx, rules,
					tx.get("character", c.id()));
			se.hirt.mcp.rpg.character.Origins.initializeResources(tx, rules, tx.get("character", c.id()));
			tx.update("pending_transaction", t.id(), Map.of("status", "COMMITTED", "revision", t.lng("revision") + 1));
			LedgerService.append(tx, campaignId, new LedgerService.EventSpec("LEVEL_UP",
					c.str("name") + " reached level " + toLevel + " (" + payload.get("class_name") + "): +" + gain
							+ " HP" + (constitutionHp == 0 ? "" : ", +" + constitutionHp + " HP for Constitution")
							+ (asi.isEmpty() ? "" : ", ability improvement " + asi)
							+ (featName == null ? "" : ", feat " + featName)
							+ (lineageSpells.isEmpty() ? "" : ", species spells " + lineageSpells)
							+ (metamagicNames.isEmpty() ? "" : ", Metamagic " + metamagicNames) + ".",
					List.of(c.id()), "MAJOR", "PARTY_KNOWN", "PLAYER", null, c.lng("location_id"), null,
					Map.of("level", toLevel, "hp_gain", gain, "choices", chosen)));
			tx.update("campaign", campaignId,
					Map.of("harness_state", HarnessState.EXPLORATION.name(), "revision", campaign.lng("revision") + 1));
			Row after = tx.get("character", c.id());
			tx.touched(Ref.of(Ref.CHARACTER, c.id()), after.lng("revision"));
			var result = new LinkedHashMap<String, Object>();
			result.put("transaction", Ref.of(Ref.TRANSACTION, t.id()));
			result.put("character", Ref.of(Ref.CHARACTER, c.id()));
			result.put("level", toLevel);
			result.put("hp_gain", gain);
			if (constitutionHp != 0) {
				result.put("constitution_hp", constitutionHp);
			}
			result.put("ability_score_improvement", asi);
			if (featName != null) {
				result.put("feat", featName);
			}
			if (!lineageSpells.isEmpty()) {
				result.put("species_spells_granted", lineageSpells);
			}
			if (!metamagicNames.isEmpty()) {
				result.put("metamagic", metamagicNames);
			}
			result.put("sheet", characters.sheet(tx, after, "PLAY"));
			result.put("level_up_eligible", rules.levelForXp(after.lng("xp")) > toLevel);
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return result;
		});
	}

	// ── companion advancement ──────────────────────────────────────────

	/**
	 * Advances every companion the campaign's {@code rules.companion_level_up} lets the engine
	 * handle, and reports the rest. Called from every operation that grants experience, so a
	 * companion never quietly sits on enough XP for three levels. A companion with no class is only
	 * ever flagged: the engine does not invent one.
	 */
	public static void companionAdvancement(
		Tx tx, RulesData rules, RollService roller, Row campaign, Map<String, Object> result) {
		long campaignId = campaign.id();
		boolean engine = "ENGINE".equals(PartyXp.companionLevelUp(campaign));
		var levelled = new ArrayList<Map<String, Object>>();
		var pending = new ArrayList<Map<String, Object>>();
		for (Row member : PartyXp.activeParty(tx, campaignId)) {
			if (PartyXp.playerControlled(tx, campaignId, member.id())) {
				continue;
			}
			Row c = tx.get("character", member.id());
			Optional<Row> cls = tx.queryOne("SELECT * FROM character_class WHERE character_id = ? ORDER BY id LIMIT 1",
					c.id());
			if (cls.isEmpty()) {
				var m = new LinkedHashMap<String, Object>();
				m.put("character", Ref.of(Ref.CHARACTER, c.id()));
				m.put("name", c.str("name"));
				m.put("xp", c.lng("xp"));
				m.put("reason", "NEEDS_CLASS");
				m.put("next_step", "begin_level_up for " + Ref.of(Ref.CHARACTER, c.id())
						+ " offers the class choice; the engine never picks a class.");
				pending.add(m);
				continue;
			}
			int level = cls.get().intOr("level", 1);
			if (rules.levelForXp(c.lng("xp")) <= level) {
				continue;
			}
			if (!engine) {
				RulesData.Definition def = rules.require(cls.get().str("class_ref"), "CLASS");
				var m = new LinkedHashMap<String, Object>();
				m.put("character", Ref.of(Ref.CHARACTER, c.id()));
				m.put("name", c.str("name"));
				m.put("class", def.name());
				m.put("level", level);
				m.put("to_level", level + 1);
				m.put("xp", c.lng("xp"));
				m.put("reason", "AWAITING_PLAYER");
				m.put("proposal", proposal(rules, campaign, c, def, level + 1));
				m.put("next_step", "Offer the proposal to the player as it stands; begin_level_up for "
						+ Ref.of(Ref.CHARACTER, c.id())
						+ " then commits it unchanged, or update_level_up records whatever they would rather do.");
				pending.add(m);
				continue;
			}
			while (true) {
				Row live = tx.get("character", c.id());
				Row row = tx
						.queryOne("SELECT * FROM character_class WHERE character_id = ? ORDER BY id LIMIT 1", live.id())
						.orElseThrow();
				int from = row.intOr("level", 1);
				if (from >= 20 || rules.levelForXp(live.lng("xp")) <= from) {
					break;
				}
				levelled.add(levelCompanion(tx, rules, roller, campaign, live, row));
			}
		}
		if (!levelled.isEmpty()) {
			result.put("companion_level_ups", levelled);
		}
		if (!pending.isEmpty()) {
			result.put("companions_awaiting_level_up", pending);
		}
	}

	/**
	 * One engine-made companion level: hit points by campaign policy, any ASI into the class's
	 * primary ability.
	 */
	private static Map<String, Object> levelCompanion(
		Tx tx, RulesData rules, RollService roller, Row campaign, Row c, Row classRow) {
		long campaignId = campaign.id();
		RulesData.Definition def = rules.require(classRow.str("class_ref"), "CLASS");
		int to = classRow.intOr("level", 1) + 1;
		int hitDie = ((Number) def.payload().get("hit_die")).intValue();
		int conMod = Rules.modifier(c.intOr("con_score", 10));
		int speciesHp = se.hirt.mcp.rpg.character.Origins.hpPerLevel(rules, c);
		String policy = PartyXp.rule(campaign, "hp_progression", "FIRST_3_MAX");
		int gain;
		String method;
		if ("FIRST_3_MAX".equals(policy) && to <= 3) {
			method = "MAX";
			gain = Math.max(1, hitDie + conMod) + speciesHp;
		} else if ("AVERAGE".equals(policy)) {
			method = "AVERAGE";
			gain = Math.max(1, hitDie / 2 + 1 + conMod) + speciesHp;
		} else {
			Roll roll = roller.roll("1d" + hitDie);
			CharacterService.recordRoll(tx, campaignId, "hit points level " + to + " " + Ref.of(Ref.CHARACTER, c.id()),
					roll);
			method = "ROLL";
			gain = Math.max(1, roll.total() + conMod) + speciesHp;
		}
		var cols = new LinkedHashMap<String, Object>();
		cols.put("max_hp", c.intOr("max_hp", 0) + gain);
		cols.put("current_hp", c.intOr("current_hp", 0) + gain);
		Map<String, Object> asi = asiLevels(def).contains(to) ? autoAsi(c, def) : Map.of();
		for (var e : asi.entrySet()) {
			Ability a = Ability.parse(e.getKey());
			cols.put(a.column(), c.intOr(a.column(), 10) + ((Number) e.getValue()).intValue());
		}
		int constitutionHp = constitutionHp(c, cols, to);
		shiftMaxHp(cols, constitutionHp);
		cols.put("revision", c.lng("revision") + 1);
		tx.update("character_class", classRow.id(), Map.of("level", to));
		tx.update("character", c.id(), cols);
		Row after = tx.get("character", c.id());
		se.hirt.mcp.rpg.magic.SpellService.initializeSlots(tx, rules, after);
		se.hirt.mcp.rpg.character.Origins.grantSpeciesSpells(tx, rules, after);
		se.hirt.mcp.rpg.character.Origins.initializeResources(tx, rules, after);
		tx.touched(Ref.of(Ref.CHARACTER, c.id()), after.lng("revision"));
		LedgerService.append(tx, campaignId,
				new LedgerService.EventSpec("LEVEL_UP",
						c.str("name") + " reached level " + to + " (" + def.name() + "): +" + gain + " HP"
								+ (constitutionHp == 0 ? "" : ", +" + constitutionHp + " HP for Constitution")
								+ (asi.isEmpty() ? "" : ", ability improvement " + asi) + ", levelled by the engine.",
						List.of(c.id()), "NOTABLE", "PARTY_KNOWN", "GM", null, c.lng("location_id"), null,
						Map.of("level", to, "hp_gain", gain, "companion", true)));
		var m = new LinkedHashMap<String, Object>();
		m.put("character", Ref.of(Ref.CHARACTER, c.id()));
		m.put("name", c.str("name"));
		m.put("class", def.name());
		m.put("level", to);
		m.put("hp_method", method);
		m.put("hp_gain", gain);
		if (constitutionHp != 0) {
			m.put("constitution_hp", constitutionHp);
		}
		m.put("max_hp", after.intOr("max_hp", 0));
		if (!asi.isEmpty()) {
			m.put("ability_score_improvement", asi);
		}
		m.put("note", "Class and subclass features are not data-driven; narrate them from the SRD.");
		return m;
	}

	/**
	 * What the engine would do at this level, without doing any of it and without rolling: the hit
	 * points the campaign's policy dictates and the ability improvement it would pick. Offered to
	 * the player so that accepting is one word and changing it is still open (RULES_ENGINE.md §6).
	 */
	static Map<String, Object> proposal(RulesData rules, Row campaign, Row c, RulesData.Definition def, int to) {
		int hitDie = ((Number) def.payload().get("hit_die")).intValue();
		int conMod = Rules.modifier(c.intOr("con_score", 10));
		int speciesHp = se.hirt.mcp.rpg.character.Origins.hpPerLevel(rules, c);
		String policy = PartyXp.rule(campaign, "hp_progression", "FIRST_3_MAX");
		var hp = new LinkedHashMap<String, Object>();
		if ("FIRST_3_MAX".equals(policy) && to <= 3) {
			hp.put("method", "MAX");
			hp.put("gain", Math.max(1, hitDie + conMod) + speciesHp);
		} else if ("AVERAGE".equals(policy)) {
			hp.put("method", "AVERAGE");
			hp.put("gain", Math.max(1, hitDie / 2 + 1 + conMod) + speciesHp);
		} else {
			hp.put("method", "ROLL");
			hp.put("gain", null);
			hp.put("range", "1d" + hitDie + " + " + conMod + (speciesHp == 0 ? "" : " + " + speciesHp)
					+ ", rolled openly at begin_level_up");
		}
		var m = new LinkedHashMap<String, Object>();
		m.put("hit_points", hp);
		if (asiLevels(def).contains(to)) {
			Map<String, Object> asi = autoAsi(c, def);
			m.put("ability_score_improvement", asi);
			m.put("alternative", "A feat may be taken instead; get_character_choices scope FEAT lists the legal ones.");
		}
		m.put("note", "Class and subclass features are not data-driven; narrate them from the SRD.");
		return m;
	}

	/**
	 * +2 into the class's primary ability, falling back to +1/+1 and then Constitution when scores
	 * are capped.
	 */
	@SuppressWarnings("unchecked")
	static Map<String, Object> autoAsi(Row c, RulesData.Definition def) {
		var order = new ArrayList<String>();
		if (def.payload().get("primary_abilities") instanceof List<?> l) {
			l.forEach(a -> order.add(String.valueOf(a)));
		}
		order.add("CON");
		order.add("DEX");
		order.add("WIS");
		var asi = new LinkedHashMap<String, Object>();
		for (String name : order) {
			Ability a = Ability.parse(name);
			if (c.intOr(a.column(), 10) <= Rules.MAX_SCORE - 2) {
				asi.put(a.name(), 2);
				return asi;
			}
		}
		for (String name : order) {
			Ability a = Ability.parse(name);
			if (asi.size() < 2 && !asi.containsKey(a.name()) && c.intOr(a.column(), 10) <= Rules.MAX_SCORE - 1) {
				asi.put(a.name(), 1);
			}
		}
		return asi.size() == 2 ? asi : Map.of();
	}

	/**
	 * Turns a companion recruited as a stat block into a character with an origin: species,
	 * background and the origin feats they carry, applied through exactly the same
	 * {@link se.hirt.mcp.rpg.character.Origins} code the character creation draft uses, so a
	 * promoted companion is indistinguishable from a player character afterwards.
	 * <p>
	 * The one deliberate omission is the background's ability-score increase. That belongs to
	 * creation; a companion being promoted has already been played with the scores they have, and
	 * silently adding +3 to them would rewrite a character the player already knows.
	 */
	@SuppressWarnings("unchecked")
	private static void applyOrigin(Tx tx, RulesData rules, Row character, Map<String, Object> chosen) {
		if (ORIGIN_CHOICES.stream().noneMatch(chosen::containsKey)) {
			return;
		}
		Row c = character;
		var cols = new LinkedHashMap<String, Object>();
		Map<String, Object> creation = c.isNull("creation_json") ? new LinkedHashMap<>()
				: new LinkedHashMap<>(c.map("creation_json"));
		RulesData.Definition species = null;
		if (chosen.get("species") != null) {
			species = rules.require(String.valueOf(chosen.get("species")), "SPECIES");
			cols.put("species_ref", species.id());
			se.hirt.mcp.rpg.character.Origins.onSpeciesSet(tx, rules, species, c, cols);
		} else if (!c.isNull("species_ref")) {
			species = rules.require(c.str("species_ref"), "SPECIES");
		}
		if (chosen.get("species_choice") != null) {
			se.hirt.mcp.rpg.character.Origins.applySpeciesChoice(tx, rules, requireSpecies(species), c,
					chosen.get("species_choice"), cols);
		}
		if (chosen.get("species_skill") != null) {
			se.hirt.mcp.rpg.character.Origins.applySpeciesSkill(tx, rules, requireSpecies(species), c,
					chosen.get("species_skill"));
		}
		if (chosen.get("background") != null) {
			se.hirt.mcp.rpg.character.Origins.applyBackground(tx, rules, c, chosen.get("background"), cols, creation);
		}
		if (chosen.get("origin_feat") != null) {
			se.hirt.mcp.rpg.character.Origins.applyOriginFeat(tx, rules, requireSpecies(species), c,
					chosen.get("origin_feat"));
		}
		if (chosen.get("background_tool") != null) {
			RulesData.Definition bg = se.hirt.mcp.rpg.character.Origins
					.resolveBackground(tx, rules, c.lng("campaign_id"),
							chosen.get("background") != null ? String.valueOf(chosen.get("background"))
									: c.str("background_ref"))
					.orElseThrow(() -> RpgException.invalidArgument("Choose a background first."));
			se.hirt.mcp.rpg.character.Origins.applyBackgroundTool(tx, rules, bg, c, chosen.get("background_tool"));
		}
		if (chosen.get("feat_choices") != null) {
			se.hirt.mcp.rpg.character.Origins.applyFeatChoices(tx, rules, c, chosen.get("feat_choices"));
		}
		if (!cols.isEmpty()) {
			cols.put("creation_json", Json.write(creation));
			tx.update("character", c.id(), cols);
		}
	}

	private static RulesData.Definition requireSpecies(RulesData.Definition species) {
		if (species == null) {
			throw RpgException.validation(List.of(new Violation("species", "REQUIRED",
					"Choose the species in the same call, before the choices that depend on it.")));
		}
		return species;
	}

	/** The skills a class may choose from; "ANY" means the whole list. */
	@SuppressWarnings("unchecked")
	static List<String> skillOptions(RulesData rules, RulesData.Definition cls) {
		Map<String, Object> choices = (Map<String, Object>) cls.payload().get("skill_choices");
		Object options = choices.get("options");
		return "ANY".equals(options) ? rules.ofKind("SKILL").stream().map(RulesData.Definition::id).toList()
				: ((List<Object>) options).stream().map(Object::toString).toList();
	}

	@SuppressWarnings("unchecked")
	static int skillChoiceCount(RulesData.Definition cls) {
		return ((Number) ((Map<String, Object>) cls.payload().get("skill_choices")).get("count")).intValue();
	}

	/**
	 * Hit points at a first class level: the full hit die plus CON and species bonuses (SRD 5.2.1
	 * "Hit Points").
	 */
	static int firstLevelHp(RulesData rules, Row c, RulesData.Definition def) {
		int hitDie = ((Number) def.payload().get("hit_die")).intValue();
		return Math.max(1, hitDie + Rules.modifier(c.intOr("con_score", 10)))
				+ se.hirt.mcp.rpg.character.Origins.hpPerLevel(rules, c);
	}

	/** The class's saving-throw proficiencies, as the character-creation path grants them. */
	@SuppressWarnings("unchecked")
	static void grantClassSaves(Tx tx, Row c, RulesData.Definition def) {
		for (Object save : (List<Object>) def.payload().get("saving_throws")) {
			if (tx.count(
					"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SAVE' AND content_ref = ?",
					c.id(), save.toString()) == 0) {
				tx.insert("character_trait",
						Map.of("character_id", c.id(), "kind", "SAVE", "content_ref", save.toString()));
			}
		}
	}

	// ── abandon_transaction (any kind) ─────────────────────────────────

	public Map<String, Object> abandon(String operationId, String campaignRef, String transactionRef, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("transaction", transactionRef);
		args.put("reason", reason);
		return db.mutate(Database.Mutation.of("abandon_transaction", campaignId, operationId, "PLAYER", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "abandon_transaction");
			Optional<Row> open = transactionRef == null || transactionRef.isBlank() ? tx.queryOne(
					"SELECT * FROM pending_transaction WHERE campaign_id = ? AND status = 'OPEN' ORDER BY id DESC LIMIT 1",
					campaignId) : tx.find("pending_transaction", Ref.id(transactionRef, Ref.TRANSACTION));
			Row t = open.orElseThrow(() -> RpgException.notFound("An open transaction"));
			if (t.lng("campaign_id") != campaignId || !"OPEN".equals(t.str("status"))) {
				throw RpgException
						.notAllowed(Ref.of(Ref.TRANSACTION, t.id()) + " is not an open transaction of this campaign.");
			}
			tx.update("pending_transaction", t.id(), Map.of("status", "ABANDONED", "revision", t.lng("revision") + 1));
			HarnessState back = tx.count(
					"SELECT COUNT(*) FROM encounter WHERE campaign_id = ? AND status IN ('RUNNING','WAITING_CHOICE')",
					campaignId) > 0 ? HarnessState.ENCOUNTER : HarnessState.EXPLORATION;
			tx.update("campaign", campaignId,
					Map.of("harness_state", back.name(), "revision", campaign.lng("revision") + 1));
			var result = new LinkedHashMap<String, Object>();
			result.put("transaction", Ref.of(Ref.TRANSACTION, t.id()));
			result.put("kind", t.str("kind"));
			result.put("status", "ABANDONED");
			result.put("note", "Canonical state is unchanged (I-56).");
			result.put("meta", Harness.meta(tx.get("campaign", campaignId), null));
			return result;
		});
	}
}
