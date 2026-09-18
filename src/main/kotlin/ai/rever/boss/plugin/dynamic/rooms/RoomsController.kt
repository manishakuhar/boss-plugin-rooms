package ai.rever.boss.plugin.dynamic.rooms

import ai.rever.boss.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

data class Approval(val action: ProposedAction, val answer: CompletableDeferred<Boolean>)
data class PendingMessage(val org: String, val room: Room, val parent: String?, val body: String, val requestId: String = UUID.randomUUID().toString(), val assistant: Boolean = false, val askAgent: Boolean = false)
data class RoomsState(
    val organizations: List<Organization> = emptyList(), val org: String? = null,
    val conversationVisit: Long = 0,
    val rooms: List<Room> = emptyList(), val people: List<Person> = emptyList(), val room: Room? = null,
    val messages: List<Message> = emptyList(), val parent: Message? = null, val replies: List<Message> = emptyList(),
    val memory: Memory = Memory(), val error: String? = null, val loading: Boolean = false,
    val status: String? = null, val approval: Approval? = null, val pending: PendingMessage? = null,
    val selectedTools: Set<String> = emptySet(), val user: String? = null,
    val errorRoom: String? = null,
    val failedAssistant: PendingMessage? = null, val failedAssistantMessage: Message? = null,
)

