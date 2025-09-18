package com.sylsatra

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import com.sylsatra.hologram.net.BusUpdatePayload
import com.sylsatra.hologram.net.WatchRequestPayload
import com.sylsatra.hologram.client.ClientActivationRegistry
import com.sylsatra.hologram.client.HologramWorldRenderer
import net.minecraft.client.MinecraftClient
import net.minecraft.block.Blocks
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext

object HologramClient : ClientModInitializer {
	override fun onInitializeClient() {
		// Ensure BlazeRod model loaders are discovered. This is safe to call multiple times.
		val logger = LoggerFactory.getLogger("hologram")
		try {
			top.fifthlight.blazerod.model.ModelFileLoaders.initialize()
			val loaders = top.fifthlight.blazerod.model.ModelFileLoaders.loaders
			logger.info("BlazeRod model loaders active: ${loaders.map { it.javaClass.simpleName }}")

			// If blazerod is provided as a plain library jar (no fabric.mod.json),
			// its ClientModInitializer won't be invoked by Fabric Loader. In that case,
			// invoke it manually to set up render events and buffers.
			val hasBrRenderMod = FabricLoader.getInstance().isModLoaded("blazerod-render")
			if (!hasBrRenderMod) {
				logger.info("blazerod-render not detected as a Fabric mod; attempting reflection init")
				try {
					val clazz = Class.forName("top.fifthlight.blazerod.BlazeRod")
					val instance = clazz.getField("INSTANCE").get(null)
					val method = clazz.getMethod("onInitializeClient")
					method.invoke(instance)
					logger.info("BlazeRod initialized via reflection")
				} catch (e: Throwable) {
					logger.info("BlazeRod class not present; skipping manual init")
				}
			}
		} catch (t: Throwable) {
			logger.warn("BlazeRod not available or failed to initialize model loaders", t)
		}

		// Client networking receiver for bus updates (typed payload)
		ClientPlayNetworking.registerGlobalReceiver(BusUpdatePayload.ID) { payload, context ->
			val client = context.client()
			client.execute {
				ClientActivationRegistry.update(payload)
			}
		}

		// Register world renderer hook
		HologramWorldRenderer.register()

		// Client-side auto-watch: when looking at tinted glass, request server to watch its cuboid
		var tickCounter = 0
		ClientTickEvents.END_CLIENT_TICK.register { client: MinecraftClient ->
			tickCounter++
			if (tickCounter % 20 == 0) {
				val player = client.player
				val world = client.world
				if (player != null && world != null) {
					val camPos: Vec3d = player.getCameraPosVec(1.0f)
					val look: Vec3d = player.getRotationVec(1.0f)
					val end = camPos.add(look.x * 20.0, look.y * 20.0, look.z * 20.0)
					val ctx = RaycastContext(
						camPos,
						end,
						RaycastContext.ShapeType.OUTLINE,
						RaycastContext.FluidHandling.NONE,
						player
					)
					val hit = world.raycast(ctx)
					if (hit.type == HitResult.Type.BLOCK) {
						val pos = (hit as BlockHitResult).blockPos
						val state = world.getBlockState(pos)
						if (state.isOf(Blocks.TINTED_GLASS) && ClientPlayNetworking.canSend(WatchRequestPayload.ID)) {
							ClientPlayNetworking.send(WatchRequestPayload(pos))
						}
					}
				}
			}
		}
	}
}