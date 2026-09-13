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
package se.hirt.mcp.rpg;

import io.smallrye.config.ConfigMapping;

import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * Server configuration ({@code rpg.*}). Everything else is campaign state in the database.
 */
@ConfigMapping(prefix = "rpg")
public interface RpgConfig {

	/**
	 * Directory holding {@code rpg.db}, as configured. Use {@link #dataPath()} for the resolved
	 * path.
	 */
	String dataDir();

	/** {@link #dataDir()} with a leading {@code ~} expanded to the user's home directory. */
	default Path dataPath() {
		return Path.of(expandHome(dataDir()));
	}

	/**
	 * Expands a leading {@code ~} ({@code ~}, {@code ~/...} or {@code ~\...}) to {@code user.home}.
	 * Hosts that launch the server with a portable default such as {@code ~/.rpg-mcp} (the MCP
	 * Bundle manifest does) cannot be relied on to expand it themselves, and a
	 * {@code ${HOME}}-style placeholder would fail in the config layer on Windows, where no
	 * {@code HOME} variable exists.
	 */
	static String expandHome(String path) {
		if (path == null) {
			return null;
		}
		String home = System.getProperty("user.home");
		if (path.equals("~")) {
			return home;
		}
		if (path.startsWith("~/") || path.startsWith("~\\")) {
			return home + path.substring(1);
		}
		return path;
	}

	/**
	 * Optional fixed seed for the roller — for reproducible local testing only; never set in real
	 * play.
	 */
	OptionalLong rollSeed();
}
