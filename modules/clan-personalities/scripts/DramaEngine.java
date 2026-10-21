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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Properties;
import java.util.HashSet;
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

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.BotClanManager;
import org.l2jmobius.gameserver.managers.BrainExecutor;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.managers.FakePlayerChatManager;
import org.l2jmobius.gameserver.managers.PhantomCombatActions;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomPlaystyleEngine;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.conditions.ConditionUsingItemType;
import org.l2jmobius.gameserver.model.skill.Skill;
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
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.model.zone.type.WaterZone;
import org.l2jmobius.gameserver.livingpop.LivingPopulationManager;
import org.l2jmobius.gameserver.network.GameClient;
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
	/** How far a duel piste keeps from a standing guard. Small on purpose:
	 *  guards mark the road mouths the arena search starts from. */
	private int _guardClearRadius = 350;
	/** How far a duel piste keeps from any AGGRESSIVE monster. */
	private int _monsterClearRadius = 700;
	/** How many towns the arena search may try per staging (0 = all). */
	private int _arenaTownTries = 0;
	/** Lead-in: seconds of pre-fight silence the world can use to walk
	 *  over (trash talk and walking heralds fill that window). */
	private int _fieldLeadSeconds = 150;
	/** Walk-in heralds drifting toward the arena, commenting locally. */
	private int _fieldHeralds = 0;
	/** Widest level gap allowed between two paired fighters. */
	private int _maxLevelGap = 5;
	/** Widest gear-score gap allowed between two paired fighters. */
	private int _maxGearGap = 3;
	/** Normal duels roll 1..this per side; epic battles roll EpicSquadMin..EpicSquadMax. */
	private int _fieldMaxSquad = 3;
	private int _epicChance = 0;
	private int _epicMin = 4;
	private int _epicMax = 9;
	/** A gear score at or below this is "bare": it only meets someone at most one point above it. */
	private int _bareGearMax = 1;
	/** 1v1 class pairings to avoid, "ATTACKER-VICTIM" by party role (DAGGER-NUKER...). */
	private final Set<String> _avoidSolo = ConcurrentHashMap.newKeySet();
	/** Skill names (lower case) never used in a field duel: monster-only or
	 *  out-of-combat skills that make a fighter stall against a player. */
	private final List<String> _fieldSkillBlacklist = new ArrayList<>(java.util.Arrays.asList("spoil", "spoil festival", "sweeper", "festival sweeper", "sow", "harvesting", "fake death"));
	/** A watcher at the fence holds the gate open this far (units). */
	private int _playerGraceRadius = 3000;
	/** A watcher still ON THE ROAD (this far off the Gatekeeper->arena
	 *  line) also holds the gate: they are coming, just not close yet. */
	private int _playerRoadRadius = 2200;
	/** ...but no watcher holds it past this, ever (millis past start). */
	private long _playerGraceMaxMillis = 180000;
	/** Aggression added per trash line (before temperament), and how long a
	 *  watcher at the fence waits at most before the first blow. */
	private int _aggroMin = 12;
	private int _aggroMax = 22;
	private long _fenceMaxWaitMillis = 60000L;
	/** A watcher within this range of the arena counts as right beside the
	 *  fighters: jabs every few seconds, and a much shorter fence wait. */
	private int _veryCloseRadius = 600;
	/** A watcher within this range of the arena is AT the fence. */
	private int _fenceRadius = 900;
	private long _veryCloseTrashMillis = 5000L;
	private long _veryCloseFenceMillis = 25000L;
	/** Crowd gossip: one roll per window; odds a fighter answers it. */
	private long _spectatorTalkMillis = 14000L;
	private int _spectatorTalkChance = 70;
	private int _fighterAnswerChance = 60;

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
		/** An EPIC battle (4v4 and up): a war, not a duel - rarer, louder, longer runway. */
		volatile boolean epic;
		/** False for open villages with no wall (Talking Island...): no "gates" to name. */
		volatile boolean gated = true;
		/** Alliance pools were used: the sides are alliances, led by the two quarreling clans. */
		volatile boolean allianceWar;
		volatile String allianceA;
		volatile String allianceB;
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
		/** The hidden aggression meter: every jab across the gap adds to it;
		 *  at 100 one side snaps and swings first. Per-side shares decide WHO. */
		volatile int aggression;
		volatile int aggressionA;
		volatile int aggressionB;
		/** Fighters who have said their piece at the field. */
		final Set<String> spoke = ConcurrentHashMap.newKeySet();
		/** When a watcher first stood at the fence (0 = not yet). */
		volatile long fenceSinceMillis;
		/** A watcher has come right up to the fighters. */
		volatile boolean veryCloseNoted;
		/** Crowd gossip cadence (gear, class, clan record). */
		long lastTalkMillis = 0;
		int talkBeats = 0;
		/** Casters' beats in a row with nothing to cast. */
		final Map<String, Integer> casterDry = new ConcurrentHashMap<>();
		/** Fans' clans by object id (from their town bot), so a fan of the
		 *  fighting clans or their alliances can be told apart. */
		final Map<Integer, Integer> fanClans = new ConcurrentHashMap<>();
		/** A real player broke in: the cast was handed to a ZeldaChickens hunt. */
		volatile boolean zelda;
		/** Buffs each support fighter has cast this duel (capped). */
		final Map<String, Integer> supportBuffs = new ConcurrentHashMap<>();
		/** Both clans belong to the SAME alliance: brothers turning on each other. */
		volatile boolean sameAlliance;
		volatile String allianceName;

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

	// ── Clan records: duel results and how quarrels ended, across restarts ──
	/** One clan pair's shared history, keyed "lowId-highId". */
	private static final class PairRecord
	{
		int winsLow;
		int winsHigh;
		int draws;
		int lastWinner; // clan id of the last duel's winner, 0 = draw / none
		long lastDuelAt;
		String lastDuelTown = "";
		String lastEnding = ""; // "peace" or "fight" - how the last quarrel closed
		long lastEndingAt;
		String lastCause = "";
	}

	private final Map<String, PairRecord> _records = new ConcurrentHashMap<>();
	private volatile File _recordFile;
	/** How much stronger a lost duel pulls a clan back toward that rival. */
	private int _revengeBoost = 3;
	private final Object _recordLock = new Object();

	// ── Grievances and alliance wars (user, 2026-10-21) ─────────────────
	/** "AllianceA+AllianceB" (sorted) -> unsatisfied quarrel endings between them. Saved in records.properties. */
	private final Map<String, Integer> _grievances = new ConcurrentHashMap<>();
	/** Clan pair (arcKey) -> when its war cry goes stale if no duel follows. */
	private final Map<String, Long> _pendingWarCries = new ConcurrentHashMap<>();
	/** Alliance pairs whose next duel is a forced EPIC alliance war. */
	private final Set<String> _warDue = ConcurrentHashMap.newKeySet();
	private volatile long _grievanceClock; // play time (a human online) toward the next fade
	private volatile long _grievanceLastTick;
	/** Master switch for grievances, alliance wars and alliance pools (on hold for 0.3.0, user 2026-10-21). */
	private volatile boolean _allianceWars = false;
	private int _grievanceStep = 3;
	private int _epicChanceCap = 40;
	private int _allianceWarAt = 6;
	private long _grievanceFadeMillis = 3L * 3600000L;
	private long _warCryWindowMillis = 20L * 60000L;
	private int _warAllyLines = 4;

	// ── Drama focus: one quarrel develops at a time ──
	/** While an argument is still developing, NEW openings (quarrels,
	 *  taunts, chatter callouts) wait their turn instead of piling on. */
	private boolean _dramaFocus = true;
	/** An argument with no beat for this long no longer holds the stage. */
	private long _focusStaleMillis = 120000L;
	/** After a quarrel resolves, the stage breathes this long before the
	 *  next held opening may land ("the timer ticks back down"). */
	private long _focusBreatherMillis = 45000L;
	private volatile long _focusBreatherUntil;
	private volatile long _focusLastLogMillis;

	// ── Taunts: hot-blooded clans goading others toward the field ──
	/** Percent chance per tick that an eligible Aggressive/PK clan taunts. */
	private int _tauntChance = 20;
	/** Each taunting clan's own rest between taunts. */
	private long _tauntCooldownMillis = 900000L;
	/** World-wide rest between any two taunts. */
	private long _tauntGlobalCooldownMillis = 360000L;
	private volatile long _nextTauntMillis;
	private final Map<Integer, Long> _tauntReadyAt = new ConcurrentHashMap<>();

	// ── Reading intent: threat, provocation, or a joke? ──
	private enum ReadType
	{
		THREAT,
		PROVOCATION,
		AMBIGUOUS,
		CONFIRMED,
		DEFUSED
	}

	/** How one clan read another's last line aimed at it. */
	private static final class Reading
	{
		volatile ReadType type;
		final String text;
		final long atMillis;
		/** Decided when the target asks: will the speaker confirm it? */
		volatile boolean confirm;

		Reading(ReadType type, String text, long atMillis)
		{
			this.type = type;
			this.text = text;
			this.atMillis = atMillis;
		}
	}

	private boolean _readIntent = true;
	private int _threatClarifyChance = 50;
	private long _threatClarifyCooldownMillis = 300000L;
	/** A reading colors the target's replies for this long. */
	private static final long READING_FRESH_MILLIS = 180000L;
	/** Directional key "speaker>target" -> how the target read the line. */
	private final Map<String, Reading> _readings = new ConcurrentHashMap<>();
	/** Pair key -> no new "do you mean it?" before this moment. */
	private final Map<String, Long> _intentAskGates = new ConcurrentHashMap<>();
	private final List<Pattern> _threatCues = new ArrayList<>();
	private final List<Pattern> _banterCues = new ArrayList<>();
	private final List<Pattern> _provokeCues = new ArrayList<>();

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
		// Nobody to play for (v0.2.3): with no real human online the world
		// goes quiet - no new drama, no duels, and (see brainSubmit) no brain
		// calls - instead of talking to an empty server. A cloud brain bills
		// every line; a local one burns the GPU for nobody. A fight already
		// running still finishes on its own heartbeat, just silently.
		if (!humanOnline())
		{
			if (!_idleAnnounced)
			{
				_idleAnnounced = true;
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [IDLE] no real player online - drama, duels and brain calls pause until one logs in.");
				}
			}
			return;
		}
		if (_idleAnnounced)
		{
			_idleAnnounced = false;
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [IDLE] a real player is online again - the clans pick the drama back up.");
			}
		}
		grievanceTick(now);
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

		// Drama focus: while one quarrel is still developing (or the stage
		// is catching its breath after one), no NEW quarrel opens - the
		// argument gets the time to play out fully. Chatter still flows.
		final boolean focusBusy = dramaFocusHolds(now, null);
		// Hot blood goads: an Aggressive or Player-Killing clan may taunt
		// another toward the field, on its own cooldown and the world's.
		final int taunted = focusBusy ? 0 : tryTaunt(now, candidates);

		for (ClanProfile profile : candidates)
		{
			final PersonaType persona = profile.getPersona();
			if (profile.getId() == taunted)
			{
				continue; // it just spent its tick on a taunt
			}

			// Grudges double the appetite of responding personas...
			int chance = (int) (_dramaStartChance * persona.getStartFactor());
			if ((chance > 0) && hasAnyGrudge(profile.getId()))
			{
				chance *= 2;
			}

			if ((chance > 0) && !focusBusy)
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
			if (_arcs.values().removeIf(arc -> (now - arc.lastBeatMillis) > STALLED_ARC_MILLIS))
			{
				focusReleased(now);
			}
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
		if (accuseSulking(clanId))
		{
			return; // Phase 3: caught being nice - the clan has gone silent
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

		// Drama focus: a NEW opening waits while another quarrel is still
		// developing, then gets its turn once that one has resolved and the
		// stage has caught its breath.
		final String pairKey = arcKey(clanId, otherClanId);
		if (((kind == MessageKind.START) || (kind == MessageKind.TAUNT)) && dramaFocusHolds(now, pairKey))
		{
			final long wait = 20000 + Rnd.get(15000);
			_pendingStarts.put(pairKey, Long.valueOf(now + wait + 180000L));
			_queue.schedule(clanId, kind, otherClanId, wait, voiceRank);
			if ((_infoHook != null) && ((now - _focusLastLogMillis) > 60000L))
			{
				_focusLastLogMillis = now;
				_infoHook.accept("clan-personalities: [FOCUS] holding " + clanNameOf(profile) + "'s " + kind + " against " + clanNameOf(_profiles.get(otherClanId)) + " - another quarrel is still developing" + ((now < _focusBreatherUntil) ? " (stage catching its breath, " + ((_focusBreatherUntil - now) / 1000) + "s)" : "") + ".");
			}
			return;
		}
		// A "do you mean it?" exchange owns the pair for a moment: the
		// argument's other beats wait until the intent is cleared up.
		if ((kind == MessageKind.RESPONSE) || (kind == MessageKind.ASIDE) || (kind == MessageKind.EXPLAIN) || (kind == MessageKind.QUESTION))
		{
			final DramaArc held = _arcs.get(pairKey);
			if ((held != null) && (now < held.intentHoldUntil))
			{
				_queue.schedule(clanId, kind, otherClanId, 12000 + Rnd.get(8000), voiceRank);
				return;
			}
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
		if ((kind == MessageKind.START) || (kind == MessageKind.TAUNT))
		{
			// An incident opens the drama: a concrete offense the pair keeps
			// arguing about while the grudge lasts, instead of noise.
			causeLabel = chooseCause(profile, other);
		}
		else if ((kind == MessageKind.RESPONSE) || (kind == MessageKind.ASIDE) || (kind == MessageKind.EXPLAIN) || (kind == MessageKind.INTENT_ASK) || (kind == MessageKind.INTENT_ANSWER))
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
			brainSubmit(() ->
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
		// The addressed clan READS the line: an open threat, mere trash
		// talk, or something in between it may ask about.
		if ((other != null) && ((kind == MessageKind.START) || (kind == MessageKind.TAUNT) || (kind == MessageKind.RESPONSE) || (kind == MessageKind.EXPLAIN) || (kind == MessageKind.ASIDE)))
		{
			readLine(profile, other, kind, text, System.currentTimeMillis());
		}

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
				if ((mentioned.getId() == clanId) || (mentioned.getPersona() == null) || _arcs.containsKey(pairKey) || pairAtRest(clanId, mentioned.getId()) || dramaFocusHolds(System.currentTimeMillis(), pairKey))
				{
					continue; // its own clan ("VS's camping" by a VioletSpider member, playtest 2026-10-21), persona-less, already running, at rest, or another quarrel holds the stage
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
		if ((kind == MessageKind.START) || (kind == MessageKind.TAUNT))
		{
			// The arc remembers the SAME incident the opening line was
			// written about: quarrel and words must tell one story.
			final DramaArc arc = new DramaArc(1, causeLabel);
			if (kind == MessageKind.TAUNT)
			{
				// A dare to fight is bickering in its own right: the pot
				// starts warm, and the rival's answers know they were dared.
				arc.tauntBy = clanId;
				noteBicker(clanId, otherClanId);
			}
			_arcs.put(arcKey(clanId, otherClanId), arc);
			_pendingStarts.remove(arcKey(clanId, otherClanId));
			scheduleResponses(clanId, otherClanId);
		}
		else if (kind == MessageKind.INTENT_ASK)
		{
			// Asked whether they meant it: the original speaker owes the
			// answer next, and quickly - the whole argument waits on it.
			if (other != null)
			{
				final int rank = rollBeatRank(0);
				_queue.schedule(otherClanId, MessageKind.INTENT_ANSWER, clanId, 6000 + Rnd.get(8000), rank);
			}
		}
		else if (kind == MessageKind.INTENT_ANSWER)
		{
			settleIntent(clanId, otherClanId, profile, other, now);
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
			_kindnessBy.put(Integer.valueOf(clanId), new long[] { otherClanId, now }); // who helped whom (Phase 3)
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
		final String[] intent = intentContext(profile, kind, other);
		return _brain.line(speaker.getName(), FakePlayerChatManager.nearestLocation(speaker.getX(), speaker.getY()), (_channel == ChatType.SHOUT) ? "SHOUT" : "SAY", brainPrompt(profile, kind, other, recent, cause, kindnessHistory, intent[0], intent[1]));
	}

	/**
	 * The situation in-character. The brain adds its own persona and memory
	 * for the speaking bot by name; this adds what ONLY the module knows:
	 * clan membership, temperament, and what this message is for.
	 */
	/** World law enforced on every spoken line: Interlude-only lore, no cross-game borrowings. */
	private static final String WORLD_LAW = "WORLD LAW: this is Lineage 2, Interlude. Speak ONLY of things that exist in that game and era - places like Talking Island, Gludio, Dion, Giran, Oren, Aden, Hunters Village, Cruma Tower, Enchanted Valley, Forest of Mirrors; creatures like wolves, gremlins, orcs, ant soldiers, golems, wyverns, Striders, spiritual eyes; coin is adena; gear grades are D, C, B, A and S only. NOTHING else exists: no places, monsters, items or grades from other games, and no inventions of your own - Ragnarok, World of Warcraft style fakes, 'P grade', 'G grade' - none of that is real here. If you are not sure something exists in Lineage 2, keep the line vague instead of naming it. CLANS: a clan name (IvoryCrown, SunSpear, BloodRaven...) is a CLAN - a whole group of players - never one person: speak of a clan as 'they', 'them', 'those guys' or by its name, never 'he', 'she', 'him', 'her' or 'a guy'. ";

	private static String brainPrompt(ClanProfile profile, MessageKind kind, ClanProfile other, List<String> recent, String cause, boolean kindnessHistory, String situationOverride, String readingNote)
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
		else if (kind == MessageKind.TAUNT)
		{
			situation = "Your clan is itching for a REAL fight, and you pick the clan " + ((other != null) && (other.getName() != null) ? other.getName() : "you despise") + " to goad. Publicly taunt them into sending fighters to meet yours in the field outside town: ONE short line that dares them to come fight and mocks their nerve - a challenge to steel, not small talk.";
		}
		else if ((kind == MessageKind.INTENT_ASK) || (kind == MessageKind.INTENT_ANSWER))
		{
			situation = (situationOverride != null) ? situationOverride : "Answer the last thing said to your clan in ONE short line.";
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
		if (readingNote != null)
		{
			// How your clan READ their last words: open threat, trash talk,
			// or a threat they confirmed / waved off when asked.
			prompt.append(readingNote).append(' ');
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

	// ── Clan records ─────────────────────────────────────────────────────

	/** Wires the record file and loads it (records.properties). */
	void setRecordFile(File file, int revengeBoost)
	{
		_recordFile = file;
		_revengeBoost = Math.max(1, Math.min(10, revengeBoost));
		_records.clear();
		if ((file == null) || !file.isFile())
		{
			return;
		}
		final Properties props = new Properties();
		try (InputStreamReader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
		{
			props.load(in);
		}
		catch (Exception e)
		{
			announce("[RECORD] could not read " + file.getName() + " (" + e + ") - starting with no history.");
			return;
		}
		_grievances.clear();
		for (String key : props.stringPropertyNames())
		{
			if (key.startsWith("grievance."))
			{
				// Grievances share the file; older versions skip these lines
				// (they don't have the 9 record fields).
				try
				{
					final String[] g = props.getProperty(key, "").split("\\|", -1);
					if (key.equals("grievance.~clock"))
					{
						_grievanceClock = Long.parseLong(g[0]);
					}
					else if (key.startsWith("grievance.war."))
					{
						if ("1".equals(g[0]))
						{
							_warDue.add(key.substring("grievance.war.".length()));
						}
					}
					else
					{
						final int count = Integer.parseInt(g[0]);
						if (count > 0)
						{
							_grievances.put(key.substring("grievance.".length()), Integer.valueOf(count));
						}
					}
				}
				catch (Exception e)
				{
					// a bad grievance line is simply dropped
				}
				continue;
			}
			final String[] f = props.getProperty(key, "").split("\\|", -1);
			if (f.length < 9)
			{
				continue;
			}
			try
			{
				final PairRecord r = new PairRecord();
				r.winsLow = Integer.parseInt(f[0]);
				r.winsHigh = Integer.parseInt(f[1]);
				r.draws = Integer.parseInt(f[2]);
				r.lastWinner = Integer.parseInt(f[3]);
				r.lastDuelAt = Long.parseLong(f[4]);
				r.lastDuelTown = f[5];
				r.lastEnding = f[6];
				r.lastEndingAt = Long.parseLong(f[7]);
				r.lastCause = f[8];
				_records.put(key, r);
			}
			catch (Exception e)
			{
				// one bad line costs only that pair's history
			}
		}
		announce("[RECORD] loaded " + _records.size() + " clan pair record(s) from " + file.getName() + (_grievances.isEmpty() ? "" : (", and " + _grievances.size() + " alliance grievance(s): " + grievanceText())) + (_warDue.isEmpty() ? "" : (" - an alliance war is due: " + String.join(", ", _warDue))) + ".");
	}

	private void saveRecords()
	{
		final File file = _recordFile;
		if (file == null)
		{
			return;
		}
		synchronized (_recordLock)
		{
			final Properties props = new Properties();
			for (Map.Entry<String, PairRecord> e : _records.entrySet())
			{
				final PairRecord r = e.getValue();
				props.setProperty(e.getKey(), r.winsLow + "|" + r.winsHigh + "|" + r.draws + "|" + r.lastWinner + "|" + r.lastDuelAt + "|" + clean(r.lastDuelTown) + "|" + clean(r.lastEnding) + "|" + r.lastEndingAt + "|" + clean(r.lastCause));
			}
			for (Map.Entry<String, Integer> g : _grievances.entrySet())
			{
				props.setProperty("grievance." + g.getKey(), String.valueOf(g.getValue()));
			}
			for (String due : _warDue)
			{
				props.setProperty("grievance.war." + due, "1");
			}
			props.setProperty("grievance.~clock", String.valueOf(_grievanceClock));
			final File tmp = new File(file.getPath() + ".tmp");
			try (OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))
			{
				props.store(out, "clan-personalities: duel results and quarrel endings per clan pair (lowId-highId = winsLow|winsHigh|draws|lastWinnerId|lastDuelAt|town|lastEnding|endingAt|cause)");
			}
			catch (Exception e)
			{
				announce("[RECORD] could not write " + file.getName() + " (" + e + ").");
				return;
			}
			if (file.exists() && !file.delete())
			{
				announce("[RECORD] could not replace " + file.getName() + ".");
				return;
			}
			if (!tmp.renameTo(file))
			{
				announce("[RECORD] could not finish writing " + file.getName() + ".");
			}
		}
	}

	/** The side a clan stands on in an alliance war: its alliance, or itself when it has none. */
	private String allianceKeyOf(ClanProfile profile)
	{
		if (profile.getAllianceGroup() > 0)
		{
			final String name = _allianceNames.get(Integer.valueOf(profile.getAllianceGroup()));
			return ((name != null) && !name.isEmpty()) ? name : ("Alliance" + profile.getAllianceGroup());
		}
		return (profile.getName() != null) ? profile.getName() : ("Clan" + profile.getId());
	}

	/** "A+B" sorted, or null when both stand on the same side. */
	private String grievanceKey(ClanProfile a, ClanProfile b)
	{
		if ((a == null) || (b == null))
		{
			return null;
		}
		final String ka = allianceKeyOf(a);
		final String kb = allianceKeyOf(b);
		if (ka.equals(kb))
		{
			return null; // same alliance: kept out on purpose (user, 2026-10-21)
		}
		return (ka.compareTo(kb) < 0) ? (ka + "+" + kb) : (kb + "+" + ka);
	}

	int grievanceCount(ClanProfile a, ClanProfile b)
	{
		final String key = grievanceKey(a, b);
		final Integer count = (key != null) ? _grievances.get(key) : null;
		return (count != null) ? count.intValue() : 0;
	}

	/** Epic chance for a duel between these clans: base + step per grievance, capped. */
	int epicChanceFor(ClanProfile a, ClanProfile b)
	{
		if (!_allianceWars)
		{
			return _epicChance;
		}
		return Math.min(Math.max(_epicChance, _epicChanceCap), _epicChance + (_grievanceStep * grievanceCount(a, b)));
	}

	/**
	 * A quarrel between two alliances ended with nothing settled - no peace,
	 * no steel. The grudge grows; at AllianceWarAt the next quarrel between
	 * them drags both alliances in and ends in an EPIC battle.
	 */
	private void addGrievance(ClanProfile a, ClanProfile b, String why)
	{
		if (!_allianceWars)
		{
			return;
		}
		final String key = grievanceKey(a, b);
		if (key == null)
		{
			return;
		}
		final int count = _grievances.merge(key, Integer.valueOf(1), (x, y) -> Integer.valueOf(x.intValue() + y.intValue())).intValue();
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [GRIEVANCE] " + key.replace("+", " vs ") + ": " + count + " (" + clanNameOf(a) + " and " + clanNameOf(b) + " - " + why + "); epic chance between them now " + epicChanceFor(a, b) + "%" + ((count >= _allianceWarAt) ? " - an ALLIANCE WAR is brewing: their next quarrel drags both alliances in." : "."));
		}
		saveRecords();
	}

	/** Play-time housekeeping: stale war cries turn into grievances, and grievances fade. */
	private void grievanceTick(long now)
	{
		final long last = _grievanceLastTick;
		_grievanceLastTick = now;
		if ((last > 0) && ((now - last) < 600000L))
		{
			_grievanceClock += (now - last);
		}
		for (Map.Entry<String, Long> e : _pendingWarCries.entrySet())
		{
			if (now >= e.getValue().longValue())
			{
				_pendingWarCries.remove(e.getKey());
				final String[] ids = e.getKey().split("-");
				try
				{
					addGrievance(_profiles.get(Integer.valueOf(Integer.parseInt(ids[0]))), _profiles.get(Integer.valueOf(Integer.parseInt(ids[1]))), "a war cry nobody answered with steel");
				}
				catch (Exception ex)
				{
					// a pair we can't read just drops
				}
			}
		}
		if ((_grievanceClock >= _grievanceFadeMillis) && (_grievanceFadeMillis > 0))
		{
			_grievanceClock -= _grievanceFadeMillis;
			boolean changed = false;
			for (Map.Entry<String, Integer> g : _grievances.entrySet())
			{
				final int next = g.getValue().intValue() - 1;
				if (next <= 0)
				{
					_grievances.remove(g.getKey());
				}
				else
				{
					g.setValue(Integer.valueOf(next));
				}
				changed = true;
			}
			if (changed)
			{
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [GRIEVANCE] time cools tempers - every grievance fades by one" + (_grievances.isEmpty() ? " (none left)." : (": " + grievanceText() + ".")));
				}
				saveRecords();
			}
		}
	}

	/** "DragonPact vs PhoenixOrder 3, ..." for logs and .clanp. */
	String grievanceText()
	{
		final List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> g : _grievances.entrySet())
		{
			parts.add(g.getKey().replace("+", " vs ") + " " + g.getValue() + (_warDue.contains(g.getKey()) ? " (WAR DUE)" : ""));
		}
		for (String due : _warDue)
		{
			if (!_grievances.containsKey(due))
			{
				parts.add(due.replace("+", " vs ") + " (WAR DUE)");
			}
		}
		return parts.isEmpty() ? "none" : String.join(", ", parts);
	}

	/** Test command: set the grievance between two clans' sides. */
	String setGrievance(ClanProfile a, ClanProfile b, int count)
	{
		final String key = grievanceKey(a, b);
		if (key == null)
		{
			return null;
		}
		if (count <= 0)
		{
			_grievances.remove(key);
		}
		else
		{
			_grievances.put(key, Integer.valueOf(count));
		}
		saveRecords();
		return key.replace("+", " vs ");
	}

	int allianceWarAt()
	{
		return _allianceWarAt;
	}

	void setAllianceWars(boolean enabled)
	{
		_allianceWars = enabled;
	}

	boolean allianceWarsOn()
	{
		return _allianceWars;
	}

	void setGrievanceRules(int step, int cap, int warAt, int fadeHours, int warCryMinutes, int allyLines)
	{
		_grievanceStep = Math.max(0, Math.min(50, step));
		_epicChanceCap = Math.max(0, Math.min(100, cap));
		_allianceWarAt = Math.max(1, Math.min(50, warAt));
		_grievanceFadeMillis = Math.max(0, Math.min(72, fadeHours)) * 3600000L;
		_warCryWindowMillis = Math.max(1, Math.min(240, warCryMinutes)) * 60000L;
		_warAllyLines = Math.max(0, Math.min(8, allyLines));
	}

	private static String clean(String text)
	{
		return (text == null) ? "" : text.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
	}

	private PairRecord recordOf(int a, int b, boolean create)
	{
		final String key = arcKey(a, b);
		PairRecord r = _records.get(key);
		if ((r == null) && create)
		{
			r = new PairRecord();
			_records.put(key, r);
		}
		return r;
	}

	/** A COMPLETED duel: winnerId 0 = a clean draw. Saved at once. */
	private void recordDuel(int clanA, int clanB, int winnerId, String town)
	{
		final PairRecord r = recordOf(clanA, clanB, true);
		final int low = Math.min(clanA, clanB);
		if (winnerId == 0)
		{
			r.draws++;
		}
		else if (winnerId == low)
		{
			r.winsLow++;
		}
		else
		{
			r.winsHigh++;
		}
		r.lastWinner = winnerId;
		r.lastDuelAt = System.currentTimeMillis();
		r.lastDuelTown = (town != null) ? town : "";
		saveRecords();
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [RECORD] " + clanNameOf(_profiles.get(clanA)) + " vs " + clanNameOf(_profiles.get(clanB)) + ": " + ((winnerId == 0) ? "a draw" : (clanNameOf(_profiles.get(winnerId)) + " won")) + " outside " + town + " - saved (" + winsOf(r, clanA, clanB) + "-" + winsOf(r, clanB, clanA) + "-" + r.draws + ").");
		}
	}

	/** How a quarrel closed (peace or a war cry), remembered for next time. */
	private void recordEnding(int clanA, int clanB, boolean fight, String cause)
	{
		if ((clanA <= 0) || (clanB <= 0) || (clanA == clanB))
		{
			return;
		}
		final PairRecord r = recordOf(clanA, clanB, true);
		r.lastEnding = fight ? "fight" : "peace";
		r.lastEndingAt = System.currentTimeMillis();
		r.lastCause = (cause != null) ? cause : "";
		saveRecords();
	}

	/** Wins of clanId in its record against otherId. */
	private static int winsOf(PairRecord r, int clanId, int otherId)
	{
		return (clanId < otherId) ? r.winsLow : r.winsHigh;
	}

	/** True when this clan lost its last duel to that one and its temper
	 *  is not Peaceful: it wants the score settled. */
	private boolean wantsRevenge(ClanProfile self, ClanProfile other)
	{
		if ((self == null) || (other == null) || (self.getPersona() == PersonaType.PEACEFUL))
		{
			return false;
		}
		final PairRecord r = _records.get(arcKey(self.getId(), other.getId()));
		return (r != null) && (r.lastWinner == other.getId());
	}

	private static String ago(long at)
	{
		if (at <= 0)
		{
			return "some time ago";
		}
		final long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60000L);
		if (minutes < 90)
		{
			return "earlier today";
		}
		if (minutes < (60 * 30))
		{
			return "about " + (minutes / 60) + " hours ago";
		}
		final long days = minutes / (60 * 24);
		return (days <= 1) ? "yesterday" : (days + " days ago");
	}

	/** The pair's shared past, told from one clan's side, for prompts. */
	private String historyNote(ClanProfile self, ClanProfile other)
	{
		if ((self == null) || (other == null))
		{
			return null;
		}
		final PairRecord r = _records.get(arcKey(self.getId(), other.getId()));
		if (r == null)
		{
			return null;
		}
		final boolean selfLow = self.getId() < other.getId();
		final int won = selfLow ? r.winsLow : r.winsHigh;
		final int lost = selfLow ? r.winsHigh : r.winsLow;
		final StringBuilder note = new StringBuilder();
		if ((won + lost + r.draws) > 0)
		{
			note.append("Your clans have met in field duels before: your clan won ").append(won).append(", lost ").append(lost);
			if (r.draws > 0)
			{
				note.append(", ").append(r.draws).append(" ended even");
			}
			note.append(". The last one, outside ").append(r.lastDuelTown.isEmpty() ? "town" : r.lastDuelTown).append(" ").append(ago(r.lastDuelAt)).append(", ");
			note.append((r.lastWinner == self.getId()) ? "YOUR clan won." : ((r.lastWinner == other.getId()) ? "THEY won." : "ended even."));
			if (wantsRevenge(self, other))
			{
				note.append(" Your clan still wants REVENGE for that defeat.");
			}
			else if (r.lastWinner == self.getId())
			{
				note.append(" You can rub that win in.");
			}
		}
		if (!r.lastEnding.isEmpty())
		{
			if (note.length() > 0)
			{
				note.append(' ');
			}
			note.append("Your last quarrel with them ").append(ago(r.lastEndingAt)).append(" ended ").append("fight".equals(r.lastEnding) ? "with a war cry" : "in a truce").append(r.lastCause.isEmpty() ? "" : (" (it was about " + r.lastCause + ")")).append(" - you may bring that up.");
		}
		return (note.length() > 0) ? note.toString() : null;
	}

	// ── Drama focus ──────────────────────────────────────────────────────

	/**
	 * True while ANOTHER quarrel is still developing (a beat within the
	 * focus window) or the stage is still catching its breath after one
	 * resolved. ownPairKey (may be null) is the pair asking: its own
	 * argument never blocks itself.
	 */
	private boolean dramaFocusHolds(long now, String ownPairKey)
	{
		if (!_dramaFocus)
		{
			return false;
		}
		if (now < _focusBreatherUntil)
		{
			return true;
		}
		for (Map.Entry<String, DramaArc> entry : _arcs.entrySet())
		{
			if ((ownPairKey != null) && entry.getKey().equals(ownPairKey))
			{
				continue;
			}
			if ((now - entry.getValue().lastBeatMillis) < _focusStaleMillis)
			{
				return true;
			}
		}
		return false;
	}

	/** A quarrel closed: the stage breathes, then held openings resume. */
	private void focusReleased(long now)
	{
		if (!_dramaFocus)
		{
			return;
		}
		_focusBreatherUntil = now + _focusBreatherMillis;
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [FOCUS] a quarrel closed - held openings resume in ~" + (_focusBreatherMillis / 1000) + "s once no other argument is still running.");
		}
	}

	// ── Taunts: hot blood goads others toward the field ──────────────────

	/**
	 * One Aggressive or Player-Killing clan (of the tick's candidates) may
	 * taunt another clan toward a real field fight. Each clan rests
	 * TauntCooldownMinutes between its own taunts, and the whole world
	 * rests TauntGlobalCooldownMinutes between any two. Returns the
	 * taunting clan's id, or 0 when nobody taunted.
	 */
	private int tryTaunt(long now, List<ClanProfile> candidates)
	{
		if ((_tauntChance <= 0) || (now < _nextTauntMillis))
		{
			return 0;
		}
		for (ClanProfile profile : candidates)
		{
			final PersonaType persona = profile.getPersona();
			if ((persona != PersonaType.AGGRESSIVE) && (persona != PersonaType.PLAYER_KILLING))
			{
				continue;
			}
			final Long ready = _tauntReadyAt.get(Integer.valueOf(profile.getId()));
			if ((ready != null) && (now < ready.longValue()))
			{
				continue;
			}
			int chance = _tauntChance;
			if (hasAnyGrudge(profile.getId()))
			{
				chance = Math.min(100, (chance * 3) / 2); // old anger itches
			}
			if (Rnd.get(100) >= chance)
			{
				continue;
			}
			final ClanProfile target = pickTauntTarget(profile, now);
			if (target == null)
			{
				continue;
			}
			final String key = arcKey(profile.getId(), target.getId());
			final Long busy = _pendingStarts.get(key);
			if ((busy != null) && (now < busy.longValue()))
			{
				continue;
			}
			_pendingStarts.put(key, Long.valueOf(now + _openingMaxMillis + 180000L));
			final long delay = openingDelay();
			_queue.schedule(profile.getId(), MessageKind.TAUNT, target.getId(), delay);
			final long rest = _tauntCooldownMillis + Rnd.get((int) Math.max(1, Math.min(Integer.MAX_VALUE, _tauntCooldownMillis / 4)));
			_tauntReadyAt.put(Integer.valueOf(profile.getId()), Long.valueOf(now + rest));
			_nextTauntMillis = now + _tauntGlobalCooldownMillis;
			profile.cooldown(now + _clanCooldownMillis + Rnd.get((int) (_clanCooldownMillis / 4)));
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [TAUNT] queued " + clanNameOf(profile) + " goading " + clanNameOf(target) + " toward a field fight in ~" + (delay / 1000) + " s (" + clanNameOf(profile) + " taunts again in ~" + (rest / 60000) + " min; world taunt rest " + (_tauntGlobalCooldownMillis / 60000) + " min).");
			}
			return profile.getId();
		}
		return 0;
	}

	/** Who to goad: never an alliance brother, never a resting pair or a
	 *  clan already out in a duel; old grudges are three times as tempting. */
	private ClanProfile pickTauntTarget(ClanProfile self, long now)
	{
		final List<ClanProfile> weighted = new ArrayList<>();
		for (ClanProfile other : _profiles.values())
		{
			if ((other.getId() == self.getId()) || (other.getPersona() == null) || pairAtRest(self.getId(), other.getId()) || self.isAlliedWith(other))
			{
				continue;
			}
			if (_requireMembersPresent && !other.isPresent())
			{
				continue;
			}
			final Long lock = _clanFightLocks.get(Integer.valueOf(other.getId()));
			if ((lock != null) && (now < lock.longValue()))
			{
				continue;
			}
			final int weight = (_memory.argued(self.getId(), other.getId(), _grudgeWindowMillis) ? 3 : 1) * (wantsRevenge(self, other) ? _revengeBoost : 1);
			for (int i = 0; i < weight; i++)
			{
				weighted.add(other);
			}
		}
		return weighted.isEmpty() ? null : weighted.get(Rnd.get(weighted.size()));
	}

	// ── Reading intent: threat, provocation, or a joke? ──────────────────

	private static String dirKey(int fromClanId, int toClanId)
	{
		return fromClanId + ">" + toClanId;
	}

	/** Cue phrases as patterns: word-bounded for plain words, literal for
	 *  emoticons like ":P" (matched case-insensitively). */
	private static List<Pattern> compileCues(List<String> cues)
	{
		final List<Pattern> patterns = new ArrayList<>();
		if (cues == null)
		{
			return patterns;
		}
		for (String cue : cues)
		{
			if ((cue == null) || cue.trim().isEmpty())
			{
				continue;
			}
			final String c = cue.trim().toLowerCase();
			final boolean wordy = c.matches("[a-z0-9' ]+");
			patterns.add(Pattern.compile(wordy ? ("(?<![a-z0-9])" + Pattern.quote(c) + "(?![a-z0-9])") : Pattern.quote(c)));
		}
		return patterns;
	}

	private static boolean anyCue(List<Pattern> cues, String lower)
	{
		for (Pattern cue : cues)
		{
			if (cue.matcher(lower).find())
			{
				return true;
			}
		}
		return false;
	}

	/** Threat words with a laugh attached read as AMBIGUOUS; bare threat
	 *  words as THREAT; insults without threat as PROVOCATION; else null. */
	private ReadType classifyLine(String text)
	{
		if (text == null)
		{
			return null;
		}
		final String lower = text.toLowerCase().replace('’', '\'');
		final boolean threat = anyCue(_threatCues, lower);
		final boolean banter = anyCue(_banterCues, lower);
		if (threat)
		{
			return banter ? ReadType.AMBIGUOUS : ReadType.THREAT;
		}
		return anyCue(_provokeCues, lower) ? ReadType.PROVOCATION : null;
	}

	/**
	 * The addressed clan reads a line aimed at it. Open threats heat the
	 * pot; trash talk is taken as provocation; a line that could be either
	 * may earn a "do you mean it?" - the speaker then confirms or waves it
	 * off, decided by its temperament, and the answer shapes the quarrel.
	 */
	private void readLine(ClanProfile speaker, ClanProfile target, MessageKind kind, String text, long now)
	{
		if (!_readIntent)
		{
			return;
		}
		final ReadType type = classifyLine(text);
		if (type == null)
		{
			return;
		}
		final Reading reading = new Reading(type, text, now);
		_readings.put(dirKey(speaker.getId(), target.getId()), reading);
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [READ] " + clanNameOf(target) + " reads " + clanNameOf(speaker) + "'s " + kind + " as " + type + ": \"" + text + "\"");
		}
		final String pairKey = arcKey(speaker.getId(), target.getId());
		final DramaArc arc = _arcs.get(pairKey);
		if (type == ReadType.THREAT)
		{
			noteBicker(speaker.getId(), target.getId()); // open threats stir the pot
			return;
		}
		if ((type != ReadType.AMBIGUOUS) || (arc == null) || (kind == MessageKind.ASIDE) || (now < arc.intentHoldUntil))
		{
			return;
		}
		final Long gate = _intentAskGates.get(pairKey);
		if ((gate != null) && (now < gate.longValue()))
		{
			return;
		}
		if (resolveMembers(target.getId()).isEmpty())
		{
			return;
		}
		final int askChance = (_threatClarifyChance * intentAskFactor(target.getPersona())) / 100;
		if (Rnd.get(100) >= askChance)
		{
			return;
		}
		reading.confirm = Rnd.get(100) < intentConfirmChance(speaker.getPersona());
		arc.intentHoldUntil = now + 90000L;
		_intentAskGates.put(pairKey, Long.valueOf(now + _threatClarifyCooldownMillis));
		final int rank = rollBeatRank(0);
		_queue.schedule(target.getId(), MessageKind.INTENT_ASK, speaker.getId(), 5000 + Rnd.get(7000), rank);
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [READ-ASK] " + clanNameOf(target) + " can't tell if " + clanNameOf(speaker) + " means it - asking (" + clanNameOf(speaker) + " will " + (reading.confirm ? "CONFIRM the threat" : "wave it off") + ").");
		}
	}

	/** Cooler heads ask before they assume; hot ones just take it as a threat. */
	private static int intentAskFactor(PersonaType persona)
	{
		return (persona == PersonaType.PEACEFUL) ? 120
			: (persona == PersonaType.NEUTRAL) ? 110
			: (persona == PersonaType.ALTRUISTIC) ? 100
			: (persona == PersonaType.AGGRESSIVE) ? 35
			: (persona == PersonaType.PLAYER_KILLING) ? 25
			: 100;
	}

	/** Asked "do you mean it?", how likely each temperament says yes. */
	private static int intentConfirmChance(PersonaType persona)
	{
		return (persona == PersonaType.PLAYER_KILLING) ? 85
			: (persona == PersonaType.AGGRESSIVE) ? 70
			: (persona == PersonaType.NEUTRAL) ? 40
			: (persona == PersonaType.ALTRUISTIC) ? 25
			: (persona == PersonaType.PEACEFUL) ? 10
			: 40;
	}

	/** The speaker's answer lands: the threat stands (the pot heats) or is
	 *  waved off (the pot cools, the quarrel winds down sooner). */
	private void settleIntent(int answererId, int askerId, ClanProfile answerer, ClanProfile asker, long now)
	{
		final DramaArc arc = _arcs.get(arcKey(answererId, askerId));
		final Reading reading = _readings.get(dirKey(answererId, askerId));
		if (arc != null)
		{
			arc.intentHoldUntil = 0;
		}
		if (reading == null)
		{
			return;
		}
		if (reading.confirm)
		{
			reading.type = ReadType.CONFIRMED;
			noteBicker(answererId, askerId);
			noteBicker(answererId, askerId);
			if (arc != null)
			{
				arc.confirmed = true;
			}
		}
		else
		{
			reading.type = ReadType.DEFUSED;
			final long[] brew = _brew.get(arcKey(answererId, askerId));
			if (brew != null)
			{
				brew[0] = Math.max(0, brew[0] - 2);
			}
			if (arc != null)
			{
				arc.defused = true;
			}
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [READ-" + (reading.confirm ? "CONFIRM" : "DEFUSE") + "] " + clanNameOf(answerer) + " " + (reading.confirm ? "stands by the threat - the pot heats" : "waves it off as trash talk - the pot cools") + " (vs " + clanNameOf(asker) + ").");
		}
	}

	/**
	 * What the brain needs to know about intent for this line: [0] a full
	 * situation for the ask/answer kinds, [1] a note on how the clan READ
	 * the other's last words (threat, provocation, confirmed, waved off),
	 * plus whether the other side dared it to a field fight.
	 */
	private String[] intentContext(ClanProfile profile, MessageKind kind, ClanProfile other)
	{
		final String[] result = new String[2];
		if (other == null)
		{
			return result;
		}
		final String otherName = clanNameOf(other);
		if (kind == MessageKind.INTENT_ASK)
		{
			final Reading heard = _readings.get(dirKey(other.getId(), profile.getId()));
			result[0] = "The clan " + otherName + " just said this to your clan in world chat: \"" + ((heard != null) ? heard.text : "something that sounded like a threat") + "\". You honestly cannot tell whether that was a real threat or just trash talk. Ask them in ONE short line whether they actually mean it.";
			return result;
		}
		if (kind == MessageKind.INTENT_ANSWER)
		{
			final Reading said = _readings.get(dirKey(profile.getId(), other.getId()));
			final String yours = (said != null) ? ("Your clan said: \"" + said.text + "\". ") : "";
			result[0] = yours + "The clan " + otherName + " just asked whether you meant that as a real threat. " + (((said != null) && said.confirm)
				? "You DID mean it: confirm it in ONE short line - cold or heated, by your temperament, no backing down."
				: "You did NOT mean it as a real threat: say it was just trash talk in ONE short line - laugh it off, shrug, or grudgingly ease off, by your temperament.");
			return result;
		}
		final StringBuilder note = new StringBuilder();
		final Reading heard = _readings.get(dirKey(other.getId(), profile.getId()));
		if ((heard != null) && ((System.currentTimeMillis() - heard.atMillis) < READING_FRESH_MILLIS))
		{
			switch (heard.type)
			{
				case THREAT:
				{
					note.append("Their last words to your clan were an open THREAT - your clan takes it as one.");
					break;
				}
				case PROVOCATION:
				{
					note.append("Their last words were trash talk meant to rile your clan up, not a real threat - your clan reads it as provocation.");
					break;
				}
				case AMBIGUOUS:
				{
					note.append("Their last words could be a threat or a joke; your clan reads them by its temperament.");
					break;
				}
				case CONFIRMED:
				{
					note.append("Asked whether they meant it, they CONFIRMED it was a real threat.");
					break;
				}
				case DEFUSED:
				{
					note.append("Asked whether they meant it, they said it was only trash talk - the tension eased a little.");
					break;
				}
			}
		}
		final DramaArc arc = _arcs.get(arcKey(profile.getId(), other.getId()));
		if ((arc != null) && (arc.tauntBy == other.getId()))
		{
			if (note.length() > 0)
			{
				note.append(' ');
			}
			note.append("They are daring your clan to send fighters to a real fight in the field outside town - accept, mock or refuse it, by your temperament.");
		}
		// The pair's shared past: openings always, replies now and then.
		final boolean opening = (kind == MessageKind.START) || (kind == MessageKind.TAUNT) || (kind == MessageKind.QUESTION) || (kind == MessageKind.EXPLAIN);
		if (opening || ((kind == MessageKind.RESPONSE) && (Rnd.get(100) < 40)))
		{
			final String history = historyNote(profile, other);
			if (history != null)
			{
				if (note.length() > 0)
				{
					note.append(' ');
				}
				note.append(history);
			}
		}
		result[1] = (note.length() > 0) ? note.toString() : null;
		return result;
	}

	// ── One-time cleanup: fighter clones saved by the old release path ──

	/**
	 * Before 2026-10-19 every released fighter, herald and fan was COOLED,
	 * which saved the clone as a real character. This finds those leftovers
	 * and (in delete mode) removes them with the game's own full character
	 * delete. A row is only a candidate when ALL of these hold:
	 *   - it belongs to the pack's internal "phantom" account (players'
	 *     own characters live on their own login accounts - never touched);
	 *   - its name is the name of a town bot standing in the world right
	 *     now (that is exactly what a fight clone is);
	 *   - it is NOT a Living Population bot (those are kept, whatever their
	 *     name);
	 *   - it is not online at this moment.
	 * Preview mode only lists what it would delete. Run on the user's
	 * explicit request; Off by default.
	 */
	void purgeSavedClones(boolean delete)
	{
		final Set<String> townNames = new HashSet<>();
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (object instanceof Npc)
			{
				final Npc npc = (Npc) object;
				if (npc.isFakePlayer() && (npc.getFakePlayerAppearance() != null) && (npc.getFakePlayerAppearance().getName() != null))
				{
					townNames.add(npc.getFakePlayerAppearance().getName().toLowerCase());
				}
			}
		}
		final String mode = delete ? "DELETE" : "PREVIEW";
		if (townNames.isEmpty())
		{
			announce("[PURGE-" + mode + "] no town bots are in the world yet - nothing checked; it runs again next boot.");
			return;
		}
		final List<Object[]> rows = new ArrayList<>();
		int phantomRows = 0;
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT charId, char_name, level FROM characters WHERE account_name = ?"))
		{
			ps.setString(1, "phantom");
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					phantomRows++;
					rows.add(new Object[] { Integer.valueOf(rs.getInt("charId")), rs.getString("char_name"), Integer.valueOf(rs.getInt("level")) });
				}
			}
		}
		catch (Exception e)
		{
			announce("[PURGE-" + mode + "] could not read the characters table (" + e + ") - nothing was changed.");
			return;
		}
		int candidates = 0;
		int deleted = 0;
		int keptPopulation = 0;
		int keptOnline = 0;
		for (Object[] row : rows)
		{
			final int charId = ((Integer) row[0]).intValue();
			final String name = (String) row[1];
			if ((name == null) || !townNames.contains(name.toLowerCase()))
			{
				continue; // not a town bot's name: not a fight clone
			}
			boolean population;
			try
			{
				population = LivingPopulationManager.getInstance().hasBotNamed(name);
			}
			catch (Exception e)
			{
				population = true; // can't tell: keep it
			}
			if (population)
			{
				keptPopulation++;
				announce("[PURGE-" + mode + "] keeping '" + name + "' (charId " + charId + ") - it is a Living Population bot.");
				continue;
			}
			if (World.getInstance().getPlayer(charId) != null)
			{
				keptOnline++;
				announce("[PURGE-" + mode + "] keeping '" + name + "' (charId " + charId + ") - it is online right now.");
				continue;
			}
			candidates++;
			if (!delete)
			{
				announce("[PURGE-PREVIEW] would delete '" + name + "' (charId " + charId + ", level " + row[2] + ", account phantom) - a saved fight clone of a town bot.");
				continue;
			}
			try
			{
				GameClient.deleteCharByObjId(charId);
				deleted++;
				announce("[PURGE-DELETE] deleted '" + name + "' (charId " + charId + ", level " + row[2] + ").");
			}
			catch (Exception e)
			{
				announce("[PURGE-DELETE] could not delete '" + name + "' (charId " + charId + "): " + e);
			}
		}
		announce("[PURGE-" + mode + "] done: " + phantomRows + " character(s) on the internal phantom account checked; " + candidates + " saved fight clone(s) found" + (delete ? (", " + deleted + " deleted") : " (nothing deleted - preview)") + "; kept " + keptPopulation + " population bot(s) and " + keptOnline + " online. Characters on any other account were not read or touched.");
	}

	/**
	 * READ-ONLY: looks up, by exact name, each town bot standing in the
	 * world and lists any saved character carrying that name, with its
	 * account and whether it is a Living Population bot. Changes nothing.
	 */
	void listSavedClones()
	{
		final Set<String> townNames = new HashSet<>();
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if ((object instanceof Npc) && ((Npc) object).isFakePlayer() && (((Npc) object).getFakePlayerAppearance() != null) && (((Npc) object).getFakePlayerAppearance().getName() != null))
			{
				townNames.add(((Npc) object).getFakePlayerAppearance().getName());
			}
		}
		if (townNames.isEmpty())
		{
			announce("[CLONE-LIST] no town bots are in the world yet - nothing looked up.");
			return;
		}
		final Map<String, Integer> byAccount = new LinkedHashMap<>();
		int found = 0;
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT charId, char_name, account_name, level FROM characters WHERE char_name = ?"))
		{
			for (String name : townNames)
			{
				ps.setString(1, name);
				try (ResultSet rs = ps.executeQuery())
				{
					while (rs.next())
					{
						found++;
						final String account = rs.getString("account_name");
						byAccount.merge(String.valueOf(account), Integer.valueOf(1), (x, y) -> Integer.valueOf(x.intValue() + y.intValue()));
						boolean population;
						try
						{
							population = LivingPopulationManager.getInstance().hasBotNamed(name);
						}
						catch (Exception e)
						{
							population = true;
						}
						announce("[CLONE-LIST] saved '" + rs.getString("char_name") + "' (charId " + rs.getInt("charId") + ", level " + rs.getInt("level") + ", account " + account + ")" + (population ? " - a Living Population bot" : "") + (World.getInstance().getPlayer(rs.getInt("charId")) != null ? " - online now" : "") + ".");
					}
				}
			}
		}
		catch (Exception e)
		{
			announce("[CLONE-LIST] could not read the characters table (" + e + ").");
			return;
		}
		announce("[CLONE-LIST] done: " + townNames.size() + " town-bot name(s) looked up; " + found + " saved character(s) share such a name, by account " + byAccount + ". Nothing was changed.");
	}

	// ── Settings for focus, taunts and intent reading ────────────────────

	void setDramaFocus(boolean focus, long staleMillis, long breatherMillis)
	{
		_dramaFocus = focus;
		_focusStaleMillis = Math.max(30000L, Math.min(600000L, staleMillis));
		_focusBreatherMillis = Math.max(0L, Math.min(600000L, breatherMillis));
	}

	void setTaunts(int chance, long cooldownMillis, long globalCooldownMillis)
	{
		_tauntChance = Math.max(0, Math.min(100, chance));
		_tauntCooldownMillis = Math.max(60000L, Math.min(7200000L, cooldownMillis));
		_tauntGlobalCooldownMillis = Math.max(0L, Math.min(7200000L, globalCooldownMillis));
	}

	void setIntentReading(boolean enabled, int clarifyChance, long clarifyCooldownMillis, List<String> threatCues, List<String> banterCues, List<String> provokeCues)
	{
		_readIntent = enabled;
		_threatClarifyChance = Math.max(0, Math.min(100, clarifyChance));
		_threatClarifyCooldownMillis = Math.max(30000L, Math.min(3600000L, clarifyCooldownMillis));
		_threatCues.clear();
		_threatCues.addAll(compileCues(threatCues));
		_banterCues.clear();
		_banterCues.addAll(compileCues(banterCues));
		_provokeCues.clear();
		_provokeCues.addAll(compileCues(provokeCues));
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
				if (wantsRevenge(self, profile))
				{
					for (int i = 1; i < _revengeBoost; i++)
					{
						options.add(profile); // a lost duel keeps pulling
					}
				}
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
		if (profile == null)
		{
			return _unknownClanName;
		}
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
	 * Picks the duel arena: on open ground just OUTSIDE the boundary of a
	 * town anyone can teleport to, so anyone strolling town or nearby can
	 * walk out and watch the fight. Every town in the world is a candidate:
	 * Giran first when in reach, then the teleport towns (a Gatekeeper
	 * stands inside) nearest the quarreling pair, then any other town zone.
	 * Towns are tried one after another until one offers a clean piste.
	 * Returns null only when no town in the whole world does.
	 */
	private FieldArena fieldArena(int aggroX, int aggroY, int aggroZ, int rivalX, int rivalY, int rivalZ, double avgLevel, int minLevel)
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
			if (!isHumanWatcher(null, online))
			{
				continue; // bots stand in every town; only a human closes one
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
		// ONE pass over the world for everything the siting needs: the
		// Gatekeepers (teleport towns), the standing guards, and the
		// aggressive monsters. Every probe below then measures against these
		// short lists instead of re-walking the whole world object pool.
		final List<Npc> keepers = new ArrayList<>();
		final List<Guard> guards = new ArrayList<>();
		final List<WorldObject> aggro = new ArrayList<>();
		final List<Monster> wild = new ArrayList<>();
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (object instanceof Guard)
			{
				guards.add((Guard) object);
			}
			else if (object instanceof Monster)
			{
				final Monster monster = (Monster) object;
				if (monster.isDead())
				{
					continue;
				}
				if (monster.isAggressive())
				{
					aggro.add(object);
				}
				if (!monster.isRaid() && !monster.isMinion())
				{
					wild.add(monster); // raid bosses stay home; the wildlife roams
				}
			}
			else if (object instanceof Npc)
			{
				final String npcName = ((Npc) object).getName();
				if ((npcName != null) && npcName.toLowerCase().contains("gatekeeper"))
				{
					keepers.add((Npc) object);
				}
			}
		}
		// Candidate towns, best first.
		final List<TownZone> candidates = new ArrayList<>();
		final Map<TownZone, Double> distances = new LinkedHashMap<>();
		final Map<TownZone, Boolean> teleport = new LinkedHashMap<>();
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
			boolean hasKeeper = false;
			for (Npc keeper : keepers)
			{
				if (zone.isInsideZone(keeper.getX(), keeper.getY(), keeper.getZ()))
				{
					hasKeeper = true;
					break;
				}
			}
			candidates.add(zone);
			distances.put(zone, Double.valueOf(distance));
			teleport.put(zone, Boolean.valueOf(hasKeeper));
		}
		if (candidates.isEmpty())
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-SCAN] arena refused: no town zone found.");
			}
			return null;
		}
		// Order (user, 2026-10-21): a town whose level band PREFERS this
		// duel -> a town whose band accepts it -> towns with no band (the
		// monster scan judges them) -> the wildcard starting villages;
		// teleport towns before the rest inside each step, nearest first;
		// watched towns after every unwatched one. A town whose band does
		// not fit the duel is never used.
		final Map<TownZone, Integer> bandRank = new LinkedHashMap<>();
		for (Iterator<TownZone> it = candidates.iterator(); it.hasNext();)
		{
			final TownZone zone = it.next();
			final int rank = bandRank(zone, avgLevel);
			if (rank < 0)
			{
				it.remove();
				continue;
			}
			bandRank.put(zone, Integer.valueOf(rank));
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [FIELD-SCAN] duel level: average " + Math.round(avgLevel) + ", lowest fighter " + minLevel + " - " + candidates.size() + " town(s) fit.");
		}
		candidates.sort((a, b) ->
		{
			final int ra = townRank(a, bandRank.get(a).intValue(), watching, teleport);
			final int rb = townRank(b, bandRank.get(b).intValue(), watching, teleport);
			if (ra != rb)
			{
				return Integer.compare(ra, rb);
			}
			return Double.compare(distances.get(a).doubleValue(), distances.get(b).doubleValue());
		});
		final Collection<WaterZone> waters = ZoneManager.getInstance().getAllZones(WaterZone.class);
		int tried = 0;
		for (TownZone chosen : candidates)
		{
			if ((_arenaTownTries > 0) && (tried >= _arenaTownTries))
			{
				break;
			}
			tried++;
			if (watching.contains(chosen) && (_infoHook != null))
			{
				_infoHook.accept("clan-personalities: [FIELD-SCAN] every clean town was tried - falling back to watched " + chosen.getName() + ".");
			}
			final int[] tally = new int[9]; // probes, town, water, unwalkable, guard, monster, blocked spot, cliff edge, wildlife too strong
			final FieldArena arena = siteArena(chosen, zones, waters, keepers, guards, aggro, wild, minLevel, tally);
			if (arena != null)
			{
				if ((tried > 1) && (_infoHook != null))
				{
					_infoHook.accept("clan-personalities: [FIELD-SCAN] " + chosen.getName() + " hosts the duel (town #" + tried + " tried).");
				}
				return arena;
			}
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-SCAN] no clean piste at " + chosen.getName() + (Boolean.TRUE.equals(teleport.get(chosen)) ? "" : " (no Gatekeeper)") + ": " + tally[0] + " spots probed - " + tally[1] + " inside a town, " + tally[2] + " wet or by the sea, " + tally[3] + " unwalkable, " + tally[4] + " near a guard (<" + _guardClearRadius + "), " + tally[5] + " near an aggressive monster (<" + _monsterClearRadius + ")" + ((tally[6] > 0) ? (", " + tally[6] + " inside an ArenaBlockedSpots circle") : "") + ((tally[7] > 0) ? (", " + tally[7] + " at a cliff edge") : "") + ((tally[8] > 0) ? (", " + tally[8] + " with wildlife over level " + (minLevel + _wildLevelGap) + " around") : "") + ".");
			}
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [FIELD-SCAN] arena refused: none of the " + tried + " town(s) tried had a clean piste.");
		}
		return null;
	}

	/** Town order for arena siting: the band step (0 prefer, 1 accept,
	 *  2 no band, 3 wildcard) twice over - teleport towns first inside a
	 *  step - and +8 for any town a watcher stands in. */
	private static int townRank(TownZone zone, int band, Set<TownZone> watching, Map<TownZone, Boolean> teleport)
	{
		final int base = (band * 2) + (Boolean.TRUE.equals(teleport.get(zone)) ? 0 : 1);
		return watching.contains(zone) ? (base + 8) : base;
	}

	/** Closest distance from a point to any object of a short list; MAX when empty. */
	private static int nearestOf(List<? extends WorldObject> objects, int x, int y)
	{
		long bestSq = Long.MAX_VALUE;
		for (WorldObject object : objects)
		{
			final long dx = object.getX() - x;
			final long dy = object.getY() - y;
			final long distSq = (dx * dx) + (dy * dy);
			if (distSq < bestSq)
			{
				bestSq = distSq;
			}
		}
		return (bestSq == Long.MAX_VALUE) ? Integer.MAX_VALUE : (int) Math.sqrt(bestSq);
	}

	/**
	 * One piste test, tallied: 0 = ok, else the veto reason index of the
	 * tally (2 water, 4 guard, 5 monster). Both fighter spots (the point and
	 * its side offset) must be clean.
	 */
	private int pisteVeto(Collection<WaterZone> waters, List<Guard> guards, List<WorldObject> aggro, List<Monster> wild, int minLevel, int x, int y, int z, int sx, int sy)
	{
		if (waterAt(waters, x, y, z) || waterAt(waters, x + sx, y + sy, z) || seaNear(x, y, z) || seaNear(x + sx, y + sy, z))
		{
			return 2;
		}
		if (Math.min(nearestOf(guards, x, y), nearestOf(guards, x + sx, y + sy)) < _guardClearRadius)
		{
			return 4;
		}
		if (Math.min(nearestOf(aggro, x, y), nearestOf(aggro, x + sx, y + sy)) < _monsterClearRadius)
		{
			return 5;
		}
		if (cliffNear(x, y, z) || cliffNear(x + sx, y + sy, z))
		{
			return 7;
		}
		if (wildTooStrong(wild, x, y, minLevel))
		{
			return 8;
		}
		return 0;
	}

	/**
	 * Tries to place a piste outside one town. Guards no longer veto at the
	 * old wide radius: they ANCHOR the search (road mouths), so only a guard
	 * standing right on the piste spoils it (GuardClearRadius, small), while
	 * aggressive monsters keep their own, wider clearance (MonsterClearRadius).
	 */
	private FieldArena siteArena(TownZone chosen, Collection<TownZone> zones, Collection<WaterZone> waters, List<Npc> keepers, List<Guard> guards, List<WorldObject> aggro, List<Monster> allWild, int minLevel, int[] tally)
	{
		final Location center = chosen.getZone().getCenterPoint();
		if (center == null)
		{
			return null;
		}
		// Only this town's surroundings matter to the wildlife scan.
		final List<Monster> wild = new ArrayList<>();
		final long reach = 9000L + _wildScanRadius;
		for (Monster monster : allWild)
		{
			final long mdx = monster.getX() - center.getX();
			final long mdy = monster.getY() - center.getY();
			if (((mdx * mdx) + (mdy * mdy)) <= (reach * reach))
			{
				wild.add(monster);
			}
		}
		// The heralds' meeting point FIRST: right beside the town's
		// Gatekeeper - the very spot a teleporting player lands. The
		// walk to the arena starts here, so every test below measures
		// from this spot, not from the town's abstract center.
		int guideX = center.getX();
		int guideY = center.getY();
		int guideZ = GeoEngine.getInstance().getSpawnHeight(guideX, guideY, center.getZ());
		long guideBest = Long.MAX_VALUE;
		for (Npc npc : keepers)
		{
			if (!chosen.isInsideZone(npc.getX(), npc.getY(), npc.getZ()))
			{
				continue;
			}
			final long gdx = npc.getX() - center.getX();
			final long gdy = npc.getY() - center.getY();
			final long distSq = (gdx * gdx) + (gdy * gdy);
			if (distSq < guideBest)
			{
				guideBest = distSq;
				guideX = npc.getX();
				guideY = npc.getY();
				guideZ = npc.getZ();
			}
		}
		int x = 0;
		int y = 0;
		int z = 0;
		int offX = 0;
		int offY = 0;
		double marchX = 1;
		double marchY = 0;
		boolean placed = false;
		Guard usedDoor = null;
		// ── THE GUARD DOORS ─────────────────────────────────────────────────
		// The pack plants guards and sentries OUTSIDE every town wall, at the
		// road mouths: the world itself marks where the town opens to the
		// wilds. First pass demands a walkable STRAIGHT trail from the
		// Gatekeeper (the breadcrumb stays honest); second pass accepts bent
		// ground if only the stretch out of the door itself is walkable.
		final List<Guard> scanDoors = new ArrayList<>();
		for (Guard sentry : guards)
		{
			final long sdx = sentry.getX() - center.getX();
			final long sdy = sentry.getY() - center.getY();
			if (((sdx * sdx) + (sdy * sdy)) > (6500L * 6500L))
			{
				continue; // another town's watch, not ours
			}
			if (chosen.isInsideZone(sentry.getX(), sentry.getY(), sentry.getZ()))
			{
				continue; // a wall-side watchman is not a road mouth
			}
			scanDoors.add(sentry);
		}
		if (!scanDoors.isEmpty())
		{
			Collections.shuffle(scanDoors);
			for (int doorLoop = 0; (doorLoop < 2) && !placed; doorLoop++)
			{
				final boolean strictTrail = doorLoop == 0;
				final List<Guard> keptDoors = new ArrayList<>();
				for (Guard door : scanDoors)
				{
					// Sentries stand two or three deep at one gate: one door
					// per cluster, and each door leans to a different flank.
					boolean clustered = false;
					for (Guard kept : keptDoors)
					{
						final long cdx = kept.getX() - door.getX();
						final long cdy = kept.getY() - door.getY();
						if (((cdx * cdx) + (cdy * cdy)) < (900L * 900L))
						{
							clustered = true;
							break;
						}
					}
					if (clustered)
					{
						continue;
					}
					keptDoors.add(door);
					if (arenaBlocked(door.getX(), door.getY()))
					{
						tally[6]++;
						continue; // this road mouth leads somewhere fighters fall (ArenaBlockedSpots)
					}
					final double outX = door.getX() - center.getX();
					final double outY = door.getY() - center.getY();
					final double outLen = Math.sqrt((outX * outX) + (outY * outY));
					if (outLen < 1)
					{
						continue;
					}
					final double dirX = outX / outLen;
					final double dirY = outY / outLen;
					final double sideX = -dirY;
					final double sideY = dirX;
					final double flank = ((keptDoors.size() % 2) == 0) ? 120 : -120;
					// Start past the guard's own clearance: the door guard
					// marks the road, it must not veto the very road it marks.
					for (int push = Math.max(60, _guardClearRadius + 60); push <= 2600; push += 160)
					{
						final int tryX = (int) (door.getX() + (dirX * push) + (sideX * flank));
						final int tryY = (int) (door.getY() + (dirY * push) + (sideY * flank));
						tally[0]++;
						boolean inTown = false;
						for (TownZone zone : zones)
						{
							if (zone.isInsideZone(tryX, tryY, door.getZ()))
							{
								inTown = true;
								break;
							}
						}
						if (inTown)
						{
							tally[1]++;
							continue;
						}
						final int tryZ = GeoEngine.getInstance().getSpawnHeight(tryX, tryY, door.getZ());
						final int veto = pisteVeto(waters, guards, aggro, wild, minLevel, tryX, tryY, tryZ, (int) (sideX * 260), (int) (sideY * 260));
						if (veto != 0)
						{
							tally[veto]++;
							continue;
						}
						if (arenaBlocked(tryX, tryY))
						{
							tally[6]++;
							continue;
						}
						final boolean outWalk = GeoEngine.getInstance().canMoveToTarget(new Location(door.getX(), door.getY(), door.getZ()), new Location(tryX, tryY, tryZ));
						final boolean walkable = outWalk && (!strictTrail || GeoEngine.getInstance().canMoveToTarget(new Location(guideX, guideY, guideZ), new Location(tryX, tryY, tryZ)));
						if (!walkable)
						{
							tally[3]++;
							continue;
						}
						x = tryX;
						y = tryY;
						z = tryZ;
						offX = (int) (sideX * 260);
						offY = (int) (sideY * 260);
						final double cdx = tryX - guideX;
						final double cdy = tryY - guideY;
						final double clen = Math.max(1, Math.sqrt((cdx * cdx) + (cdy * cdy)));
						marchX = cdx / clen;
						marchY = cdy / clen;
						placed = true;
						usedDoor = door;
						if (_infoHook != null)
						{
							_infoHook.accept("clan-personalities: [FIELD-SCAN] " + door.getName() + " (id " + door.getObjectId() + ") marks a road mouth of " + chosen.getName() + "; the piste is " + push + " units beyond it, " + (strictTrail ? "a straight walk from the Gatekeeper" : "bent ground, walked out the door itself") + ". (" + tryX + "," + tryY + ")");
						}
						break;
					}
					if (placed)
					{
						break;
					}
				}
			}
		}
		// FALLBACK: sixteen compass rays out of the Gatekeeper; the first ray
		// whose whole straight walk stays on dry, clean ground past the wall
		// line wins.
		final int rayCount = 16;
		final int startRay = Rnd.get(rayCount);
		for (int ray = 0; (ray < rayCount) && !placed; ray++)
		{
			final double ang = ((ray + startRay) % rayCount) * (Math.PI * 2 / rayCount);
			final double dirX = Math.cos(ang);
			final double dirY = Math.sin(ang);
			final double sideX = -dirY;
			final double sideY = dirX;
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
			for (int attempt = 0; attempt <= 8; attempt++, pushR += 400)
			{
				final int tryX = (int) (guideX + (dirX * pushR));
				final int tryY = (int) (guideY + (dirY * pushR));
				tally[0]++;
				boolean inTown = false;
				for (TownZone zone : zones)
				{
					if (zone.isInsideZone(tryX, tryY, guideZ))
					{
						inTown = true;
						break;
					}
				}
				if (inTown)
				{
					tally[1]++;
					continue;
				}
				final int tryZ = GeoEngine.getInstance().getSpawnHeight(tryX, tryY, guideZ);
				final int veto = pisteVeto(waters, guards, aggro, wild, minLevel, tryX, tryY, tryZ, (int) (sideX * 260), (int) (sideY * 260));
				if (veto != 0)
				{
					tally[veto]++;
					continue;
				}
				if (arenaBlocked(tryX, tryY))
				{
					tally[6]++;
					continue;
				}
				if (!GeoEngine.getInstance().canMoveToTarget(new Location(guideX, guideY, guideZ), new Location(tryX, tryY, tryZ)))
				{
					tally[3]++;
					continue;
				}
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
		}
		if (!placed)
		{
			return null;
		}
		final String townName = (chosen.getName() != null) ? chosen.getName().replace('_', ' ') : "the town";
		final FieldArena arena = new FieldArena(x, y, z, offX, offY, center.getX(), center.getY(), guideX, guideY, guideZ, marchX, marchY, townName);
		if (usedDoor != null)
		{
			arena.doorX = usedDoor.getX();
			arena.doorY = usedDoor.getY();
			arena.doorZ = usedDoor.getZ();
			arena.viaDoor = true;
		}
		return arena;
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
		// A piste found from a road mouth: the stretch door -> arena is the
		// one proven walkable, so the posts stand along THAT road (the
		// straight Gatekeeper line often cuts through walls and used to
		// collapse every post back onto the Gatekeeper).
		if (arena.viaDoor && (fraction > 0) && !GeoEngine.getInstance().canMoveToTarget(new Location(arena.guideX, arena.guideY, arena.guideZ), new Location(arena.x, arena.y, arena.z)))
		{
			for (int pull = 0; pull <= 3; pull++)
			{
				final double f = fraction / (1 << pull);
				final int px = arena.doorX + (int) ((arena.x - arena.doorX) * f);
				final int py = arena.doorY + (int) ((arena.y - arena.doorY) * f);
				final int pz = GeoEngine.getInstance().getSpawnHeight(px, py, arena.doorZ);
				if (!waterAt(waters, px, py, pz) && GeoEngine.getInstance().canMoveToTarget(new Location(arena.doorX, arena.doorY, arena.doorZ), new Location(px, py, pz)))
				{
					if (_infoHook != null)
					{
						_infoHook.accept("clan-personalities: [HERALD] post on the road from the gate guard: " + Math.round(f * 100) + "% of the way out (" + px + "," + py + ").");
					}
					return new Location(px, py, pz);
				}
			}
		}
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
		/** The road mouth (door guard) the piste was found from; equals the
		 *  Gatekeeper spot when the fallback ray search placed it. Heralds
		 *  stand along door -> arena, the stretch proven walkable. */
		int doorX;
		int doorY;
		int doorZ;
		boolean viaDoor;

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
			this.doorX = guideX;
			this.doorY = guideY;
			this.doorZ = guideZ;
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
		// Nothing simmering hot enough for steel anywhere? Then there is no
		// point walking the world for champions this tick: the scan stays
		// silent and costs nothing.
		boolean anyBrewed = false;
		for (String key : _brew.keySet())
		{
			if (brewedForSteel(key))
			{
				anyBrewed = true;
				break;
			}
		}
		if (!anyBrewed)
		{
			return;
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
			// Different alliances first; alliance brothers only when no
			// other simmering rival is available this pass.
			final List<ClanProfile> rivals = new ArrayList<>(clans);
			rivals.sort((x, y) -> Boolean.compare(aggressor.isAlliedWith(x), aggressor.isAlliedWith(y)));
			boolean otherAllianceBrewing = false;
			for (ClanProfile r : rivals)
			{
				if ((r != aggressor) && !aggressor.isAlliedWith(r) && brewedForSteel(arcKey(aggressor.getId(), r.getId())))
				{
					otherAllianceBrewing = true;
					break;
				}
			}
			for (ClanProfile rival : rivals)
			{
				if (rival == aggressor)
				{
					continue;
				}
				if (accuseSulking(aggressor.getId()) || accuseSulking(rival.getId()))
				{
					continue; // a clan caught being nice is not picking fights right now
				}
				if (otherAllianceBrewing && aggressor.isAlliedWith(rival))
				{
					continue; // a rival of another banner is ready: brothers wait
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
				if (wantsRevenge(aggressor, rival))
				{
					roll *= 2;
					if (_infoHook != null)
					{
						_infoHook.accept("clan-personalities: [REVENGE] " + clanNameOf(aggressor) + " lost its last duel to " + clanNameOf(rival) + " and wants it back (steel odds x2).");
					}
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
			if ((npc.getFakePlayerAppearance() == null) || nameTaken(npc.getFakePlayerAppearance()))
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
		int saved = 0;
		for (Creature member : resolveMembers(profile.getId()))
		{
			if (!(member instanceof Npc) || member.isAlikeDead() || ((Npc) member).getFakePlayerAppearance() == null)
			{
				continue;
			}
			if (nameTaken(((Npc) member).getFakePlayerAppearance()))
			{
				saved++;
				continue; // the pack would refuse this clone: its name is a saved character
			}
			found.add((Npc) member);
		}
		if ((saved > 0) && (_infoHook != null))
		{
			_infoHook.accept("clan-personalities: [FIELD-POOL] " + clanNameOf(profile) + ": " + found.size() + " member(s) can take the field; " + saved + " skipped (their name is already a saved character, so the pack refuses to clone them).");
		}
		return found;
	}

	/** True when the pack would refuse to clone this town member: its name
	 *  already belongs to a character in the database (or is unusable). */
	private static boolean nameTaken(FakePlayerAppearance appearance)
	{
		if ((appearance == null) || (appearance.getName() == null))
		{
			return true;
		}
		// doesCharNameExist is a database query: cached for 10 minutes per
		// name, so the 90-second scan never re-asks the database about the
		// same few hundred town bots.
		final String name = appearance.getName();
		final long now = System.currentTimeMillis();
		final long[] cached = NAME_CACHE.get(name);
		if ((cached != null) && (now < cached[1]))
		{
			return cached[0] != 0;
		}
		boolean taken;
		try
		{
			taken = CharInfoTable.getInstance().doesCharNameExist(name);
		}
		catch (Exception e)
		{
			taken = false; // can't tell: let the spawn itself decide
		}
		NAME_CACHE.put(name, new long[] { taken ? 1 : 0, now + 600000L });
		return taken;
	}

	private static final Map<String, long[]> NAME_CACHE = new ConcurrentHashMap<>();

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

	/** How far a member sits from a fight's common level and gear tier:
	 *  one level counts as much as four grade points of loadout. */
	private static int fitScore(Npc member, int level, int power)
	{
		final FakePlayerAppearance ap = member.getFakePlayerAppearance();
		return (4 * Math.abs(ap.getLevel() - level)) + Math.abs(appearancePower(ap) - power);
	}

	/** Sum of the k best fit scores of a pool for a level. */
	private static int bestFitCost(List<Npc> pool, int k, int level, int power)
	{
		final List<Integer> scores = new ArrayList<>();
		for (Npc member : pool)
		{
			scores.add(Integer.valueOf(fitScore(member, level, power)));
		}
		Collections.sort(scores);
		int cost = 0;
		for (int i = 0; (i < k) && (i < scores.size()); i++)
		{
			cost += scores.get(i).intValue();
		}
		return cost;
	}

	/**
	 * Level-fair squads: tries every level either clan can field, keeps the
	 * one whose best k members on BOTH sides sit closest to it, takes those
	 * members (nearest first), then drops pairings more than MaxLevelGap
	 * apart. Fills outA/outB; returns the common level.
	 */
	private int pickFairSquads(List<Npc> poolA, List<Npc> poolB, int max, int power, List<Npc> outA, List<Npc> outB)
	{
		final int k = Math.min(max, Math.min(poolA.size(), poolB.size()));
		if (k <= 0)
		{
			return 0;
		}
		final Set<Integer> levels = new LinkedHashSet<>();
		for (Npc member : poolA)
		{
			levels.add(Integer.valueOf(member.getFakePlayerAppearance().getLevel()));
		}
		for (Npc member : poolB)
		{
			levels.add(Integer.valueOf(member.getFakePlayerAppearance().getLevel()));
		}
		int bestLevel = 0;
		int bestCost = Integer.MAX_VALUE;
		for (Integer level : levels)
		{
			final int cost = bestFitCost(poolA, k, level.intValue(), power) + bestFitCost(poolB, k, level.intValue(), power);
			if (cost < bestCost)
			{
				bestCost = cost;
				bestLevel = level.intValue();
			}
		}
		final int target = bestLevel;
		final List<Npc> sortedA = new ArrayList<>(poolA);
		final List<Npc> sortedB = new ArrayList<>(poolB);
		Collections.shuffle(sortedA);
		Collections.shuffle(sortedB);
		sortedA.sort((a, b) -> Integer.compare(fitScore(a, target, power), fitScore(b, target, power)));
		sortedB.sort((a, b) -> Integer.compare(fitScore(a, target, power), fitScore(b, target, power)));
		for (int i = 0; i < k; i++)
		{
			final int la = sortedA.get(i).getFakePlayerAppearance().getLevel();
			final int lb = sortedB.get(i).getFakePlayerAppearance().getLevel();
			final int ga = appearancePower(sortedA.get(i).getFakePlayerAppearance());
			final int gb = appearancePower(sortedB.get(i).getFakePlayerAppearance());
			if ((Math.abs(la - lb) > _maxLevelGap) || gearUnfair(ga, gb))
			{
				break; // this pairing would be a slaughter: the squad stops here
			}
			outA.add(sortedA.get(i));
			outB.add(sortedB.get(i));
		}
		return target;
	}

	/**
	 * Team-fair squads of exactly k: the k members of each clan closest to
	 * the common level (gear breaks ties), accepted whole or not at all -
	 * every pair within MaxLevelGap, and the two squads' AVERAGE gear fair
	 * by the same gear rule a pair uses. Returns the common level.
	 */
	private int pickTeamSquads(List<Npc> poolA, List<Npc> poolB, int k, int power, List<Npc> outA, List<Npc> outB)
	{
		if ((poolA.size() < k) || (poolB.size() < k))
		{
			return 0;
		}
		final Set<Integer> levels = new LinkedHashSet<>();
		for (Npc member : poolA)
		{
			levels.add(Integer.valueOf(member.getFakePlayerAppearance().getLevel()));
		}
		for (Npc member : poolB)
		{
			levels.add(Integer.valueOf(member.getFakePlayerAppearance().getLevel()));
		}
		for (Integer level : levels)
		{
			final int target = level.intValue();
			final List<Npc> sortedA = new ArrayList<>(poolA);
			final List<Npc> sortedB = new ArrayList<>(poolB);
			Collections.shuffle(sortedA);
			Collections.shuffle(sortedB);
			sortedA.sort((a, b) -> Integer.compare(fitScore(a, target, power), fitScore(b, target, power)));
			sortedB.sort((a, b) -> Integer.compare(fitScore(a, target, power), fitScore(b, target, power)));
			final List<Npc> squadA = sortedA.subList(0, k);
			final List<Npc> squadB = sortedB.subList(0, k);
			boolean levelsOk = true;
			for (int i = 0; i < k; i++)
			{
				if (Math.abs(squadA.get(i).getFakePlayerAppearance().getLevel() - squadB.get(i).getFakePlayerAppearance().getLevel()) > _maxLevelGap)
				{
					levelsOk = false;
					break;
				}
			}
			if (levelsOk && !gearUnfair((int) Math.round(teamGearValue(squadA)), (int) Math.round(teamGearValue(squadB))))
			{
				outA.addAll(squadA);
				outB.addAll(squadB);
				return target;
			}
		}
		return 0;
	}

	private static double teamGearValue(List<Npc> squad)
	{
		double sum = 0;
		for (Npc member : squad)
		{
			sum += appearancePower(member.getFakePlayerAppearance());
		}
		return squad.isEmpty() ? 0 : (sum / squad.size());
	}

	private static String teamGear(List<Npc> squad)
	{
		return String.valueOf(Math.round(teamGearValue(squad) * 10) / 10.0);
	}

	/**
	 * Gear fairness (playtest 2026-10-21: a level 36 mage in all no-grade,
	 * score 0, met a dagger with D-grade pieces, score 4 - inside the old
	 * gap of 6, and over in 14 seconds). The gap is MaxGearGap; and a "bare"
	 * fighter (score at or below BareGearMax) only meets someone at most
	 * one point above its own score.
	 */
	private boolean gearUnfair(int ga, int gb)
	{
		if (Math.abs(ga - gb) > _maxGearGap)
		{
			return true;
		}
		final int low = Math.min(ga, gb);
		return (low <= _bareGearMax) && ((Math.max(ga, gb) - low) > 1);
	}

	private static String duelRole(Npc member)
	{
		try
		{
			return PhantomManager.roleForClass(member.getFakePlayerAppearance().getPlayerClass()).name();
		}
		catch (Exception e)
		{
			return "WARRIOR";
		}
	}

	/** True when a 1v1 between these two is on the AvoidSoloMatchups list (either way round). */
	private boolean lopsidedSolo(Npc a, Npc b)
	{
		final String ra = duelRole(a);
		final String rb = duelRole(b);
		return _avoidSolo.contains(ra + "-" + rb) || _avoidSolo.contains(rb + "-" + ra);
	}

	/**
	 * True for a town bot that would have nothing to do in a 1v1: a healer
	 * or buffer class. The Orc Mystic line (Orc Shaman, Overlord, Warcryer
	 * and their third classes) is the exception - it carries attack spells.
	 */
	private static boolean cannotDuelAlone(Npc member)
	{
		try
		{
			final FakePlayerAppearance ap = member.getFakePlayerAppearance();
			if ((ap == null) || (ap.getPlayerClass() == null))
			{
				return false;
			}
			final String role = PhantomManager.roleForClass(ap.getPlayerClass()).name();
			if (!"HEALER".equals(role) && !"BUFFER".equals(role))
			{
				return false;
			}
			return ap.getRace() != org.l2jmobius.gameserver.model.actor.enums.creature.Race.ORC;
		}
		catch (Exception e)
		{
			return false;
		}
	}

	/** "Name(lv 34, gear 18), ..." for the match log line. */
	private static String matchTag(List<Npc> squad)
	{
		final List<String> tags = new ArrayList<>();
		for (Npc member : squad)
		{
			final FakePlayerAppearance ap = member.getFakePlayerAppearance();
			tags.add(ap.getName() + "(lv " + ap.getLevel() + ", gear " + appearancePower(ap) + ")");
		}
		return String.join(", ", tags);
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
		final String warKey = grievanceKey(profileA, profileB);
		// The war is due once the grudge is deep enough - whichever path
		// brings the duel (playtest 2026-10-21: the brew-to-steel path staged
		// a plain 1v1 mid-war, before the war cry, and the battle never came).
		// Two Peaceful clans still don't start it.
		final boolean bothPeacefulPair = (profileA.getPersona() == PersonaType.PEACEFUL) && (profileB.getPersona() == PersonaType.PEACEFUL);
		final boolean warDue = _allianceWars && (warKey != null) && !bothPeacefulPair && (_warDue.contains(warKey) || (grievanceCount(profileA, profileB) >= _allianceWarAt));
		if (!warDue && (now < _nextSkirmishMillis))
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-SCAN] the skirmish bank is drawing down - no new duel until it refills (" + ((_nextSkirmishMillis - now) / 1000) + "s).");
			}
			return false;
		}
		// Squad size: the world's mood picks 1v1, 2v2 or 3v3, limited by
		// how many walkable members each clan has out there today.
		// Rarely, the bickering boils over into an EPIC battle (user,
		// 2026-10-21): EpicDuelChance percent of stagings roll EpicSquadMin
		// to EpicSquadMax a side instead of 1..FieldMaxSquad.
		// Grievances between the two sides' alliances raise the odds; a due
		// alliance war makes it certain.
		final int epicChance = epicChanceFor(profileA, profileB);
		final boolean epicRoll = warDue || ((epicChance > 0) && (Rnd.get(100) < epicChance));
		final int requested = epicRoll ? (_epicMin + Rnd.get((_epicMax - _epicMin) + 1)) : (1 + Rnd.get(_fieldMaxSquad));
		if (epicRoll && (_infoHook != null))
		{
			_infoHook.accept("clan-personalities: [FIELD-MATCH] " + (warDue ? "the ALLIANCE WAR breaks out" : ("the dice say EPIC (" + epicChance + "% chance)")) + ": " + clanNameOf(profileA) + " and " + clanNameOf(profileB) + " may field up to " + requested + " a side.");
		}
		final List<Npc> poolA = clanMembers(profileA);
		final List<Npc> poolB = clanMembers(profileB);
		if (poolA.isEmpty() || poolB.isEmpty())
		{
			return false;
		}
		// An EPIC battle between two alliances pulls from the whole alliance
		// on each side (user, 2026-10-21). Clans of the same alliance, or
		// clans with none, fight with their own members only.
		final boolean alliancePools = _allianceWars && epicRoll && (warKey != null);
		if (alliancePools)
		{
			final List<String> joinedA = new ArrayList<>();
			final List<String> joinedB = new ArrayList<>();
			for (ClanProfile profile : _profiles.values())
			{
				if ((profile == profileA) || (profile == profileB) || (profile.getPersona() == null))
				{
					continue;
				}
				if ((profileA.getAllianceGroup() > 0) && (profile.getAllianceGroup() == profileA.getAllianceGroup()))
				{
					final List<Npc> members = clanMembers(profile);
					if (!members.isEmpty())
					{
						poolA.addAll(members);
						joinedA.add(clanNameOf(profile));
					}
				}
				else if ((profileB.getAllianceGroup() > 0) && (profile.getAllianceGroup() == profileB.getAllianceGroup()))
				{
					final List<Npc> members = clanMembers(profile);
					if (!members.isEmpty())
					{
						poolB.addAll(members);
						joinedB.add(clanNameOf(profile));
					}
				}
			}
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-MATCH] alliance pools: " + allianceKeyOf(profileA) + " (" + clanNameOf(profileA) + (joinedA.isEmpty() ? "" : (" + " + String.join(", ", joinedA))) + ") vs " + allianceKeyOf(profileB) + " (" + clanNameOf(profileB) + (joinedB.isEmpty() ? "" : (" + " + String.join(", ", joinedB))) + ").");
			}
		}
		// The fight's common gear tier: the average loadout across BOTH
		// clans' available members, so each squad converges on the middle.
		// A clan hands the colors to the members whose loadout sits CLOSEST
		// to that tier - no more D-grade lambs against full-B monsters.
		final int targetPower = (int) Math.round((poolAverage(poolA) + poolAverage(poolB)) / 2.0);
		// LEVEL first, then gear: in Lineage 2 a few levels outweigh any
		// loadout. Find the level both clans can field members closest to,
		// send the members nearest it (gear breaks ties), and drop any
		// pairing whose levels still sit more than MaxLevelGap apart.
		final List<Npc> champsA = new ArrayList<>();
		final List<Npc> champsB = new ArrayList<>();
		int targetLevel = pickFairSquads(poolA, poolB, requested, targetPower, champsA, champsB);
		// A lone healer or buffer would only stand there in a 1v1. If the duel
		// came out one-on-one with one, re-match from the duelists who can
		// actually fight alone (Orc Mystic line excepted: it has attack spells).
		if ((Math.min(champsA.size(), champsB.size()) == 1) && (cannotDuelAlone(champsA.get(0)) || cannotDuelAlone(champsB.get(0))))
		{
			final List<Npc> soloA = new ArrayList<>();
			final List<Npc> soloB = new ArrayList<>();
			for (Npc member : poolA)
			{
				if (!cannotDuelAlone(member))
				{
					soloA.add(member);
				}
			}
			for (Npc member : poolB)
			{
				if (!cannotDuelAlone(member))
				{
					soloB.add(member);
				}
			}
			champsA.clear();
			champsB.clear();
			targetLevel = pickFairSquads(soloA, soloB, 1, targetPower, champsA, champsB);
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-MATCH] a lone healer/buffer would be a dull 1v1 - re-matched from fighters who can duel alone" + (champsA.isEmpty() ? " (none fit)." : "."));
			}
		}
		// A 1v1 that Interlude decides before it starts (a dagger on a lone
		// mage): try again without the first role of the pair, then without
		// the second; keep the original only if nothing else fits.
		if ((Math.min(champsA.size(), champsB.size()) == 1) && !_avoidSolo.isEmpty() && lopsidedSolo(champsA.get(0), champsB.get(0)))
		{
			final String was = duelRole(champsA.get(0)) + " vs " + duelRole(champsB.get(0));
			final List<Npc> keepA = new ArrayList<>(champsA);
			final List<Npc> keepB = new ArrayList<>(champsB);
			final int keepLevel = targetLevel;
			boolean fixed = false;
			for (int pass = 0; (pass < 2) && !fixed; pass++)
			{
				final String drop = (pass == 0) ? duelRole(keepA.get(0)) : duelRole(keepB.get(0));
				final List<Npc> fa = new ArrayList<>();
				final List<Npc> fb = new ArrayList<>();
				for (Npc member : poolA)
				{
					if (!cannotDuelAlone(member) && !drop.equals(duelRole(member)))
					{
						fa.add(member);
					}
				}
				for (Npc member : poolB)
				{
					if (!cannotDuelAlone(member) && !drop.equals(duelRole(member)))
					{
						fb.add(member);
					}
				}
				champsA.clear();
				champsB.clear();
				if (!fa.isEmpty() && !fb.isEmpty())
				{
					targetLevel = pickFairSquads(fa, fb, 1, targetPower, champsA, champsB);
				}
				fixed = !champsA.isEmpty() && !champsB.isEmpty() && !lopsidedSolo(champsA.get(0), champsB.get(0));
			}
			if (!fixed)
			{
				champsA.clear();
				champsA.addAll(keepA);
				champsB.clear();
				champsB.addAll(keepB);
				targetLevel = keepLevel;
			}
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-MATCH] " + was + " is a lopsided 1v1 - " + (fixed ? ("re-matched: " + duelRole(champsA.get(0)) + " vs " + duelRole(champsB.get(0)) + ".") : "no other fair pair, so it stands."));
			}
		}
		// No fair pair at all (user, 2026-10-21): bigger squads can still be
		// fair as TEAMS - two mixed loadouts can balance where no single
		// pair does. Try 2v2, then 3v3 (up to FieldMaxSquad): every pair
		// still within MaxLevelGap, and the squads' average gear fair.
		if (champsA.isEmpty() || champsB.isEmpty())
		{
			// Normal: smallest first (2v2, then 3v3). Epic: biggest first,
			// down to 2v2 - a war shrinks before it is called off.
			final List<Integer> sizes = new ArrayList<>();
			if (epicRoll)
			{
				for (int k = requested; k >= 2; k--)
				{
					sizes.add(Integer.valueOf(k));
				}
			}
			else
			{
				for (int k = 2; k <= Math.max(_fieldMaxSquad, requested); k++)
				{
					sizes.add(Integer.valueOf(k));
				}
			}
			for (Integer kk : sizes)
			{
				if (!champsA.isEmpty() && !champsB.isEmpty())
				{
					break;
				}
				final int k = kk.intValue();
				champsA.clear();
				champsB.clear();
				final int level = pickTeamSquads(poolA, poolB, k, targetPower, champsA, champsB);
				if (!champsA.isEmpty() && !champsB.isEmpty())
				{
					targetLevel = level;
					if (_infoHook != null)
					{
						_infoHook.accept("clan-personalities: [FIELD-MATCH] no fair pair-by-pair match - bumped to " + k + "v" + k + ": team gear " + teamGear(champsA) + " vs " + teamGear(champsB) + " on average.");
					}
				}
			}
		}
		final int size = (champsA.size() > requested) ? Math.min(champsA.size(), champsB.size()) : Math.min(requested, Math.min(champsA.size(), champsB.size()));
		if (size <= 0)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-MATCH] no fair match between " + clanNameOf(profileA) + " and " + clanNameOf(profileB) + " (no pair within " + _maxLevelGap + " levels and " + _maxGearGap + " gear points) - no duel this time.");
			}
			return false;
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [FIELD-MATCH] level ~" + targetLevel + ": " + clanNameOf(profileA) + " " + matchTag(champsA) + " vs " + clanNameOf(profileB) + " " + matchTag(champsB) + ".");
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
			// The duel's level (playtest 2026-10-21): the AVERAGE of every
			// fighter picks the town band; the LOWEST fighter sets the
			// monster scan - the weakest one is who the wildlife would eat.
			int levelSum = 0;
			int levelCount = 0;
			int minLevel = Integer.MAX_VALUE;
			final List<Npc> everyone = new ArrayList<>(champsA);
			everyone.addAll(champsB);
			if (reinforceSource != null)
			{
				everyone.add(reinforceSource);
			}
			for (Npc fighter : everyone)
			{
				// The APPEARANCE carries the member's real level; the NPC
				// shell's own getLevel() is a template value (85 for every
				// one - playtest 2026-10-21 sent a level 28 duel to Aden).
				final FakePlayerAppearance ap = fighter.getFakePlayerAppearance();
				final int level = (ap != null) ? ap.getLevel() : fighter.getLevel();
				levelSum += level;
				levelCount++;
				minLevel = Math.min(minLevel, level);
			}
			final double avgLevel = (levelCount > 0) ? ((double) levelSum / levelCount) : 40;
			arena = fieldArena(champsA.get(0).getX(), champsA.get(0).getY(), champsA.get(0).getZ(), champsB.get(0).getX(), champsB.get(0).getY(), champsB.get(0).getZ(), avgLevel, (minLevel == Integer.MAX_VALUE) ? 1 : minLevel);
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
		final long leadMillis = _fieldLeadSeconds * 1000L * ((pairSize >= _epicMin) ? 3 : ((pairSize >= 3) ? 2 : 1));
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
				// Straight onto the field (the crowd is already gathering
				// there): side A on the near mark, side B across the gap.
				// Two facing lines: each fighter steps along the line by its
				// place in the squad, so a big battle stands as two ranks
				// instead of a pile (a 1v1 stands on the mark itself).
				final double offLen = Math.max(1, Math.sqrt(((double) arena.offX * arena.offX) + ((double) arena.offY * arena.offY)));
				final double lineX = -arena.offY / offLen;
				final double lineY = arena.offX / offLen;
				final double slot = (i - ((pairSize - 1) / 2.0)) * 70.0;
				final int ax = arena.x + (int) (lineX * slot) + Rnd.get(41) - 20;
				final int ay = arena.y + (int) (lineY * slot) + Rnd.get(41) - 20;
				final Player fighter = pm.spawnPartyMemberAs(apsA.get(i), new Location(ax, ay, GeoEngine.getInstance().getSpawnHeight(ax, ay, arena.guideZ)), apsA.get(i).getLevel());
				if (fighter == null)
				{
					throw new IllegalStateException("the pack refused a fighter spawn");
				}
				sideA.add(fighter);
				final int bx = arena.x + arena.offX + (int) (lineX * slot) + Rnd.get(41) - 20;
				final int by = arena.y + arena.offY + (int) (lineY * slot) + Rnd.get(41) - 20;
				final Player rival = pm.spawnPartyMemberAs(apsB.get(i), new Location(bx, by, GeoEngine.getInstance().getSpawnHeight(bx, by, arena.guideZ)), apsB.get(i).getLevel());
				if (rival == null)
				{
					throw new IllegalStateException("the pack refused a fighter spawn");
				}
				sideB.add(rival);
			}
			// Wear the colors: the crests go on visibly, the world sees WHO.
			// Each fighter wears its OWN clan's crest - an alliance battle
			// shows every clan that came.
			for (int i = 0; i < sideA.size(); i++)
			{
				final int own = (apsA.get(i).getClanId() > 0) ? apsA.get(i).getClanId() : profileA.getId();
				final Clan clan = ClanTable.getInstance().getClan(own);
				if (clan != null)
				{
					BotClanManager.getInstance().attach(sideA.get(i), clan);
				}
			}
			for (int i = 0; i < sideB.size(); i++)
			{
				final int own = (apsB.get(i).getClanId() > 0) ? apsB.get(i).getClanId() : profileB.getId();
				final Clan clan = ClanTable.getInstance().getClan(own);
				if (clan != null)
				{
					BotClanManager.getInstance().attach(sideB.get(i), clan);
				}
			}
			// The right steel for the class: a fighter whose skills want a
			// blunt (or a dagger, a pole...) must not walk out with a sword,
			// or it can never use them.
			for (Player fighter : sideA)
			{
				fitWeapon(fighter);
			}
			for (Player fighter : sideB)
			{
				fitWeapon(fighter);
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
		// The squads already stand on the field: no march, no ferry.
		fight.marched = true;
		fight.marchedAtMillis = now;
		fight.sameAlliance = profileA.isAlliedWith(profileB);
		fight.allianceName = fight.sameAlliance ? _allianceNames.get(Integer.valueOf(profileA.getAllianceGroup())) : null;
		if (fight.sameAlliance && (_infoHook != null))
		{
			_infoHook.accept("clan-personalities: [FIELD-FIGHT] alliance brothers: " + clanNameOf(profileA) + " and " + clanNameOf(profileB) + " are both " + ((fight.allianceName != null) ? fight.allianceName : "one alliance") + " - they fight anyway, and everyone notices.");
		}
		fight.loud = (pairSize >= 3);
		fight.epic = (pairSize >= _epicMin);
		// Ring the skirmish bank: this fight's size buys the world's
		// quiet afterward - a 3v3 weighs twice a 1v1, and a hot day
		// stretches every later wait one step further.
		_skirmishHeat = Math.min(fight.epic ? 12 : 6, _skirmishHeat + pairSize);
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
			_infoHook.accept("clan-personalities: [FIELD-FIGHT] staged " + pairSize + "v" + pairSize + " outside " + arena.townName + " at " + arena.x + "," + arena.y + ": " + clanNameOf(profileA) + "'s " + String.join(", ", namesA) + " vs " + clanNameOf(profileB) + "'s " + String.join(", ", namesB) + " (" + fight.compassSide + " side; fists fly in ~" + (leadMillis / 1000) + "s, sooner once a watcher reaches the fence).");
		}
		// Who fights for which clan (an alliance battle mixes them).
		final Set<Integer> alliedA = new LinkedHashSet<>();
		final Set<Integer> alliedB = new LinkedHashSet<>();
		for (int i = 0; i < Math.min(sideA.size(), apsA.size()); i++)
		{
			final int own = (apsA.get(i).getClanId() > 0) ? apsA.get(i).getClanId() : profileA.getId();
			fight.fanClans.put(Integer.valueOf(sideA.get(i).getObjectId()), Integer.valueOf(own));
			if (own != profileA.getId())
			{
				alliedA.add(Integer.valueOf(own));
			}
		}
		for (int i = 0; i < Math.min(sideB.size(), apsB.size()); i++)
		{
			final int own = (apsB.get(i).getClanId() > 0) ? apsB.get(i).getClanId() : profileB.getId();
			fight.fanClans.put(Integer.valueOf(sideB.get(i).getObjectId()), Integer.valueOf(own));
			if (own != profileB.getId())
			{
				alliedB.add(Integer.valueOf(own));
			}
		}
		fight.gated = townHasGates(arena.townName);
		fight.allianceWar = alliancePools && (!alliedA.isEmpty() || !alliedB.isEmpty());
		if (alliancePools)
		{
			fight.allianceA = allianceKeyOf(profileA);
			fight.allianceB = allianceKeyOf(profileB);
		}
		// A war cry answered with steel is satisfied; an EPIC battle between
		// two alliances settles the whole grudge.
		_pendingWarCries.remove(arcKey(profileA.getId(), profileB.getId()));
		if (fight.epic && (warKey != null))
		{
			final Integer had = _grievances.remove(warKey);
			final boolean wasWar = _warDue.remove(warKey);
			if (((had != null) || wasWar) && (_infoHook != null))
			{
				_infoHook.accept("clan-personalities: [GRIEVANCE] " + warKey.replace("+", " vs ") + ": the epic battle settles " + ((had != null) ? had.intValue() : 0) + " grievance(s)" + (wasWar ? " - the alliance war is fought." : "."));
			}
			saveRecords();
		}
		// Peaceful clans dragged into an alliance battle say so, out loud.
		for (Integer allyId : alliedA)
		{
			draggedIn(fight, _profiles.get(allyId), profileA, profileB);
		}
		for (Integer allyId : alliedB)
		{
			draggedIn(fight, _profiles.get(allyId), profileB, profileA);
		}
		// The heartbeat starts at once: the crowd, the jabs and the fence
		// all live inside it. (Before: the first tick waited out the whole
		// lead - 300 s for a 3v3 - so a watcher at the fence saw nothing.)
		// With no human around, the fuse still holds until the lead ends.
		scheduleFightTick(fight, 3000);
		// The crowd gathers FIRST, at the arena itself, for every fight -
		// a ring of bodies is the marker people spot from a distance; the
		// fighters walk out (or arrive) into it afterwards.
		ThreadPool.schedule(() -> scheduleSpectators(fight, arena), 500 + Rnd.get(1000));
		if (fight.loud)
		{
			// A GRAND brawl is a spectacle: opener, world directions, the
			// breadcrumb road, the peanut gallery - and an EXTRA mid-lead
			// address, because a long lead loses people who heard it once.
			ThreadPool.schedule(() -> fieldOpener(fight), 1500 + Rnd.get(2500));
			ThreadPool.schedule(() -> fieldWhereLine(fight, false), 7000 + Rnd.get(4000));
			ThreadPool.schedule(() -> fieldWhereLine(fight, false), Math.max(20000, leadMillis / 2) + Rnd.get(4000));
			if (fight.epic)
			{
				// An EPIC battle is world news: a third clan spreads it, and
				// the address goes out once more late in the long runway.
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [FIELD-EPIC] " + pairSize + "v" + pairSize + " - an EPIC battle: world news, a triple runway, extra calls.");
				}
				ThreadPool.schedule(() -> epicNews(fight), 12000 + Rnd.get(6000));
				ThreadPool.schedule(() -> fieldWhereLine(fight, false), Math.max(40000, (leadMillis * 4) / 5) + Rnd.get(4000));
			}
			if (_fieldHeralds > 0)
			{
				ThreadPool.schedule(() -> scheduleHeralds(fight, arena), 3000 + Rnd.get(3000));
			}
		}
		else
		{
			// A quiet duel: one direct WORLD dare naming the place, one
			// retort - no town crier, but a small crowd still rings it.
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-FIGHT] a quiet duel: a direct dare between the two clans, a small crowd at the field.");
			}
			ThreadPool.schedule(() -> fieldDuelCallout(fight, true), 2000 + Rnd.get(2000));
		}
		return true;
	}

	/**
	 * A Peaceful clan pulled into an alliance battle complains in world
	 * chat: it will fight for its alliance, but it never asked for this.
	 */
	private void draggedIn(FieldFight fight, ClanProfile ally, ClanProfile brother, ClanProfile foe)
	{
		if ((ally == null) || (ally.getPersona() != PersonaType.PEACEFUL))
		{
			return;
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [FIELD-DRAGGED] " + clanNameOf(ally) + " (Peaceful) is dragged into " + clanNameOf(brother) + "'s war with " + clanNameOf(foe) + ".");
		}
		ThreadPool.schedule(() ->
		{
			if (!fight.running)
			{
				return;
			}
			final VoiceChoice voice = chooseVoice(ally);
			if (voice == null)
			{
				return;
			}
			brainWorld(voice.creature, "Your clan " + clanNameOf(ally) + " is Peaceful - but your alliance " + allianceKeyOf(ally) + " is going to war with " + allianceKeyOf(foe) + " over " + clanNameOf(brother) + "'s quarrel with " + clanNameOf(foe) + ", and some of your members are being dragged onto the field outside " + fight.townName + ". In ONE short line, complain about being dragged into this: you'll fight for your alliance, but you never wanted it.", "delivered [FIELD-DRAGGED]");
		}, 9000 + Rnd.get(8000) + spacingHold());
	}

	/** Towns with no wall and so no gates (user, 2026-10-21: "north gate didn't really exist"). */
	private final Set<String> _gatelessTowns = ConcurrentHashMap.newKeySet();

	void setGatelessTowns(String spec)
	{
		_gatelessTowns.clear();
		if (spec != null)
		{
			for (String raw : spec.split(","))
			{
				final String town = denseName(raw);
				if (!town.isEmpty())
				{
					_gatelessTowns.add(town);
				}
			}
		}
	}

	private boolean townHasGates(String townName)
	{
		return (townName == null) || !_gatelessTowns.contains(denseName(townName));
	}

	/** "the north gates of Giran" / "the north-east side of Talking Island". */
	private static String sidePlace(FieldFight fight)
	{
		return "the " + fight.compassSide + (fight.gated ? " gates of " : " side of ") + fight.townName;
	}

	/** "outside the north gates of Giran" / "on the north-east side of Talking Island". */
	private static String outsidePlace(FieldFight fight)
	{
		return fight.gated ? ("outside the " + fight.compassSide + " gates of " + fight.townName) : ("on the " + fight.compassSide + " side of " + fight.townName);
	}

	/** "outside the gates of Giran" / "outside Talking Island". */
	private static String outsideTown(FieldFight fight)
	{
		return fight.gated ? ("outside the gates of " + fight.townName) : ("outside " + fight.townName);
	}

	/** One side of a fight, for announcements: an alliance at war, or a clan's fighters. */
	private String sideLabel(FieldFight fight, boolean sideA)
	{
		if (fight.allianceWar)
		{
			final ClanProfile lead = _profiles.get(sideA ? fight.clanAId : fight.clanBId);
			return "the " + (sideA ? fight.allianceA : fight.allianceB) + " alliance (led by " + ((lead != null) ? clanNameOf(lead) : "its clans") + ": " + String.join(", ", sideA ? fight.namesA : fight.namesB) + ")";
		}
		return sideA ? sideTag(fight.clanAId, fight.namesA) : sideTag(fight.clanBId, fight.namesB);
	}

	/**
	 * EPIC battles only: a clan that is NOT fighting spreads the news in
	 * world chat - the whole server hears that a war is about to break out.
	 */
	private void epicNews(FieldFight fight)
	{
		if (!fight.running)
		{
			return;
		}
		final List<ClanProfile> others = new ArrayList<>();
		for (ClanProfile profile : _profiles.values())
		{
			if ((profile.getId() != fight.clanAId) && (profile.getId() != fight.clanBId) && (profile.getPersona() != null) && !(fight.allianceWar && (allianceKeyOf(profile).equals(fight.allianceA) || allianceKeyOf(profile).equals(fight.allianceB))))
			{
				others.add(profile);
			}
		}
		Collections.shuffle(others);
		for (ClanProfile crier : others)
		{
			final VoiceChoice voice = chooseVoice(crier);
			if (voice == null)
			{
				continue;
			}
			brainWorld(voice.creature, "WORLD NEWS: an EPIC battle is about to break out - " + (fight.allianceWar ? "an ALLIANCE WAR, " : "") + sideLabel(fight, true) + " against " + sideLabel(fight, false) + ", " + fight.namesA.size() + " against " + fight.namesB.size() + ", " + outsidePlace(fight) + ". Your clan " + clanNameOf(crier) + " is NOT in it - you are spreading the word, amazed or excited. In ONE short line, tell the whole world: name both clans, the size of it, and where.", "delivered [FIELD-EPIC]");
			return;
		}
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
		// Only someone AT the arena and tied to the fight spreads the word.
		final Creature where = duelVoice(fight, 0);
		if (where == null)
		{
			return;
		}
		final ClanProfile voiceClan = _profiles.get(Integer.valueOf(((where instanceof Player) && fight.sideB.contains(where)) ? fight.clanBId : fight.clanAId));
		final VoiceChoice choice = new VoiceChoice(where, 0, "", false);
		final String scale = fight.allianceWar ? ("an ALLIANCE WAR, " + fight.namesA.size() + " against " + fight.namesB.size() + " - " + fight.allianceA + " against " + fight.allianceB + ", the kind of battle people talk about for weeks") : (fight.epic ? ("an EPIC battle, " + fight.namesA.size() + " against " + fight.namesB.size() + " - a clan WAR on the field, the kind people talk about for weeks") : "a REAL clan fight");
		final String moment = alreadyStarted ? (scale + " is underway RIGHT NOW") : (scale + " starts soon");
		final String walk = walkWord(fight);
		// The compose + address-check + retry flow runs whole on a brain
		// worker; only the delivery hops back to the game pool.
		final FieldFight battle = fight;
		try
		{
			brainSubmit(() ->
			{
				String text = brainCustom(choice.creature, "WORLD NEWS, and you spread it loudly: " + moment + " - " + sideLabel(battle, true) + " against " + sideLabel(battle, false) + ". The place: " + walk + " " + sidePlace(battle) + ". In ONE short line, TELL THE WORLD WHERE: name the town AND the side of its gates - and make clear how far the walk is (" + walk + ") - so people know where to teleport and which direction to head out. Make it findable, like world-chat gossip that wants an audience.");
				if (text == null)
				{
					return; // brain down: the opener still pointed the way
				}
				// The address is the WHOLE point of this line: if the mind wandered
				// off into commentary, steer once - firmly - and if it wanders
				// again, the bare address rides along with whatever was said.
				if (!containsIgnoreCase(text, battle.compassSide) && !containsIgnoreCase(text, battle.townName))
				{
					final String retry = brainCustom(choice.creature, "No commentary - the ADDRESS. ONE short line that contains the words \"" + outsidePlace(battle) + "\", plus a pinch of gossip heat. The place must be findable.");
					if (retry == null)
					{
						return;
					}
					text = retry;
					if (!containsIgnoreCase(text, battle.compassSide) && !containsIgnoreCase(text, battle.townName))
					{
						text = text + " - " + walk + " " + sidePlace(battle) + ", right now.";
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
			// DISCARD, never cool: cooling STORES the clone as a real
			// character (Player.storeMe) - that wrote every past fighter,
			// herald and fan into the characters table, and the pack then
			// refused to clone those town members ever again (their name
			// "exists"). Discard deletes the temporary character outright.
			pm.discardLivingPopulationPhantom(fighter);
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
			role = "The clan fight just STARTED " + walk + " " + sidePlace(fight) + ", and you are really loud about it right now.";
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
			brainSubmit(() ->
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
				if (playerNearFight(fight) || playerAtFence(fight))
				{
					crowdGossipBeat(fight, aliveA, aliveB, now2); // talk for someone, not for the empty road
				}
				// The field is not silent: while the world gathers its
				// nerve, the champions heckle across the gap, squad by
				// squad, in words the near ground can hear.
				final boolean audience = playerAtFence(fight) || playerNearFight(fight) || (now2 >= fight.fightStartMillis);
				final boolean rightThere = playerVeryClose(fight);
				if (rightThere && !fight.veryCloseNoted)
				{
					fight.veryCloseNoted = true;
					fight.lastTrashMillis = 0; // the first jab comes at once
					if (_infoHook != null)
					{
						_infoHook.accept("clan-personalities: [FIELD-FUSE] a watcher is right beside the fighters - the talk speeds up.");
					}
				}
				if (audience && ((now2 - fight.lastTrashMillis) >= (rightThere ? _veryCloseTrashMillis : 11000L)))
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
				// 3) The fuse. A watcher still ON THE ROAD holds the first blow
				// (up to the grace cap) - they are coming. Otherwise steel
				// falls when: the aggression meter hits 100 (one side snaps),
				// OR every fighter has said their piece while a watcher stands
				// at the fence, OR the watcher has waited at the fence long
				// enough, OR nobody is watching and the lead ran out, OR the
				// hard cap. (Before: the fence fuse re-armed itself on every
				// check while the watcher stood there, so it never went off.)
				final boolean atFence = playerAtFence(fight);
				final boolean watcherNear = playerNearFight(fight);
				if (atFence && (fight.fenceSinceMillis == 0))
				{
					fight.fenceSinceMillis = now2;
				}
				final boolean coming = !atFence && watcherNear && (now2 < (fight.fightStartMillis + _playerGraceMaxMillis));
				if (coming)
				{
					if (!fight.stallAnnounced)
					{
						fight.stallAnnounced = true;
						if (_infoHook != null)
						{
							_infoHook.accept("clan-personalities: [FIELD-STAGE] first blow held - a watcher is on the way.");
						}
					}
					return false;
				}
				boolean allSpoke = true;
				for (Player fighter : aliveA)
				{
					if (!fight.spoke.contains(fighter.getName()))
					{
						allSpoke = false;
						break;
					}
				}
				if (allSpoke)
				{
					for (Player fighter : aliveB)
					{
						if (!fight.spoke.contains(fighter.getName()))
						{
							allSpoke = false;
							break;
						}
					}
				}
				String why = null;
				if (fight.aggression >= 100)
				{
					why = "the aggression meter hit " + fight.aggression + "/100 - tempers snap";
				}
				else if (atFence && allSpoke)
				{
					why = "every fighter has said their piece and the watcher stands at the fence (aggression " + fight.aggression + "/100)";
				}
				else if (atFence && ((now2 - fight.fenceSinceMillis) >= (fight.veryCloseNoted ? Math.min(_fenceMaxWaitMillis, _veryCloseFenceMillis) : _fenceMaxWaitMillis)))
				{
					why = "the watcher has waited at the fence " + ((now2 - fight.fenceSinceMillis) / 1000) + "s (aggression " + fight.aggression + "/100)";
				}
				else if (!atFence && !watcherNear && (now2 >= fight.fightStartMillis))
				{
					why = "nobody is watching and the lead ran out";
				}
				else if (!atFence && (now2 >= (fight.fightStartMillis + _playerGraceMaxMillis)))
				{
					why = "the watcher's grace ran out";
				}
				else if (now2 >= (fight.fightStartMillis + _playerGraceMaxMillis + 60000L))
				{
					why = "hard cap";
				}
				if (why == null)
				{
					return false; // still trading words
				}
				// WHO swings first: the side that did more of the stirring -
				// but a Peaceful clan never throws the first blow (v0.2.3).
				boolean aSnaps = (fight.aggressionA == fight.aggressionB) ? Rnd.nextBoolean() : (fight.aggressionA > fight.aggressionB);
				final ClanProfile peaceCheckA = _profiles.get(fight.clanAId);
				final ClanProfile peaceCheckB = _profiles.get(fight.clanBId);
				final boolean peacefulA = (peaceCheckA != null) && (peaceCheckA.getPersona() == PersonaType.PEACEFUL);
				final boolean peacefulB = (peaceCheckB != null) && (peaceCheckB.getPersona() == PersonaType.PEACEFUL);
				if (peacefulA && peacefulB)
				{
					// Two Peaceful clans: rarely, one of them does lose its
					// temper; otherwise nobody swings and the duel is dropped.
					if (Rnd.get(100) >= _peacefulEngageChance)
					{
						if (_infoHook != null)
						{
							_infoHook.accept("clan-personalities: [FIELD-PEACE] " + why + ", but both sides are Peaceful and neither will throw the first blow - the duel is dropped.");
						}
						endFight(fight, null, "neither side would throw the first blow - both walked away");
						return true;
					}
					if (_infoHook != null)
					{
						_infoHook.accept("clan-personalities: [FIELD-PEACE] both sides are Peaceful, but one of them loses its patience after all (" + _peacefulEngageChance + "% roll).");
					}
				}
				else if (peacefulA && aSnaps)
				{
					aSnaps = false; // the other side swings; the Peaceful one only answers
				}
				else if (peacefulB && !aSnaps)
				{
					aSnaps = true;
				}
				final List<Player> snapSide = aSnaps ? aliveA : aliveB;
				final Player striker = snapSide.get(Rnd.get(snapSide.size()));
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [FIELD-FUSE] " + why + ": " + striker.getName() + " of " + clanNameOf(_profiles.get(aSnaps ? fight.clanAId : fight.clanBId)) + " throws the first blow (stirring A " + fight.aggressionA + " / B " + fight.aggressionB + ").");
				}
				brainNear(striker, "You stand face to face with the rival clan outside " + fight.townName + " and you have just LOST your patience: you swing first, right now. ONE very short line as you strike - no shouting.", "delivered [FIELD-STRIKE]");
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
			// v0.2.3: once blows fly the crowd still talks only for a real
			// human in earshot. Before this, gossip, fighter replies and fan
			// cheers ran for an empty field (13 local lines nobody heard).
			final boolean heard = humanInEarshot(fight);
			if (heard)
			{
				crowdGossipBeat(fight, aliveA, aliveB, now2);
			}
			// The peanut gallery does not stay quiet: whoops and boos ride
			// the local air, a couple of beats apart through the duel.
			if (heard && !fight.spectators.isEmpty() && (fight.spectatorBeats < 6) && ((now2 - fight.lastSpectatorMillis) >= 22000))
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
			if (!isHumanWatcher(fight, online))
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
		final boolean sideA = fight.sideA.contains(speaker);
		final Integer ownId = fight.fanClans.get(Integer.valueOf(speaker.getObjectId()));
		final ClanProfile own = _profiles.get((ownId != null) ? ownId : Integer.valueOf(sideA ? fight.clanAId : fight.clanBId));
		final ClanProfile lead = _profiles.get(sideA ? fight.clanAId : fight.clanBId);
		final String draggedNote = (fight.allianceWar && (own != null) && (own != lead) && (own.getPersona() == PersonaType.PEACEFUL)) ? (" Your clan is Peaceful and was dragged into " + ((lead != null) ? clanNameOf(lead) : "an ally") + "'s war - you fight, but you let them know you never wanted this.") : "";
		final int level = fight.aggression;
		final String mood = (level < 40) ? "Both sides are still sizing each other up." : ((level < 75) ? "Tempers are rising fast." : "One more word and somebody swings.");
		brainSpeak(speaker, "You stand face to face with the rival clan, moments before the real clan fight at the " + fight.compassSide + " side of " + fight.townName + " begins. You fight for " + ((own != null) ? clanNameOf(own) : "your clan") + "; your rivals are " + (sideA ? sideTag(fight.clanBId, fight.namesB) : sideTag(fight.clanAId, fight.namesA)) + " - taunt THEM, never your own clan." + draggedNote + " " + mood + " In ONE short line, taunt your rivals or egg the onlookers toward the start. This line is pure attitude - no directions needed. No shouting." + allianceNote(fight, false) + ((Rnd.get(100) < 35) ? historyTail(own, _profiles.get(sideA ? fight.clanBId : fight.clanAId)) : ""), "delivered [FIELD-TRASH]", false, t ->
		{
			fight.spoke.add(speaker.getName());
			int gain = _aggroMin + Rnd.get(Math.max(1, (_aggroMax - _aggroMin) + 1));
			gain = (gain * aggressionHeat((own != null) ? own.getPersona() : null)) / 100;
			final ReadType read = classifyLine(t);
			if (read == ReadType.THREAT)
			{
				gain += 8;
			}
			else if ((read == ReadType.PROVOCATION) || (read == ReadType.AMBIGUOUS))
			{
				gain += 4;
			}
			if (sideA)
			{
				fight.aggressionA += gain;
			}
			else
			{
				fight.aggressionB += gain;
			}
			fight.aggression = Math.min(100, fight.aggression + gain);
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [FIELD-FUSE] " + speaker.getName() + "'s jab adds " + gain + " - aggression " + fight.aggression + "/100.");
			}
		}, null);
	}

	/** Every few seconds, maybe one fan gossips about a fighter. */
	private void crowdGossipBeat(FieldFight fight, List<Player> aliveA, List<Player> aliveB, long now)
	{
		if (fight.spectators.isEmpty() || (fight.talkBeats >= 14) || ((now - fight.lastTalkMillis) < _spectatorTalkMillis))
		{
			return;
		}
		fight.lastTalkMillis = now;
		if (Rnd.get(100) >= _spectatorTalkChance)
		{
			return;
		}
		fight.talkBeats++;
		final Player fan = fight.spectators.get(Rnd.get(fight.spectators.size()));
		final List<Player> fighters = new ArrayList<>(aliveA);
		fighters.addAll(aliveB);
		ThreadPool.schedule(() ->
		{
			if (fight.running)
			{
				spectatorTalk(fight, fan, fighters);
			}
		}, 300 + Rnd.get(1500));
	}

	/** The pair's history as a prompt tail (empty when there is none). */
	private String historyTail(ClanProfile self, ClanProfile other)
	{
		final String history = historyNote(self, other);
		return (history != null) ? (" " + history) : "";
	}

	/** Until they are released, the fight's bodies only defend: any monster
	 *  that has one of them as its target and is fighting gets hit back.
	 *  Nobody goes looking for mobs. */
	private void scheduleAftermathGuard(List<Player> bodies, long untilMillis)
	{
		ThreadPool.schedule(() ->
		{
			for (Player body : bodies)
			{
				if ((body == null) || !body.isOnline() || body.isAlikeDead())
				{
					continue;
				}
				try
				{
					for (Monster monster : World.getInstance().getVisibleObjectsInRange(body, Monster.class, 900))
					{
						if (!monster.isDead() && (monster.getTarget() == body) && (monster.isAttackingNow() || monster.isInCombat()))
						{
							PhantomCombatActions.maintainAttack(body, monster);
							break;
						}
					}
				}
				catch (Exception e)
				{
					// a guard beat must never break the cleanup
				}
			}
			if (System.currentTimeMillis() < (untilMillis + 12000L))
			{
				scheduleAftermathGuard(bodies, untilMillis);
			}
		}, 2000);
	}

	/** Extra prompt text when the two clans share one alliance banner. */
	private static String allianceNote(FieldFight fight, boolean crowd)
	{
		if (!fight.sameAlliance)
		{
			return "";
		}
		final String banner = (fight.allianceName != null) ? (" (" + fight.allianceName + ")") : "";
		return crowd
			? " NOTE: both clans fly the SAME alliance banner" + banner + " - alliance brothers turning on each other; remark on that."
			: " NOTE: you and your rivals fly the SAME alliance banner" + banner + " - brothers turning on each other; let that show (bitterness, disbelief, or 'alliance means nothing today').";
	}

	/** How hot a temperament's jabs run, in percent of the base gain. */
	private static int aggressionHeat(PersonaType persona)
	{
		return (persona == PersonaType.PLAYER_KILLING) ? 140
			: (persona == PersonaType.AGGRESSIVE) ? 130
			: (persona == PersonaType.NEUTRAL) ? 100
			: (persona == PersonaType.ALTRUISTIC) ? 90
			: (persona == PersonaType.PEACEFUL) ? 70
			: 100;
	}

	// ── Crowd gossip: gear, class, clan record - and the fighter answers ──

	/** One true fact about a fighter, for the crowd to gossip about. */
	private String fighterFact(FieldFight fight, Player subject)
	{
		final boolean sideA = fight.sideA.contains(subject);
		final ClanProfile clan = _profiles.get(sideA ? fight.clanAId : fight.clanBId);
		final String who = subject.getName() + " of " + clanNameOf(clan);
		final int topic = Rnd.get(3);
		try
		{
			if (topic == 0)
			{
				final Item weapon = subject.getActiveWeaponInstance();
				if (weapon == null)
				{
					return who + " is fighting without a weapon in hand";
				}
				final ItemGrade grade = weapon.getTemplate().getItemGrade();
				return who + " wields " + weapon.getTemplate().getName() + " (" + ((grade == null) || (grade.ordinal() == 0) ? "no-grade" : (grade.name() + "-grade")) + ((weapon.getEnchantLevel() > 0) ? (", +" + weapon.getEnchantLevel()) : "") + ")";
			}
			if (topic == 1)
			{
				final String cls = (subject.getPlayerClass() != null) ? subject.getPlayerClass().name().toLowerCase().replace('_', ' ') : "fighter";
				return who + " is a level " + subject.getLevel() + " " + cls;
			}
			final Clan real = ClanTable.getInstance().getClan(sideA ? fight.clanAId : fight.clanBId);
			final StringBuilder record = new StringBuilder(clanNameOf(clan));
			if (real != null)
			{
				record.append(" is a level ").append(real.getLevel()).append(" clan with ").append(real.getMembersCount()).append(" members and ").append(real.getReputationScore()).append(" reputation");
				if (real.getCastleId() > 0)
				{
					record.append(", and it holds a castle");
				}
			}
			int quarrels = 0;
			int fierce = 0;
			for (Story story : _warStories.values())
			{
				if ((clan != null) && ((story.firstClanId == clan.getId()) || (story.secondClanId == clan.getId())))
				{
					quarrels++;
					if (story.fierce)
					{
						fierce++;
					}
				}
			}
			if (quarrels > 0)
			{
				record.append("; lately it closed ").append(quarrels).append(" quarrel(s), ").append(fierce).append(" of them with a war cry");
			}
			return record.toString();
		}
		catch (Exception e)
		{
			return who + " is in the fight";
		}
	}

	/** A fan gossips to the fan beside them about one fighter; the fighter
	 *  may confirm or correct it. Facts are real; the gossip may be off. */
	private void spectatorTalk(FieldFight fight, Player fan, List<Player> fighters)
	{
		if (fighters.isEmpty() || !fan.isOnline() || fan.isAlikeDead())
		{
			return;
		}
		final Player subject = fighters.get(Rnd.get(fighters.size()));
		final String fact = fighterFact(fight, subject);
		final boolean garble = Rnd.get(100) < 35;
		brainSpeak(fan, "You stand in the crowd at a REAL clan fight outside " + fight.townName + ", chatting with the fan beside you about the fighters. " + matchupNote(fight, fan, false) + " The fact: " + fact + ". " + (garble ? "Gossip it slightly WRONG - exaggerate or misremember one detail, the way crowd talk does." : "Gossip it as you heard it.") + " ONE short line to the person next to you, naming " + subject.getName() + "." + allianceNote(fight, true), "delivered [CROWD-TALK]", false, t ->
		{
			if (Rnd.get(100) >= _fighterAnswerChance)
			{
				return;
			}
			ThreadPool.schedule(() ->
			{
				if (fight.running && subject.isOnline() && !subject.isAlikeDead())
				{
					brainNear(subject, "A spectator in the crowd just said about you: \"" + t + "\". The truth: " + fact + ". Answer them in ONE short line: confirm it if they got it right, deny or correct it if they didn't - with attitude.", "delivered [FIGHTER-REPLY]");
				}
			}, 4000 + Rnd.get(5000));
		}, null);
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
			fitWeapon(ally);
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
		// Most of the crowd is whoever wandered by - clanless folk and other
		// clans love a brawl. A seat or two may go to the fighting clans' own
		// people or their alliance, come to cheer (and to tell the world).
		final List<FakePlayerAppearance> kin = kinFanBook(fight, taken);
		final int kinSeats = (kin.isEmpty() || (Rnd.get(100) >= 60)) ? 0 : ((wanted >= 6) && (Rnd.get(100) < 40) ? 2 : 1);
		final List<FakePlayerAppearance> seats = new ArrayList<>();
		for (int i = 0; (i < kinSeats) && (i < kin.size()); i++)
		{
			seats.add(kin.get(i));
		}
		seats.addAll(identityBook(60));
		for (FakePlayerAppearance appearance : seats)
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
				taken.add(appearance.getName());
				if (appearance.getClanId() > 0)
				{
					fight.fanClans.put(Integer.valueOf(fan.getObjectId()), Integer.valueOf(appearance.getClanId()));
				}
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

	/** Town members of the two fighting clans and their alliances who could
	 *  come to watch (fighters excluded), shuffled. */
	private List<FakePlayerAppearance> kinFanBook(FieldFight fight, Set<String> taken)
	{
		final List<FakePlayerAppearance> book = new ArrayList<>();
		final ClanProfile a = _profiles.get(fight.clanAId);
		final ClanProfile b = _profiles.get(fight.clanBId);
		for (ClanProfile profile : _profiles.values())
		{
			if ((profile != a) && (profile != b) && !(((a != null) && profile.isAlliedWith(a)) || ((b != null) && profile.isAlliedWith(b))))
			{
				continue;
			}
			try
			{
				for (Npc member : clanMembers(profile))
				{
					final FakePlayerAppearance appearance = member.getFakePlayerAppearance();
					if ((appearance != null) && (appearance.getName() != null) && !taken.contains(appearance.getName()))
					{
						book.add(appearance);
					}
				}
			}
			catch (Exception e)
			{
				// a clan we can't read just sends nobody
			}
		}
		Collections.shuffle(book);
		return book;
	}

	/** A voice from the ring: an ooh, a cheer, a jab - LOCALLY. */
	private void crowdLine(FieldFight fight, Player fan)
	{
		if (!fan.isOnline() || fan.isAlikeDead())
		{
			return;
		}
		brainNear(fan, "You are in the crowd ringing a REAL clan duel at the " + fight.compassSide + " side of " + fight.townName + ". " + matchupNote(fight, fan, true) + " In ONE short line, react to the fight as it stands: an ooh, a cheer for one side, a jab at the side losing. No directions needed." + allianceNote(fight, true), "delivered [CROWD]");
	}

	/** One sword keeps its face; the pack's playstyle engine adds casts. */
	private void pairDrive(FieldFight fight, Player attacker, Creature defender)
	{
		// Skill FIRST: ordering a melee swing before the cast used to cancel
		// the spell, so mages ended up beating people with their staves.
		final String role = playRole(attacker);
		final boolean caster = isCasterRole(role);
		final boolean support = "HEALER".equals(role) || "BUFFER".equals(role);
		if (support && supportDrive(fight, attacker, role))
		{
			fight.casterDry.remove(attacker.getName());
			return; // healed or buffed a squadmate this beat
		}
		if (driveSkills(fight, attacker, defender, role))
		{
			fight.casterDry.remove(attacker.getName());
			return;
		}
		if (!caster)
		{
			PhantomCombatActions.maintainAttack(attacker, defender);
			return;
		}
		// A caster with nothing to cast this beat waits for its next spell;
		// only a dry spell of several beats or an empty MP bar sends it in
		// with the staff - the way a player out of mana would.
		final int dry = fight.casterDry.merge(attacker.getName(), Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + b.intValue())).intValue();
		if (support)
		{
			// Healers and buffers hold back with the staff: they swing only
			// when out of mana, left standing alone on their side, or when
			// nobody on their side can fight (playtest 2026-10-21: a side of
			// two healers only healed, and the duel ran out as a lopsided
			// draw - one side dealt every blow).
			if ((attacker.getCurrentMpPercent() < 8) || (squadOf(fight, attacker).size() <= 1) || !squadHasStriker(fight, attacker))
			{
				PhantomCombatActions.maintainAttack(attacker, defender);
			}
			return;
		}
		if ((dry >= 4) || (attacker.getCurrentMpPercent() < 8))
		{
			PhantomCombatActions.maintainAttack(attacker, defender);
		}
	}

	/** True while someone living on this fighter's side is not a healer or buffer. */
	private static boolean squadHasStriker(FieldFight fight, Player fighter)
	{
		for (Player mate : squadOf(fight, fighter))
		{
			final String role = playRole(mate);
			if (!"HEALER".equals(role) && !"BUFFER".equals(role))
			{
				return true;
			}
		}
		return false;
	}

	/** The living fighters on the given fighter's own side. */
	private static List<Player> squadOf(FieldFight fight, Player fighter)
	{
		final List<Player> squad = new ArrayList<>();
		for (Player mate : (fight.sideA.contains(fighter) ? fight.sideA : fight.sideB))
		{
			if ((mate != null) && mate.isOnline() && !mate.isAlikeDead())
			{
				squad.add(mate);
			}
		}
		return squad;
	}

	/** Most buffs one support fighter casts in a duel - then it heals and fights. */
	private static final int SUPPORT_BUFF_CAP = 4;

	/**
	 * A healer's or buffer's turn: heal the most wounded squadmate (under
	 * 70% HP) if it knows a heal; otherwise, early in the duel, lay a buff a
	 * squadmate lacks. Uses the fighter's OWN known skills, through the
	 * normal cast path (target, then useMagic). True when a cast went out.
	 */
	private boolean supportDrive(FieldFight fight, Player healer, String role)
	{
		try
		{
			if (healer.isCastingNow())
			{
				return true; // mid-cast: let it land
			}
			final List<Player> squad = squadOf(fight, healer);
			// 1) Heal the most wounded.
			Player wounded = null;
			double lowest = 70.0;
			for (Player mate : squad)
			{
				final double pct = (mate.getCurrentHp() * 100.0) / Math.max(1, mate.getMaxHp());
				if (pct < lowest)
				{
					lowest = pct;
					wounded = mate;
				}
			}
			if (wounded != null)
			{
				final Skill heal = bestSupportSkill(healer, wounded, true);
				if ((heal != null) && castSupport(fight, healer, wounded, heal, "heals"))
				{
					return true;
				}
			}
			// 2) Buff a squadmate who lacks one (capped per duel).
			final int buffs = fight.supportBuffs.getOrDefault(healer.getName(), Integer.valueOf(0)).intValue();
			if (buffs < SUPPORT_BUFF_CAP)
			{
				for (Player mate : squad)
				{
					final Skill buff = bestSupportSkill(healer, mate, false);
					if ((buff != null) && castSupport(fight, healer, mate, buff, "buffs"))
					{
						fight.supportBuffs.put(healer.getName(), Integer.valueOf(buffs + 1));
						return true;
					}
				}
			}
			return false;
		}
		catch (Exception e)
		{
			if (fight.notedSkillNotes.add("support-fail:" + healer.getName()) && (_infoHook != null))
			{
				_infoHook.accept("clan-personalities: [SUPPORT] " + healer.getName() + " could not support this beat (" + e + ").");
			}
			return false;
		}
	}

	/**
	 * The fighter's best usable heal (heal = true) or buff for this mate:
	 * active, friendly, affordable, off cooldown, aimable at the mate (self
	 * skills only on itself), and - for buffs - not already on the mate.
	 * Higher-level, costlier skills win (they are the stronger ranks).
	 */
	private static Skill bestSupportSkill(Player caster, Player mate, boolean heal)
	{
		Skill best = null;
		for (Skill skill : caster.getAllSkills())
		{
			if ((skill == null) || skill.isPassive() || !skill.isActive() || skill.isToggle() || skill.isDebuff() || skill.hasNegativeEffect() || skill.isDamage())
			{
				continue;
			}
			if (caster.isSkillDisabled(skill) || (skill.getMpConsume() > caster.getCurrentMp()))
			{
				continue;
			}
			if (heal ? !skill.hasEffectType(EffectType.HEAL) : (!skill.hasEffectType(EffectType.BUFF) || !skill.isContinuous() || mate.isAffectedBySkill(skill.getId())))
			{
				continue;
			}
			final TargetType target = skill.getTargetType();
			final boolean selfOnly = (target == TargetType.SELF);
			final boolean friendly = selfOnly || (target == TargetType.ONE) || (target == TargetType.PARTY) || (target == TargetType.PARTY_MEMBER) || (target == TargetType.PARTY_OTHER) || (target == TargetType.PARTY_CLAN) || (target == TargetType.TARGET_PARTY) || (target == TargetType.CLAN) || (target == TargetType.CLAN_MEMBER) || (target == TargetType.AREA_FRIENDLY) || (target == TargetType.AURA_FRIENDLY);
			if (!friendly || (selfOnly && (mate != caster)) || ((target == TargetType.PARTY_OTHER) && (mate == caster)))
			{
				continue;
			}
			if ((best == null) || (skill.getMpConsume() > best.getMpConsume()))
			{
				best = skill;
			}
		}
		return best;
	}

	private boolean castSupport(FieldFight fight, Player healer, Player mate, Skill skill, String verb)
	{
		healer.setTarget(mate);
		final boolean cast = healer.useMagic(skill, false, false);
		if (cast && fight.notedSkillNotes.add("support:" + verb + ":" + healer.getName()) && (_infoHook != null))
		{
			_infoHook.accept("clan-personalities: [SUPPORT] " + healer.getName() + " " + verb + " " + ((mate == healer) ? "itself" : mate.getName()) + " with " + skill.getName() + ".");
		}
		return cast;
	}

	/** Weapon types a skill may only be used with (empty = any weapon). */
	@SuppressWarnings("unchecked")
	private static int skillWeaponMask(Skill skill)
	{
		int mask = 0;
		for (String fieldName : new String[] { "_preCondition", "_itemPreCondition" })
		{
			try
			{
				final java.lang.reflect.Field field = Skill.class.getDeclaredField(fieldName);
				field.setAccessible(true);
				final List<Condition> conditions = (List<Condition>) field.get(skill);
				if (conditions == null)
				{
					continue;
				}
				for (Condition condition : conditions)
				{
					if (!(condition instanceof ConditionUsingItemType))
					{
						continue;
					}
					final java.lang.reflect.Field armor = ConditionUsingItemType.class.getDeclaredField("_armor");
					armor.setAccessible(true);
					if (armor.getBoolean(condition))
					{
						continue; // an armor condition, not a weapon one
					}
					final java.lang.reflect.Field m = ConditionUsingItemType.class.getDeclaredField("_mask");
					m.setAccessible(true);
					mask |= m.getInt(condition);
				}
			}
			catch (Exception e)
			{
				// unreadable on this build: treat as "any weapon"
			}
		}
		return mask;
	}

	/**
	 * Gives a fighter a weapon its skills can actually be used with. Counts,
	 * over the fighter's active skills, which weapon types they demand; if
	 * the weapon in hand is not one of them, a weapon of the most demanded
	 * type, the same crystal grade and the closest value is equipped (the
	 * clone is deleted after the fight, so nothing is kept). Casters, and
	 * classes whose skills name no weapon, keep what they hold. Bows are
	 * never handed out (they would need arrows).
	 */
	private void fitWeapon(Player fighter)
	{
		try
		{
			final String role = playRole(fighter);
			if (isCasterRole(role))
			{
				return;
			}
			final Map<WeaponType, Integer> demand = new LinkedHashMap<>();
			for (Skill skill : fighter.getAllSkills())
			{
				if ((skill == null) || skill.isPassive() || !skill.isActive())
				{
					continue;
				}
				final int mask = skillWeaponMask(skill);
				if (mask == 0)
				{
					continue;
				}
				for (WeaponType type : WeaponType.values())
				{
					if ((type.mask() != 0) && ((mask & type.mask()) != 0))
					{
						demand.merge(type, Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + b.intValue()));
					}
				}
			}
			if (demand.isEmpty())
			{
				return; // no weapon-bound skills: any steel serves
			}
			final Weapon current = fighter.getActiveWeaponItem();
			final WeaponType held = (current != null) ? current.getItemType() : WeaponType.NONE;
			if (demand.containsKey(held))
			{
				return; // it already fits
			}
			WeaponType want = null;
			int best = 0;
			for (Map.Entry<WeaponType, Integer> entry : demand.entrySet())
			{
				final WeaponType type = entry.getKey();
				if ((type == WeaponType.BOW) || (type == WeaponType.NONE) || (type == WeaponType.ETC) || (type == WeaponType.FISHINGROD) || (type == WeaponType.FLAG) || (type == WeaponType.OWNTHING))
				{
					continue;
				}
				if (entry.getValue().intValue() > best)
				{
					best = entry.getValue().intValue();
					want = type;
				}
			}
			if (want == null)
			{
				return;
			}
			final ItemGrade grade = (current != null) ? current.getItemGrade() : gradeForLevel(fighter.getLevel());
			final int price = (current != null) ? current.getReferencePrice() : 0;
			Weapon pick = null;
			long pickGap = Long.MAX_VALUE;
			for (Weapon weapon : ItemData.getInstance().getAllWeapons())
			{
				if ((weapon == null) || (weapon.getItemType() != want) || (weapon.getItemGrade() != grade) || weapon.isMagicWeapon())
				{
					continue;
				}
				final long gap = Math.abs((long) weapon.getReferencePrice() - price);
				if (gap < pickGap)
				{
					pickGap = gap;
					pick = weapon;
				}
			}
			if (pick == null)
			{
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [GEAR] " + fighter.getName() + " (" + role + ") holds a " + held + " but its skills want " + want + " - no " + want + " of " + grade + " grade found; it keeps its weapon.");
				}
				return;
			}
			final Item item = fighter.getInventory().addItem(ItemProcessType.NONE, pick.getId(), 1, fighter, null);
			if (item != null)
			{
				fighter.useEquippableItem(item, true);
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [GEAR] " + fighter.getName() + " (" + role + ") held " + ((current != null) ? current.getName() : "nothing") + " (" + held + "), but its skills want " + want + " - takes up " + pick.getName() + " (" + grade + " grade).");
				}
			}
		}
		catch (Exception e)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [GEAR] could not fit a weapon for " + fighter.getName() + " (" + e + ").");
			}
		}
	}

	/** Interlude's grade by level, for a fighter holding no weapon at all. */
	private static ItemGrade gradeForLevel(int level)
	{
		final ItemGrade[] grades = ItemGrade.values();
		final int index = (level >= 76) ? 5 : (level >= 61) ? 4 : (level >= 52) ? 3 : (level >= 40) ? 2 : (level >= 20) ? 1 : 0;
		return grades[Math.min(index, grades.length - 1)];
	}

	/** Skills that only work on monsters (or out of a fight): useless - or
	 *  a stall - against a player. Spoil made a fighter stop attacking. */
	private boolean pvpUseless(Skill skill)
	{
		if (skill == null)
		{
			return false;
		}
		final String name = skill.getName();
		if (name == null)
		{
			return false;
		}
		final String lower = name.toLowerCase();
		for (String banned : _fieldSkillBlacklist)
		{
			if (lower.equals(banned))
			{
				return true;
			}
		}
		return false;
	}

	/** The pack's own party role for a class (TANK, WARRIOR, NUKER...). Its
	 *  playstyles are keyed by this name - the old "field duel" key matched
	 *  nothing, so no fighter ever picked a skill. */
	private static String playRole(Player player)
	{
		try
		{
			return PhantomManager.roleForClass(player.getPlayerClass()).name();
		}
		catch (Exception e)
		{
			return "WARRIOR";
		}
	}

	private static boolean isCasterRole(String role)
	{
		return "NUKER".equals(role) || "HEALER".equals(role) || "BUFFER".equals(role);
	}

	/**
	 * Cast driving: the pack's own engine picks a real skill for the
	 * fighter against the defender, executes it through the combat
	 * actions, and confirms the cast in the fighter's play memory so
	 * rotations and cooldowns behave like a player's.
	 */
	private boolean driveSkills(FieldFight fight, Player attacker, Creature defender, String role)
	{
		try
		{
			if (!PhantomPlaystyleEngine.hasBaseline(attacker))
			{
				if (fight.notedSkillNotes.add(attacker.getName()) && (_infoHook != null))
				{
					_infoHook.accept("clan-personalities: [SKILLS] " + attacker.getName() + " has no playstyle baseline on file - steel only.");
				}
				return false;
			}
			PhantomPlaystyleEngine.PlayState state = fight.states.get(attacker.getName());
			if (state == null)
			{
				state = new PhantomPlaystyleEngine.PlayState();
				fight.states.put(attacker.getName(), state);
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [SKILLS] " + attacker.getName() + " fights as " + role + ".");
				}
			}
			// Same call shape the pack's own hunters use: (no healer at hand,
			// under attack, no MP reserve held back, the class's role).
			PhantomPlaystyleEngine.CastAction cast = PhantomPlaystyleEngine.choose(attacker, defender, state, false, true, 0, role);
			if ((cast == null) || pvpUseless(cast.skill))
			{
				cast = PhantomPlaystyleEngine.pick(attacker, defender, state, false, true, 0, role);
			}
			if ((cast == null) || pvpUseless(cast.skill))
			{
				cast = PhantomPlaystyleEngine.pickFallback(attacker, defender, state, 0);
			}
			// A mage OPENS with a nuke (playtest 2026-10-21: a nuker opened
			// a 1v1 with Curse: Poison against a dagger and died in 14 s).
			// Until its first damage spell has gone out, a non-damage pick
			// is turned back and the engine asked again, a few times.
			if ("NUKER".equals(role) && !fight.notedSkillNotes.contains("nuked:" + attacker.getName()))
			{
				for (int retry = 0; (retry < 4) && (cast != null) && (cast.skill != null) && !cast.skill.isDamage(); retry++)
				{
					final String skipped = cast.skill.getName();
					PhantomPlaystyleEngine.markRejected(state, cast);
					PhantomPlaystyleEngine.CastAction again = PhantomPlaystyleEngine.choose(attacker, defender, state, false, true, 0, role);
					if ((again == null) || pvpUseless(again.skill))
					{
						again = PhantomPlaystyleEngine.pick(attacker, defender, state, false, true, 0, role);
					}
					if ((again != null) && (again.skill != null) && (_infoHook != null) && fight.notedSkillNotes.add("opener:" + attacker.getName()))
					{
						_infoHook.accept("clan-personalities: [SKILLS] " + attacker.getName() + " (NUKER) passes on " + skipped + " to open with damage.");
					}
					if (again == null)
					{
						break; // nothing else: keep the first pick
					}
					cast = again;
				}
				if ((cast != null) && (cast.skill != null) && cast.skill.isDamage())
				{
					fight.notedSkillNotes.add("nuked:" + attacker.getName());
				}
			}
			if ((cast != null) && pvpUseless(cast.skill))
			{
				if (fight.notedSkillNotes.add("useless:" + attacker.getName() + ":" + cast.skill.getName()) && (_infoHook != null))
				{
					_infoHook.accept("clan-personalities: [SKILLS] " + attacker.getName() + " skips " + cast.skill.getName() + " (monster-only, useless on a player) and keeps hitting.");
				}
				cast = null;
			}
			if (cast == null)
			{
				if (fight.notedSkillNotes.add("nopick:" + attacker.getName()) && (_infoHook != null))
				{
					_infoHook.accept("clan-personalities: [SKILLS] nothing castable for " + attacker.getName() + " (" + role + ") this beat.");
				}
				return false;
			}
			PhantomCombatActions.execute(attacker, defender, state, cast, true);
			PhantomPlaystyleEngine.confirmCast(state, cast);
			if (fight.notedSkillNotes.add("cast:" + attacker.getName()) && (_infoHook != null))
			{
				_infoHook.accept("clan-personalities: [SKILLS] " + attacker.getName() + " (" + role + ") opens with " + ((cast.skill != null) ? cast.skill.getName() : "a skill") + ".");
			}
			return true;
		}
		catch (Exception e)
		{
			fight.states.remove(attacker.getName());
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [SKILLS] a cast fell apart for " + attacker.getName() + " (" + e + "); their play memory resets.");
			}
			return false;
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
	/** A real player standing RIGHT beside the fighters (VeryCloseRadius). */
	private boolean playerVeryClose(FieldFight fight)
	{
		final long radiusSq = (long) _veryCloseRadius * _veryCloseRadius;
		for (Player online : allPlayers())
		{
			if (!isHumanWatcher(fight, online))
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

	private boolean playerAtFence(FieldFight fight)
	{
		// FenceRadius (900): close enough that every fighter's LOCAL line
		// (1250 from the speaker) reaches the watcher - at 1400 the far
		// side's first jabs could be out of earshot.
		final long radiusSq = (long) _fenceRadius * _fenceRadius;
		for (Player online : allPlayers())
		{
			if (!isHumanWatcher(fight, online))
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

	/** v0.2.3: a REAL human close enough to hear the crowd's local chat.
	 *  Fans ring the field at 550-800 and local chat carries 1250, so
	 *  anyone within 2000 of the arena centre can hear some of it. */
	private boolean humanInEarshot(FieldFight fight)
	{
		for (Player online : allPlayers())
		{
			if (!isHumanWatcher(fight, online))
			{
				continue;
			}
			final long dx = online.getX() - fight.arenaX;
			final long dy = online.getY() - fight.arenaY;
			if (((dx * dx) + (dy * dy)) <= 4000000L)
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
			if (!isHumanWatcher(fight, online))
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
	/**
	 * Who is actually fighting, for crowd prompts (playtest 2026-10-21: with
	 * no matchup in the prompt, fans credited a bystander clan standing in
	 * the crowd - "ivorycrown's getting crushed" in a SunSpear vs
	 * CrimsonPhoenix duel). Names both sides, the viewer's own place, and -
	 * once fists fly - how each side is doing.
	 */
	private String matchupNote(FieldFight fight, Player viewer, boolean withHealth)
	{
		final ClanProfile a = _profiles.get(fight.clanAId);
		final ClanProfile b = _profiles.get(fight.clanBId);
		final String nameA = (a != null) ? clanNameOf(a) : "one clan";
		final String nameB = (b != null) ? clanNameOf(b) : "the other clan";
		final StringBuilder p = new StringBuilder();
		p.append("THE FIGHT: ").append(sideLabel(fight, true)).append(" against ").append(sideLabel(fight, false)).append(fight.allianceWar ? " - two alliances at war; everyone else here is just watching." : " - only these two clans are fighting; every other clan here is just watching.");
		// Spawned fans don't reliably carry their clan; the fight's own book does.
		final Integer known = fight.fanClans.get(Integer.valueOf(viewer.getObjectId()));
		final int viewerClan = (known != null) ? known.intValue() : viewer.getClanId();
		if (fight.sideA.contains(viewer) || fight.sideB.contains(viewer))
		{
			final ClanProfile own = _profiles.get(viewerClan);
			p.append(" You are one of the fighters, for ").append((own != null) ? clanNameOf(own) : "your clan").append(fight.allianceWar ? (" of the " + (fight.sideA.contains(viewer) ? fight.allianceA : fight.allianceB) + " alliance.") : ".");
		}
		else if (viewerClan == fight.clanAId)
		{
			p.append(" You are from ").append(nameA).append(", one of the two sides.");
		}
		else if (viewerClan == fight.clanBId)
		{
			p.append(" You are from ").append(nameB).append(", one of the two sides.");
		}
		else
		{
			final ClanProfile own = _profiles.get(viewerClan);
			p.append(" You are ").append((own != null) ? ("from " + clanNameOf(own) + ", ") : "").append("an onlooker - your clan is NOT in this fight.");
		}
		if (withHealth && fight.running)
		{
			p.append(" Right now ").append(nameA).append(" is at ").append(sideHealth(fight.sideA)).append("% health and ").append(nameB).append(" at ").append(sideHealth(fight.sideB)).append("%.");
		}
		return p.toString();
	}

	private static int sideHealth(List<Player> side)
	{
		double hp = 0;
		double max = 0;
		for (Player fighter : side)
		{
			if (fighter.isOnline())
			{
				hp += Math.max(0, fighter.getCurrentHp());
				max += fighter.getMaxHp();
			}
		}
		return (max > 0) ? (int) Math.round((hp / max) * 100) : 0;
	}

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
		final Creature opener = duelVoice(fight, fight.clanAId);
		if (opener == null)
		{
			return;
		}
		final VoiceChoice choice = new VoiceChoice(opener, 0, "", false);
		String cause = _pairCauses.get(arcKey(fight.clanAId, fight.clanBId));
		if (cause == null)
		{
			cause = chooseCause(profileA, profileB);
		}
		final String lead = (_fieldLeadSeconds > 0) ? " - fists fly in about a minute, so anyone who wants a seat should start walking" : " - happening right now";
		final String prompt = "Your clan " + clanNameOf(profileA) + " has walked out of town to settle the beef with steel: " + sideTag(fight.clanAId, fight.namesA) + " against " + sideTag(fight.clanBId, fight.namesB) + ", just " + outsidePlace(fight) + lead + ". ONE short hot line - announce the clash, name the place AND the side of town so people can actually find it, and taunt " + clanNameOf(profileB) + " over " + cause + "." + allianceNote(fight, false) + historyTail(profileA, profileB);
		brainSpeak(choice.creature, prompt, "delivered [FIELD-OPEN]", true, t -> _memory.add(fight.clanAId, fight.clanBId, t), townKey(fight.townName));
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
		final Creature caller = duelVoice(fight, speakerClan.getId());
		if (caller == null)
		{
			return;
		}
		final VoiceChoice choice = new VoiceChoice(caller, 0, "", false);
		if (opening)
		{
			String cause = _pairCauses.get(arcKey(fight.clanAId, fight.clanBId));
			if (cause == null)
			{
				cause = chooseCause(speakerClan, otherClan);
			}
			final String targetName = fight.namesB.isEmpty() ? clanNameOf(otherClan) : fight.namesB.get(0);
			// The dare is also how the WORLD learns a fight is on: it must
			// say who and where, loud and plain, in world chat.
			brainSpeak(choice.creature, "Your clan's " + (fight.namesA.isEmpty() ? "champion" : fight.namesA.get(0)) + " is walking out RIGHT NOW to fight " + targetName + " of " + clanNameOf(otherClan) + " " + outsidePlace(fight) + ", over " + cause + ". In ONE short world-chat line, call " + targetName + " out by name to that fight and name the place (" + townKey(fight.townName) + ", " + fight.compassSide + " side) - a real call-out to steel, not small talk." + allianceNote(fight, false) + historyTail(speakerClan, otherClan), "delivered [FIELD-OPEN]", true, t -> _memory.add(fight.clanAId, fight.clanBId, t), townKey(fight.townName));
			// The rival clan gets one answer; then only the field speaks.
			ThreadPool.schedule(() -> fieldDuelCallout(fight, false), 5000 + Rnd.get(5000));
		}
		else
		{
			brainSpeak(choice.creature, "You were just called out by name by " + clanNameOf(otherClan) + " to fight " + outsidePlace(fight) + ", and your fighters are heading there now. In ONE short world-chat line, ANSWER them: accept with heat or sharpen the mockery, and say you'll be at " + townKey(fight.townName) + "." + allianceNote(fight, false) + historyTail(speakerClan, otherClan), "delivered [FIELD-ANSWER]", true, t -> _memory.add(fight.clanBId, fight.clanAId, t), townKey(fight.townName));
		}
	}

	/**
	 * Who may talk about a duel in WORLD chat: someone who could KNOW about
	 * it - physically there, or tied to the clans fighting. Nobody across the
	 * world hears of a duel by magic. In order:
	 * 1) a fighter of the named clan (standing, then fallen - the dead can
	 *    still type);
	 * 2) a spectator of that clan or its alliance;
	 * 3) any spectator - only for neutral lines (clanId 0), since a
	 *    stranger can't say "we won";
	 * 4) a member of the clan somewhere else in the world (word travels
	 *    inside a clan);
	 * 5) for neutral lines, an alliance-mate elsewhere (alliance chat).
	 * A clan with no tie to the fight never speaks of it.
	 * clanId 0 = either side, the aggressor first. Null when nobody
	 * qualifies: the line simply isn't said.
	 */
	private Creature duelVoice(List<Player> sideA, List<Player> sideB, List<Player> fans, Map<Integer, Integer> fanClans, int clanAId, int clanBId, int clanId)
	{
		final List<int[]> order = new ArrayList<>();
		if ((clanId == 0) || (clanId == clanAId))
		{
			order.add(new int[] { 0 });
		}
		if ((clanId == 0) || (clanId == clanBId))
		{
			order.add(new int[] { 1 });
		}
		// 1) Fighters, standing first.
		for (int pass = 0; pass < 2; pass++)
		{
			for (int[] side : order)
			{
				final List<Player> squad = new ArrayList<>((side[0] == 0) ? sideA : sideB);
				Collections.shuffle(squad);
				for (Player fighter : squad)
				{
					if ((fighter != null) && fighter.isOnline() && ((pass == 1) || !fighter.isAlikeDead()))
					{
						return fighter;
					}
				}
			}
		}
		// 2) A spectator of the clan, or of its alliance.
		final List<Player> ring = new ArrayList<>(fans);
		Collections.shuffle(ring);
		for (int[] side : order)
		{
			final ClanProfile own = _profiles.get((side[0] == 0) ? clanAId : clanBId);
			if (own == null)
			{
				continue;
			}
			for (Player fan : ring)
			{
				if ((fan == null) || !fan.isOnline())
				{
					continue;
				}
				final Integer known = (fanClans != null) ? fanClans.get(Integer.valueOf(fan.getObjectId())) : null;
				final int fanClanId = (known != null) ? known.intValue() : fan.getClanId();
				final ClanProfile fanClan = _profiles.get(Integer.valueOf(fanClanId));
				if ((fanClanId == own.getId()) || ((fanClan != null) && fanClan.isAlliedWith(own)))
				{
					return fan;
				}
			}
		}
		// 3) Any spectator, for a line that takes no side.
		if (clanId == 0)
		{
			for (Player fan : ring)
			{
				if ((fan != null) && fan.isOnline() && !fan.isAlikeDead())
				{
					return fan;
				}
			}
		}
		// 4) A clanmate elsewhere: they heard it from their own.
		for (int[] side : order)
		{
			final ClanProfile own = _profiles.get((side[0] == 0) ? clanAId : clanBId);
			final VoiceChoice member = (own != null) ? chooseVoice(own) : null;
			if (member != null)
			{
				return member.creature;
			}
		}
		// 5) For a line that takes no side: an alliance-mate elsewhere (they
		//    read alliance chat). Anyone else never heard of it.
		if (clanId == 0)
		{
			final ClanProfile a = _profiles.get(clanAId);
			final ClanProfile b = _profiles.get(clanBId);
			final List<ClanProfile> kin = new ArrayList<>();
			for (ClanProfile profile : _profiles.values())
			{
				if ((profile != a) && (profile != b) && (((a != null) && profile.isAlliedWith(a)) || ((b != null) && profile.isAlliedWith(b))))
				{
					kin.add(profile);
				}
			}
			Collections.shuffle(kin);
			for (ClanProfile profile : kin)
			{
				final VoiceChoice mate = chooseVoice(profile);
				if (mate != null)
				{
					return mate.creature;
				}
			}
		}
		return null;
	}

	private Creature duelVoice(FieldFight fight, int clanId)
	{
		return duelVoice(fight.sideA, fight.sideB, fight.spectators, fight.fanClans, fight.clanAId, fight.clanBId, clanId);
	}

	/** A spectator's one line over the clash; Peaceful clans berate both. */
	private void fieldCrowdLine(ClanProfile crowd, FieldFight fight)
	{
		if (!fight.running || (crowd == null) || (crowd.getPersona() == null))
		{
			return;
		}
		// Outsiders never shout about someone else's duel: the reaction comes
		// from a fan standing in the ring, in LOCAL chat.
		final List<Player> ring = new ArrayList<>();
		for (Player fan : fight.spectators)
		{
			if ((fan != null) && fan.isOnline() && !fan.isAlikeDead())
			{
				ring.add(fan);
			}
		}
		if (ring.isEmpty())
		{
			return;
		}
		final Player watcher = ring.get(Rnd.get(ring.size()));
		final ClanProfile a = _profiles.get(fight.clanAId);
		final ClanProfile b = _profiles.get(fight.clanBId);
		if ((a == null) || (b == null))
		{
			return;
		}
		final String place = outsidePlace(fight);
		String prompt;
		if (crowd.getPersona() == PersonaType.PEACEFUL)
		{
			prompt = "Right now, " + sideTag(fight.clanAId, fight.namesA) + " and " + sideTag(fight.clanBId, fight.namesB) + " are squaring up for real " + place + ", and you are standing in the crowd watching. Berate BOTH sides in ONE short line: no threats, no favorites, pure scorn at senseless violence - the way a tired elder player would.";
		}
		else
		{
			prompt = "Right now, " + sideTag(fight.clanAId, fight.namesA) + " and " + sideTag(fight.clanBId, fight.namesB) + " are squaring up for real " + place + ", and you are standing in the crowd watching as an outsider. React in ONE short line as a spectator - a gasp, a gamble, a jab.";
		}
		brainNear(watcher, prompt, "delivered [CROWD]");
	}

	/** Close a duel: unlock, stand down, and let the world hear the end. */
	/** v0.2.3: odds (%) that one of two Peaceful clans throws the first
	 *  blow after all; otherwise their duel is dropped. */
	private int _peacefulEngageChance = 30;

	void setPeacefulEngageChance(int chance)
	{
		_peacefulEngageChance = Math.max(0, Math.min(100, chance));
	}

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
		// After the fight everyone STAYS PUT (still parked): un-parking them
		// handed them back to the hunting brain, and fighters and fans went
		// off killing the mobs around Dion. Now they only hit back if a
		// monster attacks them, and leave a little sooner.
		final List<Player> lingering = new ArrayList<>();
		lingering.addAll(fight.sideA);
		lingering.addAll(fight.sideB);
		lingering.addAll(fight.heralds);
		lingering.addAll(fight.spectators);
		final long releaseAt = System.currentTimeMillis() + 25000L;
		scheduleAftermathGuard(lingering, releaseAt);
		for (Player body : lingering)
		{
			ThreadPool.schedule(() -> releaseFighter(body), 25000 + Rnd.get(10000));
		}
		final ClanProfile aProfile = _profiles.get(fight.clanAId);
		final ClanProfile bProfile = _profiles.get(fight.clanBId);
		if ((aProfile == null) || (bProfile == null))
		{
			return;
		}
		// The record keeps COMPLETED duels only: blows were exchanged and it
		// ended in a result (a win, or a clean even finish). A fight cut by a
		// restart never reaches here; a scattered one (note) is not a result.
		if (fight.begun)
		{
			if (winnerSide != null)
			{
				recordDuel(fight.clanAId, fight.clanBId, (winnerSide == fight.sideA) ? fight.clanAId : fight.clanBId, fight.townName);
			}
			else if (note == null)
			{
				recordDuel(fight.clanAId, fight.clanBId, 0, fight.townName);
			}
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
			// The world hears it from the people who FOUGHT: a winning fighter
			// (or a winning-side fan of the clan or its alliance) - never a
			// clanmate who was somewhere else.
			final Creature winnerMouth = duelVoice(fight, winnerProfile.getId());
			final VoiceChoice winnerVoice = (winnerMouth != null) ? new VoiceChoice(winnerMouth, 0, "", false) : null;
			if (winnerVoice != null)
			{
				final String loserClan = clanNameOf(loserProfile);
				String prompt;
				if (blood)
				{
					prompt = "The duel " + outsideTown(fight) + " is OVER: " + loserClan + "'s " + loserNames + " are DOWN, your clan " + clanNameOf(winnerProfile) + " won it - your " + winnerNames + " still standing. ONE short line - the victory brag of your temperament, cold or roaring: mention that they fell.";
				}
				else
				{
					prompt = "The duel " + outsideTown(fight) + " is OVER: " + loserClan + "'s " + loserNames + " backed off, your clan " + clanNameOf(winnerProfile) + " takes the field. ONE short line - a firm, mid-temperature brag of your temperament, no threats past a reminder of who won.";
				}
				// A fighter still standing gloats right there on the field,
				// in local chat, to the beaten side and the crowd.
				Player standing = null;
				for (Player fighter : winnerSide)
				{
					if ((fighter != null) && fighter.isOnline() && !fighter.isAlikeDead())
					{
						standing = fighter;
						break;
					}
				}
				if (standing != null)
				{
					final Player gloater = standing;
					ThreadPool.schedule(() ->
					{
						if (gloater.isOnline() && !gloater.isAlikeDead())
						{
							brainNear(gloater, "You just WON a real clan fight outside " + fight.townName + " for " + clanNameOf(winnerProfile) + ": " + loserClan + "'s " + loserNames + (blood ? " lie beaten at your feet" : " backed off") + ", and the crowd is watching. ONE short line gloating over the win, straight at the losers - by your temperament.", "delivered [FIELD-GLOAT]");
						}
					}, 2500 + Rnd.get(2500));
				}
				brainWorld(winnerVoice.creature, prompt.replace("ONE short line - ", "ONE short world-chat line announcing the WIN by name - "), "delivered " + (blood ? "[FIELD-KILL]" : "[FIELD-WIN]"), t ->
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
			final Creature loserMouth = duelVoice(fight, loserProfile.getId());
			final VoiceChoice loserVoice = (loserMouth != null) ? new VoiceChoice(loserMouth, 0, "", false) : null;
			if (loserVoice != null)
			{
				ThreadPool.schedule(() -> brainWorld(loserVoice.creature, "Your clan " + clanNameOf(loserProfile) + " just LOST a field duel " + outsideTown(fight) + " against " + clanNameOf(winnerProfile) + ((blood) ? " - your clansmen " + loserNames + " died there" : " - your clansmen " + loserNames + " had to back off") + ". ONE short line, by your temperament: a bitter howl, a coughed vow of revenge, or a grudging nod.", "delivered [FIELD-LOSS]", t ->
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
						final Creature blamer = duelVoice(fight, loserProfile.getId());
						if (blamer == null)
						{
							return;
						}
						brainSpeak(blamer, "Your clan just LOST a field clash against " + clanNameOf(winnerProfile) + ". Your OWN alliance - " + kinNames + " - never joined in and left you alone out there. ONE short hot line naming them and calling your alliance out for it.", "delivered [FIELD-BLAME]", !fight.loud, t -> _memory.add(loserProfile.getId(), loserProfile.getId(), t));
					}, 6000 + Rnd.get(12000) + spacingHold());
				}
			}
		}
		else if (!fight.zelda) // an interrupted duel: the hunt does the talking
		{
			// No winner: both bruised or both vanished. One short note, from
			// someone who was out there.
			final Creature drawMouth = duelVoice(fight, 0);
			final VoiceChoice voice = (drawMouth != null) ? new VoiceChoice(drawMouth, 0, "", false) : null;
			if (voice != null)
			{
				brainWorld(voice.creature, "The field duel " + outsideTown(fight) + " between " + clanNameOf(aProfile) + "'s " + String.join(", ", fight.namesA) + " and " + clanNameOf(bProfile) + "'s " + String.join(", ", fight.namesB) + ((note != null) ? " just scattered (" + note + ")" : "just ended even - both sides hurt, none dead") + ". ONE short line, ending it in words: either clan's tone is fine.", "delivered [FIELD-DRAW]", t ->
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

	// ── The ZeldaChickens rule ───────────────────────────────────────────
	// Hit a chicken in Zelda and the whole flock comes for you. Here: a REAL
	// player who strikes or debuffs an arena duelist or fan breaks the duel,
	// and every fighter and fan present turns on them, while waves of hunters
	// keep coming until the offender dies, leaves the world, or reaches a
	// town (a peace zone, where nobody can fight). All of them berate the
	// player in chat - brain-written, like every other word.

	private boolean _zeldaChickens = true;
	private int _zeldaMaxHunters = 6;
	private long _zeldaWaveMillis = 20000L;
	private int _zeldaWaveSize = 2;
	private long _zeldaMaxMillis = 900000L;
	private final Map<Integer, ChickenHunt> _hunts = new ConcurrentHashMap<>();
	private final Set<Integer> _huntPack = ConcurrentHashMap.newKeySet();

	private static final class ChickenHunt
	{
		final Player offender;
		final FieldFight ctx; // the broken duel, reused as the pack's play memory
		final List<Player> pack = new java.util.concurrent.CopyOnWriteArrayList<>();
		/** The duel's own cast, kept apart: only they may shout to the world. */
		final List<Player> fightersA = new ArrayList<>();
		final List<Player> fightersB = new ArrayList<>();
		final List<Player> fans = new ArrayList<>();
		final long startedAt = System.currentTimeMillis();
		long lastWave = System.currentTimeMillis();
		long lastBerate = 0;
		int berates;
		int spawned;
		volatile boolean over;

		ChickenHunt(Player offender, FieldFight ctx)
		{
			this.offender = offender;
			this.ctx = ctx;
		}
	}

	void setZeldaChickens(boolean enabled, int maxHunters, int waveSeconds, int waveSize, int maxMinutes)
	{
		_zeldaChickens = enabled;
		_zeldaMaxHunters = Math.max(1, Math.min(20, maxHunters));
		_zeldaWaveMillis = Math.max(5, Math.min(300, waveSeconds)) * 1000L;
		_zeldaWaveSize = Math.max(1, Math.min(6, waveSize));
		_zeldaMaxMillis = Math.max(1, Math.min(120, maxMinutes)) * 60000L;
	}

	/** The real player behind an attacker: itself, or a summon's owner. */
	private static Player humanBehind(Creature attacker)
	{
		if (attacker instanceof Player)
		{
			return (Player) attacker;
		}
		if (attacker instanceof Summon)
		{
			return ((Summon) attacker).getOwner();
		}
		return null;
	}

	/**
	 * Called from the pack's damage hook and skill-use event, on whatever
	 * thread the blow lands on: only cheap checks here, the hunt itself
	 * starts on the game pool.
	 */
	void observeHostility(Creature attacker, Creature target)
	{
		if (!_zeldaChickens || _fieldFights.isEmpty() || !(target instanceof Player))
		{
			return;
		}
		final Player offender = humanBehind(attacker);
		if ((offender == null) || (offender == target) || _hunts.containsKey(Integer.valueOf(offender.getObjectId())) || _huntPack.contains(Integer.valueOf(offender.getObjectId())))
		{
			return;
		}
		final Player victim = (Player) target;
		for (FieldFight fight : _fieldFights.values())
		{
			if (fight.running && !fight.zelda && (fight.sideA.contains(victim) || fight.sideB.contains(victim) || fight.spectators.contains(victim) || fight.heralds.contains(victim)))
			{
				if (!isHumanWatcher(null, offender))
				{
					return; // a bot's stray blow is not an insult
				}
				fight.zelda = true; // claim it once, even if two blows land together
				ThreadPool.schedule(() -> startChickenHunt(offender, victim, fight), 0);
				return;
			}
		}
	}

	private void startChickenHunt(Player offender, Player victim, FieldFight fight)
	{
		final ChickenHunt hunt = new ChickenHunt(offender, fight);
		final List<Player> cast = new ArrayList<>();
		cast.addAll(fight.sideA);
		cast.addAll(fight.sideB);
		cast.addAll(fight.spectators);
		cast.addAll(fight.heralds);
		final PhantomManager pm = PhantomManager.getInstance();
		for (Player member : cast)
		{
			if ((member != null) && member.isOnline())
			{
				hunt.pack.add(member);
				_huntPack.add(Integer.valueOf(member.getObjectId()));
			}
		}
		hunt.fightersA.addAll(fight.sideA);
		hunt.fightersB.addAll(fight.sideB);
		hunt.fans.addAll(fight.spectators);
		// Hand the cast over BEFORE the duel closes, so its cleanup does not
		// release them: the hunt owns them now and releases them itself.
		fight.sideA.clear();
		fight.sideB.clear();
		fight.spectators.clear();
		fight.heralds.clear();
		endFight(fight, null, "interrupted by " + offender.getName());
		// The pack is one squad now: its healers heal any of them.
		fight.sideA.addAll(hunt.pack);
		_hunts.put(Integer.valueOf(offender.getObjectId()), hunt);
		for (Player member : hunt.pack)
		{
			try
			{
				if (!member.isAlikeDead())
				{
					pm.setLivingIdle(member, false);
					member.setTarget(offender);
				}
			}
			catch (Exception e)
			{
				// one stubborn member must not stop the flock
			}
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [INTERFERE] " + offender.getName() + " hit " + victim.getName() + " in the duel outside " + fight.townName + " - all " + hunt.pack.size() + " present turn on them; hunters follow every " + (_zeldaWaveMillis / 1000) + "s until they die, leave or reach a town.");
		}
		// The first fury: the one who was hit, then a fan, and the world hears.
		final String town = townKey(fight.townName);
		final String insult = "A real player named " + offender.getName() + " just attacked " + victim.getName() + " in the MIDDLE of an honorable clan duel outside " + fight.townName + " - the duel is ruined. ";
		if (victim.isOnline() && !victim.isAlikeDead())
		{
			brainNear(victim, insult + "You are the one they hit. ONE short furious line straight at " + offender.getName() + " - everyone here is coming for them now.", "delivered [INTERFERE-BERATE]");
		}
		ThreadPool.schedule(() ->
		{
			final Player fan = pickHuntVoice(hunt, victim);
			if (fan != null)
			{
				brainNear(fan, insult + "You were watching. ONE short line berating " + offender.getName() + " for butting into a duel that wasn't theirs.", "delivered [INTERFERE-BERATE]");
			}
		}, 3000 + Rnd.get(2000));
		ThreadPool.schedule(() ->
		{
			final Creature crier = duelVoice(hunt.fightersA, hunt.fightersB, hunt.fans, hunt.ctx.fanClans, hunt.ctx.clanAId, hunt.ctx.clanBId, 0);
			if (crier != null)
			{
				brainSpeak(crier, insult + "ONE short world-chat line telling everyone that " + offender.getName() + " ruined the duel at " + town + " and that the whole crowd is hunting them now.", "delivered [INTERFERE-WORLD]", true, null, town);
			}
		}, 6000 + Rnd.get(3000));
		hunt.lastBerate = System.currentTimeMillis();
		scheduleHuntTick(hunt, 500);
	}

	/** A living, online pack member to speak, other than `not` (may be null). */
	private static Player pickHuntVoice(ChickenHunt hunt, Player not)
	{
		final List<Player> voices = new ArrayList<>();
		for (Player member : hunt.pack)
		{
			if ((member != not) && member.isOnline() && !member.isAlikeDead())
			{
				voices.add(member);
			}
		}
		return voices.isEmpty() ? null : voices.get(Rnd.get(voices.size()));
	}

	private void scheduleHuntTick(ChickenHunt hunt, long delay)
	{
		ThreadPool.schedule(() ->
		{
			if (hunt.over)
			{
				return;
			}
			try
			{
				huntTick(hunt);
			}
			catch (Exception e)
			{
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [INTERFERE] a hunt beat failed (" + e + ").");
				}
			}
			if (!hunt.over)
			{
				scheduleHuntTick(hunt, 1000);
			}
		}, delay);
	}

	private void huntTick(ChickenHunt hunt)
	{
		final Player offender = hunt.offender;
		final long now = System.currentTimeMillis();
		if ((offender == null) || !offender.isOnline())
		{
			endChickenHunt(hunt, "left the world");
			return;
		}
		if (offender.isAlikeDead())
		{
			endChickenHunt(hunt, "dead");
			return;
		}
		if (offender.isInsideZone(ZoneId.PEACE))
		{
			endChickenHunt(hunt, "reached a town");
			return;
		}
		if ((now - hunt.startedAt) >= _zeldaMaxMillis)
		{
			endChickenHunt(hunt, "outlasted the hunt");
			return;
		}
		// Everyone still standing goes for the offender, in their own role.
		int alive = 0;
		for (Player member : hunt.pack)
		{
			if (!member.isOnline() || member.isAlikeDead())
			{
				// A fallen hunter leaves the pack; its body goes after a while,
				// so a long chase never piles up corpses.
				hunt.pack.remove(member);
				hunt.ctx.sideA.remove(member);
				ThreadPool.schedule(() ->
				{
					_huntPack.remove(Integer.valueOf(member.getObjectId()));
					releaseFighter(member);
				}, 20000 + Rnd.get(10000));
				continue;
			}
			alive++;
			try
			{
				pairDrive(hunt.ctx, member, offender);
			}
			catch (Exception e)
			{
				// keep the rest of the flock moving
			}
		}
		// Waves: more come, up to the live cap.
		if (((now - hunt.lastWave) >= _zeldaWaveMillis) && (alive < _zeldaMaxHunters))
		{
			hunt.lastWave = now;
			spawnHunters(hunt, Math.min(_zeldaWaveSize, _zeldaMaxHunters - alive));
		}
		// The berating never quite stops: a line every ~25 s, a few in all.
		if ((hunt.berates < 6) && ((now - hunt.lastBerate) >= (22000L + Rnd.get(8000))))
		{
			hunt.lastBerate = now;
			hunt.berates++;
			final Player voice = pickHuntVoice(hunt, null);
			if (voice != null)
			{
				brainNear(voice, "You are chasing " + offender.getName() + ", the player who broke into an honorable clan duel outside " + hunt.ctx.townName + ". ONE short line yelled at them mid-chase: berate them, mock them, tell them there is no escape.", "delivered [INTERFERE-BERATE]");
			}
		}
	}

	/** New hunters appear a short sprint from the offender, on dry ground. */
	private void spawnHunters(ChickenHunt hunt, int count)
	{
		if (count <= 0)
		{
			return;
		}
		final Player offender = hunt.offender;
		final PhantomManager pm = PhantomManager.getInstance();
		final Collection<WaterZone> waters = ZoneManager.getInstance().getAllZones(WaterZone.class);
		final Set<String> taken = new LinkedHashSet<>();
		for (Player member : hunt.pack)
		{
			taken.add(member.getName());
		}
		final List<FakePlayerAppearance> book = identityBook(80);
		// Hunters near the offender's level first: a fair, scary chase.
		final int level = offender.getLevel();
		book.sort((a, b) -> Integer.compare(Math.abs(a.getLevel() - level), Math.abs(b.getLevel() - level)));
		int made = 0;
		for (FakePlayerAppearance appearance : book)
		{
			if (made >= count)
			{
				break;
			}
			if (taken.contains(appearance.getName()) || nameTaken(appearance))
			{
				continue;
			}
			try
			{
				final int degrees = Rnd.get(360);
				final int radius = 500 + Rnd.get(301);
				final int hx = offender.getX() + (int) Math.round(radius * Math.cos(Math.toRadians(degrees)));
				final int hy = offender.getY() + (int) Math.round(radius * Math.sin(Math.toRadians(degrees)));
				final int hz = GeoEngine.getInstance().getSpawnHeight(hx, hy, offender.getZ());
				if (waterAt(waters, hx, hy, hz))
				{
					continue;
				}
				final Player hunter = pm.spawnPartyMemberAs(appearance, new Location(hx, hy, hz), appearance.getLevel());
				if (hunter == null)
				{
					continue;
				}
				if (hunter.isInsideZone(ZoneId.PEACE))
				{
					releaseFighter(hunter); // no hunting inside a town
					continue;
				}
				pm.setLivingIdle(hunter, false);
				fitWeapon(hunter);
				hunter.setTarget(offender);
				hunt.pack.add(hunter);
				hunt.ctx.sideA.add(hunter);
				_huntPack.add(Integer.valueOf(hunter.getObjectId()));
				taken.add(hunter.getName());
				made++;
				hunt.spawned++;
			}
			catch (Exception e)
			{
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [INTERFERE] a hunter failed to appear: " + e);
				}
			}
		}
		if ((made > 0) && (_infoHook != null))
		{
			_infoHook.accept("clan-personalities: [INTERFERE-WAVE] " + made + " more hunter(s) close in on " + offender.getName() + " (" + hunt.spawned + " so far).");
		}
	}

	private void endChickenHunt(ChickenHunt hunt, String how)
	{
		if (hunt.over)
		{
			return;
		}
		hunt.over = true;
		_hunts.remove(Integer.valueOf(hunt.offender.getObjectId()));
		final Player offender = hunt.offender;
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [INTERFERE-END] " + offender.getName() + " " + how + " after " + ((System.currentTimeMillis() - hunt.startedAt) / 1000) + "s; " + hunt.spawned + " hunter(s) joined the chase.");
		}
		// World chat: only one of the duel's own cast (fighters, or a fan of
		// their clans or alliances) - the hunters joined later, from outside.
		final Creature voice = duelVoice(hunt.fightersA, hunt.fightersB, hunt.fans, hunt.ctx.fanClans, hunt.ctx.clanAId, hunt.ctx.clanBId, 0);
		if (voice != null)
		{
			final String moment;
			if ("dead".equals(how))
			{
				moment = offender.getName() + " is DOWN - the player who ruined the duel outside " + hunt.ctx.townName + " got what was coming. ONE short world-chat line gloating that justice was done.";
			}
			else if ("reached a town".equals(how))
			{
				moment = offender.getName() + ", the player who ruined the duel outside " + hunt.ctx.townName + ", ran and hid inside a town where nobody can touch them. ONE short world-chat line calling them a coward who ran.";
			}
			else if ("left the world".equals(how))
			{
				moment = offender.getName() + ", the player who ruined the duel outside " + hunt.ctx.townName + ", logged off to escape. ONE short world-chat line mocking them for running away.";
			}
			else
			{
				moment = "The hunt for " + offender.getName() + ", who ruined the duel outside " + hunt.ctx.townName + ", is called off for now. ONE short world-chat line: this isn't forgotten.";
			}
			brainSpeak(voice, moment, "delivered [INTERFERE-WORLD]", true, null);
		}
		// Stand down: stop the chase, linger like a duel's aftermath, then go.
		final PhantomManager pm = PhantomManager.getInstance();
		final List<Player> pack = new ArrayList<>(hunt.pack);
		for (Player member : pack)
		{
			try
			{
				if (member.isOnline() && !member.isAlikeDead())
				{
					member.abortAttack();
					member.abortCast();
					member.setTarget(null);
					pm.setLivingIdle(member, true);
				}
			}
			catch (Exception e)
			{
				// best effort
			}
		}
		scheduleAftermathGuard(pack, System.currentTimeMillis() + 25000L);
		for (Player member : pack)
		{
			ThreadPool.schedule(() ->
			{
				_huntPack.remove(Integer.valueOf(member.getObjectId()));
				releaseFighter(member);
			}, 25000 + Rnd.get(10000));
		}
	}

	/** Module disable: every live hunt stands down and its cast is released. */
	void endAllHunts()
	{
		for (ChickenHunt hunt : new ArrayList<>(_hunts.values()))
		{
			hunt.over = true;
			for (Player member : hunt.pack)
			{
				releaseFighter(member);
			}
		}
		_hunts.clear();
		_huntPack.clear();
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
			final DramaArc gone = _arcs.remove(arcKey(speakerId, targetId));
			focusReleased(System.currentTimeMillis());
			if ((gone != null) && !Boolean.TRUE.equals(gone.war))
			{
				addGrievance(_profiles.get(speakerId), _profiles.get(targetId), "the quarrel just fizzled out");
			}
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
		// An alliance war (user, 2026-10-21): once the grudge between two
		// alliances is deep enough, the next quarrel between them drags the
		// whole alliances in - allied clans chime in on both sides - and
		// it ends in an EPIC battle.
		if (arc.war == null)
		{
			final String warKey = grievanceKey(speakerProfile, targetProfile);
			// Peaceful clans never strike first: two Peaceful clans quarreling
			// don't start the war - it waits for a quarrel with a hothead in it.
			final boolean bothPeaceful = (speakerProfile != null) && (targetProfile != null) && (speakerProfile.getPersona() == PersonaType.PEACEFUL) && (targetProfile.getPersona() == PersonaType.PEACEFUL);
			final boolean war = _allianceWars && (warKey != null) && !bothPeaceful && (_warDue.contains(warKey) || (grievanceCount(speakerProfile, targetProfile) >= _allianceWarAt));
			arc.war = Boolean.valueOf(war);
			if (war && (_infoHook != null))
			{
				_infoHook.accept("clan-personalities: [ALLIANCE-WAR] " + warKey.replace("+", " vs ") + " - " + grievanceCount(speakerProfile, targetProfile) + " grievances deep: the quarrel between " + clanNameOf(speakerProfile) + " and " + clanNameOf(targetProfile) + " drags both alliances in.");
			}
		}
		if (Boolean.TRUE.equals(arc.war) && (arc.warLines < _warAllyLines) && (Rnd.get(100) < 70))
		{
			arc.warLines++;
			final boolean speakerSide = (arc.warLines % 2) == 1;
			ThreadPool.schedule(() -> allyChime(speakerSide ? speakerProfile : targetProfile, speakerSide ? targetProfile : speakerProfile, arc.cause), 5000 + Rnd.get(6000) + spacingHold());
		}
		final int round = arc.nextRound();
		// A waved-off threat lets the air out: the quarrel winds down faster.
		final int resolveChance = arc.defused ? Math.min(100, (_resolveChance * 2) + 10) : _resolveChance;
		if ((round < _maxDramaRounds) && (Rnd.get(100) >= resolveChance))
		{
			scheduleContinuation(speakerId, targetId);
			return; // the argument continues; the next beat comes by reply pacing
		}
		_arcs.remove(key);
		focusReleased(System.currentTimeMillis());

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
				if (!Boolean.TRUE.equals(arc.war))
				{
					addGrievance(speakerProfile, targetProfile, "it ended in silence, nothing settled");
				}
				return; // nobody left to speak the solution; it ends in silence
			}
		}
		if (Boolean.TRUE.equals(arc.war) && (closer.getPersona() == PersonaType.PEACEFUL))
		{
			// The war cry comes from the side that wants the war.
			final ClanProfile other = (closer == speakerProfile) ? targetProfile : speakerProfile;
			if ((other != null) && (other.getPersona() != PersonaType.PEACEFUL) && !resolveMembers(other.getId()).isEmpty())
			{
				closer = other;
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
		// How the threat was read shades the ending: a confirmed threat
		// pushes toward steel, a waved-off one toward letting it rest.
		final boolean isWar = Boolean.TRUE.equals(arc.war);
		final boolean fightShaded = isWar || ((arc.confirmed && (persona != PersonaType.PEACEFUL)) ? (fight || (Rnd.get(100) < 35)) : (arc.defused ? (fight && (Rnd.get(100) < 40)) : fight));
		if (isWar)
		{
			// The war is ON: no grievance, the battle settles it.
			final String warKey = grievanceKey(speakerProfile, targetProfile);
			if (warKey != null)
			{
				_warDue.add(warKey);
				saveRecords();
				scheduleWarStaging(speakerProfile, targetProfile, 0);
			}
		}
		else if (fightShaded)
		{
			// A war cry: satisfying only if steel actually follows.
			if (grievanceKey(speakerProfile, targetProfile) != null)
			{
				_pendingWarCries.put(key, Long.valueOf(now + _warCryWindowMillis));
			}
		}

		final ClanProfile answerProfile = closer;
		final ClanProfile against = (closer == speakerProfile) ? targetProfile : speakerProfile;
		final String cause = _pairCauses.get(key);
		ThreadPool.schedule(() -> deliverResolution(answerProfile, against, fightShaded, cause), bandDelay(rollBeatRank(0)) + spacingHold());
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
		if (other != null)
		{
			// Phase 3: a quarrel a player's accusation started is about THAT.
			final String accused = _accuseCauses.remove(arcKey(self.getId(), other.getId()));
			if (accused != null)
			{
				_pairCauses.put(arcKey(self.getId(), other.getId()), accused);
				return accused;
			}
		}
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

	/** Emoticons and laughs are not slips: ":P", ":)", ";D", "xD", "<3", "^^". */
	private static final Pattern EMOTICON = Pattern.compile("(?i)(?<=^|\\s)(?:[:;=8][-']?[pdo()\\[\\]/\\\\|*3]+|x+d+|<3|\\^\\^)(?=$|\\s|[.,!?])");
	/** A stray space before punctuation that ends a word: "word ," */
	private static final Pattern STRAY_SPACE = Pattern.compile("[A-Za-z]+\\s+[,.;!?](?=\\s|$)");
	/** A missing space after punctuation between two real words: "word.Next" */
	private static final Pattern MISSING_SPACE = Pattern.compile("[A-Za-z]{2,}[,.;!?][A-Za-z]{2,}");

	/**
	 * The visible, unintentional slip a writer might snap to, or null: a
	 * doubled word ("the the"), a missing space after punctuation
	 * ("word.Next"), or a stray space before punctuation ("word ,").
	 * Emoticons (":P"), ellipses and "?!" runs are NOT slips.
	 */
	static String typoSlip(String text)
	{
		if (text == null)
		{
			return null;
		}
		final String[] words = text.split("\\s+");
		for (int i = 1; i < words.length; i++)
		{
			final String previous = words[i - 1].toLowerCase().replaceAll("[^a-z]", "");
			final String current = words[i].toLowerCase().replaceAll("[^a-z]", "");
			if ((previous.length() >= 2) && previous.equals(current))
			{
				return words[i - 1] + " " + words[i]; // the classic "the the"
			}
		}
		final String clean = EMOTICON.matcher(text).replaceAll(" ").replaceAll("\\.{2,}", " ").replaceAll("[?!]{2,}", " ");
		Matcher m = STRAY_SPACE.matcher(clean);
		if (m.find())
		{
			return m.group();
		}
		m = MISSING_SPACE.matcher(clean);
		if (m.find())
		{
			return m.group();
		}
		return null;
	}

	/**
	 * Rare and genuine: only a line with a detectable slip may earn a
	 * correction, and then still only on the configured chance. The
	 * correction itself is brain-written, in the same bot's voice, and is
	 * told WHICH slip it is correcting - never left to guess.
	 */
	private void maybeSelfCorrection(Creature speaker, String spokenText)
	{
		if ((_selfCorrectionChance <= 0) || (_brain == null))
		{
			return;
		}
		final String slip = typoSlip(spokenText);
		if ((slip == null) || (Rnd.get(100) >= _selfCorrectionChance))
		{
			return;
		}
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [TYPO-SLIP] " + speaker.getName() + " slipped at \"" + slip + "\" - a correction may follow.");
		}
		final String prompt = "Not long ago you said out loud: \"" + spokenText + "\". The slip in it is exactly this bit: \"" + slip + "\". Send ONE very short chat line fixing ONLY that slip the way players do (like \"*the\" or \"oops, meant ...\"). Do not repeat the sentence and do not 'fix' anything else.";
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
		brainSpeak(speaker, prompt, label, world, after, null);
	}

	/**
	 * As above; mustMention (may be null) is a word the line MUST carry -
	 * a fight announcement that forgets WHERE is useless. One retry with a
	 * firmer prompt; a second miss stays silent (never a canned stand-in).
	 */
	private void brainSpeak(Creature speaker, String prompt, String label, boolean world, Consumer<String> after, String mustMention)
	{
		try
		{
			final Creature mouth = speaker;
			brainSubmit(() ->
			{
				String text = brainCustom(mouth, prompt);
				if ((text != null) && (mustMention != null) && !containsIgnoreCase(text, mustMention))
				{
					if (_infoHook != null)
					{
						_infoHook.accept("clan-personalities: " + label + " missed the place (\"" + text + "\") - asking again.");
					}
					text = brainCustom(mouth, prompt + " IMPORTANT: the line MUST literally name " + mustMention + ", so everyone knows where it is happening.");
					if ((text != null) && !containsIgnoreCase(text, mustMention))
					{
						if (_infoHook != null)
						{
							_infoHook.accept("clan-personalities: " + label + " missed the place twice - skipped.");
						}
						text = null;
					}
				}
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

	// ---- The brain gate ---------------------------------------------------
	// The pack's BrainExecutor has four workers, shared with every brain
	// caller in the pack - players' whispers and party replies included.
	// The module's calls are serialized by BrainClient's lock anyway, so
	// letting more than one of them hold a worker buys nothing and risks a
	// drama or fight burst parking all four. The gate keeps at most ONE
	// module job on the pool; the rest wait in a short module-side line,
	// and a beat that waited past BRAIN_GATE_STALE_MILLIS is dropped (its
	// moment has passed - the same rule the pool itself applies).
	private static final int BRAIN_GATE_QUEUE = 6;
	private static final long BRAIN_GATE_STALE_MILLIS = 25000L;
	private final java.util.ArrayDeque<Object[]> _brainLine = new java.util.ArrayDeque<>();
	private boolean _brainSlotBusy;

	/** Submit one module brain job through the single-slot gate. */
	private void brainSubmit(Runnable work)
	{
		if (!humanOnline())
		{
			// v0.2.3: no real human online, no brain call. Queued beats and a
			// fight still running land here and simply stay silent.
			if (!_idleDropNoted)
			{
				_idleDropNoted = true;
				if (_infoHook != null)
				{
					_infoHook.accept("clan-personalities: [IDLE] a line was due but nobody is online to hear it - skipped (further skips this idle stretch are not logged).");
				}
			}
			return;
		}
		_idleDropNoted = false;
		synchronized (_brainLine)
		{
			if (_brainSlotBusy)
			{
				if (_brainLine.size() >= BRAIN_GATE_QUEUE)
				{
					_brainLine.pollFirst(); // the oldest beat is the stalest
					if (_infoHook != null)
					{
						_infoHook.accept("clan-personalities: [BRAIN-GATE] line full - the oldest waiting beat was dropped.");
					}
				}
				_brainLine.addLast(new Object[]
				{
					work,
					Long.valueOf(System.currentTimeMillis())
				});
				return;
			}
			_brainSlotBusy = true;
		}
		brainDispatch(work);
	}

	/** Put one job on the pack pool; the slot frees exactly once, however
	 *  the job ends (ran, went stale in the pool, or was refused). */
	private void brainDispatch(Runnable work)
	{
		final java.util.concurrent.atomic.AtomicBoolean freed = new java.util.concurrent.atomic.AtomicBoolean();
		final Runnable release = () ->
		{
			if (freed.compareAndSet(false, true))
			{
				brainSlotFreed();
			}
		};
		try
		{
			BrainExecutor.runBrainWork(work, release);
		}
		catch (Throwable t)
		{
			release.run();
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: the brain pool refused a beat (" + t + ").");
			}
		}
	}

	/** The slot is free: hand it to the next beat that is still fresh. */
	private void brainSlotFreed()
	{
		Runnable next = null;
		synchronized (_brainLine)
		{
			final long now = System.currentTimeMillis();
			while (!_brainLine.isEmpty())
			{
				final Object[] job = _brainLine.pollFirst();
				if ((now - ((Long) job[1]).longValue()) > BRAIN_GATE_STALE_MILLIS)
				{
					continue; // waited too long; its moment has passed
				}
				next = (Runnable) job[0];
				break;
			}
			if (next == null)
			{
				_brainSlotBusy = false;
				return;
			}
		}
		brainDispatch(next);
	}

	/** World-chat line on the brain pool, delivered on the game pool. */
	private void brainWorld(Creature speaker, String prompt, String label)
	{
		brainSpeak(speaker, prompt, label, true, null);
	}

	/** The word players actually use for a town: "Giran Castle Town" ->
	 *  "Giran", "Town of Oren" -> "Oren", "Hunter Village" -> "Hunter". */
	private static String townKey(String townName)
	{
		if (townName == null)
		{
			return null;
		}
		for (String word : townName.replace('_', ' ').split("\\s+"))
		{
			final String w = word.toLowerCase();
			if (!w.isEmpty() && !w.equals("town") && !w.equals("of") && !w.equals("the") && !w.equals("castle") && !w.equals("village"))
			{
				return word;
			}
		}
		return null;
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
		/** Opened by a TAUNT: the opener dared the other to a real fight. */
		volatile int tauntBy;
		/** A "do you mean it?" exchange is running: other beats wait. */
		volatile long intentHoldUntil;
		/** The threat was confirmed (hotter) or waved off (cooler). */
		volatile boolean confirmed;
		volatile boolean defused;
		/** null = not checked yet; true = this quarrel is an alliance war. */
		volatile Boolean war;
		volatile int warLines;

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

	/**
	 * Alliance war: a clan allied to one side of the quarrel joins it in
	 * world chat, standing with its alliance brother against the other.
	 * A Peaceful ally goes along - loyal, but not happy about it.
	 */
	private void allyChime(ClanProfile brother, ClanProfile foe, String cause)
	{
		if ((brother == null) || (foe == null) || (brother.getAllianceGroup() <= 0))
		{
			return;
		}
		final List<ClanProfile> allies = new ArrayList<>();
		for (ClanProfile profile : _profiles.values())
		{
			if ((profile != brother) && (profile != foe) && (profile.getPersona() != null) && (profile.getAllianceGroup() == brother.getAllianceGroup()) && !resolveMembers(profile.getId()).isEmpty())
			{
				allies.add(profile);
			}
		}
		if (allies.isEmpty())
		{
			return;
		}
		final ClanProfile ally = allies.get(Rnd.get(allies.size()));
		final VoiceChoice voice = chooseVoice(ally);
		if (voice == null)
		{
			return;
		}
		final String alliance = allianceKeyOf(brother);
		final String foeSide = allianceKeyOf(foe);
		final boolean peaceful = ally.getPersona() == PersonaType.PEACEFUL;
		final String prompt = "Your alliance " + alliance + " and " + foeSide + " have been at each other's throats for a long time, and now your alliance brother " + clanNameOf(brother) + " is in an open quarrel with " + clanNameOf(foe) + (((cause != null) && !cause.isEmpty()) ? (" about " + cause) : "") + ". The whole alliance is getting pulled in. "
			+ (peaceful ? ("Your clan " + clanNameOf(ally) + " is Peaceful and hates this - but you stand with your alliance. In ONE short line, side with " + clanNameOf(brother) + " against " + clanNameOf(foe) + ", reluctant and unhappy that it came to this.") : ("Your clan " + clanNameOf(ally) + " stands with its brother: in ONE short line, side with " + clanNameOf(brother) + " against " + clanNameOf(foe) + " by name, in your clan's own temper."));
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: [ALLIANCE-WAR] " + clanNameOf(ally) + " (" + alliance + ") joins the quarrel on " + clanNameOf(brother) + "'s side" + (peaceful ? " - reluctantly." : "."));
		}
		brainWorld(voice.creature, prompt, "delivered [ALLIANCE-WAR]");
	}

	/**
	 * The alliance war's battle: staged as soon as the stage allows, a
	 * forced EPIC with alliance pools. Retries while the world is busy (a
	 * duel on, nobody online, no fair arena); if it never happens the war
	 * stays due, so the next duel between the alliances is the war.
	 */
	private void scheduleWarStaging(ClanProfile a, ClanProfile b, int attempt)
	{
		if (attempt > 20)
		{
			if (_infoHook != null)
			{
				_infoHook.accept("clan-personalities: [ALLIANCE-WAR] the battle could not be staged yet - it stays due for the next duel between " + allianceKeyOf(a) + " and " + allianceKeyOf(b) + ".");
			}
			return;
		}
		ThreadPool.schedule(() ->
		{
			final String key = grievanceKey(a, b);
			if ((key == null) || !_warDue.contains(key))
			{
				return; // already fought
			}
			final long now = System.currentTimeMillis();
			if (!humanOnline() || fieldStageFrozen(now) || !stageFight(a, b, now))
			{
				scheduleWarStaging(a, b, attempt + 1);
			}
		}, ((attempt == 0) ? 40000L : 60000L) + Rnd.get(10000));
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
		if (against != null)
		{
			recordEnding(answerProfile.getId(), against.getId(), fight, cause);
		}
		final String ending = fight
			? "Your clan's argument with " + againstName + " in world chat is OVER. End it with a short declaration that your clan will answer " + againstName + " on the battlefield - a war cry aimed at them by name, not a debate."
				+ ((cause != null) ? " The quarrel was about: " + cause + "." : "")
			: "Your clan's argument with " + againstName + " in world chat is OVER. End it with a short diplomatic close: your clan lets the quarrel with " + againstName + " rest for now - peace, spoken in few words, naming them."
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
		_guardClearRadius = Math.max(0, Math.min(5000, radius));
	}

	/** How far a duel piste keeps from aggressive monsters, and how many
	 *  towns one staging may try before giving up (0 = every town). */
	void setArenaSiting(int monsterClearRadius, int townTries)
	{
		_monsterClearRadius = Math.max(0, Math.min(5000, monsterClearRadius));
		_arenaTownTries = Math.max(0, townTries);
	}

	// ── Level bands (user, 2026-10-21) ──────────────────────────────────
	/** Town (dense name) -> {min, max, preferred(1/0)} ranges; an empty list = wildcard. */
	private final Map<String, List<int[]>> _townBands = new ConcurrentHashMap<>();
	private int _wildScanRadius = 3000;
	private int _wildLevelGap = 8;

	/**
	 * ArenaTownBands, e.g. "Talking Island: any, Gludio Castle Town: 20-40,
	 * Giran Castle Town: 40-99* / 20-39". A range with * is PREFERRED (the
	 * town is the first choice for those duels); "any" makes a wildcard that
	 * takes every level but only after the towns that fit. Unlisted towns
	 * take any level too, judged by the wildlife scan alone.
	 */
	void setArenaTownBands(String spec)
	{
		_townBands.clear();
		if (spec == null)
		{
			return;
		}
		for (String entry : spec.split(","))
		{
			final int colon = entry.indexOf(':');
			if (colon <= 0)
			{
				continue;
			}
			final String town = denseName(entry.substring(0, colon));
			final List<int[]> ranges = new ArrayList<>();
			for (String raw : entry.substring(colon + 1).split("/"))
			{
				String part = raw.trim().toLowerCase();
				if (part.equals("any"))
				{
					continue; // a wildcard: no ranges
				}
				final boolean preferred = part.endsWith("*");
				part = part.replace("*", "").trim();
				final int dash = part.indexOf('-');
				try
				{
					final int lo = (dash > 0) ? Integer.parseInt(part.substring(0, dash).trim()) : Integer.parseInt(part.replace("+", "").trim());
					final int hi = (dash > 0) ? Integer.parseInt(part.substring(dash + 1).trim()) : 99;
					ranges.add(new int[]
					{
						lo,
						hi,
						preferred ? 1 : 0
					});
				}
				catch (NumberFormatException e)
				{
					// a malformed range is skipped
				}
			}
			if (!town.isEmpty())
			{
				_townBands.put(town, ranges);
			}
		}
	}

	private static String denseName(String text)
	{
		final StringBuilder d = new StringBuilder();
		for (char c : text.toLowerCase().toCharArray())
		{
			if (Character.isLetterOrDigit(c))
			{
				d.append(c);
			}
		}
		return d.toString();
	}

	void setWildScan(int radius, int levelGap)
	{
		_wildScanRadius = Math.max(500, Math.min(10000, radius));
		_wildLevelGap = Math.max(0, Math.min(40, levelGap));
	}

	/** 0 = a preferred band fits, 1 = a band fits, 2 = no band (scan only), 3 = wildcard, -1 = the band rules it out. */
	private int bandRank(TownZone zone, double avgLevel)
	{
		if (zone.getName() == null)
		{
			return 2;
		}
		final List<int[]> ranges = _townBands.get(denseName(zone.getName()));
		if (ranges == null)
		{
			return 2;
		}
		if (ranges.isEmpty())
		{
			return 3;
		}
		int best = -1;
		for (int[] range : ranges)
		{
			if ((avgLevel >= range[0]) && (avgLevel <= (range[1] + 0.999)))
			{
				final int rank = (range[2] == 1) ? 0 : 1;
				best = (best < 0) ? rank : Math.min(best, rank);
			}
		}
		return best;
	}

	/**
	 * The wildlife scan: among the monsters (no raid bosses, no minions)
	 * within ArenaWildScanRadius of the spot, the strongest typical one -
	 * the 90th percentile level, so one stray elite doesn't decide it - may
	 * be at most ArenaWildLevelGap above the LOWEST fighter.
	 */
	private boolean wildTooStrong(List<Monster> wild, int x, int y, int minLevel)
	{
		if (wild.isEmpty())
		{
			return false;
		}
		final List<Integer> levels = new ArrayList<>();
		final long r2 = (long) _wildScanRadius * _wildScanRadius;
		for (Monster monster : wild)
		{
			final long dx = monster.getX() - x;
			final long dy = monster.getY() - y;
			if (((dx * dx) + (dy * dy)) <= r2)
			{
				levels.add(Integer.valueOf(monster.getLevel()));
			}
		}
		if (levels.isEmpty())
		{
			return false;
		}
		Collections.sort(levels);
		final int typical = levels.get(Math.min(levels.size() - 1, (int) Math.floor(levels.size() * 0.9))).intValue();
		return typical > (minLevel + _wildLevelGap);
	}

	/** Cliff test for duel pistes: radius searched, and the biggest fall
	 *  allowed over one 32-unit step (0 = test off). */
	private int _edgeRadius = 500;
	private int _edgeMaxStep = 250;

	/** The sea: ground below this height near a piste is open water (0 = off). */
	private int _seaLevel = -3760;
	private int _shoreRadius = 400;

	void setShore(int seaLevel, int shoreRadius)
	{
		_seaLevel = seaLevel;
		_shoreRadius = Math.max(0, Math.min(2000, shoreRadius));
	}

	/**
	 * The coasts carry no water zone in this pack, and a beach slopes too
	 * gently for the cliff test - a duel could land in the sea off Talking
	 * Island (user, 2026-10-21). The server only knows the sea FLOOR, so the
	 * waterline is a height: measured in game at the Talking Island beach,
	 * /loc -81824, 239183, -3760. Any ground below ArenaSeaLevel within
	 * ArenaShoreRadius of a spot is sea.
	 */
	private boolean seaNear(int x, int y, int z)
	{
		if ((_seaLevel == 0) || (_shoreRadius <= 0))
		{
			return false;
		}
		final GeoEngine geo = GeoEngine.getInstance();
		if (geo.getHeight(x, y, z) < _seaLevel)
		{
			return true;
		}
		for (int r = 100; r <= _shoreRadius; r += 100)
		{
			for (int k = 0; k < 16; k++)
			{
				final double a = k * (Math.PI / 8);
				if (geo.getHeight((int) (x + (r * Math.cos(a))), (int) (y + (r * Math.sin(a))), z) < _seaLevel)
				{
					return true;
				}
			}
		}
		return false;
	}

	void setArenaEdge(int radius, int maxStep)
	{
		_edgeRadius = Math.max(64, Math.min(2000, radius));
		_edgeMaxStep = Math.max(0, maxStep);
	}

	/**
	 * True when the ground falls away suddenly anywhere within the edge
	 * radius: walking out along 16 rays in 32-unit steps, one step that
	 * drops more than ArenaMaxStep is a cliff or a pit lip. Measured on the
	 * pack's own geodata (playtest 2026-10-21, Hunter Village): the pit-lip
	 * spot fell 416 in one step within 300 units; open fields stay under
	 * 100 and rough hillsides under 200.
	 */
	private boolean cliffNear(int x, int y, int z)
	{
		if (_edgeMaxStep <= 0)
		{
			return false;
		}
		final GeoEngine geo = GeoEngine.getInstance();
		final int z0 = geo.getHeight(x, y, z);
		for (int k = 0; k < 16; k++)
		{
			final double a = k * (Math.PI / 8);
			final double cos = Math.cos(a);
			final double sin = Math.sin(a);
			int last = z0;
			for (int r = 32; r <= _edgeRadius; r += 32)
			{
				final int here = geo.getHeight((int) (x + (r * cos)), (int) (y + (r * sin)), last);
				if ((last - here) > _edgeMaxStep)
				{
					return true;
				}
				last = here;
			}
		}
		return false;
	}

	/** No-go circles for duel pistes: {x, y, radius}. */
	private final List<int[]> _arenaBlocked = new java.util.concurrent.CopyOnWriteArrayList<>();

	/**
	 * ArenaBlockedSpots: "x,y,radius; x,y,radius". No duel piste - and no road
	 * mouth (gate guard) - inside a circle is used. Playtest 2026-10-21:
	 * Hunter Village's east gate (piste 118372,73628, door guard Gardner)
	 * drops fighters into a pit where nobody can watch.
	 */
	void setArenaBlockedSpots(String spec)
	{
		_arenaBlocked.clear();
		if (spec == null)
		{
			return;
		}
		for (String entry : spec.split(";"))
		{
			final String[] parts = entry.trim().split("\\s*,\\s*");
			if (parts.length != 3)
			{
				continue;
			}
			try
			{
				_arenaBlocked.add(new int[]
				{
					Integer.parseInt(parts[0].trim()),
					Integer.parseInt(parts[1].trim()),
					Math.max(1, Integer.parseInt(parts[2].trim()))
				});
			}
			catch (NumberFormatException e)
			{
				// a malformed entry is skipped
			}
		}
	}

	int arenaBlockedCount()
	{
		return _arenaBlocked.size();
	}

	private boolean arenaBlocked(int x, int y)
	{
		for (int[] spot : _arenaBlocked)
		{
			final long dx = x - spot[0];
			final long dy = y - spot[1];
			if (((dx * dx) + (dy * dy)) <= ((long) spot[2] * spot[2]))
			{
				return true;
			}
		}
		return false;
	}

	/** Pre-fight runway: how long the world gets to walk to the gates. */
	void setFieldLeadSeconds(int seconds)
	{
		_fieldLeadSeconds = Math.max(0, Math.min(300, seconds));
	}

	/** How many bots drift toward the arena as local-crumbs heralds. */
	void setFieldFuse(int aggroMin, int aggroMax, long fenceMaxWaitMillis, long spectatorTalkMillis, int spectatorTalkChance, int fighterAnswerChance)
	{
		_aggroMin = Math.max(1, Math.min(100, aggroMin));
		_aggroMax = Math.max(_aggroMin, Math.min(100, aggroMax));
		_fenceMaxWaitMillis = Math.max(10000L, Math.min(600000L, fenceMaxWaitMillis));
		_spectatorTalkMillis = Math.max(5000L, Math.min(120000L, spectatorTalkMillis));
		_spectatorTalkChance = Math.max(0, Math.min(100, spectatorTalkChance));
		_fighterAnswerChance = Math.max(0, Math.min(100, fighterAnswerChance));
	}

	void setMaxLevelGap(int gap)
	{
		_maxLevelGap = Math.max(0, Math.min(80, gap));
	}

	void setFieldSkillBlacklist(List<String> names)
	{
		if ((names == null) || names.isEmpty())
		{
			return;
		}
		_fieldSkillBlacklist.clear();
		for (String name : names)
		{
			_fieldSkillBlacklist.add(name.trim().toLowerCase());
		}
	}

	void setFenceRadius(int radius)
	{
		_fenceRadius = Math.max(300, Math.min(1400, radius));
	}

	void setVeryClose(int radius, long trashMillis, long fenceMillis)
	{
		_veryCloseRadius = Math.max(100, Math.min(1400, radius));
		_veryCloseTrashMillis = Math.max(2000L, Math.min(11000L, trashMillis));
		_veryCloseFenceMillis = Math.max(5000L, Math.min(600000L, fenceMillis));
	}

	void setMaxGearGap(int gap)
	{
		_maxGearGap = Math.max(0, Math.min(100, gap));
	}

	void setDuelSizes(int maxSquad, int epicChance, int epicMin, int epicMax)
	{
		_fieldMaxSquad = Math.max(1, Math.min(3, maxSquad));
		_epicChance = Math.max(0, Math.min(100, epicChance));
		_epicMin = Math.max(4, Math.min(9, epicMin));
		_epicMax = Math.max(_epicMin, Math.min(9, epicMax));
	}

	void setBareGearMax(int score)
	{
		_bareGearMax = Math.max(-1, Math.min(20, score));
	}

	void setAvoidSoloMatchups(String spec)
	{
		_avoidSolo.clear();
		if (spec == null)
		{
			return;
		}
		for (String raw : spec.split(","))
		{
			final String pair = raw.trim().toUpperCase().replace(' ', '-');
			if (pair.indexOf('-') > 0)
			{
				_avoidSolo.add(pair);
			}
		}
	}

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
		if (brew[0] < (_brewBeats * (alliedPairKey(key) ? _allianceBrewFactor : 1)))
		{
			return false; // brothers bicker longer before they draw steel (v0.2.3)
		}
		return (now - brew[1]) >= _brewMinMillis;
	}

	/** v0.2.3: an alliance pair's brew needs this many times the usual
	 *  bicker beats before it may reach steel. With few alliances and many
	 *  clans, most quarrels are between brothers; this keeps their duels
	 *  possible but rarer. 1 = the old behavior. */
	private int _allianceBrewFactor = 2;

	void setAllianceBrewFactor(int factor)
	{
		_allianceBrewFactor = Math.max(1, Math.min(5, factor));
	}

	private boolean alliedPairKey(String key)
	{
		final int dash = key.indexOf('-');
		if (dash <= 0)
		{
			return false;
		}
		try
		{
			final ClanProfile low = _profiles.get(Integer.parseInt(key.substring(0, dash)));
			final ClanProfile high = _profiles.get(Integer.parseInt(key.substring(dash + 1)));
			return (low != null) && low.isAlliedWith(high);
		}
		catch (NumberFormatException e)
		{
			return false;
		}
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
			brainSubmit(() ->
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
	/**
	 * True only for a REAL human who could be watching. The duel's own
	 * fighters, spectators and heralds, living-population bots, phantoms,
	 * offline shops and detached clients never count. (Before: the fans
	 * spawned at the ring counted as a watcher "on the road", which held
	 * the first blow for the full grace, and town bots closed nearly every
	 * town to arenas.)
	 */
	private static boolean isHumanWatcher(FieldFight fight, Player player)
	{
		if (player == null)
		{
			return false;
		}
		if ((fight != null) && (fight.sideA.contains(player) || fight.sideB.contains(player) || fight.spectators.contains(player) || fight.heralds.contains(player)))
		{
			return false;
		}
		final PhantomManager phantoms = PhantomManager.getInstance();
		if (phantoms.livingPopulationPhantoms().containsValue(player) || phantoms.isPhantom(player) || player.isInOfflineMode())
		{
			return false;
		}
		final org.l2jmobius.gameserver.network.GameClient client = player.getClient();
		return (client != null) && !client.isDetached();
	}

	// ── Phase 3 bridge: player accusations (Accusations.java) ─────────────
	// Read-only views and one speak call, so the accusation code lives in
	// its own file and the engine stays the Phase 1-2 home.

	Collection<ClanProfile> accuseProfiles()
	{
		return _profiles.values();
	}

	/** The clans, in a fresh random order (who weighs in should vary). */
	List<ClanProfile> accuseProfilesList()
	{
		final List<ClanProfile> list = new ArrayList<>(_profiles.values());
		Collections.shuffle(list);
		return list;
	}

	ClanProfile accuseProfile(int clanId)
	{
		return _profiles.get(clanId);
	}

	String accuseClanName(ClanProfile profile)
	{
		return clanNameOf(profile);
	}

	String accuseAbbrev(ClanProfile profile)
	{
		return (profile.getName() != null) ? abbrevFor(profile.getName()) : null;
	}

	String accuseNickname(ClanProfile profile)
	{
		return (profile.getName() != null) ? _clanNicknames.get(normalizeClanToken(profile.getName())) : null;
	}

	boolean accuseIsHuman(Player player)
	{
		return isHumanWatcher(null, player);
	}

	boolean accuseStageFrozen()
	{
		return fieldStageFrozen(System.currentTimeMillis());
	}

	/** True when the clan has fighters in a duel that is forming or running. */
	boolean accuseInDuel(int clanId)
	{
		for (FieldFight fight : _fieldFights.values())
		{
			if ((fight.clanAId == clanId) || (fight.clanBId == clanId))
			{
				return true;
			}
		}
		return false;
	}

	/** What owns the stage, for a brush-off line ("a clan duel between ..."). */
	String accuseDuelNote()
	{
		for (FieldFight fight : _fieldFights.values())
		{
			return "a real clan duel between " + clanNameOf(_profiles.get(fight.clanAId)) + " and " + clanNameOf(_profiles.get(fight.clanBId)) + " is going on outside " + fight.townName;
		}
		return "a clan duel has just ended and everyone is still talking about it";
	}

	boolean accuseMembersPresent(int clanId)
	{
		return !resolveMembers(clanId).isEmpty();
	}

	/** The clan member closest to the player within range, or null. */
	Creature accuseMemberNear(int clanId, Player player, int range)
	{
		Creature best = null;
		long bestDistance = (long) range * range;
		for (Creature member : resolveMembers(clanId))
		{
			final long dx = member.getX() - player.getX();
			final long dy = member.getY() - player.getY();
			final long distance = (dx * dx) + (dy * dy);
			if (distance <= bestDistance)
			{
				bestDistance = distance;
				best = member;
			}
		}
		return best;
	}

	/** The clan this one is quarreling with right now, or 0. */
	int accuseArcOpponent(int clanId)
	{
		for (String key : _arcs.keySet())
		{
			final int dash = key.indexOf('-');
			if (dash <= 0)
			{
				continue;
			}
			try
			{
				final int low = Integer.parseInt(key.substring(0, dash));
				final int high = Integer.parseInt(key.substring(dash + 1));
				if (low == clanId)
				{
					return high;
				}
				if (high == clanId)
				{
					return low;
				}
			}
			catch (NumberFormatException e)
			{
				// not a pair key
			}
		}
		return 0;
	}

	boolean accuseArgued(int a, int b)
	{
		return _memory.argued(a, b, _grudgeWindowMillis);
	}

	boolean accuseHasGrudge(int clanId)
	{
		return hasAnyGrudge(clanId);
	}

	boolean accuseHasRecord(int a, int b)
	{
		return _records.containsKey(arcKey(a, b));
	}

	/** Duels this clan has WON (against anyone, or against `rivalId` when
	 *  it is > 0) - the only record that shows it has actually killed. */
	int accuseDuelWins(int clanId, int rivalId)
	{
		int wins = 0;
		for (Map.Entry<String, PairRecord> entry : _records.entrySet())
		{
			final String key = entry.getKey();
			final int dash = key.indexOf('-');
			if (dash <= 0)
			{
				continue;
			}
			try
			{
				final int low = Integer.parseInt(key.substring(0, dash));
				final int high = Integer.parseInt(key.substring(dash + 1));
				if ((rivalId > 0) && !(((low == clanId) && (high == rivalId)) || ((high == clanId) && (low == rivalId))))
				{
					continue;
				}
				if (low == clanId)
				{
					wins += entry.getValue().winsLow;
				}
				else if (high == clanId)
				{
					wins += entry.getValue().winsHigh;
				}
			}
			catch (NumberFormatException e)
			{
				// not a pair key
			}
		}
		return wins;
	}

	boolean accuseRecordInvolves(int clanId)
	{
		return pairKeyInvolves(_records.keySet(), clanId);
	}

	/** True when THIS clan did a kindness (as the helper) this session. */
	boolean accuseDidKindness(int clanId)
	{
		return _kindnessBy.containsKey(Integer.valueOf(clanId));
	}

	/** Helper clan id -> { helped clan id, when }: the direction of a kindness,
	 *  which _goodDeeds (a pair key) does not keep. */
	private final Map<Integer, long[]> _kindnessBy = new ConcurrentHashMap<>();

	private static boolean pairKeyInvolves(Collection<String> keys, int clanId)
	{
		final String low = clanId + "-";
		final String high = "-" + clanId;
		for (String key : keys)
		{
			if (key.startsWith(low) || key.endsWith(high))
			{
				return true;
			}
		}
		return false;
	}

	String accuseHistory(ClanProfile self, ClanProfile other)
	{
		return historyNote(self, other);
	}

	long accuseSpacingHold()
	{
		return spacingHold();
	}

	long accuseBeatSpacing()
	{
		return _minBeatSpacingMillis;
	}

	// ── Phase 3 bridge, slice 2a: outcomes ─────────────────────────────

	/** Pair key -> the accusation a quarrel opened by it is about. */
	private final Map<String, String> _accuseCauses = new ConcurrentHashMap<>();
	/** Clan id -> until when it sulks in silence (caught being nice). */
	private final Map<Integer, Long> _sulkUntil = new ConcurrentHashMap<>();

	boolean accuseSulking(int clanId)
	{
		final Long until = _sulkUntil.get(clanId);
		if (until == null)
		{
			return false;
		}
		if (System.currentTimeMillis() >= until.longValue())
		{
			_sulkUntil.remove(clanId);
			accuseInfo("[ACCUSE-SULK] " + clanNameOf(_profiles.get(clanId)) + " is talking again.");
			return false;
		}
		return true;
	}

	/**
	 * Caught being nice: the clan drops out of every argument it is in (no
	 * war cry, no peace line; the pair rests the long way) and says nothing
	 * through the drama queue until the sulk ends. Duels already staged run.
	 */
	void accuseSulk(ClanProfile clan, long millis)
	{
		final long now = System.currentTimeMillis();
		final long until = now + Math.max(60000L, millis);
		_sulkUntil.put(clan.getId(), Long.valueOf(until));
		clan.cooldown(until); // no new openings, taunts or kindness rolls either
		final List<String> dropped = new ArrayList<>();
		for (String key : new ArrayList<>(_arcs.keySet()))
		{
			if (key.startsWith(clan.getId() + "-") || key.endsWith("-" + clan.getId()))
			{
				_arcs.remove(key);
				_pairRestUntil.put(key, Long.valueOf(now + _maxPairRestMillis));
				dropped.add(key);
			}
		}
		if (!dropped.isEmpty())
		{
			focusReleased(now);
		}
		accuseInfo("[ACCUSE-SULK] " + clanNameOf(clan) + " goes silent for " + ((until - now) / 60000) + " min" + (dropped.isEmpty() ? "." : " and drops out of " + dropped.size() + " argument(s)."));
	}

	/**
	 * Start a quarrel because of an accusation: heat the pair by `beats`,
	 * and queue the starter's opening line about `cause`. Returns null when
	 * a quarrel was queued, otherwise why not (the heat still applies).
	 */
	String accuseOpenQuarrel(ClanProfile starter, ClanProfile target, String cause, int beats, long delayMillis)
	{
		if ((starter == null) || (target == null) || (starter.getPersona() == null) || (target.getPersona() == null))
		{
			return "a clan has no persona";
		}
		final String key = arcKey(starter.getId(), target.getId());
		for (int i = 0; i < beats; i++)
		{
			noteBicker(starter.getId(), target.getId());
		}
		if (starter.getPersona() == PersonaType.PEACEFUL)
		{
			return "a Peaceful clan never opens a quarrel (heated by " + beats + ")";
		}
		if (accuseSulking(starter.getId()) || accuseSulking(target.getId()))
		{
			return "a clan is sulking (heated by " + beats + ")";
		}
		if (_arcs.containsKey(key))
		{
			return "they are already quarreling (heated by " + beats + ")";
		}
		if (pairAtRest(starter.getId(), target.getId()))
		{
			return "the pair is resting from its last quarrel (heated by " + beats + ")";
		}
		_accuseCauses.put(key, cause);
		_queue.schedule(starter.getId(), MessageKind.START, target.getId(), Math.max(1000L, delayMillis));
		return null;
	}

	/** Heat a pair toward steel without opening anything (a tipped quarrel). */
	void accuseHeat(int a, int b, int beats)
	{
		for (int i = 0; i < beats; i++)
		{
			noteBicker(a, b);
		}
	}

	/** How hot a pair runs right now: a live quarrel, a warm grudge, a duel
	 *  record, a simmering brew; alliance brothers cool it. 0 = no tension. */
	int accuseTension(int a, int b)
	{
		final String key = arcKey(a, b);
		int score = 0;
		if (_arcs.containsKey(key))
		{
			score += 3;
		}
		if (_memory.argued(a, b, _grudgeWindowMillis))
		{
			score += 2;
		}
		if (_records.containsKey(key))
		{
			score += 1;
		}
		final long[] brew = _brew.get(key);
		if (brew != null)
		{
			score += (int) Math.min(2L, brew[0] / 2);
		}
		final ClanProfile pa = _profiles.get(a);
		if ((pa != null) && pa.isAlliedWith(_profiles.get(b)))
		{
			score -= 1;
		}
		return Math.max(0, score);
	}

	/** The clan this one most recently did a kindness FOR, or 0. */
	int accuseKindnessPartner(int clanId)
	{
		final long[] kind = _kindnessBy.get(Integer.valueOf(clanId));
		return (kind != null) ? (int) kind[0] : 0;
	}

	// ── Phase 3 bridge: the .clanp test command ──────────────────────────

	/** Test: record that helper did helped a kindness (the nice-sulk egg). */
	void accuseSeedKindness(int helperId, int helpedId)
	{
		final long now = System.currentTimeMillis();
		_goodDeeds.put(arcKey(helperId, helpedId), Long.valueOf(now));
		_kindnessBy.put(Integer.valueOf(helperId), new long[] { helpedId, now });
		accuseInfo("[TEST] kindness seeded: " + clanNameOf(_profiles.get(helperId)) + " helped " + clanNameOf(_profiles.get(helpedId)) + ".");
	}

	/** Test: end a sulk now. */
	boolean accuseUnsulk(int clanId)
	{
		return _sulkUntil.remove(clanId) != null;
	}

	/** Test: minutes left on a sulk, or 0. */
	long accuseSulkMinutesLeft(int clanId)
	{
		final Long until = _sulkUntil.get(clanId);
		return (until == null) ? 0 : Math.max(0, (until.longValue() - System.currentTimeMillis()) / 60000);
	}

	/** Test: the quarrels running right now, as "A vs B (round n)". */
	List<String> accuseArcSummaries()
	{
		final List<String> out = new ArrayList<>();
		for (Map.Entry<String, DramaArc> entry : _arcs.entrySet())
		{
			final String key = entry.getKey();
			final int dash = key.indexOf('-');
			if (dash <= 0)
			{
				continue;
			}
			try
			{
				out.add(clanNameOf(_profiles.get(Integer.parseInt(key.substring(0, dash)))) + " vs " + clanNameOf(_profiles.get(Integer.parseInt(key.substring(dash + 1)))));
			}
			catch (NumberFormatException e)
			{
				// not a pair key
			}
		}
		return out;
	}

	/** Test: how many bicker beats a pair has brewed, and how many it needs. */
	String accuseBrewText(int a, int b)
	{
		final String key = arcKey(a, b);
		final long[] brew = _brew.get(key);
		final int need = _brewBeats * (alliedPairKey(key) ? _allianceBrewFactor : 1);
		return ((brew == null) ? 0 : brew[0]) + "/" + need + " beats" + (brewedForSteel(key) ? " - ready for steel" : "");
	}

	/** Remember an accusation line in the pair's chat memory, so later
	 *  quarrels between them can bring it up. */
	void accuseRemember(int speakerId, int otherId, String text)
	{
		if ((otherId > 0) && (text != null))
		{
			_memory.add(speakerId, otherId, text);
		}
	}

	void accuseInfo(String message)
	{
		if (_infoHook != null)
		{
			_infoHook.accept("clan-personalities: " + message);
		}
	}

	/**
	 * One accusation answer. With a nearby mouth (local chat) it is spoken
	 * locally by that member; otherwise the clan's usual voice speaks in the
	 * drama channel. Goes through the one-slot brain gate like every line.
	 */
	void accuseSpeak(ClanProfile clan, Creature nearMouth, String situation, String label)
	{
		accuseSpeak(clan, nearMouth, situation, label, null);
	}

	void accuseSpeak(ClanProfile clan, Creature nearMouth, String situation, String label, Consumer<String> after)
	{
		Creature mouth = nearMouth;
		String title = null;
		if (mouth == null)
		{
			final VoiceChoice voice = chooseVoice(clan);
			if (voice == null)
			{
				accuseInfo("[ACCUSE-SKIP] " + clanNameOf(clan) + " has no voice in the world right now.");
				return;
			}
			mouth = voice.creature;
			title = voice.title;
		}
		final String prompt = situation + " You speak for your clan " + clanNameOf(clan) + (((title != null) && !title.isEmpty()) ? (" as its " + title) : "") + ". Your clan's temperament is " + personaHint(clan.getPersona());
		brainSpeak(mouth, prompt, label, nearMouth == null, after);
	}

	// ── Is anyone real here? (v0.2.3) ───────────────────────────────────
	private volatile boolean _idleAnnounced;
	private volatile boolean _idleDropNoted;
	private volatile long _humanCheckedAt;
	private volatile boolean _humanCached = true;

	/**
	 * True while at least one real human is connected (same rule as the
	 * duel watchers: phantoms, living-population bots, offline traders and
	 * detached clients don't count). Cached for 5 s - it is asked for every
	 * brain line.
	 */
	private boolean humanOnline()
	{
		final long now = System.currentTimeMillis();
		if ((now - _humanCheckedAt) < 5000L)
		{
			return _humanCached;
		}
		boolean found = false;
		for (Player player : World.getInstance().getPlayers())
		{
			if (isHumanWatcher(null, player))
			{
				found = true;
				break;
			}
		}
		_humanCached = found;
		_humanCheckedAt = now;
		return found;
	}

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
