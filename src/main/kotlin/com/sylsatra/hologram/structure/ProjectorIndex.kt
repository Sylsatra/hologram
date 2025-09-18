package com.sylsatra.hologram.structure

import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos

/**
 * A lightweight cache of detected glass cuboids per world.
 *
 * For simplicity and to avoid heavy memory usage, we only store cuboids keyed by their bounds
 * and perform O(n) scans across known cuboids in a world when resolving membership or invalidation.
 * The expected number of cuboids is small in typical worlds.
 */
object ProjectorIndex {
    private data class WorldCuboids(
        val cuboids: MutableMap<String, GlassCuboid> = mutableMapOf(),
    )

    private val byWorld = mutableMapOf<String, WorldCuboids>()

    private fun worldKey(world: ServerWorld): String = world.registryKey.value.toString()

    private fun keyOf(min: BlockPos, max: BlockPos): String =
        "${min.x},${min.y},${min.z}|${max.x},${max.y},${max.z}"

    fun getOrDetect(world: ServerWorld, seed: BlockPos): GlassCuboid? {
        val wk = worldKey(world)
        val store = byWorld.getOrPut(wk) { WorldCuboids() }

        // Try to find existing cuboid containing seed
        store.cuboids.values.firstOrNull { it.contains(seed) }?.let { return it }

        // Detect new cuboid
        val detected = GlassCuboidDetector.detectFromSeed(world, seed, 64) ?: return null
        val key = keyOf(detected.min, detected.max)
        store.cuboids[key] = detected
        return detected
    }

    fun invalidateAt(world: ServerWorld, pos: BlockPos) {
        val wk = worldKey(world)
        val store = byWorld[wk] ?: return
        val iterator = store.cuboids.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.contains(pos)) {
                iterator.remove()
            }
        }
    }

    fun clearWorld(world: ServerWorld) {
        byWorld.remove(worldKey(world))
    }
}
