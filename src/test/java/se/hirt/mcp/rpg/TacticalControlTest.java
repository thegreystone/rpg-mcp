/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.checkpoint.CheckpointService;
import se.hirt.mcp.rpg.party.TacticalControl;
import se.hirt.mcp.rpg.persistence.Database;
import se.hirt.mcp.rpg.protocol.Ref;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static se.hirt.mcp.rpg.TestCampaigns.*;

class TacticalControlTest {

	@Test
	void defaultAndExplicitCompanionControlNeverChangePrimaryIdentityOrMembership() throws Exception {
		try (Engine e = engine(tempDb("tactical-control-default"))) {
			Fixture f = fixture(e);
			var controls = new TacticalControl(e.db());
			long cid = Ref.id(f.campaign, Ref.CAMPAIGN);
			var identity = e.db().snapshot(cid).get("player_control_assignment");
			var membership = e.db().snapshot(cid).get("party_membership");
			assertEquals("PLAYER", assignment(e, f.campaign, f.hero).get("controller"));
			assertEquals("DM", assignment(e, f.campaign, f.first).get("controller"));
			assertEquals("DM", assignment(e, f.campaign, f.enemy).get("controller"));
			String operation = op();
			Map<String, Object> changed = controls.set(operation, f.campaign, List.of(f.first), "PLAYER", null, null,
					"I will direct Mira.");
			assertEquals("UNTIL_RELEASED", changed.get("scope"));
			assertEquals("PLAYER", assignment(e, f.campaign, f.first).get("controller"));
			assertEquals("DM", assignment(e, f.campaign, f.second).get("controller"));
			assertEquals("SEPARATED", assignment(e, f.campaign, f.second).get("membership"));
			assertEquals(true,
					controls.set(operation, f.campaign, List.of(f.first), "PLAYER", null, null, "I will direct Mira.")
							.get("replayed"));
			assertEquals(1L,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM tactical_control WHERE campaign_id=?", cid))
							.longValue());
			controls.set(op(), f.campaign, null, "DM", "UNTIL_RELEASED", null, "The DM directs the companions again.");
			assertEquals("DM", assignment(e, f.campaign, f.first).get("controller"));
			assertEquals("PLAYER", assignment(e, f.campaign, f.hero).get("controller"));
			assertEquals(identity, e.db().snapshot(cid).get("player_control_assignment"));
			assertEquals(membership, e.db().snapshot(cid).get("party_membership"));
		}
	}

	@Test
	void encounterOverridesPersistentDefaultsAndHandbacksTakeEffectImmediately() throws Exception {
		try (Engine e = engine(tempDb("tactical-control-precedence"))) {
			Fixture f = fixture(e);
			var controls = new TacticalControl(e.db());
			controls.set(op(), f.campaign, null, "PLAYER", "UNTIL_RELEASED", null, null);
			controls.set(op(), f.campaign, List.of(f.second), "DM", "UNTIL_RELEASED", null, null);
			String encounter = start(e, f);
			Map<String, Object> changed = controls.set(op(), f.campaign, null, "DM", null, null,
					"DM directs this battle.");
			assertEquals("ENCOUNTER", changed.get("scope"));
			assertEquals(encounter, changed.get("encounter"));
			assertEquals("DM", assignment(e, f.campaign, f.first).get("controller"));
			controls.set(op(), f.campaign, List.of(f.first), "PLAYER", "ENCOUNTER", encounter, null);
			assertEquals("PLAYER", assignment(e, f.campaign, f.first).get("controller"));
			controls.set(op(), f.campaign, null, "DM", "ENCOUNTER", encounter, "Hand everyone back.");
			assertEquals("DM", assignment(e, f.campaign, f.first).get("controller"),
					"all-companion handback removes per-character overrides");
			controls.set(op(), f.campaign, null, "PLAYER", "ENCOUNTER", encounter, null);
			Map<String, Object> persistentHandback = controls.set(op(), f.campaign, List.of(f.second), "DM",
					"UNTIL_RELEASED", null, "DM directs Rusk from now on.");
			assertEquals("DM", assignment(e, f.campaign, f.second).get("controller"));
			assertEquals("PLAYER", assignment(e, f.campaign, f.first).get("controller"));
			assertEquals(1, ((List<?>) persistentHandback.get("immediate_encounter_overrides")).size());
			controls.set(op(), f.campaign, null, "DM", "UNTIL_RELEASED", null, "DM directs everyone until released.");
			assertEquals("DM", assignment(e, f.campaign, f.first).get("controller"));
			assertEquals("DM", assignment(e, f.campaign, f.second).get("controller"));
			assertEquals("PLAYER", assignment(e, f.campaign, f.hero).get("controller"));
			long cid = Ref.id(f.campaign, Ref.CAMPAIGN);
			assertEquals(0L,
					e.db().read(tx -> tx.count(
							"SELECT COUNT(*) FROM tactical_control WHERE campaign_id=? AND encounter_id IS NOT NULL",
							cid)).longValue());
		}
	}

