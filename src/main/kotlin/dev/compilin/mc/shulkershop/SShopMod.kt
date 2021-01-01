package dev.compilin.mc.shulkershop

import com.github.mawillers.multiindex.MultiIndexContainer
import com.github.mawillers.multiindex.SequentialIndex
import com.github.mawillers.multiindex.UniqueIndex
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.LiteralText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.LoggerContext
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

const val MODID = "shulkershop"
val log: Logger = LogManager.getLogger(MODID)

class SShopMod : ModInitializer {

    override fun onInitialize() {
        log.info("Initializing mod ShulkerShops")
        if (Config.DEBUG) {
            val logContext = LogManager.getContext(false) as LoggerContext
            logContext.configuration.getLoggerConfig(MODID).level = Level.ALL
            logContext.updateLoggers()
            log.trace("Enabled debug mode!")
        }
        Config.init()
        SShopEventListener.initializeListeners()

        ServerWorldEvents.LOAD.register { server, world ->
            if (world == server.overworld) {
                if (shopRegistry != null) {
                    log.error("Overworld loaded more than once")
                    return@register
                }
                shopRegistry = world.persistentStateManager.getOrCreate(::ShopRegistry, ShopRegistry.ID)
                SShopEventListener.onShopsLoaded()
            }
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            SShopCommand.register(dispatcher)
            if (Config.DEBUG) {
                SShopDebugCommand.register(dispatcher)
            }
        }
        log.debug("Finished initializing")
    }

    enum class Permission(
        val configKey: String,
        val defaultPermLevel: Int,
        val description: String,
        val errorMessage: String
    ) {
        CREATE_SHOPS(
            "create", 0,
            "Can use the shop creator item",
            "You do not have permission to create shops"
        ),  // Not sure if that needs to exist but it's there for consistency
        ACCESS_OWN_SHOP_INVENTORY(
            "own.inventory", 0,
            "Can access their own shops' inventories",
            "You do not have permission to access shops' inventories"
        ),
        DELETE_OWN_SHOP(
            "own.delete", 0,
            "Can delete their own shops",
            "You do not have permission to delete shops"
        ),
        EDIT_OWN_SHOP(
            "own.edit", 0,
            "Can edit their own shops",
            "You do not have permission to edit shops"
        ),
        ACCESS_OTHERS_SHOP_INVENTORY(
            "others.inventory", 2,
            "Can access other players' shop inventories",
            "You do not have permission to access other players' shops' inventories"
        ),
        DELETE_OTHERS_SHOP(
            "others.delete", 2,
            "Can delete other players' shops",
            "You do not have permission to delete other players' shops"
        ),
        EDIT_OTHERS_SHOP(
            "others.edit", 2,
            "Can edit other players' shops",
            "You do not have permission to edit other players' shops"
        ),
        CHANGE_SHOP_OWNER(
            "change_owner", 2,
            "Can change a shop's owner",
            "You do not have permission to change shops' owners"
        ),
        MAKE_ANONYMOUS_SHOP(
            "make_anonymous", 2,
            "Can name shops without prepending them with the owner's name",
            "You do not have permission to make anonymous shops"
        ),
        GIVE_MOD_ITEMS(
            "give_items", 2,
            "Can give themselves the mod's select item or shop creator item",
            "You do not have permission to use the give subcommand"
        ),
        FORCE_DELETE_SHOP(
            "force_delete", 2,
            "Can delete shops with non-empty inventories",
            "You do not have the permission to force-delete shops"
        );

        var permissionLevel = defaultPermLevel

        val formattedError: Text
            get() = LiteralText(errorMessage).formatted(Formatting.RED)

        fun check(src: ServerCommandSource): Boolean {
            return src.hasPermissionLevel(permissionLevel)
        }

        fun check(player: PlayerEntity): Boolean {
            return player.hasPermissionLevel(permissionLevel)
        }

        fun checkOrSendError(src: ServerCommandSource): Boolean {
            return if (!check(src)) {
                src.sendError(formattedError)
                false
            } else {
                true
            }
        }

        fun checkOrSendError(player: PlayerEntity): Boolean {
            return if (!check(player)) {
                player.sendMessage(formattedError)
                false
            } else {
                true
            }
        }
    }

    /* ***********************
		Player-Shop selection
	   *********************** */
    class PlayerShopSelection(val player: PlayerEntity, val shop: ShulkerShop) {
        private var timeoutFuture: ScheduledFuture<*> =
            scheduler.schedule(this::cancelSelection, 5, TimeUnit.MINUTES)
        private var messageFuture: ScheduledFuture<*> =
            scheduler.scheduleAtFixedRate(this::refreshStatusMessage, 50, 2000, TimeUnit.MILLISECONDS)

