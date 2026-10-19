/*
 * clan-personalities
 *
 * DramaEngine.java - everything this module does, in one place: clan
 * resolution from config tokens, the presence scan, the drama and ambient
 * rolls, the delivery gates (global hour cap, per-clan cooldown, member
 * presence), the brain-required voice (no static line pools exist), the
 * persona reaction rules, and the memory.
 *
 * Clan resolution: config tokens may be numeric clan ids (used as-is) or
 * name/key tokens ("Azure_Dragon"), which stay pending until a matching clan
 * is seen in the clan table or one of its members is in the world. Matching
 * normalizes spacing and case, so "Azure_Dragon" and "Azure Dragon" are the
 * same clan.
 *
 * Policy on one page:
 * - PEACEFUL: never starts; answers only when its own clan is dragged in,
 *   and calmly.
 * - NEUTRAL: never starts; answers only when its own clan is named; never
 *   jumps in for alliance brothers.
 * - ALTRUISTIC: answers when any clan of its alliance is named, including
 *   when its own brother started the fight (defends regardless).
 * - AGGRESSIVE / PLAYER_KILLING: answers for own or allied clans, may pile
 *   onto other people's drama out of opportunism, and grudges double their
 *   hunger to open drama.
 */
package modules.clanpersonalities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.managers.BotClanManager;
import org.l2jmobius.gameserver.managers.BrainExecutor;
import org.l2jmobius.gameserver.managers.FakePlayerChatManager;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

// Clan access for name-key resolution (org.l2jmobius.gameserver.data.sql.ClanTable).
import org.l2jmobius.gameserver.model.clan.Clan;

/**
 * The drama engine.
 *
 * Concurrency: tick runs on its fixed schedule entry; deliver runs on queue
 * timer entries. Both consult the same gates (hour cap, clan cooldown) so at
 * worst two threads speak twice in a race, never corrupt anything; profiles
 * live in a concurrent map and specs change only on the tick thread.
 */
final class DramaEngine
{
	private static final long HOUR_MILLIS = 3600000;
	private static final int UNRESOLVED_WARN_TICK = 30;

	/** Every configured clan, in its configured identity. */
	private final List<ClanSpec> _specs = new ArrayList<>();

	/** Specs with a name-key still waiting for their clan to show up. */
	private final List<ClanSpec> _pending = new ArrayList<>();

	/** Resolved clans keyed by their numeric clan id. */
	private final Map<Integer, ClanProfile> _profiles = new ConcurrentHashMap<>();

	/** Delayed send orders. */
	private final ChatQueue _queue;

	/** The required LLM tier; without it the module would be silent by design. */
	private BrainClient _brain = null;

	/** The chat channel every line is spoken in; SHOUT is visible everywhere. */
	private ChatType _channel = ChatType.SHOUT;

	/** Optional debug reporter wired by the entry point for line-by-line tracing. */
	private Consumer<String> _infoHook = null;

	// ── Shorthand (clan nicknames) and the bystanders who ask about them ──
	private boolean _shorthand = false;
	private boolean _clarify = false;
	private int _shorthandChance = 25;
	private long _clarifyCooldownMillis = 21600000L; // six hours per mention
	private final Map<Integer, String> _allianceNames = new LinkedHashMap<>();
	private final Map<String, Long> _clarifyGates = new ConcurrentHashMap<>();

	// ── Drama arcs: arguments end with a solution, then the pair rests ──
	private int _maxDramaRounds = 8;
	private int _resolveChance = 20;
	private long _minPairRestMillis = 300000L;
	private long _maxPairRestMillis = 600000L;
	private final Map<String, DramaArc> _arcs = new ConcurrentHashMap<>();
	private final Map<String, Long> _pairRestUntil = new ConcurrentHashMap<>();

	// ── Old quarrels live on as war stories ──
	/** A remembered quarrel: its incident, how it ended, when it ended. */
	private static final class Story
	{
		final String cause;
		final boolean fierce;
		final int firstClanId;
		final int secondClanId;
		long endedAtMillis;

		Story(int firstClanId, int secondClanId, String cause, boolean fierce)
		{
			this.firstClanId = firstClanId;
			this.secondClanId = secondClanId;
			this.cause = cause;
			this.fierce = fierce;
		}
	}

	/** Pair key -> the last story the pair closed, kept long after the anger. */
	private final Map<String, Story> _warStories = new ConcurrentHashMap<>();
	/** Remembering beats roll rarely; their window is long. */
	private int _historyChance = 6;
	private int _storyAskChance = 45;
	private long _storyWindowMillis = 21600000;
	/** Pairs already carrying one queued opening: the tick must not stack
	 *  duplicate START rolls on the same rivalry while deliveries wait. */
	private final Map<String, Long> _pendingStarts = new ConcurrentHashMap<>();

	// ── Clans have voices: a stable leader/officer/member hierarchy ──
	private int _announceChance = 40;
	private String _leaderTitle = "clan leader";
	private String _officerTitle = "officer";
	private String _memberTitle = "member";
	private int _leadershipAskChance = 60;
	private final Map<Integer, String> _voices = new ConcurrentHashMap<>(); // clan id -> current talker's name

	// ── Offense causes: what clan arguments are actually about ──
	/** Plain-language incidents, fed straight to the brain as context. */
	private final List<String> _offenseCauses = new ArrayList<>();
	private final Map<String, String> _pairCauses = new ConcurrentHashMap<>();

	// ── Typos stay; once in a while a bot snaps to and corrects itself ──
	private int _selfCorrectionChance = 10;

	// ── Hand-tuned abbreviations beat auto-initials (Sun_Spear = SP) ──
	private final Map<String, String> _abbrevOverrides = new ConcurrentHashMap<>();

	/** Social memory (RAM-only in this phase). */
	private ChatMemory _memory = new ChatMemory(60);

	/** Optional warn hook wired by the entry point for gentle diagnostics. */
	private Consumer<String> _warnHook = null;

	// Settings mirror; written once in configure(), read-only afterwards.
	private int _dramaStartChance = 20;
	private int _ambientChance = 3;
	private int _opportunisticChance = 10;
	private int _maxMessagesPerHour = 12;
	private int _maxResponsesPerDrama = 2;
	private long _clanCooldownMillis = 600000;
	private long _grudgeWindowMillis = 1800000;
	// ── Rank-paced beat replies (an ONGOING argument only) ──
	// Leader fastest, officer in between, members carry most beats; all are
	// random bands, so speed varies inside a rank and never dawdles.
	private int _minLeaderReplyMillis = 6000;
	private int _maxLeaderReplyMillis = 14000;
	private int _minOfficerReplyMillis = 12000;
	private int _maxOfficerReplyMillis = 24000;
	private int _minMemberReplyMillis = 18000;
	private int _maxMemberReplyMillis = 35000;
	/** Human read-and-type floor: no two clan lines land closer than this,
	 *  however tight the bands or however fast the brain answers. */
	private long _minBeatSpacingMillis = 8000;
	/** When the last clan line actually went out on the wire. */
	private volatile long _lastLineMillis;
	/** The leader takes a beat rarely; officers sometimes; members often. */
	private int _leaderReplyChance = 10;
	private int _officerReplyChance = 30;
	/** Officers slower than this hand the next beat to the leader. */
	private long _leaderPatienceMillis = 30000;
	private int _leaderPatienceChance = 35;
	/** A second, junior voice blurting right around the main beat. */
	private int _chatterOverlapChance = 35;
	/** Chance the CALLED-OUT clan first demands to know what the other
	 *  wants, instead of firing back blindly. */
	private int _questionChance = 40;
	/** An argument whose next beat never comes closes itself silently. */
	private static final long STALLED_ARC_MILLIS = 300000;
	/** One quick second chance per dropped line: a brain slowdown cost a
	 *  whole exchange once already - replies are conversations, not rolls. */
	private final Set<String> _missRetries = ConcurrentHashMap.newKeySet();

	// ── A guaranteed early drama, so the stage is set fast ──
	private int _firstDramaMaxMillis = 300000;
	/** How long a NEW quarrel (or an ambient line) may sit in the queue. */
	private int _openingMinMillis = 0;
	private int _openingMaxMillis = 120000;

	// ── Kindness: positive beats, remembered by both sides ──
	private int _kindnessChance = 8;
	private boolean _kindnessCalmOnly = true;
	private int _cockyChance = 55;
	/** Pair key -> when the last kind deed happened (RAM-only). */
	private final Map<String, Long> _goodDeeds = new ConcurrentHashMap<>();
	private boolean _requireMembersPresent = true;
	private String _unknownClanName = "another clan";

	// The shared hour window for the global send cap.
	private long _windowStart = 0;
	private int _sentThisWindow = 0;

	// Tick counter, for the one-shot unresolved-clans warning.
	private int _ticks = 0;

	DramaEngine()
	{
		_queue = new ChatQueue(this);
	}

	/**
	 * Applies all settings and builds profiles. The entry point calls this
	 * once, before the tick or the queue can exist in the world.
	 * @param specs one ClanSpec per configured clan, personas already dealt
	 * @param dramaStartChance base percent chance a clan opens drama per tick
	 * @param ambientChance percent chance of plain chatter per tick
	 * @param opportunisticChance percent chance a third-party aggressive persona piles on
	 * @param maxMessagesPerHour global send cap, all voices together
	 * @param maxResponsesPerDrama how many clans may answer one opening
	 * @param clanCooldownMillis a clan's silence after speaking
	 * @param grudgeWindowMillis how long a remembered argument sharpens appetite
	 * @param requireMembersPresent skip speech when no clan member is around
	 * @param memorySize recent messages kept for adaptation
	 * @param unknownClanName placeholder until a clan's name is learned
	 * @param warnHook where the module reports "clan never matched" softly, or null
	 */
	void configure(List<ClanSpec> specs, int dramaStartChance, int ambientChance, int opportunisticChance, int maxMessagesPerHour, int maxResponsesPerDrama, long clanCooldownMillis, long grudgeWindowMillis, boolean requireMembersPresent, int memorySize, String unknownClanName, Consumer<String> warnHook)
	{
		_dramaStartChance = dramaStartChance;
		_ambientChance = ambientChance;
		_opportunisticChance = opportunisticChance;
		_maxMessagesPerHour = maxMessagesPerHour;
		_maxResponsesPerDrama = maxResponsesPerDrama;
		_clanCooldownMillis = clanCooldownMillis;
		_grudgeWindowMillis = grudgeWindowMillis;
		_requireMembersPresent = requireMembersPresent;
		_unknownClanName = unknownClanName;
		_memory = new ChatMemory(memorySize);
		_warnHook = warnHook;

		_specs.addAll(specs);

		// A numeric id needs no resolution: the clan is known from config on.
		for (ClanSpec spec : _specs)
		{
			if (spec.getNumericId() >= 0)
			{
				_profiles.put(spec.getNumericId(), toProfile(spec, spec.getNumericId(), null));
			}
			else
			{
				_pending.add(spec);
			}
		}
	}

	private void resolveSpec(ClanSpec spec, int clanId, String clanName)
	{
		_profiles.put(clanId, toProfile(spec, clanId, clanName));
		announce(spec.getOriginal() + " matched clan " + clanId + " (" + clanName + ").");
	}

	/** Soft diagnostic routed through the module logger, never fatal. */
	private void announce(String message)
	{
		if (_warnHook != null)
		{
			_warnHook.accept("clan-personalities: " + message);
		}
	}

