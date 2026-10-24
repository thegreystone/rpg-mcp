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

import se.hirt.mcp.rpg.character.RuntimeService;
import se.hirt.mcp.rpg.content.RulesData;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.RollService;
import se.hirt.mcp.rpg.magic.SpellService;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Combat;
import se.hirt.mcp.rpg.rules.Effects;
import se.hirt.mcp.rpg.rules.TurnEconomy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Shield spell as a reaction (SRD 5.2.1 "Shield"): "Reaction, which you take when you are hit
 * by an attack roll or targeted by the Magic Missile spell ... Until the start of your next turn,
 * you have a +5 bonus to AC, including against the triggering attack, and you take no damage from
 * Magic Missile." Shared by the weapon-attack hook in {@link EncounterService} and the Magic
 * Missile branch of the casting core: who may cast it, how it is cast, and how a deferred Magic
 * Missile is resolved.
 * <p>
 * Casting Shield expends a spell slot, so on the caster's own turn (an Opportunity Attack provoked
 * by their move) it is unavailable once a slot was expended that turn and it marks the turn when
 * cast (SRD 5.2.1 "One Spell with a Spell Slot per Turn"); on another creature's turn it is never
 * blocked by, and never blocks, the caster's own turn.
 */
public final class ShieldSpell {

	static final String SPELL = "srd5e:spell/shield";
	static final String EFFECT = "srd5e:effect/shield-spell";

	private ShieldSpell() {
	}

	static Optional<Row> participant(Tx tx, long encounterId, long characterId) {
		return tx.queryOne("SELECT * FROM encounter_participant WHERE encounter_id = ? AND character_id = ?",
				encounterId, characterId);
	}

	/**
	 * Whether the target can cast Shield now: reaction, the spell prepared, a slot, and no slot
	 * spent on its own turn.
	 */
	static boolean canCast(Tx tx, RulesData rules, Row encounter, Row target, Row participant) {
		// Untrained armor: "you can't cast spells" (SRD 5.2.1 "Armor Training"), reactions included.
		return se.hirt.mcp.rpg.rules.Training.untrainedArmor(tx, rules, target).isEmpty()
				&& EncounterService.reactionAvailable(tx, participant, target)
				&& tx.count(
						"SELECT COUNT(*) FROM character_trait WHERE character_id = ? AND kind = 'SPELL_PREPARED' AND content_ref = ?",
						target.id(), SPELL) > 0
				&& SpellService.hasSlot(tx, target.id(), 1)
				&& !TurnEconomy.markedThisTurn(tx, encounter, target.id(), TurnEconomy.SLOT_SPENT);
	}

