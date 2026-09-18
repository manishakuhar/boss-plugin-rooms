package ai.rever.boss.plugin.dynamic.rooms

import ai.rever.boss.plugin.api.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import org.jetbrains.skia.Image

@OptIn(ExperimentalTestApi::class)
class RoomsScreenTest {
    private class Host : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val user = MutableStateFlow<UserData?>(UserData("me", "me@example.test", "Me", null, emptyList(), 0))
        val auth = object : AuthDataProvider {
            override val currentUser = user
            override val isAdmin = MutableStateFlow(false)
            override val userPermissions = MutableStateFlow(emptySet<String>())
            override fun hasPermission(permission: String) = false
            override fun hasAnyPermission(vararg permissions: String) = false
        }
        val room = Room("room", "org", "My assistant", "assistant", owner_id = "me", members = listOf("me"))
        val messages = mutableListOf(Message("one", 1, "room", author_id = "me", body = "Help me prepare for today's meeting."))
        val db = object : SupabaseDataProvider {
            override suspend fun select(table: String, columns: String, filters: List<QueryFilter>, range: QueryRange?) = Result.failure<String>(IllegalStateException("unused"))
            override suspend fun rpc(function: String, parameters: String): Result<String> {
                val args = json.parseToJsonElement(parameters).jsonObject
                val payload = args["payload"]!!.jsonObject
                return Result.success(when (args["operation"]!!.jsonPrimitive.content) {
                    "organizations" -> json.encodeToString(listOf(Organization("org", "Test organization")))
                    "directory" -> json.encodeToString(listOf(Person("me", "Me")))
                    "list" -> json.encodeToString(listOf(room))
                    "create" -> json.encodeToString(room)
                    "messages" -> json.encodeToString(messages)
                    "post" -> { val m = Message("m${messages.size}", messages.size.toLong()+1, "room", author_id = "me", body = payload["body"]!!.jsonPrimitive.content); messages += m; json.encodeToString(m) }
                    "memory" -> json.encodeToString(Memory("I work on product design", 1))
                    else -> error("Unexpected operation")
                })
            }
        }
        val context = Proxy.newProxyInstance(PluginContext::class.java.classLoader, arrayOf(PluginContext::class.java)) { _, method, _ -> when (method.name) {
            "getAuthDataProvider" -> auth
            "getSupabaseDataProvider" -> db
            else -> null
        } } as PluginContext
        val controller = RoomsController(context, scope)
        init { runBlocking { controller.selectOrganization("org"); controller.open(room) } }
        override fun close() = controller.dispose()
    }
    @Test fun privateChatKeepsComposerReachableInNarrowAndShortPanes() {
        for ((width,height) in listOf(1000 to 700, 420 to 500, 360 to 260)) {
            Host().use { host -> runDesktopComposeUiTest(width,height) {
                setContent { MaterialTheme(colors=darkColors()) { RoomsScreen(host.controller) } }
                onAllNodesWithText("My assistant").onLast().assertIsDisplayed()
                onNodeWithContentDescription("Conversation options").performClick()
                onNodeWithText("Assistant memory").assertIsDisplayed().performClick()
                onNodeWithText("About me and my work").assertExists()
                onNodeWithText("Close").performClick()
                onNodeWithText("Send").assertIsDisplayed()
                val bounds = onNodeWithText("Send").fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.top >= 0 && bounds.bottom <= height, "Send must fit in $width x $height: $bounds")
                onNode(hasSetTextAction()).performTextInput("Follow-up question")
                onNodeWithText("Follow-up question").assertIsDisplayed()
                val file = File("build/ui-screenshots/rooms-$width-$height.png"); file.parentFile.mkdirs()
                file.writeBytes(Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
            } }
        }
    }
    @Test fun actionApprovalShowsExactTextAndOnlySendsAfterClick() {
        RoomsNavigationTest.Host(1).use { host ->
            var step = 0
            host.gateway = object : AiGatewayAPI {
                override fun activeModel() = AiModelInfo("cli", "CLI", "test")
                override fun capabilities() = emptySet<String>()
                override fun stream(request: AiRequest): Flow<AiChunk> = error("unused")
                override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> = error("unused")
                override suspend fun complete(request: AiRequest) = Result.success(AiReply(if (step++ == 0) """{"type":"tool","id":"send","name":"rooms_propose_message","arguments":{"room_id":"org1-me","body":"Exact approved update"}}""" else """{"type":"answer","text":"The approved update was sent."}"""))
            }
            runDesktopComposeUiTest(420,500) {
                setContent { MaterialTheme(colors=darkColors()) { RoomsScreen(host.controller) } }
                onNode(hasSetTextAction()).performTextInput("Send my update")
                onNodeWithText("Send").performClick()
                waitForIdle()
                onNodeWithText("Send this message?").assertIsDisplayed()
                onNodeWithText("Exact approved update").assertIsDisplayed()
                assertFalse(host.posts.any { it.body == "Exact approved update" })
                onNodeWithText("Allow this action").performClick()
                waitForIdle()
                assertEquals(1,host.posts.count { it.body == "Exact approved update" })
                onNodeWithText("The approved update was sent.").assertIsDisplayed()
                onNodeWithText("Send").assertIsDisplayed()
            }
        }
    }
    @Test fun replyFailureOffersRetryAndRecoversThroughTheComposer() {
        RoomsNavigationTest.Host(1).use { host ->
            var step = 0
            host.gateway = object : AiGatewayAPI {
                override fun activeModel() = AiModelInfo("cli", "CLI", "test")
                override fun capabilities() = emptySet<String>()
                override fun stream(request: AiRequest): Flow<AiChunk> = error("unused")
                override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> = error("unused")
                override suspend fun complete(request: AiRequest): Result<AiReply> = if(step++ == 0) Result.failure(IllegalStateException("Connection interrupted")) else Result.success(AiReply("Recovered answer"))
            }
            runDesktopComposeUiTest(420,500) {
                setContent { MaterialTheme(colors=darkColors()) { RoomsScreen(host.controller) } }
                onNode(hasSetTextAction()).performTextInput("Hello")
                onNodeWithText("Send").performClick()
                waitForIdle()
                onNodeWithText("Retry reply").assertIsDisplayed().performClick()
                waitForIdle()
                onNodeWithText("Recovered answer").assertIsDisplayed()
                onNodeWithText("Retry reply").assertDoesNotExist()
                assertEquals(1,host.posts.count { it.author_kind == "human" })
            }
        }
    }
    @Test fun organizationMenuSwitchesInsideNarrowPanel() {
        RoomsNavigationTest.Host(2).use { host -> runDesktopComposeUiTest(420,500) {
            setContent { MaterialTheme(colors=darkColors()) { RoomsScreen(host.controller) } }
            onNodeWithContentDescription("Switch organization").performClick()
            onNodeWithText("Organization 2").performClick()
            waitForIdle()
            onNodeWithText("Private org2 me").assertIsDisplayed()
            onNodeWithContentDescription("Switch organization").performClick()
            onNodeWithContentDescription("Current organization").assertExists()
            onNodeWithText("Organization 1").performClick()
            waitForIdle()
            onNodeWithText("Private org1 me").assertIsDisplayed()
            onNodeWithText("Private org2 me").assertDoesNotExist()
        } }
    }
    @Test fun openingChatShowsLatestAndRefreshDoesNotPullReaderDown() {
        Host().use { host ->
            host.messages.clear()
            repeat(30) { index -> host.messages += Message("long-$index",index.toLong(),"room",author_id="me",body="History message $index") }
            runBlocking { host.controller.open(host.room) }
            runDesktopComposeUiTest(700,500) {
                setContent { MaterialTheme(colors=darkColors()) { RoomsScreen(host.controller) } }
                waitForIdle()
                onNodeWithText("History message 29").assertIsDisplayed()
                onNodeWithTag("conversation-messages").performScrollToIndex(0)
                onNodeWithText("History message 0").assertIsDisplayed()
                runOnIdle {
                    host.messages += Message("new",30,"room",author_id="me",body="Newest incoming message")
                    runBlocking { host.controller.refresh() }
                }
                waitForIdle()
                onNodeWithText("History message 0").assertIsDisplayed()
                runOnIdle { runBlocking { host.controller.open(host.room) } }
                waitForIdle()
                onNodeWithText("Newest incoming message").assertIsDisplayed()
                runOnIdle {
                    host.messages += Message("new-follow",31,"room",author_id="me",body="Follow latest reply")
                    runBlocking { host.controller.refresh() }
                }
                waitForIdle()
                onNodeWithText("Follow latest reply").assertIsDisplayed()
                onNodeWithTag("conversation-messages").performScrollToIndex(0)
                runOnIdle { runBlocking { host.controller.thread(host.messages.first()) } }
                waitForIdle()
                onNodeWithText("Follow latest reply").assertIsDisplayed()
            }
        }
    }
    @Test fun signingOutClearsConversationText() {
        Host().use { host -> runDesktopComposeUiTest(1000,700) {
            setContent { MaterialTheme(colors=darkColors()) { RoomsScreen(host.controller) } }
            onNodeWithText("Help me prepare for today's meeting.").assertIsDisplayed()
            runOnIdle { host.user.value = null }
            waitForIdle()
            onNodeWithText("Help me prepare for today's meeting.").assertDoesNotExist()
            onNodeWithText("Sign in to BOSS to open your conversations.").assertIsDisplayed()
        } }
    }
}
