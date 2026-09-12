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

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Build {@code seed/srd5e/rules.json} from the SRD 5.2.1 text.
 * <p>
 * The Rules Glossary is an alphabetical list of procedural rules: conditions, actions, hazards, areas of effect and
 * the general glossary. Everything here is lifted from the SRD text itself rather than paraphrased, so the entries
 * carry {@code text_is_paraphrase: false}. Re-run after any SRD revision; the output is checked back against the SRD
 * by the verifier.
 */
final class BuildRules {
	private static final Pattern HEAD = Py.re("^([A-Z][A-Za-z'/ ]{1,40}?)(\\s\\[([A-Za-z ]+)\\])?$");
	private static final String RIGHT_QUOTE = Character.toString(0x2019);
	private static final String LEFT_DQUOTE = Character.toString(0x201C);
	private static final String RIGHT_DQUOTE = Character.toString(0x201D);
	private static final String MINUS = Character.toString(0x2212);
	private static final String EN_DASH = Character.toString(0x2013);
	private static final String ELLIPSIS = Character.toString(0x2026);

	/** The raw extracted text: this builder does its own character repairs so the glossary stays verbatim. */
	private final String txt;

	BuildRules(String txt) {
		this.txt = txt;
	}

	private record Entry(String name, String body, String tag) {
	}

