# RPG MCP — Economy, Treasure, Patrols & Travel Guide (topic: ECONOMY)

## Commerce, Equipment & Magic Treasure (`inventory`)

- Narrate the haggling; let the engine do the accounting. Look up list prices and items with `rules:DEFINITIONS`;
  define local campaign items or custom backgrounds with `rules:DEFINE`.
- `inventory:TRADE` (`BUY` / `SELL`; `negotiated_price` requires `reason`), `inventory:TRANSFER_ITEM`,
  `inventory:GIVE_MONEY` (character-to-character coin — tips, wages, debts — one shared ledger event), and
  `inventory:EQUIP` (enforces slots and SRD 5.2.1 3-item attunement limit; +N bonuses and Ring of Protection apply
  while equipped, other magic items play from `magic_items` text on the sheet).
- Never invent money or treasure: use `inventory:GRANT_LOOT` (`source: ENCOUNTER|QUEST|WORLD|GM_GRANT`).
  Completed quests, major encounters and bootstrap return level-appropriate `treasure` suggestions (rarity mix and
  candidates). Grant SRD magic items by name or template (`+1 Longsword`, `Ring of Protection`,
  `{item: 'Flame Tongue', base: 'Longsword'}`), search more with `rules:DEFINITIONS` (`magic: true, rarity`), or
  craft custom enchantments on a mundane base (`{item: 'Arrow', quantity: 10, magic: {name, rarity,
  damage_bonus_dice: '1d6', damage_type: 'fire', text}}`).

## Estate Accounts, Cash Flows & Calendar (`economy`)

- Treasuries that are not a character's purse (a House, keep or guild) are accounts (`economy:CREATE_ACCOUNT`,
  `economy:TRANSFER_MONEY`, `economy:GET_ACCOUNTS`). Money bags accept `'account:N'`, `'character:N'`, account name
  or `'WORLD'`.
- Rents, tolls, stipends, tithes, debts and dated happenings are `economy:DEFINE_CASH_FLOW` / `UPDATE_CASH_FLOW` /
  `LIST_CASH_FLOWS` (fixed, `percent` `of_rule` or `of_inflows`; `ONCE|DAILY|WEEKLY|MONTHLY|YEARLY|SEASONAL`;
  season multipliers; quest conditions; debt `cap` that closes itself once paid). The engine fires them automatically
  whenever the clock moves (`rest_time:ADVANCE`, `world_travel:MOVE_PARTY`, `rest_time:REST`) — read `consequences`
  and narrate paid/unpaid outcomes; never book them by hand.
- `economy:SET_CALENDAR` anchors Day 1 (`year`, `month`, `day`; 365-day year, no leap years, Monday-based weeks,
  four seasons) driving dates and seasonal cash flows.

## Patrols, Travel Risk & Interruptible Journeys (`patrols`, `world_travel`, `rest_time`)

- **Patrols (`patrols:DEFINE` / `UPDATE` / `STATE`):** Always use a finite roster of existing materialized actors
  and timed waypoints. Never spawn replacement guards after casualties; reinforcements must exist. Regional timers
  pause when no party member remains in the region and resume with remaining duration on return without catching up.
- **Travel risk & journeys:** Author story-aware risk via `world_travel:DEFINE_TRAVEL_RISK` (separate frequency,
  eligible opposition, severity and `RANDOM|AUTHORED` placement; connection-scoped `authored_at_fraction` is a fixed
  road point measured from the regional feature's `from_location_ref` or connection's first endpoint — partial and
  reverse trips retain it and starting at/past it never retriggers).
- `world_travel:MOVE_PARTY` and `world_travel:CONTINUE_JOURNEY` stop at actual contacts or interrupting events;
  inspect `world_travel:TRAVEL_STATE` (never rolls or creates encounters). During travel, bootstrap's location has
  kind `JOURNEY` (`location: null`, `departure_location` as provenance). Party location and `with_party` follow the
  controlled character; use saved `contact_location` explicitly for foes meeting an away companion.
- **Travel rosters:** Default `world_travel:MOVE_PARTY` moves active members/guests at the party's location
  (reporting `left_behind`; separated members do not join). Explicit `characters` on a new trip must be current
  party members/guests or actively player-controlled sharing one actual departure location (so an away companion can
  rejoin; mixed origins refuse). On an interrupted journey, explicit `characters` may only narrow the saved movers
  to leave someone behind (cannot add newcomers). Unaided travel refuses incapacitated/immobile movers without
  spending time; dead/departed movers are removed with reasons (`travel_roster` / `removed_travelers`). Room AREA
  locations use their containing site to join authored travel routes.
- Weave road contacts into the established place, use the saved local grid if combat starts, resolve detection /
  avoidance / negotiation / combat, then call `world_travel:RESOLVE_OPPORTUNITY` (`RESOLVED|AVOIDED|NEGOTIATED|
  CONTINUE`) and continue from the actual position. Short calls and reloads never erase exposure or reroll checks.
- **Timed actions:** `rest_time:TIMED_ACTION` (`SEARCH|WAIT|WORK`, `duration_seconds`, `action_key`, `parameters`)
  saves interrupted progress — resume with the same `action_key` and identical `parameters` instead of granting
  completed benefits early, and narrate returned elapsed time and progress. Use `world_travel:SCHEDULE_EVENT` for
  authored future/regional interrupting events.
