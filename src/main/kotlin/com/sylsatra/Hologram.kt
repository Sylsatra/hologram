package com.sylsatra

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory
import com.sylsatra.hologram.command.HoloCommands
import com.sylsatra.hologram.state.HoloActivationRegistry
import com.sylsatra.hologram.net.BusUpdatePayload
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import com.sylsatra.hologram.net.WatchRequestPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.world.ServerWorld

object Hologram : ModInitializer {
    private val logger = LoggerFactory.getLogger("hologram")

	override fun onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.
		logger.info("Hello Fabric world!")

		// Register dev command for detecting filled tinted-glass cuboids
		HoloCommands.register()

		// Initialize watch-based redstone activation registry (server tick hook)
		HoloActivationRegistry.init()

		// Register S2C payload codec
		PayloadTypeRegistry.playS2C().register(BusUpdatePayload.ID, BusUpdatePayload.CODEC)
		// Register C2S payload codec and receiver for client-initiated watch requests
		PayloadTypeRegistry.playC2S().register(WatchRequestPayload.ID, WatchRequestPayload.CODEC)
		ServerPlayNetworking.registerGlobalReceiver(WatchRequestPayload.ID) { payload, context ->
			val player = context.player()
			val server = player.server ?: return@registerGlobalReceiver
			server.execute {
				val world = player.world as ServerWorld
				HoloActivationRegistry.watch(world, payload.seed)
			}
		}
	}
}