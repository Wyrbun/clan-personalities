/*
 * clan-personalities
 *
 * ClanProfile.java - one living clan as this module sees it: identity,
 * persona, chat-context alliance group, learned display name, and its private
 * speaking gate. No global state.
 */
package modules.clanpersonalities;

/**
 * A living clan's profile.
 */
final class ClanProfile
{
	private final int _clanId;
	private int _allianceGroup = 0; // 0 = no configured alliance group
	private PersonaType _persona = null; // null: persona-less, stays silent
	private String _name = null; // learned when the clan is first seen
	private boolean _present = false; // a member was seen this tick
	private long _maySpeakAt = 0; // per-clan cooldown gate

	ClanProfile(int clanId)
	{
		_clanId = clanId;
	}

	int getId()
	{
		return _clanId;
	}

	void setAllianceGroup(int allianceGroup)
	{
		_allianceGroup = allianceGroup;
	}

	int getAllianceGroup()
	{
		return _allianceGroup;
	}

	void setPersona(PersonaType persona)
	{
		_persona = persona;
	}

	PersonaType getPersona()
	{
		return _persona;
	}

	/**
	 * A clan's name is learned from the clan itself or its own members,
	 * never guessed.
	 */
	void setName(String name)
	{
		_name = name;
	}

	String getName()
	{
		return _name;
	}

	void setPresent(boolean present)
	{
		_present = present;
	}

	boolean isPresent()
	{
		return _present;
	}

	/**
	 * Called after speaking or when a queued opening reserves the floor.
	 */
	void cooldown(long until)
	{
		if (until > _maySpeakAt)
		{
			_maySpeakAt = until;
		}
	}

	boolean maySpeakNow(long now)
	{
		return now >= _maySpeakAt;
	}

	/**
	 * Whether two clans sit in the same configured alliance group.
	 */
	boolean isAlliedWith(ClanProfile other)
	{
		return (_allianceGroup > 0) && (other != null) && (other._allianceGroup == _allianceGroup);
	}
}
