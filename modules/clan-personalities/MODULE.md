# Clan Personalities (`clan-personalities`) — Phase 1

Module notes for whoever maintains the server. Written against the Living World Module
Framework (`docs/MODULE_FRAMEWORK.md`, `docs/MODULE_AUTHORING_GUIDE.md`).
**Current version is Phase 1, "the drama": chat only.** No combat behavior of any kind
ships here — see the Phases section.

## What it does

Assigns a **persona** to each living clan you list in config. The clan's phantom members
carry the clan's voice in world chat: low-key chatter, and staged drama which other clans
answer according to their own temperament.

Personas (pure policy, per the framework's "core provides capabilities, modules provide
features" split — dramatic voice only, no mechanics):

| Persona | Opens drama | Answers for own clan | Answers for alliance | Extra |
|---|---|---|---|---|
| PEACEFUL | never | yes, one calm line | never | — |
| NEUTRAL | never | yes | **never** (never assists brothers — per concept) | — |
| ALTRUISTIC | rarely | yes | yes, even when the brother started it | — |
| AGGRESSIVE | eagerly | yes | yes | may pile on others' drama; grudges double its appetite |
| PLAYER_KILLING | like Aggressive | yes | yes | combat behavior is a later phase, not shipped here |

Anti-clutter rules (all config):
- At most `MaxClansPerPersona` clans share a persona in RANDOM mode; leftover clans stay silent.
- Everyone speaks through a shared queue: each message waits a random 1–8 minutes.
- Global cap `MaxMessagesPerHour` — extras are postponed, never dropped.
- A clan that spoke goes silent `ClanCooldownMinutes`.
- A third-party aggressive pile-on only with `OpportunisticChance`, and at most
  `MaxResponsesPerDrama` clans answer one opening.
- Replies never chain further, so an argument always runs one round and ends.

Memory (RAM, this phase): a bounded ring of recent exchanges, used to (a) double the
drama appetite of grudge-holders, and does not persist across restarts — hence no SQL.

## Clan identification

`LivingClanIds`, `PersonaMap`, and `AllianceGroups` accept, per clan, either

- the clan's **numeric id**, used as-is, or
- a **name/key token** (e.g. `Azure_Dragon`), resolved at runtime.

Name matching normalizes spacing, underscores, dashes and case, so `Azure_Dragon`,
`Azure Dragon`, and `azure dragon` all match the same clan. Resolution happens

1. against the live clan table (attempted every tick while anything is pending), and
2. against clans seen in the world when a member of one stands there.

A key that never matches is **warned about once** in the server log after ~30 ticks
listing the unmatched tokens — a misspelled key is harmless but inert. Numeric ids are
the authoritative fallback when a key is ambiguous.

## The LLM tier (FPC brain)

The pack ships an optional local brain (`brain\fpc_brain.py`, Flask + Ollama /
DeepSeek; endpoint `http://127.0.0.1:5000/chat`). When `UseBrain = True`, the
module asks it to write each outgoing line (`POST /chat`, headers `X-FPC` =
the speaking member's real name so the brain keeps its stable voice,
`X-Mode`, `X-Location`; body = a composed situation prompt that includes clan
membership, temperament, the drama target and the last remembered exchanges).
The brain already enforces single-line, tag-free replies; the module re-checks
length and newlines regardless.

Failure contract (the reason this is safe):
- **Unavailable/slow/error** → the configured line pools answer that message
  unchanged; the module pauses brain attempts for 10 minutes afterward.
- All tempo rules stay module-owned: personas, queue, caps, cooldowns decide
  who speaks when; the brain decides only the words.
- The brain being off (it is optional by the pack's design, see
  `brain\README.md`) degrades tone, never behavior.

## Layout

```text
clan-personalities/
  module.json                  # manifest; empty reserves, no database, no hooks
  config/module.ini            # master switch + personas + tempo + line pools
  scripts/
    ClanPersonalityModule.java # entry point (GameModule), modules.clanpersonalities
    BrainClient.java           # optional LLM tier; talks to the pack's FPC brain
    DramaEngine.java           # the engine: resolution, rolls, gates, reactions
    ClanSpec.java              # configured clan: token/id + persona + group
    ClanProfile.java           # resolved clan: name, cooldown, presence
    ChatQueue.java             # randomized-delay scheduled sends
    ChatMemory.java            # bounded ring of recent exchanges
    PersonaType.java           # the five temperaments as policy data
    MessageKind.java           # AMBIENT / START / RESPONSE
  MODULE.md
  README.md
```

## Ownership notes

- **No stock class is edited.** The engine speaks through `Say` packets and normal game
  types (`World`, `Player`, `ClanTable`, `ChatType`); drama is voice-only.
- **One scheduled tick** (`ThreadPool.scheduleAtFixedRate`); queue sends use
  `ThreadPool.schedule` per entry. There is no module scheduler on the V1
  `ModuleContext` surface yet; switch to it when the platform adds one, so ownership
  tracking covers those too.
- **No id reservations** (`reserves: {}`), **no database**, no shared-file writes.
- **Real wars are untouched**: the module cannot declare wars, flag anyone, or deal damage.

## Adaptation notes (confirmed against this pack, 2026-10-17)

Resolved by reading `GameServer.jar` (`javap`); these are no longer guesses:

- `GameModule`, `ModuleContext`, `ModuleConfig` live at
  `org.l2jmobius.gameserver.modules.*`; `ModuleContext.logging()` returns
  `java.util.logging.Logger` (use `.info(...)` / `.warning(...)`).
- `ChatType` is `org.l2jmobius.gameserver.network.enums.ChatType`
  (`GENERAL` exists); the chat packet is `CreatureSay(Creature, ChatType, String text)`
  at `org.l2jmobius.gameserver.network.serverpackets` (there is no `Say` class here).
- Clan access: `org.l2jmobius.gameserver.data.sql.ClanTable.getInstance().getClans()`;
  `Clan.getId()` / `Clan.getName()` at `org.l2jmobius.gameserver.model.clan.Clan`.
- Player: `getClanId()`, `getClan()`, `sendPacket(...)` all exist. **There is no
  `isFakePlayer()`** — see the membership note below.
- `World.getInstance().getPlayers()` returns `Collection<Player>` ✔;
  `ThreadPool.scheduleAtFixedRate(...)` and `Rnd.get(...)` are as used.

### Round-two corrections (after the "silent module" diagnosis)

- Clan resolution: configured keys are the exact keys of the pack's
  `game\data\BotClans.xml` (e.g. `Azure_Dragon` → in-game name `AzureDragon`).
  The engine resolves pending keys first via
  `BotClanManager.getInstance().getClanByKey(key)`, then by normalized whole
  clan-table name match, then by world-scan member sighting.
- **Membership/presence: a member is any `Player` whose `getClan()` passes
  `BotClanManager.getInstance().isBotClan(clan)` — NOT
  `PhantomManager.isPhantom(player)`.** Town fake players and
  living-population bots do not reliably pass the phantom check, which is what
  silently silenced the module in the first rounds (everyone "absent").
- `BotClans.xml` defines three ready-made alliances (DragonPact: Azure,
  Emerald, Frost Moon, Silver Wolf; PhoenixOrder: Crimson, Golden Lion,
  Sun Spear, Ivory Crown; RavenCovenant: Blood Raven, Obsidian Eye, Iron
  Hammer, Violet Spider) — pre-wired into `AllianceGroups` in module.ini.
### Round-three additions (tempo, arcs, shorthand)

- Tempo: `ClanCooldownSeconds` (default 45) replaced the old minutes-based
  cooldown; `MinReplyDelaySeconds`/`MaxReplyDelaySeconds` (30–90) pace reply
  beats. A queued message is the clan's reserved floor and only re-checks
  hour cap and member presence at delivery, never its own cooldown.
- Drama arcs (`MaxDramaRounds`, `ResolveChance`): an argument runs reply
  beats until a solution - the closing persona weighs fight vs peace
  (`ResolveFight`/`ResolvePeace` pools) - then the pair rests
  `MinPairRestMinutes`..`MaxPairRestMinutes` (defaults 5–10) via
  `arcKey("min-max")` gates; `pickTarget` skips resting pairs.
- Shorthand (`Shorthand`, `ShorthandChance`) shortens clan/alliance display
  names to initials in delivered lines; `Clarify` (6h per token) schedules a
  clan-less bystander ask (`%ABB%`) and the named clan's answer
  (`%ABB%`, `%CLAN%`). Choices of asker: crest-less fake players first, then
  living-population phantoms; never the human player.
- Brain transparency: `BrainClient.probe()` logs one boot-time reachability
  verdict; delivered lines report their source as `brain` or `pool`, and a
  `brain miss` line explains each fallback. `parseLines` strips wrapping
  quotation marks, so quoted config entries never leak `"` into chat.


## Phase roadmap

1. **Phase 1 (this version): the drama.** Persona chat sim only.
2. **Phase 2: the fights.** Clan-war reactivity, `encounters()`-based retaliation hunts,
   the Player-Killing persona's field behavior. Requires first verifying clan/phantom
   event APIs against the server source.
3. **Phase 3: the deployments.** Sustained altruistic assists, and any generic platform
   hook the core team must add (persistent phantom hostility is the candidate).

## Lifecycle

- **Disable**: `Enabled = False`, restart. Nothing registers; the server is stock. RAM
  memory has nothing to preserve.
- **Remove**: with the module disabled or the server stopped, delete
  `game/modules/clan-personalities/`. Whole uninstall; no tables, nothing outside the dir.

## Both off states (release checklist)

1. `Enabled = False` + restart → stock behavior (no extra clan chat, no logs).
2. Directory removed + restart → stock behavior again.

## Brain-required contract (latest round)

- **Every spoken word is brain-written.** Drama lines, argument endings
  (war cry / peace close), takeover brags, the "who speaks now?" exchange,
  clarify asides and typo corrections — all come from the FPC brain fresh.
  The static persona pools, `ResolveFight`/`ResolvePeace`,
  `TakeoverAnnounce`/`LeadershipAsk`/`LeadershipReply`, `ClarifyAsk`/
  `ClarifyAnswer` and `Offense*` template keys are GONE from the ini.
- **Boot probe decides activation.** The entry point probes
  `BrainUrl` before registering anything. Unreachable → a SEVERE error in
  the log ("this module's voice is entirely brain-written and REQUIRES the
  brain...") and the module stays inactive; the server remains stock.
- **Runtime outage → silence, never substitution.** A brain miss skips that
  one line (logged as `brain miss ... line skipped` when Debug is on) and
  the circuit breaker sleeps 10 minutes before the next attempt. A downed
  brain makes the clans hold their tongues; it never plays canned text.
- **Offense causes are now plain-language labels** (`OffenseCauses`,
  comma-separated, fed verbatim to the brain as "the quarrel is really
  about: X"). The pair still remembers its cause while the grudge is warm.
- **Typos are kept, not fixed.** Brain lines deliver exactly as written.
  When a line contains a *visible accidental slip* (doubled word like
  "the the", missing space after punctuation, stray space before
  punctuation), the same bot may — at `SelfCorrectionChance` (default 10%,
  applied only to slipped lines) — send one short player-style self
  correction 8–20s later, logged as `[TYPO-FIX]`. Bots never intentionally
  typo on schedule; chat is not flooded.
- `UseBrain` config key is removed (the brain is not optional anymore).
  Engine APIs renamed accordingly (`setOffenseCauses`, probe returns an
  error-or-null, `brainCustom` for one-off brain lines).

## Faster start, rank-paced beats, and kindness (latest round)

- **Warm-up drama**: within `FirstDramaMaxSeconds` (default 300 s) after
  boot the engine forces one opening quarrel, so the stage is set early.
  Normal pacing resumes after it; new quarrels/ambient lines sit in a
  short queue window (`Min/MaxOpeningDelaySeconds`, 0–120 s).
- **Beat pacing by rank** — ONGOING arguments only: every beat rolls WHO
  answers (leader `LeaderReplyChance` %, officer `OfficerReplyChance` %,
  members the rest) and waits that rank's random band
  (`Leader/Officer/MemberReplyBand`, "min-max" seconds). The leader is the
  fastest; if the argument's last reply dragged past
  `LeaderPatienceSeconds`, the leader steps in with `LeaderPatienceChance`
  instead. No reply waits longer than a minute.
- **Talking over each other**: with `ChatterOverlapChance`, a beat comes
  with an `ASIDE` first — a second, JUNIOR voice of the same clan blurts
  right before the main reply; the senior voice then visibly takes the
  word back (its scheduled rank beats the aside's rank; the takeover
  announce chance applies). Asides advance no arc rounds.
- **Kindness** (positive beats, `KindnessChance` % per tick, only while NO
  argument runs when `KindnessCalmOnly`): a clan does another a good turn,
  helped clan sends `PRAISE`, and a hot-blooded helper answers `COCKY`
  with `CockyReplyChance` %. Initiator odds scale by temperament
  (altruistic 100% … player-killing 10%). Kind deeds are remembered per
  pair (`goodDeeds`) and injected into later drama prompts as shared
  history, so quarrels can reference past favors.

## Proper exchanges (question, explanation, half-minute bands)

- **The called-out clan asks first** (`QuestionChance`, default 40%): instead
  of firing back blindly, it demands the point - "what do you want from
  us?" - the accuser then explains its side (`EXPLAIN`, grounded in the
  incident), and only then comes the real comeback. Every question earns an
  answer.
- **Faster bands everywhere** (leader 6-14 s, officer 12-24 s, member
  18-35 s; `LeaderPatienceSeconds` 30) - exchanges no longer dawdle, and
  `MaxMessagesPerHour` default rose to 24 (a low cap postpones whole
  conversations and reads as "long delays").
- **One intent per line**: the global brain constraint forbids mixing a
  threat with a request/invite/question in one message; the kindness prompt
  says "pick exactly ONE way".
- **Stalled-arc sweep**: chains whose far side left the world (or whose line
  ate a brain miss) close themselves silently after 5 minutes.

## Why the world went quiet mid-drama (brain reliability round)

- **Root cause found**: `BrainTimeoutMillis` was 8 s, but a local 12B model
  writing a drama line can take 20-40 s. One slow generation failed, and the
  old circuit breaker then silenced EVERY clan for ten whole minutes - which
  played exactly like "the drama started, then nothing ever came of it".
  Now the wait is 45 s (matching the pack's own chat bridge) and a failure
  pauses attempts for only 15 s.
- **A miss can no longer kill an exchange**: the whole question -> explanation
  -> comeback chain (and kindness chains) is scheduled BEFORE the line is
  spoken, so a skipped bot is just one silent clanmate; the argument keeps
  breathing. Each dropped line also gets ONE quick retry before it is
  abandoned, and brain calls are serialized so two beats landing together
  cannot make each other time out inside Ollama.

## Cross-game slips and the onward debate that stopped (world-fidelity round)

- **Why an argument died after one round**: the brain runs an anti-spam layer
  for self-initiated chatter — once words dominate the shout log they land on
  a 4-minute topic cooldown, and any further request that even MENTIONS them
  is answered with an empty reply WITHOUT calling the model. Drama beats
  unavoidably name the rival clan, so the first exchange "exhausted" the clan
  name and every question/explanation/comeback after it got silently killed.
  The module now marks its requests `X-Human` — the brain's own exemption for
  directed speech ("a real player is never blocked by topic cooldown"). Our
  pacing stays module-owned: hour caps, arc state and cooldowns still decide
  when anyone talks.
- **Why bots invented foreign lore** ("alligators in Glast Heim", "P grade
  armor"): those are Ragnarok / invented-game references leaking from the
  model's own training. Every prompt now carries a WORLD LAW: Lineage 2
  Interlude places, mobs, adena and D/C/B/A/S grades only — nothing from any
  other game, nothing invented, stay vague rather than name a fake thing.
  Applied to all drama beats and to every custom line (takeovers,
  self-corrections, "who speaks" asks, shorthand clarifications).

## Human pacing: nobody types instantly over someone else (pacing round)

- **The read-and-type floor**: `MinBeatSpacingSeconds` (default 8) - no two
  clan lines land closer than this anywhere in the engine. Beats that fall
  due together hold their turn for the remainder, like a player reading the
  last shout before typing their own. Every line kind (drama, takeover,
  resolution, clarify, typo-fix) is stamped and paced the same way.
- **Rank reply bands opened a little** (leader 10-20 s, officer 15-30 s,
  member 22-42 s) so exchanges breathe instead of machine-gunning.
- **A leaked "Name: text" artifact is stripped**: when the brain copies the
  chat-log format ("dùnettha: gotta find..."), the leading close-variant of
  the speaker's own name is removed before the line is spoken. Real spoken
  entries like "Trader: come to the gate" are never touched, and genuine
  typos inside the words still stay exactly as written.

## The hour-long silence after a good run (budget choke round)

- **Root cause found from a live log**: the world spoke 34 lines in six
  minutes (great), which exhausted the old `MaxMessagesPerHour = 24`
  budget; every later beat was then postponed SILENTLY until the next hour
  window - burst, dead hour, burst. The cap is now a pure safety net
  (default 200) because the read-and-type spacing floor alone prevents
  flooding.
- **Honest accounting**: every spoken kind (drama beats, takeover
  announcements, resolution closings, story retells, clarify asks) now
  counts against the budget at one door, inside the central fan-out.
- **The tick never stacks drama**: a rivalry already carrying one queued
  opening is skipped by further START rolls until that entry is delivered
  or expires - the queue no longer piles duplicates on idle pairs.

## War stories (old quarrels remembered)

- `GrudgeWindowMinutes` raised (default 120): clans hold a fresh anger much
  longer before it fades into memory.
- New: a FINISHED argument becomes a war story, kept for
  `StoryWindowMinutes` (default 360). On rare calm ticks (`HistoryChance`),
  a clan tells an old quarrel aloud; with `StoryAskChance`, a clan-less
  bystander may ask what happened - and then BOTH clans give their own
  version, each brain-written, each remembering it their way. Fresh
  quarrels overwrite the pair's older story.
