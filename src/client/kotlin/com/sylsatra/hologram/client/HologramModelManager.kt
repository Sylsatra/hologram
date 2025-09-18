package com.sylsatra.hologram.client

import com.google.gson.Gson
import com.google.gson.JsonObject
import net.minecraft.client.MinecraftClient
import org.slf4j.LoggerFactory
import top.fifthlight.blazerod.animation.AnimationItem
import top.fifthlight.blazerod.animation.AnimationLoader
import top.fifthlight.blazerod.model.ModelInstance
import top.fifthlight.blazerod.model.RenderScene
import top.fifthlight.blazerod.model.TransformId
import top.fifthlight.blazerod.model.ModelFileLoaders
import top.fifthlight.blazerod.model.load.ModelLoader
import top.fifthlight.blazerod.model.renderer.Renderer
import top.fifthlight.blazerod.model.renderer.VertexShaderTransformRenderer
import top.fifthlight.blazerod.model.renderer.CpuTransformRenderer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object HologramModelManager {
    private val gson = Gson()
    private val logger = LoggerFactory.getLogger("hologram")

    private data class ModelCache(
        val future: CompletableFuture<RenderScene?>,
        @Volatile var scene: RenderScene? = null,
        @Volatile var animations: List<AnimationItem> = emptyList(),
        val scaleMultiplier: Float = 1f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val offsetZ: Float = 0f,
        val defaultAnimation: Int? = null,
    )

    private val models = ConcurrentHashMap<Int, ModelCache>()
    private val instances = ConcurrentHashMap<Pair<Int, ClientActivationRegistry.Id>, ModelInstance>()
    private val animTimes = ConcurrentHashMap<Pair<Int, ClientActivationRegistry.Id>, Float>()
    private val animIndices = ConcurrentHashMap<Pair<Int, ClientActivationRegistry.Id>, Int>()

    @Volatile
    private var renderer: Renderer<*, *>? = null

    private fun getRenderer(): Renderer<*, *> {
        var r = renderer
        if (r == null) {
            // TEMP: Always use CPU to stabilize on systems where GPU path can crash the driver.
            // You can switch back to VertexShaderTransformRenderer once stable.
            logger.info("Using CpuTransformRenderer for holograms (temporary global default)")
            r = CpuTransformRenderer.create()
            renderer = r
        }
        return r
    }

    fun rotateRenderer() {
        try {
            renderer?.rotate()
        } catch (_: Throwable) {
        }
    }

    // Detect Iris shader pack usage via reflection without a hard dependency on the Iris API
    private fun isIrisShaderPackActive(): Boolean {
        return try {
            val apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
            val getInstance = apiClass.getMethod("getInstance")
            val api = getInstance.invoke(null)
            val isInUse = apiClass.getMethod("isShaderPackInUse").invoke(api)
            (isInUse as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    private data class Manifest(
        val file: String?,
        val scale: Float? = null,
        val offset: List<Float>? = null,
        val defaultAnimation: Int? = null,
    )

    private fun resolveFolderForCode(code: Int): Path {
        val runDir = MinecraftClient.getInstance().runDirectory.toPath()
        val nnn = String.format("%03d", code.coerceIn(0, 255))
        return runDir.resolve("hologram").resolve("models").resolve(nnn)
    }

    private fun readManifest(dir: Path): Manifest? {
        val file = dir.resolve("manifest.json")
        if (!Files.isRegularFile(file)) return null
        return Files.newBufferedReader(file).use { reader ->
            val json = gson.fromJson(reader, JsonObject::class.java)
            Manifest(
                file = json.get("file")?.asString,
                scale = json.get("scale")?.asFloat,
                offset = json.get("offset")?.asJsonArray?.map { it.asFloat },
                defaultAnimation = json.get("defaultAnimation")?.asInt,
            )
        }
    }

    private val exts = listOf("vrm", "glb", "gltf", "pmx", "pmd")

    private fun pickModelFile(dir: Path, manifest: Manifest?): Path? {
        manifest?.file?.let { f ->
            val p = dir.resolve(f)
            if (Files.isRegularFile(p)) return p
        }
        // pick first supported file
        if (!Files.isDirectory(dir)) return null
        Files.list(dir).use { stream ->
            return stream.filter { p ->
                val name = p.fileName.toString().lowercase()
                exts.any { ext -> name.endsWith(".$ext") }
            }.findFirst().orElse(null)
        }
    }

    fun ensureLoaded(code: Int) {
        if (models.containsKey(code)) return
        val dir = resolveFolderForCode(code)
        val manifest = readManifest(dir)
        val modelPath = pickModelFile(dir, manifest) ?: run {
            logger.warn("No model file found for code=$code in ${dir.toAbsolutePath()} (expected one of $exts)")
            return
        }
        // Kick off async load
        logger.info("Loading model for code=$code from ${modelPath.toAbsolutePath()}")
        val result = ModelFileLoaders.probeAndLoad(modelPath) ?: run {
            logger.warn("No loader available for file ${modelPath.fileName} (code=$code)")
            return
        }
        val model = result.model ?: run {
            logger.warn("Loader failed to provide model for file ${modelPath.fileName} (code=$code)")
            return
        }
        val future = ModelLoader.loadModelAsFuture(model)
        val scaleMul = manifest?.scale ?: 1f
        val off = manifest?.offset ?: emptyList()
        val ox = off.getOrNull(0) ?: 0f
        val oy = off.getOrNull(1) ?: 0f
        val oz = off.getOrNull(2) ?: 0f
        val cache = ModelCache(
            future = future,
            scaleMultiplier = scaleMul,
            offsetX = ox,
            offsetY = oy,
            offsetZ = oz,
            defaultAnimation = manifest?.defaultAnimation,
        )
        models[code] = cache
        future.thenAccept { scene ->
            cache.scene = scene
            scene?.let {
                it.increaseReferenceCount()
                // Load built-in animations from probe result if present
                val builtIn = try {
                    result.animations?.map { a -> AnimationLoader.load(it, a) } ?: emptyList()
                } catch (_: Throwable) { emptyList() }
                // Also load external .vmd animations from the same directory as the model
                val external = try {
                    val collected = mutableListOf<AnimationItem>()
                    if (Files.isDirectory(dir)) {
                        Files.list(dir).use { s ->
                            s.forEach { p ->
                                val name = p.fileName.toString().lowercase()
                                if (name.endsWith(".vmd")) {
                                    try {
                                        val lr = ModelFileLoaders.probeAndLoad(p)
                                        lr?.animations?.forEach { a ->
                                            collected.add(AnimationLoader.load(it, a))
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                    }
                    collected
                } catch (_: Throwable) { emptyList() }
                val all: List<AnimationItem> = builtIn + external
                cache.animations = all
                logger.info("Loaded scene for code=$code: primitives=${it.primitiveComponents.size}, nodes=${it.nodes.size}, animations=${all.size}")
            } ?: logger.warn("Model load returned null scene (code=$code)")
        }
    }

    fun getScene(code: Int): RenderScene? {
        return models[code]?.scene
    }

    fun getRendererAndInstance(
        code: Int,
        id: ClientActivationRegistry.Id,
        centerX: Double,
        centerY: Double,
        centerZ: Double,
        scale: Float,
        paramScale: Float = 1f,
        paramOffX: Float = 0f,
        paramOffY: Float = 0f,
        paramOffZ: Float = 0f,
    ): Pair<Renderer<*, *>, ModelInstance>? {
        val cache = models[code] ?: run {
            ensureLoaded(code)
            return null
        }
        val scene = cache.scene ?: run {
            ensureLoaded(code)
            return null
        }
        // Avoid creating instances when the scene has no primitives yet (GPU not ready),
        // otherwise LocalMatricesBuffer will be zero-sized and crash the renderer.
        if (scene.primitiveComponents.isEmpty()) return null
        // Lazy-rescan: if animations list is still empty, look for external .vmd files now
        if (cache.animations.isEmpty()) {
            try {
                val dir = resolveFolderForCode(code)
                if (Files.isDirectory(dir)) {
                    val collected = mutableListOf<AnimationItem>()
                    Files.list(dir).use { s ->
                        s.forEach { p ->
                            val name = p.fileName.toString().lowercase()
                            if (name.endsWith(".vmd")) {
                                try {
                                    val lr = ModelFileLoaders.probeAndLoad(p)
                                    lr?.animations?.forEach { a ->
                                        collected.add(AnimationLoader.load(scene, a))
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                    if (collected.isNotEmpty()) {
                        cache.animations = collected
                        logger.info("Loaded external animations on demand for code=$code: count=${collected.size}")
                    }
                }
            } catch (_: Throwable) {}
        }
        val key = code to id
        val instance = instances[key]?.let { existing ->
            // If an instance was created earlier when primitive size was zero, rebuild it now.
            val lmSize = existing.modelData.localMatricesBuffer.content.primitiveNodesSize
            if (lmSize == 0 && scene.primitiveComponents.isNotEmpty()) {
                existing.decreaseReferenceCount()
                val rebuilt = ModelInstance(scene)
                rebuilt.increaseReferenceCount()
                instances[key] = rebuilt
                rebuilt
            } else existing
        } ?: run {
            val inst = ModelInstance(scene)
            inst.increaseReferenceCount()
            instances[key] = inst
            inst
        }
        // Do not globally skip rendering here. We will attach skin/morph buffers per-primitive
        // only when they are valid (see HologramWorldRenderer) to avoid zero-sized buffer issues.
        // Apply transform on root
        val rootIndex = scene.rootNode.nodeIndex
        instance.setTransformDecomposed(rootIndex, TransformId.ABSOLUTE) { decomposed ->
            // Set absolute uniform scale (do not multiply each frame)
            decomposed.scale.set(scale * cache.scaleMultiplier * paramScale)
            // Apply manifest offsets plus param offsets (client bus)
            decomposed.translation.set(
                cache.offsetX + paramOffX,
                cache.offsetY + paramOffY,
                cache.offsetZ + paramOffZ,
            )
        }
        instance.updateRenderData()
        return getRenderer() to instance
    }

    // Apply animation for this instance based on anim bus code. dtSec is the frame time delta in seconds.
    fun applyAnimation(code: Int, id: ClientActivationRegistry.Id, animCode: Int, dtSec: Float) {
        val cache = models[code] ?: return
        val scene = cache.scene ?: return
        val anims = cache.animations
        if (anims.isEmpty()) return
        val key = code to id
        val instance = instances[key] ?: return
        val idx = selectAnimationIndex(cache, animCode, anims.size)
        val prevIdx = animIndices[key]
        if (prevIdx == null || prevIdx != idx) {
            animIndices[key] = idx
            animTimes[key] = 0f
            // Reset pose to avoid residual transforms/morph weights from previous clip
            resetInstancePose(scene, instance)
            instance.updateRenderData()
        }
        // idx < 0 => no animation (rest pose)
        if (idx < 0) return
        val duration = anims[idx].duration
        if (duration <= 0f) return
        val t0 = animTimes[key] ?: 0f
        val t = ((t0 + dtSec).let { if (it.isFinite()) it else 0f }) % duration
        animTimes[key] = t
        try {
            anims[idx].apply(instance, t)
            instance.updateRenderData()
        } catch (_: Throwable) {
            // ignore animation application failures per-frame
        }
    }

    // Clears all RELATIVE_ANIMATION transforms and morph weights so a new clip starts from a clean pose.
    private fun resetInstancePose(scene: RenderScene, instance: ModelInstance) {
        // Reset node-relative animations to identity
        for (node in scene.nodes) {
            val index = node.nodeIndex
            instance.setTransformDecomposed(index, TransformId.RELATIVE_ANIMATION) { decomposed ->
                decomposed.translation.set(0f, 0f, 0f)
                decomposed.rotation.identity()
                decomposed.scale.set(1f, 1f, 1f)
            }
        }
        // Reset all morph target weights to 0
        for (comp in scene.primitiveComponents) {
            val prim = comp.primitive
            val groupCount = prim.targets?.let { t ->
                minOf(t.position.targetsCount, t.color.targetsCount, t.texCoord.targetsCount)
            } ?: prim.targetGroups.size
            if (groupCount > 0) {
                val primIndex = comp.primitiveIndex
                var gi = 0
                while (gi < groupCount) {
                    try {
                        instance.setGroupWeight(primIndex, gi, 0f)
                    } catch (_: IndexOutOfBoundsException) {
                        break
                    }
                    gi++
                }
            }
        }
    }

    private fun selectAnimationIndex(cache: ModelCache, animCode: Int, count: Int): Int {
        if (count <= 0) return -1
        if (animCode > 0) {
            // Map 1..N to indices 0..N-1, wrap if out-of-range
            val idx = (animCode - 1) % count
            return if (idx >= 0) idx else (idx + count)
        }
        // animCode == 0 -> no animation (rest pose)
        return -1
    }
}
