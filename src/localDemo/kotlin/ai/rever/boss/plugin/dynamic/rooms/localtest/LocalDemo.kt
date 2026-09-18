package ai.rever.boss.plugin.dynamic.rooms.localtest

import ai.rever.boss.plugin.api.*
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.dynamic.rooms.RoomsAttachmentProvider
import ai.rever.boss.plugin.dynamic.rooms.RoomsController
import ai.rever.boss.plugin.dynamic.rooms.RoomsScreen
import ai.rever.boss.plugin.dynamic.rooms.TabOpeningPanelInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

private val codec = Json { ignoreUnknownKeys = true }
private val navigationLock = Any()
private const val ID = "ai.rever.boss.plugin.dynamic.rooms.localtest"
private fun connectionPath(): Path = Path.of(System.getProperty("rooms.demo.connection") ?: System.getenv("ROOMS_DEMO_CONNECTION") ?: Path.of(System.getProperty("user.dir"), ".local-rooms/connection.json").toString())

/** Only included in the separate local-test source set and artifact. */
class LocalHost(private val base: PluginContext? = null, initialUser: String = "manisha") {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private fun connection(): JsonObject = codec.parseToJsonElement(Files.readString(connectionPath())).jsonObject.also {
        check(it["mode"]?.jsonPrimitive?.content == "local-test") { "Not a local Rooms connection." }
    }
    private fun user(key: String): JsonObject = connection()["users"]!!.jsonArray.map { it.jsonObject }.first { it["key"]!!.jsonPrimitive.content == key }
    private var key = initialUser
    val currentUser = MutableStateFlow<UserData?>(null)
    init { select(initialUser) }
    fun select(selected: String) {
        val user = user(selected)
        key = selected
        currentUser.value = UserData(user["id"]!!.jsonPrimitive.content, user["email"]!!.jsonPrimitive.content, user["name"]!!.jsonPrimitive.content, null, emptyList(), 0)
    }
    private val auth = object : AuthDataProvider {
        override val currentUser = this@LocalHost.currentUser
        override val isAdmin = MutableStateFlow(false)
        override val userPermissions = MutableStateFlow(emptySet<String>())
        override fun hasPermission(permission: String) = false
        override fun hasAnyPermission(vararg permissions: String) = false
    }
    private val database = object : SupabaseDataProvider {
        override suspend fun select(table: String, columns: String, filters: List<QueryFilter>, range: QueryRange?): Result<String> = Result.failure(IllegalStateException("Local test supports the Rooms RPC only."))
        override suspend fun rpc(function: String, parameters: String): Result<String> {
            val selected = key
            return try {
                check(function == "boss_rooms_v1") { "Only Rooms operations are allowed." }
                val response = withContext(Dispatchers.IO) {
                    val data = connection()
                    val uri = URI(data["url"]!!.jsonPrimitive.content)
                    check(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.userInfo == null && uri.query == null && uri.rawPath.isNullOrEmpty()) { "Local backend must use loopback HTTP." }
                    val token = data["users"]!!.jsonArray.map { it.jsonObject }.first { it["key"]!!.jsonPrimitive.content == selected }["token"]!!.jsonPrimitive.content
                    val request = HttpRequest.newBuilder(uri.resolve("/rpc/boss_rooms_v1")).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header("Authorization", "Bearer $token").POST(HttpRequest.BodyPublishers.ofString(parameters)).build()
                    http.send(request, HttpResponse.BodyHandlers.ofString())
                }
                check(key == selected) { "Local test user changed." }
                check(response.statusCode() == 200) { "Local database request failed (${response.statusCode()})." }
                Result.success(response.body())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
    }
    private val attachmentStorage = object : RoomsAttachmentProvider {
        private suspend fun request(org: String, room: String, id: String, bytes: ByteArray?): ByteArray {
            val selected = key
            val response = runInterruptible(Dispatchers.IO) {
                val data = connection(); val uri = URI(data["url"]!!.jsonPrimitive.content)
                check(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.userInfo == null && uri.query == null && uri.rawPath.isNullOrEmpty())
                listOf(org,room,id).forEach { UUID.fromString(it) }
                val token = data["users"]!!.jsonArray.map { it.jsonObject }.first { it["key"]!!.jsonPrimitive.content == selected }["token"]!!.jsonPrimitive.content
                val request = HttpRequest.newBuilder(uri.resolve("/attachments/$id?org=$org&room=$room")).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer $token")
                if (bytes != null) request.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)) else request.GET()
                val result = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
                result.body().use { input ->
                    check(result.statusCode() == 200) { "File request failed." }
                    input.readNBytes(10485761).also { check(it.size <= 10485760) }
                }
            }
            check(key == selected) { "Account changed." }
            return response
        }
        override suspend fun upload(org: String, room: String, id: String, bytes: ByteArray, progress: (Int) -> Unit) { progress(0); request(org,room,id,bytes); progress(100) }
        override suspend fun download(org: String, room: String, id: String): ByteArray = request(org,room,id,null)
    }
    val context = Proxy.newProxyInstance(PluginContext::class.java.classLoader, arrayOf(PluginContext::class.java)) { _, method, args ->
        when (method.name) {
            "getPluginAPI" -> if (args?.firstOrNull() == RoomsAttachmentProvider::class.java) attachmentStorage else if (base == null) null else method.invoke(base, *(args ?: emptyArray()))
            "getAuthDataProvider" -> auth
            "getSupabaseDataProvider" -> database
            // Keep test identities away from real external-tool side effects.
            "getMcpToolRegistry" -> null
            "getPluginStorageFactory" -> object : PluginStorageFactory {
                override fun createStorage(pluginId: String): PluginStorageProvider = Proxy.newProxyInstance(PluginStorageProvider::class.java.classLoader, arrayOf(PluginStorageProvider::class.java)) { _, storageMethod, values ->
                    synchronized(navigationLock) {
                        val path = connectionPath().resolveSibling("navigation.json")
                        val saved = if (Files.exists(path)) codec.parseToJsonElement(Files.readString(path)).jsonObject.toMutableMap() else mutableMapOf()
                        when (storageMethod.name) {
                            "getString" -> saved[values!![0] as String]?.jsonPrimitive?.content ?: values[1]
                            "putString" -> {
                                saved[values!![0] as String] = JsonPrimitive(values[1] as String)
                                val temp = path.resolveSibling("navigation.json.tmp")
                                Files.writeString(temp, JsonObject(saved).toString())
                                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                                Unit
                            }
                            else -> error("Unsupported local navigation preference operation")
                        }
                    }
                } as PluginStorageProvider
            }
            else -> if (base == null) null else try { method.invoke(base, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        }
    } as PluginContext
    val controller = RoomsController(context, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    fun dispose() = controller.dispose()
}

@Composable fun LocalScreen(host: LocalHost, inBoss: Boolean) {
    val user by host.currentUser.collectAsState()
    Column(Modifier.fillMaxSize()) {
        var showTestMenu by remember { mutableStateOf(false) }
        Surface(color = MaterialTheme.colors.surface, contentColor = MaterialTheme.colors.onSurface) {
            Box {
                TextButton(onClick = { showTestMenu = true }) { Text("Test tools ▾", style = MaterialTheme.typography.caption) }
                DropdownMenu(expanded = showTestMenu, onDismissRequest = { showTestMenu = false }) {
                    Text("Fictional accounts · ${user?.email.orEmpty()}", Modifier.padding(12.dp), style = MaterialTheme.typography.caption)
                    listOf("manisha", "maya", "leo").forEach { key -> DropdownMenuItem(onClick = { host.select(key); showTestMenu = false }) { Text(key.replaceFirstChar { it.uppercase() }) } }
                    Divider()
                    Text(if (inBoss) "Uses BOSS AI. External tools disabled." else "AI is available in the BOSS trial app.", Modifier.padding(12.dp), style = MaterialTheme.typography.caption)
                }
            }
        }
        Box(Modifier.weight(1f)) { RoomsScreen(host.controller) }
    }
}

object RoomsLocalPlugin : DynamicPlugin {
    override val pluginId = ID
    override val displayName = "Rooms Local Test"
    override val version = "0.1.0"
    override fun register(context: PluginContext) {
        context.panelRegistry.registerPanel(TabOpeningPanelInfo(LocalPanelInfo, context)) { component, info -> LocalPanel(component, info, context) }
        context.pluginScope.launch {
            delay(15000)
            if (System.getenv("ROOMS_ATTACHMENT_SMOKE") == "1") {
                try { attachmentSmoke(context) } catch (e: Exception) { println("ROOMS_ATTACHMENT_SMOKE FAIL: ${e.javaClass.simpleName}: ${e.message}") }
            }
            if (System.getenv("ROOMS_TAB_SMOKE") == "1") {
                try {
                    delay(15000)
                    val entry = checkNotNull(context.panelRegistry.getPanelContent(LocalPanelInfo.id)).sidebarItem
                    withContext(Dispatchers.Main) { entry.onClick!!.invoke() }
                    delay(2000)
                    fun roomTabs(): List<TabInfo> {
                        val wrapper = checkNotNull(context.splitViewOperations?.getActiveTabsComponent())
                        // Test-only inspection: the public active-tabs list omits unassigned workspaces.
                        val component = wrapper.javaClass.getDeclaredField("bossTabsComponent").apply { isAccessible = true }.get(wrapper)
                        val flow = component.javaClass.getMethod("getTabsState").invoke(component) as com.arkivanov.decompose.value.Value<*>
                        val state = checkNotNull(flow.value)
                        val tabs = state.javaClass.getMethod("getTabs").invoke(state) as List<*>
                        return tabs.filterIsInstance<TabInfo>().filter { it.title == "Rooms Local Test" }
                    }
                    val first = withContext(Dispatchers.Main) { roomTabs() }
                    check(first.size == 1) { "Expected one Rooms tab, found ${first.size}" }
                    withContext(Dispatchers.Main) { entry.onClick!!.invoke() }
                    delay(1000)
                    val second = withContext(Dispatchers.Main) { roomTabs() }
                    check(second.map { it.id } == first.map { it.id }) { "Repeated click changed Rooms tab identity" }
                    println("[Rooms tab smoke] sidebar action opened one tab; repeated action retained same tab")
                } catch (e: Exception) { println("[Rooms tab smoke] failed: ${e.message}") }
            }
            if (System.getenv("ROOMS_ACTION_SMOKE") == "1") actionSmoke(context)
            val gateway = context.getPluginAPI(AiGatewayAPI::class.java)
            println("[Rooms local test] gateway=${gateway != null}, modelSelected=${gateway?.activeModel() != null}, toolSupport=${gateway?.capabilities()?.contains(AiGatewayAPI.CAPABILITY_TOOLS) == true}")
            if (System.getenv("ROOMS_CONTROLLER_SMOKE") in setOf("1", "2", "3")) {
                val testUser = if (System.getenv("ROOMS_CONTROLLER_SMOKE") == "3") "manisha" else "leo"
                val host = LocalHost(context, testUser)
                try {
                    var previousIds = emptySet<String>()
                    withContext(Dispatchers.Main) {
                        withTimeout(15000) { host.controller.state.first { it.room != null && !it.loading } }
                        host.controller.refresh()
                        previousIds = host.controller.state.value.messages.map { it.id }.toSet()
                        host.controller.send(if (System.getenv("ROOMS_CONTROLLER_SMOKE") == "3") "Hi" else if (System.getenv("ROOMS_CONTROLLER_SMOKE") == "2") "What exact phrase did you reply with in your previous message? Repeat that phrase only." else "Reply with exactly: Rooms full conversation works")
                    }
                    val completed = withTimeout(130000) { host.controller.state.first { it.error != null || it.messages.any { m -> m.id !in previousIds && m.author_kind == "assistant" && (if (System.getenv("ROOMS_CONTROLLER_SMOKE") == "3") m.body.isNotBlank() else m.body.contains("Rooms full conversation works")) } } }
                    check(completed.error == null) { completed.error ?: "Reply failed" }
                    println("[Rooms controller smoke] assistant reply saved and visible")
                    val replyId = completed.messages.last { it.id !in previousIds && it.author_kind == "assistant" }.id
                    val reopened = LocalHost(context, testUser)
                    try {
                        withTimeout(15000) { reopened.controller.state.first { state -> state.messages.any { it.id == replyId } } }
                        println("[Rooms controller smoke] saved reply restored in a fresh conversation controller")
                    } finally { reopened.dispose() }
                } catch (e: Exception) { println("[Rooms controller smoke] failed: " + e.message?.take(300)) }
                finally { host.dispose() }
            }
            if (System.getenv("ROOMS_CODEX_SMOKE") == "1") {
                try {
                    val cli = context.getPluginAPI(AiCliSessionAPI::class.java) ?: error("CLI session service unavailable")
                    check(cli.selectEngine(AiCliSessionAPI.ENGINE_CODEX)) { "Could not select Codex CLI" }
                    val reply = withTimeout(120000) { gateway!!.complete(AiRequest(system = "Answer only the supplied message. Do not use tools or inspect files.", messages = listOf(AiMessage.user("Reply with exactly: Rooms Codex connection works")), maxTokens = 100, timeoutMs = 90000)).getOrThrow() }
                    println("[Rooms Codex smoke] reply=" + reply.text.take(200))
                } catch (e: Exception) { println("[Rooms Codex smoke] failed: " + e.message?.take(300)) }
            }
        }
    }
}
object LocalTabType : TabTypeInfo {
    override val typeId = TabTypeId("rooms-local", ID)
    override val displayName = "Rooms Local Test"
    override val icon = Icons.Outlined.Forum
    override val newTabSpec = NewTabSpec(order = 311, inputLabel = "", inputPlaceholder = "", inputOptional = true, confirmLabel = "Open local test")
    override fun createTabInfo(input: String, context: NewTabContext): TabInfo = LocalInfo()
}
data class LocalInfo(override val id: String = UUID.randomUUID().toString(), override val title: String = "Rooms Local Test") : TabInfo {
    override val typeId = LocalTabType.typeId
    override val icon = Icons.Outlined.Forum
}
class LocalTab(override val config: TabInfo, ctx: ComponentContext, base: PluginContext) : TabComponentWithUI, ComponentContext by ctx {
    override val tabTypeInfo = LocalTabType
    private val host = runCatching { LocalHost(base) }
    init { lifecycle.doOnDestroy { host.getOrNull()?.dispose() } }
    @Composable override fun Content() {
        host.fold(onSuccess = { LocalScreen(it, true) }, onFailure = { Column(Modifier.padding(24.dp)) { Text("Start the local Rooms database first."); Text("Run backend/local-server.mjs, then reopen this test tab. The production BOSS database is not used.") } })
    }
}
fun main() = application {
    var users by remember { mutableStateOf(listOf("manisha", "maya")) }
    users.forEachIndexed { index, user -> key(user) {
        val host = remember { LocalHost(initialUser = user) }
        DisposableEffect(host) { onDispose { host.dispose() } }
        Window(onCloseRequest = { users = users - user; if (users.isEmpty()) exitApplication() }, title = "Rooms Local Test — $user", state = rememberWindowState(width = 850.dp, height = 720.dp, position = WindowPosition((50 + index * 80).dp, (50 + index * 50).dp))) {
            MaterialTheme(colors = darkColors(primary = androidx.compose.ui.graphics.Color(0xFFC6EC9A))) { LocalScreen(host, false) }
        }
    } }
}

object LocalPanelInfo : PanelInfo {
    override val id = PanelId("rooms-local-test", 66)
    override val displayName = "Rooms Local Test"
    override val icon = Icons.Outlined.Forum
    override val defaultSlotPosition = left.bottom
}
class LocalPanel(ctx: ComponentContext, override val panelInfo: PanelInfo, base: PluginContext) : PanelComponentWithUI, ComponentContext by ctx {
    private val host = runCatching { LocalHost(base) }
    init { lifecycle.doOnDestroy { host.getOrNull()?.dispose() } }
    @Composable override fun Content() {
        host.fold(onSuccess = { LocalScreen(it, true) }, onFailure = { Text("Start the local Rooms database, then reopen Rooms.", Modifier.padding(16.dp)) })
    }
}
