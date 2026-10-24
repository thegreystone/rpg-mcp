# Backlog

## Narrative direction

- [x] **Minimum Director-to-GM/DM handoff** (recorded and implemented 2026-10-24 for 0.2.0).

  Director reviews publish actionable `GM_ONLY` opportunities through existing story beats. Bootstrap and
  `get_context SCENE` include a bounded `gm_brief` and stable references; `get_context STORY_BEAT` retrieves
  full details without exposing `DIRECTOR_ONLY` planning. Optional briefing fields preserve existing campaigns.
  The guide and tool descriptions make publication, refresh, manual condition checks and open player choices explicit.

  Implemented minimum:

  - A Director review can persist a hook, location, actors and intentions, availability conditions, clues,
    illustrative approaches, consequences and developments if ignored as ordinary story-beat payload fields.
  - Compact briefings have per-beat and shared text limits, retain every reference, and flag clipped guidance.
    Legacy string summaries or descriptions supply a fallback; full payloads remain available on demand.
  - Fresh-context and visibility regression coverage verifies usable published guidance, scoped retrieval,
    private planning isolation and compatibility with existing beats.

- [ ] **Broader narrative opportunity lifecycle** (deferred; unscheduled).

  The minimum handoff supplies guidance; the GM still interprets conditions and changes beats explicitly.
  Automatic condition evaluation, opportunity ranking, scheduling and private-plan promotion remain deferred.
  Broader behavioral coverage should exercise unexpected approaches and ignored hooks through later Director
  reviews, and confirm that changed circumstances produce appropriate revised or superseded opportunities.

  Related: [Director protocol](MCP_PROTOCOL.md#18-narrative-director),
  [GM guide](../src/main/resources/protocol/guide.md),
  [visibility regression tests](../src/test/java/se/hirt/mcp/rpg/NarrativeVisibilityTest.java).
