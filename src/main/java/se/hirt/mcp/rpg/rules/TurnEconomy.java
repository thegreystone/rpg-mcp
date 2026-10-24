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

import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.LinkedHashMap;
import java.util.Map;

/** The Bonus Action shared by Light-weapon attacks, spells and Font of Magic. */
public final class TurnEconomy {

	private TurnEconomy() {
	}

	/** A participant's turn, distinct from the same participant's next round. */
	public static String turnKey(Row encounter) {
		return encounter.lng("turn_participant_id") + ":" + encounter.lng("round");
	}

	/** Whether this actor can still take a Bonus Action on the current turn. */
	public static boolean bonusActionAvailable(Tx tx, Row encounter, long actorId) {
		Turn turn = currentTurn(tx, encounter, actorId);
		return turn != null && !turnKey(turn.encounter()).equals(flags(turn.participant()).get("bonus_action_turn"));
	}

	/**
	 * The Action was taken on this turn (SRD 5.2.1 "Your Turn"). Recorded, never refused: a second
	 * Action in a turn is the GM's call (Action Surge, Haste).
	 */
	public static final String ACTION = "action_turn";
	/** A MOVE or DASH was made on this turn; zones carry no distance, so only the fact is known. */
	public static final String MOVED = "moved_turn";
	/** The actor stood up from Prone on this turn, for half its Speed (SRD 5.2.1 "Prone"). */
	public static final String STOOD_UP = "stood_up_turn";

	/** Whether this actor can still take the Action on the current turn. */
	public static boolean actionAvailable(Tx tx, Row encounter, long actorId) {
		Turn turn = currentTurn(tx, encounter, actorId);
		return turn != null && !turnKey(turn.encounter()).equals(flags(turn.participant()).get(ACTION));
	}

	/** Spends the current turn's Bonus Action in the same journaled mutation as its effect. */
	public static void spendBonusAction(Tx tx, Row encounter, long actorId, String source) {
		Turn turn = currentTurn(tx, encounter, actorId);
		if (turn == null) {
			throw RpgException.notAllowed("A Bonus Action requires the actor's active turn in a running encounter.");
		}
		Map<String, Object> flags = flags(turn.participant());
		String key = turnKey(turn.encounter());
		if (key.equals(flags.get("bonus_action_turn"))) {
			throw RpgException.notAllowed(
					"The Bonus Action for this turn has already been spent" + (flags.get("bonus_action_source") == null
							? "." : " on " + flags.get("bonus_action_source") + "."));
		}
		flags.put("bonus_action_turn", key);
		flags.put("bonus_action_source", source == null ? "a Bonus Action" : source);
		tx.update("encounter_participant", turn.participant().id(), Map.of("once_per_turn_json", Json.write(flags)));
	}

	/**
	 * A spell slot was expended on this turn (SRD 5.2.1 "One Spell with a Spell Slot per Turn").
	 */
	public static final String SLOT_SPENT = "slot_spent_turn";
	/** A level 1+ spell was cast on this turn (Quickened Spell's first clause). */
	public static final String LEVELED_SPELL = "leveled_spell_turn";
	/** A spell was Quickened on this turn (Quickened Spell's second clause). */
	public static final String QUICKENED = "quickened_turn";

	/**
	 * Whether the given once-per-turn marker is already set for the actor's current turn. False
	 * outside the actor's own turn (a reaction on another creature's turn belongs to that turn).
	 * Pending choices (WAITING_CHOICE) still count as the same turn.
	 */
	public static boolean markedThisTurn(Tx tx, Row encounter, long actorId, String marker) {
		Turn turn = turnOf(tx, encounter, actorId, true);
		return turn != null && turnKey(turn.encounter()).equals(flags(turn.participant()).get(marker));
	}

	/**
	 * Sets a once-per-turn marker for the actor's current turn; a no-op outside the actor's own
	 * turn.
	 */
	public static void markThisTurn(Tx tx, Row encounter, long actorId, String marker) {
		Turn turn = turnOf(tx, encounter, actorId, true);
		if (turn == null) {
			return;
		}
		Map<String, Object> flags = flags(turn.participant());
		flags.put(marker, turnKey(turn.encounter()));
		tx.update("encounter_participant", turn.participant().id(), Map.of("once_per_turn_json", Json.write(flags)));
	}

	private static Map<String, Object> flags(Row participant) {
		return participant.isNull("once_per_turn_json") ? new LinkedHashMap<>()
				: new LinkedHashMap<>(participant.map("once_per_turn_json"));
	}

	private record Turn(Row encounter, Row participant) {
	}

	private static Turn currentTurn(Tx tx, Row encounter, long actorId) {
		return turnOf(tx, encounter, actorId, false);
	}

	private static Turn turnOf(Tx tx, Row encounter, long actorId, boolean allowWaitingChoice) {
		Row live = tx.find("encounter", encounter.id()).orElse(null);
		if (live == null || live.lng("turn_participant_id") == null || !("RUNNING".equals(live.str("status"))
				|| allowWaitingChoice && "WAITING_CHOICE".equals(live.str("status")))) {
			return null;
		}
		Row participant = tx.queryOne(
				"SELECT p.* FROM encounter_participant p JOIN character c ON c.id = p.character_id "
						+ "WHERE p.encounter_id = ? AND p.character_id = ? AND p.id = ? AND p.status = 'ACTIVE' "
						+ "AND c.life_state = 'ALIVE' AND NOT EXISTS (SELECT 1 FROM active_effect fx WHERE fx.character_id = c.id "
						+ "AND fx.condition_ref IN ('srd5e:condition/incapacitated', 'srd5e:condition/stunned', "
						+ "'srd5e:condition/paralyzed', 'srd5e:condition/unconscious', 'srd5e:condition/petrified'))",
				live.id(), actorId, live.lng("turn_participant_id")).orElse(null);
		return participant == null ? null : new Turn(live, participant);
	}
}
