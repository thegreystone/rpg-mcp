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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SRD 5.2.1 as one text file: pages separated by {@code === PAGE n ===} markers, each page
 * starting with the running header ("System Reference Document 5.2.1" and the page number). The
 * marker format is the interchange contract between extraction and the parsers, so an extracted
 * {@code srd.txt} can be inspected and reused.
 */
public final class SrdText {
	public static final Pattern PAGE_MARKER = Pattern.compile("=== PAGE (\\d+) ===");

	// Written as code points: invisible characters do not survive every editor, and these must be exact.
	private static final String NBSP = Character.toString(0x00A0);
	private static final String SOFT_HYPHEN = Character.toString(0x00AD);
	private static final String ZWSP = Character.toString(0x200B);
	private static final String RIGHT_QUOTE = Character.toString(0x2019);
	private static final String MINUS = Character.toString(0x2212);
	private static final String HALF = Character.toString(0x00BD);

	// Python's re module is Unicode-aware for \s and \w; Java's is not unless asked.
	private static final Pattern WS = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern HYPHENATION = Pattern.compile("(\\w) ?- (?!\\d)(\\w)",
			Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern NOT_NAME = Pattern.compile("[^a-z0-9 /+]");

	private SrdText() {
	}

	/** Extracts the PDF to {@code srd.txt} next to it and returns that path. */
	public static Path extract(Path pdf) throws IOException {
		Path out = pdf.toAbsolutePath().getParent().resolve("srd.txt");
		StringBuilder sb = new StringBuilder(2_000_000);
		try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(Boolean.getBoolean("srd.sort"));
			stripper.setLineSeparator("\n");
			stripper.setParagraphEnd("");
			stripper.setPageEnd("");
			stripper.setArticleStart("");
			stripper.setArticleEnd("");
			int pages = doc.getNumberOfPages();
			for (int i = 1; i <= pages; i++) {
				stripper.setStartPage(i);
				stripper.setEndPage(i);
				sb.append("\n=== PAGE ").append(i).append(" ===\n").append(cleanPage(stripper.getText(doc)));
			}
		}
		Files.writeString(out, sb, StandardCharsets.UTF_8);
		return out;
	}

	private static final Pattern HEADER_FIRST = Pattern.compile("^(System Reference Document 5\\.2\\.1)(\\d+)$",
			Pattern.MULTILINE);
	private static final Pattern HEADER_LAST = Pattern.compile("^(\\d+) (System Reference Document 5\\.2\\.1)$",
			Pattern.MULTILINE);

	/**
	 * PDFBox emits tabs and no-break spaces where the PDF has positioned gaps, and joins the
	 * running header ("System Reference Document 5.2.1" and the page number) onto one line. The
	 * parsers expect plain spaces and the header as two lines directly after the page marker, in
	 * whichever order the page prints them.
	 */
	private static String cleanPage(String page) {
		page = page.replace("\t", " ").replace(NBSP, " ").replace(ZWSP, "");
		page = HEADER_FIRST.matcher(page).replaceFirst("$1\n$2");
		page = HEADER_LAST.matcher(page).replaceFirst("$1\n$2");
		return page;
	}

	/** Reads the text file as extracted, or extracts the PDF first. */
	public static String readRaw(Path src) throws IOException {
		if (src.getFileName().toString().toLowerCase().endsWith(".pdf")) {
			src = extract(src);
		}
		return Files.readString(src, StandardCharsets.UTF_8);
	}

	/** {@link #readRaw} plus the character normalisation the verifier's parsers expect. */
	public static String load(Path src) throws IOException {
		String txt = readRaw(src);
		// Curly apostrophe, no-break space, soft hyphen, minus sign, vulgar halves: the same list as the Python tool.
		return txt.replace(RIGHT_QUOTE, "'").replace(NBSP, " ").replace(SOFT_HYPHEN, "").replace(MINUS, "-")
				.replace("1" + HALF, "1.5").replace(HALF, "0.5");
	}

	/**
	 * Normalise a name for matching: lower-case, no apostrophes, letters/digits and a few
	 * separators only.
	 */
	public static String norm(String s) {
		s = s.toLowerCase().replace("'", "").replace(RIGHT_QUOTE, "");
		s = NOT_NAME.matcher(s).replaceAll(" ");
		return WS.matcher(s).replaceAll(" ").strip();
	}

	/** Repair two-column hyphenation ("brack - ets") and collapse whitespace. */
	public static String dehyph(String s) {
		s = HYPHENATION.matcher(s).replaceAll("$1$2");
		return WS.matcher(s).replaceAll(" ");
	}

	/**
	 * Join a block of lines, dropping page markers and the two running-header lines that follow
	 * each.
	 */
	public static String joinBlock(List<String> blk) {
		List<String> out = new ArrayList<>();
		int skip = 0;
		for (String l : blk) {
			l = l.strip();
			if (l.startsWith("=== PAGE")) {
				skip = 2;
				continue;
			}
			if (skip > 0) {
				skip--;
				continue;
			}
			out.add(l);
		}
		return dehyph(String.join(" ", out));
	}

	/** Python's {@code re.match}: anchored at the start, not necessarily at the end. */
	public static Matcher match(Pattern p, String s) {
		Matcher m = p.matcher(s);
		return m.lookingAt() ? m : null;
	}

	/** Python's {@code re.search}: first match anywhere. */
	public static Matcher search(Pattern p, String s) {
		Matcher m = p.matcher(s);
		return m.find() ? m : null;
	}

	/**
	 * Python's {@code re.findall} restricted to the first group (or the whole match without
	 * groups).
	 */
	public static List<String> findAll(Pattern p, String s) {
		List<String> out = new ArrayList<>();
		Matcher m = p.matcher(s);
		while (m.find()) {
			out.add(m.groupCount() == 0 ? m.group() : m.group(1));
		}
		return out;
	}

	/**
	 * First line index at or after {@code from} whose stripped form equals {@code needle}, or -1.
	 */
	public static int lineNo(List<String> lines, String needle, int from) {
		for (int i = Math.max(from, 0); i < lines.size(); i++) {
			if (lines.get(i).strip().equals(needle)) {
				return i;
			}
		}
		return -1;
	}

	/** Python's {@code lines[a:b]} with clamping. */
	public static List<String> slice(List<String> lines, int from, int to) {
		from = Math.max(0, Math.min(from, lines.size()));
		to = Math.max(from, Math.min(to, lines.size()));
		return lines.subList(from, to);
	}
}
