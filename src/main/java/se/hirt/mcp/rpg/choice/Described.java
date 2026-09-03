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
package se.hirt.mcp.rpg.choice;

import java.util.Arrays;
import java.util.List;

/**
 * A closed set of legal values that knows how to present itself. Every setting the player must decide on is an enum
 * implementing this interface, so the legal values, their labels and their descriptions live in exactly one place:
 * validation, {@code constraints}, and the structured {@code decisions} handed to the GM are all derived from it
 * (MCP_PROTOCOL.md §9.3.1).
 */
public interface Described {

	/** The canonical value, i.e. the enum constant name. */
	String name();

	/** Short human label, e.g. "Point buy". */
	String label();

	/** One or two sentences a GM can read out so the player can choose. */
	String description();

	/** True for the sensible default; at most one constant per enum should say so. */
	default boolean recommended() {
		return false;
	}

	static <E extends Enum<E> & Described> List<String> names(Class<E> type) {
		return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
	}

	static <E extends Enum<E> & Described> List<Option> options(Class<E> type) {
		return Arrays.stream(type.getEnumConstants()).map(Option::of).toList();
	}

	static <E extends Enum<E> & Described> String recommended(Class<E> type) {
		return Arrays.stream(type.getEnumConstants()).filter(Described::recommended).map(Enum::name).findFirst()
				.orElse(null);
	}
}
