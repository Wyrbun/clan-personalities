/*
 * clan-personalities
 *
 * TestCommands.java - the .clanp chat command, for testing alone. Only
 * registered with Debug = True AND TestCommands = True (both off in a
 * shipped config). It reads module state and pokes a few dials so a solo
 * tester can reach moments that would otherwise take hours: seed a kindness
 * for the caught-being-nice easter egg, heat a pair toward a duel, set the
 * player's credibility, clear the cooldowns. Nothing it does is saved.
 */
package modules.clanpersonalities;

import java.util.List;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;

final class TestCommands implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"clanp"
	};

	private final DramaEngine _engine;
	private final Accusations _accusations; // null when Phase 3 accusations are off

	TestCommands(DramaEngine engine, Accusations accusations)
	{
		_engine = engine;
		_accusations = accusations;
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}
		final String[] args = (params == null) ? new String[0] : params.trim().split("\\s+");
		final String sub = ((args.length > 0) && !args[0].isEmpty()) ? args[0].toLowerCase() : "help";
		try
		{
			switch (sub)
			{
				case "status":
				{
					status(player);
					break;
				}
				case "kindness":
				{
					final ClanProfile helper = clan(args, 1);
					final ClanProfile helped = clan(args, 2);
					if ((helper == null) || (helped == null) || (helper == helped))
					{
						say(player, "usage: .clanp kindness <helper clan> <helped clan>   e.g. .clanp kindness BloodRaven IvoryCrown");
						break;
					}
					_engine.accuseSeedKindness(helper.getId(), helped.getId());
					say(player, "Recorded: " + name(helper) + " did " + name(helped) + " a kindness. Now praise " + name(helper) + " in world chat.");
					break;
				}
				case "heat":
				{
					final ClanProfile a = clan(args, 1);
					final ClanProfile b = clan(args, 2);
					if ((a == null) || (b == null) || (a == b))
					{
						say(player, "usage: .clanp heat <clan> <clan> [beats]");
						break;
					}
					int beats = 3;
					if (args.length > 3)
					{
						beats = Math.max(1, Math.min(10, Integer.parseInt(args[3])));
					}
					_engine.accuseHeat(a.getId(), b.getId(), beats);
					say(player, name(a) + " / " + name(b) + ": +" + beats + " beats, brew now " + _engine.accuseBrewText(a.getId(), b.getId()) + ", tension " + _engine.accuseTension(a.getId(), b.getId()) + ". (A duel also needs the brew to simmer BrewMinSeconds and the skirmish cooldown to be over.)");
					break;
				}
				case "cred":
				{
					if (_accusations == null)
					{
						say(player, "Phase 3 accusations are off.");
						break;
					}
					if (args.length > 1)
					{
						_accusations.setCredibility(player, Double.parseDouble(args[1]));
					}
					say(player, "You: " + _accusations.repText(player));
					break;
				}
				case "forgive":
				{
					if (_accusations == null)
					{
						say(player, "Phase 3 accusations are off.");
						break;
					}
					_accusations.forgive(player);
					say(player, "Clean slate: credibility 100, no warnings, no ignore, no cooldown.");
					break;
				}
				case "ready":
				{
					if (_accusations != null)
					{
						_accusations.clearClanGates();
					}
					say(player, "Every clan may answer accusations and casual talk again right away, and the hourly accusation cap is reset.");
					break;
				}
				case "grudge":
				{
					final ClanProfile a = clan(args, 1);
					final ClanProfile b = clan(args, 2);
					if ((a == null) || (b == null) || (a == b))
					{
						say(player, "usage: .clanp grudge <clan> <clan> [count]   (sets the grievances between their alliances; the default count makes the war due)");
						break;
					}
					int count = _engine.allianceWarAt();
					if (args.length > 3)
					{
						count = Math.max(0, Math.min(50, Integer.parseInt(args[3])));
					}
					if (!_engine.allianceWarsOn())
					{
						say(player, "Alliance wars are off (AllianceWars = False in module.ini) - grievances do nothing.");
						break;
					}
					final String sides = _engine.setGrievance(a, b, count);
					say(player, (sides == null) ? (name(a) + " and " + name(b) + " are in the same alliance - no grievances between them.") : (sides + ": " + count + " grievance(s), epic chance " + _engine.epicChanceFor(a, b) + "%. Their next quarrel " + ((count >= _engine.allianceWarAt()) ? "becomes an ALLIANCE WAR." : "raises it further if it ends unsatisfied.")));
					break;
				}
				case "unsulk":
				{
					final ClanProfile clan = clan(args, 1);
					if (clan == null)
					{
						say(player, "usage: .clanp unsulk <clan>");
						break;
					}
					say(player, _engine.accuseUnsulk(clan.getId()) ? (name(clan) + " is talking again.") : (name(clan) + " was not sulking."));
					break;
				}
				default:
				{
					say(player, ".clanp status - clans, quarrels, your standing");
					say(player, ".clanp kindness <helper> <helped> - put a kindness on record (for the easter egg)");
					say(player, ".clanp heat <clan> <clan> [beats] - heat a pair toward a duel");
					say(player, ".clanp cred [0-100] - show or set your credibility");
					say(player, ".clanp forgive - clear your warnings, ignore and credibility");
					say(player, ".clanp ready - clans answer accusations again at once, hourly cap reset");
					say(player, ".clanp unsulk <clan> - end a sulk");
					say(player, ".clanp grudge <clan> <clan> [count] - set alliance grievances (no count = war due)");
					break;
				}
			}
		}
		catch (NumberFormatException e)
		{
			say(player, "That number didn't parse.");
		}
		return true;
	}

	private void status(Player player)
	{
		for (ClanProfile clan : _engine.accuseProfilesList())
		{
			if (clan.getPersona() == null)
			{
				continue;
			}
			final long sulk = _engine.accuseSulkMinutesLeft(clan.getId());
			final int wins = _engine.accuseDuelWins(clan.getId(), 0);
			say(player, name(clan) + ": " + clan.getPersona() + (_engine.accuseMembersPresent(clan.getId()) ? "" : ", NOBODY ONLINE") + ((sulk > 0) ? (", SULKING " + sulk + " min") : "") + (_engine.accuseDidKindness(clan.getId()) ? ", kindness on record" : "") + ", duels won " + wins + ((clan.getPersona() == PersonaType.PEACEFUL) && (wins == 0) ? " (so a killing charge is a lie)" : ""));
		}
		final List<String> arcs = _engine.accuseArcSummaries();
		say(player, "Quarrels running: " + (arcs.isEmpty() ? "none" : String.join(", ", arcs)));
		say(player, "Duel stage: " + (_engine.accuseStageFrozen() ? "a duel is on (or just ended)" : "clear"));
		say(player, "Alliance grievances: " + _engine.grievanceText());
		if (_accusations != null)
		{
			say(player, "You: " + _accusations.repText(player));
		}
	}

	/** A clan by name as typed: full name with or without spaces, or its short form. */
	private ClanProfile clan(String[] args, int index)
	{
		if (args.length <= index)
		{
			return null;
		}
		final String typed = dense(args[index]);
		if (typed.isEmpty())
		{
			return null;
		}
		ClanProfile partial = null;
		for (ClanProfile clan : _engine.accuseProfiles())
		{
			if (clan.getName() == null)
			{
				continue;
			}
			final String full = dense(clan.getName());
			final String abbrev = _engine.accuseAbbrev(clan);
			if (full.equals(typed) || ((abbrev != null) && dense(abbrev).equals(typed)))
			{
				return clan;
			}
			if ((partial == null) && (typed.length() >= 3) && full.startsWith(typed))
			{
				partial = clan;
			}
		}
		return partial;
	}

	private String name(ClanProfile clan)
	{
		return _engine.accuseClanName(clan);
	}

	private static String dense(String text)
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

	private static void say(Player player, String text)
	{
		player.sendMessage("[clanp] " + text);
	}
}
