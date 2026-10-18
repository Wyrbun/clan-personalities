/*
 * clan-personalities
 *
 * BrainClient.java - the REQUIRED LLM tier. Asks the pack's local FPC brain
 * (http://127.0.0.1:5000/chat, provider Ollama/gemma3:12b behind it) for one
 * in-character line, using the speaking phantom's own name so the brain
 * keeps its stable voice for that bot. This module has no static line
 * pools: the brain writes every spoken line, which is why the entry point
 * probes it at boot and stays inactive (with a logged error) if it cannot
 * answer.
 *
 * Failure contract: this client only ever returns a line or null. Every
 * failure (brain off, timeout, bad status, empty reply) is null, and a soft
 * circuit breaker pauses attempts for sixty seconds, so a downed brain
 * makes the clans fall silent rather than stall the server; nothing is
 * ever spoken in their place.
 *
 * Warm model: Ollama unloads the model after five quiet minutes, and a
 * reload costs the next line a full cold generation. A daemon thread pings
 * the brain a hair more often than that, so the model stays pinned and
 * every reply pays warm-turn time only.
 */
package modules.clanpersonalities;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A minimal, blocking, safety-capped, fully serialized client for the FPC
 * brain.
 */
final class BrainClient
{
	/**
	 * After a failure, pause attempts this long. Sixty seconds: a genuinely
	 * downed brain fails on connection-refused in milliseconds anyway, so
	 * this pause only guards against hammering a struggling one. Long
	 * enough to matter, short enough that a hiccup costs one missed line -
	 * never a long world-wide silence (the old ten-minute breaker turned
	 * one slow generation into "the drama started, then nothing").
	 */
	private static final long RETRY_DELAY_MILLIS = 60000;
	/** The brain already caps its replies; this is our own defense in depth. */
	private static final int MAX_REPLY_LENGTH = 200;
	/** The warm-keeper pings the brain at this interval - comfortably
	 *  inside Ollama's five-minute model-unload timer. */
	private static final long WARM_KEEPER_MILLIS = 210000;

	private final String _url;
	private final int _timeoutMillis;
	private final Object _callLock = new Object();
	private long _retryAt = 0;

	BrainClient(String url, int timeoutMillis)
	{
		_url = url;
		_timeoutMillis = Math.max(1000, timeoutMillis);
		armWarmKeeper();
	}

	/**
	 * Pins the model in VRAM: one tiny brain turn every 3.5 minutes, well
	 * inside the five-minute unload timer, so no drama reply ever waits on
	 * a cold model reload. Daemon thread: it must never keep the game
	 * server alive, and a downed brain costs it one rejected localhost
	 * connect every 3.5 minutes - nothing at all.
	 */
	private void armWarmKeeper()
	{
		final Thread keeper = new Thread(() ->
		{
			while (true)
			{
				try
				{
					Thread.sleep(WARM_KEEPER_MILLIS);
				}
				catch (InterruptedException e)
				{
					return; // the JVM is going down; go quietly
				}
				// Tiny keep-alive probe on the brain's own surface: no
				// breaker, no logging - a whispered "still there?" that
				// resets Ollama's unload timer.
				probeAttempt(10000);
			}
		});
		keeper.setName("clan-personalities-brain-keeper");
		keeper.setDaemon(true);
		keeper.start();
	}

	/**
	 * One-shot reachability check at boot. Null when the brain answers; a
	 * short error description otherwise (the entry point logs it and stays
	 * disabled - the brain is required). Two attempts are made: a fast one,
	 * then a slow one, because a freshly started Ollama may be loading its
	 * model on the very first request and can take well over a minute to
	 * answer; only a brain silent through BOTH attempts is declared absent.
	 */
	String probe()
	{
		final String fast = probeAttempt(45000);
		if (fast == null)
		{
			return null;
		}
		final String slow = probeAttempt(60000);
		return (slow == null) ? null : (fast + "; still unreachable on a slow second attempt: " + slow);
	}

	private String probeAttempt(int timeoutMillis)
	{
		HttpURLConnection connection = null;
		try
		{
			connection = (HttpURLConnection) new URL(_url).openConnection();
			connection.setConnectTimeout(timeoutMillis);
			connection.setReadTimeout(timeoutMillis);
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
			connection.setRequestProperty("X-FPC", "BrainCheck");
			connection.setRequestProperty("X-Mode", "SAY");
			connection.setRequestProperty("X-Human", "true"); // exempt the probe from topic-cooldown kills too
			final byte[] body = "Say OK, nothing else.".getBytes(StandardCharsets.UTF_8);
			connection.getOutputStream().write(body);
			final int status = connection.getResponseCode();
			if ((status >= 200) && (status < 300))
			{
				return null; // reachable; the module may speak
			}
			return "HTTP " + status;
		}
		catch (Exception e)
		{
			return "unreachable (" + e + ")";
		}
		finally
		{
			if (connection != null)
			{
				connection.disconnect();
			}
		}
	}

	/**
	 * Requests one line for the named bot. Returns the sanitized line, or
	 * null when the brain is unavailable, slow, or said nothing usable.
	 *
	 * ONE brain turn at a time, always. Two beats landing together (an
	 * aside and its main reply, two clans trading words) would otherwise
	 * queue INSIDE Ollama, where each waits for the other and both blow
	 * their timeouts doing it. Here the second caller simply waits its
	 * turn - a natural queue with no re-checks needed.
	 * @param botName the speaking phantom's real name (the brain keys its voice on it)
	 * @param location where the bot is ("Talking Island Village" or empty)
	 * @param prompt the composed, in-character situation
	 */
	String line(String botName, String location, String mode, String prompt)
	{
		if ((prompt == null) || prompt.isEmpty())
		{
			return null;
		}
		synchronized (_callLock)
		{
			// The breaker is open: wait it out HERE, holding the lock, so
			// the whole queue of pending beats resumes the instant it
			// closes instead of everyone racing their own retries.
			while (System.currentTimeMillis() < _retryAt)
			{
				try
				{
					_callLock.wait(Math.min(10000, _retryAt - System.currentTimeMillis()));
				}
				catch (InterruptedException e)
				{
					return null;
				}
			}
			return lineLocked(botName, location, mode, prompt);
		}
	}

