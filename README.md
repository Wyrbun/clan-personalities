# Clan Personalities

**Clans in your Lineage 2 world that actually *talk* — and settle it with steel. They argue, hold grudges, help each other, remember everything, and when a feud boils over, they fight it out in front of you.**

**Latest: [v0.2.1](https://github.com/Wyrbun/clan-personalities/releases/tag/v0.2.1)** — Phase 2 field duels, public testing.

This module gives living clans five personalities (Peaceful, Neutral, Altruistic, Aggressive, Player-Killing). Their bots speak in world chat through the pack's FPC brain: they pick quarrels over concrete incidents, taunt and threaten, fire back at whoever calls them out, drag their allies in, soften into kindness when the stage is calm, and retell old wars days later — each side remembering it their own way.

When words aren't enough, the quarrel becomes a **real NPC-vs-NPC duel** just outside a town gate: announced in world chat, matched fairly by level and gear, fought with the pack's own combat (mages cast, healers heal), watched by a gossiping crowd — and remembered. Winners gloat; losers want revenge next time.

Every single word is written fresh by the local LLM brain, in each bot's stable voice. There are no canned line pools anywhere in this module.

---

## What it looks like

A real exchange from a live server (debug log, unedited):

| Bot | What they say |
|---|---|
| *VioletSpider member* | emerald serpent needs to learn some manners about mob spots. |
| *EmeraldSerpent member* | violet spider cries about mob spots like its a big deal. |
| *EmeraldSerpent member* | Seriously? What's the big deal about a few mobs? |
| *(bystander)* | yo, what clan is sw? |
| *SilverWolf member* | yeah, sw's our clan, you know, silverwolf. |
| *(bystander)* | kronwerk drops, eh? :P kinda sus timing tbh |
| *EmeraldSerpent leader* | *(war cry or peace close, by their temperament)* |

Clans remember: a clan helped two hours ago may get a salute in today's quarrel; a clan that got caught "spawn camping" may hear about it again tomorrow.

And when it boils over (live log, 2026-10-19 — a taunt that became a 3v3 outside Giran):

| Bot | What they say |
|---|---|
| *SunSpear member* | EmeraldSerpent keeps their hands full, too scared to claim what they took. |
| *EmeraldSerpent member* | sunspear still crying about scraps, meet us at the gates. |
| *(world chat)* | EmeraldSerpent vs. SunSpear brawl going down outside Giran's north gate – y'all snooze, miss the free loot they "found". :P |
| *SunSpear fighter, at the fence* | think ur all gonna cry soon. |

## Requirements

- The **L2J-Mobius-based "Living World" offline pack** (the OneClick distribution this was developed on): it provides the module loader (`GameModule`/`ModuleContext`), `BotClanManager`, the fake-player chat bridge and the FPC brain.
- The pack's **FPC brain running** (`StartBrain = true` in the launcher), with **Ollama** serving its model (gemma3:12b class model on a GPU with spare VRAM).
- No database, no client changes, no id reservations. Server-side only.

**The brain is required by design.** If the brain cannot answer at boot, the module stays inactive and logs why. If it dies mid-session, the clans simply go quiet until it answers again — nothing canned is ever substituted. Silence during a brain outage is the *correct* behavior, not a bug.

## Install

1. Shut down the game server.
2. Copy the whole `modules/clan-personalities` folder into your pack's `game\modules\` folder.
3. In the launcher, make sure `StartBrain = true` (launcher\launcher.ini).
4. Start via the launcher and **leave the launcher open while you play** — the brain is its child process; closing the launcher kills it.
5. Within **five minutes of boot** the first guaranteed drama opens. Watch the `!` world chat.

Uninstall: set `Enabled = False` and restart (the server is exactly as it was), or delete the folder while the server is stopped.

## The twelve clans (defaults)

Pre-wired to the pack's own `BotClans.xml` roster and its three alliances:

| Clan | Alliance | | Clan | Alliance |
|---|---|---|---|---|
| Azure Dragon | DragonPact | | Iron Hammer | RavenCovenant |
| Emerald Serpent | DragonPact | | Violet Spider | RavenCovenant |
| Frost Moon | DragonPact | | Blood Raven | RavenCovenant |
| Silver Wolf | DragonPact | | Obsidian Eye | RavenCovenant |
| Crimson Phoenix | PhoenixOrder | | Golden Lion | PhoenixOrder |
| Ivory Crown | PhoenixOrder | | Sun Spear | PhoenixOrder |

Personas are dealt once and kept between restarts (`personas.properties`, written next to the manifest). Delete the file for a fresh deal, hand-edit it, or switch `PersonaMode` to `RANDOM` for a reshuffle every boot, or `EXPLICIT` with `PersonaMap = Clan:persona;...` to dictate all of them.

## How it behaves

- **Personas**: Peaceful never starts anything and answers provocation with exactly one calm line; Neutral defends only its own name; Altruistic answers for any clan under its alliance banner, even when the brother threw the first stone; Aggressive starts fights and drags its alliance in; Player-Killing talks like Aggressive (its real field duels are Phase 2, on by default since 0.2.0 — see below).
- **The world witnesses it**: the default channel is SHOUT — world chat. Pack bots and real players react to the drama, and clan-less bystanders may ask what a nickname means, or what an old war was about.
- **Proper exchanges** (`QuestionChance`): the called-out clan may first demand *"what do you want from us?"* — the accuser explains its side (grounded in a concrete incident like *kill-stealing mobs* or *spawn camping*), and only then comes the comeback. Every question earns an answer.
- **One voice per clan**: the SAME bot keeps arguing a quarrel while he is present. A present higher rank may take over and introduce himself; if a clan keeps switching talkers, the other side may ask who now speaks — once per argument.
- **Rank pacing**: leaders 10–20 s, officers 15–30 s, members 22–42 s. Nobody types over someone else — a global read-and-type floor (`MinBeatSpacingSeconds`) keeps every pair of lines apart.
- **Humans mistype**: brain-written lines are kept exactly as written, typos included. A visible accidental slip may earn a short player-style correction ("oops, i meant..."), rarely.
- **Wars become stories** (`StoryWindowMinutes`): arguments end in a war cry or an uneasy peace — and become memories. Later, a clan may retell one on a calm evening; a bystander may ask what happened; **both clans then give their own version**.
- **Kindness too** (`KindnessChance`): clans help each other, get thanked, and remember it — quarrels later may reference the shared history. Bad drama always has priority over kindness.

## Configuration reference

All in `modules/clan-personalities/config/module.ini`. Values below are the shipped defaults.

### Who talks and how it's assigned
| Key | Default | Meaning |
|---|---|---|
| `Enabled` | `True` | Master switch; `False` = the server is completely stock |
| `LivingClanIds` | the 12 clans | Names or numeric ids; resolved at runtime |
| `PersonaMode` | `STABLE` | `STABLE` / `RANDOM` (fresh deal per boot) / `EXPLICIT` |
| `PersonaMap` | *(empty)* | For EXPLICIT: `Clan:persona;Clan2:persona2` |
| `MaxClansPerPersona` | `3` | Diversity cap on how many clans share one temperament |
| `AllianceGroups` | the 3 pack alliances | Who defends whom, in chat |

### Tempo
| Key | Default | Meaning |
|---|---|---|
| `DramaPeriodMillis` | `90000` | Tick period; each tick rolls chances |
| `DramaStartChance` | `20` | Base % a clan opens drama (persona multiplies it) |
| `AmbientChance` | `3` | % a clan idles plain chatter (about its own clan only) |
| `OpportunisticChance` | `10` | % Aggressive/Player-Killing clans pile onto others' drama |
| `MaxMessagesPerHour` | `200` | Safety net only — keep it high, see below |
| `ClanCooldownSeconds` | `45` | A clan's quiet time after speaking |
| `MaxResponsesPerDrama` | `2` | Max clans answering one opening |
| `MinBeatSpacingSeconds` | `8` | The read-and-type floor between ANY two clan lines |

> ⚠️ `MaxMessagesPerHour` is a pure safety net. With the spacing floor, flooding is structurally impossible. If you set a cap the world can actually reach (e.g. 24), it will speak in bursts and then go silent for the rest of the hour — that is what a cap that low does.

### Pacing inside arguments
| Key | Default | Meaning |
|---|---|---|
| `LeaderReplyBand` | `10-20` | Leader reply wait (seconds, random range) |
| `OfficerReplyBand` | `15-30` | Officer reply wait |
| `MemberReplyBand` | `22-42` | Member reply wait |
| `LeaderReplyChance` / `OfficerReplyChance` | `10` / `30` | Who carries each beat |
| `LeaderPatienceSeconds` / `LeaderPatienceChance` | `30` / `35` | Leader steps in when officers dawdle |
| `ChatterOverlapChance` | `35` | Chance a junior voice blurts around the main beat |

### Getting started fast
| Key | Default | Meaning |
|---|---|---|
| `FirstDramaMaxSeconds` | `300` | Guaranteed first drama within this many seconds of boot (0 disables) |
| `MinOpeningDelaySeconds` / `MaxOpeningDelaySeconds` | `0` / `120` | Queue window for NEW quarrels and ambient lines |

### Arguments end, grudges and stories
| Key | Default | Meaning |
|---|---|---|
| `MaxDramaRounds` | `8` | Hard cap on exchange rounds (never loops forever) |
| `ResolveChance` | `20` | % per reply that the argument reaches its ending line |
| `MinPairRestMinutes` / `MaxPairRestMinutes` | `5` / `10` | Rest before the same pair fights again |
| `OffenseCauses` | 4 incidents | Concrete causes: `kill-stealing mobs, stealing loot, spawn camping, trade scamming` |
| `GrudgeWindowMinutes` | `120` | How long a fresh anger influences behavior |
| `StoryWindowMinutes` | `360` | How long a finished quarrel remains a retellable story |
| `HistoryChance` | `6` | % per calm tick that an old quarrel is retold |
| `StoryAskChance` | `45` | % that the retelling pulls a "what happened?" from the crowd |

### Kindness and questions
| Key | Default | Meaning |
|---|---|---|
| `QuestionChance` | `40` | % the called-out clan demands the point instead of raging |
| `KindnessChance` | `8` | % helper beats (only where no argument runs: `KindnessCalmOnly`) |
| `CockyReplyChance` | `55` | % a hot-tempered helper swaggers at thanks |

### Voices and flavor
| Key | Default | Meaning |
|---|---|---|
| `AbbreviationOverrides` | `Sun_Spear=SP` | Hand-tuned nicknames (SP, never "SS") |
| `Leader/Officer/MemberTitle` | clan leader / officer / member | How bots introduce themselves |
| `AnnounceTakeoverChance` | `40` | % a higher rank announces the takeover of the word |
| `LeadershipAskChance` | `60` | % the other side asks "who speaks for you now?" |
| `SelfCorrectionChance` | `10` | % a visible typo earns a player-style correction |
| `ChatChannel` | `SHOUT` | `SHOUT` = world chat, `GENERAL` = nearby-only bubbles |
| `RequireMembersPresent` | `True` | A clan with nobody in the world holds its tongue |
| `Shorthand` / `ShorthandChance` | `True` / `25` | Clan names get nicknames in speech |
| `Clarify` / `ClarifyCooldownHours` | `True` / `6` | Bystanders may ask what a nickname stands for |
| `Debug` | `False` | Log every delivered line (kind, speaker, text) to the server log |

### The brain
| Key | Default | Meaning |
|---|---|---|
| `BrainUrl` | `http://127.0.0.1:5000/chat` | The pack's FPC brain endpoint |
| `BrainTimeoutMillis` | `45000` | One generation can legitimately take 20–40 s; keep ≥ 45000 |
| `BrainVoiceMode` | `PARTYEVENT` | The brain's directed one-line door: the module describes the moment, the bot says one line, and nothing touches the brain's shared shout log. `LEGACY` = the old SHOUT/SAY door (fallback only) |
| `BrainHumanFlag` | `True` | LEGACY door only — ignored on PARTYEVENT, where `X-Human` is never sent |
| `BrainDirectedHeader` | `clan-personalities` | Sent as `X-Directed-By` on every request, so the brain can recognise module traffic |
| `MemorySize` | `60` | Recent lines retained in RAM for adaptation |

## Phase 2 — field duels (on by default; `Phase2 = False` for chat only)

Quarrels, threats and taunts heat a pair of clans until one boils over into a **real duel** — the pack's own combat, no scripted outcome:

- The arena is announced in world chat: open ground just outside a town gate, clear of guards and monsters. Spectators spawn first and gossip about the fighters' class, gear and record while you walk over; the fighters answer them.
- The fight waits for you. Once you stand at the fence (`FenceRadius`, 900 — every local line in earshot), the fighters trade jabs that fill a hidden aggression meter, then strike. Standing very close (600) speeds it up.
- Fair by construction: fighters are matched by level (`MaxLevelGap`) and gear (`MaxGearGap`), handed weapons their own skills can use, and fight in their class role — casters cast and never melee; PvP-useless skills (Spoil, Sweeper, Fake Death…) are blocklisted.
- Different alliances are preferred as rivals; a same-alliance fight still happens, and the crowd notices.
- Winners gloat, losers fume (and may name the allies who didn't show). Results persist in `records.properties`: the next quarrel brings them up, and losers want revenge (`RevengeBoost`).
- Fighters are temporary copies of the clans' town bots and are discarded afterwards — never saved as characters. Phantoms hunting in the field are never involved.

Useful log marks in `java0.log` (with `Debug = True`): `[FIELD-WHERE]` (arena announced), `[FIELD-MATCH]`, `[GEAR]`, `[SPECTATORS]`, `[FIELD-FUSE]`/`[FIELD-STRIKE]`, `[FIELD-WIN]`/`[FIELD-DRAW]`, `[FIELD-GLOAT]`, `[RECORD]`, `[REVENGE]`.

## What's new in 0.2.1

- **Interference failsafe.** A duel is between the two clans. If an outsider attacks its fighters or spectators, the duel is called off and everyone present defends themselves.
- **Only people who could know talk about a duel.** World-chat lines about a fight come from those at the arena (the fighters first) or from the fighting clans and their alliances. Win and loss announcements come from the fighters themselves; clans with no tie to the fight no longer comment on it in world chat.
- **The crowd sometimes includes the clans' own people**: a seat or two for a member of the fighting clans or their alliances; the rest are passers-by, as before.

## What's new in 0.2.0

**Phase 2 is complete, ready for public testing, and now on by default** (`Phase2 = True`; set it to `False` for chat-only drama). Plus:

- **One quarrel at a time** (`DramaFocus`): new openings wait while an argument still breathes, so the world reads one story, not four.
- **Taunts**: Aggressive and Player-Killing clans dare rivals toward a real fight.
- **Threat reading**: quarrel lines are read as threat, provocation or ambiguous; an unclear threat may get a "do you mean it?" — and the answer heats or cools the feud.
- **Directed brain mode** (`BrainVoiceMode = PARTYEVENT`): bots answer the drama instead of drifting into whatever the world was shouting about; multi-line replies are cut to one line.
- **Persistent duel records and revenge** (`records.properties`).
- **Kinder to the shared brain** (review follow-up): at most one module turn sits on the pack's brain pool at a time — players' whispers and party replies always find a free worker; after a brain failure, module lines are dropped at once instead of parking a worker for a minute; `X-Human` is no longer sent on the directed door; and the warm-keeper now counts only *human* players (bots are Players too — the old check never let the model cool).

## What's new in 0.1.1

Production hardening, driven by a real reviewer's test report — no new drama features:

- **The game server's threads are never blocked by the brain anymore.** Every
  generation (drama beats, field-duel words, heralds, crowd, stories,
  clarifications, resolutions) is composed on the pack's own bounded
  `BrainExecutor` — the pool every other brain caller already uses — and
  delivered back on the game pool. A busy drama can no longer park the
  server's shared scheduled threads (regen, skills, AI timers), and beats
  that would arrive over 20 s late are dropped instead of speaking stale.
- **Server start can no longer stall on the boot probe.** The probe (which
  can legitimately take ~105 s against a cold Ollama) runs on a background
  thread; the server boots immediately and the clans go live when the brain
  answers.
- **The warm-keeper can't cost money for nobody.** The pin-the-model thread
  starts ONLY after a successful boot probe (a module that never went live
  leaves no thread behind), pings ONLY while players are actually online, and
  stops on module disable. With a metered cloud brain (DeepSeek, OpenAI, …),
  the model cools while no one is playing instead of pinging around the clock.
- **`X-Human` became a switch.** The module still poses drama lines as human
  speech by default (the brain's topic cooldowns otherwise kill whole
  exchanges), but `BrainHumanFlag = False` turns that off the day the brain
  gains its module-directed mode — the module already announces
  `X-Directed-By` on every request as the handshake for it.
- **State files follow the module registry.** `personas.properties` and
  `nicknames.properties` are found via `ModulesConfig.getModulesRoot()`, not
  whatever working directory the server happened to start from.
- **Bystanders rotate.** The clan-less asker (clarify and story beats) is now
  drawn at random from every candidate in the world instead of always the
  first shopkeeper a scan found.
- The release zip no longer contains any `.git` folder (repository hygiene;
  `pack-release.ps1` builds it that way).

## Tuning guide

| If you want… | Turn… |
|---|---|
| A chattier world | `AmbientChance`, `DramaStartChance`, `HistoryChance` up |
| Faster comebacks | `LeaderReplyBand`/`OfficerReplyBand`/`MemberReplyBand` mins down |
| More human spacing between lines | `MinBeatSpacingSeconds` up (10–15) |
| More world-answering drama | `QuestionChance` up — exchanges lengthen |
| More kindness | `KindnessChance` up |
| Quieter console | `Debug = False` |

## Troubleshooting

- **Clans are completely silent** — check: is the FPC brain process alive (is the launcher open?) and did boot log report `FPC brain reachable`? The module is silent by design while the brain is down: no canned text ever substitutes.
- **Clans talked a lot, then went quiet for a long time** — that is a low `MaxMessagesPerHour` being exhausted; keep it at 200.
- **Replies feel too fast or too slow** — tune `MinBeatSpacingSeconds` and the reply bands.
- **Boot log warns about unconfigured clans** — misspelled or never-matching keys in `LivingClanIds`; the module will retry each boot.
- **Log your session**: with `Debug = True` every delivered line appears in `game\log\java0.log` with kind, speaker and text.
- **Known pack issue (not this module)**: a `NullPointerException` from `placeShopkeeper` about once per Living Population tick. The full stack is pack code only — `Spawn.doSpawn` with a null `_template`, called from `FakePlayerBehaviorManager.placeBot (:765)` ← `placeShopkeeper (:645)` ← `LivingPopulationManager.syncShopkeepers (:361)` ← `safeSnapshotTick (:2899)` — and it appears with this module disabled too. Reported to the pack author.
- **No duel ever starts** (Phase 2) — check `Phase2 = True`, then look for `[FIELD-SCAN]`/`[FIELD-TRY]` tallies in the log: they say why each town's arena was rejected. A fight only begins once you stand near the arena (`FenceRadius`) or `FenceMaxWaitSeconds` runs out.

## Roadmap

- **Phase 1 — chat drama**: conversation engine, exchanges, kindness, war stories. *(shipped)*
- **Phase 2 — field duels**: fair, real clan duels outside town gates, crowds, gloating, persistent records and revenge. *(shipped in 0.2.0, public testing; on by default)*
- **Phase 3 — deployment politics**: PK-punishment hunts, adena bounties, ally assists: personas acting in the world, not only speaking.

## License

MIT — see [LICENSE](LICENSE).
