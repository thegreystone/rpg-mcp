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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured protocol failure. Rendered as the {@code error} object of MCP_PROTOCOL.md §6.
 */
public class RpgException extends RuntimeException {

	private final ErrorCode code;
	private final boolean retryable;
	private final Map<String, Object> details;

	public RpgException(ErrorCode code, String message, boolean retryable, Map<String, Object> details) {
		super(message);
		this.code = code;
		this.retryable = retryable;
		this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
	}

	public RpgException(ErrorCode code, String message) {
		this(code, message, false, null);
	}

	public ErrorCode code() {
		return code;
	}

	public boolean retryable() {
		return retryable;
	}

	public Map<String, Object> details() {
		return details;
	}

	public RpgException withDetail(String key, Object value) {
		details.put(key, value);
		return this;
	}

	public Map<String, Object> toErrorMap() {
		var error = new LinkedHashMap<String, Object>();
		error.put("code", code.name());
		error.put("message", getMessage());
		error.put("retryable", retryable);
		error.put("details", details);
		return Map.of("error", error);
	}

	// ── factories ──────────────────────────────────────────────────────

	public static RpgException invalidArgument(String message) {
		return new RpgException(ErrorCode.INVALID_ARGUMENT, message);
	}

	public static RpgException notFound(String what) {
		return new RpgException(ErrorCode.NOT_FOUND, what + " was not found.");
	}

	public static RpgException notAllowed(String message) {
		return new RpgException(ErrorCode.OPERATION_NOT_ALLOWED, message);
	}

	public static RpgException validation(List<Violation> violations) {
		var e = new RpgException(ErrorCode.VALIDATION_FAILED, violations.size() == 1 ? violations.get(0).message()
				: "Validation failed with " + violations.size() + " issues.");
		e.details.put("violations", violations.stream().map(Violation::toMap).toList());
		return e;
	}

	public static RpgException conflict(String message) {
		return new RpgException(ErrorCode.CONFLICT, message, true, null);
	}

	public static RpgException policyDenied(String message) {
		return new RpgException(ErrorCode.POLICY_DENIED, message);
	}

	public static RpgException capabilityUnavailable(String message) {
		return new RpgException(ErrorCode.CAPABILITY_UNAVAILABLE, message);
	}

	public static RpgException idempotencyConflict(String operationId) {
		return new RpgException(ErrorCode.IDEMPOTENCY_CONFLICT,
				"operation_id '" + operationId + "' was already used with different arguments.");
	}

	public static RpgException insufficientResource(String message) {
		return new RpgException(ErrorCode.INSUFFICIENT_RESOURCE, message);
	}

	public static RpgException internal(String message, Throwable cause) {
		var e = new RpgException(ErrorCode.INTERNAL_ERROR, message, false, null);
		e.initCause(cause);
		return e;
	}
}
