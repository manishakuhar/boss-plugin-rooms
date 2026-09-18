package ai.rever.boss.plugin.dynamic.rooms

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.SupabaseDataProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val json = Json { ignoreUnknownKeys = true }
internal fun obj(vararg values: Pair<String, JsonElement>) = buildJsonObject { values.forEach { put(it.first, it.second) } }
internal fun s(value: String) = JsonPrimitive(value)
internal fun ids(values: List<String>) = JsonArray(values.map(::s))

@Serializable data class Room(val id: String, val org_id: String, val name: String, val kind: String, val visibility: String = "private", val owner_id: String, val members: List<String>)
@Serializable data class Person(val id: String, val name: String)
@Serializable data class Organization(val id: String, val name: String)
@Serializable data class Memory(val body: String = "", val revision: Int = 0)
@Serializable data class Message(val id: String, val seq: Long, val room_id: String, val parent_id: String? = null, val author_id: String, val author_kind: String = "human", val body: String, val revision: Int = 1, val deleted: Boolean = false, val pinned: Boolean = false, val created_at: String = "")

interface RoomsTransport { suspend fun call(operation: String, payload: JsonObject): JsonElement }

class HostRoomsTransport(private val database: SupabaseDataProvider?, private val auth: AuthDataProvider?) : RoomsTransport {
    override suspend fun call(operation: String, payload: JsonObject): JsonElement {
        val user = auth?.currentUser?.value?.id ?: error("Sign in to BOSS to use Rooms.")
        val db = database ?: error("This BOSS version does not provide authenticated database access.")
        val response = db.rpc("boss_rooms_v1", obj("operation" to s(operation), "payload" to payload).toString()).getOrElse {
            if (it is CancellationException) throw it
            // Do not show response bodies, tokens or database internals in the chat.
            error("Rooms could not complete the request. Check your connection, membership, and whether the Rooms backend migration is installed.")
        }
        check(auth.currentUser.value?.id == user) { "Your account changed. Open Rooms again." }
        return json.parseToJsonElement(response)
    }
}

class RoomsRepository(private val transport: RoomsTransport) {
    suspend fun raw(operation: String, org: String, vararg fields: Pair<String, JsonElement>): JsonElement =
        transport.call(operation, obj("org_id" to s(org), *fields))
    suspend fun organizations(): List<Organization> = json.decodeFromJsonElement(transport.call("organizations", obj()))
    suspend fun directory(org: String): List<Person> = json.decodeFromJsonElement(raw("directory", org))
    suspend fun rooms(org: String): List<Room> = json.decodeFromJsonElement(raw("list", org))
    suspend fun memory(org: String): Memory = json.decodeFromJsonElement(raw("memory", org))
    suspend fun saveMemory(org: String, memory: Memory, body: String): Memory = json.decodeFromJsonElement(raw("save_memory", org, "body" to s(body), "revision" to JsonPrimitive(memory.revision)))
    suspend fun create(org: String, name: String, kind: String, members: List<String>, public: Boolean = false): Room = json.decodeFromJsonElement(raw("create", org, "name" to s(name), "kind" to s(kind), "members" to ids(members), "visibility" to s(if (public) "organization" else "private")))
    suspend fun message(org: String, room: String, id: String): Message = json.decodeFromJsonElement(raw("message", org, "room_id" to s(room), "message_id" to s(id)))
    suspend fun messages(org: String, room: String, parent: String? = null, before: Long? = null): List<Message> {
        val fields = mutableListOf("room_id" to s(room), "parent_id" to (parent?.let(::s) ?: JsonNull))
        before?.let { fields.add("before" to JsonPrimitive(it)) }
        return json.decodeFromJsonElement(raw("messages", org, *fields.toTypedArray()))
    }
    suspend fun post(org: String, room: String, body: String, request: String, parent: String? = null, assistant: Boolean = false): Message =
        json.decodeFromJsonElement(raw("post", org, "room_id" to s(room), "body" to s(body), "request_id" to s(request), "parent_id" to (parent?.let(::s) ?: JsonNull), "author_kind" to s(if (assistant) "assistant" else "human")))
    suspend fun search(org: String, room: String, query: String): List<Message> = json.decodeFromJsonElement(raw("search", org, "room_id" to s(room), "query" to s(query)))
    suspend fun pins(org: String, room: String): List<Message> = json.decodeFromJsonElement(raw("pins", org, "room_id" to s(room)))
    suspend fun pin(org: String, message: Message, pinned: Boolean): Message = json.decodeFromJsonElement(raw("pin", org, "room_id" to s(message.room_id), "message_id" to s(message.id), "revision" to JsonPrimitive(message.revision), "pinned" to JsonPrimitive(pinned)))
}
