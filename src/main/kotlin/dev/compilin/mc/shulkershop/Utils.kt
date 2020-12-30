package dev.compilin.mc.shulkershop

import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.text.Text

fun areItemsAndTagsEqual(stack1: ItemStack, stack2: ItemStack): Boolean {
    return stack1.item == stack2.item && ItemStack.areTagsEqual(stack1, stack2)
}

fun PlayerEntity.sendMessage(message: Text) = sendMessage(message, false)