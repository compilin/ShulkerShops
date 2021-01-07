package dev.compilin.mc.shulkershop

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.CommandNode
import dev.compilin.mc.shulkershop.SShopCommand.getOptionalArgument
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.LiteralText

object SShopDebugCommand {

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        val command: CommandNode<ServerCommandSource> = dispatcher.register(debugCommand)
        log.debug(
            "Debug command added : ${SShopCommand.printCommandNode(command)}"
        )
    }

    val debugCommand: LiteralArgumentBuilder<ServerCommandSource>
        get() = literal("${Config.COMMAND_WORD}debug")
            .requires { it.hasPermissionLevel(4) }
            .then(
                literal("cancelselection").executes(this::cancelSelection).then(
                    argument("targets", EntityArgumentType.players()).executes(this::cancelSelection)
                )
            )

    fun cancelSelection(ctx: CommandContext<ServerCommandSource>): Int {
        val arg = ctx.getOptionalArgument("targets", EntityArgumentType::getPlayers)
        log.debug("Executing debug command ${ctx.input}. Arg : ${arg
            .map { it.joinToString(prefix = "[", postfix = "]", limit = 10) { pl -> pl.gameProfile.name } }
            .orElse("None")}")
        val filter = arg
            .map<((PlayerEntity) -> Boolean)> { list -> { list.contains(it) } }
            .orElse { true } // If no target specified,
        val count = SShopMod.playerSelectionSequential
            .filter { filter(it.player) }
            .map { it.cancelSelection() }
            .count()
        ctx.source.sendFeedback(LiteralText("Cancelled $count selections"), true)
        return count
    }
}