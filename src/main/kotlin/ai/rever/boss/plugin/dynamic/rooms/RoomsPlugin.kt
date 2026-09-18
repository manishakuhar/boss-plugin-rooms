package ai.rever.boss.plugin.dynamic.rooms

import ai.rever.boss.plugin.api.*
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*

const val ROOMS_ID = "ai.rever.boss.plugin.dynamic.rooms"
object RoomsPlugin : DynamicPlugin {
    override val pluginId = ROOMS_ID
    override val displayName = "Rooms"
    override val version = "0.1.0"
    override val description = "Team conversations and your private tool-using assistant."
    override val author = "Manisha Kuhar"
    override fun register(context: PluginContext) {
        context.panelRegistry.registerPanel(TabOpeningPanelInfo(RoomsPanelInfo, context)) { component, info -> RoomsPanel(component, info, context) }
    }
}
object RoomsPanelInfo : PanelInfo {
    override val id = PanelId("rooms", 65)
    override val displayName = "Rooms"
    override val icon = Icons.Outlined.Forum
    override val defaultSlotPosition = left.bottom
}
class RoomsPanel(ctx: ComponentContext, override val panelInfo: PanelInfo, context: PluginContext) : PanelComponentWithUI, ComponentContext by ctx {
    private val controller = RoomsController(context, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    init { lifecycle.doOnDestroy { controller.dispose() } }
    @Composable override fun Content() { RoomsScreen(controller) }
}

/** Keep a fixed rail entry while letting the host preserve and focus the panel as a tab. */
class TabOpeningPanelInfo(private val panel: PanelInfo, private val context: PluginContext) : PanelInfo by panel {
    override val sidebarItem: SidebarItem
        get() = SidebarItem(id, icon, displayName, onClick = {
            val operations = checkNotNull(context.splitViewOperations) { "This host cannot open Rooms as a tab." }
            operations.openPanelAsTab(id)
        })
}
