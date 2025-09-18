package com.sylsatra.hologram.net

import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos

data class BusUpdatePayload(
    val dimId: Identifier,
    val min: BlockPos,
    val max: BlockPos,
    val model: Int,
    val anim: Int,
    val ctrl: Int,
    val scaleQ: Int,
    val offXQ: Int,
    val offYQ: Int,
    val offZQ: Int,
    val tickTime: Long,
) : CustomPayload {
    override fun getId(): CustomPayload.Id<BusUpdatePayload> = ID

    companion object {
        val ID: CustomPayload.Id<BusUpdatePayload> = CustomPayload.Id(Identifier.of("hologram", "bus_update"))
        val CODEC: PacketCodec<RegistryByteBuf, BusUpdatePayload> = PacketCodec.tuple(
            Identifier.PACKET_CODEC, BusUpdatePayload::dimId,
            BlockPos.PACKET_CODEC, BusUpdatePayload::min,
            BlockPos.PACKET_CODEC, BusUpdatePayload::max,
            PacketCodecs.VAR_INT, BusUpdatePayload::model,
            PacketCodecs.VAR_INT, BusUpdatePayload::anim,
            PacketCodecs.VAR_INT, BusUpdatePayload::ctrl,
            PacketCodecs.VAR_INT, BusUpdatePayload::scaleQ,
            PacketCodecs.VAR_INT, BusUpdatePayload::offXQ,
            PacketCodecs.VAR_INT, BusUpdatePayload::offYQ,
            PacketCodecs.VAR_INT, BusUpdatePayload::offZQ,
            PacketCodecs.VAR_LONG, BusUpdatePayload::tickTime,
        ) { dim, min, max, model, anim, ctrl, scaleQ, offXQ, offYQ, offZQ, tick ->
            BusUpdatePayload(dim, min, max, model, anim, ctrl, scaleQ, offXQ, offYQ, offZQ, tick)
        }
    }
}
