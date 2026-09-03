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
package se.hirt.mcp.rpg.dice;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Test roller: returns queued die values first, then falls back to a seeded generator. A queued value larger than the
 * die is clamped so a script can say "roll high" without knowing the die size.
 */
public final class ScriptedRollService extends RollService {

	private final Deque<Integer> script = new ArrayDeque<>();
	private final Random fallback;

	public ScriptedRollService(long seed) {
		this.fallback = new Random(seed);
	}

	public ScriptedRollService queue(int... values) {
		for (int v : values) {
			script.addLast(v);
		}
		return this;
	}

	public boolean exhausted() {
		return script.isEmpty();
	}

	@Override
	protected int rollDie(int sides) {
		Integer next = script.pollFirst();
		if (next == null) {
			return fallback.nextInt(sides) + 1;
		}
		return Math.max(1, Math.min(sides, next));
	}
}
