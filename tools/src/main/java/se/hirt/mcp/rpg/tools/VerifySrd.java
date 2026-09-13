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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static se.hirt.mcp.rpg.tools.Py.integer;
import static se.hirt.mcp.rpg.tools.Py.neq;
import static se.hirt.mcp.rpg.tools.Py.num;
import static se.hirt.mcp.rpg.tools.Py.re;
import static se.hirt.mcp.rpg.tools.Py.repr;
import static se.hirt.mcp.rpg.tools.Py.str;
import static se.hirt.mcp.rpg.tools.Py.truthy;
import static se.hirt.mcp.rpg.tools.SrdText.dehyph;
import static se.hirt.mcp.rpg.tools.SrdText.findAll;
import static se.hirt.mcp.rpg.tools.SrdText.joinBlock;
import static se.hirt.mcp.rpg.tools.SrdText.lineNo;
import static se.hirt.mcp.rpg.tools.SrdText.match;
import static se.hirt.mcp.rpg.tools.SrdText.norm;
import static se.hirt.mcp.rpg.tools.SrdText.search;
import static se.hirt.mcp.rpg.tools.SrdText.slice;

/**
 * Diff the srd5e seed files against the SRD 5.2.1 text.
 * <p>
 * Writes {@code srd_report.txt} (one line per finding, bracketed by category). Categories without a
 * suffix are hard discrepancies; the
 * {@code *-text / *-info / *-upcast / *-table / *-equipment / *-traits} categories are context for
 * manual review. The checks are a straight port of the original Python script and keep its
 * structure and messages.
 */
final class VerifySrd {
	private final String txt;
	private final List<String> lines;
	private final List<String> report = new ArrayList<>();
	private Path seedDir;
	private JsonNode items, creatures, spells, classes, rulesDoc, species, advancement;

	// Shared between sections.
	private Map<String, String[]> srdWeapons;
	private Map<String, String[]> srdArmor;
	private JsonNode adv;

	VerifySrd(String txt) {
		this.txt = txt;
		this.lines = Arrays.asList(txt.split("\n", -1));
	}

