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
package se.hirt.mcp.rpg.economy;

import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.Map;

/**
 * A money bag reference (MCP_PROTOCOL.md §14.6): {@code account:n} (an estate or faction treasury),
 * {@code character:n} (a character's purse, {@code character.money_cp}) or {@code WORLD} (the
 * bottomless outside world: a lease paid out to a distant lord, stallage paid in by nameless
 * traders). Parsing is case-insensitive ({@code ACCOUNT:3} works); the canonical rendering is
 * lower-case for typed refs and {@code WORLD}.
 */
public record MoneyRef(Kind kind, Long id) {

	public enum Kind {
		ACCOUNT, CHARACTER, WORLD
	}

	public static final MoneyRef WORLD = new MoneyRef(Kind.WORLD, null);

	public static MoneyRef parse(Object value) {
		if (value == null) {
			throw RpgException.invalidArgument("A money reference is required: 'account:n', 'character:n' or 'WORLD'.");
		}
		String s = value.toString().trim();
		if (s.equalsIgnoreCase("WORLD")) {
			return WORLD;
		}
		int colon = s.indexOf(':');
		if (colon > 0) {
			String type = s.substring(0, colon).trim().toLowerCase();
			String rest = s.substring(colon + 1).trim();
			if (rest.matches("[1-9][0-9]{0,17}")) {
				long id = Long.parseLong(rest);
				if (type.equals("account")) {
					return new MoneyRef(Kind.ACCOUNT, id);
				}
				if (type.equals("character")) {
					return new MoneyRef(Kind.CHARACTER, id);
				}
			}
		}
		throw RpgException
				.invalidArgument("Money references look like 'account:3', 'character:4' or 'WORLD'; got '" + s + "'.");
	}

	/** Parses a reference, or resolves an account by name within the campaign. */
	public static MoneyRef resolve(Tx tx, long campaignId, Object value) {
		if (value != null && !value.toString().equalsIgnoreCase("WORLD") && value.toString().indexOf(':') < 0) {
			Row account = tx
					.queryOne("SELECT * FROM account WHERE campaign_id = ? AND name = ?", campaignId,
							value.toString().trim())
					.orElseThrow(() -> RpgException.notFound("Account '" + value
							+ "' (use 'account:n', 'character:n', 'WORLD' or an existing account name)"));
			return new MoneyRef(Kind.ACCOUNT, account.id());
		}
		MoneyRef ref = parse(value);
		ref.row(tx, campaignId);
		return ref;
	}

	public boolean isWorld() {
		return kind == Kind.WORLD;
	}

	@Override
	public String toString() {
		return switch (kind) {
		case WORLD -> "WORLD";
		case ACCOUNT -> "account:" + id;
		case CHARACTER -> "character:" + id;
		};
	}

	/** The account or character row, verified to belong to the campaign; null for WORLD. */
	public Row row(Tx tx, long campaignId) {
		return switch (kind) {
		case WORLD -> null;
		case ACCOUNT -> {
			Row a = tx.find("account", id).orElseThrow(() -> RpgException.notFound("Account " + this));
			if (a.lng("campaign_id") != campaignId) {
				throw RpgException.invalidArgument(this + " belongs to another campaign.");
			}
			yield a;
		}
		case CHARACTER -> {
			Row c = tx.find("character", id).orElseThrow(() -> RpgException.notFound("Character " + this));
			if (c.lng("campaign_id") != campaignId) {
				throw RpgException.invalidArgument(this + " belongs to another campaign.");
			}
			yield c;
		}
		};
	}

	public String name(Tx tx, long campaignId) {
		Row r = row(tx, campaignId);
		return r == null ? "the world" : r.str("name");
	}

	/** Balance in copper; the world holds nothing and everything. */
	public long balance(Tx tx, long campaignId) {
		Row r = row(tx, campaignId);
		return r == null ? Long.MAX_VALUE : r.lng("money_cp") == null ? 0 : r.lng("money_cp");
	}

	/**
	 * Adds {@code deltaCp} (negative to withdraw); no-op for WORLD. The caller checks sufficiency.
	 */
	public void adjust(Tx tx, long campaignId, long deltaCp) {
		Row r = row(tx, campaignId);
		if (r == null || deltaCp == 0) {
			return;
		}
		long have = r.lng("money_cp") == null ? 0 : r.lng("money_cp");
		if (have + deltaCp < 0) {
			throw RpgException.insufficientResource(
					name(tx, campaignId) + " holds " + se.hirt.mcp.rpg.rules.Money.format(have) + ", not enough.");
		}
		String table = kind == Kind.ACCOUNT ? "account" : "character";
		tx.update(table, r.id(), Map.of("money_cp", have + deltaCp, "revision", r.lng("revision") + 1));
	}
}
