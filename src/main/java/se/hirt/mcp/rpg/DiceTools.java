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

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * The free dice roll (MCP_PROTOCOL.md §13.8): every random number in play comes from the server's
 * roller and is journaled, including the ones no semantic tool covers — falling damage, a random
 * encounter table, a coin toss, an NPC's own dice behind the screen. Nothing is applied; pair the
 * result with {@code apply_runtime_change} or narrate it.
 */
public class DiceTools {

	@Inject
	Engine engine;

	@Tool(name = "roll_dice", description = "MUTATING (journaled roll). Rolls a dice expression with the server's dice and returns the breakdown and a roll_ref. "
			+ "For everything no semantic tool rolls for you: falling damage (then apply_runtime_change DAMAGE {dice} applies it directly), a random "
			+ "table, a wandering-monster check, a percentile chance, the dice of an NPC behind the screen. Never invent a die result. "
			+ "Expressions: NdS with +/- constants (\"2d6+3\"), keep/drop (\"4d6dl1\", \"2d20kh1\"), several terms (\"1d8+1d6-1\"). Nothing is applied.", annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
	ToolResponse rollDice(
		@ToolArg(description = "Client-generated idempotency key, unique within the campaign (e.g. a ULID).")
		String operation_id, @ToolArg(description = "Campaign reference, e.g. 'campaign:1'")
		String campaign, @ToolArg(description = "Dice expression, e.g. '2d6+3', '1d100', '4d6dl1'")
		String expression, @ToolArg(description = "Character the roll is about, if any (journaled with the roll)")
		Optional<String> actor,
		@ToolArg(description = "Why, e.g. 'fall from the sea-cliff (20 ft)', 'random encounter check'")
		Optional<String> reason) {
		return ToolSupport.run("roll_dice", () -> engine.checks().rollDice(operation_id, campaign, expression,
				actor.orElse(null), reason.orElse(null)));
	}
}
