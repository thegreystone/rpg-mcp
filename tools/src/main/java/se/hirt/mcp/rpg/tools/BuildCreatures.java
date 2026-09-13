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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static se.hirt.mcp.rpg.tools.SrdText.dehyph;
import static se.hirt.mcp.rpg.tools.SrdText.joinBlock;
import static se.hirt.mcp.rpg.tools.SrdText.search;

/**
 * {@code build-creatures}: regenerates {@code seed/srd5e/creatures.json} from every stat block in
 * the SRD 5.2.1 bestiary ("Monsters A-Z" and "Animals"). A stat block is a heading line followed by
 * a size line ("Medium or Small Humanoid, Neutral"). The header fields (AC, HP, speed, abilities
 * and saves, skills, resistances, immunities, vulnerabilities, senses, languages, gear, CR) are
 * parsed exactly; the Traits / Actions / Bonus Actions / Reactions / Legendary Actions sections are
 * split into named entries whose attack rolls and saving throws are structured and whose remaining
 * text is kept verbatim (dehyphenated). Entries already present in the seed file are kept as they
 * are.
 */
final class BuildCreatures {

	private static final Set<String> TYPES = Set.of("Aberration", "Beast", "Celestial", "Construct", "Dragon",
			"Elemental", "Fey", "Fiend", "Giant", "Humanoid", "Monstrosity", "Ooze", "Plant", "Undead");
	private static final Pattern SIZE_LINE = Pattern
			.compile("^(Tiny|Small|Medium|Large|Huge|Gargantuan)(?: or (Tiny|Small|Medium|Large|Huge|Gargantuan))?"
					+ " (?:(Swarm of (?:Tiny|Small|Medium)) )?([A-Z][a-z]+)(?: \\(([^)]+)\\))?(?:, ([A-Z][A-Za-z ]+))?$");
	private static final List<String> SECTIONS = List.of("Traits", "Actions", "Bonus Actions", "Reactions",
			"Legendary Actions");
	/**
	 * The stat blocks seeded by hand on 2026-09-01 and verified against the PDF then; they are kept
	 * as they are and only diffed. Every other entry is regenerated from the text on each run, so a
	 * parser fix reaches the seed.
	 */
	private static final Set<String> HAND_SEEDED = Set.of("Bandit", "Bandit Captain", "Commoner", "Cultist",
			"Giant Rat", "Goblin Warrior", "Guard", "Ogre", "Scout", "Skeleton", "Wolf", "Zombie");
	private static final Set<String> CONDS = Set.of("blinded", "charmed", "deafened", "exhaustion", "frightened",
			"grappled", "incapacitated", "invisible", "paralyzed", "petrified", "poisoned", "prone", "restrained",
			"stunned", "unconscious");
	private static final Set<String> DAMAGE_TYPES = Set.of("acid", "bludgeoning", "cold", "fire", "force", "lightning",
			"necrotic", "piercing", "poison", "psychic", "radiant", "slashing", "thunder");
	private static final Pattern ENTRY_START = Pattern
			.compile("^([A-Z][A-Za-z'\\- ]{0,40}?(?: \\([^)]{1,50}\\))?)\\.(?: (\\S.*))?$");
	// "+5 (with Advantage if the target is Grappled)", "+17 to hit" and "reach 5 feet" are the SRD's own variants.
	private static final Pattern ATTACK = Pattern
			.compile("^(Melee|Ranged|Melee or Ranged) Attack Roll: \\+(\\d+)(?: to hit)?(?: \\(([^)]*)\\))?, "
					+ "(?:reach (\\d+) (?:ft|feet)\\.?(?: or range (\\d+)(?:/(\\d+))? (?:ft|feet)\\.?)?|range (\\d+)(?:/(\\d+))? (?:ft|feet)\\.?)"
					+ "\\.? Hit: (?:(\\d+)(?: \\(([^)]+)\\))? (\\w+) damage)?(.*)$");
	/**
	 * Bodies that unmistakably open a new entry even when the previous line ended mid-list (a spell
	 * list, say).
	 */
	private static final Pattern OPENER = Pattern.compile(
			"^(?:(?:Melee|Ranged|Melee or Ranged) Attack Roll|(?:Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma) Saving Throw: DC|Trigger:).*");
	private static final Pattern PLUS = Pattern.compile("^,? plus (\\d+)(?: \\(([^)]+)\\))? (\\w+) damage");
	private static final Pattern SAVE = Pattern.compile(
			"^(Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma) Saving Throw: DC (\\d+), (.*?)\\. Failure: (.*)$");
	private static final Pattern DAMAGE_IN_TEXT = Pattern.compile("^(\\d+) \\(([^)]+)\\) (\\w+) damage");
	private static final Pattern QUALIFIER = Pattern.compile("^(.*?) \\((.*)\\)$");
	private static final Map<String, Integer> COUNT_WORDS = Map.of("one", 1, "two", 2, "three", 3, "four", 4, "five", 5,
			"six", 6, "seven", 7, "eight", 8);

