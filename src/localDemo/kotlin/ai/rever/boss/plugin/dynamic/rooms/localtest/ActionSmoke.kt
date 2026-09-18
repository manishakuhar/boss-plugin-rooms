package ai.rever.boss.plugin.dynamic.rooms.localtest

import ai.rever.boss.plugin.api.*
import ai.rever.boss.plugin.dynamic.rooms.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID

/** Explicit opt-in test against fictional local data; never invokes external plugin tools. */
internal suspend fun actionSmoke(context: PluginContext) {
    val host = LocalHost(context, "leo")
    try {
        withTimeout(15000) { host.controller.state.first { it.room != null && !it.loading } }
        val repo = host.controller.repository
        val state = host.controller.state.value
        val org = checkNotNull(state.org)
        val user = checkNotNull(state.user)
        val personal = repo.create(org, "My assistant", "assistant", emptyList())
        val name = "Action verification ${UUID.randomUUID().toString().take(8)}"
        val target = repo.create(org, name, "room", emptyList())
        repo.post(org, target.id, "Local test update: the release checklist is ready.", UUID.randomUUID().toString())
        var reads = 0
        var sends = 0
        val agent = RoomsAgent(repo, { context.getPluginAPI(AiGatewayAPI::class.java) }, { null }, { host.currentUser.value?.id }, approve = { action ->
            check(action.destination == name) { "Unexpected test destination" }
            when (action.title) {
                "Read this conversation?" -> { reads++; true }
                "Send this message?" -> { check(action.content == "Rooms action verification succeeded"); sends++; true }
                else -> error("Unexpected approval")
            }
        }, progress = { println("[Rooms action smoke] $it") })
        val result = agent.run(AgentContext(user, org, personal, UUID.randomUUID().toString(), "Read the recent update in the conversation named '$name' using Rooms actions. Then send exactly 'Rooms action verification succeeded' to that same conversation using the approval flow. Do not use CLI tools. Finally summarize what happened."))
        check(result.stopReason == AiStopReason.COMPLETED) { "Agent did not complete" }
        check(reads == 1 && sends == 1) { "Expected one read and one send approval; got $reads and $sends" }
        check(repo.messages(org, target.id).count { it.body == "Rooms action verification succeeded" && it.author_kind == "assistant" } == 1)
        var declined = 0
        val denyAgent = RoomsAgent(repo, { context.getPluginAPI(AiGatewayAPI::class.java) }, { null }, { host.currentUser.value?.id }, approve = { action ->
            check(action.destination == name && action.content == "This must not be sent")
            declined++
            false
        }, progress = { println("[Rooms denial smoke] $it") })
        val denied = denyAgent.run(AgentContext(user, org, personal, UUID.randomUUID().toString(), "Send exactly 'This must not be sent' to conversation '$name' (ID ${target.id}) using the Rooms approval flow. If I decline, do not send or retry; tell me it was not sent."))
        check(declined == 1 && denied.stopReason == AiStopReason.COMPLETED)
        check(repo.messages(org, target.id).none { it.body == "This must not be sent" })
        println("[Rooms denial smoke] PASS: real provider respected declined send; no message persisted")
        println("[Rooms action smoke] PASS: real provider listed conversations, approved read, approved exact send, and persisted one message in fictional local room")
    } catch (e: Exception) {
        println("[Rooms action smoke] FAIL: ${e.message?.take(240)}")
    } finally { host.dispose() }
}
