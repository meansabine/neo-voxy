package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StairBlock.class)
public interface StairBlockAccessor {
    @Accessor("baseState")
    BlockState getBaseState();
}