	/**
	 * Whether the character already enjoys Shield's "no damage from Magic Missile" (the +5 is still
	 * up).
	 */
	static boolean immuneToMagicMissile(Tx tx, long characterId) {
		for (Row e : tx.query(
				"SELECT modifier_json FROM active_effect WHERE character_id = ? AND modifier_json LIKE '%immune_magic_missile%'",
				characterId)) {
			if (Boolean.TRUE.equals(e.map("modifier_json").get("immune_magic_missile"))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Player-controlled reactors and the ASK policy decide through a pending choice; AUTO resolves
	 * at once.
	 */
	static boolean asksBeforeReacting(Tx tx, long campaignId, Row encounter, long characterId) {
		return EncounterService.playerControlled(tx, campaignId, characterId)
				|| "ASK".equals(encounter.str("npc_reactions"));
	}

	/**
	 * Casts Shield: the lowest slot, +5 AC and Magic Missile immunity until the caster's next turn,
	 * the reaction.
	 */
	static Map<String, Object> cast(Tx tx, long campaignId, Row encounter, Row target, Row participant, long round) {
		Map<String, Object> slot = SpellService.spendLowestSlot(tx, target.id(), 1).orElseThrow(
				() -> RpgException.insufficientResource(target.str("name") + " has no spell slot for Shield."));
		Effects.add(tx, campaignId, target.id(), target.id(), SPELL, "Shield (" + target.str("name") + ")", EFFECT,
				Map.of("ac_bonus", 5, "immune_magic_missile", true),
				Map.of("until", "START_OF_TURN", "participant", participant.id(), "label", "Shield"), null, "SHIELD",
				"PLAYER");
		tx.update("encounter_participant", participant.id(), Map.of("reaction_used", 1));
		TurnEconomy.markThisTurn(tx, encounter, target.id(), TurnEconomy.SLOT_SPENT);
		EncounterService.log(tx, campaignId, encounter.id(), round, target.id(), "REACTION",
				target.str("name") + " casts Shield as a reaction (AC +5 until their next turn).", null);
		var m = new LinkedHashMap<String, Object>();
		m.put("cast", "Shield");
		m.put("slot", slot);
		m.put("armor_class_bonus", 5);
		return m;
	}

	/**
	 * How a Magic Missile target reacts, decided once per target before any dart lands: IMMUNE
	 * (Shield already up), CAST_SHIELD (an NPC under the AUTO policy cast it now), PENDING (the
	 * chooser is asked; the darts wait), or NONE.
	 */
	public static String againstMagicMissile(
		Tx tx, RulesData rules, long campaignId, long encounterId, long round, Row caster, Row target) {
		if (immuneToMagicMissile(tx, target.id())) {
			return "IMMUNE";
		}
		Row encounter = tx.find("encounter", encounterId).orElse(null);
		Row participant = participant(tx, encounterId, target.id()).orElse(null);
		if (encounter == null || participant == null || !canCast(tx, rules, encounter, target, participant)) {
			return "NONE";
		}
		if (asksBeforeReacting(tx, campaignId, encounter, target.id())) {
			return "PENDING";
		}
		cast(tx, campaignId, encounter, target, participant, round);
		return "CAST_SHIELD";
	}

	/**
	 * Opens the SHIELD_SPELL choice for one Magic Missile target; the rolled darts ride on the
	 * choice and are applied only on DECLINE. The encounter's perform() picks the choice up.
	 */
	public static long openMagicMissileChoice(
		Tx tx, long campaignId, long encounterId, Row caster, Row target, RulesData.Definition spell,
		List<Map<String, Object>> darts) {
		Row encounter = tx.get("encounter", encounterId);
		var ctx = new LinkedHashMap<String, Object>();
		ctx.put("magic_missile", true);
		ctx.put("spell", spell.id());
		ctx.put("spell_name", spell.name());
		ctx.put("attacker", caster.id());
		ctx.put("target", target.id());
		ctx.put("darts", darts);
		int total = darts.stream().mapToInt(d -> dartTotal(d)).sum();
		var options = List.of(Map.of("option", "CAST_SHIELD", "description",
				"Cast Shield as a reaction (spends the lowest available spell slot): no damage from Magic Missile and AC +5 until the start of "
						+ target.str("name") + "'s next turn."),
				Map.of("option", "DECLINE", "description", "Take the darts (" + total + " force damage)."));
		var payload = new LinkedHashMap<String, Object>();
		payload.put("choice_kind", "SHIELD_SPELL");
		payload.put("encounter_id", encounter.id());
		payload.put("chooser", target.id());
		payload.put("prompt", caster.str("name") + "'s " + spell.name() + " targets " + target.str("name") + " with "
				+ darts.size() + " dart" + (darts.size() == 1 ? "" : "s") + ". Cast Shield?");
		payload.put("options", options);
		payload.put("context", ctx);
		payload.put("round", encounter.lng("round"));
		var cols = new LinkedHashMap<String, Object>();
		cols.put("campaign_id", campaignId);
		cols.put("kind", "ENCOUNTER_CHOICE");
		cols.put("status", "OPEN");
		cols.put("controlled_refs_json",
				Json.write(List.of(Ref.of(Ref.ENCOUNTER, encounter.id()), Ref.of(Ref.CHARACTER, target.id()))));
		cols.put("payload_json", Json.write(payload));
		cols.put("revision", 0);
		return tx.insert("pending_transaction", cols);
	}

	@SuppressWarnings("unchecked")
	private static int dartTotal(Map<String, Object> dart) {
		int sum = 0;
		for (Map<String, Object> part : (List<Map<String, Object>>) dart.get("breakdown")) {
			sum += ((Number) part.get("amount")).intValue();
		}
		return sum;
	}

	/**
	 * DECLINE on a Magic Missile choice: the darts rolled at casting land now, one application per
	 * dart as in the undeferred path. Idempotent on replay through the mutation journal.
	 */
	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> applyDeferredDarts(
		Tx tx, RulesData rules, RollService roller, long campaignId, Row encounter, Row caster, Row target,
		Row participant, Map<String, Object> ctx, long round) {
		var out = new ArrayList<Map<String, Object>>();
		String spellName = String.valueOf(ctx.getOrDefault("spell_name", "Magic Missile"));
		for (Map<String, Object> dart : (List<Map<String, Object>>) ctx.get("darts")) {
			Row live = tx.get("character", target.id());
			var t = new LinkedHashMap<String, Object>();
			t.put("target", Ref.of(Ref.CHARACTER, live.id()));
			t.put("name", live.str("name"));
			t.put("dart", dart.get("dart"));
			if ("DEAD".equals(live.str("life_state"))) {
				t.put("skipped", "already dead");
				out.add(t);
				continue;
			}
			var rolled = new ArrayList<Combat.RolledDamage>();
			for (Map<String, Object> part : (List<Map<String, Object>>) dart.get("breakdown")) {
				Map<String, Object> r = (Map<String, Object>) part.get("roll");
				List<Integer> dice = ((List<Number>) r.getOrDefault("dice", List.of())).stream().map(Number::intValue)
						.toList();
				List<Integer> dropped = ((List<Number>) r.getOrDefault("dropped", List.of())).stream()
						.map(Number::intValue).toList();
				Roll roll = new Roll(String.valueOf(r.get("expression")), dice, dropped,
						((Number) r.getOrDefault("modifier", 0)).intValue(), ((Number) r.get("total")).intValue());
				rolled.add(new Combat.RolledDamage(String.valueOf(part.get("type")), roll,
						((Number) part.get("amount")).intValue()));
			}
			Combat.DamageResult dr = Combat.applyDamage(live.intOr("current_hp", 0), live.intOr("temp_hp", 0),
					Math.max(1, RuntimeService.effectiveMaxHp(tx, live)), rolled,
					RuntimeService.defensesOf(tx, rules, live));
			t.putAll(RuntimeService.applyDamageResult(tx, rules, roller, live, dr, false, caster.id(),
					"slain by " + spellName));
			if ("DEAD".equals(t.get("life_state"))) {
				tx.update("encounter_participant", participant.id(), Map.of("status", "DEFEATED"));
			}
			out.add(t);
		}
		int total = out.stream().mapToInt(t -> t.get("damage") instanceof Number n ? n.intValue() : 0).sum();
		EncounterService.log(tx, campaignId, encounter.id(), round, target.id(), "REACTION",
				target.str("name") + " declines to cast Shield and takes " + total + " damage from " + spellName + ".",
				null);
		return out;
	}
}
