package com.sylsatra.hologram.client

import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.WorldRenderer
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory
import top.fifthlight.blazerod.model.renderer.CpuTransformRenderer
import org.lwjgl.opengl.GL11C
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

object HologramWorldRenderer {
    private val logger = LoggerFactory.getLogger("hologram")
    // Reuse CPU renderer to avoid per-primitive creation
    private val cpuRenderer by lazy { CpuTransformRenderer.create() }
    // Remember holograms that must always use CPU due to GPU failure
    private val gpuFallback: MutableSet<Pair<Int, ClientActivationRegistry.Id>> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val fallbackWarned: MutableSet<Pair<Int, ClientActivationRegistry.Id>> = mutableSetOf()
    private var lastFrameNs: Long = System.nanoTime()

    fun register() {
        // Render after entities but before translucent blocks so depth works correctly and
        // the hologram remains visible through tinted glass that draws later.
        WorldRenderEvents.AFTER_ENTITIES.register(WorldRenderEvents.AfterEntities { context ->
            val client = MinecraftClient.getInstance()
            val world = client.world ?: return@AfterEntities
            val dimId = world.registryKey.value
            val now = System.nanoTime()
            val dtSec = ((now - lastFrameNs).coerceAtLeast(0L) / 1_000_000_000.0f)
            lastFrameNs = now

            val entries = ClientActivationRegistry.getAll()
            if (entries.isEmpty()) return@AfterEntities

            val matrices = context.matrixStack() ?: return@AfterEntities
            val frameBuffer = client.framebuffer
            val colorView = (RenderSystem.outputColorTextureOverride ?: frameBuffer.colorAttachmentView)
                ?: return@AfterEntities
            val depthViewBase = if (frameBuffer.useDepthAttachment)
                (RenderSystem.outputDepthTextureOverride ?: frameBuffer.depthAttachmentView)
            else null

            for ((id, codes) in entries) {
                if (id.dim != dimId) continue

                val minPos = id.min
                val maxPos = id.max
                val sizeX = maxPos.x - minPos.x + 1
                val sizeY = maxPos.y - minPos.y + 1
                val sizeZ = maxPos.z - minPos.z + 1

                if (codes.model <= 0) continue

                val nnnCode = codes.model.coerceIn(0, 255)

                val centerX = (minPos.x + maxPos.x + 1) / 2.0
                val centerY = (minPos.y + maxPos.y + 1) / 2.0
                val centerZ = (minPos.z + maxPos.z + 1) / 2.0

                // Heuristic scale: fit to the smallest axis and leave a margin
                val fit = min(min(sizeX, sizeY), sizeZ)
                val scale = (fit.toFloat() * 0.9f).coerceAtLeast(0.1f)

                // Decode parameter bus (0 => inactive; manifest fallback)
                val anyParam = (codes.scaleQ != 0 || codes.offXQ != 0 || codes.offYQ != 0 || codes.offZQ != 0)
                val pScale = if (codes.scaleQ > 0) (0.25f + (codes.scaleQ.coerceIn(1, 15) / 15.0f) * 1.75f) else 1.0f
                fun offFromQ(q: Int): Float = if (q > 0) ((q.coerceIn(1, 15) - 8) / 8.0f) else 0f
                val pOffX = offFromQ(codes.offXQ)
                val pOffY = offFromQ(codes.offYQ)
                val pOffZ = offFromQ(codes.offZQ)

                val pair = HologramModelManager.getRendererAndInstance(
                    code = nnnCode,
                    id = id,
                    centerX = centerX,
                    centerY = centerY,
                    centerZ = centerZ,
                    scale = scale,
                    paramScale = pScale,
                    paramOffX = pOffX,
                    paramOffY = pOffY,
                    paramOffZ = pOffZ,
                ) ?: continue

                val (renderer, instance) = pair
                val scene = instance.scene
                // Some models (or failed loads) may produce a scene with no primitives. Skip to avoid zero-sized buffers.
                if (scene.primitiveComponents.isEmpty()) continue
                // Translate to the cuboid center in camera space (match BlazeRod example/BEs)
                val camPos = context.camera().pos
                matrices.push()
                matrices.translate(
                    centerX - camPos.x,
                    centerY - camPos.y,
                    centerZ - camPos.z,
                )
                // Log placement for diagnostics (debug only)
                logger.debug("Render hologram code=$nnnCode at center=($centerX,$centerY,$centerZ) scale=$scale params=${if (anyParam) "(${pScale}, ${pOffX}, ${pOffY}, ${pOffZ})" else "(manifest)"} primitives=${scene.primitiveComponents.size}")
                // Apply animation based on anim bus before creating the render task
                HologramModelManager.applyAnimation(nnnCode, id, codes.anim, dtSec)
                // Lighting: ctrl==3 -> use world light at center; otherwise fullbright
                val ctrl3 = (codes.ctrl and 0x3) == 0x3
                val light = if (ctrl3) {
                    WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(centerX, centerY, centerZ))
                } else {
                    0x00F000F0.toInt()
                }
                val task = instance.createRenderTask(matrices.peek().positionMatrix, light)
                // Depth: ctrl==3 uses world light with proper depth; otherwise ctrl bit0 can ignore depth
                val ignoreDepth = ((codes.ctrl and 0x1) == 1) && !ctrl3
                val depthView = if (ignoreDepth) null else depthViewBase
                // Guard: if local matrices buffer is zero-sized, skip this frame
                val lmSize = task.localMatricesBuffer.content.primitiveNodesSize
                if (lmSize <= 0) {
                    logger.warn("Skipping scene render (code=$nnnCode, dim=$dimId, id=$id): localMatrices primitiveNodesSize == 0")
                    matrices.pop()
                    continue
                }
                val noCull = (codes.ctrl and 0x4) != 0
                if (noCull) {
                    GL11C.glDisable(GL11C.GL_CULL_FACE)
                }
                try {
                    renderer.render(colorView, depthView, task, scene)
                } finally {
                    if (noCull) {
                        GL11C.glEnable(GL11C.GL_CULL_FACE)
                    }
                    // Important: release GPU-backed task buffers to avoid leaks and repeated pool resizes
                    try { task.release() } catch (_: Throwable) {}
                    matrices.pop()
                }
            }
            // Rotate internal GPU/CPU pools once per frame to release fences and avoid native crashes
            HologramModelManager.rotateRenderer()
        })
    }
}
