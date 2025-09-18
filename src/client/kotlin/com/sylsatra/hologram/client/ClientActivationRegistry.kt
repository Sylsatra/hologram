package com.sylsatra.hologram.client

import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import java.util.concurrent.ConcurrentHashMap
import com.sylsatra.hologram.net.BusUpdatePayload

object ClientActivationRegistry {
    data class Id(val dim: Identifier, val min: BlockPos, val max: BlockPos)
    data class Codes(
        var model: Int,
        var anim: Int,
        var ctrl: Int,
        var scaleQ: Int,
        var offXQ: Int,
        var offYQ: Int,
        var offZQ: Int,
        var tickTime: Long,
    )

    private val map = ConcurrentHashMap<Id, Codes>()

    fun update(payload: BusUpdatePayload) {
        val id = Id(payload.dimId, payload.min, payload.max)
        val existing = map[id]
        if (existing == null || payload.tickTime >= existing.tickTime) {
            map[id] = Codes(
                payload.model,
                payload.anim,
                payload.ctrl,
                payload.scaleQ,
                payload.offXQ,
                payload.offYQ,
                payload.offZQ,
                payload.tickTime,
            )
        }
    }

    fun getAll(): Map<Id, Codes> = map
}
