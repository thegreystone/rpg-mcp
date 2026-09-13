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
package se.hirt.mcp.rpg.tools;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Command-line entry point for the srd5e seed tooling. The SRD 5.2.1 PDF is published by Wizards of
 * the Coast under CC-BY-4.0 and downloads from
 * {@code https://media.dndbeyond.com/compendium-images/srd/5.2/SRD_CC_v5.2.1.pdf}.
 *
 * <pre>
 * extract     &lt;pdf&gt;        write srd.txt next to the PDF
 * verify      &lt;pdf|txt&gt;    diff every seed file against the SRD text, write srd_report.txt in the working directory
 * build-rules &lt;pdf|txt&gt;    regenerate seed/srd5e/rules.json (the Rules Glossary, verbatim)
 * </pre>
 */
public final class SrdTool {

	private SrdTool() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			usage();
			return;
		}
		Path src = Paths.get(args[1]);
		switch (args[0]) {
		case "extract" -> System.out.println("wrote " + SrdText.extract(src));
		case "verify" -> new VerifySrd(SrdText.load(src)).run(seedDir(), Paths.get("srd_report.txt"));
		case "build-rules" -> new BuildRules(SrdText.readRaw(src)).run(seedDir().resolve("rules.json"));
		case "build-creatures" -> new BuildCreatures(SrdText.load(src)).run(seedDir().resolve("creatures.json"));
		default -> usage();
		}
	}

	/**
	 * {@code src/main/resources/seed/srd5e}, located relative to this project's directory, not the
	 * working directory.
	 */
	static Path seedDir() {
		Path here = Paths.get(System.getProperty("rpg.tools.dir", System.getProperty("user.dir"))).toAbsolutePath();
		// Invoked either from the repository root (mvn -f tools/pom.xml) or from tools/ itself.
		Path root = here.getFileName().toString().equals("tools") ? here.getParent() : here;
		return root.resolve("src/main/resources/seed/srd5e");
	}

	private static void usage() {
		System.err.println(
				"usage: SrdTool extract <pdf> | verify <pdf|srd.txt> | build-rules <pdf|srd.txt> | build-creatures <pdf|srd.txt>");
		System.exit(2);
	}
}
