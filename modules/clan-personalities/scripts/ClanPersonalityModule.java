/*
 * clan-personalities
 *
 * ClanPersonalityModule.java - the entry point. Reads config, resolves the
 * persona assignment (RANDOM or EXPLICIT) and the alliance groups over clan
 * tokens (numeric ids or name/key words), require-probes the FPC brain
 * (this module's whole voice is brain-written; without the brain it stays
 * disabled), and wires the drama engine. With Enabled = False it does
 * nothing at all, which is the same behavior as its removal.
 */
package modules.clanpersonalities;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleContext;
import org.l2jmobius.gameserver.modules.ModulesConfig;
import org.l2jmobius.gameserver.network.enums.ChatType;

/**
 * The entry point of the clan-personalities module.
 */
public final class ClanPersonalityModule implements GameModule
{
	/** The module's log voice server-side (same channel as context logging). */
	private static final Logger LOG = Logger.getLogger("module.clan-personalities");

	/** The one shared tick task; kept for the future hot-unload path. */
	private ScheduledFuture<?> _tickTask;
	/** The brain handle, kept so a future hot-unload can stop its threads. */
	private volatile BrainClient _brainClient;

	@Override
	public void onEnable(ModuleContext context)
	{
		// The master switch. Nothing registered, scheduled or changed when off.
		if (!context.config().getBoolean("Enabled", false))
		{
			return;
		}

		// ── Clans (numeric ids or name/key tokens) ────────────────────────
		final List<String> tokens = parseTokens(context.config().getString("LivingClanIds", ""));
		if (tokens.isEmpty())
		{
			context.logging().warning("clan-personalities is enabled, but no LivingClanIds entries are configured. Staying inert.");
			return;
		}

		// ── Persona assignment (over tokens) ─────────────────────────────
		// STABLE (default): personas are dealt once, stored in the module
		// folder and kept verbatim across restarts, so a clan keeps its
		// character. RANDOM: a fresh deal every boot (the stored deal is
		// untouched, so switching back to STABLE restores it). EXPLICIT:
		// PersonaMap dictates everything.
		final Map<String, PersonaType> assignments;
		final String mode = context.config().getString("PersonaMode", "STABLE").trim().toUpperCase();
		if ("EXPLICIT".equals(mode))
		{
			assignments = parsePersonaMap(context.config().getString("PersonaMap", ""));
			if (assignments.isEmpty())
			{
				context.logging().warning("clan-personalities is enabled in EXPLICIT mode, but PersonaMap parses to nothing. Staying inert.");
				return;
			}
		}
		else if ("RANDOM".equals(mode))
		{
			assignments = assignRandom(tokens, context.config().getInt("MaxClansPerPersona", 3), new int[PersonaType.values().length]);
			context.logging().info("clan-personalities: RANDOM deal made fresh for this boot (stored deal untouched).");
		}
		else
		{
			if (!"STABLE".equals(mode))
			{
				context.logging().warning("clan-personalities: unknown PersonaMode '" + mode + "', using STABLE.");
			}
			assignments = loadDealtPersonas(personaStateFile(), tokens, context.config().getInt("MaxClansPerPersona", 3), context.logging());
		}

		// ── Alliance groups (chat context only) ─────────────────────────
		final Map<String, Integer> allianceGroups = parseAllianceGroups(context.config().getString("AllianceGroups", ""));

		// ── Clan specs: token, persona, group; numeric ids used as-is ────
		final List<ClanSpec> specs = new ArrayList<>();
		for (String token : tokens)
		{
			final ClanSpec spec = toSpec(token);
			spec.setPersona(assignments.get(DramaEngine.normalizeClanToken(token)));
			spec.setAllianceGroup(allianceGroups.getOrDefault(DramaEngine.normalizeClanToken(token), 0));
			specs.add(spec);
			if (spec.getPersona() != null)
			{
				context.logging().info("clan-personalities: " + spec.getOriginal() + " wears " + spec.getPersona().name() + ".");
			}
		}

		// ── Tempo, caps, voice ───────────────────────────────────────────
		final long clanCooldownMillis = context.config().getInt("ClanCooldownSeconds", 45) * 1000L;
		final long grudgeWindowMillis = context.config().getInt("GrudgeWindowMinutes", 30) * 60000L;

		final DramaEngine engine = new DramaEngine();
		engine.configure(specs, context.config().getInt("DramaStartChance", 20), context.config().getInt("AmbientChance", 3), context.config().getInt("OpportunisticChance", 10), context.config().getInt("MaxMessagesPerHour", 200), context.config().getInt("MaxResponsesPerDrama", 2), clanCooldownMillis, grudgeWindowMillis, context.config().getBoolean("RequireMembersPresent", true), context.config().getInt("MemorySize", 60), context.config().getString("UnknownClanName", "another clan"), message -> context.logging().warning(message));

		// The FPC brain is REQUIRED. Every spoken line - drama, resolution,
		// takeover brag, clarify aside, typo fix - is written by it; there
		// are no static pools to fall back on. The probe RUNS IN THE
		// BACKGROUND: a freshly loading Ollama can take minutes to answer,
		// and the game server must never sit boot-less waiting on it. Until
		// the probe succeeds the engine simply has no brain - every beat
		// composes nothing and the module is silent, which is exactly the
		// "stays inactive" contract held while the server starts.
		_brainClient = new BrainClient(context.config().getString("BrainUrl", "http://127.0.0.1:5000/chat"), context.config().getInt("BrainTimeoutMillis", 45000));
		// Which spam-filter doors the drama lines knock on (see BrainClient):
		// the human flag is still needed while the brain lacks a directed-
		// lines mode; X-Directed-By is always announced for that future mode.
		_brainClient.setHeaders(context.config().getBoolean("BrainHumanFlag", true), context.config().getString("BrainDirectedHeader", "clan-personalities"));
		final DramaEngine bootEngine = engine;
		final BrainClient bootBrain = _brainClient;
		final Thread probeThread = new Thread(() ->
		{
			final String brainError = bootBrain.probe();
			if (brainError != null)
			{
				context.logging().severe("clan-personalities ERROR: the FPC brain is unreachable (" + brainError + ").");
				context.logging().severe("clan-personalities: this module's voice is entirely brain-written and REQUIRES the brain at " + context.config().getString("BrainUrl", "http://127.0.0.1:5000/chat") + ". Start brain\\fpc_brain.py (Ollama) and restart the game server. Until then the module stays inactive and the server behaves as if it were absent.");
				return;
			}
			context.logging().info("clan-personalities: FPC brain reachable - the module's voice is entirely brain-written.");
			bootEngine.setBrain(bootBrain);
			// The warm-keeper starts only NOW - a successful probe, and
			// gated on actual players: with nobody in the world the model
			// is allowed to cool, which costs nothing between sessions.
			bootBrain.startKeeper(() -> !World.getInstance().getPlayers().isEmpty());
		}, "clan-personalities-boot-probe");
		probeThread.setDaemon(true);
		probeThread.start();

		// Where clan speech goes: SHOUT = visible everywhere (world chat),
		// GENERAL = the nearby bubble only. Also how the brain is addressed.
		engine.setChannel(readChannel(context.config().getString("ChatChannel", "SHOUT")));

		// Rank-paced beat replies: inside an ONGOING argument, the clan's
		// rank picks how fast the answer comes and who speaks. Leader
		// fastest (but takes a beat rarely, unless officers were slow),
		// officers in between, members carry most beats. Random bands, so
		// replies vary but never wait past a minute.
		final int[] leaderBand = parseBand(context.config().getString("LeaderReplyBand", "10-25"), 10, 25);
		final int[] officerBand = parseBand(context.config().getString("OfficerReplyBand", "20-40"), 20, 40);
		final int[] memberBand = parseBand(context.config().getString("MemberReplyBand", "35-60"), 35, 60);
		engine.setBeatReplyBands(leaderBand[0] * 1000L, leaderBand[1] * 1000L, officerBand[0] * 1000L, officerBand[1] * 1000L, memberBand[0] * 1000L, memberBand[1] * 1000L);
		// Players read, then type: no two clan lines may land closer than
		// this, no matter how fast the bands or the brain are.
		engine.setBeatSpacing(context.config().getInt("MinBeatSpacingSeconds", 8) * 1000L);
		engine.setBeatChances(context.config().getInt("LeaderReplyChance", 10), context.config().getInt("OfficerReplyChance", 30), context.config().getInt("LeaderPatienceSeconds", 30) * 1000L, context.config().getInt("LeaderPatienceChance", 35), context.config().getInt("ChatterOverlapChance", 35));
		engine.setQuestionChance(context.config().getInt("QuestionChance", 40));

		// Getting started fast: a guaranteed opening quarrel within the
		// first minutes after boot, and a short queue window for NEW
		// quarrels/ambient lines. Ongoing arguments answer on the bands.
		engine.setStartPacing(context.config().getInt("FirstDramaMaxSeconds", 300) * 1000L, context.config().getInt("MinOpeningDelaySeconds", 0) * 1000L, context.config().getInt("MaxOpeningDelaySeconds", 120) * 1000L);

		// Kindness: positive beats between clans (even across alliances),
		// both sides remember them; helpers with hot tempers may get cocky
		// about the thanks.
		engine.setKindness(context.config().getInt("KindnessChance", 8), context.config().getBoolean("KindnessCalmOnly", true), context.config().getInt("CockyReplyChance", 55));

		// War stories: remembered quarrels may be told aloud long after they
		// end; the world may ask what happened, and both clans tell their
		// own version. Memories outlive grudges by design.
		engine.setStories(context.config().getInt("HistoryChance", 6), context.config().getInt("StoryAskChance", 45), context.config().getInt("StoryWindowMinutes", 360) * 60000L);

		// Phase 2: field encounters - clan members physically stepping on
		// each other's toes in the open field: a real duel staged OUTSIDE
		// town zones, the world hearing the opener, a spectator line, and
		// both sides' closing words (the loser may blame its idle alliance).
		if (context.config().getBoolean("Phase2", false))
		{
			engine.setEncounters(context.config().getInt("EncounterChance", 25), context.config().getInt("EncounterScanRadius", 700), context.config().getInt("EncounterFightMaxSeconds", 90), context.config().getInt("EncounterCrowdChance", 60), context.config().getInt("LoserBlameChance", 50));
			context.logging().info("clan-personalities: Phase 2 field encounters armed (base chance " + context.config().getInt("EncounterChance", 25) + "% per drama tick, radius " + context.config().getInt("EncounterScanRadius", 700) + "m).");
		}

		// Drama arcs: arguments run a few beats and must end with a
		// "solution" - a war cry or a diplomatic close - after which the pair
		// rests a random while before a new argument. The brain writes those
		// endings too; the arc rules decide when and in which direction.
		engine.setArcSettings(context.config().getInt("MaxDramaRounds", 8), context.config().getInt("ResolveChance", 20), context.config().getInt("MinPairRestMinutes", 5) * 60000L, context.config().getInt("MaxPairRestMinutes", 10) * 60000L);

		// Shorthand: clan speech may nickname clans and alliances, and
		// clan-less bystanders may ask what the nickname stands for - at most
		// once per mentioned clan or alliance per cooldown window. Hand-tuned
		// overrides beat auto-initials (Sun_Spear = "SP", never "SS").
		engine.setShorthand(context.config().getBoolean("Shorthand", true), context.config().getBoolean("Clarify", true), context.config().getInt("ShorthandChance", 25), context.config().getInt("ClarifyCooldownHours", 6) * 3600000L);
		final Map<String, String> abbreviationOverrides = new LinkedHashMap<>();
		for (String part : context.config().getString("AbbreviationOverrides", "").split(","))
		{
			part = part.trim();
			final int equals = part.indexOf('=');
			if (equals > 0)
			{
				abbreviationOverrides.put(DramaEngine.normalizeClanToken(part.substring(0, equals).trim()), part.substring(equals + 1).trim());
			}
		}
		engine.setAbbreviationOverrides(abbreviationOverrides);

		// Funny clan nicknames ("Ivory_Crown=Ivy Crown") get their own roll
		// in speech, and the clarify chatter may ask about the fun name.
		// Clarifications stay rare: a chance roll first, then the
		// once-per-mention-per-window gates keep it at most one per clan.
		// Hand-tuned names (ini) outrank the brain's coined ones; the brain
		// fills in every clan that has neither. Coined names are persisted
		// to nicknames.properties in the module folder, so slang sticks.
		final Map<String, String> clanNicknames = loadBrainNicknames();
		for (String part : context.config().getString("ClanNicknames", "").split(","))
		{
			part = part.trim();
			final int equals = part.indexOf('=');
			if (equals > 0)
			{
				clanNicknames.put(DramaEngine.normalizeClanToken(part.substring(0, equals).trim()), part.substring(equals + 1).trim());
			}
		}
		engine.setNicknames(clanNicknames, context.config().getInt("NicknameChance", 45));
		engine.setClarifyChance(context.config().getInt("ClarifyChance", 40));
		engine.setNicknameDrift(context.config().getInt("NicknameDriftChance", 4), ClanPersonalityModule::saveBrainNickname);

		// Challenges: trash talk that dares ("bring it") earns a persona
		// reply - and with some odds becomes a REAL field duel at the gates.
		final List<String> challengeCues = new ArrayList<>();
		for (String cue : context.config().getString("ChallengeCues", "come fight me,bring it,fight me,meet me outside,catch me outside").split(","))
		{
			cue = cue.trim().toLowerCase();
			if (!cue.isEmpty())
			{
				challengeCues.add(cue);
			}
		}
		engine.setChallengeCues(challengeCues, context.config().getInt("ChallengeFightChance", 35));
		engine.setGuardClearRadius(context.config().getInt("GuardClearRadius", 900));
		engine.setFieldLeadSeconds(context.config().getInt("FieldLeadSeconds", 90));
		engine.setFieldHeralds(context.config().getInt("FieldHeralds", 5));
		engine.setPlayerGrace(context.config().getInt("PlayerGraceRadius", 3000), context.config().getInt("PlayerRoadRadius", 2200), context.config().getInt("PlayerGraceMaxSeconds", 180) * 1000L);
		engine.setSkirmishCooldowns(context.config().getInt("SkirmishCooldownSeconds", 240) * 1000L, context.config().getInt("SkirmishHeatStepSeconds", 90) * 1000L);
		engine.setBrewRequirements(context.config().getInt("BrewBeatsForSteel", 3), context.config().getInt("BrewWindowSeconds", 900), context.config().getInt("BrewMinSeconds", 90));

		// Clan voices: each clan keeps its leader/officer/member roles stable
		// (derived from names), the talker keeps the word, senior ranks take
		// over (and may introduce themselves), and a rival may ask who speaks.
		// All of those lines are brain-written.
		engine.setVoiceSettings(context.config().getInt("AnnounceTakeoverChance", 40), context.config().getString("LeaderTitle", "clan leader"), context.config().getString("OfficerTitle", "officer"), context.config().getString("MemberTitle", "member"));
		engine.setLeadershipAskChance(context.config().getInt("LeadershipAskChance", 60));

		// Offense causes: plain-language incidents the drama is actually
		// about; the brain is told which one the pair is quarreling over.
		final List<String> offenseCauses = new ArrayList<>();
		for (String cause : context.config().getString("OffenseCauses", "").split(","))
		{
			cause = cause.trim();
			if (!cause.isEmpty())
			{
				offenseCauses.add(cause);
			}
		}
		engine.setOffenseCauses(offenseCauses);

		// Typos stay as the brain wrote them; a rare visible slip earns a
		// rare, player-style self-correction from the same bot.
		engine.setSelfCorrectionChance(context.config().getInt("SelfCorrectionChance", 10));

		// Alliance display names, for shorthand of alliance names in speech.
		final Map<Integer, String> allianceNames = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : allianceGroups.entrySet())
		{
			allianceNames.put(entry.getValue(), entry.getKey());
		}
		engine.setAllianceNames(allianceNames);

