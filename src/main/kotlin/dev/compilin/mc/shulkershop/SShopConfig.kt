package dev.compilin.mc.shulkershop

import com.electronwill.nightconfig.core.ConfigSpec
import com.electronwill.nightconfig.core.file.CommentedFileConfig
import com.electronwill.nightconfig.core.file.FileNotFoundAction
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.command.argument.ItemStackArgument
import net.minecraft.command.argument.ItemStringReader
import java.io.File
import java.util.function.Predicate

object Config {
    private const val DEFAULT_CREATE_ITEM =
        "minecraft:flower_banner_pattern{display:{Name:'{\"text\":\"Shulker Shop Spawner\"}'," +
                "Lore:['\"Right click on solid ground\"', '\"to create a shulker shop\"']}," +
                "Enchantments:[{}]}"
    private const val DEFAULT_SELECT_ITEM = "minecraft:stick"

    private val configFile = File("config/shulkershops.toml")
    private var config: CommentedFileConfig? = null
    private val spec by lazy {
        val spec = ConfigSpec()
        spec.define("select_item", DEFAULT_SELECT_ITEM, isValidItem)
        spec.define("create_item", DEFAULT_CREATE_ITEM, isValidItem)

        for (perm in SShopMod.Permission.values()) {
            spec.defineInRange("permissions.${perm.configKey}", perm.defaultPermLevel, 0, 4)
        }

        spec
    }

    var selectItem = parseItem(DEFAULT_SELECT_ITEM)
        private set
    var createItem = parseItem(DEFAULT_CREATE_ITEM)
        private set

    fun init() {
        val config = CommentedFileConfig.builder(configFile)
            .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
            .build()
        this.config = config

        if (configFile.exists()) {
            log.debug("Loading config from file")
            config.load()
            log.trace("Config values : $config")
            reload()
        } else {
            log.debug("No config file : initializing with default values")
            config.set<String>("select_item", DEFAULT_SELECT_ITEM)
            config.setComment("select_item", "Item to hold to select shops/open their inventories")

            config.set<String>("create_item", DEFAULT_CREATE_ITEM)
            config.setComment("create_item", "Item to right click with to create shops")

            for (perm in SShopMod.Permission.values()) {
                config.set<Any>("permissions.${perm.configKey}", perm.defaultPermLevel)
                config.setComment("permissions.${perm.configKey}", perm.description)
            }

            if (!configFile.parentFile.exists()) {
                configFile.parentFile.mkdirs()
            }

            config.save()
        }

        log.trace("Config initialized")
    }

    fun reload() {
        if (config == null) return

        config!!.load()
        var corrected = false
        if (!spec.isCorrect(config)) {
            spec.correct(config) { _, path, incorrect, correct ->
                log.warn("Config error: Corrected $incorrect to $correct at path $path")
                corrected = true
            }
        }

        selectItem = parseItem(config!!.get("select_item"))
        createItem = parseItem(config!!.get("create_item"))
        for (perm in SShopMod.Permission.values()) {
            perm.permissionLevel = config!!.getInt("permissions.${perm.configKey}")
        }

        if (corrected) config!!.save()
    }

    private fun parseItem(str: String): ItemStackArgument {
        val reader = ItemStringReader(StringReader(str), false).consume()
        return ItemStackArgument(reader.item, reader.tag)
    }

    private fun tryParseItem(str: String): ItemStackArgument? = try {
        parseItem(str)
    } catch (ex: CommandSyntaxException) {
        null
    }

    private val isValidItem = Predicate<Any> { it is String && tryParseItem(it) != null }
}
