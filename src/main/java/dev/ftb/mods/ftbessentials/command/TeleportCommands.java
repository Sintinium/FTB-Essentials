package dev.ftb.mods.ftbessentials.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import dev.ftb.mods.ftbessentials.FTBEssentials;
import dev.ftb.mods.ftbessentials.config.FTBEConfig;
import dev.ftb.mods.ftbessentials.util.FTBEPlayerData;
import dev.ftb.mods.ftbessentials.util.RTPEvent;
import dev.ftb.mods.ftbessentials.util.TeleportPos;
import me.shedaniel.architectury.hooks.TagHooks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;

import java.util.Optional;

/**
 * @author LatvianModder
 */
public class TeleportCommands {
	public static final Tag<Block> IGNORE_RTP = TagHooks.getBlockOptional(new ResourceLocation(FTBEssentials.MOD_ID, "ignore_rtp"));

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("back")
				.requires(FTBEConfig.BACK)
				.executes(context -> back(context.getSource().getPlayerOrException()))
		);

		dispatcher.register(Commands.literal("spawn")
				.requires(FTBEConfig.SPAWN)
				.executes(context -> spawn(context.getSource().getPlayerOrException()))
		);

		dispatcher.register(Commands.literal("rtp")
				.requires(FTBEConfig.RTP)
				.executes(context -> rtp(context.getSource().getPlayerOrException()))
		);

		dispatcher.register(Commands.literal("teleport_last")
				.requires(FTBEConfig.TPL.enabledAndOp())
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(context -> tpLast(context.getSource().getPlayerOrException(), GameProfileArgument.getGameProfiles(context, "player").iterator().next()))
				)
		);

		dispatcher.register(Commands.literal("tpx")
				.requires(FTBEConfig.TPX.enabledAndOp())
				.then(Commands.argument("dimension", DimensionArgument.dimension())
						.executes(context -> tpx(context.getSource().getPlayerOrException(), DimensionArgument.getDimension(context, "dimension")))
				)
		);
	}

	public static int back(ServerPlayer player) {
		FTBEPlayerData data = FTBEPlayerData.get(player);

		if (data.teleportHistory.isEmpty()) {
			player.displayClientMessage(new TextComponent("Teleportation history is empty!"), false);
			return 0;
		}

		if (data.backTeleporter.teleport(player, serverPlayerEntity -> data.teleportHistory.getLast()).runCommand(player) != 0) {
			data.teleportHistory.removeLast();
			data.save();
			return 1;
		}

		return 0;
	}

	public static int spawn(ServerPlayer player) {
		FTBEPlayerData data = FTBEPlayerData.get(player);
		ServerLevel w = player.server.getLevel(Level.OVERWORLD);

		if (w == null) {
			return 0;
		}

		return data.spawnTeleporter.teleport(player, p -> new TeleportPos(w, w.getSharedSpawnPos())).runCommand(player);
	}

	public static int rtp(ServerPlayer player) {
		FTBEPlayerData data = FTBEPlayerData.get(player);
		return data.rtpTeleporter.teleport(player, p -> {
			p.displayClientMessage(new TextComponent("Looking for random location..."), false);
			return findBlockPos(player.getLevel(), p, 1);
		}).runCommand(player);
	}

	private static TeleportPos findBlockPos(ServerLevel world, ServerPlayer player, int attempt) {
		if (attempt > FTBEConfig.RTP_MAX_TRIES.get()) {
			player.displayClientMessage(new TextComponent("Could not find a valid location to teleport to!"), false);
			return new TeleportPos(player);
		}

		double dist = FTBEConfig.RTP_MIN_DISTANCE.get() + world.random.nextDouble() * (FTBEConfig.RTP_MAX_DISTANCE.get() - FTBEConfig.RTP_MIN_DISTANCE.get());
		double angle = world.random.nextDouble() * Math.PI * 2D;

		int x = Mth.floor(Math.cos(angle) * dist);
		int y = 256;
		int z = Mth.floor(Math.sin(angle) * dist);
		BlockPos currentPos = new BlockPos(x, y, z);

		WorldBorder border = world.getWorldBorder();

		if (!border.isWithinBounds(currentPos)) {
			return findBlockPos(world, player, attempt + 1);
		}

		Optional<ResourceKey<Biome>> biomeKey = world.getBiomeName(currentPos);

		if (biomeKey.isPresent() && biomeKey.get().location().getPath().contains("ocean")) {
			return findBlockPos(world, player, attempt + 1);
		}

		// TODO: FTB Chunks will listen to RTPEvent and cancel it if position is inside a claimed chunk
		if (MinecraftForge.EVENT_BUS.post(new RTPEvent(world, player, currentPos, attempt))) {
			return findBlockPos(world, player, attempt + 1);
		}

		world.getChunk(currentPos.getX() >> 4, currentPos.getZ() >> 4, ChunkStatus.HEIGHTMAPS);
		BlockPos hmPos = world.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, currentPos);

		if (hmPos.getY() > 0) {
			if (hmPos.getY() >= world.getHeight()) { // broken heightmap (nether, other mod dimensions)
				for (BlockPos newPos : BlockPos.spiralAround(new BlockPos(hmPos.getX(), world.getSeaLevel(), hmPos.getY()), 16, Direction.EAST, Direction.SOUTH)) {
					BlockState bs = world.getBlockState(newPos);

					if (bs.getMaterial().isSolidBlocking() && !bs.is(IGNORE_RTP) && world.isEmptyBlock(newPos.above(1)) && world.isEmptyBlock(newPos.above(2)) && world.isEmptyBlock(newPos.above(3))) {
						player.displayClientMessage(new TextComponent(String.format("Found good location after %d " + (attempt == 1 ? "attempt" : "attempts") + " @ [x %d, z %d]", attempt, newPos.getX(), newPos.getZ())), false);
						return new TeleportPos(world.dimension(), newPos.above());
					}
				}
			} else {
				player.displayClientMessage(new TextComponent(String.format("Found good location after %d " + (attempt == 1 ? "attempt" : "attempts") + " @ [x %d, z %d]", attempt, hmPos.getX(), hmPos.getZ())), false);
				return new TeleportPos(world.dimension(), hmPos.above());
			}
		}

		return findBlockPos(world, player, attempt + 1);
	}

	public static int tpLast(ServerPlayer player, GameProfile to) {
		ServerPlayer p = player.server.getPlayerList().getPlayer(to.getId());

		if (p != null) {
			new TeleportPos(p).teleport(player);
			return 1;
		}

		FTBEPlayerData dataTo = FTBEPlayerData.get(to);

		if (dataTo == null) {
			return 0;
		}

		dataTo.lastSeen.teleport(player);
		return 1;
	}

	public static int tpx(ServerPlayer player, ServerLevel to) {
		player.teleportTo(to, player.getX(), player.getY(), player.getZ(), player.yRot, player.xRot);
		return 1;
	}
}
