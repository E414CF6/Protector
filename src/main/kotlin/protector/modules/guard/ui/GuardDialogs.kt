package protector.modules.guard.ui

import org.bukkit.entity.Player
import protector.core.ProtectorContext

class GuardDialogs(
    private val ctx: ProtectorContext,
    private val menusProvider: () -> GuardMenus,
) {
    private val paperBackend: PaperGuardDialogs? by lazy {
        if (ctx.dialogs.supportsNativeDialogs) PaperGuardDialogs(ctx) else null
    }

    fun openGuardSettingsDialog(player: Player) {
        val backend = paperBackend
        if (backend != null) {
            backend.openGuardSettingsDialog(player)
        } else {
            menusProvider().openGuardSettingsMenu(player)
        }
    }
}