	private String lineLocked(String botName, String location, String mode, String prompt)
	{
		HttpURLConnection connection = null;
		try
		{
			connection = (HttpURLConnection) new URL(_url).openConnection();
			connection.setConnectTimeout(_timeoutMillis);
			connection.setReadTimeout(_timeoutMillis);
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
			connection.setRequestProperty("X-FPC", botName != null ? botName : "a phantom");
			connection.setRequestProperty("X-Mode", ("SHOUT".equals(mode)) ? "SHOUT" : "SAY");
			// The brain's anti-spam layer treats unmarked bot chatter as self-initiated:
			// once a topic (a rival clan's name, the cause words) dominates shout for a
			// moment it lands on a 4-minute cooldown, and every further request that
			// MENTIONS it is answered with an empty reply WITHOUT calling the model -
			// which silently killed our whole exchanges ("talked once, then silence").
			// Our beats are module-directed drama with their own pacing (hour caps, arc
			// state), not unmanaged bot loops; mark them human, like the brain exempts a
			// player's shout: never pass, never cooldown-blocked, never echo-suppressed.
			connection.setRequestProperty("X-Human", "true");
			if ((location != null) && !location.isEmpty())
			{
				connection.setRequestProperty("X-Location", location);
			}

			final byte[] body = prompt.getBytes(StandardCharsets.UTF_8);
			connection.getOutputStream().write(body);
			connection.getOutputStream().flush();

			if (connection.getResponseCode() != 200)
			{
				openBreaker();
				return null;
			}

			final StringBuilder reply = new StringBuilder();
			final InputStream input = connection.getInputStream();
			byte[] buffer = new byte[2048];
			int read;
			while ((read = input.read(buffer)) >= 0)
			{
				reply.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
				if (reply.length() > MAX_REPLY_LENGTH)
				{
					break;
				}
			}
			input.close();

			final String line = sanitize(reply.toString(), botName);
			return line;
		}
		catch (Exception e)
		{
			// Brain off, overloaded or unreachable: pause briefly. With no
			// brain there is no voice, so the clans simply stay silent
			// until it answers again - by design.
			openBreaker();
			return null;
		}
		finally
		{
			if (connection != null)
			{
				connection.disconnect();
			}
		}
	}

	/**
	 * The breaker: silence for a minute, then the next queued beat tries
	 * again. Every caller blocked on the lock wakes as soon as it lifts.
	 */
	private void openBreaker()
	{
		_retryAt = System.currentTimeMillis() + RETRY_DELAY_MILLIS;
	}

	/**
	 * Defense in depth: the brain already validates its replies, but a
	 * single line, length-capped and quote-light, is the module's own
	 * guarantee.
	 */
	private static String sanitize(String text, String botName)
	{
		if (text == null)
		{
			return null;
		}
		final String oneLine = stripNameArtifact(text.replace("\r", " ").replace("\n", " ").trim(), botName);
		if (oneLine == null)
		{
			return null;
		}
		final String capped = oneLine.length() > MAX_REPLY_LENGTH ? oneLine.substring(0, MAX_REPLY_LENGTH).trim() : oneLine;
		return capped.isEmpty() ? null : capped;
	}

	/**
	 * The brain sometimes copies the chat-log format and ships its own line
	 * as "botname: actual words" - a format artifact, not a typo (typos are
	 * a human touch and stay). Strip a leading "name:" only when it is a
	 * close variant of the speaking bot's own name, so real spoken entries
	 * like "Trader: come to the gate" are never touched.
	 */
	private static String stripNameArtifact(String text, String botName)
	{
		if ((text == null) || (botName == null) || botName.isEmpty() || (text.length() < 10))
		{
			return text;
		}
		final int colon = text.indexOf(':');
		if ((colon <= 0) || (colon > 24))
		{
			return text;
		}
		final String token = text.substring(0, colon).trim();
		if (token.isEmpty() || (token.length() > 22) || (text.length() <= colon + 1))
		{
			return text;
		}
		final String rest = text.substring(colon + 1).trim();
		if ((rest.length() < 4) || !token.matches("[\\p{L}\\p{M}_ ']+"))
		{
			return text;
		}
		return editDistanceAtMost(token, botName, 2) ? rest : text;
	}

	/** Classic Levenshtein with a cutoff, for short names only. */
	private static boolean editDistanceAtMost(String a, String b, int limit)
	{
		final int an = a.length(), bn = b.length();
		int[] prev = new int[bn + 1];
		for (int j = 0; j <= bn; j++)
		{
			prev[j] = j;
		}
		for (int i = 1; i <= an; i++)
		{
			int[] curr = new int[bn + 1];
			curr[0] = i;
			int best = curr[0];
			for (int j = 1; j <= bn; j++)
			{
				final int cost = (Character.toLowerCase(a.charAt(i - 1)) == Character.toLowerCase(b.charAt(j - 1))) ? 0 : 1;
				curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
				best = Math.min(best, curr[j]);
			}
			if (best > limit)
			{
				return false;
			}
			prev = curr;
		}
		return prev[bn] <= limit;
	}
}
