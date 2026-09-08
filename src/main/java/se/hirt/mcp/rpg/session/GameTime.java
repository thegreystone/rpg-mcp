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
package se.hirt.mcp.rpg.session;

import se.hirt.mcp.rpg.persistence.Row;
import se.hirt.mcp.rpg.persistence.Tx;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The campaign clock (DOMAIN_MODEL.md §13). The MVP calendar is a simple day clock: {@code seq} is minutes since
 * campaign start and {@code instant} renders as {@code "Day N, HH:MM"}. Ordering always uses {@code seq}; the instant
 * is presentation (I-42).
 */
public final class GameTime {

	public static final String CALENDAR = "campaign:default";
	public static final int MINUTES_PER_DAY = 24 * 60;

	private GameTime() {
	}

	public static String render(long seq) {
		long day = seq / MINUTES_PER_DAY + 1;
		long minuteOfDay = seq % MINUTES_PER_DAY;
		return String.format("Day %d, %02d:%02d", day, minuteOfDay / 60, minuteOfDay % 60);
	}

	/** Parses {@code "Day N, HH:MM"} back to a sequence value. */
	public static long parse(String instant) {
		try {
			String s = instant.trim();
			int comma = s.indexOf(',');
			long day = Long.parseLong(s.substring(3, comma).trim());
			String[] hm = s.substring(comma + 1).trim().split(":");
			return (day - 1) * MINUTES_PER_DAY + Long.parseLong(hm[0]) * 60 + Long.parseLong(hm[1]);
		} catch (RuntimeException e) {
			throw RpgException.invalidArgument("Game time must look like 'Day 3, 14:30'; got '" + instant + "'.");
		}
	}

	public static Map<String, Object> toMap(long seq) {
		var m = new LinkedHashMap<String, Object>();
		m.put("calendar", CALENDAR);
		m.put("instant", render(seq));
		m.put("sequence", seq);
		return m;
	}

	/** As {@link #toMap(long)} plus the calendar date (year, month, day, weekday, season) of the campaign. */
	public static Map<String, Object> toMap(long seq, Calendar calendar) {
		var m = toMap(seq);
		Calendar cal = calendar == null ? Calendar.DEFAULT : calendar;
		m.put("date", cal.dateOf(seq));
		m.put("display", cal.render(seq));
		return m;
	}

	/** As {@link #toMap(long)} plus the date under the campaign's calendar (or the default one). */
	public static Map<String, Object> toMap(Tx tx, long campaignId, long seq) {
		return toMap(seq, Calendar.forCampaign(tx, campaignId));
	}

	public static Row clock(Tx tx, long campaignId) {
		return tx.queryOne("SELECT * FROM game_clock WHERE campaign_id = ?", campaignId)
				.orElseThrow(() -> RpgException.notFound("Game clock for campaign " + campaignId));
	}

	public static long currentSeq(Tx tx, long campaignId) {
		return clock(tx, campaignId).lng("seq");
	}
}