	/** How many clans carry a persona (zero means enabled but nothing to do). */
	int getPersonaClanCount()
	{
		int count = 0;
		for (ClanSpec spec : _specs)
		{
			if (spec.getPersona() != null)
			{
				count++;
			}
		}
		return count;
	}

	/** Normalized token: case, spaces, underscores, dashes folded away. */
	static String normalizeClanToken(String text)
	{
		return text.toLowerCase().replaceAll("[\\s_.-]", "");
	}

	/**
	 * One drama tick: pending resolution, presence pass, then rolls. Rolls do
	 * not guarantee speech; a queued message merely reaches the gates faster.
	 */
	void tick()
	{
		_ticks++;
		if (_specs.isEmpty())
		{
			return;
		}

		// Pending name-keys: try the pack's bot-clan registry first (exact
		// keys, as written in the config), then the whole clan table by
		// normalized name, and warn once if a key never matched after a while.
		if (!_pending.isEmpty())
		{
			matchPendingFromBotClans();
			matchPendingFromClanTable();
			if (!_pending.isEmpty() && (_ticks == UNRESOLVED_WARN_TICK) && (_warnHook != null))
			{
				final StringBuilder message = new StringBuilder("clan-personalities: these configured clans never matched so far: ");
				for (ClanSpec spec : _pending)
				{
					message.append(spec.getOriginal()).append(' ');
				}
				_warnHook.accept(message.toString().trim());
			}
			if ((_ticks == 1) && (_warnHook != null))
			{
				int managed = _specs.size() - _pending.size();
				_warnHook.accept("clan-personalities: matched " + managed + " of " + _specs.size() + " configured clan(s).");
			}
		}

		if (_profiles.isEmpty())
		{
			return; // no clan seen yet; keep ticking, resolution may still land
		}

		// Presence pass. Names are learned from the members themselves,
		// never guessed. Two kinds of members exist in this pack: Players
		// wearing a bot clan through BotClanManager.attach, and the town
		// fake players (Npc actors) whose crest lives on their
		// FakePlayerAppearance. Both count; both can speak.
		for (ClanProfile profile : _profiles.values())
		{
			profile.setPresent(false);
		}
		int fakePlayersSeen = 0;
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (object instanceof Player)
			{
				final Player player = (Player) object;
				final Clan clan = player.getClan();
				if ((clan == null) || !BotClanManager.getInstance().isBotClan(clan))
				{
					continue;
				}
				ClanProfile profile = _profiles.get(clan.getId());
				if (profile == null)
				{
					// A clan this module knows by name/key may resolve here,
					// from one of its own members standing in the world.
					matchPending(clan.getId(), clan.getName());
					profile = _profiles.get(clan.getId());
				}
				if (profile == null)
				{
					continue;
				}
				profile.setPresent(true);
				if (profile.getName() == null)
				{
					profile.setName(clan.getName());
				}
			}
			else if (object instanceof Npc)
			{
				final Npc npc = (Npc) object;
				if (!npc.isFakePlayer() || (npc.getFakePlayerAppearance() == null))
				{
					continue;
				}
				fakePlayersSeen++;
				final ClanProfile profile = _profiles.get(npc.getFakePlayerAppearance().getClanId());
				if (profile != null)
				{
					profile.setPresent(true);
				}
			}
		}

		// Debug: make the scan self-explaining. If chat stays silent, this
		// line says whether the world even showed us clan-wearing members.
		if ((_infoHook != null) && (_ticks % 3 == 0))
		{
			int present = 0;
			for (ClanProfile profile : _profiles.values())
			{
				if (profile.isPresent())
				{
					present++;
				}
			}
			_infoHook.accept("clan-personalities: presence scan saw " + allPlayers().size() + " player(s) and " + fakePlayersSeen + " fake player(s) in the world; " + present + " of " + _profiles.size() + " clans have members online.");
		}

		final long now = System.currentTimeMillis();
		final List<ClanProfile> candidates = new ArrayList<>();
		for (ClanProfile profile : _profiles.values())
		{
			if ((profile.getPersona() == null) || !profile.maySpeakNow(now))
			{
				continue;
			}
			if (!profile.isPresent() && _requireMembersPresent)
			{
				continue;
			}
			candidates.add(profile);
		}
		Collections.shuffle(candidates);

