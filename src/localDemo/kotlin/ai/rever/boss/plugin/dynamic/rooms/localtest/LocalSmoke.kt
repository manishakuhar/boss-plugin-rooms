package ai.rever.boss.plugin.dynamic.rooms.localtest

import ai.rever.boss.plugin.dynamic.rooms.*
import kotlinx.coroutines.runBlocking
import java.util.UUID

fun main() = runBlocking {
    val a = LocalHost(initialUser = "manisha")
    val b = LocalHost(initialUser = "maya")
    try {
        fun repository(host: LocalHost) = RoomsRepository(HostRoomsTransport(host.context.supabaseDataProvider, host.context.authDataProvider))
        val first = repository(a)
        val second = repository(b)
        val org = first.organizations().first().id
        val room = first.create(org, "Welcome to Rooms", "direct", listOf(b.currentUser.value!!.id))
        val text = "Local database connection verified. You can reply from either test window."
        val message = first.post(org, room.id, text, UUID.randomUUID().toString())
        check(second.messages(org, room.id).any { it.id == message.id && it.body == text })
        val private = first.create(org, "My assistant", "assistant", emptyList())
        check(runCatching { second.messages(org, private.id) }.isFailure)
        println("PASS: native Kotlin adapter, real HTTP/database, two users, private conversation denial.")
    } finally { a.dispose(); b.dispose() }
}
