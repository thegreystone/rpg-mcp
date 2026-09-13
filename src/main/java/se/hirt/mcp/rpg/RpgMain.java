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

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point. MCP over STDIO is a UTF-8 protocol, but a piped {@code System.out} defaults to the
 * platform encoding on Windows (cp1252), which mangles every non-ASCII character in tool
 * descriptions and campaign text. The STDIO transport captures {@code System.out} during runtime
 * init, so it has to be rewrapped before Quarkus boots. Stdin is fine: the default charset has been
 * UTF-8 since JDK 18.
 * <p>
 * Desktop MCP hosts on Windows (Claude Desktop among them) may spawn the server with
 * {@code C:\WINDOWS\system32} as the working directory. Quarkus lists {@code ${user.dir}/config}
 * during boot to warn about stray config files, and {@code system32\config} is the registry hive
 * directory: it exists but cannot be listed, so boot fails with {@code AccessDeniedException}. If
 * the working directory's {@code config} entry cannot be listed, {@code user.dir} is redirected to
 * the data directory before Quarkus starts. Nothing in the server resolves paths relative to the
 * working directory, so this is invisible otherwise.
 */
@QuarkusMain
public class RpgMain {

	public static void main(String ... args) {
		System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
		ensureScannableWorkingDirectory();
		Quarkus.run(args);
	}

	private static void ensureScannableWorkingDirectory() {
		String userDir = System.getProperty("user.dir");
		if (userDir == null || canList(Paths.get(userDir, "config"))) {
			return;
		}
		Path dataDir = dataDir();
		try {
			Files.createDirectories(dataDir);
		} catch (IOException e) {
			return; // Quarkus will report the real problem
		}
		System.setProperty("user.dir", dataDir.toAbsolutePath().toString());
	}

	/**
	 * True when the path is absent, or is a directory that can be listed. Mirrors what Quarkus does
	 * at boot.
	 */
	private static boolean canList(Path dir) {
		if (!Files.exists(dir)) {
			return true;
		}
		if (!Files.isDirectory(dir)) {
			return true;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			return true;
		} catch (IOException | SecurityException e) {
			return false;
		}
	}

	/**
	 * Same resolution order as {@code rpg.data-dir}: system property, {@code RPG_DATA_DIR}, then
	 * {@code ~/.rpg-mcp}.
	 */
	private static Path dataDir() {
		String configured = System.getProperty("rpg.data-dir");
		if (configured == null || configured.isBlank()) {
			configured = System.getenv("RPG_DATA_DIR");
		}
		if (configured == null || configured.isBlank()) {
			configured = System.getProperty("user.home") + "/.rpg-mcp";
		}
		return Paths.get(RpgConfig.expandHome(configured));
	}
}
