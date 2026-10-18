/*
 * clan-personalities
 *
 * MessageKind.java - what a queued message is, situationally. The kind shapes
 * the brain's prompt and decides which other clans may answer it.
 */
package modules.clanpersonalities;

enum MessageKind
{
	AMBIENT, // low-key chatter, answers nobody and is not answered
	START, // opens a drama mentioned against another clan
	QUESTION, // the called-out clan demanding to know what the other wants
	EXPLAIN, // the accuser explaining its side, grounded in the incident
	RESPONSE, // answers an earlier START inside an ongoing argument
	ASIDE, // a junior clanmate blurting around a main beat; advances nothing
	KINDNESS, // a helpful or friendly gesture toward another clan
	PRAISE, // the helped clan returning thanks for a kindness
	COCKY, // the helper playing cool about the received thanks
	HISTORY // an OLD quarrel remembered aloud; may draw a world question
}