		for (ClanProfile profile : candidates)
		{
			final PersonaType persona = profile.getPersona();

			// Grudges double the appetite of responding personas...
			int chance = (int) (_dramaStartChance * persona.getStartFactor());
			if ((chance > 0) && hasAnyGrudge(profile.getId()))
			{
				chance *= 2;
			}

			if (chance > 0)
			{
				final ClanProfile target = pickTarget(profile);
				// One target per roll: pick once, then queue.
				if ((target != null) && (Rnd.get(100) < chance))
				{
					// A rivalry may carry only ONE queued opening: while a
					// delivery is still waiting its turn, this tick never
					// stacks a second identical drama on top of it.
					final String rollKey = arcKey(profile.getId(), target.getId());
					final Long busyUntil = _pendingStarts.get(rollKey);
					if ((busyUntil == null) || (now >= busyUntil.longValue()))
					{
						_pendingStarts.put(rollKey, Long.valueOf(now + _openingMaxMillis + 180000L));
						final long delay = openingDelay();
						_queue.schedule(profile.getId(), MessageKind.START, target.getId(), delay);
						if (_infoHook != null)
						{
							_infoHook.accept("clan-personalities: queued [START] " + ((profile.getName() != null) ? profile.getName() : ("clan " + profile.getId())) + " against " + ((target.getName() != null) ? target.getName() : ("clan " + target.getId())) + " in ~" + (delay / 1000) + " s.");
						}
						profile.cooldown(now + _clanCooldownMillis + Rnd.get((int) (_clanCooldownMillis / 4)));
					}
					continue;
				}
			}

			if (Rnd.get(100) < _ambientChance)
			{
				final long delay = openingDelay();
				_queue.schedule(profile.getId(), MessageKind.AMBIENT, 0, delay);
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: queued [AMBIENT] " + ((profile.getName() != null) ? profile.getName() : ("clan " + profile.getId())) + " in ~" + (delay / 1000) + " s.");
				}
				profile.cooldown(now + _clanCooldownMillis + Rnd.get((int) (_clanCooldownMillis / 4)));
			}
		}

		// Kindness: positive beats fill CALM moments (bad drama keeps
		// priority: with an argument running anywhere, this does not roll).
		if ((Rnd.get(100) < _kindnessChance) && (!_kindnessCalmOnly || _arcs.isEmpty()))
		{
			tryKindness();
		}

		// War stories: an OLD quarrel may be remembered aloud. Bad drama
		// keeps priority - nostalgia belongs only on a calm stage, exactly
		// like kindness, and an argument running anywhere outranks it.
		if ((Rnd.get(100) < _historyChance) && _arcs.isEmpty())
		{
			tryStoryBeat(now, candidates);
		}

		// Sweep: an argument whose next beat never comes (its clans left
		// the world mid-exchange, or a brain miss ate the line) closes
		// itself silently after a while instead of hanging forever.
		if (!_arcs.isEmpty())
		{
			_arcs.values().removeIf(arc -> (now - arc.lastBeatMillis) > STALLED_ARC_MILLIS);
		}
	}

	private ClanProfile toProfile(ClanSpec spec, int clanId, String clanName)
	{
		final ClanProfile profile = new ClanProfile(clanId);
		profile.setPersona(spec.getPersona());
		profile.setAllianceGroup(spec.getAllianceGroup());
		if (clanName != null)
		{
			profile.setName(clanName);
		}
		return profile;
	}

	/**
	 * Pending keys: the pack's BotClanManager resolves the exact key strings
	 * from data/BotClans.xml, which is where these ids were configured from.
	 */
	private void matchPendingFromBotClans()
	{
		final BotClanManager manager = BotClanManager.getInstance();
		for (ClanSpec spec : new ArrayList<>(_pending))
		{
			final Clan clan = manager.getClanByKey(spec.getOriginal());
			if (clan != null)
			{
				_pending.remove(spec);
				resolveSpec(spec, clan.getId(), clan.getName());
			}
		}
	}

	/**
	 * Pending name-keys: try the whole clan table once per tick, gently
	 * (org.l2jmobius.gameserver.data.sql.ClanTable.getInstance().getClans()).
	 * If a name never matches even here, the world-scan path still resolves
	 * it when a member enters the world.
	 */
	private void matchPendingFromClanTable()
	{
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			matchPending(clan.getId(), clan.getName());
		}
	}

	/**
	 * Matches a seen clan against pending name-keys. A clan that was seen
	 * once is resolved forever, keyed by its numeric id.
	 */
	private void matchPending(int clanId, String clanName)
	{
		if ((clanId <= 0) || (clanName == null) || _profiles.containsKey(clanId))
		{
			return;
		}
		final String token = normalizeClanToken(clanName);
		for (Iterator<ClanSpec> iterator = _pending.iterator(); iterator.hasNext();)
		{
			final ClanSpec spec = iterator.next();
			if (spec.getKey().equals(token))
			{
				iterator.remove();
				resolveSpec(spec, clanId, clanName);
				return;
			}
		}
	}

	/**
	 * A queued message reaching its moment. Every gate is re-checked here,
	 * which is what keeps a stale queue honest: the hour cap, the clan's own
	 * cooldown, and the voice-presence rule all hold at send time.
	 */
	void deliver(int clanId, MessageKind kind, int otherClanId, int voiceRank)
	{
		final ClanProfile profile = _profiles.get(clanId);
		if ((profile == null) || (profile.getPersona() == null))
		{
			return; // not resolved, or persona-less since this was queued
		}

		final long now = System.currentTimeMillis();

		// Global hour cap. Extras postpone into the next window, never drop.
		if (now >= (_windowStart + HOUR_MILLIS))
		{
			_windowStart = now;
			_sentThisWindow = 0;
		}
		if (_sentThisWindow >= _maxMessagesPerHour)
		{
			_queue.schedule(clanId, kind, otherClanId, Math.max(60000, (_windowStart + HOUR_MILLIS) - now), voiceRank);
			return;
		}

		// Human read-and-type floor: never let a clan line land while the
		// world is still reading the last one. Beats falling due together
		// queue up again and follow each other with a small, player-like
		// gap instead of replying in the same breath.
		final long hold = spacingHold();
		if (hold > 0)
		{
			_queue.schedule(clanId, kind, otherClanId, hold, voiceRank);
			return;
		}

		// The queue entry is the clan's reserved floor: it already set the
		// cooldown when it was queued, so no cooldown re-check here - that
		// check exists to pace the tick's new rolls, not to bounce the
		// message it reserved. The hour cap and member presence below still
		// apply at delivery time.

		// Voice: the clan's talker keeps the word while he is present; a
		// present higher rank (leader > officer > member) may take over, and
		// may introduce himself. A beat may also SCHEDULE a rank: then the
		// word goes to that rank's best voice, and a junior seen blurted
		// right before it reads as players talking over each other.
		final VoiceChoice choice = chooseVoice(profile, voiceRank);
		if (choice == null)
		{
			return; // no member left in the world
		}
		final Creature speaker = choice.creature;

		// An incoming higher rank may announce himself first: "I am the
		// leader of X - my word is theirs now." The brain writes it.
		if (choice.promoted && (_announceChance > 0) && (Rnd.get(100) < _announceChance))
		{
			final String clanName = (profile.getName() != null) ? profile.getName() : ("Clan " + profile.getId());
			brainWorld(speaker, "You have just taken over speaking for your clan " + clanName + " as its " + choice.title + ". Claim the word for your clan in ONE short spoken line; players announce themselves simply, without ceremony.", "delivered [TAKEOVER]");
		}

		// The line is written fresh by the brain at send time, so content
		// adapts to the moment rather than to the queue's memory of it. The
		// brain is REQUIRED: if it cannot answer, this message simply does
		// not happen - no static text ever stands in for a clan's voice.
		final ClanProfile other = (otherClanId != 0) ? _profiles.get(otherClanId) : null;
		final String causeLabel;
		if (kind == MessageKind.START)
		{
			// An incident opens the drama: a concrete offense the pair keeps
			// arguing about while the grudge lasts, instead of noise.
			causeLabel = chooseCause(profile, other);
		}
		else if ((kind == MessageKind.RESPONSE) || (kind == MessageKind.ASIDE) || (kind == MessageKind.EXPLAIN))
		{
			causeLabel = (other != null) ? _pairCauses.get(arcKey(profile.getId(), other.getId())) : null;
		}
		else if (kind == MessageKind.HISTORY)
		{
			// A memory retells the incident its quarrel actually closed on:
			// the current remembered cause first, the recorded story backup.
			String remembered = (other != null) ? _pairCauses.get(arcKey(profile.getId(), other.getId())) : null;
			if ((remembered == null) && (other != null))
			{
				final Story story = storyBetween(profile.getId(), other.getId());
				if (story != null)
				{
					remembered = story.cause;
				}
			}
			causeLabel = remembered;
		}
		else
		{
			causeLabel = null; // kindness beats are not about any quarrel
		}
		// The retry key is computed first: a RETRY already had its chain
		// scheduled on the first attempt (that is the point of scheduling
		// before speaking), so it must not schedule everything twice.
		final String retryKey = arcKey(clanId, otherClanId) + ":" + kind + ":" + voiceRank;
		if (!_missRetries.contains(retryKey))
		{
			chainExchange(clanId, otherClanId, kind, other, profile, now, choice, causeLabel);
		}

		// The line is written fresh by the brain at send time, so content
		// adapts to the moment rather than to the queue's memory of it. The
		// brain is REQUIRED: if it cannot answer, this message simply does
		// not happen - no static text ever stands in for a clan's voice.
		// THE SEAM: the generation parks a BrainExecutor worker (bounded,
		// stale-dropping), and delivery hops back onto the game pool - the
		// game's own timers never wait behind a 45-second answer.
		try
		{
			BrainExecutor.runBrainWork(() ->
			{
				final String written = brainLine(speaker, profile, kind, other, causeLabel);
				ThreadPool.schedule(() -> deliverWritten(speaker, profile, kind, other, clanId, otherClanId, voiceRank, now, retryKey, written), 0);
			});
		}
		catch (Throwable t)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: the brain pool refused a beat (" + t + ").");
			}
		}
	}

	/**
	 * The spoken half of one beat: runs on the game pool after the brain
	 * answered (or honestly, after it did not - then one retry, and a
	 * silence that reads as a clansman saying nothing).
	 */
	private void deliverWritten(Creature speaker, ClanProfile profile, MessageKind kind, ClanProfile other, int clanId, int otherClanId, int voiceRank, long now, String retryKey, String written)
	{
		if (written == null)
		{
			// The brain is down, slow or mid-breaker. The exchange itself
			// already survives (its next beats were scheduled above), and
			// this line gets ONE quick retry before it is given up on - a
			// short silence beats a dropped conversation. Ambient chatter
			// skips the courtesy.
			if ((kind != MessageKind.AMBIENT) && !_missRetries.contains(retryKey))
			{
				_missRetries.add(retryKey);
				_queue.schedule(clanId, kind, otherClanId, 15000 + Rnd.get(15000), voiceRank);
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: brain miss for [" + kind + "] by " + speaker.getName() + "; retrying this line once.");
				}
			}
			else
			{
				_missRetries.remove(retryKey);
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: brain miss for [" + kind + "] by " + speaker.getName() + "; line skipped (the brain is required).");
				}
			}
			return;
		}
		String text = written;
		_missRetries.remove(retryKey);

		// Shorthand: the line may name its clans and alliances by nickname,
		// and each shortened mention opens one clarification window.
		final List<ClanProfile> clanMentions = new ArrayList<>();
		final List<Integer> allianceMentions = new ArrayList<>();
		if (_shorthand)
		{
			text = shortenNames(text, clanMentions, allianceMentions);
		}

		sayToAll(speaker, text, "delivered [" + kind + "] (" + _channel + ", brain)");
		// Typos stay as they were written; humans type wrong, and a bot that
		// never slips reads like a machine. Once in a rare while, a visible
		// slip earns a tiny self-correction, the way a player would type one.
		maybeSelfCorrection(speaker, text);
		// Book-keeping: memory (with this line's text, for LLM context) and
		// the clan's cooldown. The hourly cap counts inside sayToAll, so all
		// spoken kinds pay their dues at the same door.
		_memory.add(clanId, otherClanId, text);
		profile.cooldown(now + _clanCooldownMillis + Rnd.get((int) (_clanCooldownMillis / 2)));

		// A chatter line that still NAMES another persona clan is a public
		// callout, not idle talk: the ambient prompt forbids naming clans,
		// but the brain may slip one in - and a callout that floats
		// unanswered reads like dead silence to the whole world. Give the
		// named clan the word: its question, the complainer's explanation,
		// then the comeback - the full exchange machinery, on the spot.
		if (kind == MessageKind.AMBIENT)
		{
			for (ClanProfile mentioned : clanMentions)
			{
				final String pairKey = arcKey(clanId, mentioned.getId());
				if ((mentioned.getPersona() == null) || _arcs.containsKey(pairKey) || pairAtRest(clanId, mentioned.getId()))
				{
					continue; // persona-less, already running, or at rest
				}
				final String cause = (_pairCauses.containsKey(pairKey)) ? _pairCauses.get(pairKey) : chooseCause(profile, mentioned);
				_arcs.putIfAbsent(pairKey, new DramaArc(1, cause));
				final int rank = rollBeatRank(0);
				_queue.schedule(mentioned.getId(), MessageKind.QUESTION, clanId, bandDelay(rank), rank);
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: chatter named " + ((mentioned.getName() != null) ? mentioned.getName() : ("clan " + mentioned.getId())) + " - queued a [QUESTION] so the callout gets answered.");
				}
				break; // one callout per line; the rest wait for their tick
			}
		}

		// A told war story invites the world: a clan-less bystander may ask
		// what happened back then, and BOTH clans then give their version.
		if ((kind == MessageKind.HISTORY) && (other != null))
		{
			scheduleStoryTelling(profile, other);
		}

		// A clan-less bystander may ask what a nickname means, and the named
		// clan (or one of its alliance banners) may answer. At most once per
		// mention per cooldown window.
		if (_clarify && (!clanMentions.isEmpty() || !allianceMentions.isEmpty()))
		{
			scheduleClarifyRequests(clanMentions, allianceMentions);
		}
	}

	/**
	 * The exchange's chain, scheduled BEFORE anything is spoken. A brain miss
	 * must cost one line, never the exchange: the answer to a question is
	 * already on its way the moment the question is due, so a skipped bot
	 * reads as a silent clanmate, not a dead argument. Openings start arcs; a
	 * called-out clan may first demand the point and the accuser then
	 * explains its side; replies advance the argument for a few beats until
	 * its solution; a kindness gets its thanks, and a hot-blooded helper may
	 * swagger about the thanks. Asides, ambient lines and cocky replies chain
	 * nothing.
	 */
	private void chainExchange(int clanId, int otherClanId, MessageKind kind, ClanProfile other, ClanProfile profile, long now, VoiceChoice choice, String causeLabel)
	{
		final DramaArc liveArc = _arcs.get(arcKey(clanId, otherClanId));
		if (liveArc != null)
		{
			liveArc.lastBeatMillis = now; // the argument is still breathing
		}
		if (kind == MessageKind.START)
		{
			// The arc remembers the SAME incident the opening line was
			// written about: quarrel and words must tell one story.
			final DramaArc arc = new DramaArc(1, causeLabel);
			_arcs.put(arcKey(clanId, otherClanId), arc);
			scheduleResponses(clanId, otherClanId);
		}
		else if (kind == MessageKind.QUESTION)
		{
			// The called-out clan wanted the point: the accuser (the other
			// side here) owes its explanation next, pronto.
			if (other != null)
			{
				final int rank = rollBeatRank(0);
				_queue.schedule(otherClanId, MessageKind.EXPLAIN, clanId, bandDelay(rank), rank);
			}
		}
		else if (kind == MessageKind.EXPLAIN)
		{
			// Side said; now the called-out clan gives its real comeback,
			// and from here the argument flows as normal responses.
			if (other != null)
			{
				final int rank = rollBeatRank(0);
				_queue.schedule(otherClanId, MessageKind.RESPONSE, clanId, bandDelay(rank), rank);
			}
		}
		else if (kind == MessageKind.RESPONSE)
		{
			// A changed voice on one side may prompt the other clan's talker
			// to ask who now speaks: once per argument, on enough bounce.
			if (choice.promoted && (other != null))
			{
				final DramaArc arc = _arcs.get(arcKey(clanId, otherClanId));
				if ((arc != null) && arc.bounce())
				{
					arc.leadershipAsked = true; // once per argument, not per line
					if (Rnd.get(100) < _leadershipAskChance)
					{
						scheduleLeadershipAsk(other, profile);
					}
				}
			}
			advanceArc(clanId, otherClanId, profile, other);
		}
		else if (kind == MessageKind.KINDNESS)
		{
			// Remembered by BOTH sides: later quarrels may reference this
			// shared history, and the helped clan owes a thanks now.
			_goodDeeds.put(arcKey(clanId, otherClanId), Long.valueOf(now));
			if (other != null)
			{
				final int rank = rollBeatRank(0);
				_queue.schedule(otherClanId, MessageKind.PRAISE, clanId, bandDelay(rank), rank);
			}
		}
		else if (kind == MessageKind.PRAISE)
		{
			// A hot-blooded helper may answer gratitude with swagger.
			final ClanProfile helper = other;
			if ((helper != null) && ((helper.getPersona() == PersonaType.AGGRESSIVE) || (helper.getPersona() == PersonaType.PLAYER_KILLING)) && (_goodDeeds.containsKey(arcKey(clanId, otherClanId))) && (Rnd.get(100) < _cockyChance))
			{
				final int rank = rollBeatRank(0);
				_queue.schedule(helper.getId(), MessageKind.COCKY, clanId, bandDelay(rank), rank);
			}
		}
	}

	/** The required voice: wired by the entry point only after a successful
	 *  boot probe, so the engine never runs without it. */
	void setBrain(BrainClient brain)
	{
		_brain = brain;
	}

	/** The packet channel for every line (GENERAL = nearby only, SHOUT = world chat). */
	void setChannel(ChatType channel)
	{
		if ((channel == ChatType.GENERAL) || (channel == ChatType.SHOUT))
		{
			_channel = channel;
		}
	}

	/** Optional per-line reporter for Debug mode. */
	void setInfoHook(Consumer<String> infoHook)
	{
		_infoHook = infoHook;
	}

	/** Shorthand knobs: shorten clan/alliance names in speech, and let
	 *  clan-less bystanders ask what the nickname stands for. */
	void setShorthand(boolean shorthand, boolean clarify, int chance, long clarifyCooldownMillis)
	{
		_shorthand = shorthand;
		_clarify = clarify;
		_shorthandChance = Math.max(0, Math.min(100, chance));
		_clarifyCooldownMillis = (clarifyCooldownMillis > 60000) ? clarifyCooldownMillis : 21600000L;
	}

	/** Alliance display names by group number, for shorthand of alliances. */
	void setAllianceNames(Map<Integer, String> names)
	{
		_allianceNames.clear();
		if (names != null)
		{
			_allianceNames.putAll(names);
		}
	}

	/** Rank-paced reply bands, in milliseconds, for an ongoing argument.
	 *  Forced to stay ordered: leader fastest, member slowest. */
	void setBeatReplyBands(long minLeader, long maxLeader, long minOfficer, long maxOfficer, long minMember, long maxMember)
	{
		_minLeaderReplyMillis = (int) Math.max(4000, minLeader);
		_maxLeaderReplyMillis = (int) Math.max(_minLeaderReplyMillis, maxLeader);
		_minOfficerReplyMillis = (int) Math.max(_minLeaderReplyMillis, minOfficer);
		_maxOfficerReplyMillis = (int) Math.max(_minOfficerReplyMillis, maxOfficer);
		_minMemberReplyMillis = (int) Math.max(_minOfficerReplyMillis, minMember);
		_maxMemberReplyMillis = (int) Math.max(_minMemberReplyMillis, maxMember);
	}

	/** The human read-and-type floor between ANY two clan lines, in
	 *  milliseconds (0 disables the spacing gate). */
	void setBeatSpacing(long spacingMillis)
	{
		_minBeatSpacingMillis = Math.max(0, spacingMillis);
	}

	/** Who carries a beat: the leader rarely (unless officers drag it
	 *  out), officers sometimes, members most of the time; plus the odds
	 *  of a junior voice blurting right around the main beat. */
	void setBeatChances(int leaderChance, int officerChance, long leaderPatienceMillis, int leaderPatienceChance, int chatterOverlapChance)
	{
		_leaderReplyChance = Math.max(0, Math.min(50, leaderChance));
		_officerReplyChance = Math.max(0, Math.min(100 - _leaderReplyChance, officerChance));
		_leaderPatienceMillis = Math.max(10000, leaderPatienceMillis);
		_leaderPatienceChance = Math.max(_leaderReplyChance, Math.min(100, leaderPatienceChance));
		_chatterOverlapChance = Math.max(0, Math.min(100, chatterOverlapChance));
	}

	/** Chance the called-out clan ASKS what the other wants first, so
	 *  the quarrel opens as a real question-and-answer exchange. */
	void setQuestionChance(int chance)
	{
		_questionChance = Math.max(0, Math.min(100, chance));
	}

	/** The guaranteed early drama (max seconds after boot; 0 disables) and
	 *  how long NEW quarrels or ambient lines may sit in the queue. */
	void setStartPacing(long firstDramaMaxMillis, long openingMinMillis, long openingMaxMillis)
	{
		_firstDramaMaxMillis = (int) Math.max(0, firstDramaMaxMillis);
		_openingMinMillis = (int) Math.max(0, openingMinMillis);
		_openingMaxMillis = (int) Math.max(_openingMinMillis, openingMaxMillis);
	}

	/** War stories: how often remembered quarrels are told aloud, how often
	 *  the world gets to ask about one, and how long memories stay alive. */
	void setStories(int historyChance, int storyAskChance, long storyWindowMillis)
	{
		_historyChance = Math.max(0, Math.min(100, historyChance));
		_storyAskChance = Math.max(0, Math.min(100, storyAskChance));
		_storyWindowMillis = Math.max(600000L, storyWindowMillis);
	}

	/** Kindness knobs: how often positive beats may fire, whether they
	 *  must wait for a quiet stage, and how cocky helpers may get. */
	void setKindness(int chance, boolean calmOnly, int cockyChance)
	{
		_kindnessChance = Math.max(0, Math.min(100, chance));
		_kindnessCalmOnly = calmOnly;
		_cockyChance = Math.max(0, Math.min(100, cockyChance));
	}

	/** Arc knobs: argument length, resolution odds and the pair's rest. */
	void setArcSettings(int maxDramaRounds, int resolveChance, long minPairRestMillis, long maxPairRestMillis)
	{
		_maxDramaRounds = Math.max(2, maxDramaRounds);
		_resolveChance = Math.max(0, Math.min(100, resolveChance));
		_minPairRestMillis = (minPairRestMillis > 30000) ? minPairRestMillis : 300000L;
		_maxPairRestMillis = Math.max(_minPairRestMillis, maxPairRestMillis);
	}

	/** Clan voice hierarchy: titles plus the takeover/who-asks chances. */
	void setVoiceSettings(int announceChance, String leaderTitle, String officerTitle, String memberTitle)
	{
		_announceChance = Math.max(0, Math.min(100, announceChance));
		if ((leaderTitle != null) && !leaderTitle.isEmpty())
		{
			_leaderTitle = leaderTitle;
		}
		if ((officerTitle != null) && !officerTitle.isEmpty())
		{
			_officerTitle = officerTitle;
		}
		if ((memberTitle != null) && !memberTitle.isEmpty())
		{
			_memberTitle = memberTitle;
		}
	}

	/** Chance the rival clan asks "who speaks for you now?" on a bounce. */
	void setLeadershipAskChance(int chance)
	{
		_leadershipAskChance = Math.max(0, Math.min(100, chance));
	}

	/** Hand-tuned abbreviations (normalized clan/alliance name -> nickname). */
	void setAbbreviationOverrides(Map<String, String> overrides)
	{
		if (overrides != null)
		{
			_abbrevOverrides.putAll(overrides);
		}
	}

	/** Offense causes (plain-language incidents, in a stable order). */
	void setOffenseCauses(List<String> causes)
	{
		_offenseCauses.clear();
		if (causes != null)
		{
			_offenseCauses.addAll(causes);
		}
	}

	/** Percent chance (of visible slips only) that a bot corrects itself. */
	void setSelfCorrectionChance(int chance)
	{
		_selfCorrectionChance = Math.max(0, Math.min(100, chance));
	}

	/**
	 * One brain attempt, null when the brain is off or errored. The call runs
	 * on this queue thread with the client's own timeout and circuit breaker,
	 * so a downed brain costs one bounded wait once, then ten minutes of
	 * silence from the LLM tier while the world hears nothing from the clans.
	 */
	private String brainLine(Creature speaker, ClanProfile profile, MessageKind kind, ClanProfile other, String cause)
	{
		if (_brain == null)
		{
			return null;
		}

		// Real context: the last remembered lines of both clans, so the reply
		// continues the argument instead of inventing it from scratch.
		final List<String> recent = _memory.recentLines(profile.getId(), 3);
		for (String otherRecent : _memory.recentLines(other != null ? other.getId() : 0, 2))
		{
			if (!recent.contains(otherRecent))
			{
				recent.add(otherRecent);
			}
		}
		// Positive history: if these two clans shared kind deeds, the prompt
		// says so, so quarrels may reference the shared past - pressure AND
		// respect in one line, depending on temperament.
		final boolean kindnessHistory = (other != null) && _goodDeeds.containsKey(arcKey(profile.getId(), other.getId()));
		return _brain.line(speaker.getName(), FakePlayerChatManager.nearestLocation(speaker.getX(), speaker.getY()), (_channel == ChatType.SHOUT) ? "SHOUT" : "SAY", brainPrompt(profile, kind, other, recent, cause, kindnessHistory));
	}

	/**
	 * The situation in-character. The brain adds its own persona and memory
	 * for the speaking bot by name; this adds what ONLY the module knows:
	 * clan membership, temperament, and what this message is for.
	 */
	/** World law enforced on every spoken line: Interlude-only lore, no cross-game borrowings. */
	private static final String WORLD_LAW = "WORLD LAW: this is Lineage 2, Interlude. Speak ONLY of things that exist in that game and era - places like Talking Island, Gludio, Dion, Giran, Oren, Aden, Hunters Village, Cruma Tower, Enchanted Valley, Forest of Mirrors; creatures like wolves, gremlins, orcs, ant soldiers, golems, wyverns, Striders, spiritual eyes; coin is adena; gear grades are D, C, B, A and S only. NOTHING else exists: no places, monsters, items or grades from other games, and no inventions of your own - Ragnarok, World of Warcraft style fakes, 'P grade', 'G grade' - none of that is real here. If you are not sure something exists in Lineage 2, keep the line vague instead of naming it. ";

	private static String brainPrompt(ClanProfile profile, MessageKind kind, ClanProfile other, List<String> recent, String cause, boolean kindnessHistory)
	{
		final StringBuilder prompt = new StringBuilder();

		String situation = "";
		if (kind == MessageKind.START)
		{
			situation = "Your clan has just publicly picked a quarrel with the clan " + ((other != null) && (other.getName() != null) ? other.getName() : "it dislikes") + ". Open with your stance.";
		}
		else if (kind == MessageKind.RESPONSE)
		{
			situation = "Your clan has just been named and challenged by " + ((other != null) && (other.getName() != null) ? other.getName() : "an enemy clan") + ". Fire back with ONE short comeback.";
		}
		else if (kind == MessageKind.QUESTION)
		{
			situation = "The clan " + ((other != null) && (other.getName() != null) ? other.getName() : "an enemy clan") + " just publicly picked a quarrel with your clan, but NOBODY even said what their problem is yet. Ask them what they want: ONE short line, a question - suspicious, bored, sharp or amused, by your temperament.";
		}
		else if (kind == MessageKind.EXPLAIN)
		{
			situation = "Your clan picked this quarrel and they just asked, in chat, what you people want from them. Say YOUR side now: explain what THEIR folk did to yours. ONE short line - plain, mocking or furious, by your temperament.";
		}
		else if (kind == MessageKind.ASIDE)
		{
			situation = "Your clan is in a hot argument with " + ((other != null) && (other.getName() != null) ? other.getName() : "a rival clan") + " right now, and a senior clansman is about to answer them. You are a junior clansman jumping in first: ONE short, rowdy, unofficial supporting line - the official answer comes after you.";
		}
		else if (kind == MessageKind.HISTORY)
		{
			situation = "Your clan once quarreled with the clan " + ((other != null) && (other.getName() != null) ? other.getName() : "a rival") + ((cause != null) ? " about " + cause : "") + ". That is OLD business now, long over. Remember it aloud: ONE short line - a grumble, a boast or a warning - mentioning them by name.";
		}
		else if (kind == MessageKind.KINDNESS)
		{
			situation = "There is no quarrel at this moment: your clan does ONE genuinely kind thing for the clan " + ((other != null) && (other.getName() != null) ? other.getName() : "nearby") + " - share a hunting ground, pass word of danger, offer a favor, or plainly salute their house. The line contains ONLY that kind gesture: no party or leveling invitation, no complaint, no mention of past trouble.";
		}
		else if (kind == MessageKind.PRAISE)
		{
			situation = "The clan " + ((other != null) && (other.getName() != null) ? other.getName() : "nearby") + " just did your clan a genuine kindness. Thank them in ONE short line; your temperament shades it - warm, businesslike or wary - but no insult.";
		}
		else if (kind == MessageKind.COCKY)
		{
			situation = "The clan " + ((other != null) && (other.getName() != null) ? other.getName() : "nearby") + " just thanked your clan for helping them. Answer in ONE short cocky but good-natured line - swagger, no threat.";
		}
		else
		{
			situation = "Your clan is minding its own business today. Plain everyday chatter about YOUR OWN clan only: a hunt, loot, gear, the grind, the weather, a camp. Do NOT name any other clan in this line, do not complain about anyone, do not invite anyone to a party or to level together - this is plain chatter, nothing aimed at anyone.";
		}

		prompt.append("You belong to the clan ").append(profile.getName() != null ? profile.getName() : "of your banner").append(". ");
		prompt.append("Your clan's temperament is ").append(personaHint(profile.getPersona())).append(" ");
		prompt.append(situation).append(' ');
		if (cause != null)
		{
			// A concrete incident grounds the quarrel: the brain bickers about
			// THAT, not about nothing.
			prompt.append("The quarrel is really about: ").append(cause).append(". Make the line about that incident. ");
		}
		if (kindnessHistory && ((kind == MessageKind.START) || (kind == MessageKind.RESPONSE)))
		{
			// Remembered goodwill: even a hot clan may name the favor before
			// throwing the next stone - and a calm clan may lean on it.
			prompt.append("Your clans have shared small kindnesses before this; referencing that shared history naturally is welcome. ");
		}
		if (!recent.isEmpty())
		{
			prompt.append("Recent clan chat, newest last: ");
			for (String line : recent)
			{
				prompt.append('"').append(line).append('"').append(' ');
			}
		}
		prompt.append(WORLD_LAW);
		prompt.append("The line is ONE sentence about ONE thing: a line that mixes a complaint with an invitation, or a greeting with a demand, is wrong - write only the single thought. Plain spoken words, in character, no brackets or markup, nothing that is not said out loud in the world.");
		return prompt.toString();
	}

	/** Short second-person temperament hint per persona. */
	private static String personaHint(PersonaType persona)
	{
		switch (persona != null ? persona : PersonaType.NEUTRAL)
		{
			case PEACEFUL:
			{
				return "peace-seeking; never aggressive, quiet and dignified.";
			}
			case ALTRUISTIC:
			{
				return "loyal to its alliance brothers; helpful and communal.";
			}
			case AGGRESSIVE:
			{
				return "proud and short-tempered; provokes and escalates.";
			}
			case PLAYER_KILLING:
			{
				return "grudging and menacing; likes to remind rivals of danger.";
			}
			default:
			{
				return "reserved and self-interested; answers only about its own clan.";
			}
		}
	}

	/**
	 * Who may answer an opening drama, by persona rule, and with a queue
	 * entry each. Order: the dragged-in clan gets its comeback, allied clans
	 * answer for their brother, and an aggressive persona may pile on.
	 */
	private void scheduleResponses(int clanId, int otherClanId)
	{
		final ClanProfile speaker = _profiles.get(clanId);
		final ClanProfile target = _profiles.get(otherClanId);
		if ((speaker == null) || (target == null))
		{
			return;
		}

		final List<ClanProfile> responders = new ArrayList<>();
		for (ClanProfile candidate : _profiles.values())
		{
			final PersonaType persona = candidate.getPersona();
			if ((persona == null) || (candidate.getId() == clanId))
			{
				continue; // the speaker just spoke; no double lines in this phase
			}

			final boolean isTarget = candidate.getId() == otherClanId;
			final boolean isAllianceNamed = candidate.isAlliedWith(target) || candidate.isAlliedWith(speaker);

			if (isTarget)
			{
				// The dragged-in clan: its comeback is persona-governed; even
				// Peaceful answers exactly once, and calmly.
				if (persona.defendsOwn())
				{
					responders.add(candidate);
				}
			}
			else if (persona.defendsAlliance() && isAllianceNamed)
			{
				// Altruistic: helps its alliance, even when its brother threw
				// the first stone.
				responders.add(candidate);
			}
			else if (((persona == PersonaType.AGGRESSIVE) || (persona == PersonaType.PLAYER_KILLING)) && (Rnd.get(100) < _opportunisticChance))
			{
				// Aggressive temperaments may pile onto other people's fights.
				responders.add(candidate);
			}
		}

		Collections.shuffle(responders);
		int scheduled = 0;
		for (ClanProfile responder : responders)
		{
			if (scheduled >= _maxResponsesPerDrama)
			{
				break;
			}
			final int rank = rollBeatRank(0);
			if ((responder.getId() == otherClanId) && (Rnd.get(100) < _questionChance))
			{
				// The called-out clan demands the point first: the opening
				// becomes a real exchange - question, explanation, THEN the
				// comeback (the chain lives in deliver()).
				_queue.schedule(responder.getId(), MessageKind.QUESTION, clanId, bandDelay(rank), rank);
			}
			else
			{
				_queue.schedule(responder.getId(), MessageKind.RESPONSE, clanId, bandDelay(rank), rank);
			}
			scheduled++;
		}
	}

	/** A random other clan worth starting trouble with (needs a persona). */
	private ClanProfile pickTarget(ClanProfile self)
	{
		final List<ClanProfile> options = new ArrayList<>();
		for (ClanProfile profile : _profiles.values())
		{
			if ((profile.getId() != self.getId()) && (profile.getPersona() != null) && !pairAtRest(self.getId(), profile.getId()))
			{
				options.add(profile);
			}
		}
		if (options.isEmpty())
		{
			return null;
		}
		return options.get(Rnd.get(options.size()));
	}

	// ── A guaranteed first drama, and kindness beats ─────────────────────

	/**
	 * Schedules ONE drama opening inside the first minutes after boot, so a
	 * fresh world got talking before you finish logging in. The rest of the
	 * session keeps its own slower rhythm; this is a warm-up, not a change
	 * of pace.
	 */
	void armFirstDrama()
	{
		if (_firstDramaMaxMillis <= 0)
		{
			return;
		}
		final int warmUp = 30000 + Rnd.get(Math.max(1000, _firstDramaMaxMillis - 30000));
		ThreadPool.schedule(this::openForcedDrama, warmUp);
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: warm-up drama armed for ~" + (warmUp / 1000) + " s after boot.");
		}
	}

	private void openForcedDrama()
	{
		// Instigators: personas that ever open a quarrel, weighted by
		// temperament; targets: any other persona clan with the pair not at
		// rest. Presence is re-checked at delivery, and if the world emptied
		// meanwhile the ordinary ticks carry the drama anyway.
		final List<ClanProfile> instigators = new ArrayList<>();
		for (ClanProfile profile : _profiles.values())
		{
			if ((profile.getPersona() != null) && (profile.getPersona().getStartFactor() > 0))
			{
				instigators.add(profile);
			}
		}
		for (ClanProfile instigator : instigators)
		{
			final ClanProfile target = pickTarget(instigator);
			if (target != null)
			{
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities warm-up: " + ((instigator.getName() != null) ? instigator.getName() : ("clan " + instigator.getId())) + " against " + ((target.getName() != null) ? target.getName() : ("clan " + target.getId())) + ".");
				}
				deliver(instigator.getId(), MessageKind.START, target.getId(), -1);
				return;
			}
		}
	}

	/** A kind deed's odds by initiating persona: good hearts help more. */
	private static int kindnessFactor(PersonaType persona)
	{
		switch (persona)
		{
			case ALTRUISTIC:
			{
				return 100;
			}
			case PEACEFUL:
			{
				return 60;
			}
			case NEUTRAL:
			{
				return 30;
			}
			case AGGRESSIVE:
			{
				return 20;
			}
			default:
			{
				return 10; // player-killers rarely, but even they have days
			}
		}
	}

	/**
	 * One clan does another a good turn today: shares a hunting ground,
	 * passes word of danger, salutes their plain. Even across alliances.
	 * Calm moments only (quarrels have priority) and never inside a pair's
	 * running argument.
	 */
	private void tryKindness()
	{
		final List<ClanProfile> candidates = new ArrayList<>();
		for (ClanProfile profile : _profiles.values())
		{
			if ((profile.getPersona() != null) && (!_requireMembersPresent || profile.isPresent()))
			{
				candidates.add(profile);
			}
		}
		Collections.shuffle(candidates);
		for (ClanProfile helper : candidates)
		{
			final int chance = (_kindnessChance * kindnessFactor(helper.getPersona())) / 100;
			if ((chance <= 0) || (Rnd.get(100) >= chance))
			{
				continue;
			}
			final ClanProfile helped = pairForKindness(helper);
			if (helped == null)
			{
				continue; // no calm partner right now; another tick may find one
			}
			final int rank = rollBeatRank(0);
			_queue.schedule(helper.getId(), MessageKind.KINDNESS, helped.getId(), 2000 + Rnd.get(8000), rank);
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: queued [KINDNESS] " + ((helper.getName() != null) ? helper.getName() : ("clan " + helper.getId())) + " toward " + ((helped.getName() != null) ? helped.getName() : ("clan " + helped.getId())) + ".");
			}
			return; // one kind beat per tick keeps it precious
		}
	}

	/** Another clan to be kind to: persona clan, present, no running arc. */
	private ClanProfile pairForKindness(ClanProfile helper)
	{
		final List<ClanProfile> options = new ArrayList<>();
		for (ClanProfile profile : _profiles.values())
		{
			if ((profile.getId() == helper.getId()) || (profile.getPersona() == null))
			{
				continue;
			}
			if (_requireMembersPresent && !profile.isPresent())
			{
				continue;
			}
			if (_arcs.containsKey(arcKey(helper.getId(), profile.getId())))
			{
				continue; // don't interrupt a live argument with pleasantries
			}
			options.add(profile);
		}
		return options.isEmpty() ? null : options.get(Rnd.get(options.size()));
	}

	// ── War stories: old quarrels remembered aloud ───────────────────────

	/**
	 * Old quarrels become war stories the clans love retelling. Rarely, on
	 * a calm stage, a clan with a remembered clash tells it aloud; the world
	 * may ask what happened, and BOTH clans then give their own version.
	 */
	private void tryStoryBeat(long now, List<ClanProfile> candidates)
	{
		for (ClanProfile teller : candidates)
		{
			final Story story = storyFor(teller.getId(), now);
			if (story == null)
			{
				continue; // nothing remembered that still lives
			}
			final int rivalId = (story.firstClanId == teller.getId()) ? story.secondClanId : story.firstClanId;
			final ClanProfile rival = _profiles.get(rivalId);
			if ((rival == null) || (rival.getPersona() == null) || (_requireMembersPresent && !rival.isPresent()))
			{
				continue; // the other side of the memory must still exist
			}
			if (pairAtRest(teller.getId(), rivalId) || _arcs.containsKey(arcKey(teller.getId(), rivalId)))
			{
				continue; // fresh grudge or running argument - there is drama already
			}
			final long delay = openingDelay();
			_queue.schedule(teller.getId(), MessageKind.HISTORY, rivalId, delay);
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: queued [HISTORY] " + clanNameOf(teller) + " remembering clan " + rivalId + ".");
			}
			return; // one memory per tick keeps stories precious
		}
	}

	/** The freshest remembered clash involving this clan, or null. */
	private Story storyFor(int clanId, long now)
	{
		Story best = null;
		for (Story story : _warStories.values())
		{
			if (((story.firstClanId != clanId) && (story.secondClanId != clanId)) || (now > (story.endedAtMillis + _storyWindowMillis)))
			{
				continue; // not this clan, or long since faded
			}
			if ((best == null) || (story.endedAtMillis > best.endedAtMillis))
			{
				best = story;
			}
		}
		return best;
	}

	/** The story one CLAN PAIR closed most recently, or null if faded. */
	private Story storyBetween(int firstId, int secondId)
	{
		final Story story = _warStories.get(arcKey(firstId, secondId));
		if ((story == null) || (System.currentTimeMillis() > (story.endedAtMillis + _storyWindowMillis)))
		{
			return null;
		}
		return story;
	}

	/** A spoken memory: question from the crowd, then both clans' versions. */
	private void scheduleStoryTelling(ClanProfile teller, ClanProfile rival)
	{
		if (Rnd.get(100) >= _storyAskChance)
		{
			return; // the story just floats; a later telling may pull a question
		}
		ThreadPool.schedule(() -> deliverStoryAsk(teller, rival), 6000 + Rnd.get(10000) + spacingHold());
	}

	/** A clan-less bystander asks what that old quarrel was about. */
	private void deliverStoryAsk(ClanProfile teller, ClanProfile rival)
	{
		final Creature asker = pickClanlessVoice();
		if (asker == null)
		{
			return; // nobody clan-less in the world to ask; the moment passes
		}
		final Story story = storyBetween(teller.getId(), rival.getId());
		if (story == null)
		{
			return; // the memory faded while we waited
		}
		// The question and the two versions are all brain-written, each on
		// its own beat through the executor seam.
		brainWorld(asker, "Just now, " + clanNameOf(teller) + " mentioned an old quarrel with " + clanNameOf(rival) + ((story.cause != null) ? " about " + story.cause : "") + ", and it was before your time in this server. Ask in ONE short line what that old fight was about - casual and curious, the way a player would ask.", "delivered [STORY-ASK]", t ->
		{
			ThreadPool.schedule(() -> tellStorySide(teller, rival, story), 9000 + Rnd.get(9000) + spacingHold());
			ThreadPool.schedule(() -> tellStorySide(rival, teller, story), 27000 + Rnd.get(12000) + spacingHold());
		});
	}

	/** One clan's own version of a remembered quarrel. */
	private void tellStorySide(ClanProfile telling, ClanProfile rival, Story story)
	{
		final VoiceChoice choice = chooseVoice(telling);
		if (choice == null)
		{
			return; // nobody left in the world to speak for them
		}
		final String ending = story.fierce ? "It ended with open threats of settling it on the battlefield." : "It ended in an uneasy peace.";
		brainWorld(choice.creature, "Your clan once quarreled with " + clanNameOf(rival) + ((story.cause != null) ? " about " + story.cause : "") + ". " + ending + " Someone just asked in the chat what happened back then. Give YOUR version of what happened: ONE short line, and your clan remembers it their way.", "delivered [STORY]", t -> telling.cooldown(System.currentTimeMillis() + _clanCooldownMillis));
	}

	private String clanNameOf(ClanProfile profile)
	{
		return (profile.getName() != null) ? profile.getName() : ("Clan " + profile.getId());
	}

	// ── Drama arcs ───────────────────────────────────────────────────────

	/**
	 * Keeps the argument breathing: one of the two arc clans speaks the next
	 * line on rank-paced reply bands (leader fastest, member most beats).
	 * Often a JUNIOR voice blurts an aside right around the main beat, like
	 * players talking over each other; the senior voice then takes over.
	 * When both sides have gone quiet (no members with a voice left), the
	 * arc closes silently instead of hanging.
	 */
	private void scheduleContinuation(int speakerId, int targetId)
	{
		final List<ClanProfile> voices = new ArrayList<>();
		for (int id : new int[] { targetId, speakerId })
		{
			final ClanProfile profile = _profiles.get(id);
			if ((profile != null) && (profile.getPersona() != null) && profile.isPresent() && !resolveMembers(id).isEmpty())
			{
				voices.add(profile);
			}
		}
		if (voices.isEmpty())
		{
			_arcs.remove(arcKey(speakerId, targetId));
			return; // nobody left to argue; the drama dissolves with no line
		}
		final ClanProfile chosen = voices.get(Rnd.get(voices.size()));
		final ClanProfile against = _profiles.get((chosen.getId() == targetId) ? speakerId : targetId);

		// Roll WHO answers: the leader rarely (unless the last reply dragged
		// and the officers were too slow), officers sometimes, members most.
		final DramaArc arc = _arcs.get(arcKey(speakerId, targetId));
		final long lastDelay = (arc != null) ? arc.lastDelayMillis : 0;
		final int rank = rollBeatRank(lastDelay);
		final long delay = bandDelay(rank);
		if (arc != null)
		{
			arc.lastDelayMillis = delay;
		}
		_queue.schedule(chosen.getId(), MessageKind.RESPONSE, (against != null) ? against.getId() : 0, delay, rank);

		// Over-chatter: around the main beat, a second JUNIOR voice of the
		// same clan blurts an aside (advance nothing; the official answer,
		// scheduled above, then takes the word back with seniority).
		if ((rank < 2) && (_sentThisWindow + 1 < _maxMessagesPerHour) && (Rnd.get(100) < _chatterOverlapChance))
		{
			final long asideDelay = Math.max(1500, delay - 1200 - Rnd.get(2400));
			_queue.schedule(chosen.getId(), MessageKind.ASIDE, (against != null) ? against.getId() : 0, asideDelay, rank + 1);
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: queued [ASIDE] by " + ((chosen.getName() != null) ? chosen.getName() : ("clan " + chosen.getId())) + " (the " + ((rank == 0) ? "officer" : "member") + " voice of the clan), overlapping the main beat.");
			}
		}
	}

	/** Stable pair key, direction-independent: "min-max". */
	private static String arcKey(int a, int b)
	{
		return Math.min(a, b) + "-" + Math.max(a, b);
	}

	private boolean pairAtRest(int a, int b)
	{
		final Long until = _pairRestUntil.get(arcKey(a, b));
		return (until != null) && (System.currentTimeMillis() < until.longValue());
	}

	/**
	 * One delivered reply advances the argument a beat. Each beat may end it:
	 * at the round cap, or on the resolve roll. The closing clan's persona
	 * weighs the solution - hotheads declare war, calm clans seek peace -
	 * and then the pair rests a random while before a new argument.
	 */
	private void advanceArc(int speakerId, int targetId, ClanProfile speakerProfile, ClanProfile targetProfile)
	{
		final String key = arcKey(speakerId, targetId);
		final DramaArc arc = _arcs.get(key);
		if (arc == null)
		{
			return;
		}
		final int round = arc.nextRound();
		if ((round < _maxDramaRounds) && (Rnd.get(100) >= _resolveChance))
		{
			scheduleContinuation(speakerId, targetId);
			return; // the argument continues; the next beat comes by reply pacing
		}
		_arcs.remove(key);

		final long now = System.currentTimeMillis();
		final long span = _maxPairRestMillis - _minPairRestMillis;
		final long rest = _minPairRestMillis + ((span > 0) ? Rnd.get((int) Math.min(span, Integer.MAX_VALUE)) : 0);
		_pairRestUntil.put(key, now + rest);
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: argument between clan " + ((targetProfile != null) ? targetProfile.getName() : String.valueOf(targetId)) + " and clan " + ((speakerProfile != null) ? speakerProfile.getName() : String.valueOf(speakerId)) + " ends; the pair rests ~" + (rest / 60000) + " min.");
		}

		// Whose persona decides the ending: the clan speaking the last line,
		// but any clan with members present for either side can carry it.
		ClanProfile closer = speakerProfile;
		if ((closer == null) || resolveMembers(closer.getId()).isEmpty())
		{
			if ((targetProfile != null) && !resolveMembers(targetProfile.getId()).isEmpty())
			{
				closer = targetProfile;
			}
			else
			{
				return; // nobody left to speak the solution; it ends in silence
			}
		}
		final PersonaType persona = closer.getPersona();
		final boolean fight;
		if ((persona == PersonaType.AGGRESSIVE) || (persona == PersonaType.PLAYER_KILLING))
		{
			fight = Rnd.get(100) < 80;
		}
		else if (persona == PersonaType.PEACEFUL)
		{
			fight = false;
		}
		else if (persona == PersonaType.ALTRUISTIC)
		{
			fight = Rnd.get(100) < 25;
		}
		else
		{
			fight = Rnd.get(100) < 50; // neutral or persona-less: coin toss
		}

		final ClanProfile answerProfile = closer;
		final ClanProfile against = (closer == speakerProfile) ? targetProfile : speakerProfile;
		final String cause = _pairCauses.get(key);
		ThreadPool.schedule(() -> deliverResolution(answerProfile, against, fight, cause), bandDelay(rollBeatRank(0)) + spacingHold());
	}

	// ── Clan voices: leader / officer / member ───────────────────────────

	/**
	 * A clan's voice: the same bot keeps the word while he stands in the
	 * world; a present higher rank may take the word from him instead.
	 */
	private static final class VoiceChoice
	{
		final Creature creature;
		final int rank;
		final String title;
		final boolean promoted;

		VoiceChoice(Creature creature, int rank, String title, boolean promoted)
		{
			this.creature = creature;
			this.rank = rank;
			this.title = title;
			this.promoted = promoted;
		}
	}

	/**
	 * Pick the talker for a clan side. Consistency first: the current
	 * talker keeps the word when he is here and no senior rank present
	 * outranks him. "Promoted" marks a senior member stepping in over a
	 * junior one, which the clan may then announce.
	 */
	private VoiceChoice chooseVoice(ClanProfile profile)
	{
		return chooseVoice(profile, -1);
	}

	/**
	 * The same, but with a rank scheduled for this beat (leader 0, officer
	 * 1, member 2): only members of THAT rank are eligible voices, so a
	 * scheduled member beat lets the clan's officer naturally take over if
	 * one is present, and a member yield reads exactly like players talk
	 * over each other. When that rank is not present, normal rules apply.
	 */
	private VoiceChoice chooseVoice(ClanProfile profile, int requestedRank)
	{
		final List<Creature> members = resolveMembers(profile.getId());
		if (members.isEmpty())
		{
			return null;
		}

		// Rank every eligible member; stable and name-derived; leader >
		// officer > member. The lowest weight present is the most senior
		// voice, and the remembered talker keeps the word when he matches.
		Creature best = null;
		int bestWeight = Integer.MAX_VALUE;
		Creature previous = null;
		int previousWeight = Integer.MAX_VALUE;
		final String previousName = _voices.get(Integer.valueOf(profile.getId()));
		for (Creature member : members)
		{
			final int weight = rankWeight(profile.getId(), member.getName());
			if ((requestedRank >= 0) && (weight != requestedRank))
			{
				continue; // this beat was scheduled for another rank
			}
			if (weight < bestWeight)
			{
				best = member;
				bestWeight = weight;
			}
			if ((previousName != null) && (member.getName() != null) && member.getName().equals(previousName))
			{
				previous = member;
				previousWeight = weight;
			}
		}
		if ((requestedRank >= 0) && (best == null))
		{
			return chooseVoice(profile, -1); // that rank left; normal rules
		}

		// The remembered talker keeps the floor unless a senior present
		// member outranks him; either way the word stays with one voice.
		Creature chosen = ((previous != null) && (previousWeight <= bestWeight)) ? previous : best;
		_voices.put(Integer.valueOf(profile.getId()), chosen.getName());
		final int chosenWeight = rankWeight(profile.getId(), chosen.getName());
		final boolean promoted = (previous != null) && (previous != chosen);
		return new VoiceChoice(chosen, chosenWeight, titleFor(chosenWeight), promoted);
	}

	/** Stable rank: the same name always holds the same role in one clan. */
	private static int rankWeight(int clanId, String name)
	{
		if (name == null)
		{
			return 2;
		}
		final int hash = Math.abs((clanId + "@" + name).hashCode()) % 10;
		return (hash < 2) ? 0 : ((hash < 4) ? 1 : 2);
	}

	private String titleFor(int rank)
	{
		return (rank == 0) ? _leaderTitle : ((rank == 1) ? _officerTitle : _memberTitle);
	}

	// ── Offense causes: why they are actually arguing ────────────────────

	/**
	 * Chooses what the pair argues about. A remembered cause repeats while
	 * the grudge is warm (personal insult memory); otherwise one of the
	 * configured incidents is drawn at random. Null: no causes configured.
	 */
	private String chooseCause(ClanProfile self, ClanProfile other)
	{
		if (_offenseCauses.isEmpty() || (other == null))
		{
			return null;
		}
		final String key = arcKey(self.getId(), other.getId());
		final String remembered = _pairCauses.get(key);
		if ((remembered != null) && _offenseCauses.contains(remembered) && _memory.argued(self.getId(), other.getId(), _grudgeWindowMillis))
		{
			return remembered; // the same old wound, reopened
		}
		final String chosen = _offenseCauses.get(Rnd.get(_offenseCauses.size()));
		_pairCauses.put(key, chosen);
		return chosen;
	}

	// ── An occasional human touch: bots correcting their own slips ───────

	/**
	 * A visible, unintentional slip that a writer might snap to: a doubled
	 * word ("the the"), a missing space after punctuation ("word.Next"), or
	 * a stray space before punctuation ("word ,").
	 */
	private static boolean hasTypoArtifact(String text)
	{
		final String[] words = text.split("\\s+");
		for (int i = 1; i < words.length; i++)
		{
			final String previous = words[i - 1].toLowerCase().replaceAll("[^a-z]", "");
			final String current = words[i].toLowerCase().replaceAll("[^a-z]", "");
			if ((previous.length() >= 2) && previous.equals(current))
			{
				return true; // the classic "the the"
			}
		}
		return text.matches(".*\\s+[,.;:!?].*") || text.matches(".*[,.;:!?][A-Za-z].*");
	}

	/**
	 * Rare and genuine: only a line with a detectable slip may earn a
	 * correction, and then still only on the configured chance. The
	 * correction itself is brain-written, in the same bot's voice.
	 */
	private void maybeSelfCorrection(Creature speaker, String spokenText)
	{
		if ((_selfCorrectionChance <= 0) || (_brain == null) || !hasTypoArtifact(spokenText) || (Rnd.get(100) >= _selfCorrectionChance))
		{
			return;
		}
		final String prompt = "Not long ago you said out loud: \"" + spokenText + "\". It contained a small typo or slip. Send ONE very short chat line correcting yourself the way players do (like \"I meant ...\" or \"oops, *...*\"). Do not repeat the whole sentence.";
		ThreadPool.schedule(() -> brainWorld(speaker, prompt, "delivered [TYPO-FIX]"), 8000 + Rnd.get(12000) + spacingHold());
	}

	/**
	 * A one-off brain line outside the persona drama (takeover brag, the
	 * "who speaks now?" exchange, a clarify aside, a typo fix). Same voice
	 * rules as the drama lines: the speaking bot's name keys the persona,
	 * and the answer is one plain spoken line.
	 */
	private String brainCustom(Creature speaker, String situation)
	{
		if (_brain == null)
		{
			return null;
		}
		return _brain.line(speaker.getName(), FakePlayerChatManager.nearestLocation(speaker.getX(), speaker.getY()), (_channel == ChatType.SHOUT) ? "SHOUT" : "SAY", WORLD_LAW + situation + " Answer with ONE line only, in character, plain words, no brackets or markup, nothing that is not spoken out loud in the world.");
	}

	// ── The brain never blocks the game's threads ─────────────────────────
	/**
	 * The compose-and-speak seam: the brain's HTTP turn (ten to forty-five
	 * seconds) runs on the pack's bounded BrainExecutor - the pool Core
	 * gave every brain caller for exactly this - and its queue DROPS a
	 * beat that sat too long, so a stale word never speaks. Delivery hops
	 * back onto the game pool, so no regen, skill or AI timer ever parks
	 * behind a generation. An unanswered brain is silence, never a stall.
	 */
	private void brainSpeak(Creature speaker, String prompt, String label, Consumer<String> after)
	{
		try
		{
			final Creature mouth = speaker;
			BrainExecutor.runBrainWork(() ->
			{
				final String text = brainCustom(mouth, prompt);
				if (text == null)
				{
					return; // brain down or slow: this beat stays silent
				}
				final String spoken = text;
				ThreadPool.schedule(() ->
				{
					sayToAll(mouth, spoken, label);
					if (after != null)
					{
						after.accept(spoken);
					}
				}, 0);
			});
		}
		catch (Throwable t)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: the brain pool refused a beat: " + t);
			}
		}
	}

	/** World-chat line on the brain pool, delivered on the game pool. */
	private void brainWorld(Creature speaker, String prompt, String label)
	{
		brainSpeak(speaker, prompt, label, null);
	}

	/** World-chat line plus a small game-pool follow-up (memory, next beat). */
	private void brainWorld(Creature speaker, String prompt, String label, Consumer<String> after)
	{
		brainSpeak(speaker, prompt, label, after);
	}

	// ── The other side asks who now speaks ───────────────────────────────

	/**
	 * When talkers bounced too often on one side, the rival clan may ask who
	 * carries their word; the new voice answers. Once per argument.
	 */
	private void scheduleLeadershipAsk(ClanProfile askingClan, ClanProfile namedClan)
	{
		if (resolveMembers(namedClan.getId()).isEmpty() || resolveMembers(askingClan.getId()).isEmpty())
		{
			return;
		}
		final ClanProfile asker = askingClan;
		ThreadPool.schedule(() -> deliverLeadershipAsk(asker, namedClan), 4000 + Rnd.get(12000) + spacingHold());
		ThreadPool.schedule(() -> deliverLeadershipReply(namedClan, asker), 24000 + Rnd.get(18000) + spacingHold());
	}

	private void deliverLeadershipAsk(ClanProfile askingClan, ClanProfile namedClan)
	{
		final VoiceChoice asker = chooseVoice(askingClan);
		if (asker == null)
		{
			return;
		}
		final String namedName = (namedClan.getName() != null) ? namedClan.getName() : _unknownClanName;
		brainWorld(asker.creature, "The rival clan " + namedName + " keeps switching who speaks for it. Ask them, in ONE short spoken line, who now speaks for their clan - a little suspicious, the way a player would ask.", "delivered [WHO]");
	}

	private void deliverLeadershipReply(ClanProfile namedClan, ClanProfile askingClan)
	{
		// The named clan's talker introduces himself to the asker's side.
		final VoiceChoice talker = chooseVoice(namedClan);
		if (talker == null)
		{
			return;
		}
		final String clanName = (namedClan.getName() != null) ? namedClan.getName() : ("Clan " + namedClan.getId());
		brainWorld(talker.creature, "Another clan just asked, out loud, who now speaks for your clan. Introduce yourself in ONE short line: you are the " + talker.title + " of " + clanName + ", and players state their rank simply, with a bit of pride.", "delivered [IDENTIFY]");
	}

	// ── The arc record ───────────────────────────────────────────────────

	/** One pair's argument: its round, its cause and how much it bounced. */
	private static final class DramaArc
	{
		final String cause;
		private int round;
		private int voiceSwitches;
		boolean leadershipAsked = false;
		/** How long the argument's last reply took to come: the patience
		 *  rule reads this to hand the next beat to the leader. */
		long lastDelayMillis = 0;
		/** When the argument's last beat happened: a stalled arc (its
		 *  clans left the world mid-exchange) sweeps itself away. */
		long lastBeatMillis = System.currentTimeMillis();

		DramaArc(int startRound, String cause)
		{
			this.round = startRound;
			this.cause = cause;
		}

		int nextRound()
		{
			return ++round;
		}

		/**
		 * True once and only when enough voice bouncing was seen, so the
		 * rival clan may ask "who speaks for you now?" - once per argument.
		 */
		boolean bounce()
		{
			voiceSwitches++;
			return (voiceSwitches >= 2) && !leadershipAsked;
		}
	}

	/** The solution line: a war cry or a diplomatic close, brain-written. */
	private void deliverResolution(ClanProfile answerProfile, ClanProfile against, boolean fight, String cause)
	{
		if ((answerProfile == null) || (answerProfile.getPersona() == null))
		{
			return;
		}
		final VoiceChoice choice = chooseVoice(answerProfile);
		if (choice == null)
		{
			return;
		}
		final String againstName = (against != null) && (against.getName() != null) ? against.getName() : _unknownClanName;
		final String ending = fight
			? "This argument is OVER. End it with a short declaration that your clan will answer " + againstName + " on the battlefield - a war cry, not a debate."
			: "This argument is OVER. End it with a short diplomatic close: your clan lets the quarrel with " + againstName + " rest for now - peace, spoken in few words."
				+ ((cause != null) ? " The quarrel was mainly about: " + cause + "." : "");
		brainWorld(choice.creature, ending, "delivered [" + (fight ? "RESOLVE-FIGHT" : "RESOLVE-PEACE") + "]", t ->
		{
			// The closing line cools its clan like any other speech.
			answerProfile.cooldown(System.currentTimeMillis() + _clanCooldownMillis);
			// The quarrel becomes a war story: retellable long after the anger
			// fades - a fresh clash overwrites the pair's older memory.
			if (against != null)
			{
				final Story story = new Story(answerProfile.getId(), against.getId(), cause, fight);
				story.endedAtMillis = System.currentTimeMillis();
				_warStories.put(arcKey(answerProfile.getId(), against.getId()), story);
			}
		});
	}

	private boolean hasAnyGrudge(int clanId)
	{
		for (ClanProfile other : _profiles.values())
		{
			if ((other.getId() != clanId) && _memory.argued(clanId, other.getId(), _grudgeWindowMillis))
			{
				return true;
			}
		}
		return false;
	}

	/** Members of a clan that are actually in the world right now: Players
	 *  wearing the bot clan, and town fake players cresting its appearance. */
	private List<Creature> resolveMembers(int clanId)
	{
		final List<Creature> result = new ArrayList<>();
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (object instanceof Player)
			{
				final Player player = (Player) object;
				if ((player.getClanId() == clanId) && (player.getClan() != null) && BotClanManager.getInstance().isBotClan(player.getClan()))
				{
					result.add(player);
				}
			}
			else if (object instanceof Npc)
			{
				final Npc npc = (Npc) object;
				if (npc.isFakePlayer() && (npc.getFakePlayerAppearance() != null) && (npc.getFakePlayerAppearance().getClanId() == clanId))
				{
					result.add(npc);
				}
			}
		}
		return result;
	}

	// ── Beat pacing: rank picks speed and voice ──────────────────────────

	/**
	 * Which rank carries the next beat of an ONGOING argument: the leader
	 * speaks rarely and takes over when the officers have been slow;
	 * officers sometimes; members carry most beats.
	 * @param previousDelayMillis how long the argument's last reply took
	 */
	private int rollBeatRank(long previousDelayMillis)
	{
		final int leaderChance = ((previousDelayMillis > _leaderPatienceMillis) && (_leaderPatienceChance > _leaderReplyChance)) ? _leaderPatienceChance : _leaderReplyChance;
		final int roll = Rnd.get(100);
		if (roll < leaderChance)
		{
			return 0;
		}
		return (roll < (leaderChance + _officerReplyChance)) ? 1 : 2;
	}

	/** A reply wait drawn from the rank's band; the leader is the fastest. */
	private long bandDelay(int rank)
	{
		final int min = (rank == 0) ? _minLeaderReplyMillis : ((rank == 1) ? _minOfficerReplyMillis : _minMemberReplyMillis);
		final int max = (rank == 0) ? _maxLeaderReplyMillis : ((rank == 1) ? _maxOfficerReplyMillis : _maxMemberReplyMillis);
		final int span = max - min;
		return min + ((span > 0) ? Rnd.get(span) : 0);
	}

	/** A NEW quarrel (or an ambient line) waits in this random window. */
	private long openingDelay()
	{
		final int span = _openingMaxMillis - _openingMinMillis;
		return _openingMinMillis + ((span > 0) ? Rnd.get(span) : 0);
	}

	/** Extra wait so no clan line lands inside another's read-and-type
	 *  moment: even beats falling due together hold their turn. */
	private long spacingHold()
	{
		if (_minBeatSpacingMillis <= 0)
		{
			return 0;
		}
		final long ready = _lastLineMillis + _minBeatSpacingMillis;
		final long now = System.currentTimeMillis();
		return (now < ready) ? (ready - now) : 0;
	}

	/** Every spoken clan word goes through here: one fan-out, one log line,
	 *  and the stamp that paces the minimum spacing between lines. */
	private void sayToAll(Creature speaker, String text, String label)
	{
		final CreatureSay packet = new CreatureSay(speaker, _channel, text);
		for (Player online : allPlayers())
		{
			online.sendPacket(packet);
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities " + label + " by " + speaker.getName() + " (" + _channel + "): " + text);
		}
		_lastLineMillis = System.currentTimeMillis();
		// EVERY spoken kind counts against the hourly budget: drama beats,
		// resolution closings, takeover announcements, story retells - all
		// one voice. The cap can now never be silently exceeded by the
		// uncounted paths, and never silently choke the world either.
		final long newStamp = _lastLineMillis;
		if (newStamp >= (_windowStart + HOUR_MILLIS))
		{
			_windowStart = newStamp;
			_sentThisWindow = 0;
		}
		_sentThisWindow++;
	}

	// ── Shorthand (nicknames) and clarification chatter ──────────────────

	/**
	 * Hand-tuned overrides first (Sun_Spear -> "SP" beats "SS"), then the
	 * auto nickname: the first letters of the words of the display name
	 * ("Azure Dragon" / "AzureDragon" / "Emerald_Serpent" all give two-letter
	 * initials). Single-word names get no nickname on purpose: a name like
	 * "Whisperfang" has no obvious short form to outsiders.
	 */
	private String abbrevFor(String name)
	{
		final String override = _abbrevOverrides.get(normalizeClanToken(name));
		return (override != null) ? override : abbreviationOf(name);
	}

	private static String abbreviationOf(String name)
	{
		if ((name == null) || (name.length() < 4))
		{
			return null;
		}
		final List<String> parts = new ArrayList<>();
		final StringBuilder current = new StringBuilder();
		for (int i = 0; i < name.length(); i++)
		{
			final char character = name.charAt(i);
			if (Character.isLetter(character))
			{
				if ((i > 0) && Character.isUpperCase(character) && !Character.isUpperCase(name.charAt(i - 1)) && (current.length() > 0))
				{
					parts.add(current.toString().toLowerCase());
					current.setLength(0);
				}
				current.append(character);
			}
			else if (current.length() > 0)
			{
				parts.add(current.toString().toLowerCase());
				current.setLength(0);
			}
		}
		if (current.length() > 0)
		{
			parts.add(current.toString().toLowerCase());
		}
		if (parts.size() < 2)
		{
			return null;
		}
		final StringBuilder abbr = new StringBuilder();
		for (String part : parts)
		{
			abbr.append(Character.toUpperCase(part.charAt(0)));
		}
		final String result = abbr.toString();
		return result.equalsIgnoreCase(name) ? null : result;
	}

	private static boolean containsIgnoreCase(String text, String target)
	{
		return text.toLowerCase().contains(target.toLowerCase());
	}

	private static String replaceIgnoreCase(String text, String target, String replacement)
	{
		try
		{
			return Pattern.compile(Pattern.quote(target), Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
		}
		catch (Exception e)
		{
			return text;
		}
	}

	/**
	 * Walks all known clan and alliance display names over the filled line;
	 * each name present in the text may be rolled into its nickname (at most
	 * two mentions in one line, so speech never collapses into alphabet soup).
	 */
	private String shortenNames(String text, List<ClanProfile> clanMentions, List<Integer> allianceMentions)
	{
		if ((text == null) || text.isEmpty())
		{
			return text;
		}
		for (ClanProfile profile : _profiles.values())
		{
			final String full = profile.getName();
			final String abbr = abbrevFor(full);
			if ((full == null) || (abbr == null) || !containsIgnoreCase(text, full) || (Rnd.get(100) >= _shorthandChance))
			{
				continue;
			}
			text = replaceIgnoreCase(text, full, abbr);
			clanMentions.add(profile);
			if (clanMentions.size() >= 2)
			{
				return text; // enough nicknames for one breath
			}
		}
		for (Map.Entry<Integer, String> entry : _allianceNames.entrySet())
		{
			final String abbr = abbrevFor(entry.getValue());
			if ((abbr == null) || !containsIgnoreCase(text, entry.getValue()) || (Rnd.get(100) >= _shorthandChance))
			{
				continue;
			}
			text = replaceIgnoreCase(text, entry.getValue(), abbr);
			allianceMentions.add(entry.getKey());
			if ((clanMentions.size() + allianceMentions.size()) >= 2)
			{
				return text;
			}
		}
		return text;
	}

	private void scheduleClarifyRequests(List<ClanProfile> clanMentions, List<Integer> allianceMentions)
	{
		final long now = System.currentTimeMillis();
		for (ClanProfile mentioned : clanMentions)
		{
			if (gateClarify("c:" + mentioned.getId(), now))
			{
				ThreadPool.schedule(() -> deliverClarifyAsk(mentioned, null), 5000 + Rnd.get(15000) + spacingHold());
				ThreadPool.schedule(() -> deliverClarifyAnswer(mentioned, null), 25000 + Rnd.get(25000) + spacingHold());
			}
		}
		for (Integer group : allianceMentions)
		{
			if (gateClarify("a:" + group.intValue(), now))
			{
				ThreadPool.schedule(() -> deliverClarifyAsk(null, group), 5000 + Rnd.get(15000) + spacingHold());
				ThreadPool.schedule(() -> deliverClarifyAnswer(null, group), 25000 + Rnd.get(25000) + spacingHold());
			}
		}
	}

	/** True on a fresh mention; false while a cooldown window still runs. */
	private boolean gateClarify(String token, long now)
	{
		final Long gate = _clarifyGates.get(token);
		if ((gate != null) && (now < gate.longValue()))
		{
			return false;
		}
		_clarifyGates.put(token, now + _clarifyCooldownMillis);
		return true;
	}

	/**
	 * A clan-less bystander (a shop fake player, a drifting phantom - never
	 * the human player) asks what a nickname stands for. Brain-written.
	 */
	private void deliverClarifyAsk(ClanProfile clan, Integer allianceGroup)
	{
		final String fullname = (clan != null) ? clan.getName() : _allianceNames.get(allianceGroup);
		final String abbr = abbrevFor(fullname);
		if (abbr == null)
		{
			return;
		}
		final Creature asker = pickClanlessVoice();
		if (asker == null)
		{
			return; // nobody clan-less in the world right now; the moment passes
		}
		final String kind = (clan != null) ? "clan" : "alliance";
		brainWorld(asker, "Someone in the world chat used the short name \"" + abbr + "\" for the " + kind + " " + fullname + ", and you have no idea which clan that is. Ask in ONE short line what \"" + abbr + "\" stands for - casual, curious, the way a player would type it.", "delivered [ASK]");
	}

	/** The named clan - or one present clan of the named alliance - explains. */
	private void deliverClarifyAnswer(ClanProfile clan, Integer allianceGroup)
	{
		final String fullname = (clan != null) ? clan.getName() : _allianceNames.get(allianceGroup);
		final String abbr = abbrevFor(fullname);
		if (abbr == null)
		{
			return;
		}
		ClanProfile answerProfile = clan;
		if (answerProfile == null)
		{
			final List<ClanProfile> banners = new ArrayList<>();
			for (ClanProfile profile : _profiles.values())
			{
				if ((profile.getAllianceGroup() == allianceGroup.intValue()) && !resolveMembers(profile.getId()).isEmpty())
				{
					banners.add(profile);
				}
			}
			if (banners.isEmpty())
			{
				return;
			}
			answerProfile = banners.get(Rnd.get(banners.size()));
		}
		final VoiceChoice talker = chooseVoice(answerProfile);
		if (talker == null)
		{
			return; // the named clan left the world; the question dies politely
		}
		final String answerName = (answerProfile.getName() != null) ? answerProfile.getName() : String.valueOf(fullname);
		brainWorld(talker.creature, "A stranger just asked in the world chat what the short name \"" + abbr + "\" means. Answer in ONE short line, proudly clearing it up: it stands for your clan " + answerName + "; players explain their clan's nickname casually, with a touch of pride.", "delivered [ANSWER]");
	}

	/**
	 * A voice with no clan: town fake players without a crest first, then
	 * living-population phantoms (the pack's registry of confirmed bots).
	 * The human player is never volunteered to speak.
	 */
	private Creature pickClanlessVoice()
	{
		// Every candidate gets a chance: scanning always picked the FIRST
		// match, so the same townsperson asked every time. Now the pool is
		// gathered whole and one voice is drawn at random.
		final List<Creature> candidates = new ArrayList<>();
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (object instanceof Npc)
			{
				final Npc npc = (Npc) object;
				if (npc.isFakePlayer() && (npc.getFakePlayerAppearance() != null) && (npc.getFakePlayerAppearance().getClanId() == 0))
				{
					candidates.add(npc); // shopkeepers and townsfolk are the natural gossips
				}
			}
		}
		for (Player player : PhantomManager.getInstance().livingPopulationPhantoms().values())
		{
			if (player.isOnline() && ((player.getClan() == null) || !BotClanManager.getInstance().isBotClan(player.getClan())))
			{
				candidates.add(player);
			}
		}
		return candidates.isEmpty() ? null : candidates.get(Rnd.get(candidates.size()));
	}

	// Confirmed for this pack: World.getInstance().getPlayers() works; every
	// fan-out in this class goes through this one helper.
	/**
	 * Every candidate voice in the world: the human-visible player list AND
	 * the pack's own living-population phantom registry (World's player
	 * collection does not reliably contain the town's fake players and
	 * living-population bots, which are exactly the ones wearing bot clans).
	 * Deduplicated: the same bot may appear in both sources.
	 */
	private List<Player> allPlayers()
	{
		final Set<Player> unique = new LinkedHashSet<>(World.getInstance().getPlayers());
		unique.addAll(PhantomManager.getInstance().livingPopulationPhantoms().values());
		final List<Player> result = new ArrayList<>();
		for (Player player : unique)
		{
			if (player.isOnline())
			{
				result.add(player);
			}
		}
		return result;
	}
}
