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
 * the brain a hair more often than that so the model stays pinned - but it
 * is ARMED ONLY after a successful boot probe (a module that never went
 * live leaves no thread behind) and it pings ONLY while its gate says
 * there is someone to talk to: a warm model nobody will use is pure cost
 * on a metered cloud provider.
 */
package modules.clanpersonalities;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/**
 * A minimal, blocking, safety-capped, fully serialized client for the FPC
 * brain. CALLERS run it on the pack's BrainExecutor, never on the game's
 * own threads: a generation may take tens of seconds and the game's timers
 * must never wait behind one.
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
	private volatile long _retryAt = 0;
	private volatile Thread _keeper = null;
	private volatile boolean _keeperStop = false;
	/**
	 * The brain's anti-spam door, config-wired (BrainHumanFlag). While the
	 * brain only knows that flag, module drama needs it to pass the topic
	 * cooldowns - see lineLocked's comment. When the brain gains a proper
	 * mode for module-directed lines, the deployer flips this off.
	 */
	private volatile boolean _humanFlag = true;
	/**
	 * Reserved handshake for the brain's future "module-directed lines"
	 * mode (config BrainDirectedHeader): the brain can then keep the whole
	 * composed prompt OUT of its shared shout log - today that log reads
	 * our composed beats as if someone had shouted them, and every other
	 * bot learns our WORLD LAW rules as world chat.
	 */
	private volatile String _directedBy = null;
	/** Which brain door module lines use. PARTYEVENT (default): the brain's
	 *  directed one-line mode - the body describes the moment, the bot says
	 *  ONE line for it, with NO shared shout backlog around it and nothing
	 *  filed into that backlog. SHOUT/SAY (legacy): the line poses as a
	 *  shout the bot overhears, wrapped in the last 12 world lines - which
	 *  is why bots answered essence prices instead of the drama. */
	private volatile String _voiceMode = "PARTYEVENT";

	BrainClient(String url, int timeoutMillis)
	{
		_url = url;
		_timeoutMillis = Math.max(1000, timeoutMillis);
	}

	/** Which spam-filter doors the module knocks on (config-wired). */
	void setVoiceMode(String voiceMode)
	{
		_voiceMode = ((voiceMode != null) && voiceMode.trim().equalsIgnoreCase("LEGACY")) ? "LEGACY" : "PARTYEVENT";
	}

	void setHeaders(boolean humanFlag, String directedBy)
	{
		_humanFlag = humanFlag;
		_directedBy = (directedBy != null) && !directedBy.isEmpty() ? directedBy : null;
	}

	/**
	 * Pins the model in VRAM: one tiny brain turn every 3.5 minutes, well
	 * inside the five-minute unload timer, so no drama reply ever waits on
	 * a cold model reload. Daemon thread, started ONLY after the boot
	 * probe succeeded - a module that never went live leaves no thread
	 * behind, day or night - and every ping first ASKS THE GATE: with nobody
	 * in the world there is nothing to keep warm, and on a metered cloud
	 * provider (DeepSeek, OpenAI...) each saved ping is saved money.
	 */
	void startKeeper(BooleanSupplier gate)
	{
		stopKeeper();
		_keeperStop = false;
		final BooleanSupplier allow = gate;
		final Thread keeper = new Thread(() ->
		{
			while (!_keeperStop)
			{
				try
				{
					Thread.sleep(WARM_KEEPER_MILLIS);
				}
				catch (InterruptedException e)
				{
					return; // stopped; go quietly
				}
				if (_keeperStop || (allow == null) || !allow.getAsBoolean())
				{
					continue; // nobody in the world: the model may cool
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
		_keeper = keeper;
	}

	/** Stops the warm-keeper thread (module hot-unload hook). */
	void stopKeeper()
	{
		_keeperStop = true;
		final Thread keeper = _keeper;
		if (keeper != null)
		{
			keeper.interrupt();
			_keeper = null;
		}
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
			if (_humanFlag && "LEGACY".equals(_voiceMode))
			{
				connection.setRequestProperty("X-Human", "true"); // exempt the probe from topic-cooldown kills too
			}
			if (_directedBy != null)
			{
				connection.setRequestProperty("X-Directed-By", _directedBy); // reserved for the brain's module-directed mode
			}
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
		// The breaker is open: fail FAST. A beat that waited out the minute
		// would sit on one of the pack's shared BrainExecutor workers doing
		// nothing, and those workers also serve players' whispers and
		// party replies. The dropped beat simply doesn't happen.
		if (System.currentTimeMillis() < _retryAt)
		{
			return null;
		}
		synchronized (_callLock)
		{
			if (System.currentTimeMillis() < _retryAt)
			{
				return null; // a caller ahead of us just opened it
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
			final boolean directed = "PARTYEVENT".equals(_voiceMode);
			connection.setRequestProperty("X-Mode", directed ? "PARTYEVENT" : (("SHOUT".equals(mode)) ? "SHOUT" : "SAY"));
			if (directed)
			{
				// The brain frames PARTYEVENT bots as a party member; say plainly
				// that this one is a clan member typing in world chat instead.
				connection.setRequestProperty("X-Role", "clan member (not in any party right now)");
			}
			// The brain's anti-spam layer treats unmarked bot chatter as self-initiated:
			// once a topic (a rival clan's name, the cause words) dominates shout for a
			// moment it lands on a 4-minute cooldown, and every further request that
			// MENTIONS it is answered with an empty reply WITHOUT calling the model -
			// which silently killed our whole exchanges ("talked once, then silence").
			// Our beats are module-directed drama with their own pacing (hour caps, arc
			// state), not unmanaged bot loops; while the brain only knows the human
			// flag, these lines knock on that door (config BrainHumanFlag) - the day
			// the brain grows a proper module-directed mode, the deployer flips the
			// flag off and the X-Directed-By header takes over, so the brain can keep
			// composed prompts out of its shared shout log and re-enable its checks.
			// PARTYEVENT is the brain's own directed door: nothing is filed in
			// the shout log there, so posing as human buys nothing and only
			// muddies the brain's view. X-Human goes out on the LEGACY door only.
			if (_humanFlag && !directed)
			{
				connection.setRequestProperty("X-Human", "true");
			}
			if (_directedBy != null)
			{
				connection.setRequestProperty("X-Directed-By", _directedBy);
			}
			if ((location != null) && !location.isEmpty())
			{
				connection.setRequestProperty("X-Location", location);
			}

			final String framed = "PARTYEVENT".equals(_voiceMode)
				? ("You are NOT in a party right now: you are typing ONE line in the server-wide " + (("SHOUT".equals(mode)) ? "world (shout)" : "local") + " chat, as a member of your clan, about what is happening between the clans. Stay strictly on this moment - no party calls, no prices, no unrelated chatter.\n\nWhat is happening: " + prompt)
				: prompt;
			final byte[] body = framed.getBytes(StandardCharsets.UTF_8);
			connection.getOutputStream().write(body);
			connection.getOutputStream().flush();

			if (connection.getResponseCode() != 200)
			{
				openBreaker();
				return null;
			}

			// Collect the raw bytes first and decode ONCE: decoding each network
			// chunk on its own can split an accented letter (two UTF-8 bytes)
			// across chunks and turn it into "?".
			final java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
			final InputStream input = connection.getInputStream();
			byte[] buffer = new byte[2048];
			int read;
			while ((read = input.read(buffer)) >= 0)
			{
				raw.write(buffer, 0, read);
				if (raw.size() > (MAX_REPLY_LENGTH * 4))
				{
					break;
				}
			}
			input.close();

			final String line = sanitize(new String(raw.toByteArray(), StandardCharsets.UTF_8), botName);
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
	 * The breaker: silence for a minute - every beat in that minute is
	 * dropped at once - then the next beat tries again.
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
		// ONE line: the brain sometimes returns several chat lines at once;
		// gluing them made one shout carry two or three unrelated messages.
		String first = null;
		for (String part : text.replace("\r", "\n").split("\n"))
		{
			if (!part.trim().isEmpty())
			{
				first = part.trim();
				break;
			}
		}
		if (first == null)
		{
			return null;
		}
		final String oneLine = stripNameArtifact(first, botName);
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
