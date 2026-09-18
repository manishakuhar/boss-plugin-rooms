package ai.rever.boss.plugin.dynamic.rooms

import ai.rever.boss.plugin.api.*
import ai.rever.boss.plugin.ui.BossDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.*
import androidx.compose.material.icons.outlined.AttachFile

@Composable fun RoomsScreen(controller: RoomsController) {
    val state by controller.state.collectAsState()
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    DisposableEffect(controller) { onDispose { controller.viewing(false) } }
    var dialog by remember { mutableStateOf<String?>(null) }
    var mobileNav by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Message>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(state.user, state.org) { dialog = null; results = emptyList(); query = ""; mobileNav = false }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
        BoxWithConstraints {
            val compact = maxWidth < 680.dp
            val short = maxHeight < 400.dp
            Column(Modifier.fillMaxSize()) {
            OrganizationSwitcher(controller, state)
            Row(Modifier.weight(1f)) {
                if (!compact) Navigation(controller, state, { dialog = it }, Modifier.width(220.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (compact) IconButton(onClick = { mobileNav = true }) { Icon(Icons.Outlined.Menu, contentDescription = "Conversations") }
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(state.room?.name ?: "Rooms", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!short) Text(when (state.room?.kind) {
                                "assistant" -> "Private conversation"
                                null -> "Choose an organization to begin"
                                else -> "${state.room?.members?.size} members · ${state.room?.visibility}"
                            }, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (state.room != null) {
                            IconButton(onClick = { results = emptyList(); query = ""; dialog = "search" }) { Icon(Icons.Outlined.Search, contentDescription = "Search conversation") }
                            var expanded by remember(state.room?.id) { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "Conversation options") }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    DropdownMenuItem(onClick = { expanded = false; controller.launch { results = controller.repository.pins(state.org!!, state.room!!.id); dialog = "pins" } }) { Text("Pinned messages") }
                                    if (state.room?.kind == "assistant") DropdownMenuItem(onClick = { expanded = false; controller.launch { controller.loadMemory(); dialog = "memory" } }) { Text("Assistant memory") }
                                    DropdownMenuItem(onClick = { expanded = false; dialog = "tools" }) { Text("Connected tools") }
                                    DropdownMenuItem(onClick = { expanded = false; dialog = "notifications" }) { Text("Notifications") }
                                    DropdownMenuItem(onClick = { expanded = false; dialog = "details" }) { Text("Conversation details") }
                                }
                            }
                        }
                    }
                    state.error?.takeIf { state.errorRoom == null || state.errorRoom == state.room?.id }?.let { error -> Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(error, color = MaterialTheme.colors.error, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.body2)
                        if (state.errorRoom != null && state.failedAssistant != null && state.pending == null) TextButton(onClick = controller::retryAssistant) { Text("Retry reply") }
                        if (state.errorRoom == null) TextButton(onClick = { controller.fail(null); controller.launch { if (state.room != null) controller.refresh() else controller.loadOrganizations() } }) { Text("Reload") }
                    } }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.pending?.let { pending -> Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("${state.delivery ?: "Delivery not yet confirmed"} · ${pending.room.name}", fontWeight = FontWeight.Bold)
                        Text(pending.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { controller.launch { controller.retry() } }) { Text("Retry same message") }
                    } }
                    Divider()
                    if (state.room == null) {
                        Column(Modifier.weight(1f).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (state.user == null) "Sign in to BOSS to open your conversations." else "Your team and your assistant, together in BOSS.", style = MaterialTheme.typography.h6)
                            Text("Use My assistant for private questions and work. Use Rooms and Messages to talk with people. An agent in a team room sees that room’s context, not your private assistant history.")
                            if (state.org == null && state.user != null) Button(onClick = { dialog = "organizations" }) { Text("Choose organization") }
                            if (state.org != null) Button(onClick = { controller.launch { controller.personal() } }) { Text("Open My assistant") }
                        }
                    } else {
                        if (state.parent != null) Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { controller.launch { controller.thread(null) } }) { Text("← Conversation") }
                            Text("Thread", style = MaterialTheme.typography.subtitle2)
                        }
                        val visibleMessages = if (state.parent == null) state.messages else state.replies
                        val listState = remember(state.user, state.org, state.room?.id, state.parent?.id, state.conversationVisit) { LazyListState() }
                        var positioned by remember(listState) { mutableStateOf(false) }
                        val followLatest = !listState.canScrollForward
                        LaunchedEffect(listState, visibleMessages.lastOrNull()?.id) {
                            if (visibleMessages.isNotEmpty()) {
                                if (!positioned || followLatest) {
                                    val lastIndex = (if (visibleMessages.size >= 100) 1 else 0) + (if (state.parent != null) 1 else 0) + visibleMessages.size
                                    withFrameNanos { }
                                    listState.scrollToItem(lastIndex)
                                }
                                positioned = true
                            }
                        }
                        LaunchedEffect(windowFocused, positioned, listState.canScrollForward, visibleMessages.lastOrNull()?.seq, state.conversationVisit) {
                            controller.viewing(windowFocused && positioned && !listState.canScrollForward)
                            if (windowFocused && positioned && !listState.canScrollForward) visibleMessages.lastOrNull()?.let { controller.launch { controller.markVisibleRead(it.seq) } }
                        }
                        if (positioned && listState.canScrollForward) TextButton(onClick = { controller.scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) } }) { Text("Jump to latest") }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).testTag("conversation-messages"), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if ((if (state.parent == null) state.messages else state.replies).size >= 100) item { TextButton(onClick = { controller.launch { if (state.parent == null) controller.refresh(older = true) else controller.olderReplies() } }) { Text("Load earlier messages") } }
                            state.parent?.let { parent -> item(key = "parent-${parent.id}") { MessageRow(parent, state, controller, false) } }
                            val messages = if (state.parent == null) state.messages else state.replies
                            if (messages.isEmpty()) item { Text("Start a conversation. Messages are shared only with its members.", Modifier.padding(vertical = 16.dp)) }
                            items(messages, key = { it.id }) { message -> MessageRow(message, state, controller, state.parent == null && state.room?.kind != "assistant") }
                            item(key = "conversation-end") { Spacer(Modifier.height(10.dp)) }
                        }
                    state.status?.let { status -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.failedAssistant?.room?.id == state.room?.id) status else "Replying in ${state.failedAssistant?.room?.name.orEmpty()}", Modifier.weight(1f), style = MaterialTheme.typography.body2)
                        TextButton(onClick = controller::stopAgent) { Text("Stop") }
                    } }
                        Composer(controller, state, short)
                    }
                }
            }
            }
            if (mobileNav) RoomsDialog("Conversations", { mobileNav = false }) {
                Navigation(controller, state, { mobileNav = false; dialog = it }, Modifier.fillMaxWidth().heightIn(max = 550.dp), afterOpen = { mobileNav = false })
            }
        }
    }
    state.approval?.let { approval -> RoomsDialog(approval.action.title, { approval.answer.complete(false) }) {
        Text("Destination: ${approval.action.destination}", fontWeight = FontWeight.Bold)
        SelectionContainerText(approval.action.content)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { approval.answer.complete(false) }) { Text("Decline") }
            Button(onClick = { approval.answer.complete(true) }) { Text("Allow this action") }
        }
    } }
    when (dialog) {
        "notifications" -> RoomsDialog("Notifications", { dialog = null }) {
            Text("In-app alerts while Rooms is open. Message previews stay private.")
            val mode = state.inbox.firstOrNull { it.room_id == state.room?.id }?.mode ?: "mentions"
            listOf("mentions" to "Direct messages and mentions", "all" to "All messages", "mute" to "Mute this conversation").forEach { (value, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == value, onClick = { controller.launch { controller.notificationMode(value) } })
                    Text(label)
                }
            }
        }
        "actions" -> RoomsDialog("Conversation actions", { dialog = null }) {
            TextButton(onClick = { dialog = "details" }) { Text("Details") }
            TextButton(onClick = { query = ""; results = emptyList(); dialog = "search" }) { Text("Search") }
            TextButton(onClick = { controller.launch { results = controller.repository.pins(state.org!!, state.room!!.id); dialog = "pins" } }) { Text("Pinned messages") }
            TextButton(onClick = { dialog = "tools" }) { Text("Connected tools") }
            if (state.room?.kind == "assistant") TextButton(onClick = { controller.launch { controller.loadMemory(); dialog = "memory" } }) { Text("What my assistant knows") }
        }
        "organizations" -> RoomsDialog("Choose organization", { dialog = null }) {
            Text("Select the organization whose conversations and people you want to use. Private assistant memory is kept separate for each organization.")
            if (state.organizations.isEmpty()) Text("No organizations loaded. Reload after signing in or installing the Rooms backend.")
            state.organizations.forEach { org -> TextButton(onClick = { dialog = null; controller.launch { controller.selectOrganization(org.id) } }) { Text((if (org.id == state.org) "✓ " else "") + org.name) } }
        }
        "room", "chat" -> CreateConversationDialog(dialog == "room", controller, state) { dialog = null }
        "memory" -> {
            var body by remember(state.memory) { mutableStateOf(state.memory.body) }
            RoomsDialog("What my assistant knows", { dialog = null }) {
                Text("Background and preferences for My assistant only. You control this text. Clear it and save to forget it. It is sent to your configured AI provider when you ask your private assistant.")
                OutlinedTextField(body, { if (it.length <= 8000) body = it }, label = { Text("About me and my work") }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp))
                Button(onClick = { controller.launch { controller.saveMemory(body); dialog = null } }) { Text("Save") }
            }
        }
        "tools" -> {
            var selected by remember { mutableStateOf(state.selectedTools) }
            val tools = controller.context.mcpToolRegistry?.tools?.value.orEmpty()
            RoomsDialog("Connected tools", { dialog = null }) {
                Text("Allow the assistant to propose using these BOSS tools. Each proposed call shows its arguments and asks for your approval. Selection applies to the next run.")
                Text("Rooms search and approved messages work without connecting extra tools. In My assistant, you can also approve reading another conversation.")
                if (tools.isEmpty()) Text("No additional tools are currently available.")
                tools.forEach { tool -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(tool.definition.name in selected, { selected = if (it) selected + tool.definition.name else selected - tool.definition.name })
                    Column { Text(tool.definition.name); Text(tool.definition.description.take(180), style = MaterialTheme.typography.caption) }
                } }
                Button(onClick = { controller.setTools(selected); dialog = null }) { Text("Save selection") }
            }
        }
        "search", "pins" -> RoomsDialog(if (dialog == "pins") "Pinned messages" else "Search this conversation", { dialog = null }) {
            if (dialog == "search") {
                OutlinedTextField(query, { query = it }, label = { Text("Search messages") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { controller.launch { results = controller.repository.search(state.org!!, state.room!!.id, query) } }) { Text("Search") }
            }
            if (results.isEmpty()) Text("No messages to show.")
            results.forEach { message -> Column(Modifier.padding(vertical = 8.dp)) {
                Text(message.body, maxLines = 5, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { controller.launch {
                    if (message.parent_id == null) controller.thread(message)
                    else {
                        val root = controller.repository.message(state.org!!, message.room_id, message.parent_id)
                        controller.thread(root)
                    }
                    dialog = null
                } }) { Text("Open thread") }
            } }
        }
        "editRoom" -> EditRoomDialog(controller, state) { dialog = null }
        "leave" -> RoomsDialog("Leave this room?", { dialog = null }) {
            Text("Your messages remain in the room. A private room needs a new invitation before you can return.")
            Button(onClick = { controller.launch { controller.repository.raw("leave", state.org!!, "room_id" to s(state.room!!.id)); dialog = null; controller.selectOrganization(state.org!!) } }) { Text("Leave room") }
        }
        "details" -> RoomsDialog(state.room?.name ?: "Conversation", { dialog = null }) {
            Text(if (state.room?.kind == "assistant") "Only you can access this conversation. The configured AI provider receives the context needed to answer your questions." else "Members can read the earlier messages in this conversation.")
            state.room?.members?.forEach { id -> Text(state.people.firstOrNull { it.id == id }?.name ?: id) }
            if (state.room?.kind == "room" && state.room?.owner_id == state.user) TextButton(onClick = { dialog = "editRoom" }) { Text("Edit room and members") }
            if (state.room?.kind == "room" && state.room?.owner_id != state.user) TextButton(onClick = { dialog = "leave" }) { Text("Leave room") }
        }
    }
}

