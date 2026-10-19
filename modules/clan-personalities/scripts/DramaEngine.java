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
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.BotClanManager;
import org.l2jmobius.gameserver.managers.BrainExecutor;
import org.l2jmobius.gameserver.managers.FakePlayerChatManager;
import org.l2jmobius.gameserver.managers.PhantomCombatActions;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomPlaystyleEngine;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemGrade;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.instance.Guard;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerAppearance;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.model.zone.type.WaterZone;
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
	private int _clarifyChance = 40;
	private int _nicknameChance = 45;
	private int _nicknameDriftChance = 4;
	private long _clarifyCooldownMillis = 21600000L; // six hours per mention
	private final Map<Integer, String> _allianceNames = new LinkedHashMap<>();
	private final Map<String, Long> _clarifyGates = new ConcurrentHashMap<>();
	private final Map<String, String> _clanNicknames = new ConcurrentHashMap<>();
	/** module-side persistence hook: (normalizedClanToken, coinedNickname). */
	private BiConsumer<String, String> _nicknameDriftSink = null;
	/** Challenge steel: percent chance a public "bring it" becomes a real
	 *  duel at the gates, and the cue phrases trash talk uses to dare. */
	private int _challengeFightChance = 35;
	private List<String> _challengeCues = new ArrayList<>();
	/** Gate-guard distance the arena keeps from any standing guard. */
	private int _guardClearRadius = 900;
	/** Lead-in: seconds of pre-fight silence the world can use to walk
	 *  over (trash talk and walking heralds fill that window). */
	private int _fieldLeadSeconds = 150;
	/** Walk-in heralds drifting toward the arena, commenting locally. */
	private int _fieldHeralds = 5;
	/** A watcher at the fence holds the gate open this far (units). */
	private int _playerGraceRadius = 3000;
	/** A watcher still ON THE ROAD (this far off the Gatekeeper->arena
	 *  line) also holds the gate: they are coming, just not close yet. */
	private int _playerRoadRadius = 2200;
	/** ...but no watcher holds it past this, ever (millis past start). */
	private long _playerGraceMaxMillis = 180000;

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

	// ── Phase 2: field encounters (real clashes, world-witnessed) ──
	/** Master switch for the physical part of the drama. */
	private boolean _encounters;
	/** Base odds a field duel breaks out per tick (persona-weighed). */
	private int _encounterChance = 25;
	/** How close two members must stand for toes to be stepped on. */
	private int _encounterScanRadius = 700;
	/** A duel lasts at most this long; then both withdraw, narratively. */
	private int _fightMaxSeconds = 90;
	/** Odds that one spectator voice narrates the clash mid-fight. */
	private int _fightCrowdChance = 60;
	/** Odds an Aggressive/PK loser blames its idle alliance aloud. */
	private int _loserBlameChance = 50;
	/** Fighters booked into a duel can never be drafted twice. */
	private final Map<Integer, Long> _fightLocks = new ConcurrentHashMap<>();
	/** One live duel per clan pair, keyed by the normalized pair key. */
	private final Map<String, FieldFight> _fieldFights = new ConcurrentHashMap<>();
	/** Clan-level duel locks: a clan never walks out to two fights at once. */
	private final Map<Integer, Long> _clanFightLocks = new ConcurrentHashMap<>();
	/** Until this moment no NEW drama opens: a duel owns the stage while
	 *  it runs, and the world keeps its attention for a while afterwards. */
	private volatile long _fieldQuietUntil;
	/** One-shot log bookkeeping: the freeze announcements speak once each. */
	private volatile boolean _stageFrozenAnnounced;
	/** The skirmish bank: no new field duel stages before this moment.
	 *  Each staged fight charges the bank by its size, and a hot day's
	 *  fighting stretches every later wait - big brawls weigh the most. */
	private volatile long _nextSkirmishMillis;
	private int _skirmishHeat;
	private long _skirmishHeatDecayAt;
	/** Skirmish bank dials: base quiet between duels, and the extra wait
	 *  per notch of heat a fight's size left in the day. */
	private long _skirmishCooldownMillis = 240000L;
	private long _skirmishHeatStepMillis = 90000L;
	/** The brew: recent beats of REAL bickering between two clans, pair
	 *  keyed (beats, first beat, last beat). Steel must be justified by a
	 *  simmering argument behind it - a fight is a quarrel's chosen
	 *  ending, never a dice roll from thin air. */
	private final ConcurrentHashMap<String, long[]> _brew = new ConcurrentHashMap<>();
	private int _brewBeats = 3;
	private long _brewWindowMillis = 900000L;  // anger lingers a quarter hour
	private long _brewMinMillis = 90000L;      // and needs a little time to build

	/** A live field duel: champions materialized at the arena as REAL
	 *  combat phantoms wearing their clans' town identity, one to three
	 *  per side, fists flying after a short lead-in the world can use
	 *  to walk to the gates. */
	private static final class FieldFight
	{
		final List<Player> sideA; // the aggressor clan's champions
		final List<Player> sideB; // the rival clan's champions
		final List<String> namesA;
		final List<String> namesB;
		final int clanAId;
		final int clanBId;
		final String townName;
		final String compassSide;
		final int arenaX;
		final int arenaY;
		final int arenaZ;
		final int guideX;
		final int guideY;
		final long startedAtMillis;
		final long fightStartMillis; // when blowing may begin (lead-in over)
		final long maxMillis;
		volatile int crowdBeats;
		volatile boolean running = true;
		/** Blow-report cadence: side HP percentage to the log. */
		long lastProbeMillis = 0;
		/** Pre-blow patience: a nearby watcher holds the gate open. */
		volatile boolean begun;
		volatile long gateMillis;
		volatile boolean stallAnnounced;
		/** Squads gather in TOWN and march out when a watcher arrives. */
		volatile boolean marched;
		volatile long marchedAtMillis;
		/** A watcher stood AT the fence: the gate opens on a short fuse. */
		volatile boolean closeCalled;
		/** Pre-blow heckling cadence, alternating between the squads. */
		long lastTrashMillis = 0;
		boolean trashSide;
		/** The underdog calls in ONE friend mid-fight, when the gear gap
		 *  was wide at staging; null when no reinforcement is due. */
		volatile Npc reinforceSource;
		volatile boolean reinforceSideA;
		/** Bodies of the peanut gallery in the ring around the arena. */
		final List<Player> spectators = new ArrayList<>();
		/** Crowd-chat cadence: who speaks when, and how many beats. */
		long lastSpectatorMillis = 0;
		int spectatorBeats = 0;
		/** One-shot skill notes so tick diagnostics never turn to spam. */
		final Set<String> notedSkillNotes = new LinkedHashSet<>();
		/** Loud spectacles (3v3+) announce, guide and draw crowds; quiet
		 *  duels (1v1/2v2) speak only between the two clans involved. */
		volatile boolean loud;
		/** One-shot ferry markers for march stragglers. */
		final Set<String> ferried = new LinkedHashSet<>();
		/** Per-fighter playstyle memory, so the pack casts real skills. */
		final Map<String, PhantomPlaystyleEngine.PlayState> states = new LinkedHashMap<>();
		/** When the gate actually opened (blows allowed). */
		volatile long begunAtMillis;
		/** First tick anyone bleeds: the duel is truly engaged. */
		volatile long firstBloodMillis;
		/** Once-per-fight engagement diagnostic marker. */
		volatile boolean engageLogged;
		/** Herald bodies the module materialized itself (released after). */
		final List<Player> heralds = new ArrayList<>();

		FieldFight(List<Player> sideA, List<Player> sideB, List<String> namesA, List<String> namesB, int clanAId, int clanBId, String townName, String compassSide, int arenaX, int arenaY, int arenaZ, int guideX, int guideY, long startedAtMillis, long fightStartMillis, long maxMillis)
		{
			this.sideA = sideA;
			this.sideB = sideB;
			this.namesA = namesA;
			this.namesB = namesB;
			this.clanAId = clanAId;
			this.clanBId = clanBId;
			this.townName = townName;
			this.compassSide = compassSide;
			this.arenaX = arenaX;
			this.arenaY = arenaY;
			this.arenaZ = arenaZ;
			this.guideX = guideX;
			this.guideY = guideY;
			this.startedAtMillis = startedAtMillis;
			this.fightStartMillis = fightStartMillis;
			this.maxMillis = maxMillis;
		}
	}

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
		// Field fights own the stage: while a duel is live (or its aftermath
		// is still settling), no NEW drama opens anywhere - the world's
		// clans are supposed to be watching the steel.
		final boolean stageFrozen = fieldStageFrozen(now);

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

		if (stageFrozen)
		{
			// A duel is live (or its aftermath still holds the stage):
			// only the encounter subsystem ticks; words wait their turn.
			tryFieldEncounter(now);
			return;
		}

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

		// Nickname drift: during this quiet tick the world's slang may grow
		// by one - the brain coins a player-style nickname that sticks.
		if (Rnd.get(100) < _nicknameDriftChance)
		{
			tryNicknameDrift();
		}

		// War stories: an OLD quarrel may be remembered aloud. Bad drama
		// keeps priority - nostalgia belongs only on a calm stage, exactly
		// like kindness, and an argument running anywhere outranks it.
		if ((Rnd.get(100) < _historyChance) && _arcs.isEmpty())
		{
			tryStoryBeat(now, candidates);
		}

		// Phase 2: field encounters - clan members physically stepping on
		// each other's toes. Real combat between real phantoms, staged
		// outside the towns; then the world hears about it.
		if (_encounters && (_profiles.size() > 1))
		{
			tryFieldEncounter(now);
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

		// Field fights own the stage: while a duel is live (or its aftermath
		// still settles), queued clan chat holds its turn - the world watches
		// the steel first, then reads the words.
		if (fieldStageFrozen(now))
		{
			_queue.schedule(clanId, kind, otherClanId, 45000 + Rnd.get(45000), voiceRank);
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

	/** Clarify odds: the percent chance a nickname earns its question at all
	 *  (the once-per-mention-per-window gates still hold on top of it). */
	void setClarifyChance(int chance)
	{
		_clarifyChance = Math.max(0, Math.min(100, chance));
	}

	/** Hand-tuned funny clan nicknames ("Ivory_Crown=Ivy Crown"): when a clan
	 *  carries one, speech may use the fun name instead of the initials -
	 *  players coin nicknames all the time; bots do too. */
	void setNicknames(Map<String, String> nicknames, int chance)
	{
		_clanNicknames.clear();
		_clanNicknames.putAll(nicknames);
		_nicknameChance = Math.max(0, Math.min(100, chance));
	}

	/** Nickname drift: on calm ticks the brain may coin one more player-style
	 *  nickname for a clan missing one; the module persists the coin so it
	 *  sticks between restarts, and speech starts using it. */
	void setNicknameDrift(int chance, BiConsumer<String, String> sink)
	{
		_nicknameDriftChance = Math.max(0, Math.min(100, chance));
		_nicknameDriftSink = sink;
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
	void setEncounters(int encounterChance, int encounterScanRadius, int fightMaxSeconds, int fightCrowdChance, int loserBlameChance)
	{
		_encounters = true;
		_encounterChance = Math.max(1, encounterChance);
		_encounterScanRadius = Math.max(100, encounterScanRadius);
		_fightMaxSeconds = Math.max(20, fightMaxSeconds);
		_fightCrowdChance = Math.min(100, Math.max(0, fightCrowdChance));
		_loserBlameChance = Math.min(100, Math.max(0, loserBlameChance));
	}

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

	// ── Phase 2: field encounters - real clashes, world-witnessed ────────

	/**
	 * The stage freezes from the moment a duel forms until its aftermath
	 * has settled: while frozen, new drama opens nowhere and queued chat
	 * holds its turn. Transitions are announced once each.
	 */
	private boolean fieldStageFrozen(long now)
	{
		// The skirmish bank cools with the day: half an hour of quiet
		// takes one notch of heat off, so stray duels stay affordable.
		if ((_skirmishHeat > 0) && (now >= _skirmishHeatDecayAt))
		{
			_skirmishHeat--;
			_skirmishHeatDecayAt = now + 1800000L;
		}
		final boolean frozen = !_fieldFights.isEmpty() || (now < _fieldQuietUntil);
		if (frozen != _stageFrozenAnnounced)
		{
			_stageFrozenAnnounced = frozen;
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-STAGE] " + (frozen ? "frozen - a duel owns the world's attention; new drama pauses." : "released - the stage is clear again."));
			}
		}
		return frozen;
	}

	/**
	 * Picks the duel arena: on open ground just OUTSIDE the boundary of the
	 * nearest major town (Giran gets first call when in reach), so anyone
	 * strolling town or nearby can walk out and watch the fight. Returns
	 * null if the world offers no arena.
	 */
	private FieldArena fieldArena(int aggroX, int aggroY, int aggroZ, int rivalX, int rivalY, int rivalZ)
	{
		final int midX = (aggroX + rivalX) / 2;
		final int midY = (aggroY + rivalY) / 2;
		final Collection<TownZone> zones = ZoneManager.getInstance().getAllZones(TownZone.class);
		// A fight does not open on a player's doorstep: any town currently
		// hosting a REAL player is off-limits - the walk IS the event, so
		// the show is staged where the watcher must travel to it.
		final Set<TownZone> watching = new LinkedHashSet<>();
		for (Player online : allPlayers())
		{
			if (PhantomManager.getInstance().livingPopulationPhantoms().containsValue(online))
			{
				continue;
			}
			for (TownZone zone : zones)
			{
				if (!watching.contains(zone) && zone.isInsideZone(online.getX(), online.getY(), online.getZ()))
				{
					watching.add(zone);
					if (_infoHook != null)
					{
						_infoHook.accept("clan-personalities: [FIELD-SCAN] " + zone.getName() + " is off-limits - a watcher stands inside it.");
					}
				}
			}
		}
		TownZone chosen = null;
		TownZone giran = null;
		double bestDistance = Double.MAX_VALUE;
		double giranDistance = Double.MAX_VALUE;
		for (TownZone zone : zones)
		{
			if (watching.contains(zone))
			{
				continue;
			}
			final Location center = zone.getZone().getCenterPoint();
			if (center == null)
			{
				continue;
			}
			final double dx = center.getX() - midX;
			final double dy = center.getY() - midY;
			final double distance = Math.sqrt((dx * dx) + (dy * dy));
			if (distance < bestDistance)
			{
				bestDistance = distance;
				chosen = zone;
			}
			final String name = zone.getName();
			if ((name != null) && name.toLowerCase().contains("giran") && (distance < giranDistance))
			{
				giranDistance = distance;
				giran = zone;
			}
		}
		if (chosen == null)
		{
			// Every town had a watcher standing in it: relax the rule -
			// better a doorstep fight than none at all.
			for (TownZone zone : zones)
			{
				final Location center = zone.getZone().getCenterPoint();
				if (center == null)
				{
					continue;
				}
				final double dx = center.getX() - midX;
				final double dy = center.getY() - midY;
				final double distance = Math.sqrt((dx * dx) + (dy * dy));
				if (distance < bestDistance)
				{
					bestDistance = distance;
					chosen = zone;
				}
			}
			if ((chosen != null) && (_infoHook != null))
			{
				_infoHook.accept("clan-personalities: [FIELD-SCAN] every town was watched - falling back to " + chosen.getName() + ".");
			}
		}
		// Giran is the world's crossroads: inside a league of the crossed
		// toes, it hosts the fight (user rule: fights belong by Giran, or
		// by the nearest major town) so nobody misses a brawl for distance.
		if ((giran != null) && (giranDistance < 15000))
		{
			chosen = giran;
		}
		if (chosen == null)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-SCAN] arena refused: no town zone found.");
			}
			return null;
		}
		final Location center = chosen.getZone().getCenterPoint();
		if (center == null)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-SCAN] arena refused: chosen town has no center point.");
			}
			return null;
		}
		// The heralds' meeting point FIRST: right beside the town's
		// Gatekeeper - the very spot a teleporting player lands. The
		// walk to the arena starts here, so every test below measures
		// from this spot, not from the town's abstract center.
		int guideX = center.getX();
		int guideY = center.getY();
		int guideZ = GeoEngine.getInstance().getSpawnHeight(guideX, guideY, center.getZ());
		int guideBest = Integer.MAX_VALUE;
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (!(object instanceof Npc))
			{
				continue;
			}
			final Npc npc = (Npc) object;
			final String npcName = npc.getName();
			if ((npcName == null) || !npcName.toLowerCase().contains("gatekeeper") || !chosen.isInsideZone(npc.getX(), npc.getY(), npc.getZ()))
			{
				continue;
			}
			final long gdx = npc.getX() - center.getX();
			final long gdy = npc.getY() - center.getY();
			final long distSq = (gdx * gdx) + (gdy * gdy);
			if (distSq < guideBest)
			{
				guideBest = (int) Math.min(Integer.MAX_VALUE, distSq);
				guideX = npc.getX();
				guideY = npc.getY();
				guideZ = npc.getZ();
			}
		}
		// Now find the arena by WALKABILITY, not by a hoped-for direction:
		// sixteen compass rays march out from the Gatekeeper, and the first
		// ray whose whole straight walk stays on dry, hostile-free ground
		// past the wall line wins. Some towns have no north exit - the
		// world simply leaves wherever the roads truly go, and the herald
		// trail is placed along the very walk that was proven possible.
		final Collection<WaterZone> waters = ZoneManager.getInstance().getAllZones(WaterZone.class);
		int x = 0;
		int y = 0;
		int z = 0;
		int offX = 0;
		int offY = 0;
		double marchX = 1;
		double marchY = 0;
		boolean placed = false;
		int closestSeen = Integer.MAX_VALUE;
		int probeX = guideX;
		int probeY = guideY;
		final int rayCount = 16;
		final int startRay = Rnd.get(rayCount);
		for (int ray = 0; (ray < rayCount) && !placed; ray++)
		{
			final double ang = ((ray + startRay) % rayCount) * (Math.PI * 2 / rayCount);
			final double dirX = Math.cos(ang);
			final double dirY = Math.sin(ang);
			final double sideX = -dirY;
			final double sideY = dirX;
			// Where does the wall line fall on this ray?
			int exitR = -1;
			for (int r = 60; r <= 6000; r += 60)
			{
				if (!chosen.isInsideZone((int) (guideX + (dirX * r)), (int) (guideY + (dirY * r)), guideZ))
				{
					exitR = r;
					break;
				}
			}
			if (exitR < 0)
			{
				continue;
			}
			int pushR = exitR + 40;
			for (int attempt = 0; attempt <= 8; attempt++)
			{
				final int tryX = (int) (guideX + (dirX * pushR));
				final int tryY = (int) (guideY + (dirY * pushR));
				probeX = tryX;
				probeY = tryY;
				final int spectA = hostileDistance(tryX, tryY);
				final int spectB = hostileDistance(tryX + (int) (sideX * 260), tryY + (int) (sideY * 260));
				final int spect = (spectA < 0) ? ((spectB < 0) ? Integer.MAX_VALUE : spectB) : ((spectB < 0) ? spectA : Math.min(spectA, spectB));
				if (spect < closestSeen)
				{
					closestSeen = spect;
				}
				final int tryZ = GeoEngine.getInstance().getSpawnHeight(tryX, tryY, guideZ);
				final boolean wet = waterAt(waters, tryX, tryY, tryZ) || waterAt(waters, tryX + (int) (sideX * 260), tryY + (int) (sideY * 260), tryZ);
				// The walk test: the WHOLE straight trail from the
				// Gatekeeper to the arena must be walkable ground -
				// this is what keeps the breadcrumb trail honest.
				final boolean walkable = GeoEngine.getInstance().canMoveToTarget(new Location(guideX, guideY, guideZ), new Location(tryX, tryY, tryZ));
				if (walkable && !wet && ((spect < 0) || (spect > _guardClearRadius)))
				{
					x = tryX;
					y = tryY;
					z = tryZ;
					offX = (int) (sideX * 260);
					offY = (int) (sideY * 260);
					marchX = dirX;
					marchY = dirY;
					placed = true;
					if (_infoHook != null)
					{
						_infoHook.accept("clan-personalities: [FIELD-SCAN] the walkable road found " + Math.round(ang * 180 / Math.PI) + "° out of " + chosen.getName() + " (" + tryX + "," + tryY + "; " + pushR + " units from the gatekeeper).");
					}
					break;
				}
				pushR += 400;
			}
		}
		if (!placed)
		{
			// Every ray is watched, wet or unwalkable: no clean arena here.
			// Point at the very guard that vetoed the closest attempt.
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-SCAN] arena refused: every walkable approach of " + chosen.getName() + " is blocked or watched (closest " + closestSeen + "m; blocker: " + nearestGuard(probeX, probeY) + ").");
			}
			return null;
		}
		final String townName = (chosen.getName() != null) ? chosen.getName().replace('_', ' ') : "the town";
		return new FieldArena(x, y, z, offX, offY, center.getX(), center.getY(), guideX, guideY, guideZ, marchX, marchY, townName);
	}

	/** Water spoils a duel piste: any water zone covering the point vetoes it. */
	private boolean waterAt(Collection<WaterZone> waters, int x, int y, int z)
	{
		for (WaterZone water : waters)
		{
			if (water.isInsideZone(x, y, z))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The surface-safe road position of one herald post: the height is
	 * resolved with the pack's SPAWN-safe resolver (never snaps to a cave,
	 * tunnel or water layer below), and if the straight trail from the
	 * Gatekeeper is blocked by terrain, the post is walked back toward the
	 * Gatekeeper until the trail stays on open ground.
	 */
	private Location postSpot(FieldArena arena, Collection<WaterZone> waters, double fraction)
	{
		for (int pull = 0; pull <= 4; pull++)
		{
			final double f = fraction / (1 << pull);
			final int px = arena.guideX + (int) ((arena.x - arena.guideX) * f);
			final int py = arena.guideY + (int) ((arena.y - arena.guideY) * f);
			final int pz = GeoEngine.getInstance().getSpawnHeight(px, py, arena.guideZ);
			if (waterAt(waters, px, py, pz))
			{
				continue; // a post by the shore, not beneath it
			}
			if ((pull == 4) || GeoEngine.getInstance().canMoveToTarget(new Location(arena.guideX, arena.guideY, arena.guideZ), new Location(px, py, pz)))
			{
				if ((pull > 0) && (_infoHook != null))
				{
					_infoHook.accept("clan-personalities: [HERALD] the trail was blocked - the post walked back to " + Math.round(f * 100) + "% of the way (" + px + "," + py + ").");
				}
				return new Location(px, py, pz);
			}
		}
		// unreachable (pull==4 always returns), the compiler just wants spots
		return new Location(arena.guideX, arena.guideY, arena.guideZ);
	}

	/** Distance to the closest guard standing anywhere the world knows;
	 *  -1 when no guard exists in the world's object pool at all. Long
	 *  arithmetic: delta squares across the whole map overflow int. */
	private int guardDistance(int x, int y)
	{
		long bestSq = Long.MAX_VALUE;
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (!(object instanceof Guard))
			{
				continue;
			}
			final long dx = object.getX() - x;
			final long dy = object.getY() - y;
			final long distSq = (dx * dx) + (dy * dy);
			if (distSq < bestSq)
			{
				bestSq = distSq;
			}
		}
		return (bestSq == Long.MAX_VALUE) ? -1 : (int) Math.sqrt(bestSq);
	}

	/** Distance to the closest HOSTILE thing about an open point: a standing
	 *  gate guard or an AGGRESSIVE monster - either one spoils a duel piste.
	 *  -1 when nothing hostile is known to the whole world pool. */
	private int hostileDistance(int x, int y)
	{
		long bestSq = Long.MAX_VALUE;
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (!(object instanceof Guard) && !(object instanceof Monster))
			{
				continue;
			}
			final boolean aggressive = (object instanceof Guard) || ((Monster) object).isAggressive();
			if (!aggressive)
			{
				continue;
			}
			final long dx = object.getX() - x;
			final long dy = object.getY() - y;
			final long distSq = (dx * dx) + (dy * dy);
			if (distSq < bestSq)
			{
				bestSq = distSq;
			}
		}
		return (bestSq == Long.MAX_VALUE) ? -1 : (int) Math.sqrt(bestSq);
	}

	/** Who the nearest guard actually is, for the refusal confession. */
	private String nearestGuard(int x, int y)
	{
		Guard best = null;
		long bestSq = Long.MAX_VALUE;
		int guardsSeen = 0;
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (!(object instanceof Guard))
			{
				continue;
			}
			guardsSeen++;
			final long dx = object.getX() - x;
			final long dy = object.getY() - y;
			final long distSq = (dx * dx) + (dy * dy);
			if (distSq < bestSq)
			{
				bestSq = distSq;
				best = (Guard) object;
			}
		}
		if (best == null)
		{
			return "none";
		}
		return best.getName() + " id " + best.getObjectId() + " at " + best.getX() + "," + best.getY() + " (" + (int) Math.sqrt(bestSq) + "m away; " + guardsSeen + " guard(s) in the world registry)";
	}

	/** One chosen duel arena: location, the rival's sidestep offset, the
	 *  town center it grew from, the march direction, and the heralds'
	 *  meeting point right beside the town's Gatekeeper (where players
	 *  land when they teleport in). */
	private static final class FieldArena
	{
		final int x;
		final int y;
		final int z;
		final int offX;
		final int offY;
		final int centerX;
		final int centerY;
		final int guideX;
		final int guideY;
		final int guideZ;
		final double dirX;
		final double dirY;
		final String townName;

		FieldArena(int x, int y, int z, int offX, int offY, int centerX, int centerY, int guideX, int guideY, int guideZ, double dirX, double dirY, String townName)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.offX = offX;
			this.offY = offY;
			this.centerX = centerX;
			this.centerY = centerY;
			this.guideX = guideX;
			this.guideY = guideY;
			this.guideZ = guideZ;
			this.dirX = dirX;
			this.dirY = dirY;
			this.townName = townName;
		}
	}

	/** Who starts field fights: hot blood steps on toes; sheep don't. */
	private int fieldInitiateChance(PersonaType persona, int baseChance)
	{
		if (persona == null)
		{
			return 0; // persona-less clans hold no fists
		}
		switch (persona)
		{
			case AGGRESSIVE:
			case PLAYER_KILLING:
			{
				return baseChance;
			}
			case NEUTRAL:
			{
				return baseChance / 3;
			}
			case ALTRUISTIC:
			{
				return baseChance / 4;
			}
			default:
			{
				return 0; // PEACEFUL never starts one
			}
		}
	}

	/**
	 * The toe-stepping scan: two clan members standing close in the field
	 * may escalate into a real duel - staged just outside any town zone,
	 * run by the pack's own combat actions, then narrated for the world.
	 */
	private void tryFieldEncounter(long now)
	{
		if (!_fieldFights.isEmpty())
		{
			return; // one duel at a time owns the world's attention
		}
		// The pot cools: quarrels abandoned for over half an hour stop
		// counting as live simmering.
		final List<String> spent = new ArrayList<>();
		for (Map.Entry<String, long[]> entry : _brew.entrySet())
		{
			if ((now - entry.getValue()[2]) > 1800000L)
			{
				spent.add(entry.getKey());
			}
		}
		for (String key : spent)
		{
			_brew.remove(key);
		}
		// Candidate clans: personas alive, present, not mid-duel, and with a
		// cloneable field champion - a town member (Npc fake player) whose
		// identity the pack can walk out of the gates as a real fighter.
		final List<ClanProfile> clans = new ArrayList<>();
		final Map<ClanProfile, Npc> champions = new LinkedHashMap<>();
		for (ClanProfile profile : _profiles.values())
		{
			if (profile.getPersona() == null)
			{
				continue;
			}
			if (_requireMembersPresent && !profile.isPresent())
			{
				continue;
			}
			final Long lock = _clanFightLocks.get(Integer.valueOf(profile.getId()));
			if ((lock != null) && (now < lock.longValue()))
			{
				continue; // their champion is already in the field
			}
			final Npc champion = fieldChampion(profile);
			if (champion == null)
			{
				continue;
			}
			clans.add(profile);
			champions.put(profile, champion);
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [FIELD-SCAN] clansReady=" + clans.size() + " of " + _profiles.size() + "; the pot simmers between " + _brew.size() + " marked pairs - only simmering arguments may walk to steel.");
		}
		if (clans.size() < 2)
		{
			return;
		}
		Collections.shuffle(clans);
		for (ClanProfile aggressor : clans)
		{
			final int chance = fieldInitiateChance(aggressor.getPersona(), _encounterChance);
			if (chance <= 0)
			{
				continue; // Peaceful clans never start one
			}
			for (ClanProfile rival : clans)
			{
				if (rival == aggressor)
				{
					continue;
				}
				final String pairKey = arcKey(aggressor.getId(), rival.getId());
				if (_fieldFights.containsKey(pairKey))
				{
					continue; // a live duel already owns the pair
				}
				// NO real bickering behind the pair? NO steel. A fight is
				// the chosen end of words that actually happened - chat
				// drama first, fists after, never a dice roll from air.
				if (!brewedForSteel(pairKey))
				{
					continue;
				}
				// Persona tints HOW the brew breaks: hot blood finishes
				// what words started; grudged pairs flare twice as easily.
				int roll = chance;
				if (_memory.argued(aggressor.getId(), rival.getId(), _grudgeWindowMillis))
				{
					roll *= 2;
				}
				if (Rnd.get(100) >= roll)
				{
					continue;
				}
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [FIELD-TRY] the brew breaks: " + clanNameOf(aggressor) + " vs " + clanNameOf(rival) + " after " + brewBeats(pairKey) + " beats of real bickering; staging now.");
				}
				if (stageFight(aggressor, rival, now))
				{
					return; // one duel owns the stage; the world watches
				}
			}
		}
	}

	/** One walkable champion: a live town member (Npc fake player). */
	private Npc fieldChampion(ClanProfile profile)
	{
		Npc chosen = null;
		int seen = 0;
		for (Creature member : resolveMembers(profile.getId()))
		{
			if (!(member instanceof Npc) || member.isAlikeDead())
			{
				continue;
			}
			final Npc npc = (Npc) member;
			if (npc.getFakePlayerAppearance() == null)
			{
				continue;
			}
			seen++;
			if (Rnd.get(seen) == 0)
			{
				chosen = npc;
			}
		}
		return chosen;
	}

	/** Every live town member of one clan (Npc fake players), any order. */
	private List<Npc> clanMembers(ClanProfile profile)
	{
		final List<Npc> found = new ArrayList<>();
		for (Creature member : resolveMembers(profile.getId()))
		{
			if (!(member instanceof Npc) || member.isAlikeDead() || ((Npc) member).getFakePlayerAppearance() == null)
			{
				continue;
			}
			found.add((Npc) member);
		}
		return found;
	}

	/** Mean loadout weight of a candidate pool, in grade points. */
	private static double poolAverage(List<Npc> pool)
	{
		if (pool.isEmpty())
		{
			return 0.0;
		}
		double total = 0;
		for (Npc member : pool)
		{
			total += appearancePower(member.getFakePlayerAppearance());
		}
		return total / pool.size();
	}

	/** Mean loadout weight of a staged side, in grade points. */
	private static double squadPower(List<FakePlayerAppearance> aps)
	{
		if (aps.isEmpty())
		{
			return 0.0;
		}
		double total = 0;
		for (FakePlayerAppearance ap : aps)
		{
			total += appearancePower(ap);
		}
		return total / aps.size();
	}

	/** Gear-fair pick: shuffled inside equal closeness, then ordered by
	 *  how close each member's loadout sits to the fight's common tier. */
	private static List<Npc> pickGearFair(List<Npc> pool, int max, int targetPower)
	{
		Collections.shuffle(pool);
		pool.sort((a, b) -> Integer.compare(Math.abs(appearancePower(a.getFakePlayerAppearance()) - targetPower), Math.abs(appearancePower(b.getFakePlayerAppearance()) - targetPower)));
		return pool.size() > max ? pool.subList(0, max) : pool;
	}

	/** How heavy a loadout is, in grade points: each worn piece counts its
	 *  crystal grade (NONE=0 .. S=5), the weapon counts double, and the
	 *  weave of enchants cherry-tops the steel. */
	private static int appearancePower(FakePlayerAppearance ap)
	{
		try
		{
			int score = 0;
			score += 2 * gradePoints(ap.getEquipRHand());
			score += gradePoints(ap.getEquipLHand());
			score += gradePoints(ap.getEquipHead());
			score += gradePoints(ap.getEquipChest());
			score += gradePoints(ap.getEquipLegs());
			score += gradePoints(ap.getEquipGloves());
			score += gradePoints(ap.getEquipFeet());
			score += gradePoints(ap.getEquipCloak());
			score += Math.min(ap.getWeaponEnchantLevel(), 16);
			return score;
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	/** Crystal grade of an item id as points, NONE=0 .. S=5; unknown = 0. */
	private static int gradePoints(int itemId)
	{
		if (itemId <= 0)
		{
			return 0;
		}
		try
		{
			final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
			return (template == null) ? 0 : template.getItemGrade().ordinal();
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	/**
	 * Stage the duel: both clans' champions materialize at an arena just
	 * outside a major town's gate (clear of guards), wearing their clan
	 * identity as REAL combat phantoms - 1v1, 2v2 or 3v3 - facing each
	 * other across the arena. The blows wait out a lead-in the world can
	 * use to walk over; then the pack's combat actions run the melee.
	 */
	private boolean stageFight(ClanProfile profileA, ClanProfile profileB, long now)
	{
		// The skirmish bank: the world holds only so many duels. After a
		// brawl the roads clear for a while - and each fight makes the
		// NEXT one wait longer, its charge growing with the squad size.
		if (now < _nextSkirmishMillis)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-SCAN] the skirmish bank is drawing down - no new duel until it refills (" + ((_nextSkirmishMillis - now) / 1000) + "s).");
			}
			return false;
		}
		// Squad size: the world's mood picks 1v1, 2v2 or 3v3, limited by
		// how many walkable members each clan has out there today.
		final int requested = 1 + Rnd.get(3);
		final List<Npc> poolA = clanMembers(profileA);
		final List<Npc> poolB = clanMembers(profileB);
		if (poolA.isEmpty() || poolB.isEmpty())
		{
			return false;
		}
		// The fight's common gear tier: the average loadout across BOTH
		// clans' available members, so each squad converges on the middle.
		// A clan hands the colors to the members whose loadout sits CLOSEST
		// to that tier - no more D-grade lambs against full-B monsters.
		final int targetPower = (int) Math.round((poolAverage(poolA) + poolAverage(poolB)) / 2.0);
		final List<Npc> champsA = pickGearFair(poolA, requested, targetPower);
		final List<Npc> champsB = pickGearFair(poolB, requested, targetPower);
		final int size = Math.min(requested, Math.min(champsA.size(), champsB.size()));
		if (size <= 0)
		{
			return false;
		}
		final List<FakePlayerAppearance> apsA = new ArrayList<>();
		final List<String> namesA = new ArrayList<>();
		for (Npc champion : champsA)
		{
			final FakePlayerAppearance appearance = champion.getFakePlayerAppearance();
			if ((appearance == null) || (appearance.getName() == null))
			{
				continue;
			}
			apsA.add(appearance);
			namesA.add(appearance.getName());
		}
		final List<FakePlayerAppearance> apsB = new ArrayList<>();
		final List<String> namesB = new ArrayList<>();
		for (Npc champion : champsB)
		{
			final FakePlayerAppearance appearance = champion.getFakePlayerAppearance();
			if ((appearance == null) || (appearance.getName() == null))
			{
				continue;
			}
			apsB.add(appearance);
			namesB.add(appearance.getName());
		}
		final int pairSize = Math.min(size, Math.min(apsA.size(), apsB.size()));
		if (pairSize <= 0)
		{
			return false;
		}
		// The underdog gets friends: when one side's loadouts still outclass
		// the other's by far even after fair picks, the weaker side brings
		// ONE mid-fight reinforcement - and the other side gets to comment.
		Npc reinforceSource = null;
		boolean reinforceSideA = false;
		{
			final double avgA = squadPower(apsA);
			final double avgB = squadPower(apsB);
			final boolean aBetter = avgA >= avgB;
			final double hi = Math.max(avgA, avgB);
			final double lo = Math.min(avgA, avgB);
			if (((hi - lo) >= 8.0) && (hi > (lo * 1.8)))
			{
				final List<Npc> weakPool = aBetter ? poolB : poolA;
				final List<Npc> weakChamps = aBetter ? champsB : champsA;
				for (Npc spare : weakPool)
				{
					if (!weakChamps.contains(spare))
					{
						reinforceSource = spare;
						reinforceSideA = !aBetter;
						break;
					}
				}
			}
		}
		// The arena: on the road just outside the gate of the nearest major
		// town (Giran preferred when in reach), on real ground, and NO gate
		// guard within reach of either fighter's spot.
		final FieldArena arena;
		try
		{
			arena = fieldArena(champsA.get(0).getX(), champsA.get(0).getY(), champsA.get(0).getZ(), champsB.get(0).getX(), champsB.get(0).getY(), champsB.get(0).getZ());
		}
		catch (Exception e)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-SCAN] arena resolve blew up: " + e);
			}
			return false;
		}
		if (arena == null)
		{
			return false;
		}
		final PhantomManager pm = PhantomManager.getInstance();
		// Grand brawls (3v3+) take longer to start: a spectacle worth
		// walking for deserves time to gather an audience.
		final long leadMillis = _fieldLeadSeconds * 1000L * ((pairSize >= 3) ? 2 : 1);
		final long maxMillis = _fightMaxSeconds * 1000L;
		final long unlockAt = now + leadMillis + maxMillis + 120000L;
		_clanFightLocks.put(Integer.valueOf(profileA.getId()), Long.valueOf(unlockAt));
		_clanFightLocks.put(Integer.valueOf(profileB.getId()), Long.valueOf(unlockAt));
		final List<Player> sideA = new ArrayList<>();
		final List<Player> sideB = new ArrayList<>();
		try
		{
			// The squads gather IN TOWN at the Gatekeeper meeting spot -
			// visibly loitering on the street - and MARCH out to the arena
			// together the moment a watcher comes to town. A side keeps
			// its own loose column so the walk reads as two companies.
			for (int i = 0; i < pairSize; i++)
			{
				final int ax = arena.guideX + Rnd.get(241) - 120;
				final int ay = arena.guideY + Rnd.get(241) - 120;
				final Player fighter = pm.spawnPartyMemberAs(apsA.get(i), new Location(ax, ay, GeoEngine.getInstance().getSpawnHeight(ax, ay, arena.guideZ)), apsA.get(i).getLevel());
				if (fighter == null)
				{
					throw new IllegalStateException("the pack refused a fighter spawn");
				}
				sideA.add(fighter);
				final int bx = arena.guideX + Rnd.get(241) - 120;
				final int by = arena.guideY + Rnd.get(241) - 120;
				final Player rival = pm.spawnPartyMemberAs(apsB.get(i), new Location(bx, by, GeoEngine.getInstance().getSpawnHeight(bx, by, arena.guideZ)), apsB.get(i).getLevel());
				if (rival == null)
				{
					throw new IllegalStateException("the pack refused a fighter spawn");
				}
				sideB.add(rival);
			}
			// Wear the colors: the crests go on visibly, the world sees WHO.
			final Clan clanA = ClanTable.getInstance().getClan(profileA.getId());
			final Clan clanB = ClanTable.getInstance().getClan(profileB.getId());
			if (clanA != null)
			{
				for (Player fighter : sideA)
				{
					BotClanManager.getInstance().attach(fighter, clanA);
				}
			}
			if (clanB != null)
			{
				for (Player fighter : sideB)
				{
					BotClanManager.getInstance().attach(fighter, clanB);
				}
			}
			// Park each champion's own wander loop; the duel drives them.
			for (Player fighter : sideA)
			{
				pm.setLivingIdle(fighter, true);
			}
			for (Player fighter : sideB)
			{
				pm.setLivingIdle(fighter, true);
			}
		}
		catch (Exception e)
		{
			for (Player fighter : sideA)
			{
				releaseFighter(fighter);
			}
			for (Player fighter : sideB)
			{
				releaseFighter(fighter);
			}
			_clanFightLocks.remove(Integer.valueOf(profileA.getId()));
			_clanFightLocks.remove(Integer.valueOf(profileB.getId()));
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: field fight staging refused (" + e + ").");
			}
			return false;
		}
		final FieldFight fight = new FieldFight(sideA, sideB, namesA, namesB, profileA.getId(), profileB.getId(), arena.townName, compassOf(arena.x - arena.guideX, arena.y - arena.guideY), arena.x, arena.y, arena.z, arena.guideX, arena.guideY, now, now + leadMillis, maxMillis);
		fight.reinforceSource = reinforceSource;
		fight.reinforceSideA = reinforceSideA;
		fight.loud = (pairSize >= 3);
		// Ring the skirmish bank: this fight's size buys the world's
		// quiet afterward - a 3v3 weighs twice a 1v1, and a hot day
		// stretches every later wait one step further.
		_skirmishHeat = Math.min(6, _skirmishHeat + pairSize);
		_nextSkirmishMillis = now + _skirmishCooldownMillis + (_skirmishHeat * _skirmishHeatStepMillis);
		if (_skirmishHeatDecayAt == 0)
		{
			_skirmishHeatDecayAt = now + 1800000L;
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [FIELD-BANK] heat " + _skirmishHeat + "; the next skirmish waits " + ((_nextSkirmishMillis - now) / 1000) + "s.");
		}
		_fieldFights.put(arcKey(profileA.getId(), profileB.getId()), fight);
		// The duel owns the stage until the last word of its aftermath.
		_fieldQuietUntil = now + leadMillis + maxMillis + 210000L;
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [FIELD-FIGHT] staged " + pairSize + "v" + pairSize + " outside " + arena.townName + " at " + arena.x + "," + arena.y + ": " + clanNameOf(profileA) + "'s " + String.join(", ", namesA) + " vs " + clanNameOf(profileB) + "'s " + String.join(", ", namesB) + " (" + fight.compassSide + " side; fists fly in ~" + _fieldLeadSeconds + "s).");
		}
		scheduleFightTick(fight, leadMillis + 1000);
		if (fight.loud)
		{
			// A GRAND brawl is a spectacle: opener, world directions, the
			// breadcrumb road, the peanut gallery - and an EXTRA mid-lead
			// address, because a long lead loses people who heard it once.
			ThreadPool.schedule(() -> fieldOpener(fight), 1500 + Rnd.get(2500));
			ThreadPool.schedule(() -> fieldWhereLine(fight, false), 7000 + Rnd.get(4000));
			ThreadPool.schedule(() -> fieldWhereLine(fight, false), Math.max(20000, leadMillis / 2) + Rnd.get(4000));
			ThreadPool.schedule(() -> scheduleHeralds(fight, arena), 3000 + Rnd.get(3000));
			ThreadPool.schedule(() -> scheduleSpectators(fight, arena), 5000 + Rnd.get(4000));
		}
		else
		{
			// A quiet duel still opens its mouth - but ONLY between the
			// two clans: one direct WORLD dare from the challenger's
			// fighter voice, one retort from the rival. No heralds, no
			// crowd, no addresses - nobody is called to watch.
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-FIGHT] a quiet duel: a direct dare between the two clans only, no heralds, no crowd.");
			}
			ThreadPool.schedule(() -> fieldDuelCallout(fight, true), 2000 + Rnd.get(2000));
		}
		return true;
	}

	/**
	 * The town crier beat: one hard location line in WORLD chat per fight,
	 * so people far away know where to teleport and which side to run.
	 * The opener carries attitude; THIS line carries the address.
	 */
	private void fieldWhereLine(FieldFight fight, boolean alreadyStarted)
	{
		if (!fight.running)
		{
			return;
		}
		final ClanProfile voiceClan = pickFieldCrowd(_profiles.get(fight.clanAId), _profiles.get(fight.clanBId));
		if (voiceClan == null)
		{
			return;
		}
		final VoiceChoice choice = chooseVoice(voiceClan);
		if (choice == null)
		{
			return;
		}
		final String moment = alreadyStarted ? "a REAL clan fight is underway RIGHT NOW" : "a REAL clan fight starts within the minute";
		final String walk = walkWord(fight);
		// The compose + address-check + retry flow runs whole on a brain
		// worker; only the delivery hops back to the game pool.
		final FieldFight battle = fight;
		try
		{
			BrainExecutor.runBrainWork(() ->
			{
				String text = brainCustom(choice.creature, "WORLD NEWS, and you spread it loudly: " + moment + " - " + sideTag(battle.clanAId, battle.namesA) + " against " + sideTag(battle.clanBId, battle.namesB) + ". The place: " + walk + " the " + battle.compassSide + " gates of " + battle.townName + ". In ONE short line, TELL THE WORLD WHERE: name the town AND the side of its gates - and make clear how far the walk is (" + walk + ") - so people know where to teleport and which direction to head out. Make it findable, like world-chat gossip that wants an audience.");
				if (text == null)
				{
					return; // brain down: the opener still pointed the way
				}
				// The address is the WHOLE point of this line: if the mind wandered
				// off into commentary, steer once - firmly - and if it wanders
				// again, the bare address rides along with whatever was said.
				if (!containsIgnoreCase(text, battle.compassSide) && !containsIgnoreCase(text, battle.townName))
				{
					final String retry = brainCustom(choice.creature, "No commentary - the ADDRESS. ONE short line that contains the words \"outside the " + battle.compassSide + " gates of " + battle.townName + "\", plus a pinch of gossip heat. The place must be findable.");
					if (retry == null)
					{
						return;
					}
					text = retry;
					if (!containsIgnoreCase(text, battle.compassSide) && !containsIgnoreCase(text, battle.townName))
					{
						text = text + " - " + walk + " the " + battle.compassSide + " gates of " + battle.townName + ", right now.";
					}
				}
				final String spoken = text;
				ThreadPool.schedule(() ->
				{
					sayToAll(choice.creature, spoken, "delivered [FIELD-WHERE]");
					_memory.add(voiceClan.getId(), battle.clanAId, spoken);
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

	/** Hand a finished fighter back to the pack: cool, then discard. */
	private void releaseFighter(Player fighter)
	{
		if (fighter == null)
		{
			return;
		}
		final PhantomManager pm = PhantomManager.getInstance();
		try
		{
			if (!pm.coolLivingPopulationPhantom(fighter))
			{
				pm.discardLivingPopulationPhantom(fighter);
			}
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-EXIT] released " + fighter.getName() + " back to the pack.");
			}
		}
		catch (Exception e)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-EXIT] could not release " + fighter.getName() + " (" + e + ").");
			}
		}
	}

	// ── Walk-in heralds: bots build a breadcrumb trail to the fight ──────

	/**
	 * Ordinary bots drift from the town toward the arena in three hops and
	 * comment LOCALLY about where the fight is - nearby players can follow
	 * the trail like a noise. Local lines are small and uncounted: they
	 * only reach the people standing around the speaker.
	 */
	private void scheduleHeralds(FieldFight fight, FieldArena arena)
	{
		if (_fieldHeralds <= 0)
		{
			return;
		}
		final PhantomManager pm = PhantomManager.getInstance();
		final List<Player> pool = new ArrayList<>();
		// The living pool drains and refills on its own schedule - when it is
		// empty, the trail went silent and nobody got guided at all. So the
		// module now MATERIALIZES its heralds: any town fake player's identity
		// (a wandering crowd of 991 exists always) becomes a real herald body.
		final List<FakePlayerAppearance> book = new ArrayList<>();
		final Set<String> seenNames = new LinkedHashSet<>();
		try
		{
			for (WorldObject object : World.getInstance().getVisibleObjects())
			{
				if (!(object instanceof Npc))
				{
					continue;
				}
				final Npc npc = (Npc) object;
				if (!npc.isFakePlayer())
				{
					continue;
				}
				final FakePlayerAppearance appearance = npc.getFakePlayerAppearance();
				if ((appearance == null) || (appearance.getName() == null) || !seenNames.add(appearance.getName()))
				{
					continue;
				}
				book.add(appearance);
				if (book.size() >= 30)
				{
					break;
				}
			}
		}
		catch (Exception e)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [HERALD] identity book failed: " + e);
			}
		}
		Collections.shuffle(book);
		// Stationary breadcrumbs: each herald holds ONE post on the road
		// from the Gatekeeper out toward the arena, through the whole lead
		// and the fight - a trail a person can WALK, post after post, each
		// one confirming the direction as they pass. No hopping, no drift,
		// nothing to miss: bodies standing in the open, shouting the way.
		final long leadMillis = Math.max(20000, _fieldLeadSeconds * 1000L);
		final double[] posts = { 0.0, 0.25, 0.55, 0.75, 0.4 };
		// Surface-safe spots, resolved ONCE: spawn-height resolver, no water,
		// and a walkback whenever the straight trail from the Gatekeeper is
		// blocked - posts can no longer land in tunnels, lakes or layers.
		final Collection<WaterZone> waters = ZoneManager.getInstance().getAllZones(WaterZone.class);
		final Location[] postSpots = new Location[posts.length];
		for (int i = 0; i < posts.length; i++)
		{
			postSpots[i] = postSpot(arena, waters, posts[i]);
		}
		for (FakePlayerAppearance appearance : book)
		{
			if (pool.size() >= _fieldHeralds)
			{
				break;
			}
			try
			{
				final Location spot = postSpots[pool.size() % postSpots.length];
				final Player herald = pm.spawnPartyMemberAs(appearance, spot, appearance.getLevel());
				if (herald == null)
				{
					continue;
				}
				pm.setLivingIdle(herald, true);
				glowUp(herald);
				pool.add(herald);
				fight.heralds.add(herald);
			}
			catch (Exception e)
			{
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [HERALD] a herald spawn failed: " + e);
				}
			}
		}
		// Any shortfall is topped up from the living pool when it has bodies
		// - those get walked to their post.
		for (Player phantom : pm.livingPopulationPhantoms().values())
		{
			if (pool.size() >= _fieldHeralds)
			{
				break;
			}
			if ((phantom == null) || !phantom.isOnline() || phantom.isAlikeDead())
			{
				continue;
			}
			try
			{
				final Location spot = postSpots[pool.size() % postSpots.length];
				pm.livingTeleport(phantom, spot.getX(), spot.getY(), spot.getZ());
				glowUp(phantom);
			}
			catch (Exception e)
			{
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [HERALD] a post walk failed: " + e);
				}
			}
			pool.add(phantom);
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [HERALD] " + pool.size() + " post(s) of " + _fieldHeralds + " wanted, planted on the road from " + arena.townName + "'s gatekeeper toward the arena.");
		}
		if (pool.isEmpty())
		{
			return;
		}
		int heraldIndex = 0;
		for (Player herald : pool)
		{
			// Post zero stands AT the Gatekeeper (the teleport landing);
			// the rest hold waypoints at 25%, 55%, 75%, 40% of the way out.
			final int postIndex = heraldIndex++;
			final boolean atGate = (postIndex == 0);
			final Location spot = postSpots[postIndex % postSpots.length];
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [HERALD] " + herald.getName() + " holds " + spot.getX() + "," + spot.getY() + "," + spot.getZ() + (atGate ? " (the Gatekeeper spot)" : (" (" + Math.round(posts[postIndex % posts.length] * 100) + "% of the road out).")));
			}
			// Words: every ~18s through the whole lead, so a player walking
			// the trail hears direction after direction as they pass each
			// post. The pass also watches WHO is around: anyone drifting
			// AWAY from the arena gets a wrong-way call-out. Then one
			// start-holler when the fists fly.
			final Map<String, Double> approach = new LinkedHashMap<>();
			long delay = 5000;
			while (delay <= (leadMillis + 2000))
			{
				ThreadPool.schedule(() ->
				{
					if (fight.running)
					{
						heraldSpeechPass(fight, herald, atGate, approach);
					}
				}, delay + Rnd.get(4000));
				delay += 18000;
			}
			ThreadPool.schedule(() ->
			{
				if (fight.running)
				{
					heraldLocalWord(fight, herald, atGate, true, false);
				}
			}, leadMillis + 1500 + Rnd.get(2500));
		}
	}

	/**
	 * A speaking beat from one post: only speaks when real ears are near,
	 * and if somebody within earshot has walked FURTHER from the arena since
	 * the last beat, the post switches to a wrong-way call-out.
	 */
	private void heraldSpeechPass(FieldFight fight, Player herald, boolean atGate, Map<String, Double> approach)
	{
		if (!herald.isOnline() || herald.isAlikeDead())
		{
			return;
		}
		// Real ears only: fighters, heralds, pool phantoms don't count.
		boolean anyone = false;
		boolean wrongWay = false;
		for (Player online : allPlayers())
		{
			if (fight.sideA.contains(online) || fight.sideB.contains(online))
			{
				continue;
			}
			if (fight.heralds.contains(online) || PhantomManager.getInstance().livingPopulationPhantoms().containsValue(online))
			{
				continue;
			}
			final long dx = online.getX() - herald.getX();
			final long dy = online.getY() - herald.getY();
			if (((dx * dx) + (dy * dy)) > 1562500L) // 1250: out of earshot
			{
				continue;
			}
			anyone = true;
			final double ex = online.getX() - fight.arenaX;
			final double ey = online.getY() - fight.arenaY;
			final double distSq = (ex * ex) + (ey * ey);
			final Double was = approach.get(online.getName());
			if ((was != null) && (distSq > (was.doubleValue() + 144000.0))) // 380+ further off
			{
				wrongWay = true;
			}
			approach.put(online.getName(), Double.valueOf(distSq));
		}
		if (!anyone)
		{
			return; // no shouting into empty air
		}
		heraldLocalWord(fight, herald, atGate, false, wrongWay);
	}

	/** One local-breath comment from a stationary trail post, held to the
	 *  DIRECTION discipline: no clan gossip, no old stories - the compass
	 *  side and the town MUST be in the line, checked and re-steered like
	 *  the world-chat address. A leading wrong-way call-out fires when a
	 *  nearby player is drifting away from the arena. */
	private void heraldLocalWord(FieldFight fight, Player herald, boolean atGate, boolean started, boolean wrongWay)
	{
		if (!herald.isOnline() || herald.isAlikeDead())
		{
			return;
		}
		final String role;
		final String ask;
		final String walk = walkWord(fight);
		if (started)
		{
			role = "The clan fight just STARTED " + walk + " the " + fight.compassSide + " gates of " + fight.townName + ", and you are really loud about it right now.";
			ask = "In ONE short line, call to whoever is near: it has begun - " + walk + " the " + fight.compassSide + " side out of " + fight.townName + " - come quick. No shouting.";
		}
		else if (wrongWay)
		{
			role = "Someone standing near you just walked FURTHER from the fight - they took the wrong turn, or reversed on the road.";
			ask = "In ONE short line, CALL THEM BACK: no no, turn around, the fight lies " + walk + " on the " + fight.compassSide + " side of " + fight.townName + " - back down the road, toward where you came from. No shouting.";
		}
		else if (atGate)
		{
			role = "You are standing beside the town's Gatekeeper, where people teleport in - they practically bump into you when they arrive.";
			ask = "In ONE short line, tell whoever is nearest WHERE the trouble is: name the " + fight.compassSide + " side of " + fight.townName + ", that it is " + walk + ", and that they can follow the road straight out that way to watch. You are right here, at the Gatekeeper - direct them onward.";
		}
		else
		{
			role = "You stand on the road out of " + fight.townName + ", holding a milestone toward the fight at the " + fight.compassSide + " side - people moving that way pass right through your spot.";
			ask = "In ONE short line, tell whoever passes WHERE the trouble is: name the " + fight.compassSide + " side of " + fight.townName + " - it is still " + walk + " further - and that they should keep walking your way along this same road.";
		}
		final String hardRule = " STRICT RULES: directions only - NO clan gossip, NO old memories, NO loot talk, NO romance. The words \"" + fight.compassSide + " side of " + fight.townName + "\" MUST appear in the line.";
		final String compose = role + " The rumble involves " + sideTag(fight.clanAId, fight.namesA) + " against " + sideTag(fight.clanBId, fight.namesB) + ". " + ask + hardRule + " No shouting.";
		// The compose + address-check + retry flow runs whole on a brain
		// worker; the local delivery hops back to the game pool.
		final String fallback = walk + " the " + fight.compassSide + " side of " + fight.townName + ".";
		final FieldFight battle = fight;
		try
		{
			BrainExecutor.runBrainWork(() ->
			{
				String text = brainCustom(herald, compose);
				if (text == null)
				{
					return;
				}
				// The address is the whole point: a wandering mind gets one firm
				// retry, then the bare direction rides along with what it said.
				if (!containsIgnoreCase(text, battle.compassSide) && !containsIgnoreCase(text, battle.townName))
				{
					final String retry = brainCustom(herald, "No chatter. ONE short line that contains the words \"" + battle.compassSide + " side of " + battle.townName + "\" - that is all the line is about. No shouting.");
					if (retry == null)
					{
						return;
					}
					text = retry;
					if (!containsIgnoreCase(text, battle.compassSide) && !containsIgnoreCase(text, battle.townName))
					{
						text = text + " - " + fallback;
					}
				}
				final String spoken = text;
				ThreadPool.schedule(() -> sayLocal(herald, spoken, "delivered " + (atGate ? "[HERALD-ANCHOR]" : "[HERALD]")), 0);
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

	/** Speak in LOCAL (general) chat: heard only by players standing near
	 *  the speaker - the way a passing bot's mutter reaches a street. */
	private void sayLocal(Creature speaker, String text, String label)
	{
		if ((text == null) || (speaker == null))
		{
			return;
		}
		final CreatureSay packet = new CreatureSay(speaker, ChatType.GENERAL, text);
		int sent = 0;
		for (Player online : allPlayers())
		{
			final long dx = online.getX() - speaker.getX();
			final long dy = online.getY() - speaker.getY();
			if (((dx * dx) + (dy * dy)) < 1562500L) // 1250 range, long math
			{
				online.sendPacket(packet);
				sent++;
			}
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities " + label + " by " + speaker.getName() + " (LOCAL, heard by " + sent + "): " + text);
		}
		_lastLineMillis = System.currentTimeMillis();
	}

	/** How the walk to a fight reads in words: arenas beyond the wall line
	 *  vary from a doorstep brawl to a genuine hike, and the words should
	 *  say which one it is. */
	private static String walkWord(FieldFight fight)
	{
		final long dx = (long) fight.arenaX - fight.guideX;
		final long dy = (long) fight.arenaY - fight.guideY;
		final long dist = (long) Math.sqrt((dx * dx) + (dy * dy));
		if (dist < 900)
		{
			return "just outside";
		}
		if (dist < 2000)
		{
			return "a short walk out";
		}
		return "a LONG walk out";
	}

	/** Which side of town a world point sits on, in a compass word. */
	private static String compassOf(double dx, double dy)
	{
		// The world's map: +X is east, +Y is SOUTH (Lineage's Y axis runs
		// opposite the compass - north is the NEGATIVE Y direction).
		final double bearing = (Math.toDegrees(Math.atan2(dx, -dy)) + 360) % 360;
		if (bearing < 22.5)
		{
			return "north";
		}
		if (bearing < 67.5)
		{
			return "north-east";
		}
		if (bearing < 112.5)
		{
			return "east";
		}
		if (bearing < 157.5)
		{
			return "south-east";
		}
		if (bearing < 202.5)
		{
			return "south";
		}
		if (bearing < 247.5)
		{
			return "south-west";
		}
		if (bearing < 292.5)
		{
			return "west";
		}
		if (bearing < 337.5)
		{
			return "north-west";
		}
		return "north";
	}

	/** The duel's heartbeat: both sides press, then the world reads. */
	private void scheduleFightTick(FieldFight fight, long initialDelay)
	{
		ThreadPool.schedule(() ->
		{
			if (fight.running && !fightTickBody(fight))
			{
				scheduleFightTick(fight, 1000); // still on; check again shortly
			}
		}, initialDelay);
	}

	/** One duel heartbeat. Returns true when the duel has ENDED. */
	private boolean fightTickBody(FieldFight fight)
	{
		try
		{
			// Vanished fighters scatter the duel - the world retells it later.
			final List<Player> aliveA = new ArrayList<>();
			final List<Player> aliveB = new ArrayList<>();
			for (Player fighter : fight.sideA)
			{
				if (!fighter.isOnline())
				{
					endFight(fight, null, "a fighter vanished from the world");
					return true;
				}
				if (!fighter.isAlikeDead())
				{
					aliveA.add(fighter);
				}
			}
			for (Player fighter : fight.sideB)
			{
				if (!fighter.isOnline())
				{
					endFight(fight, null, "a fighter vanished from the world");
					return true;
				}
				if (!fighter.isAlikeDead())
				{
					aliveB.add(fighter);
				}
			}
			if (aliveA.isEmpty())
			{
				// Blood decides; the world hears immediately.
				endFight(fight, fight.sideB, null);
				return true;
			}
			if (aliveB.isEmpty())
			{
				endFight(fight, fight.sideA, null);
				return true;
			}
			// Pre-blow patience: the squads wait in town, and the whole
			// theater of marching out must land before steel falls.
			final long now2 = System.currentTimeMillis();
			if (!fight.begun)
			{
				// 1) March trigger: a real player came to the host town -
				// or the lead ran out and the world waits alone no more.
				if (!fight.marched)
				{
					if (playerNearTown(fight) || (now2 >= fight.fightStartMillis))
					{
						fight.marched = true;
						fight.marchedAtMillis = now2;
						final PhantomManager pm = PhantomManager.getInstance();
						// The watcher may already stand AT the field: then no
						// hike theater - the squads blink to their marks at
						// once. Merely being in town or on the road keeps the
						// walking column, which is the theater worth seeing.
						final boolean rush = playerAtFence(fight);
						final String why;
						if (rush)
						{
							why = " - the watcher reached the field first; the squads arrive at once.";
							rushArrival(fight, aliveA, pm);
							rushArrival(fight, aliveB, pm);
						}
						else
						{
							why = (now2 < fight.fightStartMillis) ? " - a watcher has come to town." : " - the lead ran out; no watcher came.";
							for (Player fighter : aliveA)
							{
								try
								{
									pm.setLivingIdle(fighter, false);
									final int mx = fight.arenaX + ((fighter.getObjectId() % 301) - 150);
									final int my = fight.arenaY + ((fighter.getObjectId() % 271) - 135);
									pm.livingMoveTo(fighter, mx, my, GeoEngine.getInstance().getSpawnHeight(mx, my, fight.arenaZ));
								}
								catch (Exception e)
								{
									if (_infoHook != null)
									{
										_infoHook.accept("clan-personalities: [FIELD-MARCH] a step failed for " + fighter.getName() + ": " + e);
									}
								}
							}
							for (Player fighter : aliveB)
							{
								try
								{
									pm.setLivingIdle(fighter, false);
									final int mx = fight.arenaX + ((fighter.getObjectId() % 301) - 150);
									final int my = fight.arenaY + ((fighter.getObjectId() % 271) - 135);
									pm.livingMoveTo(fighter, mx, my, GeoEngine.getInstance().getSpawnHeight(mx, my, fight.arenaZ));
								}
								catch (Exception e)
								{
									if (_infoHook != null)
									{
										_infoHook.accept("clan-personalities: [FIELD-MARCH] a step failed for " + fighter.getName() + ": " + e);
									}
								}
							}
						}
						if (_infoHook != null)
						{
							_infoHook.accept("clan-personalities: [FIELD-MARCH] the champions " + (rush ? "arrive in a rush of steps." : "march out to the field.") + why);
						}
					}
					else
					{
						return false; // the squads hold their street corner
					}
				}
				// 2) March in progress: blows wait until everyone reaches
				// the field - with a soft cap, then stragglers are ferried.
				boolean arrived = true;
				for (Player fighter : aliveA)
				{
					final long ddx = fighter.getX() - fight.arenaX;
					final long ddy = fighter.getY() - fight.arenaY;
					if (((ddx * ddx) + (ddy * ddy)) > 2000000L) // beyond ~1414
					{
						arrived = false;
						break;
					}
				}
				if (arrived)
				{
					for (Player fighter : aliveB)
					{
						final long ddx = fighter.getX() - fight.arenaX;
						final long ddy = fighter.getY() - fight.arenaY;
						if (((ddx * ddx) + (ddy * ddy)) > 2000000L)
						{
							arrived = false;
							break;
						}
					}
				}
				if (!arrived)
				{
					if ((now2 - fight.marchedAtMillis) < 75000)
					{
						return false; // boots on the road
					}
					// March deadline hit: the stragglers ride the rest in.
					ferryStragglers(fight, aliveA);
					ferryStragglers(fight, aliveB);
				}
				// The field is not silent: while the world gathers its
				// nerve, the champions heckle across the gap, squad by
				// squad, in words the near ground can hear.
				if ((now2 - fight.lastTrashMillis) >= 11000)
				{
					fight.lastTrashMillis = now2;
					final List<Player> mouth = fight.trashSide ? aliveA : aliveB;
					fight.trashSide = !fight.trashSide;
					if (!mouth.isEmpty())
					{
						final Player speaker = mouth.get(Rnd.get(mouth.size()));
						ThreadPool.schedule(() ->
						{
							if (fight.running)
							{
								fieldTrashLine(fight, speaker);
							}
						}, 300 + Rnd.get(1200));
					}
				}
				// 3) Watcher logic: AT the fence, the waiting ends within
				// breaths - lead or no lead - because the audience has
				// arrived and the duel owes them steel. Merely on the road,
				// they are still coming, and the first blow holds.
				final boolean watcherNear = playerNearFight(fight);
				if (watcherNear)
				{
					if (playerAtFence(fight))
					{
						fight.gateMillis = now2 + 5000;
						fight.closeCalled = true;
					}
					else if (now2 < (fight.fightStartMillis + _playerGraceMaxMillis))
					{
						fight.gateMillis = now2 + 6000;
						if (!fight.stallAnnounced)
						{
							fight.stallAnnounced = true;
							if (_infoHook != null)
							{
								_infoHook.accept("clan-personalities: [FIELD-STAGE] first blow held - a watcher is close enough to join the circle.");
							}
						}
						return false;
					}
				}
				final long floor = fight.closeCalled ? fight.gateMillis : Math.max(fight.fightStartMillis, fight.gateMillis);
				if (now2 < floor)
				{
					return false; // lead-in still breathing, or the short fuse
				}
				fight.begun = true;
				fight.begunAtMillis = now2;
				if (fight.reinforceSource != null)
				{
					// The weaker side's promised friend rides in mid-duel:
					// a short wait after the first blow, then drama.
					ThreadPool.schedule(() ->
					{
						if (fight.running)
						{
							bringReinforcement(fight);
						}
					}, 18000 + Rnd.get(6000));
				}
				// A watcher may have missed the first address while walking;
				// one more world-chat beat rides out when steel actually
				// flies - loud fights only: quiet duels speak no world word.
				if (fight.loud)
				{
					ThreadPool.schedule(() ->
					{
						if (fight.running)
						{
							fieldWhereLine(fight, true);
						}
					}, 1500 + Rnd.get(2500));
				}
			}
			// The peanut gallery does not stay quiet: whoops and boos ride
			// the local air, a couple of beats apart through the duel.
			if (!fight.spectators.isEmpty() && (fight.spectatorBeats < 6) && ((now2 - fight.lastSpectatorMillis) >= 22000))
			{
				fight.lastSpectatorMillis = now2;
				fight.spectatorBeats++;
				final Player fan = fight.spectators.get(Rnd.get(fight.spectators.size()));
				ThreadPool.schedule(() ->
				{
					if (fight.running && fan.isOnline() && !fan.isAlikeDead())
					{
						crowdLine(fight, fan);
					}
				}, 200 + Rnd.get(1500));
			}
			// Blood ledger: has any blade landed yet? A duel that never
			// engages is a draw nobody saw - so it gets help.
			boolean bled = false;
			for (Player fighter : aliveA)
			{
				if (fighter.getCurrentHp() < fighter.getMaxHp())
				{
					bled = true;
					break;
				}
			}
			if (!bled)
			{
				for (Player fighter : aliveB)
				{
					if (fighter.getCurrentHp() < fighter.getMaxHp())
					{
						bled = true;
						break;
					}
				}
			}
			if (bled)
			{
				if (fight.firstBloodMillis == 0)
				{
					fight.firstBloodMillis = now2;
				}
			}
			else if (now2 > (fight.begunAtMillis + 6000))
			{
				// The squares stand apart past melee reach: bring the first
				// pair together by hand so the duel cannot stall dead.
				final Player a0 = aliveA.get(0);
				final Player b0 = aliveB.get(0);
				if ((a0 != null) && (b0 != null))
				{
					final long dx = a0.getX() - b0.getX();
					final long dy = a0.getY() - b0.getY();
					final long distSq = (dx * dx) + (dy * dy);
					if (distSq > 8100) // a spear's reach and more
					{
						PhantomManager.getInstance().livingMoveTo(a0, b0.getX(), b0.getY(), b0.getZ());
						if (!fight.engageLogged)
						{
							fight.engageLogged = true;
							if (_infoHook != null)
							{
								_infoHook.accept("clan-personalities: [ENGAGE] no blood " + ((now2 - fight.begunAtMillis) / 1000) + "s into the duel - the pair stands " + Math.round(Math.sqrt(distSq)) + " apart; walking them together.");
							}
						}
						if ((now2 - fight.begunAtMillis) > 25000)
						{
							// Still no steel: the rival is set beside its face.
							final int nx = b0.getX();
							final int ny = b0.getY() + 40;
							PhantomManager.getInstance().livingTeleport(a0, nx, ny, b0.getZ());
						}
					}
					else if ((now2 - fight.begunAtMillis) > 30000)
					{
						// Faces close, blades sheathed: one raw swing is forced.
						a0.setTarget(b0);
						a0.doAttack(b0);
					}
				}
			}
			// Every standing sword takes a face across the gap, and the
			// pack's playstyle engine casts real skills on top of steel.
			pairDrive(fight, aliveA.get(0), aliveB.get(0));
			pairDrive(fight, aliveB.get(0), aliveA.get(0));
			if ((aliveA.size() > 1) || (aliveB.size() > 1))
			{
				for (int i = 1; i < aliveA.size(); i++)
				{
					pairDrive(fight, aliveA.get(i), aliveB.get(i % aliveB.size()));
					pairDrive(fight, aliveB.get(i % aliveB.size()), aliveA.get(i));
				}
				for (int i = aliveA.size(); i < aliveB.size(); i++)
				{
					pairDrive(fight, aliveB.get(i), aliveA.get(i % aliveA.size()));
					pairDrive(fight, aliveA.get(i % aliveA.size()), aliveB.get(i));
				}
			}
			final long now = System.currentTimeMillis();
			if (now >= (fight.fightStartMillis + fight.maxMillis))
			{
				// The clock decides: whoever bled less takes the field
				// story; near-even goes down as a shared bruising.
				double hpA = 0;
				double maxA = 0;
				for (Player fighter : aliveA)
				{
					hpA += fighter.getCurrentHp();
					maxA += Math.max(1.0, fighter.getMaxHp());
				}
				double hpB = 0;
				double maxB = 0;
				for (Player fighter : aliveB)
				{
					hpB += fighter.getCurrentHp();
					maxB += Math.max(1.0, fighter.getMaxHp());
				}
				final double aRatio = (maxA > 0) ? (hpA / maxA) : 0;
				final double bRatio = (maxB > 0) ? (hpB / maxB) : 0;
				// A penalty threshold: 85% of max health decides or draws.
				if (Math.abs(aRatio - bRatio) < 0.15)
				{
					endFight(fight, null, null); // too even: a draw
				}
				else
				{
					endFight(fight, aRatio > bRatio ? fight.sideA : fight.sideB, null);
				}
				return true;
			}
			// The crowd: across the duel's length, up to THREE spectator
			// clans may say the moment (the rest of the stage is frozen -
			// watching IS what the world is doing right now).
			final long elapsed = now - fight.fightStartMillis;
			if ((now - fight.lastProbeMillis) >= 15000)
			{
				// Blow report: if blades truly land, these numbers bleed.
				fight.lastProbeMillis = now;
				double hpA = 0;
				double maxA = 0;
				for (Player fighter : aliveA)
				{
					hpA += fighter.getCurrentHp();
					maxA += Math.max(1.0, fighter.getMaxHp());
				}
				double hpB = 0;
				double maxB = 0;
				for (Player fighter : aliveB)
				{
					hpB += fighter.getCurrentHp();
					maxB += Math.max(1.0, fighter.getMaxHp());
				}
				long gapSq = 0;
				if (!aliveA.isEmpty() && !aliveB.isEmpty())
				{
					final long gdx = aliveA.get(0).getX() - aliveB.get(0).getX();
					final long gdy = aliveA.get(0).getY() - aliveB.get(0).getY();
					gapSq = (gdx * gdx) + (gdy * gdy);
				}
				if (_infoHook != null)
				{
					final ClanProfile pa = _profiles.get(fight.clanAId);
					final ClanProfile pb = _profiles.get(fight.clanBId);
					_infoHook.accept("clan-personalities: [FIELD-BLOWS] " + ((pa == null) ? "sideA" : clanNameOf(pa)) + " at " + ((maxA > 0) ? (int) ((hpA / maxA) * 100) : 0) + "% hp vs " + ((pb == null) ? "sideB" : clanNameOf(pb)) + " at " + ((maxB > 0) ? (int) ((hpB / maxB) * 100) : 0) + "% hp, gap " + Math.round(Math.sqrt(gapSq)) + ".");
				}
			}
			// Third clans kibitz only over GRAND brawls; a quiet duel stays
			// a matter between the two clans on the field.
			while (fight.loud && (fight.crowdBeats < 3) && (elapsed > (22000 + (fight.crowdBeats * 28000L))))
			{
				final int beat = fight.crowdBeats;
				fight.crowdBeats++;
				final int chance = (beat == 0) ? 60 : ((beat == 1) ? 50 : 40);
				if (Rnd.get(100) < chance)
				{
					final ClanProfile crowd = pickFieldCrowd(_profiles.get(fight.clanAId), _profiles.get(fight.clanBId));
					if (crowd != null)
					{
						ThreadPool.schedule(() -> fieldCrowdLine(crowd, fight), 1500 + Rnd.get(4000) + spacingHold());
					}
				}
			}
		}
		catch (Exception e)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: field fight tick error: " + e);
			}
			endFight(fight, null, null);
			return true;
		}
		return false;
	}

	/** True when a REAL human stands near the arena fence (phantoms of the
	 *  world's cameras and the duel's own champions don't count). */
	/** True when a REAL human stands within the host town (loosely: within
	 *  2800 of the Gatekeeper landing) - the queue that starts the march. */
	private boolean playerNearTown(FieldFight fight)
	{
		final long radiusSq = 2800L * 2800L;
		for (Player online : allPlayers())
		{
			if (PhantomManager.getInstance().livingPopulationPhantoms().containsValue(online))
			{
				continue;
			}
			final long dx = online.getX() - fight.guideX;
			final long dy = online.getY() - fight.guideY;
			if (((dx * dx) + (dy * dy)) <= radiusSq)
			{
				return true;
			}
		}
		return false;
	}

	/** The field-first arrival: the squads blink straight to their marks
	 *  (still parked - the blows decide when steel leaves the sheath). */
	private void rushArrival(FieldFight fight, List<Player> alive, PhantomManager pm)
	{
		for (Player fighter : alive)
		{
			try
			{
				final int fx = fight.arenaX + Rnd.get(401) - 200;
				final int fy = fight.arenaY + Rnd.get(401) - 200;
				pm.livingTeleport(fighter, fx, fy, GeoEngine.getInstance().getSpawnHeight(fx, fy, fight.arenaZ));
			}
			catch (Exception e)
			{
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [FIELD-MARCH] the rush failed for " + fighter.getName() + ": " + e);
				}
			}
		}
	}

	/** Pre-fight heckling: a champion taunts across the gap, LOCALLY - pure
	 *  attitude, the heralds carry the address. The brain stays required. */
	private void fieldTrashLine(FieldFight fight, Player speaker)
	{
		if (!speaker.isOnline() || speaker.isAlikeDead())
		{
			return;
		}
		brainNear(speaker, "You stand face to face with the rival clan, seconds before the real clan fight at the " + fight.compassSide + " side of " + fight.townName + " begins. In ONE short line, taunt your rivals or egg the onlookers toward the start. This line is pure attitude - no directions needed. No shouting.", "delivered [FIELD-TRASH]");
	}

	/** The underdog's promised friend rides in, one body, once: spawned at
	 *  the trail's edge they run the last stretch into the melee - the
	 *  stronger-geared side gets to comment on it, and the arriving friend
	 *  joins the squad lists so every duel mechanic sees them. */
	private void bringReinforcement(FieldFight fight)
	{
		final Npc source = fight.reinforceSource;
		if (source == null)
		{
			return;
		}
		fight.reinforceSource = null;
		try
		{
			final FakePlayerAppearance appearance = source.getFakePlayerAppearance();
			if ((appearance == null) || (appearance.getName() == null))
			{
				return;
			}
			final PhantomManager pm = PhantomManager.getInstance();
			// They arrive from town: at the trail's edge, then a short run
			// into the circle the duel has drawn.
			final double back = 0.80;
			final int rx = fight.guideX + (int) Math.round((fight.arenaX - fight.guideX) * back) + Rnd.get(241) - 120;
			final int ry = fight.guideY + (int) Math.round((fight.arenaY - fight.guideY) * back) + Rnd.get(241) - 120;
			final Player ally = pm.spawnPartyMemberAs(appearance, new Location(rx, ry, GeoEngine.getInstance().getSpawnHeight(rx, ry, fight.arenaZ)), appearance.getLevel());
			if (ally == null)
			{
				return;
			}
			final Clan clan = ClanTable.getInstance().getClan(fight.reinforceSideA ? fight.clanAId : fight.clanBId);
			if (clan != null)
			{
				BotClanManager.getInstance().attach(ally, clan);
			}
			pm.setLivingIdle(ally, false);
			pm.livingMoveTo(ally, fight.arenaX + Rnd.get(401) - 200, fight.arenaY + Rnd.get(401) - 200, GeoEngine.getInstance().getSpawnHeight(fight.arenaX, fight.arenaY, fight.arenaZ));
			if (fight.reinforceSideA)
			{
				fight.sideA.add(ally);
			}
			else
			{
				fight.sideB.add(ally);
			}
			// Both sides get a word: the rival side reacts, the calling
			// side gloats - LOCALLY, for whoever stands at the fence.
			final List<Player> rivals = fight.reinforceSideA ? fight.sideB : fight.sideA;
			final List<Player> friends = fight.reinforceSideA ? fight.sideA : fight.sideB;
			if (!rivals.isEmpty())
			{
				final Player speaker = rivals.get(Rnd.get(rivals.size()));
				if (speaker.isOnline() && !speaker.isAlikeDead())
				{
					brainNear(speaker, "A fighter of the rival clan just called in " + appearance.getName() + " - reinforcements are breaking onto your field mid-duel. In ONE short line, react: outrage, mockery, or a shout to your own side to hold steady.", "delivered [FIELD-REINFORCE]");
				}
			}
			if (!friends.isEmpty())
			{
				final Player speaker = friends.get(Rnd.get(friends.size()));
				if (speaker.isOnline() && !speaker.isAlikeDead())
				{
					brainNear(speaker, "You called in " + appearance.getName() + " and they are running to join your side of the duel right now. In ONE short line, gloat about it: the field just turned, say so.", "delivered [FIELD-REINFORCE]");
				}
			}
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-REINFORCE] the " + (fight.reinforceSideA ? "A" : "B") + " side called in " + appearance.getName() + " mid-duel.");
			}
		}
		catch (Exception e)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-REINFORCE] the call failed: " + e);
			}
		}
	}

	/** An identity book: distinct fake players walking the world right now,
	 *  shuffled - the pool the module materializes faces from. */
	private List<FakePlayerAppearance> identityBook(int limit)
	{
		final List<FakePlayerAppearance> book = new ArrayList<>();
		final Set<String> seenNames = new LinkedHashSet<>();
		try
		{
			for (WorldObject object : World.getInstance().getVisibleObjects())
			{
				if (!(object instanceof Npc))
				{
					continue;
				}
				final Npc npc = (Npc) object;
				if (!npc.isFakePlayer())
				{
					continue;
				}
				final FakePlayerAppearance appearance = npc.getFakePlayerAppearance();
				if ((appearance == null) || (appearance.getName() == null) || !seenNames.add(appearance.getName()))
				{
					continue;
				}
				book.add(appearance);
				if (book.size() >= limit)
				{
					break;
				}
			}
		}
		catch (Exception e)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [SPECTATORS] identity book failed: " + e);
			}
		}
		Collections.shuffle(book);
		return book;
	}

	/** A peanut gallery in the ring around the arena: four to six fake
	 *  players standing a crowd's distance out, watching the duel. Dry
	 *  ground only, fighters and heralds excluded. */
	private void scheduleSpectators(FieldFight fight, FieldArena arena)
	{
		if (!fight.running)
		{
			return;
		}
		final PhantomManager pm = PhantomManager.getInstance();
		final Set<String> taken = new LinkedHashSet<>();
		for (String name : fight.namesA)
		{
			taken.add(name);
		}
		for (String name : fight.namesB)
		{
			taken.add(name);
		}
		for (Player herald : fight.heralds)
		{
			if (herald.isOnline())
			{
				taken.add(herald.getName());
			}
		}
		final int wanted = 4 + Rnd.get(3); // 4..6 fans
		final Collection<WaterZone> waters = ZoneManager.getInstance().getAllZones(WaterZone.class);
		for (FakePlayerAppearance appearance : identityBook(60))
		{
			if (fight.spectators.size() >= wanted)
			{
				break;
			}
			if (taken.contains(appearance.getName()))
			{
				continue;
			}
			try
			{
				final int degrees = Rnd.get(360);
				final int radius = 550 + Rnd.get(251);
				final int sx = fight.arenaX + (int) Math.round(radius * Math.cos(Math.toRadians(degrees)));
				final int sy = fight.arenaY + (int) Math.round(radius * Math.sin(Math.toRadians(degrees)));
				final int sz = GeoEngine.getInstance().getSpawnHeight(sx, sy, fight.arenaZ);
				if (waterAt(waters, sx, sy, sz))
				{
					continue;
				}
				final Player fan = pm.spawnPartyMemberAs(appearance, new Location(sx, sy, sz), appearance.getLevel());
				if (fan == null)
				{
					continue;
				}
				pm.setLivingIdle(fan, true);
				fight.spectators.add(fan);
			}
			catch (Exception e)
			{
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [SPECTATORS] a fan failed to appear: " + e);
				}
			}
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [SPECTATORS] " + fight.spectators.size() + " fans ring the field at " + fight.arenaX + "," + fight.arenaY + ".");
		}
	}

	/** A voice from the ring: an ooh, a cheer, a jab - LOCALLY. */
	private void crowdLine(FieldFight fight, Player fan)
	{
		if (!fan.isOnline() || fan.isAlikeDead())
		{
			return;
		}
		brainNear(fan, "You are in the crowd ringing a REAL clan duel at the " + fight.compassSide + " side of " + fight.townName + ". In ONE short line, react to the fight as it stands: an ooh, a cheer for one side, a jab at the side losing. No directions needed.", "delivered [CROWD]");
	}

	/** One sword keeps its face; the pack's playstyle engine adds casts. */
	private void pairDrive(FieldFight fight, Player attacker, Creature defender)
	{
		PhantomCombatActions.maintainAttack(attacker, defender);
		driveSkills(fight, attacker, defender);
	}

	/**
	 * Cast driving: the pack's own engine picks a real skill for the
	 * fighter against the defender, executes it through the combat
	 * actions, and confirms the cast in the fighter's play memory so
	 * rotations and cooldowns behave like a player's.
	 */
	private void driveSkills(FieldFight fight, Player attacker, Creature defender)
	{
		try
		{
			if (!PhantomPlaystyleEngine.hasBaseline(attacker))
			{
				if (fight.notedSkillNotes.add(attacker.getName()) && (_infoHook != null))
				{
					_infoHook.accept("clan-personalities: [SKILLS] " + attacker.getName() + " has no playstyle baseline on file - steel only.");
				}
				return; // no playstyle data on file: pure steel, then
			}
			PhantomPlaystyleEngine.PlayState state = fight.states.get(attacker.getName());
			if (state == null)
			{
				state = new PhantomPlaystyleEngine.PlayState();
				fight.states.put(attacker.getName(), state);
			}
			PhantomPlaystyleEngine.CastAction cast = PhantomPlaystyleEngine.pick(attacker, defender, state, true, false, 1, "field duel");
			if (cast == null)
			{
				// The main draw stays dry; the fallback pool is the pack's
				// own second chance at a live cast.
				cast = PhantomPlaystyleEngine.pickFallback(attacker, defender, state, 1);
			}
			if (cast == null)
			{
				if (fight.notedSkillNotes.add("nopick:" + attacker.getName()) && (_infoHook != null))
				{
					_infoHook.accept("clan-personalities: [SKILLS] no pick and no fallback for " + attacker.getName() + " - steel only.");
				}
				return;
			}
			PhantomCombatActions.execute(attacker, defender, state, cast, true);
			PhantomPlaystyleEngine.confirmCast(state, cast);
		}
		catch (Exception e)
		{
			// A broken cast loop must not eat the duel: forget the fighter's
			// play memory and let plain steel carry the round.
			fight.states.remove(attacker.getName());
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [SKILLS] a cast fell apart for " + attacker.getName() + " (" + e + "); their play memory resets.");
			}
		}
	}

	/** Anyone of a squad still far from the field gets ferried in - once. */
	private void ferryStragglers(FieldFight fight, List<Player> alive)
	{
		for (Player fighter : alive)
		{
			final long ddx = fighter.getX() - fight.arenaX;
			final long ddy = fighter.getY() - fight.arenaY;
			if (((ddx * ddx) + (ddy * ddy)) <= 2000000L) // within ~1414
			{
				continue;
			}
			if (fight.ferried.add(fighter.getName()))
			{
				final int fx = fight.arenaX + Rnd.get(401) - 200;
				final int fy = fight.arenaY + Rnd.get(401) - 200;
				try
				{
					PhantomManager.getInstance().livingTeleport(fighter, fx, fy, GeoEngine.getInstance().getSpawnHeight(fx, fy, fight.arenaZ));
				}
				catch (Exception e)
				{
					if (_infoHook != null)
					{
						_infoHook.accept("clan-personalities: [FIELD-MARCH] the ferry failed for " + fighter.getName() + ": " + e);
					}
				}
			}
		}
	}

	/** True when a REAL human stands right at the arena fence - close enough
	 *  that the waiting must end: the duel begins on a short fuse. */
	private boolean playerAtFence(FieldFight fight)
	{
		final long radiusSq = 1400L * 1400L;
		for (Player online : allPlayers())
		{
			if (fight.sideA.contains(online) || fight.sideB.contains(online))
			{
				continue;
			}
			if (PhantomManager.getInstance().livingPopulationPhantoms().containsValue(online))
			{
				continue;
			}
			final long dx = online.getX() - fight.arenaX;
			final long dy = online.getY() - fight.arenaY;
			if (((dx * dx) + (dy * dy)) <= radiusSq)
			{
				return true;
			}
		}
		return false;
	}

	/** True when a REAL human stands near the arena fence OR anywhere along
	 *  the breadcrumb road from the Gatekeeper to the arena - phantoms of
	 *  the world's cameras and the duel's own champions don't count. */
	private boolean playerNearFight(FieldFight fight)
	{
		final long radiusSq = (long) _playerGraceRadius * _playerGraceRadius;
		final long roadSq = (long) _playerRoadRadius * _playerRoadRadius;
		final long segX = (long) (fight.arenaX - fight.guideX);
		final long segY = (long) (fight.arenaY - fight.guideY);
		final double segLenSq = (segX * segX) + (segY * segY);
		for (Player online : allPlayers())
		{
			if (fight.sideA.contains(online) || fight.sideB.contains(online))
			{
				continue;
			}
			if (PhantomManager.getInstance().livingPopulationPhantoms().containsValue(online))
			{
				continue;
			}
			final long dx = online.getX() - fight.arenaX;
			final long dy = online.getY() - fight.arenaY;
			if (((dx * dx) + (dy * dy)) <= radiusSq)
			{
				return true;
			}
			// And the road: distance from the player to the straight line
			// between the Gatekeeper landing and the arena. Anyone walking
			// the announced direction holds the first blow while en route.
			if (segLenSq > 0)
			{
				final double pdx = online.getX() - fight.guideX;
				final double pdy = online.getY() - fight.guideY;
				final double t = ((pdx * segX) + (pdy * segY)) / segLenSq;
				final double tc = Math.max(0.0, Math.min(1.0, t));
				final double cdx = pdx - (segX * tc);
				final double cdy = pdy - (segY * tc);
				if (((cdx * cdx) + (cdy * cdy)) <= roadSq)
				{
					return true;
				}
			}
		}
		return false;
	}

	/** A spectator clan for the clash: Peaceful favorites, then anyone. */
	private ClanProfile pickFieldCrowd(ClanProfile a, ClanProfile b)
	{
		final List<ClanProfile> peaceful = new ArrayList<>();
		final List<ClanProfile> others = new ArrayList<>();
		for (ClanProfile profile : _profiles.values())
		{
			if ((profile == a) || (profile == b) || (profile.getPersona() == null))
			{
				continue; // never the fighters' own clans cheering themselves
			}
			if (_requireMembersPresent && !profile.isPresent())
			{
				continue;
			}
			if (resolveMembers(profile.getId()).isEmpty())
			{
				continue;
			}
			if (profile.getPersona() == PersonaType.PEACEFUL)
			{
				peaceful.add(profile);
			}
			else
			{
				others.add(profile);
			}
		}
		// Peaceful clans are the world's referees: they show up first.
		if (!peaceful.isEmpty())
		{
			return peaceful.get(Rnd.get(peaceful.size()));
		}
		if (!others.isEmpty())
		{
			return others.get(Rnd.get(others.size()));
		}
		return null;
	}

	/** Roster tag: "SunSpear's Elarion, Wyrlis and Gwenn" (clan up front). */
	private String sideTag(int clanId, List<String> names)
	{
		final ClanProfile profile = _profiles.get(clanId);
		final String clan = (profile != null) ? clanNameOf(profile) : "a clan";
		return clan + "'s " + String.join(", ", names);
	}

	/** The moment the duel is ON: the aggressor's side says it out loud. */
	private void fieldOpener(FieldFight fight)
	{
		if (!fight.running)
		{
			return;
		}
		final ClanProfile profileA = _profiles.get(fight.clanAId);
		final ClanProfile profileB = _profiles.get(fight.clanBId);
		if ((profileA == null) || (profileB == null))
		{
			return;
		}
		final VoiceChoice choice = chooseVoice(profileA);
		if (choice == null)
		{
			return;
		}
		String cause = _pairCauses.get(arcKey(fight.clanAId, fight.clanBId));
		if (cause == null)
		{
			cause = chooseCause(profileA, profileB);
		}
		final String lead = (_fieldLeadSeconds > 0) ? " - fists fly in about a minute, so anyone who wants a seat should start walking" : " - happening right now";
		final String prompt = "Your clan " + clanNameOf(profileA) + " has walked out of town to settle the beef with steel: " + sideTag(fight.clanAId, fight.namesA) + " against " + sideTag(fight.clanBId, fight.namesB) + ", just OUTSIDE the " + fight.compassSide + " gates of " + fight.townName + lead + ". ONE short hot line - announce the clash, name the place AND the side of town so people can actually find it, and taunt " + clanNameOf(profileB) + " over " + cause + ".";
		brainWorld(choice.creature, prompt, "delivered [FIELD-OPEN]", t -> _memory.add(fight.clanAId, fight.clanBId, t));
	}

	/**
	 * A QUIET duel's mouth: the challenger's clan dares its rivals DIRECTLY
	 * in world chat - names, place, one loose line, no crowd-herding - and
	 * the rival clan answers once. Nobody else's voice joins; if no one
	 * walks out that side of town, only the two clans ever know.
	 */
	private void fieldDuelCallout(FieldFight fight, boolean opening)
	{
		if (!fight.running)
		{
			return;
		}
		final ClanProfile speakerClan = _profiles.get(opening ? fight.clanAId : fight.clanBId);
		final ClanProfile otherClan = _profiles.get(opening ? fight.clanBId : fight.clanAId);
		if ((speakerClan == null) || (otherClan == null))
		{
			return;
		}
		final VoiceChoice choice = chooseVoice(speakerClan);
		if (choice == null)
		{
			return;
		}
		if (opening)
		{
			String cause = _pairCauses.get(arcKey(fight.clanAId, fight.clanBId));
			if (cause == null)
			{
				cause = chooseCause(speakerClan, otherClan);
			}
			final String targetName = fight.namesB.isEmpty() ? clanNameOf(otherClan) : fight.namesB.get(0);
			brainWorld(choice.creature, "Your clan's " + (fight.namesA.isEmpty() ? "champion" : fight.namesA.get(0)) + " has called its rival out to finish the quarrel with fists: the trouble is about " + cause + ". In ONE short line, dare " + targetName + " of " + clanNameOf(otherClan) + " DIRECTLY - name them, name the " + fight.compassSide + " side of " + fight.townName + " - but do NOT call a crowd, do NOT announce like news; this dare is addressed to THEM alone.", "delivered [FIELD-OPEN]", t -> _memory.add(fight.clanAId, fight.clanBId, t));
			// The rival clan gets one answer; then only the field speaks.
			ThreadPool.schedule(() -> fieldDuelCallout(fight, false), 5000 + Rnd.get(5000));
		}
		else
		{
			brainWorld(choice.creature, "You were just dared by name, over the quarrel with " + clanNameOf(otherClan) + ", to meet at the " + fight.compassSide + " side of " + fight.townName + ". In ONE short line, your fighter ANSWERS them directly: accept with heat or sharpen the mockery - but no crowd talk, no news announcing.", "delivered [FIELD-ANSWER]", t -> _memory.add(fight.clanBId, fight.clanAId, t));
		}
	}

	/** A spectator's one line over the clash; Peaceful clans berate both. */
	private void fieldCrowdLine(ClanProfile crowd, FieldFight fight)
	{
		if (!fight.running || (crowd == null) || (crowd.getPersona() == null))
		{
			return;
		}
		final VoiceChoice choice = chooseVoice(crowd);
		if (choice == null)
		{
			return;
		}
		final ClanProfile a = _profiles.get(fight.clanAId);
		final ClanProfile b = _profiles.get(fight.clanBId);
		if ((a == null) || (b == null))
		{
			return;
		}
		final String place = "outside the " + fight.compassSide + " gates of " + fight.townName;
		String prompt;
		if (crowd.getPersona() == PersonaType.PEACEFUL)
		{
			prompt = "Right now, " + sideTag(fight.clanAId, fight.namesA) + " and " + sideTag(fight.clanBId, fight.namesB) + " are squaring up for real " + place + ". You are of the PEACEFUL clan " + clanNameOf(crowd) + ". Berate BOTH sides in ONE short line: no threats, no favorites, pure scorn at senseless violence - the way a tired elder player would.";
		}
		else
		{
			prompt = "Right now, " + sideTag(fight.clanAId, fight.namesA) + " and " + sideTag(fight.clanBId, fight.namesB) + " are squaring up for real " + place + ". You are a member of " + clanNameOf(crowd) + ", watching as an outsider. React in ONE short line as a spectator - a gasp, a gamble, a jab, whatever your temperament wants.";
		}
		brainWorld(choice.creature, prompt, "delivered [CROWD]", t -> _memory.add(crowd.getId(), a.getId(), t));
	}

	/** Close a duel: unlock, stand down, and let the world hear the end. */
	private void endFight(FieldFight fight, List<Player> winnerSide, String note)
	{
		fight.running = false;
		// The aftermath needs air too: keep the stage frozen a while longer.
		_fieldQuietUntil = Math.max(_fieldQuietUntil, System.currentTimeMillis() + 150000L);
		_clanFightLocks.remove(Integer.valueOf(fight.clanAId));
		_clanFightLocks.remove(Integer.valueOf(fight.clanBId));
		// They settled it in steel: the brew is spent, whatever it brewed.
		_brew.remove(arcKey(fight.clanAId, fight.clanBId));
		_fieldFights.remove(arcKey(fight.clanAId, fight.clanBId));
		final PhantomManager pm = PhantomManager.getInstance();
		try
		{
			for (Player fighter : fight.sideA)
			{
				if (fighter.isOnline())
				{
					pm.setLivingIdle(fighter, false);
				}
			}
			for (Player fighter : fight.sideB)
			{
				if (fighter.isOnline())
				{
					pm.setLivingIdle(fighter, false);
				}
			}
			for (Player herald : fight.heralds)
			{
				if (herald.isOnline())
				{
					pm.setLivingIdle(herald, false);
				}
			}
			for (Player fan : fight.spectators)
			{
				if (fan.isOnline())
				{
					pm.setLivingIdle(fan, false);
				}
			}
		}
		catch (Exception e)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: field fight stand-down error: " + e);
			}
		}
		// The fighters walk off after the dust settles: a little posing for
		// whoever came to watch, then the pack takes its people back.
		for (Player fighter : fight.sideA)
		{
			ThreadPool.schedule(() -> releaseFighter(fighter), 45000 + Rnd.get(20000));
		}
		for (Player fighter : fight.sideB)
		{
			ThreadPool.schedule(() -> releaseFighter(fighter), 45000 + Rnd.get(20000));
		}
		for (Player herald : fight.heralds)
		{
			ThreadPool.schedule(() -> releaseFighter(herald), 45000 + Rnd.get(20000));
		}
		for (Player fan : fight.spectators)
		{
			ThreadPool.schedule(() -> releaseFighter(fan), 45000 + Rnd.get(20000));
		}
		final ClanProfile aProfile = _profiles.get(fight.clanAId);
		final ClanProfile bProfile = _profiles.get(fight.clanBId);
		if ((aProfile == null) || (bProfile == null))
		{
			return;
		}
		if (winnerSide != null)
		{
			// Real blood or a clear beating: the world hears BOTH sides.
			final boolean winnerIsA = (winnerSide == fight.sideA);
			final List<Player> loserSide = winnerIsA ? fight.sideB : fight.sideA;
			boolean bloodHit = false;
			for (Player fighter : loserSide)
			{
				if (fighter.isAlikeDead())
				{
					bloodHit = true;
					break;
				}
			}
			final ClanProfile winnerProfile = winnerIsA ? aProfile : bProfile;
			final ClanProfile loserProfile = winnerIsA ? bProfile : aProfile;
			final String winnerNames = String.join(", ", winnerIsA ? fight.namesA : fight.namesB);
			final String loserNames = String.join(", ", winnerIsA ? fight.namesB : fight.namesA);
			final boolean blood = bloodHit;
			final VoiceChoice winnerVoice = chooseVoice(winnerProfile);
			if (winnerVoice != null)
			{
				final String loserClan = clanNameOf(loserProfile);
				String prompt;
				if (blood)
				{
					prompt = "The duel outside the gates of " + fight.townName + " is OVER: " + loserClan + "'s " + loserNames + " are DOWN, your clan " + clanNameOf(winnerProfile) + " won it - your " + winnerNames + " still standing. ONE short line - the victory brag of your temperament, cold or roaring: mention that they fell.";
				}
				else
				{
					prompt = "The duel outside the gates of " + fight.townName + " is OVER: " + loserClan + "'s " + loserNames + " backed off, your clan " + clanNameOf(winnerProfile) + " takes the field. ONE short line - a firm, mid-temperature brag of your temperament, no threats past a reminder of who won.";
				}
				brainWorld(winnerVoice.creature, prompt, "delivered " + (blood ? "[FIELD-KILL]" : "[FIELD-WIN]"), t ->
				{
					// The winners' own brag rings out either way - world
					// talk for a quiet duel stays between the two clans
					// because NO third party ever joins it.
					_memory.add(winnerProfile.getId(), loserProfile.getId(), t);
					if (fight.loud)
					{
						// A grand brawl weighs on memory: the grudge is
						// written twice, so it outlives smaller talk.
						_memory.add(winnerProfile.getId(), loserProfile.getId(), t);
					}
				});
			}
			final VoiceChoice loserVoice = chooseVoice(loserProfile);
			if (loserVoice != null)
			{
				ThreadPool.schedule(() -> brainWorld(loserVoice.creature, "Your clan " + clanNameOf(loserProfile) + " just LOST a field duel outside the gates of " + fight.townName + " against " + clanNameOf(winnerProfile) + ((blood) ? " - your clansmen " + loserNames + " died there" : " - your clansmen " + loserNames + " had to back off") + ". ONE short line, by your temperament: a bitter howl, a coughed vow of revenge, or a grudging nod.", "delivered [FIELD-LOSS]", t ->
				{
					// The beaten side's own vow: their mouth, loose
					// world talk - no third voice needs to join it.
					_memory.add(loserProfile.getId(), winnerProfile.getId(), t);
					if (fight.loud)
					{
						_memory.add(loserProfile.getId(), winnerProfile.getId(), t);
					}
				}), 4000 + Rnd.get(8000) + spacingHold());
			}
			// An Aggressive or Player-Killing loser blames its IDLE alliance
			// aloud if their kin never joined the fight.
			if (((loserProfile.getPersona() == PersonaType.AGGRESSIVE) || (loserProfile.getPersona() == PersonaType.PLAYER_KILLING)) && (Rnd.get(100) < _loserBlameChance))
			{
				final List<String> idleKin = allianceMatesIdle(loserProfile, aProfile, bProfile);
				if (!idleKin.isEmpty())
				{
					final String kinNames = String.join(", ", idleKin);
					ThreadPool.schedule(() ->
					{
						final VoiceChoice blamer = chooseVoice(loserProfile);
						if (blamer == null)
						{
							return;
						}
						brainSpeak(blamer.creature, "Your clan just LOST a field clash against " + clanNameOf(winnerProfile) + ". Your OWN alliance - " + kinNames + " - never joined in and left you alone out there. ONE short hot line naming them and calling your alliance out for it.", "delivered [FIELD-BLAME]", !fight.loud, t -> _memory.add(loserProfile.getId(), loserProfile.getId(), t));
					}, 6000 + Rnd.get(12000) + spacingHold());
				}
			}
		}
		else
		{
			// No winner: both bruised or both vanished. One short note.
			final VoiceChoice voice = chooseVoice(aProfile);
			if (voice != null)
			{
				brainWorld(voice.creature, "The field duel outside the gates of " + fight.townName + " between " + clanNameOf(aProfile) + "'s " + String.join(", ", fight.namesA) + " and " + clanNameOf(bProfile) + "'s " + String.join(", ", fight.namesB) + ((note != null) ? " just scattered (" + note + ")" : "just ended even - both sides hurt, none dead") + ". ONE short line, ending it in words: either clan's tone is fine.", "delivered [FIELD-DRAW]", t ->
				{
					// The ending note is a party's own word - world chat.
					_memory.add(aProfile.getId(), bProfile.getId(), t);
					if (fight.loud)
					{
						_memory.add(aProfile.getId(), bProfile.getId(), t);
					}
				});
			}
		}
		// The clash feeds future chat: a fresh incident the pair remembers.
		final String key = arcKey(fight.clanAId, fight.clanBId);
		if (!_pairCauses.containsKey(key))
		{
			_pairCauses.put(key, "a field clash between the two clans");
		}
	}

	/** The loser's alliance-mates (the fight sides EXCLUDED), by name. */
	private List<String> allianceMatesIdle(ClanProfile lostProfile, ClanProfile aProfile, ClanProfile bProfile)
	{
		final List<String> names = new ArrayList<>();
		for (ClanProfile profile : _profiles.values())
		{
			if ((profile == lostProfile) || (profile == aProfile) || (profile == bProfile))
			{
				continue;
			}
			if ((profile.getAllianceGroup() == lostProfile.getAllianceGroup()) && (profile.getName() != null) && !resolveMembers(profile.getId()).isEmpty())
			{
				names.add(profile.getName());
			}
		}
		return names;
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
		// The brew thickens: every delivered answer of a running argument
		// is one more beat of REAL bickering - the very stuff steel
		// needs simmering behind it before fists are ever justified.
		noteBicker(speakerId, targetId);
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

	// ── The brain never blocks the game's threads ─────────────────────────
	/**
	 * The compose-and-speak seam: the brain's HTTP turn (ten to forty-five
	 * seconds) runs on the pack's bounded BrainExecutor - the pool Core
	 * gave every brain caller for exactly this - and its queue DROPS a
	 * beat that sat too long, so a stale word never speaks. Delivery hops
	 * back onto the game pool, so no regen, skill or AI timer ever parks
	 * behind a generation. An unanswered brain is silence, never a stall.
	 */
	private void brainSpeak(Creature speaker, String prompt, String label, boolean world, Consumer<String> after)
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
					if (world)
					{
						sayToAll(mouth, spoken, label);
					}
					else
					{
						sayLocal(mouth, spoken, label);
					}
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
		brainSpeak(speaker, prompt, label, true, null);
	}

	/** World-chat line plus a small game-pool follow-up (memory, next beat). */
	private void brainWorld(Creature speaker, String prompt, String label, Consumer<String> after)
	{
		brainSpeak(speaker, prompt, label, true, after);
	}

	/** Local-bubble line on the brain pool, delivered on the game pool. */
	private void brainNear(Creature speaker, String prompt, String label)
	{
		brainSpeak(speaker, prompt, label, false, null);
	}

	/**
	 * A one-off brain line outside the persona drama (takeover brag, the
	 * "who speaks now?" exchange, a clarify aside, a typo fix). Same voice
	 * rules as the drama lines: the speaking bot's name keys the persona,
	 * and the answer is one plain spoken line. THIS IS THE SYNCHRONOUS
	 * CORE: call it ONLY from a BrainExecutor worker - callers that block
	 * on it must be exactly the beats that were parked for the wait.
	 */
	private String brainCustom(Creature speaker, String situation)
	{
		if (_brain == null)
		{
			return null;
		}
		return _brain.line(speaker.getName(), FakePlayerChatManager.nearestLocation(speaker.getX(), speaker.getY()), (_channel == ChatType.SHOUT) ? "SHOUT" : "SAY", WORLD_LAW + situation + " Answer with ONE line only, in character, plain words, no brackets or markup, nothing that is not spoken out loud in the world.");
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
		// Provocation handling: some talk deserves steel. A public challenge
		// earns a persona answer - and sometimes a real duel at the gates.
		tryChallengeSpark(speaker, text);
	}

	// ── Challenges: provocation -> persona reply -> sometimes steel ──────

	/**
	 * A delivered line that dares somebody gets handled: if the stage is
	 * free and the roll passes, the challenge becomes a REAL field duel
	 * (the sparks light steel); otherwise a clan member answers with the
	 * heat their temperament demands. The reply is never canned - if the
	 * brain is down, nothing comes and the challenge hangs in the air.
	 */
	private void tryChallengeSpark(Creature speaker, String text)
	{
		if ((text == null) || text.isEmpty() || (speaker == null))
		{
			return;
		}
		String cue = null;
		for (String marker : _challengeCues)
		{
			if ((marker != null) && !marker.isEmpty() && containsIgnoreCase(text, marker))
			{
				cue = marker;
				break;
			}
		}
		if (cue == null)
		{
			return;
		}
		// Whose fist was it? A spoken cue belongs to a clan member.
		final ClanProfile speakerProfile = profileOfSpeaker(speaker);
		if ((speakerProfile == null) || (speakerProfile.getPersona() == null))
		{
			return;
		}
		// The rival: the live argument partner first, else any grudge.
		ClanProfile rival = partnerInArc(speakerProfile);
		if (rival == null)
		{
			rival = anyGrudgePartner(speakerProfile);
		}
		if ((rival == null) || (rival.getPersona() == null))
		{
			return;
		}
		// Hot blood answers a public dare; cold blood shrugs at it.
		if (Rnd.get(100) >= challengeReplyChance(rival.getPersona()))
		{
			return;
		}
		// The dare itself is MORE bickering: the beat counts whatever
		// follows - and where the pot already simmers, hot blood dares
		// twice as hard to actually meet in the field.
		noteBicker(speakerProfile.getId(), rival.getId());
		final int brewedBeats = brewBeats(arcKey(speakerProfile.getId(), rival.getId()));
		final int fightChance = _challengeFightChance * ((brewedBeats >= _brewBeats) ? 2 : 1);
		// Steel first, when the world allows it AND the skirmish bank
		// has drawn down: the fight IS the answer, but never a hot string.
		final long now = System.currentTimeMillis();
		if (_encounters && _fieldFights.isEmpty() && (now >= _fieldQuietUntil) && (now >= _nextSkirmishMillis) && (Rnd.get(100) < fightChance))
		{
			final Npc champA = fieldChampion(speakerProfile);
			final Npc champB = fieldChampion(rival);
			if ((champA != null) && (champB != null) && stageFight(speakerProfile, rival, now))
			{
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [FIELD-SPARK] a challenge over \"" + cue + "\" lit real steel outside per " + clanNameOf(speakerProfile) + " vs " + clanNameOf(rival) + ".");
				}
				return;
			}
		}
		// No steel (this time): a hot, brain-written persona answer.
		final ClanProfile challenger = speakerProfile;
		final ClanProfile replyRival = rival;
		final VoiceChoice voice = chooseVoice(rival);
		if (voice == null)
		{
			return;
		}
		ThreadPool.schedule(() -> brainWorld(voice.creature, "A " + clanNameOf(challenger) + " clansman just publicly challenged your clan in world chat: \"" + text + "\". Answer in ONE short line, exactly as your clan's temperament demands - if your blood is hot, accept the dare loudly and name where you'll meet; if your clan stays cool, scoff at the trash talk. Do not be diplomatic.", "delivered [CHALLENGE-REPLY]", t ->
		{
			_memory.add(replyRival.getId(), challenger.getId(), t);
			// The exchange answered is bickering too: the brew thickens
			// from both mouths, not just the challenger's.
			noteBicker(replyRival.getId(), challenger.getId());
		}), 4000 + Rnd.get(8000) + spacingHold());
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [CHALLENGE] " + clanNameOf(speakerProfile) + " dares " + clanNameOf(replyRival) + " over \"" + cue + "\"; their answer rolls.");
		}
	}

	/** How likely a persona answers a public dare with actual heat. */
	private static int challengeReplyChance(PersonaType persona)
	{
		return (persona == PersonaType.AGGRESSIVE) ? 80
			: (persona == PersonaType.PLAYER_KILLING) ? 70
			: (persona == PersonaType.ALTRUISTIC) ? 50
			: (persona == PersonaType.NEUTRAL) ? 30
			: 20; // PEACEFUL mostly lets trash talk slide
	}

	/** The clan profile a spoken creature belongs to, or null for strangers. */
	private ClanProfile profileOfSpeaker(Creature speaker)
	{
		for (ClanProfile profile : _profiles.values())
		{
			if (resolveMembers(profile.getId()).contains(speaker))
			{
				return profile;
			}
		}
		return null;
	}

	/** The clan currently arguing with the given one, if an arc is live. */
	private ClanProfile partnerInArc(ClanProfile profile)
	{
		for (ClanProfile other : _profiles.values())
		{
			if ((other == profile) || (other.getPersona() == null))
			{
				continue;
			}
			if (_arcs.containsKey(arcKey(profile.getId(), other.getId())))
			{
				return other;
			}
		}
		return null;
	}

	/** Someone this clan quarreled with recently, if any. */
	private ClanProfile anyGrudgePartner(ClanProfile profile)
	{
		final List<ClanProfile> grudges = new ArrayList<>();
		for (ClanProfile other : _profiles.values())
		{
			if ((other == profile) || (other.getPersona() == null))
			{
				continue;
			}
			if (_memory.argued(profile.getId(), other.getId(), _grudgeWindowMillis))
			{
				grudges.add(other);
			}
		}
		return grudges.isEmpty() ? null : grudges.get(Rnd.get(grudges.size()));
	}

	/** Wire the challenge dials from the module. */
	void setChallengeCues(List<String> cues, int fightChance)
	{
		_challengeCues.clear();
		_challengeCues.addAll(cues);
		_challengeFightChance = Math.max(0, Math.min(100, fightChance));
	}

	/** How far a duel arena keeps itself from any standing guard. */
	void setGuardClearRadius(int radius)
	{
		_guardClearRadius = Math.max(200, Math.min(5000, radius));
	}

	/** Pre-fight runway: how long the world gets to walk to the gates. */
	void setFieldLeadSeconds(int seconds)
	{
		_fieldLeadSeconds = Math.max(0, Math.min(300, seconds));
	}

	/** How many bots drift toward the arena as local-crumbs heralds. */
	void setFieldHeralds(int heralds)
	{
		_fieldHeralds = Math.max(0, Math.min(5, heralds));
	}

	/** How close a watcher must stand (or walk) to hold the first blow. */
	void setPlayerGrace(int radius, int roadRadius, long maxMillis)
	{
		_playerGraceRadius = Math.max(500, Math.min(6000, radius));
		_playerRoadRadius = Math.max(200, Math.min(4000, roadRadius));
		_playerGraceMaxMillis = Math.max(0, Math.min(600000, maxMillis));
	}

	/** The skirmish bank: base quiet between duels anywhere in the world,
	 *  and the extra wait per notch of heat a fight's size left behind. */
	void setSkirmishCooldowns(long cooldownMillis, long heatStepMillis)
	{
		_skirmishCooldownMillis = Math.max(30000, Math.min(3600000, cooldownMillis));
		_skirmishHeatStepMillis = Math.max(0, Math.min(1800000, heatStepMillis));
	}

	/** How much bickering steel must be justified by: the minimum beats
	 *  of argument, how long the anger lingers after the last word, and
	 *  the shortest brew before a pair may choose fists. */
	void setBrewRequirements(int beats, int windowSeconds, int minSeconds)
	{
		_brewBeats = Math.max(1, Math.min(9, beats));
		_brewWindowMillis = Math.max(60000, Math.min(3600000, windowSeconds * 1000L));
		_brewMinMillis = Math.max(0, Math.min(600000, minSeconds * 1000L));
	}

	/** One more beat of bickering between two clans - a stir of the pot. */
	private void noteBicker(int a, int b)
	{
		if (a == b)
		{
			return;
		}
		final String key = arcKey(a, b);
		final long now = System.currentTimeMillis();
		final long[] brew = _brew.computeIfAbsent(key, k -> new long[] { 0, now, now });
		if ((now - brew[2]) > _brewWindowMillis)
		{
			brew[0] = 1; // a cold day's old business: the count starts over
			brew[1] = now;
		}
		else
		{
			brew[0] = Math.min(9, brew[0] + 1);
		}
		brew[2] = now;
	}

	/** Beats of recent argument between two clans; a cold pot reads zero. */
	private int brewBeats(String key)
	{
		final long[] brew = _brew.get(key);
		if (brew == null)
		{
			return 0;
		}
		if ((System.currentTimeMillis() - brew[2]) > _brewWindowMillis)
		{
			return 0;
		}
		return (int) brew[0];
	}

	/** True once real simmering justifies steel: enough beats lately,
	 *  AND long enough brewing for the choice to feel made, not sudden. */
	private boolean brewedForSteel(String key)
	{
		final long[] brew = _brew.get(key);
		if (brew == null)
		{
			return false;
		}
		final long now = System.currentTimeMillis();
		if ((now - brew[2]) > _brewWindowMillis)
		{
			return false;
		}
		if (brew[0] < _brewBeats)
		{
			return false;
		}
		return (now - brew[1]) >= _brewMinMillis;
	}

	/** A glowing +10 weapon, so a post catches the eye in a crowd. */
	private void glowUp(Player herald)
	{
		try
		{
			final Item weapon = herald.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
			if (weapon != null)
			{
				weapon.setEnchantLevel(10);
				herald.broadcastUserInfo();
			}
		}
		catch (Exception e)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [HERALD] glow failed for " + herald.getName() + ": " + e);
			}
		}
	}

	// ── Shorthand (nicknames) and clarification chatter ──────────────────

	/**
	 * Nickname drift: the brain coins one player-style nickname for a clan
	 * that has none yet. Coined names enter the speech pool immediately and
	 * are persisted by the module, so the world's slang grows over days.
	 */
	private void tryNicknameDrift()
	{
		if (_nicknameDriftSink == null || _profiles.isEmpty())
		{
			return;
		}
		final List<ClanProfile> ground = new ArrayList<>();
		for (ClanProfile profile : _profiles.values())
		{
			if ((profile.getPersona() == null) || (profile.getName() == null) || resolveMembers(profile.getId()).isEmpty())
			{
				continue;
			}
			if (_clanNicknames.containsKey(normalizeClanToken(profile.getName())))
			{
				continue; // already nicknamed (hand-tuned or coined)
			}
			ground.add(profile);
		}
		if (ground.isEmpty())
		{
			return;
		}
		final ClanProfile seed = ground.get(Rnd.get(ground.size()));
		final VoiceChoice asker = chooseVoice(seed);
		if (asker == null)
		{
			return;
		}
		// The coin happens on a brain worker; the word it wins is stored
		// back on the game pool.
		final ClanProfile named = seed;
		try
		{
			BrainExecutor.runBrainWork(() ->
			{
				final String coined = brainCustom(asker.creature, "Players are starting to coin a short, funny nickname for the clan " + named.getName() + " - the way players coin nicknames for clan names (like \"Ivy Crown\" for Ivory Crown). Reply with ONLY the nickname itself: 1 to 3 words, no quotes, no punctuation, no explanation, nothing else.");
				ThreadPool.schedule(() -> storeCoinedNickname(named, asker.creature, coined), 0);
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

	/** The coined word becomes the clan's standing shorthand, persisted. */
	private void storeCoinedNickname(ClanProfile seed, Creature asker, String coinedRaw)
	{
		if (coinedRaw == null)
		{
			return; // the brain is down: slang waits for it
		}
		final String nick = cleanNickname(coinedRaw);
		if (nick == null)
		{
			return;
		}
		final String token = normalizeClanToken(seed.getName());
		_clanNicknames.put(token, nick);
		_nicknameDriftSink.accept(token, nick);
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [NICKNAME] " + seed.getName() + " coined as \"" + nick + "\" (sticks between restarts) by " + asker.getName() + ".");
		}
	}

	/** Loose brain reply in, tight one-word-to-three nickname out. */
	private static String cleanNickname(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String nick = raw.replace('\r', ' ').replace('\n', ' ').trim();
		nick = nick.replaceAll("^[\"'`\\s]+", "").replaceAll("[\"'`\\s.]+$", "");
		if (nick.isEmpty() || (nick.length() > 24) || nick.contains(":") || nick.contains("=") || nick.contains(","))
		{
			return null;
		}
		return nick;
	}

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
			if ((full == null) || !containsIgnoreCase(text, full) || (Rnd.get(100) >= _shorthandChance))
			{
				continue;
			}
			// A hand-tuned funny nickname gets its own roll: given one, the
			// voice may say "Ivy Crown" instead of initials this time.
			final String nick = _clanNicknames.get(normalizeClanToken(full));
			final String abbr = abbrevFor(full);
			String spoken = abbr;
			if ((nick != null) && (Rnd.get(100) < _nicknameChance))
			{
				spoken = nick;
			}
			if (spoken == null)
			{
				continue;
			}
			text = replaceIgnoreCase(text, full, spoken);
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
			// Odds first, then the per-mention cooldown gate: most mentions
			// are just talk; the ask is a rare treat wherever it lands.
			if (Rnd.get(100) >= _clarifyChance)
			{
				continue;
			}
			if (gateClarify("c:" + mentioned.getId(), now))
			{
				ThreadPool.schedule(() -> deliverClarifyAsk(mentioned, null), 5000 + Rnd.get(15000) + spacingHold());
				ThreadPool.schedule(() -> deliverClarifyAnswer(mentioned, null), 25000 + Rnd.get(25000) + spacingHold());
			}
		}
		for (Integer group : allianceMentions)
		{
			if (Rnd.get(100) >= _clarifyChance)
			{
				continue;
			}
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
		final String abbr = shortFormOf(clan, fullname);
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
		String situation;
		if (clan != null)
		{
			situation = "Players call the clan " + fullname + " by a nickname you keep seeing: \"" + abbr + "\". You have no idea it means them. Ask in ONE short line what \"" + abbr + "\" stands for - casual, curious, the way a player would type it.";
		}
		else
		{
			situation = "Someone in the world chat used the short name \"" + abbr + "\" for the " + kind + " " + fullname + ", and you have no idea which clan that is. Ask in ONE short line what \"" + abbr + "\" stands for - casual, curious, the way a player would type it.";
		}
		brainWorld(asker, situation, "delivered [ASK]");
	}

	/** The talkable short form of a clan: its funny nickname when it has
	 *  one, else the initials. Returns null for unshortenable names. */
	private String shortFormOf(ClanProfile clan, String fullname)
	{
		if ((clan != null) && (clan.getName() != null))
		{
			final String nick = _clanNicknames.get(normalizeClanToken(clan.getName()));
			if (nick != null)
			{
				return nick;
			}
		}
		return abbrevFor(fullname);
	}

	/** The named clan - or one present clan of the named alliance - explains. */
	private void deliverClarifyAnswer(ClanProfile clan, Integer allianceGroup)
	{
		final String fullname = (clan != null) ? clan.getName() : _allianceNames.get(allianceGroup);
		final String abbr = shortFormOf(clan, fullname);
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
