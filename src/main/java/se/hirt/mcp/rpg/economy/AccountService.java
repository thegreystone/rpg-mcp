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

import se.hirt.mcp.rpg.harness.Harness;
import se.hirt.mcp.rpg.ledger.LedgerService;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;
import se.hirt.mcp.rpg.rules.Money;
import se.hirt.mcp.rpg.session.Calendar;
import se.hirt.mcp.rpg.session.GameTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accounts (MCP_PROTOCOL.md §14.6, DATABASE.md §3.11): money bags that are not a character's purse — an estate or
 * faction treasury such as "The House of Greystone". Balances are copper like character purses; every movement is a
 * {@code MONEY_FLOW} ledger event, and the engine never creates coin silently: an opening balance or a transfer from
 * {@code WORLD} is a GM grant recorded as such.
 */
public final class AccountService {

	public static final String ACCOUNT = "account";
	public static final String CASH_FLOW = "cash_flow";
	static final Set<String> OWNER_KINDS = Set.of("FACTION", "ESTATE", "CHARACTER", "OTHER");

	private final Database db;

	public AccountService(Database db) {
		this.db = db;
	}

	// ── create_account ─────────────────────────────────────────────────

	public Map<String, Object> create(
			String operationId, String campaignRef, String name, String ownerKind, String ownerRef, Object money,
			String notes) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("name", name);
		args.put("owner_kind", ownerKind);
		args.put("owner", ownerRef);
		args.put("money", money);
		args.put("notes", notes);
		return db.mutate(Database.Mutation.of("create_account", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "create_account");
			if (name == null || name.isBlank()) {
				throw RpgException.invalidArgument("An account name is required (e.g. 'The House of Greystone').");
			}
			String trimmed = name.trim();
			if (tx.queryOne("SELECT id FROM account WHERE campaign_id = ? AND name = ?", campaignId, trimmed)
					.isPresent()) {
				throw RpgException.conflict("An account named '" + trimmed + "' already exists in this campaign.");
			}
			String kind = ownerKind == null || ownerKind.isBlank() ? "OTHER" : ownerKind.trim().toUpperCase();
			if (!OWNER_KINDS.contains(kind)) {
				throw RpgException.invalidArgument("owner_kind must be one of " + OWNER_KINDS + ".");
			}
			Long ownerId = ownerId(tx, campaignId, kind, ownerRef);
			long cp = money == null ? 0 : Money.parseCp(money);
			var cols = new LinkedHashMap<String, Object>();
			cols.put("campaign_id", campaignId);
			cols.put("name", trimmed);
			cols.put("owner_kind", kind);
			cols.put("owner_id", ownerId);
			cols.put("money_cp", 0L);
			cols.put("notes", notes);
			cols.put("revision", 0L);
			long id = tx.insert(ACCOUNT, cols);
			MoneyRef ref = new MoneyRef(MoneyRef.Kind.ACCOUNT, id);
			Long eventId = null;
			if (cp > 0) {
				ref.adjust(tx, campaignId, cp);
				eventId = moneyFlowEvent(tx, campaignId, MoneyRef.WORLD, ref, cp,
						"Opening balance of " + trimmed + ": " + Money.format(cp) + ".", "MINOR", null, null,
						Map.of("reason", "opening balance"));
			} else {
				eventId = LedgerService.append(tx, campaignId,
						new LedgerService.EventSpec("ACCOUNT_CREATED", "Account opened: " + trimmed + ".", null,
								"MINOR", "PARTY_KNOWN", "GM", null, null, null,
								Map.of("account", ref.toString(), "owner_kind", kind)));
			}
			var result = new LinkedHashMap<String, Object>(view(tx, tx.get(ACCOUNT, id)));
			result.put("event", Ref.of(Ref.EVENT, eventId));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── transfer_money ─────────────────────────────────────────────────

	public Map<String, Object> transfer(
			String operationId, String campaignRef, Object from, Object to, Object money, String reason) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		var args = new LinkedHashMap<String, Object>();
		args.put("campaign", campaignRef);
		args.put("from", from);
		args.put("to", to);
		args.put("money", money);
		args.put("reason", reason);
		return db.mutate(Database.Mutation.of("transfer_money", campaignId, operationId, "GM", args), tx -> {
			Row campaign = Harness.requireMutation(tx, campaignRef, "transfer_money");
			MoneyRef source = MoneyRef.resolve(tx, campaignId, from);
			MoneyRef target = MoneyRef.resolve(tx, campaignId, to);
			if (source.isWorld() && target.isWorld()) {
				throw RpgException.invalidArgument("At least one side must be an account or a character.");
			}
			if (source.equals(target)) {
				throw RpgException.invalidArgument("from and to are the same money bag.");
			}
			long cp = Money.parseCp(money);
			if (cp <= 0) {
				throw RpgException.invalidArgument("The amount must be positive.");
			}
			String sourceName = source.name(tx, campaignId);
			String targetName = target.name(tx, campaignId);
			source.adjust(tx, campaignId, -cp);
			target.adjust(tx, campaignId, cp);
			String summary = Money.format(cp) + (source.isWorld() ? " to " + targetName
					: target.isWorld() ? " paid out of " + sourceName
							: " from " + sourceName + " to " + targetName) + (reason == null || reason.isBlank() ? "."
					: ": " + reason);
			long eventId = moneyFlowEvent(tx, campaignId, source, target, cp, summary,
					cp >= 10 * Money.GP ? "NOTABLE" : "MINOR", null, null, Map.of("reason", reason == null ? "" : reason));
			var result = new LinkedHashMap<String, Object>();
			result.put("from", source.toString());
			result.put("to", target.toString());
			result.put("money", Money.render(cp));
			result.put("balances", balances(tx, campaignId, List.of(source, target)));
			result.put("event", Ref.of(Ref.EVENT, eventId));
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── get_accounts ───────────────────────────────────────────────────

	public Map<String, Object> accounts(String campaignRef, Integer lastRuns) {
		long campaignId = Ref.id(campaignRef, Ref.CAMPAIGN);
		int runs = lastRuns == null ? 10 : Math.max(0, Math.min(100, lastRuns));
		return db.read(tx -> {
			Row campaign = Harness.campaign(tx, campaignRef);
			Calendar cal = Calendar.forCampaign(campaign);
			long now = GameTime.currentSeq(tx, campaignId);
			var result = new LinkedHashMap<String, Object>();
			result.put("game_time", GameTime.toMap(now, cal));
			result.put("calendar", calendarView(campaign, cal));
			var accounts = new ArrayList<Map<String, Object>>();
			for (Row a : tx.query("SELECT * FROM account WHERE campaign_id = ? ORDER BY id", campaignId)) {
				var v = new LinkedHashMap<String, Object>(view(tx, a));
				MoneyRef ref = new MoneyRef(MoneyRef.Kind.ACCOUNT, a.id());
				v.put("upcoming", upcoming(tx, campaignId, cal, ref.toString(), 5));
				accounts.add(v);
			}
			result.put("accounts", accounts);
			var purses = new ArrayList<Map<String, Object>>();
			for (Row c : tx.query(
					"SELECT c.* FROM party_membership m JOIN character c ON c.id = m.character_id WHERE m.campaign_id = ? " + "AND m.state IN ('ACTIVE','GUEST') AND c.lifecycle = 'ACTIVE' ORDER BY m.id",
					campaignId)) {
				var p = new LinkedHashMap<String, Object>();
				p.put("character", Ref.of(Ref.CHARACTER, c.id()));
				p.put("name", c.str("name"));
				p.put("money", Money.render(c.lng("money_cp")));
				p.put("upcoming", upcoming(tx, campaignId, cal, Ref.of(Ref.CHARACTER, c.id()), 5));
				purses.add(p);
			}
			result.put("purses", purses);
			var recent = new ArrayList<Map<String, Object>>();
			for (Row r : tx.query(
					"SELECT r.*, f.name AS flow_name, f.kind AS flow_kind FROM cash_flow_run r JOIN cash_flow f ON f.id = r.cash_flow_id " + "WHERE f.campaign_id = ? ORDER BY r.due_seq DESC, r.id DESC LIMIT ?",
					campaignId, runs)) {
				recent.add(runView(r, cal));
			}
			result.put("recent_runs", recent);
			result.put("meta", Harness.meta(campaign, null));
			return result;
		});
	}

	// ── Helpers shared with the cash-flow code ─────────────────────────

	public static Map<String, Object> calendarView(Row campaign, Calendar cal) {
		var m = new LinkedHashMap<String, Object>();
		m.put("set", campaign.has("calendar_json") && campaign.str("calendar_json") != null);
		m.put("epoch", cal.epoch());
		m.put("day_1", cal.dateOf(0).get("display"));
		m.put("note", "365-day year, 12 months, 7-day weeks (Monday = 1); seasons SPRING Mar–May, SUMMER Jun–Aug, "
				+ "AUTUMN Sep–Nov, WINTER Dec–Feb. 'Day N' stays the primary clock; the date is derived.");
		return m;
	}

	static Long ownerId(Tx tx, long campaignId, String kind, String ownerRef) {
		if (ownerRef == null || ownerRef.isBlank()) {
			return null;
		}
		String table = switch (kind) {
			case "FACTION" -> "faction";
			case "CHARACTER" -> "character";
			case "ESTATE" -> "location";
			default -> null;
		};
		if (table == null) {
			throw RpgException.invalidArgument("owner is only meaningful for FACTION, CHARACTER or ESTATE accounts.");
		}
		long id = Ref.id(ownerRef, table);
		Row row = tx.find(table, id).orElseThrow(() -> RpgException.notFound(ownerRef));
		if (row.lng("campaign_id") != campaignId) {
			throw RpgException.invalidArgument(ownerRef + " belongs to another campaign.");
		}
		return id;
	}

	public static Map<String, Object> view(Tx tx, Row a) {
		var m = new LinkedHashMap<String, Object>();
		m.put("account", Ref.of(ACCOUNT, a.id()));
		m.put("name", a.str("name"));
		m.put("owner_kind", a.str("owner_kind"));
		String ownerTable = switch (a.str("owner_kind")) {
			case "FACTION" -> "faction";
			case "CHARACTER" -> "character";
			case "ESTATE" -> "location";
			default -> null;
		};
		Long ownerId = a.lng("owner_id");
		m.put("owner", ownerTable == null || ownerId == null ? null : Ref.of(ownerTable, ownerId));
		if (ownerTable != null && ownerId != null) {
			tx.find(ownerTable, ownerId).ifPresent(o -> m.put("owner_name", o.str("name")));
		}
		m.put("money", Money.render(a.lng("money_cp")));
		m.put("notes", a.str("notes"));
		m.put("revision", a.lng("revision"));
		return m;
	}

	static List<Map<String, Object>> upcoming(Tx tx, long campaignId, Calendar cal, String ref, int limit) {
		var out = new ArrayList<Map<String, Object>>();
		for (Row f : tx.query(
				"SELECT * FROM cash_flow WHERE campaign_id = ? AND active = 1 AND next_due_seq IS NOT NULL AND (from_ref = ? OR to_ref = ?) " + "ORDER BY next_due_seq, id LIMIT ?",
				campaignId, ref, ref, limit)) {
			var m = new LinkedHashMap<String, Object>();
			m.put("cash_flow", Ref.of(CASH_FLOW, f.id()));
			m.put("name", f.str("name"));
			m.put("direction", ref.equals(f.str("to_ref")) ? "IN" : "OUT");
			m.put("amount", CashFlowService.describeAmount(tx, campaignId, f));
			m.put("due", cal.render(f.lng("next_due_seq")));
			out.add(m);
		}
		return out;
	}

	static Map<String, Object> balances(Tx tx, long campaignId, List<MoneyRef> refs) {
		var m = new LinkedHashMap<String, Object>();
		for (MoneyRef r : refs) {
			if (!r.isWorld()) {
				m.put(r.toString(), Money.render(r.balance(tx, campaignId)));
			}
		}
		return m;
	}

	static Map<String, Object> runView(Row r, Calendar cal) {
		var m = new LinkedHashMap<String, Object>();
		m.put("run", r.lng("id"));
		m.put("cash_flow", Ref.of(CASH_FLOW, r.lng("cash_flow_id")));
		if (r.has("flow_name")) {
			m.put("name", r.str("flow_name"));
		}
		m.put("due", cal.render(r.lng("due_seq")));
		m.put("status", r.str("status"));
		m.put("amount", Money.render(r.lng("amount_cp")));
		m.put("event", Ref.ofNullable(Ref.EVENT, r.lng("event_id")));
		m.put("note", r.str("note"));
		return m;
	}

	/** Writes the {@code MONEY_FLOW} ledger event for a movement of coin; characters involved become actors. */
	static long moneyFlowEvent(
			Tx tx, long campaignId, MoneyRef from, MoneyRef to, long cp, String summary, String importance,
			Long fictionalSeq, Long locationId, Map<String, Object> extraPayload) {
		var payload = new LinkedHashMap<String, Object>();
		payload.put("from", from.toString());
		payload.put("to", to.toString());
		payload.put("money_cp", cp);
		if (extraPayload != null) {
			payload.putAll(extraPayload);
		}
		var actors = new ArrayList<Long>();
		if (from.kind() == MoneyRef.Kind.CHARACTER) {
			actors.add(from.id());
		}
		if (to.kind() == MoneyRef.Kind.CHARACTER) {
			actors.add(to.id());
		}
		return LedgerService.append(tx, campaignId,
				new LedgerService.EventSpec("MONEY_FLOW", summary, actors, importance, "PARTY_KNOWN", "GM",
						fictionalSeq, locationId, null, payload));
	}
}
