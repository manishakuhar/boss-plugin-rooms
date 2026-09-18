package ai.rever.boss.plugin.dynamic.rooms

import ai.rever.boss.plugin.api.*
import java.lang.reflect.Proxy
import kotlin.test.*

class RoomsEntryTest {
    @Test fun sidebarEntryPromotesSamePanelOnEveryClick() {
        val opened = mutableListOf<PanelId>()
        val operations = Proxy.newProxyInstance(SplitViewOperations::class.java.classLoader, arrayOf(SplitViewOperations::class.java)) { _, method, args ->
            if (method.name == "openPanelAsTab") opened.add(args!![0] as PanelId)
            null
        } as SplitViewOperations
        val context = Proxy.newProxyInstance(PluginContext::class.java.classLoader, arrayOf(PluginContext::class.java)) { _, method, _ ->
            if (method.name == "getSplitViewOperations") operations else null
        } as PluginContext
        val entry = TabOpeningPanelInfo(RoomsPanelInfo, context)
        assertEquals(RoomsPanelInfo.id, entry.id)
        repeat(2) { entry.sidebarItem.onClick!!.invoke() }
        assertEquals(listOf(RoomsPanelInfo.id, RoomsPanelInfo.id), opened)
    }
}