	@Test
	void persistenceAndCheckpointRestorationPreserveAllPoliciesAndFutureCompanionDefaults() throws Exception {
		Path path = tempDb("tactical-control-reload");
		Fixture f;
		String checkpoint;
		List<Map<String, Object>> policies;
		try (Engine e = engine(path)) {
			f = fixture(e);
			var controls = new TacticalControl(e.db());
			controls.set(op(), f.campaign, null, "PLAYER", "UNTIL_RELEASED", null,
					"I direct companions until released.");
			String newcomer = (String) e.runtime()
					.materialize(op(), f.campaign, "Commoner", "Later guest", null, null, null, null, false)
					.get("character");
			e.party().updateMembership(op(), f.campaign, newcomer, "GUEST_ADD",
					"The guide accompanies the expedition.");
			assertEquals("PLAYER", assignment(e, f.campaign, newcomer).get("controller"));
			assertEquals("GUEST", assignment(e, f.campaign, newcomer).get("membership"));
			long cid = Ref.id(f.campaign, Ref.CAMPAIGN);
			policies = e.db().snapshot(cid).get("tactical_control");
			checkpoint = (String) e.checkpoints().create(op(), f.campaign, "before handback").get("checkpoint");
			controls.set(op(), f.campaign, List.of(f.first), "DM", "UNTIL_RELEASED", null, "DM takes Mira.");
		}
		try (Engine e = engine(path)) {
			assertEquals("DM", assignment(e, f.campaign, f.first).get("controller"));
			assertEquals("PLAYER", assignment(e, f.campaign, f.second).get("controller"));
			e.checkpoints().restore(op(), f.campaign, checkpoint, "restore tactical choices");
			assertEquals("PLAYER", assignment(e, f.campaign, f.first).get("controller"));
			assertEquals(policies, e.db().snapshot(Ref.id(f.campaign, Ref.CAMPAIGN)).get("tactical_control"));
			var summary = e.db().read(tx -> TacticalControl.summary(tx, Ref.id(f.campaign, Ref.CAMPAIGN)));
			assertEquals("DM", summary.get("default_companion_controller"));
			assertEquals(4, ((List<?>) summary.get("members")).size());
		}
	}

	@Test
	void encounterPolicyAppliesToCompanionsNotYetInCombatAndExpiresWithoutErasingPersistentChoice() throws Exception {
		try (Engine e = engine(tempDb("tactical-control-expiry"))) {
			Fixture f = fixture(e);
			var controls = new TacticalControl(e.db());
			controls.set(op(), f.campaign, null, "PLAYER", "UNTIL_RELEASED", null, null);
			String encounter = start(e, f);
			controls.set(op(), f.campaign, null, "DM", "ENCOUNTER", encounter, "DM directs only this fight.");
			assertNull(e.db().read(tx -> tx.get("character", Ref.id(f.second, Ref.CHARACTER)).lng("encounter_id")));
			assertEquals("DM", assignment(e, f.campaign, f.second).get("controller"),
					"a joining companion uses the live encounter's policy");
			long cid = Ref.id(f.campaign, Ref.CAMPAIGN);
			String cp = (String) e.db()
					.mutate(Database.Mutation.of("control-checkpoint-fixture", cid, op(), "GM", null),
							tx -> Map.of("checkpoint", CheckpointService.createInTx(tx, cid, "battle control")))
					.get("checkpoint");
			e.encounters().end(op(), f.campaign, encounter, "OTHER", "The wolf leaves.");
			assertEquals("PLAYER", assignment(e, f.campaign, f.second).get("controller"));
			assertEquals(0L,
					e.db().read(tx -> tx.count(
							"SELECT COUNT(*) FROM tactical_control WHERE campaign_id=? AND encounter_id IS NOT NULL",
							cid)).longValue());
			e.checkpoints().restore(op(), f.campaign, cp, "restore battle and its policy");
			assertEquals("DM", assignment(e, f.campaign, f.second).get("controller"));
			assertEquals("ENCOUNTER", assignment(e, f.campaign, f.second).get("scope"));
		}
	}