	private final List<String> lines;
	private final ObjectMapper mapper = new ObjectMapper();
	private final List<String> notes = new ArrayList<>();
	private final List<String> errata = new ArrayList<>();
	/** CR -> XP from seed/srd5e/advancement.json ("Experience Points by Challenge Rating"). */
	private final Map<String, Integer> xpByCr = new LinkedHashMap<>();

	BuildCreatures(String txt) {
		this.lines = Arrays.asList(txt.split("\n", -1));
	}

	void run(Path seedFile) throws IOException {
		JsonNode existingDoc = mapper.readTree(seedFile.toFile());
		for (JsonNode e : mapper.readTree(seedFile.resolveSibling("advancement.json").toFile()).get("entries")) {
			if (e.get("id").asText().equals("srd5e:table/xp-by-cr")) {
				for (JsonNode row : e.get("payload").get("rows")) {
					xpByCr.put(row.get("cr").asText(), row.get("xp").asInt());
				}
			}
		}
		if (xpByCr.isEmpty()) {
			throw new IllegalStateException("srd5e:table/xp-by-cr not found in advancement.json");
		}
		Map<String, JsonNode> existing = new LinkedHashMap<>();
		for (JsonNode e : existingDoc.get("entries")) {
			existing.put(e.get("name").asText(), e);
		}
		int start = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).strip().matches("Monsters A.Z")) {
				start = i;
			}
		}
		if (start < 0) {
			throw new IllegalStateException("'Monsters A-Z' heading not found");
		}
		List<int[]> blocks = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (int i = start; i + 1 < lines.size(); i++) {
			String name = lines.get(i).strip();
			int j = nextContent(i);
			if (j >= lines.size() || !isHeading(name) || SIZE_LINE.matcher(lines.get(j).strip()).matches() == false) {
				continue;
			}
			Matcher sm = SIZE_LINE.matcher(lines.get(j).strip());
			sm.matches();
			if (!TYPES.contains(sm.group(3) != null ? sm.group(4).replaceAll("s$", "") : sm.group(4))) {
				continue;
			}
			if (!blocks.isEmpty()) {
				// The PDF prints every bestiary heading twice ("Hell Hound" / "Hell Hound" / "Medium Fiend, ..."); only
				// the second copy is followed by the size line, so the first would otherwise bleed into the previous
				// block's last action text ("Success: Half damage. Hezrou"). Trim trailing copies of the heading.
				int end = i;
				while (end - 1 > blocks.get(blocks.size() - 1)[0] && lines.get(end - 1).strip().equals(name)) {
					end--;
				}
				blocks.get(blocks.size() - 1)[1] = end;
			}
			blocks.add(new int[] {j, lines.size()});
			names.add(name);
		}
		Map<String, ObjectNode> built = new TreeMap<>();
		List<String> failed = new ArrayList<>();
		for (int b = 0; b < blocks.size(); b++) {
			String name = names.get(b);
			List<String> blk = new ArrayList<>(SrdText.slice(lines, blocks.get(b)[0], blocks.get(b)[1]));
			try {
				built.put(name, parse(name, blk));
			} catch (RuntimeException ex) {
				failed.add(name + ": " + ex.getMessage());
			}
		}
		ObjectNode doc = mapper.createObjectNode();
		doc.put("kind", "CREATURE");
		doc.put("citation",
				"SRD 5.2.1, Monsters A-Z and Animals; Gameplay Toolbox - Experience Points by Challenge Rating");
		doc.put("verify", "Generated from the SRD 5.2.1 PDF text (CC-BY-4.0) by tools 'build-creatures' on 2026-09-08: "
				+ built.size()
				+ " stat blocks, every heading in Monsters A-Z and Animals. Parsed exactly: size, type, alignment, AC, initiative, HP, speed, "
				+ "ability scores and saves, skills, gear, senses, languages, damage resistances/immunities/vulnerabilities, condition "
				+ "immunities, CR/XP/PB, attack names, bonuses, reach/range and damage (with 'plus' riders), saving-throw actions (ability, DC, "
				+ "failure/success text), Multiattack counts, recharge and per-day uses. Kept as text: trait, action, bonus action, "
				+ "reaction and legendary action bodies (dehyphenated, verbatim). The 12 entries seeded by hand on 2026-09-01 are kept "
				+ "unchanged; differences the generator found against them are listed in the build output. xp_value comes from the "
				+ "Experience Points by Challenge Rating table (CR 0 keeps the printed 0 or 10); the printed XP is cross-checked and "
				+ "SRD errata are listed here: " + (errata.isEmpty() ? "none" : String.join("; ", errata))
				+ ". Checked with tools 'verify'.");
		ObjectNode schema = mapper.createObjectNode();
		schema.put("hp", "{average, dice}");
		schema.put("abilities", "{STR..CHA}");
		schema.put("saves", "{ABILITY: bonus} only where the stat block lists a save that differs from the modifier");
		schema.put("cr", "string, cr_times_8 integer, xp_value integer (xp_in_lair when the SRD gives one)");
		schema.put("actions",
				"[{name, kind: MELEE_ATTACK|RANGED_ATTACK|OTHER, attack_bonus, reach|range, damage: [{dice, type}], save: {ability, dc, targets, failure, success}, recharge, uses, text}]");
		schema.put("multiattack", "{count, text} when the creature attacks more than once per Attack action");
		schema.put("traits, bonus_actions, reactions, legendary_actions",
				"[{name, text}]; legendary_action_uses when present");
		doc.set("schema", schema);
		ArrayNode entries = doc.putArray("entries");
		int kept = 0;
		for (Map.Entry<String, ObjectNode> e : built.entrySet()) {
			JsonNode old = existing.get(e.getKey());
			if (old != null && HAND_SEEDED.contains(e.getKey())) {
				diff(e.getKey(), old.get("payload"), e.getValue().get("payload"));
				entries.add(old);
				kept++;
			} else {
				entries.add(e.getValue());
			}
		}
		for (String name : existing.keySet()) {
			if (!built.containsKey(name)) {
				notes.add("kept without SRD match: " + name);
				entries.add(existing.get(name));
			}
		}
		DefaultPrettyPrinter printer = new DefaultPrettyPrinter().withSeparators(
				Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER));
		DefaultIndenter indenter = new DefaultIndenter(" ", "\r\n");
		printer.indentObjectsWith(indenter);
		printer.indentArraysWith(indenter);
		String json = mapper.writer(printer).writeValueAsString(doc);
		Files.writeString(seedFile, json, StandardCharsets.UTF_8);
		System.out.println("wrote " + seedFile + ": " + entries.size() + " creatures (" + kept
				+ " kept from the previous seed, " + (built.size() - kept) + " generated)");
		for (String f : failed) {
			System.out.println("FAILED " + f);
		}
		for (String n : notes) {
			System.out.println("NOTE " + n);
		}
	}

	/**
	 * Index of the next line that is not a page marker or one of the two running-header lines after
	 * it.
	 */
	private int nextContent(int i) {
		int j = i + 1;
		while (j < lines.size()) {
			String l = lines.get(j).strip();
			if (l.startsWith("=== PAGE")) {
				j += 3;
				continue;
			}
			return j;
		}
		return j;
	}

	private static boolean isHeading(String name) {
		if (name.isEmpty() || name.startsWith("===") || name.matches("\\d+")
				|| name.startsWith("System Reference Document")) {
			return false;
		}
		if (!Character.isUpperCase(name.charAt(0)) || name.endsWith(".") || name.endsWith(",") || name.contains(":")) {
			return false;
		}
		return name.split("\\s+").length <= 5 && !name.matches(".*\\d.*");
	}

	// ── one stat block ───────────────────────────────────────────────────

	private ObjectNode parse(String name, List<String> blk) {
		// Section boundaries on raw lines (page markers and headers are dropped by joinBlock).
		List<String> clean = new ArrayList<>();
		int skip = 0;
		for (String l : blk) {
			String s = l.strip();
			if (s.startsWith("=== PAGE")) {
				skip = 2;
				continue;
			}
			if (skip > 0) {
				skip--;
				continue;
			}
			clean.add(s);
		}
		Map<String, List<String>> sections = new LinkedHashMap<>();
		List<String> header = new ArrayList<>();
		String current = null;
		for (String l : clean) {
			if (SECTIONS.contains(l)) {
				current = l;
				sections.put(l, new ArrayList<>());
				continue;
			}
			if (current == null) {
				header.add(l);
			} else {
				sections.get(current).add(l);
			}
		}
		String head = joinBlock(header);
		ObjectNode p = mapper.createObjectNode();
		Matcher sm = SIZE_LINE.matcher(header.get(0));
		if (!sm.matches()) {
			throw new IllegalStateException("size line: " + header.get(0));
		}
		p.put("size", sm.group(1));
		if (sm.group(2) != null) {
			p.put("size_alternative", sm.group(2));
		}
		p.put("creature_type", sm.group(3) != null ? sm.group(3) + " " + sm.group(4) : sm.group(4));
		if (sm.group(5) != null) {
			p.put("creature_tag", sm.group(5));
		}
		if (sm.group(6) != null) {
			p.put("alignment", sm.group(6).strip());
		}
		Matcher m = req(search(Pattern.compile("AC (\\d+)"), head), "AC", name);
		p.put("ac", Integer.parseInt(m.group(1)));
		m = search(Pattern.compile("Initiative ([+-]\\d+) \\((\\d+)\\)"), head);
		if (m != null) {
			p.put("initiative_bonus", Integer.parseInt(m.group(1)));
		}
		m = req(search(Pattern.compile("HP (\\d+) \\(([^)]+)\\)"), String.join(" ", header)), "HP", name);
		ObjectNode hp = p.putObject("hp");
		hp.put("average", Integer.parseInt(m.group(1)));
		hp.put("dice", dice(m.group(2)));
		m = req(search(Pattern.compile("Speed (.*?)(?= MOD)"), head), "Speed", name);
		ObjectNode speed = p.putObject("speed");
		for (String part : m.group(1).split(",")) {
			Matcher mm = SrdText.match(Pattern.compile("\\s*(?:(\\w+) )?(\\d+) ft"), part);
			if (mm != null) {
				speed.put((mm.group(1) != null ? mm.group(1) : "walk").toLowerCase(Locale.ROOT),
						Integer.parseInt(mm.group(2)));
			}
		}
		if (m.group(1).contains("(hover)")) {
			p.put("hover", true);
		}
		ObjectNode abilities = p.putObject("abilities");
		ObjectNode saves = mapper.createObjectNode();
		Matcher ab = Pattern.compile("\\b(Str|Dex|Con|Int|Wis|Cha) (\\d+) ([+-]\\d+) ([+-]?\\d+)").matcher(head);
		while (ab.find()) {
			String a = ab.group(1).toUpperCase(Locale.ROOT);
			int score = Integer.parseInt(ab.group(2));
			int save = Integer.parseInt(ab.group(4));
			abilities.put(a, score);
			if (save != Math.floorDiv(score - 10, 2)) {
				saves.put(a, save);
			}
		}
		if (abilities.size() != 6) {
			throw new IllegalStateException("abilities parsed: " + abilities.size());
		}
		if (!saves.isEmpty()) {
			p.set("saves", saves);
		}
		String fieldEnd = "(?= Senses| Resistances| Immunities| Vulnerabilities| Gear| Languages| CR)";
		m = search(Pattern.compile("Skills (.*?)" + fieldEnd), head);
		if (m != null) {
			ObjectNode skills = p.putObject("skills");
			Matcher sk = Pattern.compile("(\\w+) \\+(\\d+)").matcher(m.group(1));
			while (sk.find()) {
				skills.put(sk.group(1), Integer.parseInt(sk.group(2)));
			}
		}
		for (String[] kl : new String[][] {{"Resistances", "damage_resistances"}, {"Immunities", "damage_immunities"},
				{"Vulnerabilities", "damage_vulnerabilities"}}) {
			m = search(Pattern.compile(kl[0] + " (.*?)" + fieldEnd), head);
			if (m == null) {
				continue;
			}
			List<String> damage = new ArrayList<>();
			List<String> conditions = new ArrayList<>();
			List<String> other = new ArrayList<>();
			for (String tok : m.group(1).split("[,;]")) {
				String t = tok.strip();
				if (t.isEmpty()) {
					continue;
				}
				String base = t.toLowerCase(Locale.ROOT).replaceAll(" \\(.*\\)$", "");
				if (CONDS.contains(base)) {
					conditions.add(t);
				} else if (DAMAGE_TYPES.contains(base)) {
					damage.add(base);
				} else {
					other.add(t);
				}
			}
			if (!damage.isEmpty()) {
				strings(p.putArray(kl[1]), damage);
			}
			if (!conditions.isEmpty()) {
				strings(p.putArray("condition_" + kl[1].substring("damage_".length())), conditions);
			}
			if (!other.isEmpty()) {
				strings(p.putArray(kl[1] + "_notes"), other);
				notes.add(name + ": unclassified " + kl[0].toLowerCase(Locale.ROOT) + " " + other);
			}
		}
		m = search(Pattern.compile("Gear (.*?)" + fieldEnd), head);
		if (m != null) {
			strings(p.putArray("gear"),
					Arrays.stream(m.group(1).split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
		}
		m = search(Pattern.compile("Senses (.*?)(?= Languages| CR)"), head);
		if (m != null) {
			strings(p.putArray("senses"),
					Arrays.stream(m.group(1).split("[;,]")).map(String::strip).filter(s -> !s.isEmpty()).toList());
		}
		m = search(Pattern.compile("Languages (.*?)(?= CR \\d)"), head);
		if (m != null && !m.group(1).strip().equals("None")) {
			strings(p.putArray("languages"),
					Arrays.stream(m.group(1).split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
		}
		// Four blocks (three metallic wyrmlings, Young White Dragon) print "700 XP" instead of "XP 700"; the Young White
		// Dragon's Intelligence save is printed without its sign. Both are typographical slips in the SRD text.
		m = req(search(
				Pattern.compile(
						"CR ([\\d/]+) \\((?:XP ([\\d,]+)|([\\d,]+) XP)(?:,? or ([\\d,]+) in lair)?; PB \\+(\\d+)\\)"),
				head), "CR", name);
		String cr = m.group(1);
		String xp = m.group(2) != null ? m.group(2) : m.group(3);
		p.put("cr", cr);
		p.put("cr_times_8", switch (cr) {
		case "0" -> 0;
		case "1/8" -> 1;
		case "1/4" -> 2;
		case "1/2" -> 4;
		default -> Integer.parseInt(cr) * 8;
		});
		int printedXp = Integer.parseInt(xp.replace(",", ""));
		Integer tableXp = xpByCr.get(cr);
		if (tableXp == null) {
			throw new IllegalStateException("CR " + cr + " not in the XP table");
		}
		// The table is authoritative; CR 0 is legitimately 0 XP (no attacks) or 10 XP, so the printed value stands there.
		if (cr.equals("0") && (printedXp == 0 || printedXp == 10)) {
			tableXp = printedXp;
		} else if (printedXp != tableXp) {
			errata.add(name + ": PDF prints XP " + xp + ", table gives " + tableXp + "; table used");
		}
		p.put("xp_value", tableXp.intValue());
		if (m.group(4) != null) {
			p.put("xp_in_lair", Integer.parseInt(m.group(4).replace(",", "")));
		}
		p.put("proficiency_bonus", Integer.parseInt(m.group(5)));

		List<Map.Entry<String, String>> traits = entries(sections.getOrDefault("Traits", List.of()));
		if (!traits.isEmpty()) {
			ArrayNode arr = p.putArray("traits");
			for (var t : traits) {
				ObjectNode tn = arr.addObject();
				named(tn, t.getKey());
				tn.put("text", t.getValue());
			}
		}
		ArrayNode actions = mapper.createArrayNode();
		for (var a : entries(sections.getOrDefault("Actions", List.of()))) {
			String aname = a.getKey();
			String text = a.getValue();
			if (aname.startsWith("Multiattack")) {
				ObjectNode multi = p.putObject("multiattack");
				multi.put("count", multiattackCount(text));
				multi.put("text", text);
				Matcher q = QUALIFIER.matcher(aname);
				if (q.matches()) {
					multi.put("qualifier", q.group(2));
				}
				continue;
			}
			actions.add(action(aname, text));
		}
		// The Shrieker Fungus has no Actions section at all (only a reaction); it still gets an empty list.
		p.set("actions", actions);
		for (String[] sec : new String[][] {{"Bonus Actions", "bonus_actions"}, {"Reactions", "reactions"}}) {
			List<Map.Entry<String, String>> es = entries(sections.getOrDefault(sec[0], List.of()));
			if (!es.isEmpty()) {
				ArrayNode arr = p.putArray(sec[1]);
				for (var e : es) {
					arr.add(action(e.getKey(), e.getValue()));
				}
			}
		}
		List<Map.Entry<String, String>> leg = entries(sections.getOrDefault("Legendary Actions", List.of()));
		if (!leg.isEmpty()) {
			ArrayNode arr = p.putArray("legendary_actions");
			for (var e : leg) {
				if (e.getKey().startsWith("Legendary Action Uses")) {
					Matcher u = search(Pattern.compile("(\\d+)(?: \\((\\d+) in Lair\\))?"),
							e.getKey() + ". " + e.getValue());
					if (u != null) {
						p.put("legendary_action_uses", Integer.parseInt(u.group(1)));
						if (u.group(2) != null) {
							p.put("legendary_action_uses_in_lair", Integer.parseInt(u.group(2)));
						}
					}
					p.put("legendary_actions_text", e.getKey() + ". " + e.getValue());
					continue;
				}
				ObjectNode ln = arr.addObject();
				named(ln, e.getKey());
				ln.put("text", e.getValue());
			}
		}
		ObjectNode entry = mapper.createObjectNode();
		entry.put("id", "srd5e:creature/" + slug(name));
		entry.put("name", name);
		entry.set("payload", p);
		return entry;
	}

	/**
	 * "Fire Breath (Recharge 5-6)" -> name + recharge; "(3/Day)" -> uses; other parentheticals ->
	 * qualifier.
	 */
	private static void named(ObjectNode n, String heading) {
		Matcher q = QUALIFIER.matcher(heading);
		if (!q.matches()) {
			n.put("name", heading);
			return;
		}
		n.put("name", q.group(1));
		String inner = q.group(2);
		Matcher r = search(Pattern.compile("Recharge ([\\d-]+)"), inner.replace("\u2013", "-"));
		Matcher u = search(Pattern.compile("(\\d+)/(Day|Short Rest|Long Rest)"), inner);
		if (r != null) {
			n.put("recharge", r.group(1));
		} else if (u != null) {
			n.put("uses", u.group(1) + "/" + u.group(2));
			if (inner.contains("in Lair")) {
				n.put("uses_note", inner);
			}
		} else if (inner.contains("Recharge after")) {
			n.put("recharge", inner);
		} else {
			n.put("qualifier", inner);
		}
	}

	private ObjectNode action(String heading, String text) {
		ObjectNode a = mapper.createObjectNode();
		named(a, heading);
		Matcher at = ATTACK.matcher(text);
		if (at.matches()) {
			String mode = at.group(1);
			a.put("kind", mode.equals("Ranged") ? "RANGED_ATTACK" : "MELEE_ATTACK");
			a.put("attack_bonus", Integer.parseInt(at.group(2)));
			if (at.group(3) != null) {
				a.put("attack_note", at.group(3));
			}
			if (at.group(4) != null) {
				a.put("reach", Integer.parseInt(at.group(4)));
			}
			String rn = at.group(5) != null ? at.group(5) : at.group(7);
			String rl = at.group(6) != null ? at.group(6) : at.group(8);
			if (rn != null) {
				ObjectNode range = a.putObject("range");
				range.put("normal", Integer.parseInt(rn));
				range.put("long", Integer.parseInt(rl != null ? rl : rn));
			}
			ArrayNode dmg = a.putArray("damage");
			if (at.group(11) != null) {
				ObjectNode first = dmg.addObject();
				first.put("dice", at.group(10) != null ? dice(at.group(10)) : at.group(9));
				first.put("type", at.group(11).toLowerCase(Locale.ROOT));
			}
			String rest = at.group(12);
			Matcher plus = PLUS.matcher(rest);
			while (plus.lookingAt()) {
				ObjectNode d = dmg.addObject();
				d.put("dice", plus.group(2) != null ? dice(plus.group(2)) : plus.group(1));
				d.put("type", plus.group(3).toLowerCase(Locale.ROOT));
				rest = rest.substring(plus.end());
				plus = PLUS.matcher(rest);
			}
			rest = rest.strip();
			if (rest.startsWith(".")) {
				rest = rest.substring(1).strip();
			}
			if (!rest.isEmpty()) {
				a.put("text", rest);
			}
			return a;
		}
		a.put("kind", "OTHER");
		Matcher sv = SAVE.matcher(text);
		if (sv.matches()) {
			ObjectNode save = a.putObject("save");
			save.put("ability", sv.group(1).substring(0, 3).toUpperCase(Locale.ROOT));
			save.put("dc", Integer.parseInt(sv.group(2)));
			save.put("targets", sv.group(3));
			String failure = sv.group(4);
			String success = null;
			int idx = failure.indexOf(" Success: ");
			if (idx >= 0) {
				success = failure.substring(idx + " Success: ".length());
				failure = failure.substring(0, idx);
			}
			save.put("failure", failure);
			if (success != null) {
				save.put("success", success);
			}
			Matcher dm = DAMAGE_IN_TEXT.matcher(failure);
			if (dm.find()) {
				ArrayNode dmg = a.putArray("damage");
				ObjectNode d = dmg.addObject();
				d.put("dice", dice(dm.group(2)));
				d.put("type", dm.group(3).toLowerCase(Locale.ROOT));
			}
		}
		a.put("text", text);
		return a;
	}

	private static int multiattackCount(String text) {
		Matcher m = search(Pattern.compile("makes (one|two|three|four|five|six|seven|eight|\\d+) "), text);
		if (m == null) {
			return 2;
		}
		String w = m.group(1);
		return COUNT_WORDS.containsKey(w) ? COUNT_WORDS.get(w) : Integer.parseInt(w);
	}

	/**
	 * Splits a section into "Name. text" entries. A new entry starts on a line that begins with a
	 * short capitalised name followed by ". " when the previous line ended a sentence (so wrapped
	 * lines such as "Piercing damage." after a line ending in ")" stay inside the running entry).
	 */
	private static List<Map.Entry<String, String>> entries(List<String> sec) {
		List<Map.Entry<String, String>> out = new ArrayList<>();
		String name = null;
		StringBuilder body = new StringBuilder();
		String prev = "";
		// A heading's parenthetical can wrap ("Legendary Resistance (3/Day, or 4/Day in" / "Lair)."): join such lines first.
		List<String> joined = new ArrayList<>();
		for (String l : sec) {
			if (!joined.isEmpty() && unclosedParen(joined.get(joined.size() - 1))) {
				joined.set(joined.size() - 1, joined.get(joined.size() - 1) + " " + l);
			} else {
				joined.add(l);
			}
		}
		for (String l : joined) {
			if (l.startsWith("Legendary Action Uses: ")) {
				l = "Legendary Action Uses. " + l.substring("Legendary Action Uses: ".length());
			}
			Matcher m = ENTRY_START.matcher(l);
			String rest = m.matches() ? m.group(2) : null;
			boolean starts = m.matches() && (rest != null || m.group(1).contains("("))
					&& (prev.isEmpty() || prev.endsWith(".") || prev.endsWith(":") || prev.endsWith("!")
							|| (rest != null && OPENER.matcher(rest).matches()))
					&& !m.group(1).contains(" damage") && !m.group(1).startsWith("At Will")
					&& m.group(1).replaceAll(" \\(.*\\)$", "").split(" ").length <= 6;
			if (starts) {
				if (name != null) {
					out.add(Map.entry(name, dehyph(body.toString()).strip()));
				}
				name = m.group(1);
				body = new StringBuilder(rest != null ? rest : "");
			} else if (name != null) {
				body.append(' ').append(l);
			} else {
				// Text before the first named entry (rare): attach it to a synthetic entry.
				name = "Note";
				body = new StringBuilder(l);
			}
			prev = l;
		}
		if (name != null) {
			out.add(Map.entry(name, dehyph(body.toString()).strip()));
		}
		return out;
	}

	/**
	 * "1d4 – 1" -> "1d4-1": no spaces, and every dash variant (en dash, em dash, minus sign) as
	 * ASCII.
	 */
	static String dice(String s) {
		return s.replace(" ", "").replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-');
	}

	private static boolean unclosedParen(String l) {
		int open = l.lastIndexOf('(');
		return open >= 0 && l.indexOf(')', open) < 0;
	}

	private void diff(String name, JsonNode old, JsonNode fresh) {
		for (String k : List.of("ac", "hp", "speed", "abilities", "saves", "skills", "cr", "xp_value",
				"proficiency_bonus", "damage_resistances", "damage_immunities", "damage_vulnerabilities")) {
			JsonNode a = old.get(k);
			JsonNode b = fresh.get(k);
			if (a == null && b == null) {
				continue;
			}
			if (a == null || b == null || !a.equals(b)) {
				notes.add(name + ": " + k + " seed " + a + " vs SRD " + b + " (seed entry kept)");
			}
		}
	}

	private static Matcher req(Matcher m, String what, String name) {
		if (m == null) {
			throw new IllegalStateException(what + " not found");
		}
		return m;
	}

	private static void strings(ArrayNode arr, List<String> values) {
		for (String v : values) {
			arr.add(v);
		}
	}

	static String slug(String name) {
		String s = name.toLowerCase(Locale.ROOT).replace("'", "").replaceAll("[^a-z0-9]+", "-");
		return s.replaceAll("^-|-$", "");
	}
}
