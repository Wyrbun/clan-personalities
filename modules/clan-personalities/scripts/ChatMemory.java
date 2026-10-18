/*
 * clan-personalities
 *
 * ChatMemory.java - the social memory: a bounded ring of recent messages used
 * to adapt tempo and targets. Only who-spoke-about-whom entries are kept (the
 * text itself stays configurable), so memory never grows heavy.
 *
 * This phase keeps memory in RAM; nothing persists between restarts and the
 * module therefore ships no database tables. Persistence is a candidate for a
 * later phase via the module's own install.sql.
 */
package modules.clanpersonalities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Bounded recent-message memory.
 */
final class ChatMemory
{
	/** One remembered exchange. */
	private static final class Entry
	{
		final int _speaker;
		final int _other;
		final String _text;
		final long _at;

		Entry(int speaker, int other, String text)
		{
			_speaker = speaker;
			_other = other;
			// Bounded on entry: the memory stays a thimble of text, never a log.
			_text = (text != null) && (text.length() > 120) ? text.substring(0, 120) : text;
			_at = System.currentTimeMillis();
		}

		/** Whether this entry involves clans a and b, in either direction. */
		boolean involves(int a, int b)
		{
			return ((_speaker == a) && (_other == b)) || ((_speaker == b) && (_other == a));
		}
	}

	private final Deque<Entry> _entries = new ArrayDeque<>();
	private final int _limit;

	ChatMemory(int limit)
	{
		_limit = Math.max(1, limit);
	}

	synchronized void add(int speaker, int other, String text)
	{
		_entries.addLast(new Entry(speaker, other, text));
		while (_entries.size() > _limit)
		{
			_entries.removeFirst();
		}
	}

	/**
	 * The most recent remembered lines involving a clan, newest first, for
	 * feeding the LLM tier real context instead of a summary.
	 */
	synchronized List<String> recentLines(int clan, int limit)
	{
		final List<String> result = new ArrayList<>();
		final Iterator<Entry> iterator = _entries.descendingIterator();
		while (iterator.hasNext() && (result.size() < limit))
		{
			final Entry entry = iterator.next();
			final String text = entry._text;
			if (((entry._speaker == clan) || (entry._other == clan)) && (text != null) && !text.isEmpty())
			{
				result.add(text);
			}
		}
		return result;
	}

	/**
	 * Whether clans a and b argued inside the window, in either direction.
	 */
	synchronized boolean argued(int a, int b, long windowMillis)
	{
		final long since = System.currentTimeMillis() - windowMillis;
		for (Entry entry : _entries)
		{
			if ((entry._at >= since) && entry.involves(a, b))
			{
				return true;
			}
		}
		return false;
	}

	/** How many messages clan spoke within the window (anti-spam damping). */
	synchronized int countBy(int clan, long windowMillis)
	{
		final long since = System.currentTimeMillis() - windowMillis;
		int count = 0;
		for (Entry entry : _entries)
		{
			if ((entry._at >= since) && ((entry._speaker == clan) || (entry._other == clan)))
			{
				count++;
			}
		}
		return count;
	}

	synchronized void clear()
	{
		_entries.clear();
	}
}
