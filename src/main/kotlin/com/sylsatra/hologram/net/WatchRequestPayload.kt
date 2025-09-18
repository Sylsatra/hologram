package com.sylsatra.hologram.net

import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos

/**
 * Client -> Server request to begin watching the filled tinted-glass cuboid containing [seed].
 */
data class WatchRequestPayload(
    val seed: BlockPos,
) : CustomPayload {
    override fun getId(): CustomPayload.Id<WatchRequestPayload> = ID

    companion object {
        val ID: CustomPayload.Id<WatchRequestPayload> = CustomPayload.Id(Identifier.of("hologram", "watch_request"))
        val CODEC: PacketCodec<RegistryByteBuf, WatchRequestPayload> = PacketCodec.tuple(
            BlockPos.PACKET_CODEC, WatchRequestPayload::seed,
        ) { seed ->
            WatchRequestPayload(seed)
        }
    }
}
