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
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

object Config {

    private val configItems = mutableListOf<SpecItem<*, *>>()

    val CREATE_ITEM: ItemStackArgument by SpecItem(
        "create_item",
        """minecraft:flower_banner_pattern{display:{Name:'{"text":"Shulker Shop Spawner"}',Lore:['"Right click on solid ground"', '"to create a shulker shop"']},HideFlags:32,Enchantments:[{}]}""",
        "Item to right click with to create shops",
        this::parseItem
    ) { key, defVal -> define(key, defVal, isValidItem) }

    val SELECT_ITEM: ItemStackArgument by SpecItem(
        "select_item",
        "minecraft:stick",
        "Item to hold to select shops/open their inventories",
        this::parseItem
    ) { key, defVal -> define(key, defVal, isValidItem) }

    val SELECTION_TIMEOUT: Int by SpecItem.unparsed(
        "selection_timeout",
        5,
        "Timeout duration (in minutes) of shop selections. " +
                "Can be set between 1 and 60 minutes, or to 61 to disable timeout",
        { key, defVal -> defineInRange(key, defVal, 1, 61) })

    val COMMAND_WORD: String by SpecItem.unparsed(
        "command_word",
        "sshop",
        "First word to register as root of the mod's command. Not recommended to change unless it conflicts with other mods. " +
                "May only contain basic letters",
        { key, defVal ->
            define(key, defVal) { it is String && it.matches(Regex("^[a-zA-Z]+$")) }
        }
    )

    // Might need to debug the config system so we can't have this in the config file
    val DEBUG get() = System.getProperty("shulkershop.debug", "false") == "true"

    private val configFile = File("config/shulkershops.toml")
    private val config: CommentedFileConfig = CommentedFileConfig.builder(configFile)
        .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
        .build()
    private val spec by lazy {
        val spec = ConfigSpec()
        configItems.forEach { it.runDefine(spec) }

        for (perm in SShopMod.Permission.values()) {
            spec.defineInRange("permissions.${perm.configKey}", perm.defaultPermLevel, 0, 4)
        }

        spec
    }

    fun init() {
        if (configFile.exists()) {
            log.debug("Loading config from file")
            config.load()
            log.trace("Config values : $config")
            reload()
        } else {
            log.debug("No config file : initializing with default values")

            configItems.forEach {
                config.set<Unit>(it.key, it.defaultValue)
                config.setComment(it.key, it.description)
            }

            for (perm in SShopMod.Permission.values()) {
                config.set<Unit>("permissions.${perm.configKey}", perm.defaultPermLevel)
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
        config.load()
        if (!spec.isCorrect(config)) {
            spec.correct(config) { action, path, incorrect, correct ->
                if (action == ConfigSpec.CorrectionAction.ADD) {
                    configItems.find { it.key == path.joinToString(".") }?.description
                        ?.let { config.setComment(path, it) }
                }
                log.warn("Config error: Corrected $incorrect to $correct at path $path")
            }
        }

        configItems.forEach { it.readValue(config) }

        for (perm in SShopMod.Permission.values()) {
            perm.permissionLevel = config.getInt("permissions.${perm.configKey}")
        }
    }

    fun save() {
        config.save()
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

    /**
     * Convenience class to declare a config spec node's default value and description together
     */
    data class SpecItem<C, V>(
        val key: String,
        val defaultValue: C,
        val description: String,
        val valueParser: (C) -> V,
        val define: ConfigSpec.(String, C) -> Unit
    ) : ReadOnlyProperty<Config, V> {
        private var value: V = valueParser(defaultValue)

        init {
            configItems.add(this)
        }

        internal fun runDefine(spec: ConfigSpec) {
            define.invoke(spec, key, defaultValue)
        }

        internal fun readValue(config: CommentedFileConfig) {
            val confValue: C = config.get(key)
            value = valueParser(confValue)
        }

        override operator fun getValue(thisRef: Config, property: KProperty<*>): V = value

        companion object {
            fun <T> unparsed(
                key: String,
                defaultValue: T,
                comment: String,
                define: ConfigSpec.(String, T) -> Unit
            ): SpecItem<T, T> {
                return SpecItem(key, defaultValue, comment, { it }, define)
            }
        }

    }
}
