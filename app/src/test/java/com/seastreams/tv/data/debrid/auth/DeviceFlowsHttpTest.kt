package com.seastreams.tv.data.debrid.auth

import com.seastreams.tv.data.debrid.DebridClientIds
import com.seastreams.tv.data.debrid.DebridCredentials
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.api.DebridAuthException
import com.seastreams.tv.data.debrid.api.DebridServiceException
import com.seastreams.tv.testutil.TestHttpServer
import com.seastreams.tv.testutil.debridEndpoints
import com.seastreams.tv.testutil.json
import com.seastreams.tv.testutil.testDebridHttp
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

/** Each service's code sign-in over a real local HTTP server, with its documented requests and answers. */
class DeviceFlowsHttpTest {

    private val rdPolls = AtomicInteger()
    private val adPolls = AtomicInteger()
    private val tbPolls = AtomicInteger()
    private val dlPolls = AtomicInteger()
    private val putPolls = AtomicInteger()
    private val forms = mutableListOf<Map<String, String>>()

    private val server = TestHttpServer {
        // Real-Debrid: code → 403 while waiting → per-user client credentials → token.
        get("/rdoauth/device/code") {
            call.json("""{"device_code":"RDDEV","user_code":"ABCD1234","interval":5,"expires_in":600,"verification_url":"https://real-debrid.com/device"}""")
        }
        get("/rdoauth/device/credentials") {
            if (rdPolls.incrementAndGet() == 1) {
                call.json("""{"error":"authorization_pending","error_code":0}""", HttpStatusCode.Forbidden)
            } else {
                call.json("""{"client_id":"USERCID","client_secret":"USERSECRET"}""")
            }
        }
        post("/rdoauth/token") {
            val form = call.receiveParameters().entries().associate { it.key to it.value.first() }
            synchronized(forms) { forms += form }
            if (form["code"] == "dead-refresh") return@post call.json("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest)
            call.json("""{"access_token":"RDACCESS","expires_in":3600,"token_type":"Bearer","refresh_token":"RDREFRESH"}""")
        }
        // AllDebrid PIN.
        get("/ad/v4.1/pin/get") {
            call.json("""{"status":"success","data":{"pin":"WXYZ","check":"CHECKHASH","expires_in":600,"user_url":"https://alldebrid.com/pin/?pin=WXYZ","base_url":"https://alldebrid.com/pin/"}}""")
        }
        post("/ad/v4/pin/check") {
            val form = call.receiveParameters()
            if (form["pin"] != "WXYZ" || form["check"] != "CHECKHASH" || call.request.queryParameters["agent"] != "SeaStreams") {
                return@post call.json("""{"status":"error","error":{"code":"PIN_INVALID"}}""")
            }
            if (adPolls.incrementAndGet() == 1) {
                call.json("""{"status":"success","data":{"activated":false,"expires_in":590}}""")
            } else {
                call.json("""{"status":"success","data":{"activated":true,"apikey":"ADKEY123","expires_in":580}}""")
            }
        }
        // TorBox.
        get("/tb/user/auth/device/start") {
            call.json("""{"success":true,"data":{"device_code":"TBDEV","code":"TB-CODE","verification_url":"https://tor.box/link","interval":5,"expires_in":600}}""")
        }
        post("/tb/user/auth/device/token") {
            val body = call.receiveText()
            when (tbPolls.incrementAndGet()) {
                1 -> call.json("""{"success":false,"error":"DEVICE_CODE_NOT_USED"}""", HttpStatusCode.BadRequest)
                else -> call.json(if (body.contains("TBDEV")) """{"success":true,"data":{"access_token":"TBTOKEN"}}""" else """{"success":false,"error":"ITEM_NOT_FOUND"}""")
            }
        }
        // Debrid-Link (client ID configured).
        post("/dl/oauth/device/code") {
            call.json("""{"device_code":"DLDEV","user_code":"DL-CODE","verification_url":"https://debrid-link.com/device","expires_in":900,"interval":3}""")
        }
        post("/dl/oauth/token") {
            val form = call.receiveParameters()
            when {
                form["grant_type"] == "refresh_token" -> call.json("""{"access_token":"DLACCESS2","expires_in":86400}""")
                dlPolls.incrementAndGet() == 1 -> call.json("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest)
                else -> call.json("""{"error":"access_denied"}""", HttpStatusCode.BadRequest)
            }
        }
        // put.io OOB code (app ID configured).
        get("/putio/oauth2/oob/code") { call.json("""{"code":"PUTCODE","qr_code_url":"https://api.put.io/qr/PUTCODE","status":"OK"}""") }
        get("/putio/oauth2/oob/code/{code}") {
            if (putPolls.incrementAndGet() == 1) call.json("""{"oauth_token":null,"status":"OK"}""") else call.json("""{"oauth_token":"PUTTOKEN","status":"OK"}""")
        }
    }

    private val flows = DeviceFlows(testDebridHttp(), server.debridEndpoints(), DebridClientIds(debridLink = "dl-client", putIo = "4242"))

    @AfterEach
    fun tearDown() = server.close()

    private fun flow(kind: DebridServiceKind) = checkNotNull(flows.flow(kind))

    @Test
    fun `Real-Debrid trades approved device credentials for refreshable tokens`() = runBlocking<Unit> {
        val rd = flow(DebridServiceKind.REAL_DEBRID)
        val code = rd.start()
        assertEquals("ABCD1234" to "https://real-debrid.com/device", code.userCode to code.verificationUrl)
        assertEquals(DevicePoll.Pending, rd.poll(code))
        val linked = rd.poll(code) as DevicePoll.Linked
        with(linked.tokens) {
            assertEquals(listOf("RDACCESS", "RDREFRESH", "USERCID", "USERSECRET"), listOf(accessToken, refreshToken, clientId, clientSecret))
            assertEquals(3_600L, expiresInSeconds)
        }
        assertEquals(
            mapOf("client_id" to "USERCID", "client_secret" to "USERSECRET", "code" to "RDDEV", "grant_type" to "http://oauth.net/grant_type/device/1.0"),
            forms.single(),
        )
        val request = server.requests.first { it.path == "/rdoauth/device/code" }
        assertEquals("X245A4XAIBGVM" to "yes", request.queryParam("client_id") to request.queryParam("new_credentials"))

        val refresher = checkNotNull(flows.of(DebridServiceKind.REAL_DEBRID))
        val creds = DebridCredentials(1, DebridServiceKind.REAL_DEBRID, "old", "RDREFRESH", "USERCID", "USERSECRET")
        assertEquals("RDACCESS", refresher.refresh(creds).accessToken)
        assertThrows<DebridAuthException> {
            refresher.refresh(DebridCredentials(1, DebridServiceKind.REAL_DEBRID, "old", "dead-refresh", "USERCID", "USERSECRET"))
        }
    }

    @Test
    fun `AllDebrid's PIN turns into an API key once activated`() = runBlocking<Unit> {
        val ad = flow(DebridServiceKind.ALL_DEBRID)
        val code = ad.start()
        assertEquals("WXYZ" to "https://alldebrid.com/pin/?pin=WXYZ", code.userCode to code.verificationUrl)
        assertEquals(DevicePoll.Pending, ad.poll(code))
        assertEquals("ADKEY123", (ad.poll(code) as DevicePoll.Linked).tokens.accessToken)
        assertTrue(server.requests.all { it.queryParam("agent") == "SeaStreams" || !it.path.startsWith("/ad/") })
        val stale = DeviceCode("OTHER", "NOPE", "https://alldebrid.com/pin/", 5, 600)
        assertEquals(DevicePoll.Expired, ad.poll(stale))
    }

    @Test
    fun `TorBox waits for the device code, then hands out a token`() = runBlocking<Unit> {
        val tb = flow(DebridServiceKind.TORBOX)
        val code = tb.start()
        assertEquals("TB-CODE", code.userCode)
        assertEquals(DevicePoll.Pending, tb.poll(code))
        assertEquals("TBTOKEN", (tb.poll(code) as DevicePoll.Linked).tokens.accessToken)
        assertEquals(DevicePoll.Expired, tb.poll(DeviceCode("GONE", "x", "https://tor.box/link", 5, 600)))
    }

    @Test
    fun `Debrid-Link and put io code sign-ins run only with a client ID`() = runBlocking<Unit> {
        val dl = flow(DebridServiceKind.DEBRID_LINK)
        val dlCode = dl.start()
        assertEquals(3 to "DL-CODE", dlCode.intervalSeconds to dlCode.userCode)
        assertEquals(DevicePoll.Pending, dl.poll(dlCode))
        assertEquals(DevicePoll.Denied, dl.poll(dlCode))
        val refreshed = checkNotNull(flows.of(DebridServiceKind.DEBRID_LINK)).refresh(DebridCredentials(2, DebridServiceKind.DEBRID_LINK, "a", "dl-refresh"))
        assertEquals("DLACCESS2" to "dl-refresh", refreshed.accessToken to refreshed.refreshToken)

        val put = flow(DebridServiceKind.PUT_IO)
        val putCode = put.start()
        assertEquals("PUTCODE" to "https://put.io/link", putCode.userCode to putCode.verificationUrl)
        assertEquals(DevicePoll.Pending, put.poll(putCode))
        assertEquals("PUTTOKEN", (put.poll(putCode) as DevicePoll.Linked).tokens.accessToken)
        assertEquals("4242", server.requests.first { it.path == "/putio/oauth2/oob/code" }.queryParam("app_id"))

        val withoutIds = DeviceFlows(testDebridHttp(), server.debridEndpoints(), DebridClientIds())
        assertNull(withoutIds.flow(DebridServiceKind.DEBRID_LINK))
        assertNull(withoutIds.flow(DebridServiceKind.PUT_IO))
        assertNull(withoutIds.flow(DebridServiceKind.PREMIUMIZE))
        assertNull(withoutIds.flow(DebridServiceKind.OFFCLOUD))
        assertEquals(
            setOf(DebridServiceKind.REAL_DEBRID, DebridServiceKind.ALL_DEBRID, DebridServiceKind.TORBOX),
            DebridServiceKind.entries.filter { it.deviceSignInAvailable(DebridClientIds()) }.toSet(),
        )
    }

    @Test
    fun `a link page that isn't https is refused`() {
        assertThrows<DebridServiceException> { requireHttps("javascript:alert(1)") }
        assertThrows<DebridServiceException> { requireHttps("http://real-debrid.com/device") }
        assertEquals("https://real-debrid.com/device", requireHttps("https://real-debrid.com/device"))
    }
}
