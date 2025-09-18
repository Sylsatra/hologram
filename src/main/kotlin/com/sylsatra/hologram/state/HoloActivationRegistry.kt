package com.sylsatra.hologram.state

import com.sylsatra.hologram.structure.GlassCuboid
import com.sylsatra.hologram.structure.ProjectorIndex
import com.sylsatra.hologram.net.BusUpdatePayload
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.world.ServerWorld
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.block.Blocks
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import org.slf4j.LoggerFactory

/**
 * Maintains a set of watched tinted-glass cuboids and computes their redstone power.
 * Phase 3: originally face sampling for power.
 * Phase 4: Universal Edge Bus (UEB) — compute model/anim/ctrl codes from edge ports and broadcast.
 */
object HoloActivationRegistry {
    private val logger = LoggerFactory.getLogger("hologram")
    data class WatchedEntry(
        var cuboid: GlassCuboid,
        var power: Int = 0, // kept for debug
        var modelCode: Int = 0,
        var animCode: Int = 0,
        var ctrlCode: Int = 0,
        var scaleQ: Int = 0,
        var offXQ: Int = 0,
        var offYQ: Int = 0,
        var offZQ: Int = 0,
    )

    private data class WorldState(
        val watched: MutableMap<String, WatchedEntry> = mutableMapOf(),
        var tickCounter: Long = 0L,
    )

    private val worlds = mutableMapOf<String, WorldState>()

    private fun worldKey(world: ServerWorld) = world.registryKey.value.toString()

    private fun keyOf(cuboid: GlassCuboid): String =
        "${cuboid.min.x},${cuboid.min.y},${cuboid.min.z}|${cuboid.max.x},${cuboid.max.y},${cuboid.max.z}"

    fun init() {
        // Tick each world to update watched cuboids
        ServerTickEvents.END_WORLD_TICK.register(ServerTickEvents.EndWorldTick { world ->
            if (world is ServerWorld) tickWorld(world)
        })
    }

    fun watch(world: ServerWorld, seed: BlockPos): WatchedEntry? {
        val cuboid = ProjectorIndex.getOrDetect(world, seed) ?: return null
        val wk = worldKey(world)
        val ws = worlds.getOrPut(wk) { WorldState() }
        val key = keyOf(cuboid)
        val existed = ws.watched.containsKey(key)
        val entry = ws.watched.getOrPut(key) { WatchedEntry(cuboid) }
        if (existed) {
            // Already watched: avoid re-broadcasting and spamming logs
            return entry
        }
        // Compute once on first watch
        entry.power = samplePower(world, cuboid)
        var (m, a, c) = computeCodes(world, cuboid)
        // If powered but codes are zero (e.g., wiring style mismatch), bootstrap a visible default
        if (m == 0 && a == 0 && c == 0 && entry.power > 0) {
            m = 3; a = 0; c = 2
        }
        entry.modelCode = m
        entry.animCode = a
        entry.ctrlCode = c
        // Parameter bus (quantized 0..15); zeros mean "inactive" so client keeps manifest fallback
        val (sQ, xQ, yQ, zQ) = computeParams(world, cuboid)
        entry.scaleQ = sQ; entry.offXQ = xQ; entry.offYQ = yQ; entry.offZQ = zQ
        // Broadcast initial state immediately so clients render without waiting for a change
        ws.tickCounter++
        val payload = BusUpdatePayload(
            world.registryKey.value,
            entry.cuboid.min,
            entry.cuboid.max,
            m, a, c,
            sQ, xQ, yQ, zQ,
            ws.tickCounter,
        )
        for (player in world.players) {
            ServerPlayNetworking.send(player, payload)
        }
        logger.debug("Auto-watch: min=${entry.cuboid.min} max=${entry.cuboid.max} size=${entry.cuboid.sizeX}x${entry.cuboid.sizeY}x${entry.cuboid.sizeZ} initialCodes=m:${m} a:${a} c:${c} params=${sQ},${xQ},${yQ},${zQ}")
        return entry
    }

