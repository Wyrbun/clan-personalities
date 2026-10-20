# Clan Personalities (`clan-personalities`)

Maintainer reference for the module. Written against the Living World Module
Framework (`docs/MODULE_FRAMEWORK.md`, `docs/MODULE_AUTHORING_GUIDE.md`).

**Current version: 0.2.0.** This file has two halves:

1. **The reference** (everything up to "Changelog") describes the module *as it
   is now*. If it disagrees with an older note anywhere, the reference wins.
2. **The changelog** at the end is history. It records what changed in each
   round and why; statements there may since have been replaced.

Every dial named here is documented in `config/module.ini`, which is commented
key by key. This file explains behavior; the ini is the config reference.

---

## At a glance

| | |
|---|---|
| Phase 1, "the drama" | Chat-only clan social sim. **On** whenever `Enabled = True`. |
| Phase 2, "the fights" | Real field duels outside towns, watched and talked about. **On** by default since 0.2.0: `Phase2 = True`. Set `False` for chat-only. |
| Words | Every spoken line is written live by the pack's FPC brain. There are **no line pools**. No brain means silence. |
| Database | None. Nothing is written to SQL. State lives in three small files (see "State files"). |
| Stock code | Untouched. The module uses only public game types and standard chat packets. |

## Personas

Each living clan you list gets one temperament. A persona is policy only: it
decides who speaks and how hot, never what the words are.

| Persona | Opens drama | Answers for own clan | Answers for alliance | Extra |
|---|---|---|---|---|
| PEACEFUL | never | yes, one calm line | never | Scolds both sides of a fight it watches. |
| NEUTRAL | never | yes | **never** | — |
| ALTRUISTIC | rarely | yes | yes, even when the brother started it | Most likely to do kind deeds. |
| AGGRESSIVE | eagerly | yes | yes | Piles onto others' drama; grudges double its appetite; taunts. |
| PLAYER_KILLING | like Aggressive | yes | yes | Taunts; its fighting happens in Phase 2. |

`PersonaMode = RANDOM` deals temperaments (at most `MaxClansPerPersona` clans
per persona; leftovers stay silent). `EXPLICIT` takes them from `PersonaMap`.
A RANDOM deal is saved to `personas.properties` so it survives restarts.

## Clan identification

`LivingClanIds`, `PersonaMap` and `AllianceGroups` accept per clan either a
**numeric id** or a **name/key token** (e.g. `Azure_Dragon`). Tokens are
matched ignoring spaces, underscores, dashes and case, in this order:

1. `BotClanManager.getClanByKey(key)` (the keys of `game\data\BotClans.xml`);
2. a normalized whole-name match against the clan table;
3. a sighting of a member standing in the world.

A token that never matches is warned about once in the log (after about 30
ticks) and stays silent. Numeric ids win when a name is ambiguous.

**Who counts as a member:** any `Player` whose `getClan()` passes
`BotClanManager.isBotClan(clan)`. Do not use `PhantomManager.isPhantom` for
this: town fake players and living-population bots often fail that check.

`AllianceGroups` is pre-wired with the three alliances in `BotClans.xml`
(DragonPact, PhoenixOrder, RavenCovenant).

## Phase 1: the drama

**Shape of a quarrel.** A clan opens on another (`START`). The target may first
ask what this is about (`QUESTION`), the opener explains (`EXPLAIN`), and only
then the comeback lands. Allies can step in. Each argument runs reply rounds
until it ends: the closing persona weighs a war cry against a peace close. Then
the pair rests for `MinPairRestMinutes`–`MaxPairRestMinutes`.

**Who answers.** Inside an ongoing argument, each beat rolls which rank speaks:
leader (fastest, rarely), officer, or member (most beats). Each rank has its own
reply band. With `ChatterOverlapChance`, a junior voice blurts an `ASIDE` first
and the senior voice takes the word back (`TAKEOVER`).

**Focus.** Only one quarrel develops at a time (`DramaFocus`). New openings
(quarrels, taunts, callouts) wait while an argument still breathes. When it
ends, the stage rests `FocusBreatherSeconds`, then the next one may land. Plain
chatter is never held back.

**Taunts.** Aggressive and Player-Killing clans dare other clans toward a real
fight (`TAUNT`). The dare warms the duel "brew". Cooldowns apply per clan and
world-wide. Alliance brothers are never taunted.