private fun unreadLabel(state: RoomsState, room: Room): String = state.inbox.firstOrNull { it.room_id == room.id }?.unread?.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()

@Composable private fun Navigation(controller: RoomsController, state: RoomsState, openDialog: (String) -> Unit, modifier: Modifier, afterOpen: () -> Unit = {}) {
    Column(modifier.background(MaterialTheme.colors.surface).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Rooms", style = MaterialTheme.typography.h5)

        TextButton(onClick = { afterOpen(); controller.launch { controller.personal() } }, enabled = state.org != null, modifier = Modifier.fillMaxWidth()) { Text("✦ My assistant", fontWeight = if (state.room?.kind == "assistant") FontWeight.Bold else FontWeight.Normal) }
        Divider()
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Rooms", Modifier.weight(1f)); TextButton(onClick = { openDialog("room") }, enabled = state.org != null) { Text("＋") } }
        state.rooms.filter { it.kind == "room" }.forEach { room -> TextButton(onClick = { afterOpen(); controller.launch { controller.open(room) } }) { Text((if (state.user in room.members) "# " else "Join # ") + room.name + unreadLabel(state, room), maxLines = 2) } }
        Divider()
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Messages", Modifier.weight(1f)); TextButton(onClick = { openDialog("chat") }, enabled = state.org != null) { Text("＋") } }
        state.rooms.filter { it.kind == "direct" }.forEach { room -> TextButton(onClick = { afterOpen(); controller.launch { controller.open(room) } }) { Text(room.name + unreadLabel(state, room), maxLines = 2) } }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable private fun Composer(controller: RoomsController, state: RoomsState, short: Boolean) {
    val key = state.parent?.id ?: state.room!!.id
    var mentions by remember(key, state.pending) { mutableStateOf<List<String>>(emptyList()) }
    var pickingMention by remember(key) { mutableStateOf(false) }
    var draft by remember(key, state.pending) { mutableStateOf(controller.drafts[key].orEmpty()) }
    LaunchedEffect(key) { draft = controller.restoreDraft(key) }
    val dropTarget = remember(controller) { object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean = runCatching {
            val files = event.awtTransferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<*> ?: return false
            if (files.size > 5) { controller.fail("Attach up to five files at once."); return false }
            files.filterIsInstance<java.io.File>().filter { it.isFile }.forEach { controller.attach(it.absolutePath) }
            true
        }.getOrDefault(false)
    } }
    Column(Modifier.fillMaxWidth().padding(if (short) 4.dp else 12.dp).dragAndDropTarget(
        shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor) }, target = dropTarget,
    )) {
        val uploads = state.uploads.filter { it.attachment.room_id == state.room?.id && it.parent == state.parent?.id }
        if (uploads.isNotEmpty()) Column(Modifier.heightIn(max = 110.dp).verticalScroll(rememberScrollState())) {
            uploads.forEach { upload -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(upload.attachment.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (upload.ready) "Ready" else if (upload.error != null) "Failed" else "Uploading…", style = MaterialTheme.typography.caption)
                if (upload.error != null) TextButton(onClick = { controller.upload(upload.attachment.id) }) { Text("Retry") }
                TextButton(onClick = { controller.removeUpload(upload.attachment.id) }) { Text("Remove") }
            } }
        }
        OutlinedTextField(draft, { if (it.length <= 16000) { draft = it; controller.updateDraft(key, it) } }, label = { Text(if (state.room?.kind == "assistant") "Ask your assistant…" else "Message, or mention @Agent…") }, modifier = Modifier.fillMaxWidth().heightIn(min = if (short) 48.dp else 70.dp, max = if (short) 68.dp else 140.dp).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed && state.status == null && state.pending == null && draft.isNotBlank()) {
                controller.launch { controller.send(draft, mentions) }; true
            } else false
        })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.room?.kind == "assistant") "Private · uses your configured AI provider" else "Shared with conversation members", Modifier.weight(1f), style = MaterialTheme.typography.caption)
            IconButton(onClick = {
                if (controller.attachmentProvider == null) controller.fail("Attachments need an authenticated storage adapter from your BOSS administrator.")
                else controller.context.filePickerProvider?.pickFile("Attach file", listOf("png", "jpg", "jpeg", "pdf", "txt", "md", "csv")) { it?.let(controller::attach) }
            }) { Icon(Icons.Outlined.AttachFile, "Attach file") }
            if (state.room?.kind != "assistant") Box {
                TextButton(onClick = { pickingMention = true }) { Text("@") }
                DropdownMenu(pickingMention, onDismissRequest = { pickingMention = false }) {
                    state.people.filter { it.id in state.room!!.members && it.id != state.user }.forEach { person ->
                        DropdownMenuItem(onClick = {
                            val next = draft + " @${person.name} "
                            if (next.length <= 16000) { draft = next; controller.updateDraft(key, next); mentions = (mentions + person.id).distinct() }
                            pickingMention = false
                        }) { Text(person.name) }
                    }
                }
            }
            Button(onClick = { controller.launch { controller.send(draft, mentions) } }, enabled = (draft.isNotBlank() || uploads.isNotEmpty()) && uploads.all { it.ready } && state.pending == null && state.status == null) { Text("Send") }
        }
    }
}
@Composable private fun MessageRow(message: Message, state: RoomsState, controller: RoomsController, showThread: Boolean) {
    var action by remember(message.id) { mutableStateOf<String?>(null) }
    var edited by remember(message.id, message.revision) { mutableStateOf(message.body) }
    if (action != null) RoomsDialog(if (action == "edit") "Edit message" else "Delete message?", { action = null }) {
        if (action == "edit") OutlinedTextField(edited, { if (it.length <= 16000) edited = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp))
        else Text("This removes the message text and pin. Replies remain.")
        Button(onClick = { controller.launch {
            controller.repository.raw(action!!, state.org!!, "room_id" to s(message.room_id), "message_id" to s(message.id), "revision" to JsonPrimitive(message.revision), "body" to s(edited.trim()))
            action = null; controller.refresh()
        } }, enabled = action != "edit" || edited.isNotBlank()) { Text(if (action == "edit") "Save" else "Delete") }
    }
    var menu by remember(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (message.author_kind == "assistant") {
                if (state.room?.kind == "assistant") "Assistant" else "Assistant · requested by ${state.people.firstOrNull { it.id == message.author_id }?.name ?: message.author_id}"
            } else state.people.firstOrNull { it.id == message.author_id }?.name ?: message.author_id,
                modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.subtitle2)
            if (message.pinned) Text("Pinned", style = MaterialTheme.typography.caption)
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "Message options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (showThread) DropdownMenuItem(onClick = { menu = false; controller.launch { controller.thread(message) } }) { Text("Open thread") }
                    if (!message.deleted) DropdownMenuItem(onClick = { menu = false; controller.launch { controller.repository.pin(state.org!!, message, !message.pinned); controller.refresh() } }) { Text(if (message.pinned) "Unpin" else "Pin") }
                    if (!message.deleted && message.author_id == state.user) {
                        if (message.author_kind != "assistant") DropdownMenuItem(onClick = { menu = false; action = "edit" }) { Text("Edit") }
                        DropdownMenuItem(onClick = { menu = false; action = "delete" }) { Text("Delete") }
                    }
                }
            }
        }
        SelectionContainerText(if (message.deleted) "Message deleted" else message.body)
        if (!message.deleted) state.attachments.filter { it.message_id == message.id }.forEach { attachment -> AttachmentCard(attachment, controller) }
    }
}
@Composable private fun AttachmentCard(attachment: Attachment, controller: RoomsController) {
    var preview by remember(attachment.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.15f), MaterialTheme.shapes.small).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${(attachment.size + 1023) / 1024} KB", style = MaterialTheme.typography.caption) }
        if (attachment.mime.startsWith("image/")) TextButton(enabled = !loading, onClick = { controller.launch {
            loading = true
            try {
                val bytes = controller.download(attachment)
                preview = withContext(Dispatchers.IO) {
                    javax.imageio.ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
                        val readers = javax.imageio.ImageIO.getImageReaders(input); check(readers.hasNext()) { "Unsupported image." }
                        val reader = readers.next()
                        try { reader.input = input; check(reader.getWidth(0).toLong() * reader.getHeight(0) <= 16000000) { "Image is too large to preview. Download it instead." } } finally { reader.dispose() }
                    }
                    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }
            } finally { loading = false }
        } }) { Text(if (loading) "Loading…" else "Preview") }
        TextButton(onClick = {
            controller.context.filePickerProvider?.pickSaveFile(attachment.name, emptyList()) { path -> controller.launch {
                val bytes = controller.download(attachment)
                withContext(Dispatchers.IO) { java.nio.file.Files.write(java.nio.file.Path.of(path), bytes) }
            } }
        }) { Text("Download") }
    }
    preview?.let { bitmap -> RoomsDialog(attachment.name, { preview = null }) { Image(bitmap, attachment.name, Modifier.fillMaxWidth().heightIn(max = 420.dp)) } }
}
@Composable private fun SelectionContainerText(text: String) { androidx.compose.foundation.text.selection.SelectionContainer { Text(text) } }
@Composable private fun RoomsDialog(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BossDialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.medium, elevation = 8.dp) {
            Column(Modifier.widthIn(max = 540.dp).heightIn(max = 620.dp).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.h6); TextButton(onClick = close) { Text("Close") } }
                content()
            }
        }
    }
}
@Composable private fun CreateConversationDialog(room: Boolean, controller: RoomsController, state: RoomsState, close: () -> Unit) {
    var name by remember { mutableStateOf("") }; var selected by remember { mutableStateOf(setOf<String>()) }; var public by remember { mutableStateOf(false) }
    RoomsDialog(if (room) "Create room" else "New message", close) {
        if (room) {
            OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("Room name") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(public, { public = it }); Text("Anyone in this organization can join") }
        } else Text("Choose one person or several for a group. The same participants reopen an existing conversation.")
        state.people.filter { it.id != state.user }.forEach { person -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(person.id in selected, { selected = if (it) selected + person.id else selected - person.id }); Text(person.name)
        } }
        Button(onClick = { controller.launch {
            val title = if (room) name else state.people.filter { it.id in selected }.joinToString(", ") { it.name }.take(80)
            controller.create(title, if (room) "room" else "direct", selected.toList(), public); close()
        } }, enabled = if (room) name.isNotBlank() else selected.isNotEmpty()) { Text(if (room) "Create room" else "Open conversation") }
    }
}

