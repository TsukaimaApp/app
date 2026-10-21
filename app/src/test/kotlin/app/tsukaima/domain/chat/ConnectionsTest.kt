package app.tsukaima.domain.chat

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import app.tsukaima.data.api.HermesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsTest {
    private fun target(name: String, state: String = "pending") =
        ConnectionTarget(name = name, kind = "connector", action = "connect", state = state, resolved = false)

    @Test
    fun skipAllSkipsEveryTargetWithoutSettledBy() {
        val answer = ConnectionAnswers.skipAll(listOf(target("linear"), target("github", "initiated")))

        assertEquals(
            """{"targets":[{"name":"linear","status":"skipped"},{"name":"github","status":"skipped"}]}""",
            answer.toJson().toString(),
        )
    }

    @Test
    fun approveCarriesTheEnvOnlyWhenGiven() {
        assertEquals(
            """{"targets":[{"name":"github","status":"approved"}]}""",
            ConnectionAnswers.approve("github").toJson().toString(),
        )
        assertEquals(
            """{"targets":[{"name":"github","status":"approved","env":{"GITHUB_TOKEN":"t0k","GITHUB_HOST":"github.com"}}]}""",
            ConnectionAnswers.approve("github", linkedMapOf("GITHUB_TOKEN" to "t0k", "GITHUB_HOST" to "github.com")).toJson().toString(),
        )
    }

    @Test
    fun skipOneAndContinue() {
        assertEquals("""{"targets":[{"name":"linear","status":"skipped"}]}""", ConnectionAnswers.skip("linear").toJson().toString())
        assertEquals("""{"targets":[],"settled_by":"continue"}""", ConnectionAnswers.continueNow().toJson().toString())
    }

    @Test
    fun answerNeverPrintsEnvValues() {
        val text = ConnectionAnswers.approve("github", mapOf("GITHUB_TOKEN" to "s3cr3t-value")).toString()
        assertFalse(text, "s3cr3t-value" in text)
        assertTrue(text, "GITHUB_TOKEN" in text)
    }

    @Test
    fun targetsParseKnownAndUnknownFieldsWithoutThrowing() {
        val json = HermesJson.parseToJsonElement(
            """[
              {"name":"linear","kind":"connector","action":"connect","state":"initiated","resolved":false,
               "detail":"Opens Linear","connect_url":"https://example.test/auth","display":"Linear"},
              {"name":"filesystem","kind":"mcp","action":"install","state":"pending",
               "required_env":[{"name":"ROOT","required":true,"secret":false,"default":"/srv","prompt":"Root folder"},
                               {"name":"TOKEN","required":false,"secret":true,"default":""}]},
              {"name":"weird","kind":"Quantum","action":"Teleport","state":"Superposed"},
              {"name":"  ","kind":"connector","action":"connect","state":"pending"},
              {"name":"done","kind":"connector","action":"connect","state":"connected"}
            ]""",
        ).jsonArray

        val targets = ConnectionAnswers.targets(json)

        assertEquals(listOf("linear", "filesystem", "weird", "done"), targets.map { it.name })
        val linear = targets[0]
        assertEquals("Linear", linear.label)
        assertEquals("https://example.test/auth", linear.connectUrl)
        assertEquals("Opens Linear", linear.detail)
        assertFalse(linear.resolved)
        val fs = targets[1]
        assertEquals("filesystem", fs.label)
        assertEquals(
            listOf(
                ConnectionEnvField("ROOT", required = true, secret = false, default = "/srv", prompt = "Root folder"),
                ConnectionEnvField("TOKEN", required = false, secret = true, default = "", prompt = null),
            ),
            fs.requiredEnv,
        )
        val weird = targets[2]
        assertEquals("quantum", weird.kind)
        assertEquals("teleport", weird.action)
        assertEquals("superposed", weird.state)
        assertEquals("superposed", weird.stateLabel)
        // Without the server's `resolved` flag, connected and skipped are resolved.
        assertTrue(targets[3].resolved)
        assertEquals("not connected", target("x", "not_connected").stateLabel)
    }

    @Test
    fun countdownRoundsDown() {
        // Whole minutes, rounded down, from two minutes on ...
        assertEquals("5 min left", ConnectionAnswers.countdown(deadlineAt = 1_000.0 + 300, nowSeconds = 1_000.0))
        assertEquals("4 min left", ConnectionAnswers.countdown(deadlineAt = 1_000.0 + 299.5, nowSeconds = 1_000.0))
        assertEquals("2 min left", ConnectionAnswers.countdown(deadlineAt = 1_000.0 + 179, nowSeconds = 1_000.0))
        assertEquals("2 min left", ConnectionAnswers.countdown(deadlineAt = 1_000.0 + 120, nowSeconds = 1_000.0))
        // ... seconds below, also rounded down (never "0 s": a fraction left still counts as a second).
        assertEquals("119 s left", ConnectionAnswers.countdown(deadlineAt = 1_000.0 + 119.9, nowSeconds = 1_000.0))
        assertEquals("61 s left", ConnectionAnswers.countdown(deadlineAt = 1_000.0 + 61, nowSeconds = 1_000.0))
        assertEquals("1 s left", ConnectionAnswers.countdown(deadlineAt = 1_000.0 + 0.2, nowSeconds = 1_000.0))
        assertEquals("Expired", ConnectionAnswers.countdown(deadlineAt = 1_000.0, nowSeconds = 1_000.0))
        assertEquals("Expired", ConnectionAnswers.countdown(deadlineAt = 1_000.0, nowSeconds = 1_100.0))
        assertNull(ConnectionAnswers.countdown(deadlineAt = 0.0, nowSeconds = 1_000.0))
    }

    @Test
    fun suggestionHintNamesAtMostFive() {
        assertNull(ConnectionAnswers.suggestionHint(emptyList()))
        assertEquals("Hermes suggests connecting: linear", ConnectionAnswers.suggestionHint(listOf("linear")))
        assertEquals(
            "Hermes suggests connecting: a, b, c, d, e",
            ConnectionAnswers.suggestionHint(listOf("a", "b", "c", "d", "e")),
        )
        assertEquals(
            "Hermes suggests connecting: A, B, C, D, E and 2 more",
            ConnectionAnswers.suggestionHint(listOf("a", "b", "c", "d", "e", "f", "g")) { it.uppercase() },
        )
    }

    @Test
    fun sessionOwner() {
        assertEquals(
            HermesJson.parseToJsonElement("""{"type":"session","session_id":"d4fb0dea"}""").jsonObject,
            ConnectionAnswers.sessionOwner("d4fb0dea"),
        )
    }
}
