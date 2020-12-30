package dev.compilin.mc.shulkershop.mixins;

import dev.compilin.mc.shulkershop.GoalSelectorAccess;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.ai.goal.PrioritizedGoal;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * Allows accessing the goals collection in GoalSelector to be able to clear ShulkerEntity's targetSelector and goalSelector
 * if they are attached to a shop
 */
@Mixin(GoalSelector.class)
public abstract class GoalSelectorAccessMixin implements GoalSelectorAccess {
    @Override
    @Accessor("goals")
    public abstract @NotNull Set<PrioritizedGoal> getGoals();
}
