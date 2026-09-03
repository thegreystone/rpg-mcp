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

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.DiceExpression;
import se.hirt.mcp.rpg.dice.RandomRollService;
import se.hirt.mcp.rpg.dice.Roll;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Rules;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Roller sanity invariants (MCP_PROTOCOL.md §25.5): dice within bounds, arithmetic consistent.
 */
class DiceTest {

	@Test
	void parsesCompoundExpressions() {
		DiceExpression e = DiceExpression.parse("2d6+1d4-3");
		assertEquals(3, e.terms().size());
		assertEquals(-3, e.constantModifier());
		assertThrows(RpgException.class, () -> DiceExpression.parse("2x6"));
		assertThrows(RpgException.class, () -> DiceExpression.parse("4d6dl5"));
	}

	@Test
	void randomRollsStayWithinBounds() {
		var roller = new RandomRollService();
		for (int i = 0; i < 500; i++) {
			Roll r = roller.roll("3d6+2");
			assertEquals(3, r.dice().size());
			r.dice().forEach(d -> assertTrue(d >= 1 && d <= 6));
			assertEquals(r.dice().stream().mapToInt(Integer::intValue).sum() + 2, r.total());
			assertEquals(2, r.modifier());
		}
	}

	@Test
	void dropLowestKeepsThreeHighest() {
		var roller = new ScriptedRollService(1).queue(6, 5, 5, 2);
		Roll r = roller.roll("4d6dl1");
		assertEquals(List.of(6, 5, 5), r.dice());
		assertEquals(List.of(2), r.dropped());
		assertEquals(16, r.total());
		assertEquals("4d6dl1", r.expression());
	}

	@Test
	void advantageAndDisadvantage() {
		assertEquals(17, new ScriptedRollService(1).queue(4, 17).roll("2d20kh1").total());
		assertEquals(4, new ScriptedRollService(1).queue(4, 17).roll("2d20kl1").total());
		Roll r = new ScriptedRollService(1).queue(17).roll("1d20+5");
		assertEquals(22, r.total());
		assertEquals("1d20+5", r.expression());
	}

	@Test
	void scriptedValuesAreClamped() {
		assertEquals(6, new ScriptedRollService(1).queue(99).roll("1d6").total());
	}

	@Test
	void abilityModifierFormula() {
		assertEquals(-1, Rules.modifier(9));
		assertEquals(0, Rules.modifier(10));
		assertEquals(0, Rules.modifier(11));
		assertEquals(3, Rules.modifier(16));
		assertEquals(-5, Rules.modifier(1));
		assertEquals(5, Rules.modifier(20));
	}
}
