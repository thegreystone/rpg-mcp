// Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause
// Player intent only: tool names and argument shapes must be discovered by the GM.
export const scenarios = {
  'short-rest': 'Continue my campaign. Richard takes a short rest and spends exactly two of his Hit Point Dice to recover. Leave Mira out of this rest. Tell me the result, then stop.',
  'companion-control': 'Continue my campaign. I want to control Mira myself until I explicitly hand her back to the DM, including future encounters. Richard remains my primary character. Save that preference and confirm it; do not move anyone or start a fight.',
  'pending-status': 'Continue my campaign. Before I decide about the pending reaction, tell me how Richard and Mira are doing: their current and maximum hit points, conditions, and who controls them. Do not resolve the reaction, spend anything, move anyone, or advance the turn.',
  'combat-reaction': 'Continue my campaign. Take Richard\'s pending opportunity attack with his Club. Resolve just that reaction and tell me what happened. Stop before taking any subsequent action or moving anyone else.',
  'level-up': 'Continue my campaign. Advance Richard to sorcerer level two. Choose Empowered Spell and Quickened Spell for his Metamagic and retain his current spells. Complete the level-up, report the result, and stop.',
  'interrupted-travel': 'Continue my campaign. We talked our way past the road watchers: record that travel opportunity as negotiated and continue the unfinished journey to Old Mill. Complete only the remaining journey. Do not start combat, grant rewards, rest, or start another trip.',
  'upgrade-recall': { upgrade: true, turns: [
    'Continue my saved campaign. Retrieve the complete record of our last battle with the road watcher. Recount the recorded result, the party\'s hit points, and the XP earned. Check whether any loot was automatically awarded. Do not grant treasure or change the game state.',
  ] },
  'upgrade-checkpoint': { upgrade: true, turns: [
    'Continue my saved campaign. Rewind to the checkpoint whose reason is Before the toll. Restore that saved point, then stop without advancing the story.',
    'Continue my campaign. Verify Richard\'s hit points, purse, and inventory after the rewind. Has the torch bought after that checkpoint gone away? Do not buy, grant, heal, or rewind anything again.',
  ] },
  'spell-potion': { upgrade: true, turns: [
    'Continue my campaign. Richard casts Mage Armor on himself using one first-level slot, then drinks his carried Potion of Healing. Resolve each exactly once. Do not rest, buy, grant anything, or use a GM override.',
    'Continue my campaign. Tell me Richard\'s current hit points, armor class, remaining first-level slots, and whether he still carries that Potion of Healing. Do not cast or use anything again.',
  ] },
  'session-recovery': { upgrade: true, turns: [
    'Continue my campaign. Save that I control Mira myself until I explicitly hand her back to the DM. Also record this promise as a shared memory: Richard promised Mira he would recover her silver brooch from Old Mill. Do not move the party or advance time.',
    'End this session. Save a short recap of our current position, my control of Mira, and the promise about her silver brooch.',
    'Continue my campaign. Remind me what Richard promised Mira, where the party is, and who controls Mira. Do not change the saved control preference, move, or advance time.',
  ] },
  'commerce-sequence': { upgrade: true, turns: [
    'Continue my campaign. Richard buys two additional Torches at their normal list price, then gives exactly one of the newly bought torches to Mira. Stop after those transactions. Do not award money or create treasure.',
    'Continue my campaign. Verify Richard\'s remaining money and the number of Torches Richard and Mira each carry. Do not buy or transfer anything again.',
  ] },
  'upgrade-level-up': { upgrade: true, turns: [
    'Continue my campaign and finish Richard\'s already-open level-up to sorcerer level two. Choose Empowered Spell and Quickened Spell and retain his current spells. Complete the existing transaction without abandoning it or starting another level-up.',
  ] },
  'upgrade-death': { upgrade: true, turns: [
    'Richard has died. Continue the campaign with Mira as my new primary player character. Preserve Richard\'s death and the party\'s history. Do not resurrect anyone, restore a checkpoint, award XP, or advance time.',
  ] },
  'release-upgrade': { upgrade: true, turns: [
    'Continue my existing campaign. Check Richard and Mira\'s full status, coins, carried items, and remaining first-level spell slots. Remind me of the promise Richard made about the copper key. Do not heal, rest, spend anything, or move the party.',
    'Continue my campaign. Restore the old checkpoint whose reason is Released campaign checkpoint, before Richard was injured and poisoned and spent that spell slot. Verify the restored hit points, conditions, spell slots, purse, and inventory, then stop. Do not rest, buy, move, or use a GM override.',
  ] },
};

export function scenarioDetails(scenario) {
  if (!Object.hasOwn(scenarios, scenario)) throw new Error(`Unknown scenario: ${scenario}`);
  const value = scenarios[scenario];
  return typeof value === 'string' ? { turns: [value], upgrade: false } : value;
}

export function promptFor(scenario, step = 0) {
  const details = scenarioDetails(scenario);
  if (!Number.isInteger(step) || step < 0 || step >= details.turns.length) throw new Error(`Invalid step for ${scenario}: ${step}`);
  return `You are the Game Master for a saved RPG campaign, acting on one player request.
Use only the RPG MCP server supplied for this run. Discover its tools and load its Game Master guide first.
Reconstruct the current campaign from server state; you have no prior transcript.
Resolve the player's request through authoritative tools and respond in normal player-facing prose.
Stop when this request is complete. Do not invent another player turn or perform unrelated Director/chronicle maintenance. Suspend the session only if the player explicitly asks to end it.
If genuinely blocked, explain the blocker and stop. Do not inspect source code, database files, or test expectations.

Player: ${details.turns[step]}
`;
}
