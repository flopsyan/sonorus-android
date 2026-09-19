package org.sonorus.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule that decides whether a queued write is thrown away.
 *
 * It earns a test of its own because it fails silently and late: a write
 * dropped here is a rating the user gave, watched turn amber, and finds missing
 * days later in a random run. There is no error to see and nothing in the app
 * that would say so.
 */
class WriteRuleTest {

    @Test
    fun `a thing that is gone is not worth sending again`() {
        assertTrue(permanentRefusal("not_found"))
        assertTrue(permanentRefusal("invalid_stars"))
        assertTrue(permanentRefusal("forbidden"))
    }

    @Test
    fun `a login that did not go through keeps the queue`() {
        // The regression this test exists for. `bad_login` is an ApiException
        // like any other, and the old rule kept only `not_json` and `bad_url` -
        // so one re-login that failed dropped every queued write in one pass.
        assertFalse(permanentRefusal("bad_login"))
        assertFalse(permanentRefusal("blocked"))
    }

    @Test
    fun `a proxy or a server having a moment is not the server saying no`() {
        assertFalse(permanentRefusal("not_json"))
        assertFalse(permanentRefusal("bad_url"))
        assertFalse(permanentRefusal("http_500"))
        assertFalse(permanentRefusal("http_502"))
    }
}