	void run(Path seedDir, Path reportPath) throws IOException {
		this.seedDir = seedDir;
		items = load("items.json");
		creatures = load("creatures.json");
		spells = load("spells.json");
		classes = load("classes.json");
		rulesDoc = Files.exists(seedDir.resolve("rules.json")) ? load("rules.json") : null;
		species = load("species.json");
		advancement = load("advancement.json");

		weapons();
		armor();
		gear();
		advancement();
		classes();
		species();
		creatures();
		spells();
		spellLists();
		origins();
		rules();

		Files.writeString(reportPath, String.join("\n", report), StandardCharsets.UTF_8);
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String r : report) {
			counts.merge(r.substring(1, r.indexOf(']')), 1, Integer::sum);
		}
		System.out.println("Counter("
				+ repr(counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).collect(
						Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)))
				+ ")");
	}

	private JsonNode load(String name) throws IOException {
		return new ObjectMapper().readTree(seedDir.resolve(name).toFile());
	}

	private void rep(String cat, String msg) {
		report.add("[" + cat + "] " + msg);
	}

	private static Integer costCp(String s) {
		s = s.replace(",", "").strip();
		Matcher m = match(re("^([\\d.]+) (CP|SP|GP|PP)$"), s);
		if (m == null) {
			return null;
		}
		double v = Double.parseDouble(m.group(1));
		int mult = switch (m.group(2)) {
		case "CP" -> 1;
		case "SP" -> 10;
		case "GP" -> 100;
		default -> 1000;
		};
		return (int) Math.round(v * mult);
	}

	private static Double weightLb(String s) {
		s = s.strip();
		if (s.equals("—") || s.equals("no weight")) {
			return 0.0;
		}
		Matcher m = match(re("^([\\d/.]+) lb\\.?$"), s);
		if (m == null) {
			return null;
		}
		String v = m.group(1);
		if (v.contains("/")) {
			String[] ab = v.split("/");
			return Double.parseDouble(ab[0]) / Double.parseDouble(ab[1]);
		}
		return Double.parseDouble(v);
	}

	private static List<String> sorted(Collection<String> c) {
		List<String> out = new ArrayList<>(c);
		Collections.sort(out);
		return out;
	}

	private static Set<String> minus(Set<String> a, Set<String> b) {
		Set<String> out = new TreeSet<>(a);
		out.removeAll(b);
		return out;
	}

	private static Set<String> both(Set<String> a, Set<String> b) {
		Set<String> out = new TreeSet<>(a);
		out.retainAll(b);
		return out;
	}

	private static String lower(String s) {
		return s == null ? null : s.toLowerCase();
	}

	// ── 1. Weapons ───────────────────────────────────────────────────────

	private List<String[]> weaponsTable() {
		int start = lines.indexOf("Simple Melee Weapons");
		List<String[]> rows = new ArrayList<>();
		String buf = "";
		Pattern row = re(
				"^(.+?) (1|\\d+d\\d+) (Bludgeoning|Piercing|Slashing) (.*?) (\\S+) (—|[\\d/]+ lb\\.) ([\\d,]+ (?:CP|SP|GP))$");
		Pattern section = re("^(Simple|Martial) (Melee|Ranged) Weapons$");
		for (int i = start; i < start + 120; i++) {
			String ln = lines.get(i).strip();
			if (ln.equals("Weapon Properties") || ln.startsWith("Ammunition.")) {
				break;
			}
			if (ln.startsWith("=== PAGE") || match(section, ln) != null || ln.equals("System Reference Document 5.2.1")
					|| Py.isDigits(ln)) {
				continue;
			}
			buf = (buf + " " + ln).strip();
			Matcher m = match(row, buf);
			if (m != null) {
				rows.add(groups(m));
				buf = "";
			}
			if (buf.length() > 300) {
				buf = "";
			}
		}
		return rows;
	}

	private static String[] groups(Matcher m) {
		String[] g = new String[m.groupCount()];
		for (int i = 0; i < g.length; i++) {
			g[i] = m.group(i + 1);
		}
		return g;
	}

	private Map<String, JsonNode> seedByName(JsonNode doc, java.util.function.Predicate<JsonNode> filter) {
		Map<String, JsonNode> out = new LinkedHashMap<>();
		for (JsonNode e : doc.get("entries")) {
			if (filter.test(e)) {
				out.put(norm(e.get("name").asText()), e);
			}
		}
		return out;
	}

	private void weapons() {
		srdWeapons = new LinkedHashMap<>();
		for (String[] r : weaponsTable()) {
			srdWeapons.put(norm(r[0]), r);
		}
		Map<String, JsonNode> seedWeapons = seedByName(items,
				e -> "WEAPON".equals(str(e.path("payload").path("type"))));
		rep("weapons", "SRD rows " + srdWeapons.size() + ", seed weapons " + seedWeapons.size());
		for (String n : minus(srdWeapons.keySet(), seedWeapons.keySet())) {
			rep("weapons", "MISSING in seed: " + srdWeapons.get(n)[0]);
		}
		for (String n : minus(seedWeapons.keySet(), srdWeapons.keySet())) {
			rep("weapons", "EXTRA in seed: " + seedWeapons.get(n).get("name").asText());
		}
		Pattern propPattern = re("^(\\w[\\w-]*)(?: \\((.*)\\))?$");
		Pattern rangePattern = re("Range (\\d+)/(\\d+)");
		Pattern splitPattern = re(",\\s*(?![^()]*\\))");
		for (String n : both(srdWeapons.keySet(), seedWeapons.keySet())) {
			String[] r = srdWeapons.get(n);
			String name = r[0], dice = r[1], dtype = r[2], props = r[3], mastery = r[4], wt = r[5], cost = r[6];
			JsonNode p = seedWeapons.get(n).get("payload");
			JsonNode damage = p.path("damage");
			if (neq(str(damage.path("dice")), dice) || neq(str(damage.path("type")), dtype.toLowerCase())) {
				rep("weapons", name + ": damage seed " + repr(damage) + " vs SRD " + dice + " " + dtype);
			}
			if (neq(str(p.path("mastery")), mastery.toLowerCase())) {
				rep("weapons", name + ": mastery seed " + repr(p.path("mastery")) + " vs SRD " + mastery);
			}
			if (neq(num(p.path("weight_lb")), weightLb(wt))) {
				rep("weapons", name + ": weight seed " + repr(p.path("weight_lb")) + " vs SRD " + wt);
			}
			if (neq(integer(p.path("cost_cp")), costCp(cost))) {
				rep("weapons", name + ": cost seed " + repr(p.path("cost_cp")) + " vs SRD " + cost);
			}
			Set<String> srdProps = new TreeSet<>();
			String versatile = null;
			int[] rng = null;
			String ammo = null;
			for (String part : splitPattern.split(props)) {
				part = part.strip();
				if (part.equals("—") || part.isEmpty()) {
					continue;
				}
				Matcher m = match(propPattern, part);
				String key = m != null ? m.group(1).toLowerCase().replace('-', '_') : part.toLowerCase();
				srdProps.add(key);
				if (key.equals("versatile")) {
					versatile = m.group(2);
				}
				if (key.equals("thrown") || key.equals("ammunition")) {
					Matcher mm = search(rangePattern, part);
					rng = new int[] {Integer.parseInt(mm.group(1)), Integer.parseInt(mm.group(2))};
					if (key.equals("ammunition")) {
						ammo = Py.strip(part.split(";")[1], ')').strip();
					}
				}
			}
			Set<String> seedProps = new TreeSet<>();
			for (JsonNode x : p.path("properties")) {
				seedProps.add(x.asText().toLowerCase().replace('-', '_'));
			}
			if (!seedProps.equals(srdProps)) {
				rep("weapons",
						name + ": properties seed " + repr(sorted(seedProps)) + " vs SRD " + repr(sorted(srdProps)));
			}
			if (versatile != null && neq(str(p.path("versatile")), versatile)) {
				rep("weapons", name + ": versatile seed " + repr(p.path("versatile")) + " vs SRD " + versatile);
			}
			if (rng != null) {
				JsonNode sr = p.path("range");
				if (neq(integer(sr.path("normal")), rng[0]) || neq(integer(sr.path("long")), rng[1])) {
					rep("weapons", name + ": range seed " + repr(sr) + " vs SRD (" + rng[0] + ", " + rng[1] + ")");
				}
			}
			if (ammo != null && !norm(Objects.toString(str(p.path("ammunition")), "")).contains(norm(ammo))) {
				rep("weapons", name + ": ammunition seed " + repr(p.path("ammunition")) + " vs SRD " + ammo);
			}
		}
	}

	// ── 2. Armor ─────────────────────────────────────────────────────────

	private List<String[]> armorTable() {
		int start = lines.indexOf("Armor Armor Class (AC) Strength Stealth Weight Cost");
		List<String[]> rows = new ArrayList<>();
		Pattern row = re(
				"^(.+?) (\\+2|\\d+(?: \\+ Dex modifier(?: \\(max 2\\))?)?) (—|Str \\d+) (—|Disadvantage) (—|[\\d/]+ lb\\.) ([\\d,]+ GP)$");
		for (String ln : slice(lines, start + 1, start + 30)) {
			Matcher m = match(row, ln.strip());
			if (m != null) {
				rows.add(groups(m));
			}
			if (ln.startsWith("=== PAGE")) {
				break;
			}
		}
		return rows;
	}

	private void armor() {
		srdArmor = new LinkedHashMap<>();
		for (String[] r : armorTable()) {
			srdArmor.put(norm(r[0]), r);
		}
		Map<String, JsonNode> seedArmor = seedByName(items, e -> {
			String t = str(e.path("payload").path("type"));
			return "ARMOR".equals(t) || "SHIELD".equals(t);
		});
		rep("armor", "SRD rows " + srdArmor.size() + ", seed armor " + seedArmor.size());
		for (String n : minus(srdArmor.keySet(), seedArmor.keySet())) {
			rep("armor", "MISSING in seed: " + srdArmor.get(n)[0]);
		}
		for (String n : minus(seedArmor.keySet(), srdArmor.keySet())) {
			rep("armor", "EXTRA in seed: " + seedArmor.get(n).get("name").asText());
		}
		for (String n : both(srdArmor.keySet(), seedArmor.keySet())) {
			String[] r = srdArmor.get(n);
			String name = r[0], ac = r[1], strength = r[2], stealth = r[3], wt = r[4], cost = r[5];
			JsonNode p = seedArmor.get(n).get("payload");
			JsonNode a = p.path("armor");
			if (ac.equals("+2")) {
				Integer bonus = a.has("ac_bonus") ? integer(a.get("ac_bonus")) : Integer.valueOf(2);
				if (neq(bonus, 2) || neq(str(a.path("category")), "SHIELD")) {
					rep("armor", name + ": shield seed " + repr(a));
				}
			} else {
				int base = Integer.parseInt(ac.split(" ")[0]);
				String dex = ac.contains("Dex") && !ac.contains("max") ? "FULL"
						: ac.contains("max 2") ? "MAX_2" : "NONE";
				if (neq(integer(a.path("base_ac")), base) || neq(str(a.path("dex_bonus")), dex)) {
					rep("armor", name + ": AC seed base " + repr(a.path("base_ac")) + " dex "
							+ repr(a.path("dex_bonus")) + " vs SRD '" + ac + "'");
				}
			}
			Integer sreq = strength.equals("—") ? null : Integer.parseInt(strength.split(" ")[1]);
			if (neq(integer(a.path("strength_requirement")), sreq)) {
				rep("armor", name + ": strength seed " + repr(a.path("strength_requirement")) + " vs SRD " + strength);
			}
			if (truthy(a.path("stealth_disadvantage")) != stealth.equals("Disadvantage")) {
				rep("armor", name + ": stealth seed " + repr(a.path("stealth_disadvantage")) + " vs SRD " + stealth);
			}
			if (neq(num(p.path("weight_lb")), weightLb(wt))) {
				rep("armor", name + ": weight seed " + repr(p.path("weight_lb")) + " vs SRD " + wt);
			}
			if (neq(integer(p.path("cost_cp")), costCp(cost))) {
				rep("armor", name + ": cost seed " + repr(p.path("cost_cp")) + " vs SRD " + cost);
			}
		}
	}

	// ── 3. Gear / tools / ammunition / foci / mounts / tack ──────────────

	private static final Map<String, String> ALIASES = Map.ofEntries(Map.entry("arrow", "arrows"),
			Map.entry("bolt", "bolts"), Map.entry("bullet firearm", "bullets firearm"),
			Map.entry("bullet sling", "bullets sling"), Map.entry("needle", "needles"),
			Map.entry("arcane focus crystal", "crystal"), Map.entry("arcane focus orb", "orb"),
			Map.entry("arcane focus rod", "rod"), Map.entry("arcane focus staff", "staff also a quarterstaff"),
			Map.entry("arcane focus wand", "wand"), Map.entry("druidic focus sprig of mistletoe", "sprig of mistletoe"),
			Map.entry("druidic focus wooden staff", "wooden staff also a quarterstaff"),
			Map.entry("druidic focus yew wand", "yew wand"), Map.entry("holy symbol amulet", "amulet worn or held"),
			Map.entry("holy symbol emblem", "emblem borne on fabric or a shield"),
			Map.entry("holy symbol reliquary", "reliquary held"), Map.entry("saddle exotic", "exotic"),
			Map.entry("saddle military", "military"), Map.entry("saddle riding", "riding"),
			Map.entry("feed per day", "feed per day"), Map.entry("gaming set dice", "dice"),
			Map.entry("gaming set dragonchess", "dragonchess"), Map.entry("gaming set playing cards", "playing cards"),
			Map.entry("gaming set three dragon ante", "three dragon ante"));

	/** Kind, weight text, cost text and the row key a seed item was matched to. */
	private record Hit(String kind, String wt, String cost, String key) {
	}

	private void gear() {
		int eqStart = firstLineStartingWith("=== PAGE 90 ===");
		int eqEnd = firstLineStartingWith("=== PAGE 104 ===");
		List<String> eq = slice(lines, eqStart, eqEnd);

		Map<String, String[]> gearRows = new LinkedHashMap<>();
		Pattern gearRow = re(
				"^(.+?) (—|[\\d/.,]+ lb\\.|Varies) (—|Varies|[\\d,]+ (?:CP|SP|GP|PP)(?: per (?:day|hour|mile|item))?)$");
		for (String ln : eq) {
			Matcher m = match(gearRow, ln.strip());
			if (m != null && !srdWeapons.containsKey(norm(m.group(1))) && !srdArmor.containsKey(norm(m.group(1)))) {
				gearRows.putIfAbsent(norm(m.group(1)), groups(m));
			}
		}
		Map<String, String[]> toolRows = new LinkedHashMap<>(); // name, cost, weight, ability
		Pattern toolHead = re("^([A-Z][\\w' ]+?) \\((\\d+ (?:CP|SP|GP)|Varies)\\)$");
		Pattern toolAbility = re("Ability: (\\w+)\\s+Weight: (.*)$");
		Pattern anyHead = re("^[A-Z][\\w' ]+ \\(");
		Pattern variants = re("Variants: (.*)$");
		Pattern variant = re("([\\w' -]+?) \\((\\d+ (?:CP|SP|GP))(?:, ([\\d/]+ lb\\.|no weight))?\\)");
		for (int i = 0; i < eq.size(); i++) {
			Matcher m = match(toolHead, eq.get(i).strip());
			if (m != null && i + 1 < eq.size() && eq.get(i + 1).startsWith("Ability:")) {
				Matcher mm = match(toolAbility, eq.get(i + 1).strip());
				String ability = mm != null ? mm.group(1) : null;
				toolRows.put(norm(m.group(1)),
						new String[] {m.group(1), m.group(2), mm != null ? mm.group(2) : null, ability});
				int j = i + 2;
				StringBuilder para = new StringBuilder();
				while (j < eq.size() && j < i + 12 && match(anyHead, eq.get(j).strip()) == null) {
					para.append(' ').append(eq.get(j).strip());
					j++;
				}
				Matcher vm = search(variants, dehyph(para.toString()));
				if (vm != null) {
					Matcher v = variant.matcher(vm.group(1));
					while (v.find()) {
						toolRows.put(norm(v.group(1)), new String[] {v.group(1).strip(), v.group(2),
								v.group(3) != null ? v.group(3) : "—", ability});
					}
				}
			}
		}
		Map<String, String[]> ammoRows = new LinkedHashMap<>();
		Pattern ammoRow = re(
				"^(Arrows|Bolts|Bullets, Firearm|Bullets, Sling|Needles) (\\d+) (\\w+) ([\\d/.]+ lb\\.) (\\d+ (?:CP|SP|GP))$");
		for (String ln : eq) {
			Matcher m = match(ammoRow, ln.strip());
			if (m != null) {
				ammoRows.put(norm(m.group(1)), groups(m));
			}
		}
		Map<String, String[]> mountRows = new LinkedHashMap<>();
		Pattern mountRow = re(
				"^(Camel|Elephant|Horse, Draft|Horse, Riding|Mastiff|Mule|Pony|Warhorse) ([\\d,]+ lb\\.) ([\\d,]+ GP)$");
		for (String ln : eq) {
			Matcher m = match(mountRow, ln.strip());
			if (m != null) {
				mountRows.put(norm(m.group(1)), groups(m));
			}
		}
		Map<String, String[]> packText = new LinkedHashMap<>(); // cost, contents
		Pattern packHead = re("^([A-Z][a-z]+'s Pack) \\((\\d+ GP)\\)$");
		Pattern packContents = re("contains the following items: (.*?)\\.");
		for (int i = 0; i < eq.size(); i++) {
			Matcher m = match(packHead, eq.get(i).strip());
			if (m != null) {
				String para = joinBlock(slice(eq, i + 1, i + 6));
				Matcher mm = search(packContents, para);
				packText.put(norm(m.group(1)), new String[] {m.group(2), mm != null ? mm.group(1) : para});
			}
		}
		Map<String, JsonNode> seedOther = seedByName(items, e -> {
			String t = str(e.path("payload").path("type"));
			return !"WEAPON".equals(t) && !"ARMOR".equals(t) && !"SHIELD".equals(t);
		});
		rep("gear",
				"SRD gear rows " + gearRows.size() + ", tools " + toolRows.size() + ", ammo " + ammoRows.size()
						+ ", mounts " + mountRows.size() + ", packs " + packText.size() + "; seed other items "
						+ seedOther.size());
		Set<String> matched = new HashSet<>();
		Map<String, String> itemNameById = new HashMap<>();
		for (JsonNode x : items.get("entries")) {
			itemNameById.put(x.get("id").asText(), x.get("name").asText());
		}
		Pattern packSplit = re(", (?:and )?|, and ");
		Pattern packItem = re("^(\\d+) (?:flasks of |days of |sheets of )?(.*)$");
		for (Map.Entry<String, JsonNode> entry : seedOther.entrySet()) {
			String n = entry.getKey();
			JsonNode e = entry.getValue();
			JsonNode p = e.get("payload");
			String ename = e.get("name").asText();
			String type = str(p.path("type"));
			List<String> cands = new ArrayList<>(
					List.of(n, ALIASES.getOrDefault(n, n), n + "s", Py.rstrip(n, 's'), norm(ename)));
			if (ename.contains(", ")) {
				String[] ab = ename.split(", ", 2);
				cands.add(norm(ab[1] + " " + ab[0]));
				cands.add(norm(ab[0] + ", " + ab[1]));
			}
			Hit hit = null;
			for (String c : cands) {
				if (gearRows.containsKey(c)) {
					hit = new Hit("gear", gearRows.get(c)[1], gearRows.get(c)[2], c);
				} else if (toolRows.containsKey(c)) {
					hit = new Hit("tool", toolRows.get(c)[2], toolRows.get(c)[1], c);
				} else if (ammoRows.containsKey(c)) {
					hit = new Hit("ammo", ammoRows.get(c)[3], ammoRows.get(c)[4], c);
				} else if (mountRows.containsKey(c)) {
					hit = new Hit("mount", null, mountRows.get(c)[2], c);
				}
				if (hit != null) {
					break;
				}
			}
			if (hit == null) {
				rep("gear", "NO SRD ROW for seed item '" + ename + "' (type " + type + ") — check manually or remove");
				continue;
			}
			matched.add(hit.key());
			String wt = hit.wt(), cost = hit.cost();
			if (wt != null && !wt.equals("Varies") && !wt.equals("—") && weightLb(wt) != null
					&& neq(num(p.path("weight_lb")), weightLb(wt)) && !"PACK".equals(type) && !"MOUNT".equals(type)) {
				rep("gear", ename + ": weight seed " + repr(p.path("weight_lb")) + " vs SRD " + wt);
			}
			if (cost != null && !cost.equals("Varies") && !cost.equals("—") && costCp(cost) != null
					&& neq(integer(p.path("cost_cp")), costCp(cost))) {
				rep("gear", ename + ": cost seed " + repr(p.path("cost_cp")) + " vs SRD " + cost);
			}
			if (hit.kind().equals("ammo")) {
				String[] row = ammoRows.get(hit.key());
				if (neq(integer(p.path("bundle_size")), Integer.parseInt(row[1]))) {
					rep("gear", ename + ": bundle seed " + repr(p.path("bundle_size")) + " vs SRD " + row[1]);
				}
				if (!norm(Objects.toString(str(p.path("container")), "")).contains(norm(row[2]))) {
					rep("gear", ename + ": container seed " + repr(p.path("container")) + " vs SRD " + row[2]);
				}
			}
			if (hit.kind().equals("mount")) {
				int cap = Integer.parseInt(mountRows.get(hit.key())[1].replace(",", "").split(" ")[0]);
				if (neq(integer(p.path("carrying_capacity_lb")), cap)) {
					rep("gear", ename + ": carrying capacity seed " + repr(p.path("carrying_capacity_lb")) + " vs SRD "
							+ cap);
				}
			}
			if (hit.kind().equals("tool") && toolRows.get(hit.key())[3] != null) {
				String ab = Py.head(toolRows.get(hit.key())[3], 3).toUpperCase();
				if (neq(str(p.path("ability")), ab)) {
					rep("gear", ename + ": tool ability seed " + repr(p.path("ability")) + " vs SRD "
							+ toolRows.get(hit.key())[3]);
				}
			}
			if ("PACK".equals(type)) {
				String[] pt = packText.get(n);
				String contents = pt != null ? pt[1] : null;
				if (contents != null) {
					List<String> srdItems = new ArrayList<>();
					for (String part : packSplit.split(contents)) {
						Matcher mm = match(packItem, part.strip());
						int q = mm != null ? Integer.parseInt(mm.group(1)) : 1;
						srdItems.add(canonPackItem(q, norm(mm != null ? mm.group(2) : part)));
					}
					List<String> seedItems = new ArrayList<>();
					for (JsonNode c : p.path("contents")) {
						seedItems.add(canonPackItem(c.get("quantity").asInt(),
								norm(itemNameById.get(c.get("item").asText()))));
					}
					List<String> a = sortedPairs(seedItems), b = sortedPairs(srdItems);
					if (!a.equals(b)) {
						rep("gear", ename + ": contents seed [" + String.join(", ", a) + "] vs SRD ["
								+ String.join(", ", b) + "]");
					}
				}
			}
		}
		Set<String> skip = Set.of("Ammunition", "Arcane Focus", "Druidic Focus", "Holy Symbol", "Stabling per day");
		for (String g : minus(gearRows.keySet(), matched)) {
			if (!skip.contains(gearRows.get(g)[0])) {
				rep("gear-srd-only", "SRD row not in seed: " + tuple(gearRows.get(g)));
			}
		}
		for (String g : minus(toolRows.keySet(), matched)) {
			rep("gear-srd-only", "SRD tool not in seed: " + tuple(toolRows.get(g)));
		}
		for (String g : minus(mountRows.keySet(), matched)) {
			rep("gear-srd-only", "SRD mount not in seed: " + tuple(mountRows.get(g)));
		}
	}

	/**
	 * {@code (quantity, canonical-name)} rendered as a Python tuple so the list sorts like the
	 * original.
	 */
	private static String canonPackItem(int q, String nm) {
		nm = nm.replace("lantern hooded", "hooded lantern").replace("lantern bullseye", "bullseye lantern")
				.replace("clothes fine", "fine clothes").replace("case map or scroll", "map or scroll case");
		if (nm.endsWith("ches")) {
			nm = nm.substring(0, nm.length() - 2);
		}
		if (!nm.equals("rations")) {
			nm = Py.rstrip(nm, 's');
		}
		return String.format("%06d|%s", q, nm);
	}

	private static List<String> sortedPairs(List<String> encoded) {
		return encoded.stream().sorted().map(s -> {
			int bar = s.indexOf('|');
			return "(" + Integer.parseInt(s.substring(0, bar)) + ", " + repr(s.substring(bar + 1)) + ")";
		}).toList();
	}

	private static String tuple(String[] row) {
		return Arrays.stream(row).map(Py::repr).collect(Collectors.joining(", ", "(", ")"));
	}

	private int firstLineStartingWith(String prefix) {
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(prefix)) {
				return i;
			}
		}
		throw new IllegalStateException("no line starts with " + prefix);
	}

	// ── 4. Advancement ───────────────────────────────────────────────────

	private JsonNode entryEndingWith(JsonNode doc, String suffix) {
		for (JsonNode e : doc.get("entries")) {
			if (e.get("id").asText().endsWith(suffix)) {
				return e;
			}
		}
		throw new IllegalStateException("no entry ends with " + suffix);
	}

	private void advancement() {
		adv = entryEndingWith(advancement, "character-advancement").get("payload").get("levels");
		int start = lines.indexOf("Level Experience Points Proficiency Bonus");
		for (String ln : slice(lines, start + 1, start + 21)) {
			String[] t = ln.strip().split("\\s+");
			String lvl = t[0], xp = t[1], pb = t[2];
			JsonNode s = adv.get(Integer.parseInt(lvl) - 1);
			if (neq(integer(s.path("xp")), Integer.parseInt(xp.replace(",", "")))
					|| neq(integer(s.path("proficiency_bonus")), Integer.parseInt(pb))) {
				rep("advancement", "level " + lvl + ": seed " + repr(s) + " vs SRD xp " + xp + " pb " + pb);
			}
		}
		start = lines.indexOf("Experience Points by Challenge Rating");
		Map<String, String> srdCr = new LinkedHashMap<>();
		Pattern crCell = re("(?:^|\\s)(\\d+/\\d+|\\d+) ([\\d,]+(?: or \\d+)?)(?=\\s|$)");
		for (String ln : slice(lines, start + 2, start + 22)) {
			if (ln.startsWith("Proficiency Bonus")) {
				break;
			}
			Matcher m = crCell.matcher(ln.replace("11, 50 0", "11,500"));
			while (m.find()) {
				srdCr.putIfAbsent(m.group(1), m.group(2));
			}
		}
		JsonNode xpcr = entryEndingWith(advancement, "xp-by-cr");
		for (JsonNode row : xpcr.get("payload").get("rows")) {
			String cr = str(row.get("cr"));
			String sv = srdCr.get(cr);
			if (sv == null) {
				List<String> keys = sorted(srdCr.keySet());
				rep("xp-by-cr", "CR " + cr + " not found in SRD table (parsed "
						+ repr(keys.subList(0, Math.min(5, keys.size()))) + "...)");
				continue;
			}
			String[] alt = sv.split(" or ");
			int svNum = Integer.parseInt(alt[alt.length - 1].replace(",", ""));
			int xp = row.get("xp").asInt();
			if (cr.equals("0")) {
				if (xp != 0 && xp != 10) {
					rep("xp-by-cr", "CR 0: seed " + xp + " vs SRD " + sv);
				}
			} else if (xp != svNum) {
				rep("xp-by-cr", "CR " + cr + ": seed " + xp + " vs SRD " + sv);
			}
			double crv = cr.contains("/") ? Double.parseDouble(cr.split("/")[0]) / Double.parseDouble(cr.split("/")[1])
					: Integer.parseInt(cr);
			int expPb = crv <= 4 ? 2
					: crv <= 8 ? 3 : crv <= 12 ? 4 : crv <= 16 ? 5 : crv <= 20 ? 6 : crv <= 24 ? 7 : crv <= 28 ? 8 : 9;
			if (neq(integer(row.path("proficiency_bonus")), expPb)) {
				rep("xp-by-cr", "CR " + cr + ": PB seed " + repr(row.path("proficiency_bonus")) + " vs SRD " + expPb);
			}
		}
		JsonNode gen = entryEndingWith(advancement, "ability-generation").get("payload");
		int pcStart = lines.indexOf("Ability Score Point Costs");
		String pcText = slice(lines, pcStart, pcStart + 14).stream().map(String::strip)
				.collect(Collectors.joining(" "));
		Map<String, String> srdCosts = new HashMap<>();
		Matcher m = re("(?:^|\\s)(\\d{1,2}) (\\d)(?=\\s|$)").matcher(pcText);
		while (m.find()) {
			srdCosts.put(m.group(1), m.group(2));
		}
		JsonNode cost = gen.path("point_buy").path("cost");
		for (var it = cost.fields(); it.hasNext();) {
			var f = it.next();
			if (neq(srdCosts.get(f.getKey()), f.getValue().asText())) {
				rep("advancement",
						"point cost " + f.getKey() + ": seed " + f.getValue() + " vs SRD " + srdCosts.get(f.getKey()));
			}
		}
		String around = dehyph(
				slice(lines, pcStart - 40, pcStart + 10).stream().map(String::strip).collect(Collectors.joining(" ")));
		Matcher sa = search(re("Standard Array\\. Use the following six scores for your ability scores: ([\\d, ]+)\\."),
				around);
		if (sa != null) {
			List<Integer> srdArray = Arrays.stream(sa.group(1).split(", ")).map(Integer::parseInt).toList();
			if (!srdArray.equals(Py.ints(gen.path("standard_array")))) {
				rep("advancement",
						"standard array seed " + repr(gen.path("standard_array")) + " vs SRD " + sa.group(1));
			}
		}
	}

	// ── 5. Classes ───────────────────────────────────────────────────────

	private static final Map<String, String> SKILL_ALIAS = Map.of("sleight of hand", "sleight-of-hand",
			"animal handling", "animal-handling");
	private static final int[][] FULL = {{2}, {3}, {4, 2}, {4, 3}, {4, 3, 2}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 2},
			{4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2, 1}, {4, 3, 3, 3, 2, 1}, {4, 3, 3, 3, 2, 1, 1},
			{4, 3, 3, 3, 2, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1, 1},
			{4, 3, 3, 3, 3, 1, 1, 1, 1}, {4, 3, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 3, 2, 2, 1, 1}};
	private static final int[][] HALF = {{2}, {2}, {3}, {3}, {4, 2}, {4, 2}, {4, 3}, {4, 3}, {4, 3, 2}, {4, 3, 2},
			{4, 3, 3}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 1}, {4, 3, 3, 2}, {4, 3, 3, 2}, {4, 3, 3, 3, 1},
			{4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2}};

	private String classBlock(String name) {
		int start = lines.indexOf("Core " + name + " Traits");
		if (start < 0) {
			return null;
		}
		int end = start + 1;
		while (!lines.get(end).startsWith("Becoming a ")) {
			end++;
		}
		return dehyph(slice(lines, start + 1, end).stream().map(String::strip).collect(Collectors.joining(" ")));
	}

	/** Level → (proficiency bonus, feature text, numeric columns). */
	private record FeatureRow(int pb, String features, List<Integer> nums) {
	}

	private Map<Integer, FeatureRow> featuresTable(String name) {
		int start = lines.indexOf(name + " Features");
		Map<Integer, FeatureRow> rows = new LinkedHashMap<>();
		String buf = "";
		Pattern rowStart = re("^(\\d{1,2}) \\+\\d ");
		Pattern row = re("^(\\d{1,2}) \\+(\\d) (.*?)((?: (?:\\d+|—|\\d+d\\d+|\\+\\d+ ft\\.|\\+\\d+))+)$");
		Pattern numCell = re("^(\\d+|—)$");
		for (String ln : slice(lines, start + 1, start + 70)) {
			ln = ln.strip();
			if (ln.startsWith("=== PAGE") || ln.equals("System Reference Document 5.2.1")) {
				continue;
			}
			if (match(rowStart, ln) != null && Integer.parseInt(ln.split(" ")[0]) == rows.size() + 1) {
				buf = ln;
			} else if (!buf.isEmpty()) {
				buf = buf + " " + ln;
			} else {
				continue;
			}
			Matcher m = match(row, buf);
			if (m != null && Integer.parseInt(m.group(1)) == rows.size() + 1) {
				List<Integer> nums = new ArrayList<>();
				for (String x : m.group(4).strip().split(" ")) {
					if (match(numCell, x) != null) {
						nums.add(x.equals("—") ? 0 : Integer.parseInt(x));
					}
				}
				rows.put(Integer.parseInt(m.group(1)), new FeatureRow(Integer.parseInt(m.group(2)), m.group(3), nums));
				buf = "";
			}
			if (rows.size() == 20) {
				break;
			}
		}
		return rows;
	}

	private void classes() {
		for (JsonNode e : classes.get("entries")) {
			String name = e.get("name").asText();
			JsonNode p = e.get("payload");
			String block = classBlock(name);
			if (block == null) {
				rep("classes", name + ": no Core traits block found");
				continue;
			}
			Matcher m = search(re("Hit Point Die D(\\d+)"), block);
			if (m != null && neq(integer(p.path("hit_die")), Integer.parseInt(m.group(1)))) {
				rep("classes", name + ": hit die seed " + repr(p.path("hit_die")) + " vs SRD D" + m.group(1));
			}
			m = search(re("Saving Throw Proficiencies (\\w+) and (\\w+)"), block);
			if (m != null) {
				List<String> srdSaves = sorted(
						List.of(Py.head(m.group(1), 3).toUpperCase(), Py.head(m.group(2), 3).toUpperCase()));
				if (!sorted(Py.strings(p.path("saving_throws"))).equals(srdSaves)) {
					rep("classes",
							name + ": saves seed " + repr(p.path("saving_throws")) + " vs SRD " + repr(srdSaves));
				}
			}
			m = search(re("Skill Proficiencies Choose (\\d+): (.*?)(?: Weapon Proficiencies)"), block);
			if (m != null) {
				int cnt = Integer.parseInt(m.group(1));
				String raw = m.group(2);
				List<String> opts = new ArrayList<>();
				for (String x : raw.split(", ")) {
					if (!x.strip().isEmpty()) {
						String k = norm(x.replace("or ", ""));
						opts.add(SKILL_ALIAS.getOrDefault(k, k).replace(' ', '-'));
					}
				}
				Collections.sort(opts);
				List<String> seedOpts = new ArrayList<>();
				for (JsonNode o : p.path("skill_choices").path("options")) {
					String s = o.asText();
					seedOpts.add(s.substring(s.lastIndexOf('/') + 1));
				}
				Collections.sort(seedOpts);
				Integer seedCount = integer(p.path("skill_choices").path("count"));
				if (raw.toLowerCase().contains("any")) {
					if (seedOpts.size() != 18 || neq(cnt, seedCount)) {
						rep("classes", name + ": skills should be any " + cnt + " (seed " + seedCount + " of "
								+ seedOpts.size() + ")");
					}
				} else if (!opts.equals(seedOpts) || neq(cnt, seedCount)) {
					rep("classes", name + ": skills seed " + seedCount + " of " + repr(seedOpts) + " vs SRD " + cnt
							+ " of " + repr(opts));
				}
			}
			m = search(re("Starting Equipment (.*)$"), block);
			String eqtext = m != null ? m.group(1) : "?";
			JsonNode se = p.path("starting_equipment");
			if (m != null) {
				List<String> gold = findAll(re("(\\d+) GP"), eqtext);
				List<String> seedGold = new ArrayList<>();
				List<String> keys = new ArrayList<>();
				se.fieldNames().forEachRemaining(keys::add);
				Collections.sort(keys);
				for (String k : keys) {
					seedGold.add(repr(se.get(k).path("gold_gp")));
				}
				if (!gold.equals(seedGold)) {
					rep("classes", name + ": starting gold seed " + repr(seedGold) + " vs SRD " + repr(gold));
				}
			}
			Map<Integer, FeatureRow> rows = featuresTable(name);
			if (rows.size() != 20) {
				rep("classes", name + ": features table parsed " + rows.size() + " rows");
				continue;
			}
			List<Integer> asi = new ArrayList<>();
			rows.forEach((lvl, r) -> {
				if (r.features().contains("Ability Score Improvement")) {
					asi.add(lvl);
				}
			});
			if (!asi.equals(Py.ints(p.path("asi_levels")))) {
				rep("classes", name + ": ASI levels seed " + repr(p.path("asi_levels")) + " vs SRD " + repr(asi));
			}
			List<Integer> pbs = new ArrayList<>();
			List<Integer> expPb = new ArrayList<>();
			for (int l = 1; l <= 20; l++) {
				pbs.add(rows.get(l).pb());
				expPb.add(adv.get(l - 1).get("proficiency_bonus").asInt());
			}
			if (!pbs.equals(expPb)) {
				rep("classes", name + ": proficiency column " + repr(pbs));
			}
			JsonNode sc = p.path("spellcasting");
			if (truthy(sc)) {
				String progression = str(sc.path("progression"));
				for (Map.Entry<Integer, FeatureRow> row : rows.entrySet()) {
					int lvl = row.getKey();
					List<Integer> nums = row.getValue().nums();
					int n = nums.size();
					int cant, prep;
					if ("PACT".equals(progression)) {
						cant = nums.get(n - 4);
						prep = nums.get(n - 3);
						int slots = nums.get(n - 2), slotLvl = nums.get(n - 1);
						int expSlots = lvl >= 17 ? 4 : lvl >= 11 ? 3 : lvl >= 2 ? 2 : 1;
						int expLvl = lvl >= 9 ? 5 : lvl >= 7 ? 4 : lvl >= 5 ? 3 : lvl >= 3 ? 2 : 1;
						if (slots != expSlots || slotLvl != expLvl) {
							rep("classes", name + " L" + lvl + ": pact slots SRD " + slots + "@" + slotLvl
									+ " vs engine " + expSlots + "@" + expLvl);
						}
					} else if ("HALF".equals(progression)) {
						cant = 0;
						prep = nums.get(n - 6);
						List<Integer> slots = nums.subList(n - 5, n);
						List<Integer> exp = padded(HALF[lvl - 1], 5);
						if (!slots.equals(exp)) {
							rep("classes",
									name + " L" + lvl + ": slots SRD " + repr(slots) + " vs engine " + repr(exp));
						}
					} else {
						cant = nums.get(n - 11);
						prep = nums.get(n - 10);
						List<Integer> slots = nums.subList(n - 9, n);
						List<Integer> exp = padded(FULL[lvl - 1], 9);
						if (!slots.equals(exp)) {
							rep("classes",
									name + " L" + lvl + ": slots SRD " + repr(slots) + " vs engine " + repr(exp));
						}
					}
					if (neq(integer(sc.path("cantrips_known").path(lvl - 1)), cant)) {
						rep("classes", name + " L" + lvl + ": cantrips seed "
								+ repr(sc.path("cantrips_known").path(lvl - 1)) + " vs SRD " + cant);
					}
					if (neq(integer(sc.path("prepared").path(lvl - 1)), prep)) {
						rep("classes", name + " L" + lvl + ": prepared seed " + repr(sc.path("prepared").path(lvl - 1))
								+ " vs SRD " + prep);
					}
				}
			}
			rep("classes-table", name + ": L1 " + rows.get(1).features() + " " + repr(rows.get(1).nums()) + " | L5 "
					+ repr(rows.get(5).nums()) + " | L20 " + repr(rows.get(20).nums()));
			rep("classes-equipment", name + ": SRD '" + eqtext + "' | seed " + repr(se));
		}
	}

	private static List<Integer> padded(int[] slots, int width) {
		List<Integer> out = new ArrayList<>();
		for (int s : slots) {
			out.add(s);
		}
		while (out.size() < width) {
			out.add(0);
		}
		return out;
	}

	// ── 6. Species ───────────────────────────────────────────────────────

	private void species() {
		int spStart = lines.indexOf("Species Descriptions");
		Pattern traitPattern = re("\\s([A-Z][\\w' ]+?)\\. ");
		for (JsonNode e : species.get("entries")) {
			String name = e.get("name").asText();
			JsonNode p = e.get("payload");
			int idx = -1;
			for (int i = spStart; i < spStart + 1500 && i + 1 < lines.size(); i++) {
				if (lines.get(i).strip().equals(name) && lines.get(i + 1).startsWith("Creature Type")) {
					idx = i;
					break;
				}
			}
			if (idx < 0) {
				rep("species", name + ": no entry found");
				continue;
			}
			String block = slice(lines, idx + 1, idx + 5).stream().map(String::strip).collect(Collectors.joining(" "));
			Matcher m = search(re("Size: (\\w+)(?: or (\\w+))?"), block);
			Matcher ms = search(re("Speed: (\\d+) feet"), block);
			Matcher mt = search(re("Creature Type: (\\w+)"), block);
			String srdSize = m.group(1) + (m.group(2) != null ? " or " + m.group(2) : "");
			if (!norm(str(p.path("size"))).equals(norm(srdSize))) {
				rep("species", name + ": size seed " + str(p.path("size")) + " vs SRD " + srdSize);
			}
			if (ms != null && neq(integer(p.path("speed")), Integer.parseInt(ms.group(1)))) {
				rep("species", name + ": speed seed " + repr(p.path("speed")) + " vs SRD " + ms.group(1));
			}
			if (mt != null && neq(str(p.path("creature_type")), mt.group(1))) {
				rep("species", name + ": type seed " + repr(p.path("creature_type")) + " vs SRD " + mt.group(1));
			}
			String traitText = slice(lines, idx + 5, idx + 45).stream().map(String::strip)
					.collect(Collectors.joining(" "));
			List<String> traits = findAll(traitPattern, traitText).stream().filter(t -> t.length() < 30).limit(8)
					.toList();
			List<String> keys = new ArrayList<>();
			p.fieldNames().forEachRemaining(keys::add);
			Collections.sort(keys);
			rep("species-traits", name + ": SRD traits " + repr(traits) + " | seed keys " + repr(keys));
		}
	}

	// ── 7. Creatures ─────────────────────────────────────────────────────

	private static final Pattern SIZES = re("^(Tiny|Small|Medium|Large|Huge|Gargantuan)");
	// Group 10 (the damage dice) is absent for flat damage ("Hit: 1 Piercing damage"); the seed then records the flat number.
	private static final String ATTACK = "(?: \\([^)]*\\))?\\. (Melee|Ranged|Melee or Ranged) Attack Roll: \\+(\\d+)(?: to hit)?(?: \\([^)]*\\))?, (?:reach (\\d+) (?:ft|feet)|range (\\d+)(?:/(\\d+))? (?:ft|feet)|reach (\\d+) (?:ft|feet)\\.? or range (\\d+)(?:/(\\d+))? (?:ft|feet))\\.? Hit: (?:(\\d+)(?: \\(([^)]+)\\))? (\\w+) damage)?";
	private static final Set<String> CONDS = Set.of("blinded", "charmed", "deafened", "exhaustion", "frightened",
			"grappled", "incapacitated", "invisible", "paralyzed", "petrified", "poisoned", "prone", "restrained",
			"stunned", "unconscious");

	private List<String> statBlock(String name) {
		for (int i = 0; i + 1 < lines.size(); i++) {
			if (lines.get(i).strip().equals(name) && match(SIZES, lines.get(i + 1).strip()) != null) {
				int end = Math.min(i + 60, lines.size());
				for (int j = i + 2; j < Math.min(i + 90, lines.size()); j++) {
					String prev = lines.get(j - 1).strip();
					if (match(SIZES, lines.get(j).strip()) != null && !lines.get(j - 1).startsWith("===")
							&& !prev.isEmpty() && Character.isUpperCase(prev.charAt(0))
							&& prev.split("\\s+").length <= 4) {
						end = j;
						break;
					}
				}
				return slice(lines, i + 1, end - 1);
			}
		}
		return null;
	}

	private void creatures() {
		Pattern attack = re(ATTACK);
		for (JsonNode e : creatures.get("entries")) {
			String name = e.get("name").asText();
			JsonNode p = e.get("payload");
			List<String> blk = statBlock(name);
			if (blk == null) {
				rep("creatures", name + ": stat block not found");
				continue;
			}
			String text = joinBlock(blk);
			Matcher m = search(re("AC (\\d+)"), text);
			if (m != null && neq(integer(p.path("ac")), Integer.parseInt(m.group(1)))) {
				rep("creatures", name + ": AC seed " + repr(p.path("ac")) + " vs SRD " + m.group(1));
			}
			m = search(re("HP (\\d+) \\(([^)]+)\\)"), text);
			JsonNode hp = p.path("hp");
			if (m != null && (neq(integer(hp.path("average")), Integer.parseInt(m.group(1))) || neq(
					Objects.toString(str(hp.path("dice")), "").replace(" ", ""), BuildCreatures.dice(m.group(2))))) {
				rep("creatures", name + ": HP seed " + repr(hp) + " vs SRD " + m.group(1) + " (" + m.group(2) + ")");
			}
			m = search(re("Speed (.*?)(?= MOD)"), text);
			if (m != null) {
				Map<String, Integer> srdSpeed = new LinkedHashMap<>();
				for (String part : m.group(1).split(",")) {
					Matcher mm = match(re("\\s*(?:(\\w+) )?(\\d+) ft"), part);
					if (mm != null) {
						srdSpeed.put((mm.group(1) != null ? mm.group(1) : "walk").toLowerCase(),
								Integer.parseInt(mm.group(2)));
					}
				}
				Map<String, Integer> seedSpeed = new LinkedHashMap<>();
				p.path("speed").fields()
						.forEachRemaining(f -> seedSpeed.put(f.getKey().toLowerCase(), integer(f.getValue())));
				if (!srdSpeed.equals(seedSpeed)) {
					rep("creatures", name + ": speed seed " + repr(p.path("speed")) + " vs SRD " + repr(srdSpeed));
				}
			}
			Matcher ab = re("\\b(Str|Dex|Con|Int|WIS|Wis|Cha) (\\d+) ([+-]\\d+) ([+-]?\\d+)").matcher(text);
			while (ab.find()) {
				String a = ab.group(1).toUpperCase();
				int score = Integer.parseInt(ab.group(2));
				int save = Integer.parseInt(ab.group(4));
				if (neq(integer(p.path("abilities").path(a)), score)) {
					rep("creatures",
							name + ": " + a + " seed " + repr(p.path("abilities").path(a)) + " vs SRD " + score);
				}
				JsonNode saves = p.path("saves");
				Integer seedSave = saves.has(a) ? integer(saves.get(a)) : Integer.valueOf(Math.floorDiv(score - 10, 2));
				if (neq(seedSave, save)) {
					rep("creatures",
							name + ": " + a + " save seed " + seedSave + " vs SRD " + save + " (add to saves)");
				}
			}
			// Four blocks (three metallic wyrmlings, Young White Dragon) print "700 XP" instead of "XP 700".
			m = search(re("CR ([\\d/]+) \\((?:XP ([\\d,]+)|([\\d,]+) XP)(?:,? or [\\d ,]+ in lair)?; PB \\+(\\d)\\)"),
					text);
			if (m == null) {
				rep("creatures", name + ": CR line unparsed");
			} else if (neq(str(p.path("cr")), m.group(1))
					|| neq(integer(p.path("xp_value")),
							Integer.parseInt((m.group(2) != null ? m.group(2) : m.group(3)).replace(",", "")))
					|| neq(integer(p.path("proficiency_bonus")), Integer.parseInt(m.group(4)))) {
				rep("creatures", name + ": CR/XP/PB seed " + str(p.path("cr")) + "/" + str(p.path("xp_value")) + "/"
						+ str(p.path("proficiency_bonus")) + " vs SRD " + tuple(groups(m)));
			}
			m = search(re("Skills (.*?)(?= Senses| Resistances| Immunities| Vulnerabilities| Gear| Languages| CR)"),
					text);
			Map<String, Integer> srdSkills = new LinkedHashMap<>();
			if (m != null) {
				Matcher sk = re("(\\w+) \\+(\\d+)").matcher(m.group(1));
				while (sk.find()) {
					srdSkills.put(sk.group(1), Integer.parseInt(sk.group(2)));
				}
			}
			Map<String, Integer> seedSkills = new LinkedHashMap<>();
			p.path("skills").fields().forEachRemaining(f -> seedSkills.put(f.getKey(), integer(f.getValue())));
			if (!srdSkills.equals(seedSkills)) {
				rep("creatures", name + ": skills seed " + repr(p.path("skills")) + " vs SRD " + repr(srdSkills));
			}
			for (String[] kl : new String[][] {{"Resistances", "damage_resistances"},
					{"Immunities", "damage_immunities"}, {"Vulnerabilities", "damage_vulnerabilities"}}) {
				String key = kl[0], label = kl[1];
				Matcher mm = search(
						re(key + " (.*?)(?= Senses| Gear| Languages| CR| Resistances| Immunities| Vulnerabilities)"),
						text);
				List<String> srdAll = new ArrayList<>();
				if (mm != null) {
					for (String x : mm.group(1).split("[,;]")) {
						srdAll.add(x.strip().toLowerCase());
					}
				}
				// A parenthetical qualifier ("Charmed (with Mind Blank)") does not change what the token is.
				java.util.function.Predicate<String> isCond = x -> CONDS.contains(x.replaceAll(" \\(.*\\)$", ""));
				List<String> srdV = sorted(srdAll.stream().filter(x -> !isCond.test(x)).toList());
				List<String> srdC = sorted(srdAll.stream().filter(isCond).toList());
				// The seed keeps unclassified tokens (not a damage type, not a condition) in <label>_notes.
				List<String> seedV = sorted(java.util.stream.Stream
						.concat(Py.strings(p.path(label)).stream(), Py.strings(p.path(label + "_notes")).stream())
						.map(String::toLowerCase).toList());
				if (!srdV.equals(seedV)) {
					rep("creatures", name + ": " + label + " seed " + repr(seedV) + " vs SRD " + repr(srdV));
				}
				if (key.equals("Immunities")) {
					List<String> seedC = sorted(
							Py.strings(p.path("condition_immunities")).stream().map(String::toLowerCase).toList());
					if (!srdC.equals(seedC)) {
						rep("creatures", name + ": condition_immunities seed " + repr(seedC) + " vs SRD " + repr(srdC));
					}
				}
			}
			m = search(re("Senses (.*?)(?= Languages| CR)"), text);
			List<String> senses = Py.strings(p.path("senses"));
			if (m != null) {
				Matcher srdPp = search(re("Passive Perception (\\d+)"), m.group(1));
				String seedPp = null;
				for (String s : senses) {
					if (s.contains("Passive")) {
						Matcher d = search(re("(\\d+)"), s);
						seedPp = d != null ? d.group(1) : null;
						break;
					}
				}
				if (srdPp != null && neq(seedPp, srdPp.group(1))) {
					rep("creatures", name + ": passive perception seed " + seedPp + " vs SRD " + srdPp.group(1));
				}
				for (String sense : List.of("Darkvision", "Blindsight", "Tremorsense", "Truesight")) {
					Matcher ms = search(re(sense + " (\\d+) ft"), m.group(1));
					if (ms != null && senses.stream()
							.noneMatch(s -> s.toLowerCase().contains(sense.toLowerCase()) && s.contains(ms.group(1)))) {
						rep("creatures",
								name + ": sense '" + ms.group() + "' missing in seed " + repr(p.path("senses")));
					}
				}
			}
			for (JsonNode a : p.path("actions")) {
				String aname = a.get("name").asText();
				String kind = Objects.toString(str(a.path("kind")), "");
				Matcher ma = search(re(Pattern.quote(aname) + ATTACK), text);
				if (ma == null) {
					if (kind.endsWith("ATTACK")) {
						rep("creatures", name + ": action '" + aname + "' not found as an attack in SRD text");
					}
					continue;
				}
				int bonus = Integer.parseInt(ma.group(2));
				// The Roper's Tentacle has no damage on its Hit line; dashes in dice come in every typographical variant.
				String dice = ma.group(10) != null ? BuildCreatures.dice(ma.group(10)) : ma.group(9);
				String dtype = ma.group(11) != null ? ma.group(11).toLowerCase() : null;
				if (neq(integer(a.path("attack_bonus")), bonus)) {
					rep("creatures", name + " " + aname + ": attack bonus seed " + repr(a.path("attack_bonus"))
							+ " vs SRD +" + bonus);
				}
				JsonNode sd = a.path("damage").path(0);
				if (dtype != null && (!Objects.toString(str(sd.path("dice")), "").replace(" ", "").equals(dice)
						|| neq(str(sd.path("type")), dtype))) {
					rep("creatures",
							name + " " + aname + ": damage seed " + repr(sd) + " vs SRD " + dice + " " + dtype);
				}
				String reach = ma.group(3) != null ? ma.group(3) : ma.group(6);
				String rn = ma.group(4) != null ? ma.group(4) : ma.group(7);
				String rl = ma.group(5) != null ? ma.group(5) : ma.group(8);
				if (reach != null && neq(integer(a.path("reach")), Integer.parseInt(reach))) {
					rep("creatures", name + " " + aname + ": reach seed " + repr(a.path("reach")) + " vs SRD " + reach);
				}
				if (rn != null && neq(integer(a.path("range").path("normal")), Integer.parseInt(rn))) {
					rep("creatures",
							name + " " + aname + ": range seed " + repr(a.path("range")) + " vs SRD " + rn + "/" + rl);
				}
				Matcher extra = search(
						re(Pattern.quote(aname) + ATTACK + "(?: plus (\\d+) \\(([^)]+)\\) (\\w+) damage)?"), text);
				if (extra != null && extra.group(12) != null) {
					JsonNode dmg = a.path("damage");
					if (dmg.size() < 2 || !Objects.toString(str(dmg.get(1).path("dice")), "").replace(" ", "")
							.equals(extra.group(13).replace(" ", ""))) {
						rep("creatures", name + " " + aname + ": SRD adds 'plus " + extra.group(12) + " ("
								+ extra.group(13) + ") " + extra.group(14) + "' — seed damage " + repr(dmg));
					}
				}
			}
			List<String> srdActions = findAll(re(
					"(?:^|\\s)((?:Actions )?[A-Z][\\w' ]+?)(?: \\([^)]*\\))?\\. (?:Melee|Ranged|Melee or Ranged) Attack Roll"),
					text).stream().map(n -> n.replace("Actions ", "").strip()).toList();
			List<String> seedNames = new ArrayList<>();
			for (JsonNode a : p.path("actions")) {
				if (Objects.toString(str(a.path("kind")), "").endsWith("ATTACK")) {
					seedNames.add(a.get("name").asText());
				}
			}
			for (String n : srdActions) {
				if (!seedNames.contains(n)) {
					rep("creatures", name + ": SRD attack '" + n + "' missing in seed actions " + repr(seedNames));
				}
			}
			Matcher mm = search(re("Multiattack\\. (.*?)(?= [A-Z][\\w' ]+\\. (?:Melee|Ranged))"), text);
			if (mm != null && !truthy(p.path("multiattack"))) {
				rep("creatures", name + ": SRD has Multiattack '" + mm.group(1) + "', seed has none");
			} else if (mm != null) {
				rep("creatures-info",
						name + ": Multiattack SRD '" + mm.group(1) + "' | seed " + repr(p.path("multiattack")));
			}
			if (text.contains("Traits")) {
				String tsec = text.split("Actions")[0];
				int t = tsec.lastIndexOf("Traits");
				tsec = t >= 0 ? tsec.substring(t + "Traits".length()) : tsec;
				List<String> traits = findAll(re("(?:^|\\s)([A-Z][\\w' ]+?)\\. (?=[A-Z])"), tsec);
				List<String> seedTraits = new ArrayList<>();
				for (JsonNode tr : p.path("traits")) {
					seedTraits.add(str(tr.path("name")));
				}
				rep("creatures-info", name + ": SRD traits " + repr(traits) + " | seed traits " + repr(seedTraits));
			}
		}
	}

	// ── 8. Spells ────────────────────────────────────────────────────────

	private static final List<String> CLASSES = List.of("bard", "cleric", "druid", "paladin", "ranger", "sorcerer",
			"warlock", "wizard");
	private static final Pattern HEAD = re("^(?:Level (\\d+) (\\w+)|(\\w+) Cantrip) \\((.*)\\)$");
	private static final Pattern HEAD_LOWER = re("^(level \\d+ \\w+|\\w+ cantrip) \\(");

	private List<String> spellBlock(List<String> lowerLines, String name) {
		String n = name.toLowerCase();
		for (int i = 0; i + 1 < lowerLines.size(); i++) {
			if (lowerLines.get(i).equals(n) && match(HEAD_LOWER, lowerLines.get(i + 1)) != null) {
				int end = i + 2;
				while (end < lines.size() && end - i < 150) {
					if (match(HEAD_LOWER, lowerLines.get(end)) != null && !lowerLines.get(end - 1).startsWith("===")) {
						break;
					}
					end++;
				}
				return slice(lines, i + 1, end - 1);
			}
		}
		return null;
	}

	private static String fieldLine(List<String> blk, String label) {
		for (int k = 0; k < blk.size(); k++) {
			String l = blk.get(k).strip();
			if (l.startsWith(label + ":")) {
				String v = l.substring(label.length() + 1).strip();
				if (k + 1 < blk.size()) {
					String next = blk.get(k + 1).strip();
					if (!next.isEmpty() && Character.isLowerCase(next.charAt(0)) && !next.startsWith("range:")
							&& !next.startsWith("components:") && !next.startsWith("duration:")) {
						v += " " + next;
					}
				}
				return dehyph(v);
			}
		}
		return null;
	}

	private void spells() {
		List<String> lowerLines = lines.stream().map(l -> l.strip().toLowerCase()).toList();
		for (JsonNode e : spells.get("entries")) {
			String name = e.get("name").asText();
			JsonNode p = e.get("payload");
			List<String> blk = spellBlock(lowerLines, name);
			if (blk == null) {
				rep("spells", name + ": description not found");
				continue;
			}
			String head = blk.get(0).strip();
			int k = 1;
			while (!head.contains(")") && k < blk.size()) {
				head += " " + blk.get(k).strip();
				k++;
			}
			List<String> rest = new ArrayList<>();
			rest.add(head);
			rest.addAll(slice(blk, k, blk.size()));
			blk = rest;
			Matcher m = match(HEAD, head);
			if (m == null) {
				rep("spells", name + ": unparsable header '" + head + "'");
				continue;
			}
			int level = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
			String school = m.group(2) != null ? m.group(2) : m.group(3);
			List<String> cls = sorted(Arrays.stream(m.group(4).split(",")).map(x -> x.strip().toLowerCase()).toList());
			if (neq(integer(p.path("level")), level)) {
				rep("spells", name + ": level seed " + repr(p.path("level")) + " vs SRD " + level);
			}
			if (neq(str(p.path("school")), school)) {
				rep("spells", name + ": school seed " + str(p.path("school")) + " vs SRD " + school);
			}
			List<String> seedClasses = sorted(Py.strings(p.path("classes")));
			if (!seedClasses.equals(cls)) {
				rep("spells", name + ": classes seed " + repr(seedClasses) + " vs SRD " + repr(cls));
			}
			String ct = fieldLine(blk, "Casting Time");
			String rg = fieldLine(blk, "Range");
			String comp = fieldLine(blk, "Components");
			String dur = fieldLine(blk, "Duration");
			String castingTime = Objects.toString(str(p.path("casting_time")), "");
			if (ct != null) {
				boolean ritual = ct.contains("Ritual");
				String ctMain = ct.split(",")[0].replaceAll("\\([^)]*\\)", "").replace("or Ritual", "").strip()
						.replaceAll("\\s+", " ");
				if (!norm(ctMain).replace("1 ", "").equals(norm(castingTime).replace("1 ", ""))) {
					rep("spells", name + ": casting time seed '" + castingTime + "' vs SRD '" + ct + "'");
				}
				if (truthy(p.path("ritual")) != ritual) {
					rep("spells", name + ": ritual seed " + repr(p.path("ritual")) + " vs SRD " + repr(ritual));
				}
			}
			String range = Objects.toString(str(p.path("range")), "");
			if (rg != null && !norm(rg).equals(norm(range)) && !norm(rg).equals(norm(range.split(" \\(")[0]))) {
				rep("spells", name + ": range seed '" + range + "' vs SRD '" + rg + "'");
			}
			if (comp != null) {
				List<String> letters = findAll(re("\\b([VSM])\\b"), comp.split("\\(")[0]);
				if (!letters.equals(Py.strings(p.path("components")))) {
					rep("spells", name + ": components seed " + repr(p.path("components")) + " vs SRD " + comp);
				}
				Matcher mat = search(re("M \\((.*)\\)"), comp);
				if (mat != null && !truthy(p.path("material"))) {
					rep("spells-material", name + ": SRD material '" + mat.group(1) + "' missing in seed");
				}
			}
			if (dur != null) {
				boolean conc = dur.startsWith("Concentration");
				if (truthy(p.path("concentration")) != conc) {
					rep("spells",
							name + ": concentration seed " + repr(p.path("concentration")) + " vs SRD '" + dur + "'");
				}
				String d = dur.replace("Concentration, ", "").replace("up to ", "");
				if (!norm(d).equals(norm(Objects.toString(str(p.path("duration")), "").replace("up to ", "")))) {
					rep("spells", name + ": duration seed '" + str(p.path("duration")) + "' vs SRD '" + dur + "'");
				}
			}
			String text = joinBlock(slice(blk, 1, blk.size()));
			String lowerText = text.toLowerCase();
			JsonNode mk = p.path("mechanics");
			Set<String> mechDice = new TreeSet<>(findAll(re("\\d+d\\d+"), mk.toString()));
			Set<String> textDice = new TreeSet<>();
			for (String x : findAll(re("\\d+d\\s?\\d+"), text)) {
				textDice.add(x.replace(" ", ""));
			}
			Set<String> missing = minus(mechDice, textDice);
			if (!missing.isEmpty()) {
				rep("spells-dice", name + ": mechanics dice " + repr(sorted(missing)) + " not in SRD text (text dice "
						+ repr(sorted(textDice)) + ")");
			}
			String kind = str(mk.path("kind"));
			Matcher sv = search(re("(Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma) saving throw"),
					text);
			if ("SAVE".equals(kind) && sv != null && neq(str(mk.path("save")), Py.head(sv.group(1), 3).toUpperCase())) {
				rep("spells", name + ": save seed " + repr(mk.path("save")) + " vs SRD " + sv.group(1));
			}
			if (!"SAVE".equals(kind) && sv != null && kind != null
					&& List.of("ATTACK", "AUTO", "HEAL").contains(kind)) {
				rep("spells-info",
						name + ": mechanics " + kind + " but SRD mentions a " + sv.group(1) + " saving throw");
			}
			String onSuccess = mk.has("on_success") ? str(mk.get("on_success")) : "HALF";
			if ("SAVE".equals(kind) && lowerText.contains("half as much damage") && !"HALF".equals(onSuccess)) {
				rep("spells",
						name + ": SRD says half damage on success; seed on_success " + repr(mk.path("on_success")));
			}
			if ("SAVE".equals(kind) && !lowerText.contains("half as much damage") && truthy(mk.path("damage"))
					&& "HALF".equals(onSuccess)) {
				rep("spells", name + ": SRD does not say half damage; seed on_success HALF");
			}
			if ("ATTACK".equals(kind) && !lowerText.contains("spell attack") && !lowerText.contains("attack roll")) {
				rep("spells", name + ": mechanics ATTACK but SRD text has no attack roll");
			}
			if (lowerText.contains("ranged spell attack") && neq(str(mk.path("attack")), "RANGED_SPELL")
					&& "ATTACK".equals(kind)) {
				rep("spells", name + ": SRD ranged spell attack; seed " + repr(mk.path("attack")));
			}
			if (lowerText.contains("melee spell attack") && neq(str(mk.path("attack")), "MELEE_SPELL")
					&& "ATTACK".equals(kind)) {
				rep("spells", name + ": SRD melee spell attack; seed " + repr(mk.path("attack")));
			}
			Matcher up = search(re("Using a Higher-Level Spell Slot\\. (.*?)(?:\\.|$)"), text);
			if (up != null) {
				rep("spells-upcast", name + ": SRD '" + up.group(1) + "' | seed upcast " + repr(mk.path("upcast")));
				Matcher um = search(re("increases by (\\d+d\\d+) for each spell slot level above (\\d+)"), up.group(1));
				String seedUp = str(mk.path("upcast").path("per_level_dice"));
				if (um != null && neq(seedUp, um.group(1))) {
					rep("spells", name + ": upcast per-level dice seed " + repr(seedUp) + " vs SRD " + um.group(1));
				}
				if (um == null && seedUp != null && !seedUp.isEmpty() && up.group(1).contains("increases by")) {
					rep("spells",
							name + ": seed upcast " + repr(mk.path("upcast")) + " but SRD says '" + up.group(1) + "'");
				}
			}
			Matcher cs = search(re("Cantrip Upgrade\\. (.*?)(?:\\.|$)"), text);
			if (cs != null) {
				rep("spells-upcast", name + ": SRD cantrip '" + cs.group(1) + "' | seed scaling "
						+ repr(mk.path("cantrip_scaling")));
				Matcher cm = search(re("levels 5 \\((\\d+d\\d+)\\), 11 \\((\\d+d\\d+)\\), and 17 \\((\\d+d\\d+)\\)"),
						cs.group(1));
				JsonNode scaling = mk.path("cantrip_scaling");
				if (cm != null && (scaling.size() != 3 || neq(str(scaling.path("5")), cm.group(1))
						|| neq(str(scaling.path("11")), cm.group(2)) || neq(str(scaling.path("17")), cm.group(3)))) {
					rep("spells", name + ": cantrip scaling seed " + repr(scaling) + " vs SRD " + tuple(groups(cm)));
				}
			}
			rep("spells-text", name + " [" + kind + "]: " + Py.head(text, 700));
		}
	}

	private void spellLists() {
		Set<String> allSeed = new HashSet<>();
		for (JsonNode e : spells.get("entries")) {
			allSeed.add(e.get("name").asText());
		}
		Pattern listEnd = re("^(Level \\d+ \\w+ Spells|\\w+ Cantrips)");
		Pattern listRow = re(
				"^(.+?) (Abjuration|Conjuration|Divination|Enchantment|Evocation|Illusion|Necromancy|Transmutation) (—|[CMR, ]+)$");
		for (String c : CLASSES) {
			String cap = Character.toUpperCase(c.charAt(0)) + c.substring(1);
			Pattern listHead = re("^(Level \\d+ " + cap + " Spells|Cantrips \\(Level 0 " + cap + " Spells\\))$");
			Set<String> listed = new LinkedHashSet<>();
			for (int i = 0; i < lines.size(); i++) {
				if (match(listHead, lines.get(i).strip()) != null) {
					for (int j = i + 2; j < i + 60 && j < lines.size(); j++) {
						String ln = lines.get(j).strip();
						if (match(listEnd, ln) != null) {
							break;
						}
						Matcher mm = match(listRow, ln);
						if (mm != null) {
							listed.add(mm.group(1).strip());
						}
					}
				}
			}
			Set<String> seedList = new LinkedHashSet<>();
			for (JsonNode e : spells.get("entries")) {
				if (Py.strings(e.get("payload").path("classes")).contains(c)) {
					seedList.add(e.get("name").asText());
				}
			}
			if (!listed.isEmpty()) {
				for (String n : minus(seedList, listed)) {
					rep("spell-lists", c + ": seed lists '" + n + "' but the SRD " + c + " list does not");
				}
				for (String n : minus(both(listed, allSeed), seedList)) {
					rep("spell-lists", c + ": SRD lists '" + n + "' but seed does not");
				}
				rep("spell-lists-info", c + ": SRD list has " + listed.size() + " spells, seed covers "
						+ both(seedList, listed).size());
			}
		}
	}

	// ── character origins: species traits, backgrounds, feats ───────────

	/**
	 * Text of each subsection: heading line until the next name in {@code names} (page markers
	 * skipped).
	 */
	private Map<String, String> sectionBlock(int heading, List<String> names) {
		Map<String, Integer> idx = new LinkedHashMap<>();
		Set<String> wanted = new HashSet<>(names);
		for (int i = 0; i < lines.size(); i++) {
			String s = lines.get(i).strip();
			if (wanted.contains(s) && !idx.containsKey(s) && i > heading) {
				idx.put(s, i);
			}
		}
		List<Map.Entry<String, Integer>> order = new ArrayList<>(idx.entrySet());
		order.sort(Map.Entry.comparingByValue());
		Map<String, String> out = new LinkedHashMap<>();
		for (int j = 0; j < order.size(); j++) {
			int i = order.get(j).getValue();
			int end = j + 1 < order.size() ? order.get(j + 1).getValue() : i + 220;
			out.put(order.get(j).getKey(), norm(dehyph(joinBlock(slice(lines, i, end)))));
		}
		return out;
	}

	private int lineNoOrZero(String pattern) {
		int i = lineNo(lines, pattern, 0);
		return Math.max(i, 0);
	}

	private static List<String> names(JsonNode doc) {
		List<String> out = new ArrayList<>();
		for (JsonNode e : doc.get("entries")) {
			out.add(e.get("name").asText());
		}
		return out;
	}

	private void origins() throws IOException {
		JsonNode backgrounds = load("backgrounds.json");
		JsonNode feats = load("feats.json");

		Map<String, String> speciesBlocks = sectionBlock(lineNoOrZero("Species Descriptions"), names(species));
		for (JsonNode e : species.get("entries")) {
			String name = e.get("name").asText();
			String blk = speciesBlocks.get(name);
			if (blk == null) {
				rep("species", name + ": no species description found in the SRD text");
				continue;
			}
			JsonNode traits = e.get("payload").path("traits");
			for (JsonNode t : traits) {
				if (!blk.contains(norm(t.get("name").asText()))) {
					rep("species-traits",
							name + ": trait '" + t.get("name").asText() + "' not found in the SRD species block");
				}
				JsonNode dv = t.path("darkvision_ft");
				if (dv.isInt() && !blk.contains(norm("darkvision with a range of " + dv.asInt() + " feet"))) {
					rep("species", name + ": darkvision " + dv.asInt() + " ft not confirmed by the SRD text");
				}
			}
			if (!truthy(traits)) {
				rep("species-traits", name + ": seed carries no traits");
			}
		}

		Map<String, String> bgBlocks = sectionBlock(lineNoOrZero("Background Descriptions"), names(backgrounds));
		Map<String, String> featById = new HashMap<>();
		for (JsonNode f : feats.get("entries")) {
			featById.put(f.get("id").asText(), f.get("name").asText());
		}
		Map<String, String> skillById = new HashMap<>();
		for (JsonNode s : load("skills.json").get("entries")) {
			skillById.put(s.get("id").asText(), s.get("name").asText());
		}
		Map<String, String> abilityNames = Map.of("STR", "strength", "DEX", "dexterity", "CON", "constitution", "INT",
				"intelligence", "WIS", "wisdom", "CHA", "charisma");
		for (JsonNode e : backgrounds.get("entries")) {
			String name = e.get("name").asText();
			JsonNode p = e.get("payload");
			String blk = bgBlocks.get(name);
			if (blk == null) {
				rep("backgrounds", name + ": no background description found");
				continue;
			}
			for (String a : Py.strings(p.path("ability_scores"))) {
				if (!blk.contains(abilityNames.get(a))) {
					rep("backgrounds", name + ": ability " + a + " not in the SRD ability list");
				}
			}
			String feat = featById.get(str(p.path("feat")));
			if (!blk.contains(norm(feat))) {
				rep("backgrounds", name + ": feat " + feat + " not named in the SRD text");
			}
			for (String s : Py.strings(p.path("skills"))) {
				if (!blk.contains(norm(skillById.get(s)))) {
					rep("backgrounds", name + ": skill " + skillById.get(s) + " not in the SRD skill line");
				}
			}
			String gold = str(p.path("starting_equipment").path("A").path("gold_gp"));
			if (!blk.contains(norm(gold + " gp; or (b) 50 gp"))) {
				rep("backgrounds", name + ": option A gold " + gold + " GP / B 50 GP not confirmed");
			}
		}

		Map<String, String> featBlocks = sectionBlock(lineNoOrZero("Origin Feats"), names(feats));
		Map<String, String> category = Map.of("ORIGIN", "origin feat", "GENERAL", "general feat", "FIGHTING_STYLE",
				"fighting style feat", "EPIC_BOON", "epic boon feat");
		for (JsonNode f : feats.get("entries")) {
			String name = f.get("name").asText();
			JsonNode p = f.get("payload");
			String blk = featBlocks.get(name);
			if (blk == null) {
				rep("feats", name + ": no feat description found");
				continue;
			}
			if (!blk.contains(category.get(str(p.path("category"))))) {
				rep("feats", name + ": category " + str(p.path("category")) + " not confirmed");
			}
			String prereq = str(p.path("prerequisite"));
			if (prereq != null && !prereq.isEmpty() && !blk.contains(norm(prereq.split(",")[0]))) {
				rep("feats-info", name + ": prerequisite '" + prereq + "' not literally found (check wording)");
			}
		}
	}

	// ── rules.json: the Rules Glossary, lifted verbatim ──────────────────

	private static String squash(String x) {
		return x.toLowerCase().replaceAll("[^a-z0-9]", "");
	}

	private void rules() {
		if (rulesDoc == null) {
			return;
		}
		// Compare on letters and digits only: the PDF has two-column hyphenation, smart quotes and bullets that the
		// rules builder repairs, and none of that is a difference in the rule. Entries can straddle a page break, so
		// the running header has to go before comparing.
		String plain = txt.replaceAll("=== PAGE \\d+ ===", " ");
		plain = plain.replaceAll("System Reference Document 5\\.2\\.1\\s*\\d+", " ");
		String flat = squash(dehyph(plain));
		int missing = 0;
		JsonNode entries = rulesDoc.get("entries");
		for (JsonNode e : entries) {
			String text = Objects.toString(str(e.path("payload").path("text")), "");
			String probe = Py.head(squash(text), 90);
			if (!probe.isEmpty() && !flat.contains(probe)) {
				missing++;
				rep("rules", e.get("name").asText() + ": opening text not found verbatim in the SRD - "
						+ repr(Py.head(text, 70)));
			}
			if (truthy(e.path("payload").path("text_is_paraphrase"))) {
				rep("rules", e.get("name").asText() + ": marked as a paraphrase but rules.json must be verbatim");
			}
		}
		rep("rules-info", "rules.json: " + entries.size() + " entries, " + missing
				+ " whose opening text did not match the SRD verbatim");
		for (Object[] tw : new Object[][] {{"CONDITION", 15}, {"AREA_OF_EFFECT", 5}, {"HAZARD", 5}}) {
			int got = 0;
			for (JsonNode e : entries) {
				if (tw[0].equals(str(e.path("payload").path("tag")))) {
					got++;
				}
			}
			if (got != (int) tw[1]) {
				rep("rules", "expected " + tw[1] + " " + tw[0] + " entries, found " + got);
			}
		}
	}
}