    fun unwatch(world: ServerWorld, pos: BlockPos): Boolean {
        val wk = worldKey(world)
        val ws = worlds[wk] ?: return false
        val iter = ws.watched.iterator()
        var removed = false
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.value.cuboid.contains(pos)) {
                iter.remove()
                removed = true
            }
        }
        return removed
    }

    fun tickWorld(world: ServerWorld) {
        val wk = worldKey(world)
        val ws = worlds[wk] ?: return
        ws.tickCounter++
        // Auto-watch: every ~10 ticks, if a player is looking at tinted glass, begin watching its cuboid
        if (ws.tickCounter % 10L == 0L) {
            for (player in world.players) {
                val seed = findTargetTintedGlass(world, player, 20.0)
                if (seed != null) {
                    if (getWatchedEntry(world, seed) == null) {
                        watch(world, seed)
                    }
                }
            }
        }
        // Auto-watch (Option B): every ~20 ticks, if a powered tinted-glass block is near a player, begin watching its cuboid
        if (ws.tickCounter % 20L == 5L) {
            for (player in world.players) {
                val seed = findPoweredTintedGlassNear(world, player, radiusXZ = 8, radiusY = 4)
                if (seed != null) {
                    if (getWatchedEntry(world, seed) == null) {
                        watch(world, seed)
                    }
                }
            }
        }
        // Fallback: every ~40 ticks, watch any valid filled tinted-glass cuboid near players (no power requirement)
        if (ws.tickCounter % 40L == 15L) {
            for (player in world.players) {
                val seed = findAnyTintedGlassNear(world, player, radiusXZ = 8, radiusY = 4)
                if (seed != null) {
                    if (getWatchedEntry(world, seed) == null) {
                        watch(world, seed)
                    }
                }
            }
        }
        // Revalidate cuboid bounds occasionally to avoid stale dimensions when glass changes
        if (ws.tickCounter % 20L == 10L) {
            for ((_, entry) in ws.watched) {
                val seed = entry.cuboid.min
                // Invalidate cache containing the seed then detect fresh bounds
                ProjectorIndex.invalidateAt(world, seed)
                val fresh = ProjectorIndex.getOrDetect(world, seed)
                if (fresh != null && (fresh.min != entry.cuboid.min || fresh.max != entry.cuboid.max)) {
                    entry.cuboid = fresh
                    // Force an immediate broadcast of codes next section
                    entry.modelCode = -1
                }
            }
        }
        // Update each watched cuboid's power
        for ((_, entry) in ws.watched) {
            entry.power = samplePower(world, entry.cuboid)
            var (m, a, c) = computeCodes(world, entry.cuboid)
            // Face-bus compatibility: if UEB edge bus yields 0 but there is power, try face ports (bottom/top/vertical centers)
            if (m == 0 && a == 0 && c == 0 && entry.power > 0) {
                val fallback = computeCodesFaceBus(world, entry.cuboid)
                if (fallback.first != 0 || fallback.second != 0 || fallback.third != 0) {
                    m = fallback.first; a = fallback.second; c = fallback.third
                }
            }
            val (sQ, xQ, yQ, zQ) = computeParams(world, entry.cuboid)
            if (
                m != entry.modelCode || a != entry.animCode || c != entry.ctrlCode ||
                sQ != entry.scaleQ || xQ != entry.offXQ || yQ != entry.offYQ || zQ != entry.offZQ
            ) {
                entry.modelCode = m
                entry.animCode = a
                entry.ctrlCode = c
                entry.scaleQ = sQ; entry.offXQ = xQ; entry.offYQ = yQ; entry.offZQ = zQ
                val payload = BusUpdatePayload(
                    world.registryKey.value,
                    entry.cuboid.min,
                    entry.cuboid.max,
                    m, a, c,
                    sQ, xQ, yQ, zQ,
                    ws.tickCounter,
                )
                for (player in world.players) {
                    ServerPlayNetworking.send(player, payload)
                }
            }
        }
    }

    private fun computeParams(world: ServerWorld, cuboid: GlassCuboid): QuadrupleInt {
        val min = cuboid.min
        val max = cuboid.max
        val midY = (min.y + max.y) / 2
        val cx = (min.x + max.x) / 2
        val cz = (min.z + max.z) / 2
        fun q(pos: BlockPos) = world.getReceivedRedstonePower(pos).coerceIn(0, 15)
        val scaleQ = q(BlockPos(cx, midY, min.z)) // north center
        val offXQ = q(BlockPos(max.x, midY, cz))  // east center
        val offYQ = q(BlockPos(cx, midY, max.z))  // south center
        val offZQ = q(BlockPos(min.x, midY, cz))  // west center
        return QuadrupleInt(scaleQ, offXQ, offYQ, offZQ)
    }

    private data class QuadrupleInt(val a: Int, val b: Int, val c: Int, val d: Int)

    private fun computeCodesFaceBus(world: ServerWorld, cuboid: GlassCuboid): Triple<Int, Int, Int> {
        val min = cuboid.min
        val max = cuboid.max
        val midY = (min.y + max.y) / 2
        val cx = (min.x + max.x) / 2
        val cz = (min.z + max.z) / 2

        fun bit(pos: BlockPos) = if (world.getReceivedRedstonePower(pos) >= 1) 1 else 0

        // Model (bottom face, 8 bits): NW row, SW row, centers on W/E rows
        val modelBits = intArrayOf(
            bit(BlockPos(min.x, min.y, min.z)), // 0: BNW corner
            bit(BlockPos(cx,  min.y, min.z)),   // 1: BN center
            bit(BlockPos(max.x, min.y, min.z)), // 2: BNE corner
            bit(BlockPos(min.x, min.y, cz)),    // 3: BW center
            bit(BlockPos(max.x, min.y, cz)),    // 4: BE center
            bit(BlockPos(min.x, min.y, max.z)), // 5: BSW corner
            bit(BlockPos(cx,  min.y, max.z)),   // 6: BS center
            bit(BlockPos(max.x, min.y, max.z)), // 7: BSE corner
        )
        var model = 0
        for (i in 0 until 8) model = model or (modelBits.getOrElse(i) { 0 } shl i)

        // Anim (top face, 8 bits) mirrors bottom face
        val animBits = intArrayOf(
            bit(BlockPos(min.x, max.y, min.z)),
            bit(BlockPos(cx,  max.y, min.z)),
            bit(BlockPos(max.x, max.y, min.z)),
            bit(BlockPos(min.x, max.y, cz)),
            bit(BlockPos(max.x, max.y, cz)),
            bit(BlockPos(min.x, max.y, max.z)),
            bit(BlockPos(cx,  max.y, max.z)),
            bit(BlockPos(max.x, max.y, max.z)),
        )
        var anim = 0
        for (i in 0 until 8) anim = anim or (animBits.getOrElse(i) { 0 } shl i)

        // Ctrl (vertical centers at middle Y, 4 bits): NW, NE, SW, SE
        val ctrlBits = intArrayOf(
            bit(BlockPos(min.x, midY, min.z)),
            bit(BlockPos(max.x, midY, min.z)),
            bit(BlockPos(min.x, midY, max.z)),
            bit(BlockPos(max.x, midY, max.z)),
        )
        var ctrl = 0
        for (i in 0 until 4) ctrl = ctrl or (ctrlBits.getOrElse(i) { 0 } shl i)

        return Triple(model, anim, ctrl)
    }

    private fun findTargetTintedGlass(world: ServerWorld, player: ServerPlayerEntity, distance: Double): BlockPos? {
        val eyePos: Vec3d = player.getCameraPosVec(1.0f)
        val look: Vec3d = player.getRotationVec(1.0f)
        val end = eyePos.add(look.x * distance, look.y * distance, look.z * distance)
        val ctx = RaycastContext(
            eyePos,
            end,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            player,
        )
        val hit = world.raycast(ctx)
        if (hit.type != net.minecraft.util.hit.HitResult.Type.BLOCK) return null
        val pos = (hit as net.minecraft.util.hit.BlockHitResult).blockPos
        val state = world.getBlockState(pos)
        return if (state.isOf(Blocks.TINTED_GLASS)) pos else null
    }

    private fun findPoweredTintedGlassNear(world: ServerWorld, player: ServerPlayerEntity, radiusXZ: Int, radiusY: Int): BlockPos? {
        val center = player.blockPos
        val minX = center.x - radiusXZ
        val maxX = center.x + radiusXZ
        val minY = center.y - radiusY
        val maxY = center.y + radiusY
        val minZ = center.z - radiusXZ
        val maxZ = center.z + radiusXZ
        var y = minY
        while (y <= maxY) {
            var x = minX
            while (x <= maxX) {
                var z = minZ
                while (z <= maxZ) {
                    val pos = BlockPos(x, y, z)
                    val state = world.getBlockState(pos)
                    if (state.isOf(Blocks.TINTED_GLASS)) {
                        val power = world.getReceivedRedstonePower(pos)
                        if (power >= 1) {
                            return pos
                        }
                    }
                    z++
                }
                x++
            }
            y++
        }
        return null
    }

    private fun findAnyTintedGlassNear(world: ServerWorld, player: ServerPlayerEntity, radiusXZ: Int, radiusY: Int): BlockPos? {
        val center = player.blockPos
        val minX = center.x - radiusXZ
        val maxX = center.x + radiusXZ
        val minY = center.y - radiusY
        val maxY = center.y + radiusY
        val minZ = center.z - radiusXZ
        val maxZ = center.z + radiusXZ
        var y = minY
        while (y <= maxY) {
            var x = minX
            while (x <= maxX) {
                var z = minZ
                while (z <= maxZ) {
                    val pos = BlockPos(x, y, z)
                    val state = world.getBlockState(pos)
                    if (state.isOf(Blocks.TINTED_GLASS)) {
                        return pos
                    }
                    z += 2 // stride to reduce cost
                }
                x += 2
            }
            y += 2
        }
        return null
    }

    private fun samplePower(world: ServerWorld, cuboid: GlassCuboid): Int {
        // Sample faces with a stride to bound cost on large cuboids
        val strideX = strideFor(cuboid.sizeX)
        val strideY = strideFor(cuboid.sizeY)
        val strideZ = strideFor(cuboid.sizeZ)
        var maxPower = 0

        // +X and -X faces
        val xMin = cuboid.min.x
        val xMax = cuboid.max.x
        val yMin = cuboid.min.y
        val yMax = cuboid.max.y
        val zMin = cuboid.min.z
        val zMax = cuboid.max.z

        var y = yMin
        while (y <= yMax) {
            var z = zMin
            while (z <= zMax) {
                maxPower = maxOf(maxPower, world.getReceivedRedstonePower(BlockPos(xMin, y, z)))
                if (maxPower == 15) return 15
                maxPower = maxOf(maxPower, world.getReceivedRedstonePower(BlockPos(xMax, y, z)))
                if (maxPower == 15) return 15
                z += strideZ
            }
            y += strideY
        }

        // +Y and -Y faces
        var x = xMin
        while (x <= xMax) {
            var z2 = zMin
            while (z2 <= zMax) {
                maxPower = maxOf(maxPower, world.getReceivedRedstonePower(BlockPos(x, yMin, z2)))
                if (maxPower == 15) return 15
                maxPower = maxOf(maxPower, world.getReceivedRedstonePower(BlockPos(x, yMax, z2)))
                if (maxPower == 15) return 15
                z2 += strideZ
            }
            x += strideX
        }

        // +Z and -Z faces
        var y2 = yMin
        while (y2 <= yMax) {
            var x2 = xMin
            while (x2 <= xMax) {
                maxPower = maxOf(maxPower, world.getReceivedRedstonePower(BlockPos(x2, y2, zMin)))
                if (maxPower == 15) return 15
                maxPower = maxOf(maxPower, world.getReceivedRedstonePower(BlockPos(x2, y2, zMax)))
                if (maxPower == 15) return 15
                x2 += strideX
            }
            y2 += strideY
        }

        return maxPower
    }

    private fun strideFor(size: Int): Int {
        // Target ~32 samples per axis; stride at least 1
        val target = 32
        return if (size <= target) 1 else (size + target - 1) / target
    }

    // For Phase 4 usage
    fun currentPower(world: ServerWorld, cuboid: GlassCuboid): Int {
        val wk = worldKey(world)
        val ws = worlds[wk] ?: return 0
        val key = keyOf(cuboid)
        return ws.watched[key]?.power ?: 0
    }

    // Universal Edge Bus computations
    private fun computeCodes(world: ServerWorld, cuboid: GlassCuboid): Triple<Int, Int, Int> {
        val ports = enumerateEdgePorts(cuboid)
        // Read all port strengths once
        val strengths = IntArray(ports.size) { i -> world.getReceivedRedstonePower(ports[i]) }

        var index = 0
        val (model, idxAfterModel) = readBus(strengths, index, 8)
        index = idxAfterModel
        val (anim, idxAfterAnim) = readBus(strengths, index, 8)
        index = idxAfterAnim
        val (ctrl, _) = readBus(strengths, index, 4)

        return Triple(model, anim, ctrl)
    }

    // Reads up to maxBits. If only one port available for this bus, use analog nibble. If zero, return 0.
    private fun readBus(strengths: IntArray, start: Int, maxBits: Int): Pair<Int, Int> {
        val remaining = strengths.size - start
        if (remaining <= 0) return 0 to start
        if (remaining == 1) {
            // Hex fallback
            val v = strengths[start].coerceIn(0, 15)
            return v to (start + 1)
        }
        val bits = minOf(maxBits, remaining)
        var value = 0
        for (i in 0 until bits) {
            val bit = if (strengths[start + i] >= 1) 1 else 0
            value = value or (bit shl i)
        }
        return value to (start + bits)
    }

    private fun enumerateEdgePorts(cuboid: GlassCuboid): List<BlockPos> {
        val set = LinkedHashSet<BlockPos>()
        val min = cuboid.min
        val max = cuboid.max

        fun addLineX(y: Int, z: Int) {
            for (x in min.x..max.x) set.add(BlockPos(x, y, z))
        }
        fun addLineZ(y: Int, x: Int) {
            for (z in min.z..max.z) set.add(BlockPos(x, y, z))
        }
        fun addLineY(x: Int, z: Int) {
            if (max.y - min.y >= 2) {
                for (y in (min.y + 1)..(max.y - 1)) set.add(BlockPos(x, y, z))
            }
        }

        // Bottom edges
        addLineX(min.y, min.z) // BN
        addLineX(min.y, max.z) // BS
        addLineZ(min.y, min.x) // BW
        addLineZ(min.y, max.x) // BE

        // Top edges
        addLineX(max.y, min.z) // TN
        addLineX(max.y, max.z) // TS
        addLineZ(max.y, min.x) // TW
        addLineZ(max.y, max.x) // TE

        // Vertical edges (excluding endpoints to avoid duplicates)
        addLineY(min.x, min.z) // VNW
        addLineY(max.x, min.z) // VNE
        addLineY(min.x, max.z) // VSW
        addLineY(max.x, max.z) // VSE

        // Only keep positions that are actually tinted glass (robustness if structure changed)
        return set.filter { pos -> pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z }
    }

    // --- Debug helpers ---
    fun getWatchedEntry(world: ServerWorld, pos: BlockPos): WatchedEntry? {
        val wk = worldKey(world)
        val ws = worlds[wk] ?: return null
        return ws.watched.values.firstOrNull { it.cuboid.contains(pos) }
    }

    fun getCodes(world: ServerWorld, pos: BlockPos): Triple<Int, Int, Int>? {
        val entry = getWatchedEntry(world, pos) ?: return null
        return Triple(entry.modelCode, entry.animCode, entry.ctrlCode)
    }

    fun setCodes(world: ServerWorld, pos: BlockPos, model: Int, anim: Int, ctrl: Int): Boolean {
        val wk = worldKey(world)
        val ws = worlds[wk] ?: return false
        val entry = ws.watched.values.firstOrNull { it.cuboid.contains(pos) } ?: return false
        entry.modelCode = model.coerceIn(0, 255)
        entry.animCode = anim.coerceIn(0, 255)
        entry.ctrlCode = ctrl.coerceIn(0, 15)
        // Broadcast immediately
        ws.tickCounter++
        val payload = BusUpdatePayload(
            world.registryKey.value,
            entry.cuboid.min,
            entry.cuboid.max,
            entry.modelCode,
            entry.animCode,
            entry.ctrlCode,
            entry.scaleQ,
            entry.offXQ,
            entry.offYQ,
            entry.offZQ,
            ws.tickCounter,
        )
        for (player in world.players) {
            ServerPlayNetworking.send(player, payload)
        }
        return true
    }
}