@Composable private fun EditRoomDialog(controller: RoomsController, state: RoomsState, close: () -> Unit) {
    val room = state.room ?: return
    var name by remember { mutableStateOf(room.name) }
    var members by remember { mutableStateOf(room.members.toSet()) }
    var owner by remember { mutableStateOf(room.owner_id) }
    RoomsDialog("Edit room and members", close) {
        OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("Room name") }, modifier = Modifier.fillMaxWidth())
        Text("New members can read earlier messages. Removing a member revokes their access. Visibility stays unchanged.")
        state.people.forEach { person -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(person.id in members, { members = if (it) members + person.id else members - person.id }, enabled = person.id != state.user)
            Text(person.name)
        } }
        Text("Room owner", fontWeight = FontWeight.Bold)
        state.people.filter { it.id in members }.forEach { person -> Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(owner == person.id, onClick = { owner = person.id }); Text(person.name)
        } }
        Text("Transfer ownership before leaving a room you created.", style = MaterialTheme.typography.caption)
        Button(onClick = { controller.launch {
            controller.repository.raw("update", state.org!!, "room_id" to s(room.id), "name" to s(name.trim()), "members" to ids(members.toList()), "owner_id" to s(owner))
            controller.refresh(); close()
        } }, enabled = name.isNotBlank() && owner in members) { Text("Save changes") }
    }
}

@Composable private fun OrganizationSwitcher(controller: RoomsController, state: RoomsState) {
    var expanded by remember(state.user) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colors.surface)) {
        TextButton(onClick = { expanded = true }, enabled = state.user != null, modifier = Modifier.fillMaxWidth().height(40.dp)) {
            Text(state.organizations.firstOrNull { it.id == state.org }?.name ?: "Choose organization", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Icon(Icons.Outlined.ExpandMore, contentDescription = "Switch organization")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(max = 320.dp)) {
            Text("Your organizations", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.caption)
            state.organizations.forEach { org ->
                DropdownMenuItem(onClick = { expanded = false; if (org.id != state.org) controller.launch { controller.selectOrganization(org.id) } }) {
                    Text(org.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (org.id == state.org) Icon(Icons.Outlined.Check, contentDescription = "Current organization")
                }
            }
            Divider()
            DropdownMenuItem(onClick = { expanded = false; controller.launch { controller.loadOrganizations() } }) { Text("Refresh organizations") }
        }
    }
}
