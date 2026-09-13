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
import se.hirt.mcp.rpg.protocol.Json;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The campaign calendar (DOMAIN_MODEL.md §13, DATABASE.md §3.11): a 365-day year of twelve months
 * with the familiar lengths and no leap years, seven-day weeks (Monday = 1; 1 January of year 1 is
 * a Monday), and four seasons derived from the month (spring March–May, summer June–August, autumn
 * September–November, winter December–February).
 * <p>
 * The clock stays what it is: {@code seq} is minutes since campaign start and "Day N" is the
 * primary display. The calendar only maps Day 1 onto a date, the epoch stored in
 * {@code campaign.calendar_json} as {@code {"year": 1, "month": 3, "day": 1}}. A campaign without a
 * calendar uses {@link #DEFAULT}: Day 1 is 1 March of year 1, a spring morning.
 */
public final class Calendar {

	public static final List<String> MONTHS = List.of("January", "February", "March", "April", "May", "June", "July",
			"August", "September", "October", "November", "December");
	public static final List<String> WEEKDAYS = List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
			"Saturday", "Sunday");
	public static final List<String> SEASONS = List.of("SPRING", "SUMMER", "AUTUMN", "WINTER");
	public static final int DAYS_PER_YEAR = 365;
	private static final int[] DAYS_IN_MONTH = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

	/** Day 1 = 1 March, year 1. */
	public static final Calendar DEFAULT = new Calendar(1, 3, 1);

	private final int epochYear;
	private final int epochMonth;
	private final int epochDay;
	/** Absolute day number (days since 1 January of year 1) of campaign Day 1. */
	private final long epochAbsoluteDay;

	public Calendar(int year, int month, int day) {
		validate(year, month, day);
		this.epochYear = year;
		this.epochMonth = month;
		this.epochDay = day;
		this.epochAbsoluteDay = absoluteDay(year, month, day);
	}

	public static Calendar of(Map<String, Object> json) {
		if (json == null || json.isEmpty()) {
			return DEFAULT;
		}
		return new Calendar(intOf(json, "year", 1), intOf(json, "month", 3), intOf(json, "day", 1));
	}

	public static Calendar fromJson(String json) {
		return json == null || json.isBlank() ? DEFAULT : of(Json.readMap(json));
	}

	/** The calendar of a campaign: its stored epoch, or {@link #DEFAULT} when none has been set. */
	public static Calendar forCampaign(Tx tx, long campaignId) {
		return tx.find("campaign", campaignId).map(Calendar::forCampaign).orElse(DEFAULT);
	}

	public static Calendar forCampaign(Row campaign) {
		return campaign.has("calendar_json") ? fromJson(campaign.str("calendar_json")) : DEFAULT;
	}

	public boolean isDefault() {
		return epochYear == DEFAULT.epochYear && epochMonth == DEFAULT.epochMonth && epochDay == DEFAULT.epochDay;
	}

	public Map<String, Object> epoch() {
		var m = new LinkedHashMap<String, Object>();
		m.put("year", epochYear);
		m.put("month", epochMonth);
		m.put("day", epochDay);
		return m;
	}

	public String epochJson() {
		return Json.write(epoch());
	}

	// ── Date math ──────────────────────────────────────────────────────

	public static int daysInMonth(int month) {
		return DAYS_IN_MONTH[month - 1];
	}

	public static String season(int month) {
		return switch (month) {
		case 3, 4, 5 -> "SPRING";
		case 6, 7, 8 -> "SUMMER";
		case 9, 10, 11 -> "AUTUMN";
		default -> "WINTER";
		};
	}

	/**
	 * The month a season opens in: spring March, summer June, autumn September, winter December.
	 */
	public static int seasonStartMonth(String season) {
		return switch (normalizeSeason(season)) {
		case "SPRING" -> 3;
		case "SUMMER" -> 6;
		case "AUTUMN" -> 9;
		default -> 12;
		};
	}

	public static String normalizeSeason(String season) {
		if (season == null) {
			throw RpgException.invalidArgument("A season is required: one of " + SEASONS + ".");
		}
		String s = season.trim().toUpperCase();
		if (s.equals("FALL")) {
			s = "AUTUMN";
		}
		if (!SEASONS.contains(s)) {
			throw RpgException.invalidArgument("Unknown season '" + season + "'; use one of " + SEASONS + ".");
		}
		return s;
	}

	public static int dayOfYear(int month, int day) {
		int n = day;
		for (int m = 1; m < month; m++) {
			n += DAYS_IN_MONTH[m - 1];
		}
		return n;
	}

	/** Days since 1 January of year 1 (that day is 0). */
	public static long absoluteDay(int year, int month, int day) {
		return (long) (year - 1) * DAYS_PER_YEAR + dayOfYear(month, day) - 1;
	}

	/** Weekday 1–7 (Monday = 1) of an absolute day; 1 January of year 1 is a Monday. */
	public static int weekdayOf(long absoluteDay) {
		return (int) Math.floorMod(absoluteDay, 7) + 1;
	}

	/** The absolute day of a clock value (campaign Day 1 is day 0 of the campaign). */
	public long absoluteDayOf(long seq) {
		return epochAbsoluteDay + Math.floorDiv(seq, GameTime.MINUTES_PER_DAY);
	}

	/** The date of a clock value. */
	public Map<String, Object> dateOf(long seq) {
		return dateOfAbsoluteDay(absoluteDayOf(seq));
	}

	public Map<String, Object> dateOfAbsoluteDay(long absoluteDay) {
		long year = Math.floorDiv(absoluteDay, DAYS_PER_YEAR) + 1;
		int dayOfYear = (int) Math.floorMod(absoluteDay, DAYS_PER_YEAR) + 1;
		int month = 1;
		int remaining = dayOfYear;
		while (remaining > DAYS_IN_MONTH[month - 1]) {
			remaining -= DAYS_IN_MONTH[month - 1];
			month++;
		}
		int weekday = weekdayOf(absoluteDay);
		var m = new LinkedHashMap<String, Object>();
		m.put("year", year);
		m.put("month", month);
		m.put("day", remaining);
		m.put("month_name", MONTHS.get(month - 1));
		m.put("weekday", weekday);
		m.put("weekday_name", WEEKDAYS.get(weekday - 1));
		m.put("season", season(month));
		m.put("day_of_year", dayOfYear);
		m.put("display", WEEKDAYS.get(weekday - 1) + " " + remaining + " " + MONTHS.get(month - 1) + ", year " + year);
		return m;
	}

	/**
	 * The clock value of a date at a minute of the day (0–1439). Dates before Day 1 give negative
	 * values.
	 */
	public long seqOf(int year, int month, int day, int minuteOfDay) {
		validate(year, month, day);
		if (minuteOfDay < 0 || minuteOfDay >= GameTime.MINUTES_PER_DAY) {
			throw RpgException.invalidArgument("minute_of_day must be 0–1439.");
		}
		return (absoluteDay(year, month, day) - epochAbsoluteDay) * GameTime.MINUTES_PER_DAY + minuteOfDay;
	}

	/**
	 * Parses {@code {"year", "month", "day", "minute_of_day"?}} or {@code "Day N, HH:MM"} to a
	 * clock value.
	 */
	public long seqOf(Object when) {
		if (when instanceof Map<?, ?> m) {
			@SuppressWarnings("unchecked")
			Map<String, Object> map = (Map<String, Object>) m;
			int year = intOf(map, "year", epochYear);
			int month = intOf(map, "month", -1);
			int day = intOf(map, "day", -1);
			if (month < 0 || day < 0) {
				throw RpgException.invalidArgument("A date needs month and day (and optionally year, minute_of_day).");
			}
			int minute = intOf(map, "minute_of_day", -1);
			if (minute < 0 && map.get("hour") instanceof Number h) {
				minute = h.intValue() * 60;
			}
			return seqOf(year, month, day, Math.max(0, minute));
		}
		if (when instanceof Number n) {
			return n.longValue();
		}
		return GameTime.parse(String.valueOf(when));
	}

	/** {@code "Day N, HH:MM (Tuesday 3 March, year 1, spring)"}. */
	public String render(long seq) {
		Map<String, Object> d = dateOf(seq);
		return GameTime.render(seq) + " (" + d.get("display") + ", " + String.valueOf(d.get("season")).toLowerCase()
				+ ")";
	}

	private static void validate(int year, int month, int day) {
		if (year < 1) {
			throw RpgException.invalidArgument("year must be 1 or later.");
		}
		if (month < 1 || month > 12) {
			throw RpgException.invalidArgument("month must be 1–12.");
		}
		if (day < 1 || day > daysInMonth(month)) {
			throw RpgException
					.invalidArgument("day must be 1–" + daysInMonth(month) + " for " + MONTHS.get(month - 1) + ".");
		}
	}

	private static int intOf(Map<String, Object> m, String key, int fallback) {
		Object v = m.get(key);
		if (v == null) {
			return fallback;
		}
		if (v instanceof Number n) {
			return n.intValue();
		}
		try {
			return Integer.parseInt(v.toString().trim());
		} catch (NumberFormatException e) {
			throw RpgException.invalidArgument(key + " must be a whole number; got '" + v + "'.");
		}
	}
}
