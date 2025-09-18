package com.sylsatra.hologram.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.block.Blocks
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.command.argument.BlockPosArgumentType
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import com.sylsatra.hologram.structure.ProjectorIndex
import com.sylsatra.hologram.state.HoloActivationRegistry

object HoloCommands {
    fun register() {
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, registryAccess: CommandRegistryAccess, environment ->
            dispatcher.register(buildRoot())
        })
    }

    private fun buildRoot(): LiteralArgumentBuilder<ServerCommandSource> {
        val root = CommandManager.literal("holo")
            // Explicitly allow all players (no OP/cheats required)
            .requires { source -> source.hasPermissionLevel(0) && source.entity is ServerPlayerEntity }
        root.executes { ctx ->
            val source = ctx.source
            source.sendFeedback({ Text.literal("Usage: /holo detect|map|codes|setcodes|watch|unwatch") }, false)
            Command.SINGLE_SUCCESS
        }

        // /holo detect [pos]
        val detect = CommandManager.literal("detect")
            .executes { ctx ->
                val source = ctx.source
                val player = source.playerOrThrow
                val world = source.world as ServerWorld
                val seed = findTargetTintedGlass(player, 20.0)
                    ?: return@executes fail(source, "Look at a tinted glass block within 20 blocks, or use /holo detect <x> <y> <z>.")
                val cuboid = ProjectorIndex.getOrDetect(world, seed)
                    ?: return@executes fail(source, "No filled tinted-glass cuboid found from seed $seed or it exceeds 64 per axis.")
                source.sendFeedback({ Text.literal("Detected cuboid: min=${cuboid.min} max=${cuboid.max} size=${cuboid.sizeX}x${cuboid.sizeY}x${cuboid.sizeZ} volume=${cuboid.volume}") }, false)
                Command.SINGLE_SUCCESS
            }
            .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                .executes { ctx ->
                    val source = ctx.source
                    val world = source.world as ServerWorld
                    val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                    val state = world.getBlockState(pos)
                    if (!state.isOf(Blocks.TINTED_GLASS)) {
                        return@executes fail(source, "The given position is not tinted glass: $pos")
                    }
                    val cuboid = ProjectorIndex.getOrDetect(world, pos)
                        ?: return@executes fail(source, "No filled tinted-glass cuboid found from seed $pos or it exceeds 64 per axis.")
                    source.sendFeedback({ Text.literal("Detected cuboid: min=${cuboid.min} max=${cuboid.max} size=${cuboid.sizeX}x${cuboid.sizeY}x${cuboid.sizeZ} volume=${cuboid.volume}") }, false)
                    Command.SINGLE_SUCCESS
                }
            )
        root.then(detect)

        // /holo map [pos]
        val map = CommandManager.literal("map")
            .executes { ctx ->
                val source = ctx.source
                val player = source.playerOrThrow
                val world = source.world as ServerWorld
                val seed = findTargetTintedGlass(player, 20.0)
                    ?: return@executes fail(source, "Look at a tinted glass block within 20 blocks, or use /holo map <x> <y> <z>.")
                printPortMap(source, world, seed)
            }
            .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                .executes { ctx ->
                    val source = ctx.source
                    val world = source.world as ServerWorld
                    val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                    val state = world.getBlockState(pos)
                    if (!state.isOf(Blocks.TINTED_GLASS)) {
                        return@executes fail(source, "The given position is not tinted glass: $pos")
                    }
                    printPortMap(source, world, pos)
                }
            )
        root.then(map)

        // /holo codes [pos]
        val codes = CommandManager.literal("codes")
            .executes { ctx ->
                val source = ctx.source
                val player = source.playerOrThrow
                val world = source.world as ServerWorld
                val pos = findTargetTintedGlass(player, 20.0)
                    ?: return@executes fail(source, "Look at a tinted glass block within 20 blocks, or use /holo codes <x> <y> <z>.")
                val c = HoloActivationRegistry.getCodes(world, pos)
                    ?: return@executes fail(source, "No watched cuboid matched the given position. Use /holo watch first.")
                source.sendFeedback({ Text.literal("Codes: model=${c.first} anim=${c.second} ctrl=${c.third}") }, false)
                Command.SINGLE_SUCCESS
            }
            .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                .executes { ctx ->
                    val source = ctx.source
                    val world = source.world as ServerWorld
                    val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                    val c = HoloActivationRegistry.getCodes(world, pos)
                        ?: return@executes fail(source, "No watched cuboid matched the given position. Use /holo watch first.")
                    source.sendFeedback({ Text.literal("Codes: model=${c.first} anim=${c.second} ctrl=${c.third}") }, false)
                    Command.SINGLE_SUCCESS
                }
            )
        root.then(codes)

        // /holo setcodes <model> <anim> <ctrl> [pos]
        val setcodes = CommandManager.literal("setcodes")
            .requires { source -> source.hasPermissionLevel(2) }
            .then(CommandManager.argument("model", IntegerArgumentType.integer(0, 255))
                .then(CommandManager.argument("anim", IntegerArgumentType.integer(0, 255))
                    .then(CommandManager.argument("ctrl", IntegerArgumentType.integer(0, 15))
                        .executes { ctx ->
                            val source = ctx.source
                            val player = source.playerOrThrow
                            val world = source.world as ServerWorld
                            val model = IntegerArgumentType.getInteger(ctx, "model")
                            val anim = IntegerArgumentType.getInteger(ctx, "anim")
                            val ctrl = IntegerArgumentType.getInteger(ctx, "ctrl")
                            val pos = findTargetTintedGlass(player, 20.0)
                                ?: return@executes fail(source, "Look at a tinted glass block within 20 blocks, or use /holo setcodes <model> <anim> <ctrl> <x> <y> <z>.")
                            val ok = HoloActivationRegistry.setCodes(world, pos, model, anim, ctrl)
                            if (!ok) return@executes fail(source, "No watched cuboid matched the given position. Use /holo watch first.")
                            source.sendFeedback({ Text.literal("Set codes for cuboid at $pos: model=$model anim=$anim ctrl=$ctrl") }, false)
                            Command.SINGLE_SUCCESS
                        }
                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                            .executes { ctx ->
                                val source = ctx.source
                                val world = source.world as ServerWorld
                                val model = IntegerArgumentType.getInteger(ctx, "model")
                                val anim = IntegerArgumentType.getInteger(ctx, "anim")
                                val ctrl = IntegerArgumentType.getInteger(ctx, "ctrl")
                                val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                                val ok = HoloActivationRegistry.setCodes(world, pos, model, anim, ctrl)
                                if (!ok) return@executes fail(source, "No watched cuboid matched the given position. Use /holo watch first.")
                                source.sendFeedback({ Text.literal("Set codes for cuboid at $pos: model=$model anim=$anim ctrl=$ctrl") }, false)
                                Command.SINGLE_SUCCESS
                            }
                        )
                    )
                )
            )
        root.then(setcodes)

        // /holo watch [pos]
        val watch = CommandManager.literal("watch")
            .executes { ctx ->
                val source = ctx.source
                val player = source.playerOrThrow
                val world = source.world as ServerWorld
                val seed = findTargetTintedGlass(player, 20.0)
                    ?: return@executes fail(source, "Look at a tinted glass block within 20 blocks, or use /holo watch <x> <y> <z>.")
                val entry = HoloActivationRegistry.watch(world, seed)
                    ?: return@executes fail(source, "No filled tinted-glass cuboid found to watch from $seed.")
                source.sendFeedback({ Text.literal("Watching cuboid: min=${entry.cuboid.min} max=${entry.cuboid.max} size=${entry.cuboid.sizeX}x${entry.cuboid.sizeY}x${entry.cuboid.sizeZ} initialPower=${entry.power}") }, false)
                Command.SINGLE_SUCCESS
            }
            .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                .executes { ctx ->
                    val source = ctx.source
                    val world = source.world as ServerWorld
                    val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                    val state = world.getBlockState(pos)
                    if (!state.isOf(Blocks.TINTED_GLASS)) {
                        return@executes fail(source, "The given position is not tinted glass: $pos")
                    }
                    val entry = HoloActivationRegistry.watch(world, pos)
                        ?: return@executes fail(source, "No filled tinted-glass cuboid found to watch from $pos.")
                    source.sendFeedback({ Text.literal("Watching cuboid: min=${entry.cuboid.min} max=${entry.cuboid.max} size=${entry.cuboid.sizeX}x${entry.cuboid.sizeY}x${entry.cuboid.sizeZ} initialPower=${entry.power}") }, false)
                    Command.SINGLE_SUCCESS
                }
            )
        root.then(watch)

        // /holo unwatch [pos]
        val unwatch = CommandManager.literal("unwatch")
            .executes { ctx ->
                val source = ctx.source
                val player = source.playerOrThrow
                val world = source.world as ServerWorld
                val seed = findTargetTintedGlass(player, 20.0)
                    ?: return@executes fail(source, "Look at a tinted glass block within 20 blocks, or use /holo unwatch <x> <y> <z>.")
                val removed = HoloActivationRegistry.unwatch(world, seed)
                if (!removed) return@executes fail(source, "No watched cuboid matched the given position.")
                source.sendFeedback({ Text.literal("Unwatched cuboid at position $seed") }, false)
                Command.SINGLE_SUCCESS
            }
            .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                .executes { ctx ->
                    val source = ctx.source
                    val world = source.world as ServerWorld
                    val pos = BlockPosArgumentType.getBlockPos(ctx, "pos")
                    val removed = HoloActivationRegistry.unwatch(world, pos)
                    if (!removed) return@executes fail(source, "No watched cuboid matched the given position.")
                    source.sendFeedback({ Text.literal("Unwatched cuboid at position $pos") }, false)
                    Command.SINGLE_SUCCESS
                }
            )
        root.then(unwatch)

        return root
    }

    private fun printPortMap(source: ServerCommandSource, world: ServerWorld, seed: BlockPos): Int {
        val cuboid = ProjectorIndex.getOrDetect(world, seed)
            ?: return fail(source, "No filled or shell tinted-glass cuboid found from seed $seed or it exceeds 64 per axis.")
        val min = cuboid.min
        val max = cuboid.max
        val sizeX = cuboid.sizeX
        val sizeY = cuboid.sizeY
        val sizeZ = cuboid.sizeZ

        // Enumerate edge ports in the same order used by the server bus reader
        val ports = enumerateEdgePorts(min, max)
        val modelPorts = ports.take(8)
        val animPorts = ports.drop(8).take(8)
        val ctrlPorts = ports.drop(16).take(4)

        // Param bus ports (middle layer faces' centers)
        val midY = (min.y + max.y) / 2
        val cx = (min.x + max.x) / 2
        val cz = (min.z + max.z) / 2
        val scalePort = BlockPos(cx, midY, min.z) // north center
        val offXPort = BlockPos(max.x, midY, cz)  // east center
        val offYPort = BlockPos(cx, midY, max.z)  // south center
        val offZPort = BlockPos(min.x, midY, cz)  // west center

        source.sendFeedback({
            Text.literal("Holo Port Map: min=$min max=$max size=${sizeX}x${sizeY}x${sizeZ}")
        }, false)

        fun tpText(p: BlockPos): Text {
            val cmd = "/tp @s ${p.x} ${p.y} ${p.z}"
            val hover = Text.literal("Click to TP to ${p.x} ${p.y} ${p.z}")
            val clickEvent = if (source.hasPermissionLevel(2)) ClickEvent.RunCommand(cmd) else ClickEvent.SuggestCommand(cmd)
            val hoverEvent = HoverEvent.ShowText(hover)
            return Text.literal("(${p.x}, ${p.y}, ${p.z})").styled { style ->
                style.withClickEvent(clickEvent)
                    .withHoverEvent(hoverEvent)
            }
        }

        fun line(label: String, p: BlockPos): Text =
            Text.literal("$label: ").append(tpText(p))

        // Model
        if (modelPorts.isNotEmpty()) {
            source.sendFeedback({ Text.literal("Model bits (0..${modelPorts.size - 1}):") }, false)
            modelPorts.forEachIndexed { i, p -> source.sendFeedback({ line("  MODEL[$i]", p) }, false) }
        } else {
            source.sendFeedback({ Text.literal("Model bits: none (edge too small)") }, false)
        }

        // Anim
        if (animPorts.isNotEmpty()) {
            source.sendFeedback({ Text.literal("Anim bits (0..${animPorts.size - 1}):") }, false)
            animPorts.forEachIndexed { i, p -> source.sendFeedback({ line("  ANIM[$i]", p) }, false) }
        } else {
            source.sendFeedback({ Text.literal("Anim bits: none (edge too small)") }, false)
        }

        // Ctrl
        if (ctrlPorts.isNotEmpty()) {
            source.sendFeedback({ Text.literal("Ctrl bits (0..${ctrlPorts.size - 1}):") }, false)
            ctrlPorts.forEachIndexed { i, p -> source.sendFeedback({ line("  CTRL[$i]", p) }, false) }
        } else {
            source.sendFeedback({ Text.literal("Ctrl bits: none (edge too small)") }, false)
        }

        // Params
        source.sendFeedback({ Text.literal("Param bus (analog 0..15; 0=inactive):") }, false)
        source.sendFeedback({ line("  scaleQ (north center)", scalePort) }, false)
        source.sendFeedback({ line("  offXQ (east center)", offXPort) }, false)
        source.sendFeedback({ line("  offYQ (south center)", offYPort) }, false)
        source.sendFeedback({ line("  offZQ (west center)", offZPort) }, false)

        return Command.SINGLE_SUCCESS
    }

    private fun enumerateEdgePorts(min: BlockPos, max: BlockPos): List<BlockPos> {
        val set = linkedSetOf<BlockPos>()
        fun addLineX(y: Int, z: Int) { for (x in min.x..max.x) set.add(BlockPos(x, y, z)) }
        fun addLineZ(y: Int, x: Int) { for (z in min.z..max.z) set.add(BlockPos(x, y, z)) }
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
        // Vertical edges (excluding endpoints)
        addLineY(min.x, min.z) // VNW
        addLineY(max.x, min.z) // VNE
        addLineY(min.x, max.z) // VSW
        addLineY(max.x, max.z) // VSE
        // Limit to bounding box (robustness)
        return set.filter { p -> p.x in min.x..max.x && p.y in min.y..max.y && p.z in min.z..max.z }
    }

    private fun findTargetTintedGlass(player: ServerPlayerEntity, distance: Double): BlockPos? {
        val world = player.world as ServerWorld
        val hit = raycast(player, distance)
        if (hit.type != HitResult.Type.BLOCK) return null
        val pos = (hit as BlockHitResult).blockPos
        val state = world.getBlockState(pos)
        return if (state.isOf(Blocks.TINTED_GLASS)) pos else null
    }

    private fun raycast(player: ServerPlayerEntity, distance: Double): BlockHitResult {
        val world = player.world as ServerWorld
        val eyePos: Vec3d = player.getCameraPosVec(1.0f)
        val look: Vec3d = player.getRotationVec(1.0f)
        val end = eyePos.add(look.x * distance, look.y * distance, look.z * distance)
        val ctx = RaycastContext(
            eyePos,
            end,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            player
        )
        return world.raycast(ctx)
    }

    private fun fail(source: ServerCommandSource, message: String): Int {
        source.sendError(Text.literal(message))
        return 0
    }
}