**Threat reading.** Every quarrel line aimed at a clan is classified from cue
words, module-side and without a brain call:

- `THREAT` heats the brew;
- `PROVOCATION` is trash talk;
- `AMBIGUOUS` is threat words plus a laugh.

On an ambiguous line, the target may ask "do you mean it?" (`INTENT_ASK`). The
speaker then confirms or waves it off (`INTENT_ANSWER`), by temperament. The
reading is passed to the brain, so replies answer it for what it was.

**Kindness.** On calm ticks, a clan may do another a good turn. The helped clan
thanks it (`PRAISE`), and a hot-blooded helper may brag (`COCKY`). Favors are
remembered per pair and come up in later quarrels.

**Memory and stories.** A bounded RAM ring keeps recent exchanges, so replies
continue the argument instead of starting over. Grudges stay warm for
`GrudgeWindowMinutes`. A finished argument becomes a war story for
`StoryWindowMinutes`. A clan may retell it, and a clan-less bystander may ask,
after which both clans give their own version.

**Shorthand.** Bots shorten long clan and alliance names, coin nicknames, and a
bystander occasionally asks who "IC" is. Coined nicknames persist in
`nicknames.properties`.

**Typos.** Lines are delivered exactly as the brain wrote them. When a line
shows a visible slip, the same bot may correct itself a few seconds later, at
`SelfCorrectionChance` (`TYPO-FIX`).

**Pacing and caps.**

- `MinBeatSpacingSeconds` is the read-and-type floor between any two clan lines.
- `ClanCooldownSeconds` keeps one clan from dominating.
- `MaxMessagesPerHour` (default 200) is a safety net. Lines over the cap are
  postponed, not dropped.
- Openings after boot: one guaranteed quarrel within `FirstDramaMaxSeconds`.

## Phase 2: the fights (`Phase2 = True`, the default)

Phase 2 turns some quarrels into **real duels**, using the pack's own combat
with no scripted outcome. It runs **only** for the duel's own participants.
Field phantoms and NPCs that are out hunting are never touched.

1. **The brew.** Quarrels, threats and taunts heat a pair's brew. When it boils,
   the engine stages a fight. While no brew is warm, it skips the arena scan
   entirely.
2. **The arena.** A piste is sited just outside a town gate, on open, dry and
   walkable ground. It keeps clear of gate guards (`GuardClearRadius`, 350) and
   aggressive monsters (`MonsterClearRadius`, 700). Every town is a candidate:
   Giran first when in reach, then teleport towns by distance, then the rest.
   The place is announced in world chat (`FIELD-WHERE`).
3. **The squads.** Fighters are temporary copies of the clans' town bots,
   spawned at the arena.
   - They are matched for fairness (`MaxLevelGap` 5, `MaxGearGap` 6).
   - Each is fitted with a weapon its own skills can use, at its level's grade
     (`GEAR`).
   - Pairs from different alliances are preferred. A same-alliance fight still
     happens, and the crowd remarks on it.
   - Fighters are discarded after the fight and are never saved as characters.
4. **The crowd.** Spectators spawn first and mark the spot. While waiting, they
   gossip about the fighters' class, gear and history, and the fighters confirm
   or deny it (`CROWD-TALK`, `FIGHTER-REPLY`).
5. **The fuse.** The fight starts when the player can watch. The fighters trade
   jabs that fill a hidden aggression meter (`AggressionPerJab`), and they
   strike at 100 (`FIELD-FUSE`, `FIELD-STRIKE`).
   - "At the fence" means within `FenceRadius` (900) of the arena, so every
     local line is in earshot (local chat reaches 1250).
   - If you are very close (`VeryCloseRadius` 600), the jabs and the fence wait
     speed up.
   - Nobody waits longer than `FenceMaxWaitSeconds`.
6. **The fight.** Each fighter plays its class role. It casts skills first, and
   casters do not melee. Healers and buffers support first: they heal the most
   wounded squadmate (under 70% HP) and lay up to 4 buffs, and only swing a
   weapon when out of mana or alone on their side (`SUPPORT`). A 1v1 never fields a
   lone healer or buffer (the Orc Mystic line excepted: it has attack spells). Skills that are useless in PvP are never used
   (`FieldSkillBlacklist`: Spoil, Sweeper, Sow, Fake Death, and so on).
   - The fight ends at a death or at `EncounterFightMaxSeconds`. At the time
     limit, the side that bled less holds the field; otherwise it's a draw.
