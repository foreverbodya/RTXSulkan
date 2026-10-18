package com.sulkan.shaders.diagnostics;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

/** Authored room only in the caller's identity-checked disposable world. */
final class QaInteriorScene {
    record Setup(double[] pose, Map<String,Object> description) {}
    static Setup create(MinecraftServer server, float pitch) {
        var source=server.createCommandSourceStack().withSuppressedOutput();
        // Absolute coordinates keep repeated builds independent of biome lookup.
        // Warm emitters, a small hanging lantern, dark beams, neutral receivers,
        // rough wood, a metal accent and open windows exercise real terrain rays.
        String[] commands={
            "fill 0 124 0 16 132 14 minecraft:stone_bricks hollow",
            "fill 1 125 1 15 131 13 minecraft:air",
            "fill 1 124 1 15 124 13 minecraft:spruce_planks",
            "fill 1 125 0 15 131 0 minecraft:stripped_spruce_log[axis=x]",
            "fill 0 125 1 0 131 13 minecraft:stripped_spruce_log[axis=z]",
            "fill 1 125 14 15 131 14 minecraft:smooth_sandstone",
            "fill 16 125 1 16 131 13 minecraft:smooth_sandstone",
            "fill 1 132 1 15 132 13 minecraft:dark_oak_planks",
            "fill 4 126 14 9 129 14 minecraft:air",
            "fill 16 126 4 16 129 9 minecraft:air",
            "fill 4 125 13 9 125 14 minecraft:stone_brick_slab[type=top]",
            "fill 15 125 4 16 125 9 minecraft:stone_brick_slab[type=top]",
            "fill 1 125 7 1 131 7 minecraft:spruce_log[axis=y]",
            "fill 10 125 13 10 131 13 minecraft:spruce_log[axis=y]",
            "fill 1 131 7 15 131 7 minecraft:spruce_log[axis=x]",
            "fill 11 124 10 15 124 13 minecraft:polished_andesite",
            "fill 11 125 12 15 129 13 minecraft:deepslate_bricks",
            "fill 12 125 11 14 127 12 minecraft:air",
            "fill 12 125 12 13 125 12 minecraft:glowstone",
            "fill 11 128 11 15 128 13 minecraft:deepslate_brick_slab[type=top]",
            "fill 12 129 13 14 132 13 minecraft:deepslate_bricks",
            "setblock 6 130 7 minecraft:chain[axis=y]",
            "setblock 6 129 7 minecraft:lantern[hanging=true]",
            "fill 5 125 8 7 125 10 minecraft:spruce_fence",
            "fill 5 126 8 7 126 10 minecraft:spruce_slab[type=top]",
            "setblock 6 127 9 minecraft:iron_block",
            "fill 2 125 10 2 127 12 minecraft:bookshelf",
            "fill 3 125 12 3 126 12 minecraft:bookshelf",
            "weather clear", "time set 18000",
            "gamerule minecraft:advance_time false", "gamerule minecraft:random_tick_speed 0",
            "gamemode spectator @p", "tick freeze",
            String.format(Locale.ROOT,"tp @p 2.5 125 2.5 -39 %.5f",(double)pitch)
        };
        for(String command:commands)server.getCommands().performPrefixedCommand(source,command);
        var world=server.overworld();
        if(!world.getBlockState(new net.minecraft.core.BlockPos(12,125,12)).is(net.minecraft.world.level.block.Blocks.GLOWSTONE)
            || !world.getBlockState(new net.minecraft.core.BlockPos(6,129,7)).is(net.minecraft.world.level.block.Blocks.LANTERN)
            || !world.getBlockState(new net.minecraft.core.BlockPos(6,124,6)).is(net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS)
            || !world.getBlockState(new net.minecraft.core.BlockPos(6,127,14)).isAir())
            throw new IllegalStateException("Interior room source blocks differ from authored scene");
        if(!server.tickRateManager().isFrozen())throw new IllegalStateException("Interior world not frozen");
        var description=new LinkedHashMap<String,Object>();
        description.put("fixture","authored-room-v1");
        description.put("roomBounds",java.util.List.of(0,124,0,16,132,14));
        description.put("commands",java.util.List.of(commands));
        description.put("camera",java.util.List.of(2.5,125,2.5,-39,pitch));
        description.put("scope","Actual textured Minecraft terrain interior, two glowstone blocks, hanging lantern, open windows and furniture. Not a measured real-world lighting reference.");
        return new Setup(new double[]{2.5,125,2.5,-39},description);
    }
}
