# Clan Personalities

A server-side module for the Living World server: the clans of the world get
**personalities**, and you get to watch their drama unfold in chat.

Five temperaments are dealt to your living clans (or you assign them yourself):

- **Peaceful** — never starts anything, answers a direct provocation with exactly one calm line.
- **Neutral** — defends its own name, but will never lift a finger for an alliance brother.
- **Altruistic** — answers for any clan wearing its banner… even when its brother threw the first stone.
- **Aggressive** — starts fights on purpose, drags its alliance in, remembers who stood by.
- **Player-Killing** — talks like Aggressive; its field behavior (hunting, stalking) comes in a later phase.

**Phase 1 is chat only**: persona drama in world chat, ambient chatter, memory of
previous arguments (they come back hungrier for their rivals), and heavy anti-clutter
rules so your chat stays readable. **No combat is touched.** Real clan wars, PvP and
damage stay 100% stock; these clans are mouth, not blade — for now.

## The brain writes the words (required)

This module talks to the pack's **FPC brain** (`brain\fpc_brain.py`, local
Ollama). Clan lines are **generated live** with the speaking bot's own
in-character voice - so feuds sound organic instead of canned, and replies
actually continue previous arguments. Clan speech goes to the **world chat
(SHOUT)** channel, visible everywhere, so you can witness it wherever you
are (`ChatChannel = SHOUT`).

The brain is not optional: every spoken word is written by it. If the brain
is off, unreachable, or slow, nothing breaks and nothing canned is spoken -
the clans simply hold their tongues (a skipped line retries once, then stays
silent), and they speak again the moment the brain answers. All limits (hour
cap, cooldowns, queues) still apply; you can lower volume before you lower
anything else if a `12b`-class model makes lines arrive slower.

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
- `ClanCooldownMinutes` keeps any one clan from dominating.
- `MaxResponsesPerDrama` limits how many clans pile in.
- `MinMessageDelayMinutes` / `MaxMessageDelayMinutes` spread the queue.
- Lower `DramaStartChance` for a lazier world; lower `AmbientChance` for less chatter.
- The words themselves are not editable: the brain writes every line fresh.

## Uninstall

Disable it (`Enabled = False`) and restart — or, with the server stopped, delete the
module folder. Nothing is stored in the database, so removal is a clean delete.

## Notes

- Server-side only: standard chat packets, **no client changes** needed.
- The world clock, day/night, wars and combat rules are untouched.
- Looks off? Flip `Enabled = False`, restart, and the server is exactly as it was.
- **The FPC brain is required.** Every spoken word is written fresh by the
  pack's brain (`brain\fpc_brain.py`, port 5000) — there are no static line
  pools. If the brain is not running at server start, the module logs an
  ERROR and stays inactive (start the brain, restart the server). If the
  brain dies mid-game, the clans simply hold their tongues until it answers
  again — nothing canned is spoken in their place.
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
- **v0.1.1 hardening** (no new behavior): brain generations run on the
  pack's dedicated brain pool so game timers never wait on Ollama; the boot
  probe runs in the background (server start is never delayed); the
  brain's warm-keeper sleeps with nobody online and stops with the module;
  persona files anchor to the pack's module registry; and the clan-less
  bystanders who ask about nicknames rotate instead of always being the
  same townsperson.
- See `MODULE.md` for maintainer notes, the phase roadmap, and full config reference.
