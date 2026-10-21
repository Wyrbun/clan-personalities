/*
 * clan-personalities
 *
 * ChatQueue.java - turns decisions into delivery orders with randomized
 * delays: the "personas queue messages and delay them by a random number of
 * minutes" rule. Each entry is one scheduled send; delivery re-checks every
 * gate (hour cap, clan cooldown, member presence) at the moment it happens,
 * so a stale queue can never shout through a closed gate.
 */
package modules.clanpersonalities;

import org.l2jmobius.commons.threads.ThreadPool;

/**
 * Scheduled delayed sends.
 */
final class ChatQueue
{
	private final DramaEngine _engine;

	ChatQueue(DramaEngine engine)
	{
		_engine = engine;
	}

	/**
	 * Queues a delivery attempt after delayMillis, voice rules deciding who
	 * in the clan speaks.
	 */
	void schedule(int clanId, MessageKind kind, int otherClanId, long delayMillis)
	{
		schedule(clanId, kind, otherClanId, delayMillis, -1);
	}

	/**
	 * Queues a delivery attempt after delayMillis with an expected voice
	 * rank: leader = 0, officer = 1, member = 2. -1 lets the clan's own
	 * voice rules (stickiness plus seniority) decide.
	 */
	void schedule(int clanId, MessageKind kind, int otherClanId, long delayMillis, int voiceRank)
	{
		if ((otherClanId > 0) && (otherClanId == clanId))
		{
			// Safety net: a clan never quarrels with itself (playtest
			// 2026-10-21: "VioletSpider vs VioletSpider").
			_engine.accuseInfo("[SELF-PAIR] dropped a [" + kind + "] that paired clan " + clanId + " with itself.");
			return;
		}
		ThreadPool.schedule(() -> _engine.deliver(clanId, kind, otherClanId, voiceRank), Math.max(1, delayMillis));
	}
}
