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
package se.hirt.mcp.rpg.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * JSON helpers. All protocol payloads are plain maps/lists so that no reflection registration is
 * needed in a native image.
 */
public final class Json {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ObjectMapper CANONICAL = new ObjectMapper()
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};
	private static final TypeReference<List<Object>> LIST = new TypeReference<>() {
	};

	private Json() {
	}

	public static String write(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw RpgException.internal("Failed to serialize JSON", e);
		}
	}

	public static String writeOrNull(Object value) {
		return value == null ? null : write(value);
	}

	public static Map<String, Object> readMap(String json) {
		if (json == null || json.isBlank()) {
			return new java.util.LinkedHashMap<>();
		}
		try {
			return MAPPER.readValue(json, MAP);
		} catch (JsonProcessingException e) {
			throw RpgException.internal("Failed to parse JSON object", e);
		}
	}

	public static List<Object> readList(String json) {
		if (json == null || json.isBlank()) {
			return new java.util.ArrayList<>();
		}
		try {
			return MAPPER.readValue(json, LIST);
		} catch (JsonProcessingException e) {
			throw RpgException.internal("Failed to parse JSON array", e);
		}
	}

	/**
	 * SHA-256 over the canonical (key-sorted) JSON encoding, used for idempotency comparisons.
	 */
	public static String hash(Object value) {
		try {
			byte[] bytes = CANONICAL.writeValueAsBytes(value);
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		} catch (JsonProcessingException | NoSuchAlgorithmException e) {
			throw RpgException.internal("Failed to hash arguments", e);
		}
	}

	public static String hashBytes(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw RpgException.internal("SHA-256 unavailable", e);
		}
	}

	public static byte[] utf8(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}
}
