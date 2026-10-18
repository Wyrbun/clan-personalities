/*
 * clan-personalities
 *
 * ClanSpec.java - what config says about one clan, before the clan itself is
 * ever seen. A spec may carry a numeric clan id directly, or a name/key token
 * that stays pending until a matching clan shows up in the clan table or its
 * members enter the world.
 */
package modules.clanpersonalities;

/**
 * One clan as configured, pending or resolved.
 */
final class ClanSpec
{
	private final String _key; // the normalized token used for matching
	private final String _original; // the token exactly as written in config
	private final int _numericId; // a clan id, or -1 when the token is a name
	private PersonaType _persona;
	private int _allianceGroup = 0;

	ClanSpec(String original, int numericId)
	{
		_original = original;
		_key = DramaEngine.normalizeClanToken(original);
		_numericId = numericId;
	}

	String getKey()
	{
		return _key;
	}

	String getOriginal()
	{
		return _original;
	}

	/** >= 0 when the config token was the clan id itself. */
	int getNumericId()
	{
		return _numericId;
	}

	void setPersona(PersonaType persona)
	{
		_persona = persona;
	}

	PersonaType getPersona()
	{
		return _persona;
	}

	void setAllianceGroup(int allianceGroup)
	{
		_allianceGroup = allianceGroup;
	}

	int getAllianceGroup()
	{
		return _allianceGroup;
	}
}