7. **After.** The winner gloats (`FIELD-GLOAT`). The crowd and the loser react,
   and a hot-blooded loser may blame idle alliance members by name. Bodies stay
   where they fell. Everyone only *retaliates* against monsters during a 25–35
   second aftermath, then the cast is released.
8. **Records.** Completed duels, and quarrel endings, are stored per clan pair in
   `records.properties` (`RECORD`). The next quarrel between the pair brings
   the history up. A clan that lost wants revenge, and its brew heats faster
   (`RevengeBoost`, `REVENGE`). Fights that never began are not recorded.

Heralds (road-side announcers) are retired. `FieldHeralds = 0` is the default,
and the code path remains only as a dial.

`PurgeSavedClones` is a one-time cleanup for databases touched by builds before
2026-10-19, which saved fight clones as characters. It defaults to `Off`.
`Preview` only lists matches. A fresh install never needs it.

## The brain (required)

The pack's FPC brain (`brain\fpc_brain.py`, Flask + Ollama or a cloud
provider; `BrainUrl`, default `http://127.0.0.1:5000/chat`) writes every word.
The module composes a situation prompt with the clan, temperament, rival,
cause, recent exchanges and the Interlude-only world law. The brain answers
with one line, and the module sanitizes it to the first non-empty line, length
capped.

**Which door.** `BrainVoiceMode = PARTYEVENT` (the default) uses the brain's
directed one-line mode:

- The module describes the moment, and the bot says one line for it.
- The brain's shared shout log is neither shown to the model nor written to,
  so module prompts never pollute it and world chatter never pulls the line
  off-topic.
- Every request carries `X-Directed-By: <BrainDirectedHeader>`, so the brain
  can recognise module traffic.
- `X-Human` is **never** sent on this door. `BrainHumanFlag` only matters on
  the `LEGACY` (SHOUT/SAY) door, which exists only as a fallback.

