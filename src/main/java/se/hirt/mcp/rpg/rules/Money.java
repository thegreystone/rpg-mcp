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

import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Money is one non-negative integer in canonical copper (DESIGN.md §12, I-14). Denominations are presentation and
 * exchange metadata: SRD 5.2.1 Coin Values — 1 SP = 10 CP, 1 EP = 50 CP, 1 GP = 100 CP, 1 PP = 1,000 CP; 50 coins weigh
 * 1 lb.
 */
public final class Money {

	public static final long CP = 1;
	public static final long SP = 10;
	public static final long EP = 50;
	public static final long GP = 100;
	public static final long PP = 1000;
	public static final int COINS_PER_POUND = 50;

	private static final Pattern PART = Pattern.compile("(\\d+)\\s*(pp|gp|ep|sp|cp)", Pattern.CASE_INSENSITIVE);

	private Money() {
	}

	/** Accepts an integer (copper), {@code {"gp": 15, "sp": 2}} or {@code "15 gp 2 sp"}. */
	public static long parseCp(Object value) {
		if (value == null) {
			throw RpgException.invalidArgument("An amount of money is required.");
		}
		if (value instanceof Number n) {
			return requireNonNegative(n.longValue());
		}
		if (value instanceof Map<?, ?> m) {
			long total = 0;
			for (var e : m.entrySet()) {
				if (!(e.getValue() instanceof Number n)) {
					throw RpgException.invalidArgument(
							"Money amounts must be numbers; got '" + e.getValue() + "' for " + e.getKey() + ".");
				}
				total += n.longValue() * unit(String.valueOf(e.getKey()));
			}
			return requireNonNegative(total);
		}
		String text = value.toString().trim().toLowerCase();
		if (text.matches("\\d+")) {
			return Long.parseLong(text);
		}
		Matcher matcher = PART.matcher(text);
		long total = 0;
		boolean any = false;
		while (matcher.find()) {
			total += Long.parseLong(matcher.group(1)) * unit(matcher.group(2));
			any = true;
		}
		if (!any) {
			throw RpgException.invalidArgument(
					"Cannot parse money '" + value + "'; use e.g. '15 gp', '2 sp 5 cp', {\"gp\": 15} or an integer in cp.");
		}
		return total;
	}

	private static long unit(String code) {
		return switch (code.toLowerCase()) {
			case "pp" -> PP;
			case "gp" -> GP;
			case "ep" -> EP;
			case "sp" -> SP;
			case "cp" -> CP;
			default -> throw RpgException.invalidArgument("Unknown coin '" + code + "'; use pp, gp, ep, sp or cp.");
		};
	}

	private static long requireNonNegative(long cp) {
		if (cp < 0) {
			throw RpgException.invalidArgument("Money cannot be negative.");
		}
		return cp;
	}

	/** Lossless rendering in gp/sp/cp (electrum and platinum are exchange coins, not shown). */
	public static Map<String, Object> render(Long cpValue) {
		long total = cpValue == null ? 0 : cpValue;
		var m = new LinkedHashMap<String, Object>();
		m.put("total_cp", total);
		m.put("gp", total / GP);
		m.put("sp", (total % GP) / SP);
		m.put("cp", total % SP);
		m.put("display", format(total));
		return m;
	}

	public static String format(long cp) {
		long gp = cp / GP;
		long sp = (cp % GP) / SP;
		long c = cp % SP;
		var sb = new StringBuilder();
		if (gp > 0) {
			sb.append(gp).append(" gp");
		}
		if (sp > 0) {
			sb.append(sb.isEmpty() ? "" : " ").append(sp).append(" sp");
		}
		if (c > 0 || sb.isEmpty()) {
			sb.append(sb.isEmpty() ? "" : " ").append(c).append(" cp");
		}
		return sb.toString();
	}

	/** Coins actually carried for a canonical amount, assuming the fewest coins (pp, gp, sp, cp). */
	public static long coinCount(long cp) {
		long remaining = cp;
		long coins = remaining / PP;
		remaining %= PP;
		coins += remaining / GP;
		remaining %= GP;
		coins += remaining / SP;
		remaining %= SP;
		return coins + remaining;
	}

	public static double coinWeightLb(long cp) {
		return coinCount(cp) / (double) COINS_PER_POUND;
	}
}