class RoomsController(val context: PluginContext, val scope: CoroutineScope) {
    val repository = RoomsRepository(HostRoomsTransport(context.supabaseDataProvider, context.authDataProvider))
    private val mutable = MutableStateFlow(RoomsState())
    val state: StateFlow<RoomsState> = mutable.asStateFlow()
    private val navigationStorage = context.pluginStorageFactory?.createStorage("rooms-navigation")
    private val navigation = mutableMapOf<String, String>()
    private suspend fun remembered(key: String): String? = navigation[key] ?: navigationStorage?.getString(key, "")?.takeIf { it.isNotBlank() }
    private suspend fun remember(key: String, value: String) { navigation[key] = value; navigationStorage?.putString(key, value) }
    private var generation = 0L
    private var agentJob: Job? = null
    private var activeRun: String? = null
    private var sending = false
    val drafts = mutableMapOf<String, String>()
    private fun update(block: (RoomsState) -> RoomsState) { mutable.update(block) }
    fun fail(message: String?) { update { it.copy(error = message, errorRoom = null) } }
    fun launch(block: suspend () -> Unit) = scope.launch {
        val user = state.value.user
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (user == state.value.user) fail(e.message ?: "The request failed. Try again.")
        }
    }
    private fun current(epoch: Long) { check(epoch == generation) { "Account or organization changed." } }

    init {
        scope.launch {
            context.authDataProvider?.currentUser?.map { it?.id }?.distinctUntilChanged()?.collect { user ->
                generation++; agentJob?.cancel(); mutable.value.approval?.answer?.complete(false); drafts.clear()
                mutable.value = RoomsState(user = user)
                if (user != null) loadOrganizations()
            }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                if (state.value.room != null) {
                    val epoch = generation
                    val visit = state.value.conversationVisit
                    try { refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        if (epoch != generation || visit != state.value.conversationVisit) continue
                        agentJob?.cancel(); update { it.copy(messages = emptyList(), replies = emptyList(), parent = null, error = "Connection or access changed. Reload to continue.") }
                    }
                }
            }
        }
    }
    suspend fun loadOrganizations() {
        val epoch = generation
        update { it.copy(loading = true, error = null) }
        try {
            val orgs = repository.organizations(); current(epoch)
            val previous = state.value.org
            update { it.copy(organizations = orgs) }
            if (previous != null && orgs.none { it.id == previous }) {
                generation++; stopAgent(); drafts.clear()
                mutable.value = RoomsState(user = state.value.user, organizations = orgs, error = "You no longer have access to that organization. Choose another organization.")
                return
            }
            if (previous == null) {
                val saved = remembered("org.${state.value.user}"); current(epoch)
                val target = orgs.firstOrNull { it.id == saved } ?: orgs.singleOrNull()
                if (target != null) selectOrganization(target.id)
            }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (epoch == generation) fail(e.message) }
        finally { if (epoch == generation) update { it.copy(loading = false) } }
    }
    suspend fun selectOrganization(org: String) {
        check(state.value.organizations.any { it.id == org }) { "Choose an organization you belong to." }
        generation++; val epoch = generation; stopAgent()
        update { RoomsState(organizations = it.organizations, org = org, user = it.user, loading = true) }
        try {
            val people = repository.directory(org); val rooms = repository.rooms(org); current(epoch)
            update { it.copy(people = people, rooms = rooms, loading = false) }
            remember("org.${state.value.user}", org); current(epoch)
            val saved = remembered("room.${state.value.user}.$org"); current(epoch)
            val previousRoom = rooms.firstOrNull { it.id == saved && state.value.user in it.members }
            if (previousRoom != null) open(previousRoom) else personal()
        } finally { if (epoch == generation) update { it.copy(loading = false) } }
    }
    suspend fun open(room: Room) {
        val org = state.value.org ?: return; val epoch = generation
        if (state.value.user !in room.members) repository.raw("join", org, "room_id" to s(room.id))
        current(epoch)
        update { it.copy(conversationVisit = it.conversationVisit + 1, room = room, messages = emptyList(), parent = null, replies = emptyList(), error = if (it.errorRoom != null) it.error else null) }
        refresh()
        current(epoch)
        remember("room.${state.value.user}.$org", room.id)
    }
    suspend fun personal() {
        val org = state.value.org ?: return; val epoch = generation
        val room = repository.create(org, "My assistant", "assistant", emptyList()); current(epoch); open(room)
    }
    suspend fun create(name: String, kind: String, members: List<String>, public: Boolean) {
        val org = state.value.org ?: return; val epoch = generation
        val room = repository.create(org, name.trim(), kind, members, public); current(epoch); open(room)
    }
    suspend fun refresh(older: Boolean = false) {
        val snapshot = state.value; val room = snapshot.room ?: return; val org = snapshot.org ?: return; val epoch = generation
        val rooms = repository.rooms(org)
        check(rooms.any { it.id == room.id && snapshot.user in it.members }) { "Conversation membership required." }
        val messages = repository.messages(org, room.id, before = if (older) snapshot.messages.minOfOrNull { it.seq } else null)
        val replies = snapshot.parent?.let { repository.messages(org, room.id, it.id) }.orEmpty()
        current(epoch)
        if (state.value.room?.id != room.id || state.value.parent?.id != snapshot.parent?.id) return
        update { it.copy(rooms = rooms, room = rooms.first { r -> r.id == room.id }, messages = (it.messages + messages).associateBy { m -> m.id }.values.sortedBy { m -> m.seq }, parent = it.parent?.let { p -> messages.firstOrNull { m -> m.id == p.id } ?: p }, replies = (it.replies + replies).associateBy { m -> m.id }.values.sortedBy { m -> m.seq }) }
    }
    suspend fun thread(message: Message?) {
        update { it.copy(conversationVisit = it.conversationVisit + 1, parent = message, replies = emptyList()) }
        refresh()
    }
    suspend fun olderReplies() {
        val snapshot = state.value; val parent = snapshot.parent ?: return; val org = snapshot.org ?: return; val epoch = generation
        val older = repository.messages(org, parent.room_id, parent.id, snapshot.replies.minOfOrNull { it.seq }); current(epoch)
        if (state.value.parent?.id == parent.id) update { it.copy(replies = (older + it.replies).distinctBy { m -> m.id }.sortedBy { m -> m.seq }) }
    }
    fun draftKey() = state.value.parent?.id ?: state.value.room?.id.orEmpty()
    suspend fun send(body: String) {
        val snapshot = state.value; val room = snapshot.room ?: return; val org = snapshot.org ?: return
        check(body.isNotBlank() && body.length <= 16000) { "Write a message of up to 16,000 characters." }
        check(snapshot.status == null) { "Wait for your assistant or stop its current reply first." }
        check(snapshot.pending == null && !sending) { "Retry the pending message before sending another." }
        val ask = room.kind == "assistant" || Regex("(^|\\s)@agent\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)
        check(!ask || agentJob?.isActive != true) { "Wait for your assistant or stop its current reply first." }
        update { it.copy(pending = PendingMessage(org, room, snapshot.parent?.id, body.trim(), askAgent = ask)) }
        retry()
    }
    suspend fun retry() {
        val pending = state.value.pending ?: return
        if (sending) return
        sending = true; val epoch = generation
        var posted: Message? = null
        try {
            val message = repository.post(pending.org, pending.room.id, pending.body, pending.requestId, pending.parent, pending.assistant)
            current(epoch); update { it.copy(pending = null, error = null) }; drafts.remove(pending.parent ?: pending.room.id)
            if (state.value.room?.id == pending.room.id) refresh()
            posted = message
        } finally { sending = false }
        if (pending.askAgent) posted?.let { startAgent(pending, it) }
    }
    private fun startAgent(pending: PendingMessage, message: Message) {
        val user = state.value.user ?: return; val epoch = generation; val selectedTools = state.value.selectedTools.toSet()
        val visit = state.value.conversationVisit
        val runId = UUID.randomUUID().toString(); activeRun = runId
        update { it.copy(failedAssistant = pending, failedAssistantMessage = message, error = null) }
        agentJob = scope.launch {
            try {
                if (activeRun == runId && epoch == generation) update { it.copy(status = "Waiting for assistant reply…") }
                val memory = if (pending.room.kind == "assistant") repository.memory(pending.org).body else ""
                current(epoch)
                val agent = RoomsAgent(repository, { context.getPluginAPI(AiGatewayAPI::class.java) }, { context.mcpToolRegistry }, { context.authDataProvider?.currentUser?.value?.id },
                    approve = { action ->
                        val answer = CompletableDeferred<Boolean>(); if (activeRun == runId && epoch == generation) update { it.copy(approval = Approval(action, answer), status = "Waiting for your review") }
                        try { answer.await() } finally { if (activeRun == runId && epoch == generation) update { it.copy(approval = null) } }
                    }, progress = { text -> if (activeRun == runId && epoch == generation) update { it.copy(status = text) } })
                val parent = pending.parent ?: message.id
                val result = agent.run(AgentContext(user, pending.org, pending.room, parent, pending.body, memory, selectedTools))
                current(epoch)
                val body = result.text.ifBlank { "The assistant finished without a written answer." } + if (result.stopReason != AiStopReason.COMPLETED) "\n\nStopped before completion: ${result.stopReason}." else ""
                check(body.length <= 16000) { "Assistant reply exceeded the message limit." }
                // Keep a stable ID if the final answer commits but acknowledgement is lost.
                check(state.value.pending == null) { "Another message is pending. Assistant result was not posted." }
                if (activeRun == runId && epoch == generation) update { it.copy(pending = PendingMessage(pending.org, pending.room, if (pending.room.kind == "assistant") null else parent, body, assistant = true)) }
                retry()
                if (pending.room.kind != "assistant" && state.value.room?.id == pending.room.id && state.value.parent == null && state.value.conversationVisit == visit) {
                    val root = if (pending.parent == null) message else state.value.messages.firstOrNull { it.id == pending.parent }
                    if (root != null) thread(root)
                }
                if (activeRun == runId && epoch == generation) update { it.copy(status = null, failedAssistant = null, failedAssistantMessage = null) }
            } catch (e: TimeoutCancellationException) {
                if (activeRun == runId && epoch == generation) update { it.copy(status = null, approval = null, errorRoom = pending.room.id, error = "The assistant took too long to reply. Your message is saved. Retry the reply.") }
            } catch (e: CancellationException) { if (activeRun == runId && epoch == generation) update { it.copy(status = null, approval = null) }; throw e }
            catch (e: Exception) { if (activeRun == runId && epoch == generation) update { it.copy(status = null, approval = null, errorRoom = pending.room.id, error = if (e.message?.contains("content_filter", ignoreCase = true) == true) "The AI provider declined this reply (content_filter). Your message is saved. You can retry or choose another model." else e.message ?: "Assistant failed. Your message is still saved.") } }
        }
    }
    fun retryAssistant() {
        val snapshot = state.value
        val pending = snapshot.failedAssistant ?: return
        val message = snapshot.failedAssistantMessage ?: return
        if (snapshot.status != null || agentJob?.isActive == true || snapshot.pending != null) return
        startAgent(pending, message)
    }
    fun stopAgent() { activeRun = null; agentJob?.cancel(); mutable.value.approval?.answer?.complete(false); update { it.copy(approval = null, status = null, errorRoom = it.failedAssistant?.room?.id, error = if (it.status != null) "Reply stopped. Your message is saved; you can retry when ready." else it.error) } }
    fun setTools(tools: Set<String>) { update { it.copy(selectedTools = tools) } }
    suspend fun loadMemory() { val org = state.value.org ?: return; val epoch = generation; val memory = repository.memory(org); current(epoch); update { it.copy(memory = memory) } }
    suspend fun saveMemory(body: String) { val org = state.value.org ?: return; val epoch = generation; val memory = repository.saveMemory(org, state.value.memory, body); current(epoch); update { it.copy(memory = memory) } }
    fun dispose() { stopAgent(); scope.cancel(); drafts.clear() }
}
