package dev.aod.mcmcp.observation;

import dev.aod.mcmcp.agent.observation.ObservationRecord.ContainerLabel;
import dev.aod.mcmcp.agent.observation.ObservationRecord.Face;
import dev.aod.mcmcp.agent.observation.ObservationValues.BlockPosition;
import dev.aod.mcmcp.agent.observation.ObservationValues.ResourceId;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.Objects;
import java.util.Optional;

/** Resolves only directly attached, non-ambiguous Vanilla container item-frame labels. */
public final class ContainerLabelResolver {
    private ContainerLabelResolver() {
    }

    public static Optional<ContainerLabel> resolve(
            ClientLevel level, Entity entity, String dimension) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(dimension, "dimension");
        if (!(entity instanceof ItemFrame frame)) return Optional.empty();
        String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(frame.getType()).toString();
        if (!"minecraft:item_frame".equals(entityType)
                && !"minecraft:glow_item_frame".equals(entityType)) {
            return Optional.empty();
        }
        ItemStack displayed = frame.getItem();
        if (displayed.isEmpty()) return Optional.empty();
        var itemKey = BuiltInRegistries.ITEM.getKey(displayed.getItem());
        if (itemKey == null || "minecraft:air".equals(itemKey.toString())) {
            return Optional.empty();
        }

        Direction direction = frame.getDirection();
        BlockPos support = frame.getPos().relative(direction.getOpposite());
        if (!level.isLoaded(support)) return Optional.empty();
        BlockState state = level.getBlockState(support);
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.is(Blocks.CHEST)) {
            // A label on one half does not prove a unique policy for the logical double chest.
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return Optional.empty();
        } else if (!state.is(Blocks.BARREL)) {
            return Optional.empty();
        }
        return Optional.of(new ContainerLabel(
                new ResourceId(itemKey.toString()),
                new BlockPosition(
                        new ResourceId(dimension), support.getX(), support.getY(), support.getZ()),
                new ResourceId(block),
                face(direction)));
    }

    private static Face face(Direction direction) {
        return switch (direction) {
            case DOWN -> Face.DOWN;
            case UP -> Face.UP;
            case NORTH -> Face.NORTH;
            case SOUTH -> Face.SOUTH;
            case WEST -> Face.WEST;
            case EAST -> Face.EAST;
        };
    }
}
