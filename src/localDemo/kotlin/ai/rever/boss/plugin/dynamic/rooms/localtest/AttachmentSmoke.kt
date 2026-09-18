package ai.rever.boss.plugin.dynamic.rooms.localtest

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files

/** Opt-in smoke against fictional local accounts only. No AI or external tools. */
suspend fun attachmentSmoke(context: PluginContext) {
    val host = LocalHost(context, "manisha")
    val file = Files.createTempFile("rooms-attachment-smoke-", ".txt")
    try {
        Files.writeString(file, "Attachment smoke: private file round trip")
        withTimeout(15000) { host.controller.state.first { it.org != null && it.room != null } }
        host.controller.create("Attachment smoke ${System.currentTimeMillis()}", "room", emptyList(), false)
        host.controller.attach(file.toString())
        withTimeout(15000) { host.controller.state.first { it.uploads.any { upload -> upload.ready } } }
        host.controller.send("")
        host.controller.refresh()
        val attachment = host.controller.state.value.attachments.single()
        check(host.controller.download(attachment).decodeToString() == Files.readString(file))
        check(host.controller.state.value.uploads.isEmpty())
        println("ROOMS_ATTACHMENT_SMOKE PASS: native controller upload, attachment-only send, refresh and authenticated download")
    } finally { host.dispose(); Files.deleteIfExists(file) }
}
