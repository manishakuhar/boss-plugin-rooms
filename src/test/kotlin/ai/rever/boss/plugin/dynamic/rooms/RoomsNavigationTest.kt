package ai.rever.boss.plugin.dynamic.rooms

import ai.rever.boss.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import kotlin.test.*

class RoomsNavigationTest {
    class Host(count: Int) : AutoCloseable {
        val user = MutableStateFlow<UserData?>(UserData("me", "me@example.test", "Me", null, emptyList(), 0))
        var orgs = (1..count).map { Organization("org$it", "Organization $it") }
        val saved = mutableMapOf<String,String>()
        var extraRooms = emptyList<Room>()
        var gateway: AiGatewayAPI? = null
        val posts = mutableListOf<Message>()
        val auth = Proxy.newProxyInstance(AuthDataProvider::class.java.classLoader, arrayOf(AuthDataProvider::class.java)) { _, m, _ -> if (m.name == "getCurrentUser") user else null } as AuthDataProvider
        val storage = Proxy.newProxyInstance(PluginStorageProvider::class.java.classLoader, arrayOf(PluginStorageProvider::class.java)) { _, m, a -> when(m.name) {
            "getString" -> saved[a!![0]] ?: a[1]
            "putString" -> { saved[a!![0] as String] = a[1] as String; Unit }
            else -> null
        }} as PluginStorageProvider
        val db = object : SupabaseDataProvider {
            override suspend fun select(table:String,columns:String,filters:List<QueryFilter>,range:QueryRange?) = Result.failure<String>(Exception("Unused"))
            override suspend fun rpc(function:String,parameters:String):Result<String> {
                val a = json.parseToJsonElement(parameters).jsonObject
                val p = a["payload"]!!.jsonObject
                val org = p["org_id"]?.jsonPrimitive?.content ?: ""
                val uid = user.value!!.id
                val room = Room("$org-$uid",org,"My assistant","assistant",owner_id=uid,members=listOf(uid))
                return Result.success(when(a["operation"]!!.jsonPrimitive.content) {
                    "organizations" -> json.encodeToString(orgs)
                    "directory" -> json.encodeToString(listOf(Person(uid,uid)))
                    "list" -> json.encodeToString(listOf(room) + extraRooms)
                    "create" -> json.encodeToString(room)
                    "messages" -> json.encodeToString(listOf(Message("message-$org-$uid",1,room.id,author_id=uid,body="Private $org $uid")) + posts)
                    "memory" -> json.encodeToString(Memory())
                    "post" -> { val m = Message("post-${posts.size}",posts.size.toLong()+2,room.id,author_id=uid,author_kind=p["author_kind"]!!.jsonPrimitive.content,body=p["body"]!!.jsonPrimitive.content); posts += m; json.encodeToString(m) }
                    else -> error("Unexpected operation")
                })
            }
        }
        val context = Proxy.newProxyInstance(PluginContext::class.java.classLoader,arrayOf(PluginContext::class.java)) { _, m, _ -> when(m.name) {
            "getPluginAPI" -> gateway
            "getAuthDataProvider" -> auth
            "getSupabaseDataProvider" -> db
            "getPluginStorageFactory" -> object : PluginStorageFactory { override fun createStorage(pluginId:String) = storage }
            else -> null
        }} as PluginContext
        var controller = newController()
        fun newController() = RoomsController(context,CoroutineScope(SupervisorJob()+Dispatchers.Unconfined))
        override fun close() = controller.dispose()
    }
    @Test fun immediateAssistantReplyIsPersistedAfterUserWriteCompletes() = runBlocking {
        Host(1).use { h ->
            h.gateway = object : AiGatewayAPI {
                override fun activeModel() = AiModelInfo("cli", "CLI", "default")
                override fun capabilities() = emptySet<String>()
                override fun stream(request: AiRequest): Flow<AiChunk> = error("unused")
                override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> = error("unused")
                override suspend fun complete(request: AiRequest) = Result.success(AiReply("Immediate answer"))
            }
            h.controller.send("Reply now")
            assertEquals(listOf("human", "assistant"), h.posts.map { it.author_kind })
            assertTrue(h.controller.state.value.messages.any { it.body == "Immediate answer" })
            assertNull(h.controller.state.value.pending)
            assertNull(h.controller.state.value.error)
            assertNull(h.controller.state.value.status)
        }
    }
    @Test fun timeoutIsVisibleAndRetryDoesNotDuplicateUserMessage() = runBlocking {
        Host(1).use { h ->
            var requests = 0
            h.gateway = object : AiGatewayAPI {
                override fun activeModel() = AiModelInfo("cli", "CLI", "default")
                override fun capabilities() = emptySet<String>()
                override fun stream(request: AiRequest): Flow<AiChunk> = error("unused")
                override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> = error("unused")
                override suspend fun complete(request: AiRequest): Result<AiReply> {
                    if (requests++ == 0) withTimeout(10) { awaitCancellation() }
                    delay(10)
                    return Result.success(AiReply("Recovered reply"))
                }
            }
            h.controller.send("Please reply")
            withTimeout(2000) { h.controller.state.first { it.error != null } }
            assertContains(h.controller.state.value.error!!,"too long")
            assertNotNull(h.controller.state.value.failedAssistant)
            h.controller.retryAssistant()
            withTimeout(2000) { h.controller.state.first { it.messages.any { m -> m.body == "Recovered reply" } && it.status == null } }
            assertEquals(1,h.posts.count { it.author_kind == "human" })
            assertEquals(1,h.posts.count { it.author_kind == "assistant" })
            assertNull(h.controller.state.value.error)
        }
    }
    @Test fun failureRemainsAttachedToOriginWhenSwitchingConversations() = runBlocking {
        Host(1).use { h ->
            val origin = h.controller.state.value.room!!
            val other = Room("team", "org1", "Team", "room", owner_id = "me", members = listOf("me"))
            h.extraRooms = listOf(other)
            val answer = CompletableDeferred<Result<AiReply>>()
            h.gateway = object : AiGatewayAPI {
                override fun activeModel() = AiModelInfo("cli", "CLI", "default")
                override fun capabilities() = emptySet<String>()
                override fun stream(request: AiRequest): Flow<AiChunk> = error("unused")
                override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> = error("unused")
                override suspend fun complete(request: AiRequest) = answer.await()
            }
            h.controller.drafts[origin.id] = "Original draft"
            h.controller.send("Please reply")
            h.controller.open(other)
            h.controller.drafts[other.id] = "Team draft"
            answer.complete(Result.failure(IllegalStateException("Provider unavailable")))
            withTimeout(2000) { h.controller.state.first { it.error != null } }
            assertEquals(other.id, h.controller.state.value.room!!.id)
            assertEquals(origin.id, h.controller.state.value.errorRoom)
            h.controller.open(origin)
            assertEquals("Provider unavailable", h.controller.state.value.error)
            assertEquals("Team draft", h.controller.drafts[other.id])
            assertNull(h.controller.state.value.parent)
        }
    }
    @Test fun stoppedReplyCanRetryWithoutDuplicatingTheMessage() = runBlocking {
        Host(1).use { h ->
            var requests = 0
            h.gateway = object : AiGatewayAPI {
                override fun activeModel() = AiModelInfo("cli", "CLI", "default")
                override fun capabilities() = emptySet<String>()
                override fun stream(request: AiRequest): Flow<AiChunk> = error("unused")
                override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> = error("unused")
                override suspend fun complete(request: AiRequest): Result<AiReply> {
                    if (requests++ == 0) awaitCancellation()
                    return Result.success(AiReply("Retried after stop"))
                }
            }
            h.controller.send("Please reply")
            assertNotNull(h.controller.state.value.status)
            h.controller.stopAgent()
            assertNull(h.controller.state.value.status)
            assertContains(h.controller.state.value.error!!, "stopped")
            assertEquals(h.controller.state.value.room!!.id, h.controller.state.value.errorRoom)
            h.controller.retryAssistant()
            assertEquals(1, h.posts.count { it.author_kind == "human" })
            assertEquals(1, h.posts.count { it.body == "Retried after stop" })
        }
    }
    @Test fun organizationSwitchCancelsPendingReplyAndClearsItsRecoveryState() = runBlocking {
        Host(2).use { h ->
            var cancelled = false
            h.gateway = object : AiGatewayAPI {
                override fun activeModel() = AiModelInfo("cli", "CLI", "default")
                override fun capabilities() = emptySet<String>()
                override fun stream(request: AiRequest): Flow<AiChunk> = error("unused")
                override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> = error("unused")
                override suspend fun complete(request: AiRequest): Result<AiReply> {
                    try { awaitCancellation() } finally { cancelled = true }
                }
            }
            h.controller.selectOrganization("org1")
            h.controller.send("Please reply")
            h.controller.selectOrganization("org2")
            assertTrue(cancelled)
            assertNull(h.controller.state.value.status)
            assertNull(h.controller.state.value.error)
            assertNull(h.controller.state.value.failedAssistant)
            assertEquals(0, h.posts.count { it.author_kind == "assistant" })
        }
    }
    @Test fun oneOrganizationOpensAutomaticallyAndMultipleRequireAChoice() {
        Host(1).use { assertEquals("org1",it.controller.state.value.org); assertEquals("assistant",it.controller.state.value.room?.kind) }
        Host(2).use { assertNull(it.controller.state.value.org); assertEquals(2,it.controller.state.value.organizations.size) }
    }
    @Test fun switchesRestoreConversationAndKeepDraftsAndHistorySeparated() = runBlocking {
        Host(2).use { h ->
            h.controller.selectOrganization("org1")
            val key = h.controller.draftKey(); h.controller.drafts[key]="Draft for first organization"
            h.controller.selectOrganization("org2")
            assertEquals("Private org2 me",h.controller.state.value.messages.single().body)
            assertNull(h.controller.drafts[h.controller.draftKey()])
            h.controller.selectOrganization("org1")
            assertEquals("Draft for first organization",h.controller.drafts[h.controller.draftKey()])
            h.controller.dispose(); h.controller=h.newController()
            assertEquals("org1",h.controller.state.value.org)
            assertEquals("org1-me",h.controller.state.value.room?.id)
            h.user.value=UserData("other","other@example.test","Other",null,emptyList(),0)
            assertNull(h.controller.state.value.org)
            assertTrue(h.controller.state.value.messages.isEmpty())
            assertTrue(h.controller.drafts.isEmpty())
        }
    }
    @Test fun removedOrganizationClearsPrivateContentAndOffersAnotherChoice() = runBlocking {
        Host(2).use { h ->
            h.controller.selectOrganization("org1")
            h.orgs=h.orgs.filter { it.id!="org1" }
            h.controller.loadOrganizations()
            assertNull(h.controller.state.value.org)
            assertTrue(h.controller.state.value.messages.isEmpty())
            assertContains(h.controller.state.value.error!!,"no longer have access")
            assertFailsWith<IllegalStateException> { h.controller.selectOrganization("org1") }
            Unit
        }
    }
}