		// Debug: report every delivered line in the server log, kind, speaker
		// and channel included. Off by default; turn on to prove the voice.
		if (context.config().getBoolean("Debug", false))
		{
			engine.setInfoHook(message -> context.logging().info(message));
		}

		if (engine.getPersonaClanCount() == 0)
		{
			context.logging().warning("clan-personalities is enabled, but no clan carries a persona. Staying inert.");
			return;
		}

		// One shared tick. Scheduling is plain game code (see MODULE.md):
		// the V1 ModuleContext surface has no scheduler yet.
		final int period = context.config().getInt("DramaPeriodMillis", 90000);
		_tickTask = ThreadPool.scheduleAtFixedRate(engine::tick, period, period);
		engine.armFirstDrama(); // the guaranteed early quarrel

		context.logging().info("clan-personalities active for " + engine.getPersonaClanCount() + " living clan(s).");
	}

	@Override
	public void onDisable(ModuleContext context)
	{
		// Reserved for a future hot-unload path. V1 never calls this; when it
		// does, stopping the tick is step one and draining the queue is step
		// two (queued sends re-check every gate on their own).
		if (_tickTask != null)
		{
			_tickTask.cancel(false);
			_tickTask = null;
		}
		// The warm-keeper must not outlive the module: on a cloud brain every
		// ping after shutdown is money spent for nobody.
		final BrainClient brain = _brainClient;
		if (brain != null)
		{
			brain.stopKeeper();
			_brainClient = null;
		}
	}

	/**
	 * A config token is a clan id when it is purely digits, a name/key token
	 * otherwise. Name-key tokens stay pending until their clan is seen.
	 */
	private static ClanSpec toSpec(String token)
	{
		return new ClanSpec(token, isNumeric(token) ? Integer.parseInt(token) : -1);
	}

	private static boolean isNumeric(String text)
	{
		for (char character : text.toCharArray())
		{
			if (!Character.isDigit(character))
			{
				return false;
			}
		}
		return !text.isEmpty();
	}

	// ── Config parsing helpers ────────────────────────────────────────────

	/** Accepts SHOUT (default) or GENERAL; anything else falls back to SHOUT. */
	private static ChatType readChannel(String channelName)
	{
		final String value = (channelName == null) ? "" : channelName.trim().toUpperCase();
		return "GENERAL".equals(value) ? ChatType.GENERAL : ChatType.SHOUT;
	}

	private static List<String> parseTokens(String text)
	{
		final List<String> tokens = new ArrayList<>();
		for (String part : text.split(","))
		{
			part = part.trim();
			if (!part.isEmpty())
			{
				tokens.add(part);
			}
		}
		return tokens;
	}

	/**
	 * Parses a "min-max" seconds band for the rank reply pacing; a bad or
	 * empty value keeps the default.
	 */
	private static int[] parseBand(String text, int defaultMin, int defaultMax)
	{
		try
		{
			final String[] parts = text.trim().split("-");
			if (parts.length == 2)
			{
				final int min = Integer.parseInt(parts[0].trim());
				final int max = Integer.parseInt(parts[1].trim());
				return new int[]
				{
					Math.min(min, max), Math.max(min, max)
				};
			}
		}
		catch (Exception ignored)
		{
			// fall through to defaults
		}
		return new int[]
		{
			defaultMin, defaultMax
		};
	}

	/**
	 * PersonaMap format: "token:persona;token:persona". Tokens may be clan
	 * ids or name/key words; matching is normalized. Bad entries are skipped.
	 */
	private static Map<String, PersonaType> parsePersonaMap(String text)
	{
		final Map<String, PersonaType> result = new LinkedHashMap<>();
		for (String part : text.split(";"))
		{
			part = part.trim();
			if (part.isEmpty())
			{
				continue;
			}
			final String[] pair = part.split(":");
			if (pair.length != 2)
			{
				continue;
			}
			final PersonaType persona = PersonaType.parse(pair[1]);
			if (persona != null)
			{
				result.put(DramaEngine.normalizeClanToken(pair[0]), persona);
			}
		}
		return result;
	}

	/**
	 * RANDOM assignment: shuffle the tokens, shuffle a persona pool where each
	 * persona appears at most MaxClansPerPersona times, and deal them out.
	 * Leftover clans get no persona and stay silent, which is the anti-clutter
	 * limit doing its job. usedPerPersona lets a caller deal only the missing
	 * clans while respecting capacity already taken by the stored deal.
	 */
	private static Map<String, PersonaType> assignRandom(List<String> tokens, int maxPerPersona, int[] usedPerPersona)
	{
		final List<String> clans = new ArrayList<>();
		for (String token : tokens)
		{
			clans.add(DramaEngine.normalizeClanToken(token));
		}

		final List<PersonaType> pool = new ArrayList<>();
		for (PersonaType persona : PersonaType.values())
		{
			final int used = usedPerPersona[persona.ordinal()];
			final int total = Math.max(1, maxPerPersona);
			final int remaining = total - used;
			for (int i = 0; i < Math.min(total, remaining); i++)
			{
				pool.add(persona);
			}
		}

		Collections.shuffle(clans);
		Collections.shuffle(pool);

		final Map<String, PersonaType> result = new LinkedHashMap<>();
		final int count = Math.min(clans.size(), pool.size());
		for (int i = 0; i < count; i++)
		{
			result.put(clans.get(i), pool.get(i));
		}
		return result;
	}

	// ── STABLE persona storage (personas.properties in the module folder) ──

	/**
	 * The module folder resolves from the pack's module registry root
	 * (ModulesConfig) - never a working-directory guess made by whatever
	 * launched the server. Old pack builds without the registry fall back
	 * to the relative modules folder.
	 */
	private static File personaStateFile()
	{
		File dir = new File(ModulesConfig.getModulesRoot(), "clan-personalities");
		if (!dir.isDirectory())
		{
			final File fallback = new File("modules", "clan-personalities");
			if (fallback.isDirectory())
			{
				dir = fallback;
			}
		}
		return new File(dir, "personas.properties");
	}

	// ── Brain-coined clan nicknames (nicknames.properties persists them) ──

	/** Where the world's coined slang lives, next to the persona deal. */
	private static File nicknameStateFile()
	{
		File dir = new File(ModulesConfig.getModulesRoot(), "clan-personalities");
		if (!dir.isDirectory())
		{
			final File fallback = new File("modules", "clan-personalities");
			if (fallback.isDirectory())
			{
				dir = fallback;
			}
		}
		return new File(dir, "nicknames.properties");
	}

	/** Loads the brain's coined nicknames (normalizedToken=nickname lines).
	 *  A missing or unreadable file simply means the world starts clean. */
	private static Map<String, String> loadBrainNicknames()
	{
		final Map<String, String> stored = new LinkedHashMap<>();
		final File file = nicknameStateFile();
		if (!file.isFile())
		{
			return stored;
		}
		BufferedReader reader = null;
		try
		{
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null)
			{
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				final int equals = line.indexOf('=');
				if (equals > 0)
				{
					stored.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
				}
			}
		}
		catch (Exception e)
		{
			LOG.finest("clan-personalities: could not load nicknames (" + e + "); starting clean.");
		}
		finally
		{
			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (Exception ignored)
				{
				}
			}
		}
		return stored;
	}

	/** Persists one coined nickname; failure is a log whisper, never a crash. */
	private static void saveBrainNickname(String token, String nickname)
	{
		final File file = nicknameStateFile();
		try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)))
		{
			if (!file.isFile())
			{
				writer.println("");
			}
			writer.println(token + "=" + nickname);
		}
		catch (Exception e)
		{
			LOG.finest("clan-personalities: could not save nickname (" + e + "); the coin lives only for this run.");
		}
	}

	/**
	 * STABLE mode: load the stored deal, deal new personas for clans that have
	 * none yet (respecting capacity already taken), persist the merge, and log
	 * exactly what happened. A missing or unreadable file means a fresh deal.
	 */
	private static Map<String, PersonaType> loadDealtPersonas(File file, List<String> tokens, int maxPerPersona, Logger log)
	{
		final Map<String, PersonaType> stored = new LinkedHashMap<>();
		if (file.isFile())
		{
			BufferedReader reader = null;
			try
			{
				reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
				String line;
				while ((line = reader.readLine()) != null)
				{
					line = line.trim();
					if (line.isEmpty() || line.startsWith("#"))
					{
						continue;
					}
					final int separator = line.indexOf('=');
					if (separator <= 0)
					{
						continue;
					}
					final PersonaType persona = PersonaType.parse(line.substring(separator + 1).trim());
					if (persona != null)
					{
						stored.put(DramaEngine.normalizeClanToken(line.substring(0, separator).trim()), persona);
					}
				}
			}
			catch (Exception e)
			{
				log.info("clan-personalities: could not read " + file.getName() + " (" + e + "); dealing fresh.");
			}
			finally
			{
				if (reader != null)
				{
					try
					{
						reader.close();
					}
					catch (Exception ignored)
					{
					}
				}
			}
		}

		final Map<String, PersonaType> assignments = new LinkedHashMap<>();
		final List<String> missing = new ArrayList<>();
		for (String token : tokens)
		{
			final String key = DramaEngine.normalizeClanToken(token);
			final PersonaType persona = stored.get(key);
			if (persona == null)
			{
				missing.add(token);
			}
			else
			{
				assignments.put(key, persona);
			}
		}

		if (!missing.isEmpty())
		{
			final int[] used = new int[PersonaType.values().length];
			for (PersonaType persona : assignments.values())
			{
				used[persona.ordinal()]++;
			}
			assignments.putAll(assignRandom(missing, maxPerPersona, used));
		}

		if (assignments.isEmpty())
		{
			return assignments; // nothing to store; the inert check handles it
		}
		saveDealtPersonas(file, tokens, assignments, log);
		if (!missing.isEmpty() && file.isFile())
		{
			log.info("clan-personalities: dealt personas for " + missing.size() + " clan(s) with no stored one; deal saved to " + file.getName() + " and kept between restarts.");
		}
		return assignments;
	}

	/** Writes the dealt personas; failure is a log line, never a crash. */
	private static void saveDealtPersonas(File file, List<String> tokens, Map<String, PersonaType> assignments, Logger log)
	{
		PrintWriter writer = null;
		try
		{
			final File dir = file.getParentFile();
			if ((dir != null) && !dir.isDirectory() && !dir.mkdirs())
			{
				throw new IllegalStateException("cannot create " + dir);
			}
			writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
			writer.println("# clan-personalities: the dealt personas. Loaded on every boot in STABLE mode.");
			writer.println("# Free-form edits apply on next restart (peaceful/neutral/altruistic/aggressive/playerkilling).");
			writer.println("# Deleting this file lets the module deal everything fresh on its next launch.");
			for (String token : tokens)
			{
				final PersonaType persona = assignments.get(DramaEngine.normalizeClanToken(token));
				if (persona != null)
				{
					writer.println(token + " = " + persona.name());
				}
			}
		}
		catch (Exception e)
		{
			log.info("clan-personalities: could not save personas (" + e + "); a fresh deal happens on the next boot.");
		}
		finally
		{
			if (writer != null)
			{
				writer.close();
			}
		}
	}

	/**
	 * AllianceGroups format: "name:token,token;name:token". Tokens may be ids
	 * or name/key words. Group numbers are stable only within a boot; it is
	 * the name that carries identity in config.
	 */
	private static Map<String, Integer> parseAllianceGroups(String text)
	{
		final Map<String, Integer> result = new LinkedHashMap<>();
		int group = 0;
		for (String part : text.split(";"))
		{
			final int separator = part.lastIndexOf(':');
			if (separator < 0)
			{
				continue;
			}
			group++;
			for (String token : part.substring(separator + 1).split(","))
			{
				token = token.trim();
				if (!token.isEmpty())
				{
					result.put(DramaEngine.normalizeClanToken(token), group);
				}
			}
		}
		return result;
	}
}