**Threading** (the game's threads never wait on a generation):

- Every brain call is a job on the pack's `BrainExecutor`, and the line is
  delivered back on the game's `ThreadPool`.
- The **brain gate** keeps at most **one** module job on the executor at a time.
  The executor has four workers shared with every brain caller in the pack,
  including players' whispers and party replies. Module calls are serialized
  anyway, so one slot loses nothing and a drama or fight burst can never fill
  the pool.
- Further jobs wait in a short module-side queue (6). A job that waited over
  25 s is dropped, because its moment has passed. A full queue drops its
  oldest job (`BRAIN-GATE`).
- **The breaker fails fast.** After a brain failure, every call in the next
  60 s returns at once with no line, instead of sleeping on a worker.

**Boot.** A background thread probes the brain, so server start never waits:

- If the probe succeeds, the module goes live.
- If it fails, the module logs a SEVERE verdict and stays silent; restart once
  the brain runs.
- A brain that dies mid-game means silence. Nothing canned is ever spoken in
  its place.

**Warm-keeper.** One tiny ping every 3.5 minutes keeps the model loaded in
VRAM. It:

- starts only after a successful probe;
- stops when the module is disabled;
- pings only while a **real human** is connected. Phantoms, living-population
  bots, offline traders and detached clients don't count, so a metered cloud
  brain costs nothing overnight.

**Sharing the brain with players.** The module uses the same brain as the
players' own whispers and party chat. One live session measured about 130
module lines per hour during duel play (30 world, 12 local in 19 minutes). The
module limits its share in four ways:

- the one-slot gate;
- one quarrel at a time (focus);
- `MinBeatSpacingSeconds`;
- `MaxMessagesPerHour`.

On a slow model or a paid API, lower `MaxMessagesPerHour` first.

Drama speaks in world chat (`ChatChannel = SHOUT`) by design, so it is
witnessed anywhere. Arena and crowd talk is local chat around the arena.

## State files

All three live in the module folder, resolved through
`ModulesConfig.getModulesRoot()` (older pack builds fall back to a relative
path). Each is a plain `.properties` file. Deleting one resets that memory.

| File | Holds |
|---|---|
| `personas.properties` | The RANDOM persona deal. |
| `nicknames.properties` | Clan nicknames the bots have coined. |
| `records.properties` | Per pair: duel wins and losses, and how quarrels ended. |

## Layout

```text
clan-personalities/
  module.json                  # manifest; empty reserves, no database
  config/module.ini            # every dial, commented
  scripts/
    ClanPersonalityModule.java # entry point (GameModule): config, boot probe, keeper
    BrainClient.java           # HTTP client for the FPC brain; breaker; keeper
    DramaEngine.java           # the engine: drama, focus, reading, Phase 2 duels, brain gate
    ClanSpec.java              # configured clan: token/id + persona + group
    ClanProfile.java           # resolved clan: name, cooldown, presence
    ChatQueue.java             # randomized-delay scheduled sends
    ChatMemory.java            # bounded ring of recent exchanges
    PersonaType.java           # the five temperaments as policy data
    MessageKind.java           # every beat kind (START, QUESTION, TAUNT, INTENT_ASK, ...)
  MODULE.md
  README.md
```

## Ownership notes

- **No stock class is edited.** Speech goes out as `CreatureSay` packets.
  Duels use the pack's own combat, AI and `PhantomManager` spawn and discard
  calls.
- **Real wars are untouched.** The module never declares wars or flags anyone.
  Damage in a duel comes only from the duel's own temporary fighters.
- **One scheduled tick** (`ThreadPool.scheduleAtFixedRate`), plus per-entry
  `ThreadPool.schedule` sends. The V1 `ModuleContext` has no module scheduler
  yet; move to it when the platform adds one.
- **No id reservations** (`reserves: {}`), and no database.

## API facts (confirmed against this pack's `GameServer.jar`)

- `GameModule`, `ModuleContext` and `ModuleConfig` are in
  `org.l2jmobius.gameserver.modules.*`. `context.logging()` is a
  `java.util.logging.Logger`.
- Chat: `CreatureSay(Creature, ChatType, String)`. `ChatType` is in
  `org.l2jmobius.gameserver.network.enums`.
- Clans: `ClanTable.getInstance().getClans()`, `Clan.getId()`, `Clan.getName()`.
  Bot clans come from `BotClanManager` (`getClanByKey`, `isBotClan`).
- `Player` has no `isFakePlayer()`. `PhantomManager.isPhantom(Player)` exists
  but misses town bots (see "Who counts as a member").
- `PhantomManager.spawnPartyMemberAs` ignores its level argument. It returns
  `null` when the name already exists in the DB, at `PhantomMaxCount`, or when
  the template is null. Use `discardLivingPopulationPhantom` to remove a
  temporary fighter: `coolLivingPopulationPhantom` saves it to the DB.
- `BrainExecutor.runBrainWork(work, onComplete)` runs `onComplete` exactly once,
  whether the job ran, went stale, or was refused. The brain gate relies on
  this.
- Skill weapon conditions: `ConditionUsingItemType` inside `Skill`'s
  pre-conditions, with `WeaponType.mask()` bits.

## Log marks (with `Debug = True`)

| Area | Marks |
|---|---|
| Drama | `START` `QUESTION` `ANSWER` `ASIDE` `TAKEOVER` `AMBIENT` `KINDNESS` `STORY` `STORY-ASK` `HISTORY` `NICKNAME` `IDENTIFY` `WHO` |
| Focus and reading | `FOCUS` `TAUNT` `READ` `READ-ASK` `READ-CONFIRM` `READ-DEFUSE` |
| Typos | `TYPO-SLIP` `TYPO-FIX` |
| Support | `SUPPORT` |
| Duel staging | `FIELD-SCAN` `FIELD-TRY` `FIELD-STAGE` `FIELD-MATCH` `GEAR` `SKILLS` `SPECTATORS` `FIELD-WHERE` |
| Before the blows | `FIELD-FUSE` `FIELD-STRIKE` `CROWD-TALK` `FIGHTER-REPLY` `FIELD-TRASH` |
| The fight and after | `FIELD-FIGHT` `FIELD-BLOWS` `FIELD-KILL` `FIELD-WIN` `FIELD-LOSS` `FIELD-DRAW` `FIELD-GLOAT` `FIELD-BLAME` `CROWD` `FIELD-EXIT` |
| Records | `RECORD` `REVENGE` |
| Brain | `BRAIN-GATE`, `brain miss ... line skipped` |

## Lifecycle and both off states

- **Disable:** set `Enabled = False` and restart. Nothing registers; the
  server is stock.
- **Remove:** with the server stopped, delete
  `game/modules/clan-personalities/`. There are no tables and nothing outside
  the folder.
- **Release check:** both states must give stock behavior, with no clan chat
  and no module log lines.

## Known pack issue (not this module)

`NullPointerException` in `FakePlayerBehaviorManager.placeShopkeeper`, about
once per Living Population tick. The full trace from `error0.log` is all pack
code:

```text
Spawn.doSpawn            (_template is null)
FakePlayerBehaviorManager.placeBot        (:765)
FakePlayerBehaviorManager.placeShopkeeper (:645)
LivingPopulationManager.syncShopkeepers   (:361)
LivingPopulationManager.safeSnapshotTick  (:2899)
```

It happens with this module disabled too and is harmless to it. It has been
reported to the pack author.

---

## Changelog

History, newest first. Where an entry contradicts the reference above, the
reference describes the current behavior.

### 0.2.0 (2026-10-19): Phase 2 ready for public testing

`Phase2` now defaults to `True`. It was `False` through 0.1.x.

**Fights**

- Arenas site reliably. The old guard veto rejected spots next to the door
  guards themselves, and only one town was tried. Guards and monsters now have
  separate clearances, and every town is a candidate.
- Fighters spawn at the arena. Names that already exist in the DB are skipped.
  Fighters are discarded after the fight and never saved.
- An aggression fuse replaces the old fence timer, which re-armed forever while
  you watched. "At the fence" is now 900, and standing very close speeds
  things up.
- Fair matching by level and gear. Weapons are fitted to the fighter's
  skills, roles are played (casters cast), and a PvP skill blocklist applies.
- Every fight has spectators. The crowd gossips with the fighters, and winners
  gloat. Heralds are retired.
- Alliances: rivals from other alliances are preferred, and same-alliance
  fights are remarked on. Alliance names come from the configured group name.
  Before, an alliance was named after its last clan.
- Persistent duel records and revenge (`records.properties`).
- An aftermath guard: bodies stay put, and survivors only retaliate against
  monsters.
- The arena scan is skipped while no brew is warm.
- The duel's heartbeat starts at staging, not after the lead, so crowd talk and
  jabs begin as soon as a human comes near. Only real humans count as watchers:
  spectators, bots and phantoms no longer hold the first blow or close towns.
- Healers and buffers heal and buff their squad instead of meleeing.
- A 1v1 never fields a lone healer or buffer; the Orc Mystic line is the exception.

**Drama**

- Drama focus, taunts, and threat reading with clarification.

**Brain**

- PARTYEVENT door by default. Multi-line replies are cut to the first line.
- A line about a place must name its town, and is retried once if it doesn't.

**Review fixes (Teras)**

- One-slot brain gate on `BrainExecutor`.
- The breaker fails fast instead of parking a worker.
- `X-Human` is no longer sent on the PARTYEVENT door.
- The warm-keeper counts only real humans.

### 0.1.1: hardening

- All brain work moved to `BrainExecutor`. Before, it blocked the game's
  scheduled pool.
- The boot probe moved to a background thread. Before, a cold Ollama could
  stall server start by up to about 105 s.
- The warm-keeper starts only after a successful probe and stops on disable.
- State files resolve via `getModulesRoot()`.
- Bystanders are picked at random. Before, the first match always asked.
- The release zip excludes `.git`.

### 0.1.0 and earlier rounds: Phase 1

- Brain-required contract. All static line pools and the `UseBrain` switch
  were removed; a downed brain means silence.
- Proper exchanges (question, explanation, comeback), rank-paced replies,
  asides and takeovers, kindness, war stories, shorthand and nicknames, kept
  typos with rare self-correction.
- Reliability: brain timeout raised from 8 s to 45 s, and brain calls
  serialized.
- The hour cap became a safety net (200). Before, a low cap made bursts of
  lines followed by a dead hour.
- Interlude-only world law in every prompt.
- `X-Human` was used to dodge the brain's topic cooldown on the old SHOUT door.
  PARTYEVENT replaced that in 0.2.0.
