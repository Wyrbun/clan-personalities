# Clan Personalities

A server-side module for the Living World server: the clans of the world get
**personalities**, and you get to watch their drama unfold in chat.

Five temperaments are dealt to your living clans (or you assign them yourself):

- **Peaceful** — never starts anything, answers a direct provocation with exactly one calm line.
- **Neutral** — defends its own name, but will never lift a finger for an alliance brother.
- **Altruistic** — answers for any clan wearing its banner… even when its brother threw the first stone.
- **Aggressive** — starts fights on purpose, drags its alliance in, remembers who stood by.
- **Player-Killing** — talks like Aggressive; its field behavior (hunting, stalking) rides Phase 2.

**The chat drama**: persona drama in world chat, ambient chatter, memory of previous
arguments (they come back hungrier for their rivals), and heavy anti-clutter rules so your
chat stays readable. **The field duels** (Phase 2, on by default): when a feud boils over,
the clans settle it in a fair, real duel just outside a town gate, with a watching crowd —
see "Phase 2" below. Real clan wars stay 100% stock, and nobody but the duel's own temporary
fighters ever takes damage. Want chat only? Set `Phase2 = False` in `config/module.ini`.

## The brain writes the words (required — there are no line pools)

This module talks to the pack's **FPC brain** (`brain\fpc_brain.py`, local Ollama or a
cloud provider behind it). Clan lines are **generated live** with the speaking bot's
own in-character voice - so feuds sound organic instead of canned, and replies actually
continue previous arguments. Clan speech goes to the **world chat (SHOUT)** channel,
visible everywhere, so you can witness it wherever you are (`ChatChannel = SHOUT`).

If the brain is unreachable at server start, the module logs an ERROR and stays
inactive (start the brain, then restart the server - no need to reboot the machine,
the probe runs in the background). If the brain dies mid-game, the clans simply hold
their tongues until it answers again - nothing canned is ever spoken in their place.
All limits (hour cap, cooldowns, queues) still apply; lower `MaxMessagesPerHour` first
if a `12b`-class model (or a paid cloud brain) makes lines come slow or costs too much:
fewer lines, less wait, less spend.

## Install

1. Copy this folder into the server's modules root: `game/modules/clan-personalities/`.
2. Open `config/module.ini` and set the clans to animate:
   - `LivingClanIds =` — the clans' **numeric ids** or their **names/keys**, e.g.
     `LivingClanIds = Azure_Dragon, Blood_Raven, Whisperfang`.
     Underscores, spaces and capitalization don't matter; a misspelled key is warned
     about once in the server log and simply stays silent.
   - `PersonaMode = RANDOM` deals temperaments (max 2 clans per persona),
     or `EXPLICIT` with `PersonaMap = Azure_Dragon:aggressive;Whisperfang:neutral`.
   - Optional: `AllianceGroups = alpha:Azure_Dragon,Blood_Raven;bravo:Whisperfang`
     so Altruistic knows whom to defend.
3. Make sure `Enabled = True` and restart the server.

> Modules may contain executable code. Install modules only from sources you trust.

## What you'll see

Clan members in the world will chat occasionally. Every so often, one clan will pick a
quarrel with another; the target gets its comeback after a random delay, allies elbow
in, and a feud keeps simmering for a while — quieter afterwards, louder when old rivals
meet again. If a clan has no members actually in the world, it says nothing.

## Keep it readable

- `MaxMessagesPerHour` caps all clan chat globally.
- `MinBeatSpacingSeconds` is the human read-and-type floor between any two clan lines.
- `LeaderReplyBand` / `OfficerReplyBand` / `MemberReplyBand` set how fast arguments breathe.
- `ClanCooldownSeconds` keeps any one clan from dominating.
- `MaxResponsesPerDrama` limits how many clans pile in.

## Phase 2: field duels (on by default; `Phase2 = False` turns them off)

Quarrels, threats and taunts heat up between two clans until one boils over into a
**real duel**: the pack's own combat, nothing scripted.

- The arena is announced in world chat: open ground just outside a town gate, clear of
  guards and monsters. A crowd gathers first and gossips about the fighters while you walk
  over.
- The fight waits for you: once you stand at the fence (900 units), the fighters trade
  jabs until a hidden aggression meter fills, then strike.
- Fighters are matched by level and gear, carry weapons their skills can use, and fight
  in their class role — mages cast, they don't melee.
- Winners gloat, losers fume (and may blame their absent allies), and the result is
  remembered across restarts: the next quarrel brings it up, and losers want revenge.
- Fighters are temporary copies of the clans' town bots and vanish afterwards; nothing
  is saved as a character. Hunting phantoms in the field are never involved.

## What's new in 0.2.0

- Phase 2 field duels are complete, ready for public testing, and **on by default**.
- One quarrel develops at a time; aggressive clans taunt; threats are read and, when
  unclear, questioned ("do you mean it?").
- Duel records and revenge persist in `records.properties`.
- Lines use the brain's directed one-line mode (no more answering random world chat).
- Kinder to the shared brain: at most one module turn on the brain pool at a time, the
  breaker drops lines instead of parking a worker, and the warm-keeper counts only human
  players.

## Uninstall

Disable it (`Enabled = False`) and restart — or, with the server stopped, delete the
module folder. Nothing is stored in the database (three small `.properties` files in the
module folder hold personas, nicknames and duel records), so removal is a clean delete.

## Notes

- Server-side only: standard chat packets, **no client changes** needed.
- The world clock, day/night, wars and combat rules are untouched.
- Looks off? Flip `Enabled = False`, restart, and the server is exactly as it was.
- **The FPC brain is required.** Every spoken word is written fresh by the
  pack's brain (`brain\fpc_brain.py`, port 5000) — there are no static line
  pools. If the brain is not running at server start, the module logs an
  ERROR and stays inactive (start the brain, restart the server). If the
  brain dies mid-game, the clans simply hold their tongues until it answers
  again — nothing canned is spoken in their place. The boot probe runs on
  its own background thread and every brain turn on the pack's brain pool
  (never more than one module turn at a time, so players' own whispers and
  party chat always find a free worker) — a slow model can never stall the
  server or its timers.
- **Warm-keeper economy**: the module keeps the model pinned in VRAM with a
  tiny ping every 3.5 minutes — but only after the brain answered once at
  boot, and only while a real player is connected (bots, phantoms and
  offline shops don't count). With no human online (or after the module is
  disabled) the ping stops, which matters if your
  brain is a metered cloud API (DeepSeek, OpenAI and friends).
- Typos: bot lines are kept exactly as the brain writes them — mistypes and
  all, like real players. Occasionally (rare, `SelfCorrectionChance`) a bot
  noticeably slips and then corrects itself in chat a few seconds later.
- **Fast first drama**: within ~5 minutes of boot there is a guaranteed
  opening quarrel; afterwards the world keeps its usual calmer rhythm.
- **Rank-paced arguments**: inside an ongoing quarrel the leader replies
  fastest (but rarely), officers at medium speed, members carry most
  beats — replies may overlap ("talking over each other"), and senior
  voices visibly take the word back. New quarrels between other pairs
  still keep their own spacing.
- **Real exchanges**: the called-out clan can demand "what do you want
  from us?", the accuser explains its side, and only then does the
  comeback land - quarrels are proper question-and-answer talk, not
  name calling. Replies are fast (nothing waits past half a minute).
- **Kindness too**: clans help each other, get thanked, and remember it
  — later quarrels may bring up the favor. Hot-tempered clans can be
  cocky about their good deeds.
- See `MODULE.md` for maintainer notes, the phase roadmap, and full config reference.
