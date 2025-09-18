package com.sylsatra.hologram.structure

import net.minecraft.block.Blocks
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import java.util.ArrayDeque
import java.util.HashSet

/**
 * Detects a filled cuboid of tinted glass starting from any seed tinted glass block.
 * Returns null if the connected component is not a perfect filled cuboid or exceeds size limits per axis.
 */
object GlassCuboidDetector {
    private val NEIGHBORS = arrayOf(
        BlockPos(1, 0, 0), BlockPos(-1, 0, 0),
        BlockPos(0, 1, 0), BlockPos(0, -1, 0),
        BlockPos(0, 0, 1), BlockPos(0, 0, -1),
    )

    private fun facesFilled(world: ServerWorld, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int): Boolean {
        // Bottom/Top faces (Y planes)
        var x = minX
        while (x <= maxX) {
            var z = minZ
            while (z <= maxZ) {
                if (!world.getBlockState(BlockPos(x, minY, z)).isOf(Blocks.TINTED_GLASS)) return false
                if (!world.getBlockState(BlockPos(x, maxY, z)).isOf(Blocks.TINTED_GLASS)) return false
                z++
            }
            x++
        }
        // West/East faces (X planes)
        var y = minY
        while (y <= maxY) {
            var z2 = minZ
            while (z2 <= maxZ) {
                if (!world.getBlockState(BlockPos(minX, y, z2)).isOf(Blocks.TINTED_GLASS)) return false
                if (!world.getBlockState(BlockPos(maxX, y, z2)).isOf(Blocks.TINTED_GLASS)) return false
                z2++
            }
            y++
        }
        // North/South faces (Z planes)
        var y2 = minY
        while (y2 <= maxY) {
            var x2 = minX
            while (x2 <= maxX) {
                if (!world.getBlockState(BlockPos(x2, y2, minZ)).isOf(Blocks.TINTED_GLASS)) return false
                if (!world.getBlockState(BlockPos(x2, y2, maxZ)).isOf(Blocks.TINTED_GLASS)) return false
                x2++
            }
            y2++
        }
        return true
    }

    fun detectFromSeed(world: ServerWorld, seed: BlockPos, maxSizePerAxis: Int = 64): GlassCuboid? {
        if (!world.getBlockState(seed).isOf(Blocks.TINTED_GLASS)) return null

        val visited = HashSet<BlockPos>(1024)
        val queue: ArrayDeque<BlockPos> = ArrayDeque()
        queue.add(seed)
        visited.add(seed)

        var minX = seed.x; var minY = seed.y; var minZ = seed.z
        var maxX = seed.x; var maxY = seed.y; var maxZ = seed.z

        val maxVolume = maxSizePerAxis * maxSizePerAxis * maxSizePerAxis

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()

            if (cur.x < minX) minX = cur.x
            if (cur.y < minY) minY = cur.y
            if (cur.z < minZ) minZ = cur.z
            if (cur.x > maxX) maxX = cur.x
            if (cur.y > maxY) maxY = cur.y
            if (cur.z > maxZ) maxZ = cur.z

            val sizeX = maxX - minX + 1
            val sizeY = maxY - minY + 1
            val sizeZ = maxZ - minZ + 1
            if (sizeX > maxSizePerAxis || sizeY > maxSizePerAxis || sizeZ > maxSizePerAxis) {
                return null
            }

            for (d in NEIGHBORS) {
                val next = cur.add(d.x, d.y, d.z)
                if (!visited.contains(next)) {
                    val state = world.getBlockState(next)
                    if (state.isOf(Blocks.TINTED_GLASS)) {
                        visited.add(next)
                        if (visited.size > maxVolume) return null
                        queue.add(next)
                    }
                }
            }
        }

        val sizeX = maxX - minX + 1
        val sizeY = maxY - minY + 1
        val sizeZ = maxZ - minZ + 1
        val volume = sizeX * sizeY * sizeZ

        val isFilled = (visited.size == volume)
        val isShell = if (!isFilled) facesFilled(world, minX, minY, minZ, maxX, maxY, maxZ) else false
        if (!isFilled && !isShell) return null

        val min = BlockPos(minX, minY, minZ)
        val max = BlockPos(maxX, maxY, maxZ)
        return GlassCuboid(world.registryKey, min, max)
    }
}