	@Test
	void invalidAndForeignTargetsFailAtomicallyAndFormerCompanionsRemainDmControlled() throws Exception {
		try (Engine e = engine(tempDb("tactical-control-validation"))) {
			Fixture f = fixture(e);
			var controls = new TacticalControl(e.db());
			long cid = Ref.id(f.campaign, Ref.CAMPAIGN);
			assertThrows(RpgException.class,
					() -> controls.set(op(), f.campaign, List.of(f.hero), "DM", null, null, null));
			assertThrows(RpgException.class,
					() -> controls.set(op(), f.campaign, List.of(f.first, f.enemy), "PLAYER", null, null, null));
			assertThrows(RpgException.class,
					() -> controls.set(op(), f.campaign, List.of("character:999999"), "PLAYER", null, null, null));
			assertThrows(RpgException.class,
					() -> controls.set(op(), f.campaign, List.of(), "PLAYER", null, null, null));
			assertThrows(RpgException.class,
					() -> controls.set(op(), f.campaign, null, "PLAYER", "ENCOUNTER", null, null));
			assertThrows(RpgException.class, () -> controls.set(op(), f.campaign, null, "NOBODY", null, null, null));
			String foreign = committedCampaign(e);
			String foreignCharacter = e.db()
					.read(tx -> "character:" + tx.queryOne(
							"SELECT character_id FROM player_control_assignment WHERE campaign_id=? AND active=1",
							Ref.id(foreign, Ref.CAMPAIGN)).orElseThrow().lng("character_id"));
			assertThrows(RpgException.class,
					() -> controls.set(op(), f.campaign, List.of(foreignCharacter), "PLAYER", null, null, null));
			assertEquals(0L,
					e.db().read(tx -> tx.count("SELECT COUNT(*) FROM tactical_control WHERE campaign_id=?", cid))
							.longValue());
			controls.set(op(), f.campaign, null, "PLAYER", "UNTIL_RELEASED", null, null);
			e.party().updateMembership(op(), f.campaign, f.first, "LEAVE", "Mira leaves the expedition.");
			assertEquals("DM", assignment(e, f.campaign, f.first).get("controller"));
			assertEquals("NON_PARTY", assignment(e, f.campaign, f.first).get("source"));
			assertEquals("PLAYER", assignment(e, f.campaign, f.hero).get("controller"));
		}
	}

	private record Fixture(String campaign, String hero, String first, String second, String enemy) {
	}

	private static Fixture fixture(Engine e) {
		String campaign = committedCampaign(e);
		e.sessions().bootstrap(op(), campaign, null);
		String hero = e.db()
				.read(tx -> "character:" + tx
						.queryOne("SELECT character_id FROM player_control_assignment WHERE campaign_id=? AND active=1",
								Ref.id(campaign, Ref.CAMPAIGN))
						.orElseThrow().lng("character_id"));
		String first = companion(e, campaign, "Mira"), second = companion(e, campaign, "Rusk");
		e.party().updateMembership(op(), campaign, second, "SEPARATE", "Rusk watches the nearby entrance.");
		String enemy = (String) e.runtime().materialize(op(), campaign, "Wolf", "Wolf", null, null, null, null, false)
				.get("character");
		return new Fixture(campaign, hero, first, second, enemy);
	}

	private static String companion(Engine e, String campaign, String name) {
		String ref = (String) e.runtime().materialize(op(), campaign, "Scout", name, null, null, null, null, false)
				.get("character");
		e.party().updateMembership(op(), campaign, ref, "JOIN", name + " joins the expedition.");
		return ref;
	}

	private static String start(Engine e, Fixture f) {
		return (String) e.encounters().start(op(), f.campaign,
				map("party", List.of(f.hero, f.first), "wolves", List.of(f.enemy)), null, null, null, null, null)
				.get("encounter");
	}

	private static Map<String, Object> assignment(Engine e, String campaign, String character) {
		return e.db().read(
				tx -> TacticalControl.assignment(tx, Ref.id(campaign, Ref.CAMPAIGN), Ref.id(character, Ref.CHARACTER)));
	}
}
