package com.lumi.player.nfc

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [ReconnectingPresenceSession]: the fresh-session
 * reconnection that disambiguates "the tag's session went cold while the
 * tag is still in range" from "the tag genuinely left the field".
 *
 * A cold-but-present tag must NOT produce an [IOException] strike (a fresh
 * session reads it and the wrapper adopts it); a genuinely removed tag
 * must (no fresh session can read it).
 */
class ReconnectingPresenceSessionTest {

    /**
     * A fake low-level session. [answers] decides, per read number, whether
     * that read succeeds: `true` = the tag answered (in range), `false` =
     * the read threw IOException (cold session / tag out of range). Records
     * closes through [closed].
     */
    private class FakeSession(val answers: (Int) -> Boolean = { true }) {
        var reads = 0
        val closed = mutableListOf<Int>()
        fun read() {
            reads++
            if (!answers(reads)) throw IOException("tag out of range")
        }
        fun close() {
            closed.add(reads)
        }
    }

    /** A session that never answers (cold, or a removed tag's ghost). */
    private val neverAnswers: (Int) -> Boolean = { false }

    @Test
    fun healthySession_readsDirectly_neverReconnects() {
        val session0 = FakeSession()
        var reconnects = 0
        val session = ReconnectingPresenceSession(session0, { it.read() }, { reconnects++; null })
        session.read()
        session.read()
        assertEquals(2, session0.reads)
        assertEquals("a healthy session must never reconnect", 0, reconnects)
    }

    @Test
    fun coldSessionButTagStillInRange_reconnectReadsAndNoStrike() {
        // The current session went cold (every read fails) but the tag is
        // still in the field: a fresh session reads it fine. The wrapper
        // must adopt the fresh session and report NO strike - the tag is
        // present, so the caller's debounce must reset.
        val initial = FakeSession(neverAnswers)
        val fresh = FakeSession()
        var reconnects = 0
        val session = ReconnectingPresenceSession(
            initial = initial,
            readOf = { it.read() },
            reconnect = { reconnects++; if (reconnects <= 1) fresh else null },
            closeOf = { it.close() },
        )
        // First read: the cold session fails, the reconnect + fresh read
        // succeeds - no exception escapes.
        session.read()
        assertEquals(1, reconnects)
        assertEquals("the fresh session must have answered", 1, fresh.reads)
        // Subsequent reads use the adopted fresh session directly - no
        // further reconnects while it is healthy.
        session.read()
        session.read()
        assertEquals("an adopted healthy session must not keep reconnecting", 1, reconnects)
        assertEquals(3, fresh.reads)
        // Session hygiene: adopting the fresh session closed the stale one
        // exactly once; [close] then closes the (adopted) current one.
        session.close()
        assertEquals("the stale session must be closed exactly once on adoption", 1, initial.closed.size)
        assertEquals("the adopted session is closed by close()", 1, fresh.closed.size)
    }

    @Test
    fun removedTag_noFreshSession_throwsStrike() {
        // The tag is genuinely gone: the current read fails and no fresh
        // session can be established (reconnect returns null) - the read
        // must throw IOException (the strike).
        var reconnects = 0
        val session = ReconnectingPresenceSession(
            initial = FakeSession(neverAnswers),
            readOf = { it.read() },
            reconnect = { reconnects++; null },
        )
        val strike = assertThrows(IOException::class.java) { session.read() }
        assertEquals(
            "the strike must carry the original read failure so the caller's log can name it",
            "tag out of range", strike.cause?.message,
        )
        assertEquals(1, reconnects)
    }

    @Test
    fun removedTag_freshSessionCannotReadEither_throwsStrike() {
        // The tag is gone: every reconnect establishes a (dead) session but
        // its read fails. The failed fresh session must NOT be adopted - so
        // each read retries a fresh connect instead of reusing the dead one.
        var reconnects = 0
        val session = ReconnectingPresenceSession(
            initial = FakeSession(neverAnswers),
            readOf = { it.read() },
            reconnect = { reconnects++; FakeSession(neverAnswers) },
        )
        assertThrows(IOException::class.java) { session.read() }
        // The next read strikes again and reconnects again (the rejected
        // fresh session was not adopted).
        assertThrows(IOException::class.java) { session.read() }
        assertEquals(
            "a rejected fresh session is not adopted: each read retries a fresh connect",
            2, reconnects,
        )
    }

    @Test
    fun failedFreshSession_isClosedNotLeaked() {
        // The tag is gone: a reconnect opens a fresh session whose read
        // fails. That freshly-opened session must be closed (not leaked),
        // and the original current session must still be the one [close]
        // closes at the end.
        val initial = FakeSession(neverAnswers)
        val deadFresh = FakeSession(neverAnswers)
        val session = ReconnectingPresenceSession(
            initial = initial,
            readOf = { it.read() },
            reconnect = { deadFresh },
            closeOf = { it.close() },
        )
        assertThrows(IOException::class.java) { session.read() }
        assertEquals("the rejected fresh session must be closed immediately", 1, deadFresh.closed.size)
        assertEquals("the current session must not be closed before the end", 0, initial.closed.size)
        session.close()
        assertEquals(1, initial.closed.size)
    }

    @Test
    fun reconnectItselfThrows_treatedAsNoFreshSession_throwsStrike() {
        // A reconnection that throws (stale tag handle, NFC service
        // hiccup) degrades to "no fresh session": the strike stands, no
        // crash, and still names the original read failure.
        val session = ReconnectingPresenceSession(
            initial = FakeSession(neverAnswers),
            readOf = { it.read() },
            reconnect = { throw RuntimeException("connect failed") },
        )
        val strike = assertThrows(IOException::class.java) { session.read() }
        assertEquals("tag out of range", strike.cause?.message)
    }

    @Test
    fun cancellationDuringRead_propagates() {
        val session = ReconnectingPresenceSession(
            initial = FakeSession(neverAnswers),
            readOf = { throw CancellationException() },
            reconnect = { null },
        )
        assertThrows(CancellationException::class.java) { session.read() }
    }

    @Test
    fun cancellationDuringReconnect_propagates() {
        val session = ReconnectingPresenceSession(
            initial = FakeSession(neverAnswers),
            readOf = { it.read() },
            reconnect = { throw CancellationException() },
        )
        assertThrows(CancellationException::class.java) { session.read() }
    }

    @Test
    fun oscillatingColdTag_reconnectRecoversRepeatably_neverStrikes() {
        // The device oscillates: each session goes cold after ONE read but
        // a fresh session is always available (the tag stays in the field
        // the whole time). Every read must succeed through a reconnect -
        // a held-still tag that keeps going cold must never produce a
        // strike, no matter how long it is held.
        val answersOnce: (Int) -> Boolean = { n -> n == 1 }
        var reconnects = 0
        val session = ReconnectingPresenceSession(
            initial = FakeSession(neverAnswers),
            readOf = { it.read() },
            reconnect = { reconnects++; FakeSession(answersOnce) },
        )
        // Ten reads: each adopted session goes cold after one read, the
        // reconnect recovers it. No IOException may escape.
        repeat(10) { session.read() }
        assertEquals(
            "every cold read must have recovered through exactly one reconnect",
            10, reconnects,
        )
    }
}
