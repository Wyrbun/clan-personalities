/*
 * clan-personalities
 *
 * PersonaType.java - the five temperaments and how likely each one is to open
 * drama, and whom it will answer for. Policy only; no mechanics live here.
 */
package modules.clanpersonalities;

/**
 * The clan temperaments.
 */
public enum PersonaType
{
	// startFactor: DramaStartChance multiplier. defendOwn / defendAlliance:
	// whether the persona reacts when its own clan / an alliance clan is
	// dragged into drama. Peaceful only ever reacts to direct provocation.
	PEACEFUL(0.0f, true, false),
	NEUTRAL(0.05f, true, false),
	ALTRUISTIC(0.15f, true, true),
	AGGRESSIVE(1.0f, true, true),
	PLAYER_KILLING(0.8f, true, true);

	private final float _startFactor;
	private final boolean _defendsOwn;
	private final boolean _defendsAlliance;

	PersonaType(float startFactor, boolean defendsOwn, boolean defendsAlliance)
	{
		_startFactor = startFactor;
		_defendsOwn = defendsOwn;
		_defendsAlliance = defendsAlliance;
	}

	float getStartFactor()
	{
		return _startFactor;
	}

	boolean defendsOwn()
	{
		return _defendsOwn;
	}

	boolean defendsAlliance()
	{
		return _defendsAlliance;
	}

	/** Config-friendly display name: "peaceful", "player-killing", ... */
	String configName()
	{
		return name().toLowerCase().replace('_', '-');
	}

	/**
	 * Parses a persona name; null when unrecognized, so bad config lines can
	 * be skipped instead of refused.
	 * @param text tolerant of case and '-', '_', and spaces
	 */
	static PersonaType parse(String text)
	{
		if (text == null)
		{
			return null;
		}
		final String value = text.trim().toLowerCase().replace('_', '-').replace(" ", "-");
		for (PersonaType persona : values())
		{
			if (persona.configName().equals(value) || persona.name().equalsIgnoreCase(value))
			{
				return persona;
			}
		}
		return null;
	}
}
