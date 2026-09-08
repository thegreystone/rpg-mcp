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

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.dice.ScriptedRollService;
import se.hirt.mcp.rpg.protocol.ErrorCode;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.map;
import static se.hirt.mcp.rpg.TestCampaigns.op;

/**
 * Spellcasting: selection limits, slots as resources, casting mechanics, concentration, buffs in AC/attacks, rests and
 * level-ups resizing slots (DESIGN.md §13, I-29, I-30).
 */
class SpellTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		return (List<Map<String, Object>>) o;
	}

	/** Returns {campaign, pc}. */
	private static String[] caster(
			Engine engine, String cls, Map<String, Object> scores, List<String> skills, List<String> cantrips,
			List<String> spells) {
		String campaign = (String) engine.campaigns().create(op(), null, null).get("campaign");
		engine.campaigns().updateSetup(op(), campaign, null,
				map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
						"CHECKPOINT", "party", "SURPRISE_ME", "adventure",
						map("premise", "x", "opening_location", "Tower", "immediate_goal", "y")));
		String pc = (String) engine.characters().createDraft(op(), campaign,
				map("name", "Caster", "species", "Human", "class", cls, "ability_scores", scores, "skills", skills,
						"personality", "x", "background", "Criminal", "background_ability_scores",
						map("CON", 2, "DEX", 1), "species_skill", "Animal Handling", "origin_feat",
						map("feat", "Skilled", "proficiencies", List.of("Nature", "Survival", "Medicine")), "cantrips",
						cantrips, "spells", spells), true).get("character");
		engine.characters().commitDraft(op(), campaign, pc, null);
		engine.campaigns().commitSetup(op(), campaign, null);
		engine.sessions().bootstrap(op(), campaign, null);
		return new String[] {campaign, pc};
	}

	@Test
	void wizardSelectionSlotsAndCasting() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("wizard"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			Map<String, Object> scores = map("INT", 15, "DEX", 14, "CON", 13, "WIS", 12, "CHA", 10, "STR", 8);
			// Limits: a level-1 wizard knows 3 cantrips and prepares 4 spells from the wizard list.
			String probe = (String) engine.campaigns().create(op(), null, null).get("campaign");
			engine.campaigns().updateSetup(op(), probe, null,
					map("content_profile", "PEGI_16", "experience", "SURPRISE_ME", "rules", Map.of(), "continuation",
							"CHECKPOINT"));
			String draft = (String) engine.characters().createDraft(op(), probe,
					map("name", "P", "species", "Human", "class", "Wizard", "ability_scores", scores, "skills",
							List.of("Arcana", "History"), "personality", "x"), true).get("character");
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class, () -> engine.characters()
							.updateDraft(op(), probe, draft, null,
									map("cantrips", List.of("Fire Bolt", "Mage Hand", "Light", "Prestidigitation")))).code(),
					"4 cantrips is too many");
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class, () -> engine.characters()
							.updateDraft(op(), probe, draft, null, map("spells", List.of("Cure Wounds")))).code(),
					"not a wizard spell");
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class, () -> engine.characters()
							.updateDraft(op(), probe, draft, null, map("spells", List.of("Fireball")))).code(),
					"level 3 is beyond a level-1 slot");
			assertTrue(((List<?>) engine.characters().createDraft(op(), probe,
							map("name", "F", "species", "Human", "class", "Fighter", "ability_scores", scores), false)
					.get("next_steps")).stream().noneMatch(s -> s.toString().contains("cantrips")));
			assertTrue(((List<?>) engine.characters().updateDraft(op(), probe, draft, null, map("age", 30))
							.get("next_steps")).stream().anyMatch(s -> s.toString().contains("cantrips")),
					"casters are reminded to choose spells");

			String[] ids = caster(engine, "Wizard", scores, List.of("Arcana", "History"),
					List.of("Fire Bolt", "Mage Hand", "Light"),
					List.of("Magic Missile", "Mage Armor", "Sleep", "Shield"));
			String campaign = ids[0];
			String pc = ids[1];
			Map<String, Object> sheet = engine.characters().characterSheet(campaign, pc, "PLAY");
			Map<String, Object> sc = m(sheet.get("spellcasting"));
			assertEquals("INT", sc.get("ability"));
			assertEquals(8 + 2 + 2, sc.get("save_dc"));
			assertEquals(4, sc.get("attack_bonus"));
			assertEquals(2, m(m(sc.get("slots")).get("1")).get("max"));
			assertEquals(3, ((List<?>) sc.get("cantrips")).size());
			assertEquals(4, ((List<?>) sc.get("prepared")).size());
			int baseAc = (Integer) m(sheet.get("armor_class")).get("value");
			assertEquals(12, baseAc, "unarmored 10 + DEX 14");

			// Mage Armor: a timed buff that changes AC.
			Map<String, Object> armor = engine.spells().cast(op(), campaign, pc, "Mage Armor", null, null, null);
			assertEquals(1, m(armor.get("slot")).get("remaining"));
			assertEquals(15,
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("armor_class")).get("value"),
					"13 + DEX");
			assertEquals(1, list(engine.characters().characterSheet(campaign, pc, "PLAY").get("effects")).size());

			// Magic Missile: three darts, automatic, 1d4+1 each → scripted 2,3,4 = 3+4+5 = 12.
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			dice.queue(2, 3, 4);
			Map<String, Object> missiles = engine.spells()
					.cast(op(), campaign, pc, "Magic Missile", null, List.of(bandit), null);
			assertEquals(3, list(missiles.get("targets")).size());
			assertEquals(0, m(missiles.get("slot")).get("remaining"));
			assertEquals(11 - 12 < 0 ? 0 : 11 - 12, list(missiles.get("targets")).get(2).get("hp_after"));
			assertEquals("DEAD", list(missiles.get("targets")).get(2).get("life_state"),
					"12 damage kills an 11 HP bandit");
			// No slots left; cantrips still work.
			assertEquals(ErrorCode.INSUFFICIENT_RESOURCE, assertThrows(RpgException.class,
					() -> engine.spells().cast(op(), campaign, pc, "Sleep", null, List.of(bandit), null)).code());
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class,
							() -> engine.spells().cast(op(), campaign, pc, "Fireball", null, List.of(bandit), null)).code(),
					"not prepared");
			String wolf = (String) engine.runtime().materialize(op(), campaign, "Wolf", null, null, null, null, null, false)
					.get("character");
			dice.queue(15, 7);
			Map<String, Object> bolt = engine.spells().cast(op(), campaign, pc, "Fire Bolt", null, List.of(wolf), null);
			Map<String, Object> hit = list(bolt.get("targets")).get(0);
			assertEquals(Boolean.TRUE, hit.get("hit"), "15 + 4 vs AC 12");
			assertEquals(7, hit.get("damage"));
			assertEquals(11 - 7, hit.get("hp_after"));
			assertNull(bolt.get("slot"), "cantrips do not spend slots");

			// Timed expiry: Mage Armor lasts 8 hours.
			engine.sessions().advanceTime(op(), campaign, 7 * 60, "waiting");
			assertEquals(15,
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("armor_class")).get("value"));
			Map<String, Object> later = engine.sessions().advanceTime(op(), campaign, 2 * 60, "waiting");
			assertEquals(1, later.get("expired_effects"));
			assertEquals(12,
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("armor_class")).get("value"));

			// Long rest restores slots; level 2 gives three.
			engine.rest().rest(op(), campaign, "LONG", null, null);
			assertEquals(2, m(m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get(
					"slots")).get("1")).get("current"));
			engine.runtime().awardXp(op(), campaign, List.of(pc), 300, "QUEST", "x");
			String t = (String) m(engine.levelUps().begin(op(), campaign, pc).get("transaction")).get("ref");
			Map<String, Object> choices = engine.levelUps().choices(campaign, t);
			assertEquals(5, m(choices.get("spellcasting_at_new_level")).get("prepared_spells"));
			engine.levelUps().commit(op(), campaign, t, null);
			assertEquals(3, m(m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get(
					"slots")).get("1")).get("max"));
			engine.spells().prepare(op(), campaign, pc, null,
					List.of("Magic Missile", "Mage Armor", "Sleep", "Shield", "Thunderwave"));
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class, () -> engine.spells()
					.prepare(op(), campaign, pc, null,
							List.of("Magic Missile", "Mage Armor", "Sleep", "Shield", "Thunderwave",
									"Grease"))).code());
			// Search finds spells by class and level.
			Map<String, Object> found = engine.content()
					.definitions(campaign, "SPELL", null, "wizard level 1 evocation", null, null, null, 50, "SUMMARY");
			assertTrue(list(found.get("items")).stream().anyMatch(i -> i.get("name").equals("Magic Missile")));
		}
	}

	@Test
	void clericConcentrationBuffsAndSaves() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("cleric"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String[] ids = caster(engine, "Cleric",
					map("WIS", 15, "CON", 14, "STR", 13, "DEX", 12, "CHA", 10, "INT", 8),
					List.of("Insight", "Religion"), List.of("Sacred Flame", "Guidance", "Spare the Dying"),
					List.of("Bless", "Shield of Faith", "Cure Wounds", "Guiding Bolt"));
			String campaign = ids[0];
			String pc = ids[1];
			String mara = (String) engine.runtime()
					.materialize(op(), campaign, "Guard", "Mara", null, null, null, null, false).get("character");
			engine.party().updateMembership(op(), campaign, mara, "JOIN", null);
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");

			// Bless on self and Mara (concentration), then Shield of Faith (concentration) ends Bless (I-29).
			Map<String, Object> bless = engine.spells()
					.cast(op(), campaign, pc, "Bless", null, List.of(pc, mara), null);
			assertEquals(2, list(bless.get("targets")).size());
			assertEquals(Boolean.TRUE, bless.get("concentration"));
			assertEquals(List.of("Bless (Caster)"),
					m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting"))).get(
							"concentrating_on"));
			Map<String, Object> sof = engine.spells()
					.cast(op(), campaign, pc, "Shield of Faith", null, List.of(mara), null);
			assertEquals(List.of("Bless (Caster)", "Bless (Caster)"), sof.get("concentration_ended"));
			assertEquals(18,
					m(engine.characters().characterSheet(campaign, mara, "PLAY").get("armor_class")).get("value"),
					"guard 16 + 2");
			assertTrue(list(engine.characters().characterSheet(campaign, pc, "PLAY").get("effects")).isEmpty(),
					"the caster's own Bless ended");

			// Both slots are spent; a long rest restores them. Bless again and attack in an encounter: the d4 is added to the attack roll.
			assertEquals(ErrorCode.INSUFFICIENT_RESOURCE, assertThrows(RpgException.class,
					() -> engine.spells().cast(op(), campaign, pc, "Bless", null, List.of(pc), null)).code());
			engine.rest().rest(op(), campaign, "LONG", null, null);
			engine.spells().cast(op(), campaign, pc, "Bless", null, List.of(pc), null);
			dice.queue(20, 50, 1, 50, 1, 50);
			String enc = (String) engine.encounters()
					.start(op(), campaign, map("party", List.of(pc, mara), "raiders", List.of(bandit)), null, null,
							null, null, null).get("encounter");
			dice.queue(9, 3);
			Map<String, Object> swing = engine.encounters()
					.perform(op(), campaign, enc, pc, map("kind", "ATTACK", "target", bandit, "weapon", "unarmed"),
							false);
			assertEquals(9 + 1 + 2 + 3, swing.get("attack_total"), "d20 9 + STR 1 + prof 2 + Bless d4 3");
			assertEquals(Boolean.TRUE, swing.get("hit"));
			// Sacred Flame in the encounter via CAST: DEX save vs DC 8+2+2=12; bandit rolls 5+1 → fails → 1d8 radiant 6.
			dice.queue(5, 6);
			Map<String, Object> flame = engine.encounters()
					.perform(op(), campaign, enc, pc, map("kind", "CAST", "spell", "Sacred Flame", "target", bandit),
							true);
			Map<String, Object> ft = list(flame.get("targets")).get(0);
			assertEquals(Boolean.FALSE, ft.get("saved"));
			assertEquals(6, ft.get("damage"));
			assertEquals("CAST", list(m(flame.get("state")).get("recent_log")).get(0).get("kind"));
			// Mara passes; the bandit hits the cleric → concentration save DC 10; scripted 3 → lost.
			engine.encounters().perform(op(), campaign, enc, mara, map("kind", "END_TURN"), true);
			dice.queue(18, 4, 3);
			Map<String, Object> banditTurn = engine.encounters()
					.perform(op(), campaign, enc, bandit, map("kind", "ATTACK", "target", pc, "attack", "Scimitar"),
							true);
			assertEquals(Boolean.TRUE, banditTurn.get("hit"));
			assertEquals(Boolean.FALSE, m(banditTurn.get("concentration")).get("kept"));
			assertTrue(list(engine.characters().characterSheet(campaign, pc, "PLAY").get("effects")).isEmpty(),
					"Bless is gone");
			engine.encounters().end(op(), campaign, enc, "PARTY_VICTORY", "x");

			// Healing revives a dying companion; Spare the Dying stabilizes.
			engine.runtime().applyRuntimeChange(op(), campaign, mara,
					map("kind", "DAMAGE", "amount", 11, "damage_type", "slashing", "reason", "test"));
			assertEquals("DYING", engine.characters().characterSheet(campaign, mara, "SUMMARY").get("life_state"));
			engine.spells().cast(op(), campaign, pc, "Spare the Dying", null, List.of(mara), null);
			assertEquals(Boolean.TRUE,
					m(engine.characters().characterSheet(campaign, mara, "PLAY").get("death_saves")).get("stable"));
			dice.queue(4, 4);
			Map<String, Object> cure = engine.spells()
					.cast(op(), campaign, pc, "Cure Wounds", null, List.of(mara), null);
			assertEquals(8 + 2, list(cure.get("targets")).get(0).get("healed"), "2d8 (4+4) + WIS 2");
			assertEquals("ALIVE", engine.characters().characterSheet(campaign, mara, "SUMMARY").get("life_state"));
			// Upcasting Cure Wounds with a level 2 slot is refused at level 1 (no such slot).
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class,
					() -> engine.spells().cast(op(), campaign, pc, "Cure Wounds", 2, List.of(mara), null)).code());
			// Guidance adds a d4 to a check.
			engine.spells().cast(op(), campaign, pc, "Guidance", null, List.of(pc), null);
			dice.queue(10, 4);
			Map<String, Object> check = engine.checks()
					.resolveCheck(op(), campaign, pc, "SKILL_CHECK", null, "Religion", 15, null, null);
			assertEquals(10 - 1 + 2 + 4, check.get("total"), "d20 10 + INT -1 + prof 2 + Guidance 4");
		}
	}

	@Test
	void warlockPactSlotsRechargeOnShortRest() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("warlock"))) {
			String[] ids = caster(engine, "Warlock",
					map("CHA", 15, "CON", 14, "DEX", 13, "WIS", 12, "INT", 10, "STR", 8),
					List.of("Arcana", "Deception"), List.of("Eldritch Blast", "Minor Illusion"),
					List.of("Hex", "Charm Person"));
			String campaign = ids[0];
			String pc = ids[1];
			Map<String, Object> sc = m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting"));
			assertEquals(1, m(sc.get("pact_slots")).get("max"));
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");
			Map<String, Object> hex = engine.spells()
					.cast(op(), campaign, pc, "Hex", null, List.of(pc), map("against", bandit));
			assertEquals(0, m(hex.get("slot")).get("remaining"));
			assertEquals(ErrorCode.INSUFFICIENT_RESOURCE, assertThrows(RpgException.class, () -> engine.spells()
					.cast(op(), campaign, pc, "Charm Person", null, List.of(bandit), null)).code());
			engine.rest().rest(op(), campaign, "SHORT", null, null);
			assertEquals(1, m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get(
					"pact_slots")).get("current"), "pact slots return on a short rest");
			// Charm Person: WIS save vs DC 8 + 2 + 2 = 12; the bandit rolls 2 → Charmed for an hour.
			((ScriptedRollService) engine.roller()).queue(2);
			Map<String, Object> charm = engine.spells()
					.cast(op(), campaign, pc, "Charm Person", null, List.of(bandit), null);
			Map<String, Object> ct = list(charm.get("targets")).get(0);
			assertEquals(Boolean.FALSE, ct.get("saved"));
			assertEquals(List.of("CHARMED"), ct.get("conditions"));
			assertTrue(list(engine.characters().characterSheet(campaign, bandit, "PLAY").get("conditions")).stream()
					.anyMatch(c -> "CHARMED".equals(c.get("condition"))));
			assertEquals(0, m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get(
					"pact_slots")).get("current"));
		}
	}

	/**
	 * A spell centred on the caster can be cast with nobody in its area (Spirit Guardians before the enemy closes),
	 * a damage-only concentration spell is still tracked as concentration, and the free roll / dice damage path
	 * journals the server's dice.
	 */
	@Test
	void selfCentredSpellsFreeRollsAndDiceDamage() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("emanation"))) {
			ScriptedRollService dice = (ScriptedRollService) engine.roller();
			String[] ids = caster(engine, "Druid", map("WIS", 15, "CON", 14, "STR", 13, "DEX", 12, "CHA", 10, "INT", 8),
					List.of("Perception", "Insight"), List.of("Druidcraft", "Guidance"),
					List.of("Thunderwave", "Faerie Fire", "Cure Wounds", "Goodberry"));
			String campaign = ids[0];
			String pc = ids[1];
			String bandit = (String) engine.runtime()
					.materialize(op(), campaign, "Bandit", null, null, null, null, null, false).get("character");

			// Thunderwave (Self, 15-foot cube) with no creature in the cube: legal, the slot is spent, a note explains
			// how to resolve later saves.
			Map<String, Object> wave = engine.spells().cast(op(), campaign, pc, "Thunderwave", null, List.of(), null);
			assertTrue(list(wave.get("targets")).isEmpty());
			assertTrue(String.valueOf(wave.get("note")).contains("resolve_check"));
			// A ranged save spell still needs a target.
			assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(RpgException.class,
					() -> engine.spells().cast(op(), campaign, pc, "Faerie Fire", null, List.of(), null)).code());

			// Faerie Fire on a bandit who saves: nothing lands on the target, but the caster is concentrating.
			dice.queue(20);
			Map<String, Object> fire = engine.spells()
					.cast(op(), campaign, pc, "Faerie Fire", null, List.of(bandit), null);
			assertEquals(Boolean.TRUE, list(fire.get("targets")).get(0).get("saved"));
			assertEquals(List.of("Faerie Fire (Caster)"),
					m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting"))).get(
							"concentrating_on"));

			// A free roll: 4d6 drop lowest = 6+5+4 (the 1 dropped), journaled with a roll_ref.
			dice.queue(6, 1, 5, 4);
			Map<String, Object> free = engine.checks().rollDice(op(), campaign, "4d6dl1", pc, "test");
			assertEquals(15, free.get("total"));
			assertEquals(List.of(1), m(free.get("roll")).get("dropped"));
			assertTrue(String.valueOf(free.get("roll_ref")).startsWith("roll:"));

			// DAMAGE with dice: 2d6 fall = 3 + 4 = 7 off the bandit's 11 HP, breakdown returned.
			dice.queue(3, 4);
			Map<String, Object> fall = engine.runtime().applyRuntimeChange(op(), campaign, bandit,
					map("kind", "DAMAGE", "dice", "2d6", "damage_type", "bludgeoning", "reason", "fell off a roof"));
			assertEquals(7, m(fall.get("roll")).get("total"));
			assertEquals(11 - 7,
					(Integer) m(engine.characters().characterSheet(campaign, bandit, "SUMMARY").get("hp")).get("current"));
		}
	}

	@Test
	void ritualCastingSpendsNoSlot() throws Exception {
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("ritual"))) {
			String[] ids = caster(engine, "Cleric",
					map("WIS", 15, "CON", 14, "STR", 13, "DEX", 12, "CHA", 10, "INT", 8),
					List.of("Insight", "Religion"), List.of("Sacred Flame", "Guidance", "Spare the Dying"),
					List.of("Detect Magic", "Bless", "Cure Wounds", "Guiding Bolt"));
			String campaign = ids[0];
			String pc = ids[1];
			Map<String, Object> slotsBefore = m(
					m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get("slots"));

			// A ritual-tagged spell cast by a class with Ritual Casting spends nothing.
			Map<String, Object> ritual = engine.spells()
					.cast(op(), campaign, pc, "Detect Magic", null, null, map("ritual", true));
			assertEquals(Boolean.TRUE, m(ritual.get("slot")).get("ritual"));
			assertEquals(slotsBefore,
					m(m(engine.characters().characterSheet(campaign, pc, "PLAY").get("spellcasting")).get("slots")),
					"no slot spent");

			// A spell without the Ritual tag cannot be cast as one; slot_level does not combine with a ritual.
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class,
					() -> engine.spells().cast(op(), campaign, pc, "Bless", null, List.of(pc), map("ritual", true)))
					.code());
			assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(RpgException.class,
					() -> engine.spells().cast(op(), campaign, pc, "Detect Magic", 2, null, map("ritual", true)))
					.code());
			// The same spell cast normally spends a level-1 slot.
			Map<String, Object> normal = engine.spells().cast(op(), campaign, pc, "Detect Magic", null, null, null);
			assertEquals(1, m(normal.get("slot")).get("slot_level"));
		}
		try (Engine engine = TestCampaigns.engine(TestCampaigns.tempDb("ritual-sorcerer"))) {
			// Sorcerers have no Ritual Casting: the ritual option is refused even for a ritual-tagged spell.
			String[] ids = caster(engine, "Sorcerer",
					map("CHA", 15, "CON", 14, "DEX", 13, "INT", 12, "WIS", 10, "STR", 8),
					List.of("Arcana", "Persuasion"), List.of("Fire Bolt", "Light", "Mage Hand", "Prestidigitation"),
					List.of("Detect Magic", "Magic Missile"));
			RpgException refused = assertThrows(RpgException.class, () -> engine.spells()
					.cast(op(), ids[0], ids[1], "Detect Magic", null, null, map("ritual", true)));
			assertEquals(ErrorCode.VALIDATION_FAILED, refused.code());
		}
	}
}