        @Volatile
        var isCurrent = true
            private set

        @Synchronized
        internal fun refreshTimeout() {
            if (timeoutFuture.cancel(false)) {
                timeoutFuture = scheduler.schedule(this::cancelSelection, 5, TimeUnit.MINUTES)
            } else if (isCurrent) {
                log.error("Couldn't cancel timeoutFuture ")
            }
        }

        internal fun refreshStatusMessage() {
            val message = LiteralText("Currently selected shop : \"")
                .append(shop.name)
                .append("\"")
            if (timeoutFuture.getDelay(TimeUnit.SECONDS) < 20) {
                message.append(" (About to expire!").formatted(Formatting.YELLOW)
            } else if (timeoutFuture.getDelay(TimeUnit.MINUTES) < 2) {
                message.append(" (expires soon)").formatted(Formatting.ITALIC)
            }
            if (isCurrent) { // Final check to avoid race conditions if cancelled while running
                player.sendMessage(message, true)
            }
        }

        /**
         * This is called when the selection either times out or is cancelled for other reasons (e.g player logs out
         * or selects another shop)
         */
        @Synchronized
        fun cancelSelection() {
            messageFuture.cancel(false)
            timeoutFuture.cancel(false)
            isCurrent = false
            playerSelectionSequential.remove(this)
            shop.setSelectingPlayer(null)
            player.sendMessage(LiteralText.EMPTY, true)
        }
    }

    companion object {
        private val playerSelection: MultiIndexContainer<PlayerShopSelection> = MultiIndexContainer.create()
        val playerSelectionSequential: SequentialIndex<PlayerShopSelection> =
            playerSelection.createSequentialIndex()
        private val selectionByPlayer: UniqueIndex<UUID, PlayerShopSelection> =
            playerSelection.createHashedUniqueIndex { sel -> sel.player.uuid }
        private val selectionByShop: UniqueIndex<ShulkerShop, PlayerShopSelection> =
            playerSelection.createHashedUniqueIndex { sel -> sel.shop }
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r: Runnable -> newExecutorThread(r) }

        var shopRegistry: ShopRegistry? = null
            private set

        fun getSelectionByPlayer(playerId: UUID): Optional<PlayerShopSelection> {
            return getSelectionByPlayer(playerId, false)
        }

        fun getSelectionByPlayer(playerId: UUID, refresh: Boolean): Optional<PlayerShopSelection> {
            val optional: Optional<PlayerShopSelection> = selectionByPlayer.getOptional(playerId)
            if (refresh && optional.isPresent) {
                optional.get().refreshTimeout()
            }
            return optional
        }

        fun getSelectionByShop(shop: ShulkerShop): Optional<PlayerShopSelection> {
            return selectionByShop.getOptional(shop)
        }

        fun setPlayerSelection(player: PlayerEntity, shop: ShulkerShop): Boolean {
            val prevSelection: Optional<PlayerShopSelection> = selectionByPlayer.getOptional(player.uuid)
            if (prevSelection.isPresent) {
                if (prevSelection.get().shop === shop) {
                    return true // Already selected
                } else {
                    unsetPlayerSelection(player.uuid)
                }
            }
            val selection = PlayerShopSelection(player, shop)
            if (selectionByPlayer.add(selection)) {
                shop.setSelectingPlayer(player)
                selection.refreshTimeout()
                return true
            }
            return false
        }

        fun unsetPlayerSelection(playerId: UUID) {
            val selection: PlayerShopSelection? = selectionByPlayer.remove(playerId)
            if (selection != null) {
                selection.cancelSelection()
                selection.shop.setSelectingPlayer(null)
            }
        }

        fun toggleSelectPlayerShop(player: PlayerEntity, shop: ShulkerShop): Boolean {
            return if (selectionByPlayer.getOptional(player.uuid).map { sel -> sel.shop === shop }.orElse(false)) {
                unsetPlayerSelection(player.uuid)
                false
            } else {
                setPlayerSelection(player, shop)
                true
            }
        }

        fun areShopsLoaded(): Boolean {
            return shopRegistry != null
        }

        private fun newExecutorThread(r: Runnable): Thread {
            val t = Executors.defaultThreadFactory().newThread(r)
            t.isDaemon = true
            return t
        }
    }
}