	void run(Path out) throws IOException {
		int start = txt.indexOf("Rules Definitions\nHere are definitions of various rules.");
		// "Gameplay Toolbox" is cross-referenced inside the glossary; the section itself begins after a page break.
		int end = start < 0 ? -1 : txt.indexOf("=== PAGE 192 ===", start);
		if (start < 0 || end < 0) {
			throw new IllegalStateException("could not locate the Rules Glossary");
		}
		String g = txt.substring(start, end);

		// Strip PDF furniture, then repair the hyphenation the two-column layout introduces.
		g = g.replaceAll("=== PAGE \\d+ ===\n", "");
		g = g.replaceAll("System Reference Document 5\\.2\\.1\n\\d+\n", "");
		g = g.replaceAll("\\d+\nSystem Reference Document 5\\.2\\.1\n", "");
		g = g.replace(RIGHT_QUOTE, "'").replace(LEFT_DQUOTE, "\"").replace(RIGHT_DQUOTE, "\"");
		g = Py.re("(\\w)\\s*[-" + MINUS + EN_DASH + "]\\s*\n(\\w)").matcher(g).replaceAll("$1$2"); // "brack -\nets"
		g = g.replace(MINUS, "-");

		List<String> lines = List.of(g.split("\n", -1));
		List<int[]> headLines = new ArrayList<>();
		List<Entry> heads = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i).strip();
			Matcher m = SrdText.match(HEAD, line);
			if (m == null) {
				continue;
			}
			String name = m.group(1).strip();
			String tag = m.group(3);
			if (name.equals("Rules Definitions") || name.length() < 3) {
				continue;
			}
			String[] words = name.split("\\s+");
			// Table headers ("AC Substance AC Substance", "Size Fragile Resilient") are three or more words that are
			// all capitalised; a real entry name of that length has a lowercase connector.
			if (words.length >= 3 && allCapitalised(words)) {
				continue;
			}
			// A heading is followed by prose, not by another heading or a row of numbers.
			String nxt = "";
			for (String l : SrdText.slice(lines, i + 1, i + 4)) {
				if (!l.strip().isEmpty()) {
					nxt = l.strip();
					break;
				}
			}
			if (nxt.length() < 25 || !Character.isLetter(nxt.charAt(0))) {
				continue;
			}
			headLines.add(new int[] {i});
			heads.add(new Entry(name, null, tag));
		}

		Map<String, Entry> best = new LinkedHashMap<>();
		for (int idx = 0; idx < heads.size(); idx++) {
			int i = headLines.get(idx)[0];
			int stop = idx + 1 < heads.size() ? headLines.get(idx + 1)[0] : lines.size();
			String body = String.join(" ", SrdText.slice(lines, i + 1, stop)).strip();
			body = body.replaceAll("[ \t]+", " ").strip();
			if (body.length() < 40) {
				continue;
			}
			String name = heads.get(idx).name();
			String slug = Py.strip(name.toLowerCase().replaceAll("[^a-z0-9]+", "-"), '-');
			// A term can appear both inside a list (bare, trailing the next entry's prose) and as its own entry.
			// The real entry is the one with the fuller body.
			if (best.containsKey(slug) && best.get(slug).body().length() >= body.length()) {
				continue;
			}
			best.put(slug, new Entry(name, body, heads.get(idx).tag()));
		}

		ObjectMapper mapper = new ObjectMapper();
		List<ObjectNode> entries = new ArrayList<>();
		for (Map.Entry<String, Entry> be : best.entrySet()) {
			Entry e = be.getValue();
			ObjectNode payload = mapper.createObjectNode();
			String first200 = Py.head(e.body(), 200);
			int cut = first200.lastIndexOf(' ');
			payload.put("summary", (cut >= 0 ? first200.substring(0, cut) : first200) + (e.body().length() > 200 ? ELLIPSIS : ""));
			payload.put("text", e.body());
			payload.put("text_is_paraphrase", false);
			if (e.tag() != null) {
				payload.put("tag", e.tag().toUpperCase().replace(' ', '_'));
			}
			ObjectNode entry = mapper.createObjectNode();
			entry.put("id", "srd5e:rule/" + be.getKey());
			entry.put("name", e.name() + (e.tag() != null ? " [" + e.tag() + "]" : ""));
			entry.set("payload", payload);
			entries.add(entry);
		}
		entries.sort((a, b) -> a.get("name").asText().toLowerCase().compareTo(b.get("name").asText().toLowerCase()));

		ObjectNode doc = mapper.createObjectNode();
		doc.put("kind", "RULE");
		doc.put("citation", "SRD 5.2.1, Rules Glossary (pp. 176-191)");
		doc.put("verify", "Lifted verbatim from the SRD 5.2.1 PDF text (CC-BY-4.0) on " + LocalDate.now()
				+ " by the tools/ rules builder (SrdTool build-rules): each entry is the glossary entry's own wording with "
				+ "the two-column hyphenation repaired, not a paraphrase. Re-check with SrdTool verify after any edit.");
		ArrayNode arr = doc.putArray("entries");
		entries.forEach(arr::add);

		Files.writeString(out, mapper.writer(pythonStyle()).writeValueAsString(doc) + "\n", StandardCharsets.UTF_8);
		System.out.println("wrote " + entries.size() + " rules to " + out);
		Map<String, List<String>> tags = new TreeMap<>();
		for (ObjectNode e : entries) {
			String tag = e.get("payload").has("tag") ? e.get("payload").get("tag").asText() : "-";
			tags.computeIfAbsent(tag, k -> new ArrayList<>()).add(e.get("name").asText());
		}
		tags.forEach((k, v) -> System.out.printf("  %-14s %2d  %s%n", k, v.size(),
				v.stream().limit(8).map(x -> x.split(" \\[")[0]).collect(Collectors.joining(", "))));
	}

	private static boolean allCapitalised(String[] words) {
		for (String w : words) {
			if (!Character.isUpperCase(w.charAt(0))) {
				return false;
			}
		}
		return true;
	}

	/** Matches Python's {@code json.dumps(indent=2, ensure_ascii=False)} so regenerating gives a clean diff. */
	private static DefaultPrettyPrinter pythonStyle() {
		DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
		DefaultIndenter indent = new DefaultIndenter("  ", "\n");
		pp.indentObjectsWith(indent);
		pp.indentArraysWith(indent);
		return pp.withSeparators(Separators.createDefaultInstance()
				.withObjectFieldValueSpacing(Separators.Spacing.AFTER)
				.withObjectEmptySeparator("")
				.withArrayEmptySeparator(""));
	}
}
