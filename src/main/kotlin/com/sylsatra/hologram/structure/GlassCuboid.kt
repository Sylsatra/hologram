package com.sylsatra.hologram.structure

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * Represents a validated filled tinted-glass cuboid in a specific world.
 */
data class GlassCuboid(
    val worldKey: RegistryKey<World>,
    val min: BlockPos,
    val max: BlockPos,
) {
    val sizeX: Int get() = max.x - min.x + 1
    val sizeY: Int get() = max.y - min.y + 1
    val sizeZ: Int get() = max.z - min.z + 1
    val volume: Int get() = sizeX * sizeY * sizeZ

    fun contains(pos: BlockPos): Boolean =
        pos.x in min.x..max.x &&
        pos.y in min.y..max.y &&
        pos.z in min.z..max.z

    override fun toString(): String =
        "GlassCuboid(world=$worldKey, min=$min, max=$max, size=${sizeX}x${sizeY}x${sizeZ}, volume=$volume)"
}
