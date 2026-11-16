package dev.syed.hermeslocal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which dependency generation Clean up keeps: the one facts.json selects, never a guess. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StorageTest {
    @Test fun selectedFromRealFacts() {
        // engine-v0.23.0-3 on the optimize-research runner (optim/run2/update/update-facts-before.txt)
        val facts = """{"packages":{"venv":{"environment":"/root/.hermes/installs/bceb66d11c12ceaa/environments/9a63bc819c6d4c68a5ab12ceacf4e42e/venv",
            "extras":["all","edge-tts"],"stamp":"838c"}},"schema":1}"""
        assertEquals("9a63bc819c6d4c68a5ab12ceacf4e42e", Storage.selectedEnvironment(facts))
    }

    @Test fun unknownSelectionKeepsEverything() {
        assertNull(Storage.selectedEnvironment(null))
        assertNull(Storage.selectedEnvironment("{}"))
        assertNull(Storage.selectedEnvironment("""{"packages":{"venv":{"environment":"/somewhere/else"}}}"""))
        assertNull(Storage.selectedEnvironment("not json"))
    }
}
