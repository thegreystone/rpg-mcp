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
package se.hirt.mcp.rpg.persistence;

import se.hirt.mcp.rpg.protocol.Json;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A database row as an ordered column→value map with typed accessors. SQLite values arrive as
 * {@link Long}, {@link Double}, {@link String}, {@code byte[]} or {@code null}.
 */
public final class Row {

	private final Map<String, Object> values;

	public Row(Map<String, Object> values) {
		this.values = new LinkedHashMap<>(values);
	}

	static Row from(ResultSet rs) throws SQLException {
		ResultSetMetaData md = rs.getMetaData();
		var m = new LinkedHashMap<String, Object>();
		for (int i = 1; i <= md.getColumnCount(); i++) {
			Object v = rs.getObject(i);
			if (v instanceof Integer n) {
				v = n.longValue();
			}
			m.put(md.getColumnLabel(i), v);
		}
		return new Row(m);
	}

	public Object get(String column) {
		return values.get(column);
	}

	public boolean has(String column) {
		return values.containsKey(column);
	}

	public boolean isNull(String column) {
		return values.get(column) == null;
	}

	public String str(String column) {
		Object v = values.get(column);
		return v == null ? null : v.toString();
	}

	public Long lng(String column) {
		Object v = values.get(column);
		if (v == null) {
			return null;
		}
		if (v instanceof Number n) {
			return n.longValue();
		}
		return Long.parseLong(v.toString());
	}

	public long id() {
		return lng("id");
	}

	public Integer integer(String column) {
		Long v = lng(column);
		return v == null ? null : v.intValue();
	}

	public int intOr(String column, int fallback) {
		Long v = lng(column);
		return v == null ? fallback : v.intValue();
	}

	public boolean bool(String column) {
		Long v = lng(column);
		return v != null && v != 0;
	}

	public Map<String, Object> map(String column) {
		return Json.readMap(str(column));
	}

	public List<Object> list(String column) {
		return Json.readList(str(column));
	}

	public Map<String, Object> asMap() {
		return new LinkedHashMap<>(values);
	}

	@Override
	public String toString() {
		return values.toString();
	}
}
