# Clan Personalities

**Clans in your Lineage 2 world that actually *talk* — argue, hold grudges, help each other, and remember everything.**

This module gives living clans five personalities (Peaceful, Neutral, Altruistic, Aggressive, Player-Killing). Their bots speak in world chat through the pack's FPC brain: they pick quarrels over concrete incidents, fire back at whoever calls them out, drag their allies in, soften into kindness when the stage is calm, and retell old wars days later — each side remembering it their own way.

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

- **Personas**: Peaceful never starts anything and answers provocation with exactly one calm line; Neutral defends only its own name; Altruistic answers for any clan under its alliance banner, even when the brother threw the first stone; Aggressive starts fights and drags its alliance in; Player-Killing talks like Aggressive (Phase 2 field brawls ride in the code behind the `Phase2` switch, off by default).
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
| `BrainHumanFlag` | `True` | Drama lines knock on the brain's anti-spam door as human speech (see MODULE.md). Flip to `False` when the brain ships its module-directed mode |
| `BrainDirectedHeader` | `clan-personalities` | Sent as `X-Directed-By` on every request — the reserved handshake for that future brain mode |
| `MemorySize` | `60` | Recent lines retained in RAM for adaptation |

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
- **Known pack issue (not this module)**: the pack's `LivingPopulationManager` may log a harmless NPE from `placeShopkeeper` per tick; it is pre-existing pack behavior.

## Roadmap

- **Phase 1 — chat drama**: conversation engine, exchanges, kindness, war stories. *(shipped)*
- **Phase 2 — field duels**: equal-gear clan brawls staged in the field outside major towns, march/run theaters, herald trails, spectator crowds, a fight bank that makes fights earned and paced. *(in the code behind `Phase2 = True`; validated release to follow as 0.2.0)*
- **Phase 3 — deployment politics**: PK-punishment hunts, adena bounties, ally assists: personas acting in the world, not only speaking.

## License

MIT — see [LICENSE](LICENSE).
