package eu.pb4.polymer.core.impl.networking;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItemGroupUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.core.impl.PolymerImpl;
import eu.pb4.polymer.core.impl.PolymerImplUtils;
import eu.pb4.polymer.core.impl.compat.ServerTranslationUtils;
import eu.pb4.polymer.core.impl.interfaces.PolymerBlockPosStorage;
import eu.pb4.polymer.core.impl.interfaces.PolymerIdList;
import eu.pb4.polymer.core.impl.interfaces.PolymerNetworkHandlerExtension;
import eu.pb4.polymer.core.impl.interfaces.RegistryExtension;
import eu.pb4.polymer.core.impl.networking.packets.*;
import eu.pb4.polymer.networking.api.ServerPacketWriter;
import eu.pb4.polymer.networking.api.PolymerServerNetworking;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemGroup;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.IndexedIterable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import static eu.pb4.polymer.networking.api.PolymerServerNetworking.buf;

@ApiStatus.Internal
public class PolymerServerProtocol {
    public static void sendBlockUpdate(ServerPlayNetworkHandler player, BlockPos pos, BlockState state) {
        var version = PolymerServerNetworking.getSupportedVersion(player, ServerPackets.WORLD_SET_BLOCK_UPDATE);
        var p = state.getBlock() instanceof PolymerBlock polymerBlock ? polymerBlock : null;

        if (PolymerImplUtils.POLYMER_STATES.contains(state) && version > -1 && (p == null || p.canSynchronizeToPolymerClient(player.player))) {
            var buf = buf(version);

            buf.writeBlockPos(pos);
            buf.writeVarInt(Block.STATE_IDS.getRawId(state));

            player.sendPacket(new CustomPayloadS2CPacket(ServerPackets.WORLD_SET_BLOCK_UPDATE, buf));
        }

    }

    public static void sendMultiBlockUpdate(ServerPlayNetworkHandler player, ChunkSectionPos chunkPos, short[] positions, BlockState[] blockStates) {
        var version = PolymerServerNetworking.getSupportedVersion(player, ServerPackets.WORLD_CHUNK_SECTION_UPDATE);

        if (version > -1) {
            var list = new LongArrayList();

            for (int i = 0; i < blockStates.length; i++) {
                var p = blockStates[i].getBlock() instanceof PolymerBlock polymerBlock ? polymerBlock : null;
                if (PolymerImplUtils.POLYMER_STATES.contains(blockStates[i]) && (p == null || p.canSynchronizeToPolymerClient(player.player))) {
                    list.add(((long) Block.STATE_IDS.getRawId(blockStates[i])) << 12 | positions[i]);
                }
            }

            if (!list.isEmpty()) {
                var buf = buf(version);
                buf.writeChunkSectionPos(chunkPos);
                buf.writeVarInt(list.size());

                for (var value : list) {
                    buf.writeVarLong(value);
                }

                player.sendPacket(new CustomPayloadS2CPacket(ServerPackets.WORLD_CHUNK_SECTION_UPDATE, buf));
            }
        }
    }

    public static void sendSectionUpdate(ServerPlayNetworkHandler player, WorldChunk chunk) {
        var version = PolymerServerNetworking.getSupportedVersion(player, ServerPackets.WORLD_CHUNK_SECTION_UPDATE);

        if (version > -1) {
            var wci = (PolymerBlockPosStorage) chunk;
            if (wci.polymer$hasAny()) {
                var sections = chunk.getSectionArray();
                for (var i = 0; i < sections.length; i++) {
                    var section = sections[i];
                    var storage = (PolymerBlockPosStorage) section;

                    if (section != null && storage.polymer$hasAny()) {
                        var buf = buf(version);
                        var set = storage.polymer$getBackendSet();
                        buf.writeChunkSectionPos(ChunkSectionPos.from(chunk.getPos(), chunk.sectionIndexToCoord(i)));

                        assert set != null;
                        var data = new LongArrayList(set.size());

                        for (var pos : set) {
                            int x = ChunkSectionPos.unpackLocalX(pos);
                            int y = ChunkSectionPos.unpackLocalY(pos);
                            int z = ChunkSectionPos.unpackLocalZ(pos);
                            var state = section.getBlockState(x, y, z);
                            var p = state.getBlock() instanceof PolymerBlock polymerBlock ? polymerBlock : null;

                            if (p == null || p.canSynchronizeToPolymerClient(player.player)) {
                                data.add((((long) Block.STATE_IDS.getRawId(state)) << 12 | pos));
                            }
                        }

                        buf.writeVarInt(data.size());
                        data.forEach(buf::writeVarLong);

                        player.sendPacket(new CustomPayloadS2CPacket(ServerPackets.WORLD_CHUNK_SECTION_UPDATE, buf));
                    }
                }
            }
        }
    }


    public static void sendSyncPackets(ServerPlayNetworkHandler handler, boolean fullSync) {
        if (PolymerServerNetworking.getSupportedVersion(handler, ServerPackets.SYNC_STARTED) == -1) {
            return;
        }

        var startTime = System.nanoTime();
        int version;

        handler.sendPacket(new CustomPayloadS2CPacket(ServerPackets.SYNC_STARTED, buf(0)));
        PolymerSyncUtils.ON_SYNC_STARTED.invoke((c) -> c.accept(handler));

        version = PolymerServerNetworking.getSupportedVersion(handler, ServerPackets.SYNC_CLEAR);
        if (version != -1) {
            handler.sendPacket(new CustomPayloadS2CPacket(ServerPackets.SYNC_CLEAR, buf(version)));
        }

        version = PolymerServerNetworking.getSupportedVersion(handler, ServerPackets.SYNC_INFO);
        if (version != -1) {
            var buf = buf(version);

            buf.writeVarInt(PolymerImplUtils.getBlockStateOffset());

            handler.sendPacket(new CustomPayloadS2CPacket(ServerPackets.SYNC_INFO, buf));
        }
        sendSync(handler, ServerPackets.SYNC_ENCHANTMENT, getServerSideEntries(Registries.ENCHANTMENT), false,
                type -> new IdValueEntry(Registries.ENCHANTMENT.getRawId(type), Registries.ENCHANTMENT.getId(type)));

        PolymerSyncUtils.BEFORE_ITEM_SYNC.invoke((listener) -> listener.accept(handler, fullSync));
        sendSync(handler, ServerPackets.SYNC_ITEM, getServerSideEntries(Registries.ITEM), false, PolymerItemEntry::of);
        PolymerSyncUtils.AFTER_ITEM_SYNC.invoke((listener) -> listener.accept(handler, fullSync));

        if (fullSync) {
            PolymerSyncUtils.BEFORE_ITEM_GROUP_SYNC.invoke((listener) -> listener.accept(handler, true));

            sendCreativeSyncPackets(handler);

            PolymerSyncUtils.AFTER_ITEM_GROUP_SYNC.invoke((listener) -> listener.accept(handler, true));
        }

        PolymerSyncUtils.BEFORE_BLOCK_SYNC.invoke((listener) -> listener.accept(handler, fullSync));
        sendSync(handler, ServerPackets.SYNC_BLOCK, getServerSideEntries(Registries.BLOCK), false, PolymerBlockEntry::of);
        PolymerSyncUtils.AFTER_BLOCK_SYNC.invoke((listener) -> listener.accept(handler, fullSync));

        PolymerSyncUtils.BEFORE_BLOCK_STATE_SYNC.invoke((listener) -> listener.accept(handler, fullSync));
        sendSync(handler, ServerPackets.SYNC_BLOCKSTATE, getServerSideEntries(Block.STATE_IDS), false, PolymerBlockStateEntry::of);
        PolymerSyncUtils.AFTER_BLOCK_STATE_SYNC.invoke((listener) -> listener.accept(handler, fullSync));


        PolymerSyncUtils.BEFORE_ENTITY_SYNC.invoke((listener) -> listener.accept(handler, fullSync));
        sendSync(handler, ServerPackets.SYNC_ENTITY, getServerSideEntries(Registries.ENTITY_TYPE), false, PolymerEntityEntry::of);
        PolymerSyncUtils.AFTER_ENTITY_SYNC.invoke((listener) -> listener.accept(handler, fullSync));


        sendSync(handler, ServerPackets.SYNC_VILLAGER_PROFESSION, Registries.VILLAGER_PROFESSION);
        sendSync(handler, ServerPackets.SYNC_STATUS_EFFECT, Registries.STATUS_EFFECT);
        sendSync(handler, ServerPackets.SYNC_BLOCK_ENTITY, Registries.BLOCK_ENTITY_TYPE);
        sendSync(handler, ServerPackets.SYNC_FLUID, Registries.FLUID);

        if (fullSync) {
            sendSync(handler, ServerPackets.SYNC_TAGS, (Registry<Registry<Object>>) Registries.REGISTRIES, true, PolymerTagEntry::of);
        }

        PolymerSyncUtils.ON_SYNC_CUSTOM.invoke((c) -> c.accept(handler, fullSync));

        PolymerSyncUtils.ON_SYNC_FINISHED.invoke((c) -> c.accept(handler));

        handler.sendPacket(new CustomPayloadS2CPacket(ServerPackets.SYNC_FINISHED, buf(0)));


        if (PolymerImpl.LOG_SYNC_TIME) {
            PolymerImpl.LOGGER.info((fullSync ? "Full" : "Partial") + " sync for {} took {} ms", handler.player.getGameProfile().getName(), ((System.nanoTime() - startTime) / 10000) / 100d);
        }
    }

    private static <T> Collection<T> getServerSideEntries(IndexedIterable<T> registry) {
        if (registry instanceof Registry<T> registry1) {
            return RegistryExtension.getPolymerEntries(registry1);
        } else if (registry instanceof PolymerIdList<?>) {
            return ((PolymerIdList<T>) registry).polymer$getPolymerEntries();
        }

        return List.of();
    }

    private static void sendSync(ServerPlayNetworkHandler handler, Identifier packetId, Registry registry) {
        sendSync(handler, packetId, getServerSideEntries(registry), false,
                type -> new IdValueEntry(registry.getRawId(type), registry.getId(type)));
    }

    public static void sendCreativeSyncPackets(ServerPlayNetworkHandler handler) {
        var version = PolymerServerNetworking.getSupportedVersion(handler, ServerPackets.SYNC_ITEM_GROUP_DEFINE);

        if (version != -1) {
            for (var group : PolymerItemGroupUtils.getItemGroups(handler.getPlayer())) {
                syncItemGroup(group, handler);
            }

            handler.sendPacket(new CustomPayloadS2CPacket(ServerPackets.SYNC_ITEM_GROUP_APPLY_UPDATE, buf(PolymerServerNetworking.getSupportedVersion(handler, ServerPackets.SYNC_ITEM_GROUP_APPLY_UPDATE))));
        }
    }

    public static void syncItemGroup(ItemGroup group, ServerPlayNetworkHandler handler) {
        if (PolymerImpl.SYNC_MODDED_ENTRIES_POLYMC || PolymerItemGroupUtils.isPolymerItemGroup(group)) {
            removeItemGroup(group, handler);
            syncItemGroupDefinition(group, handler);
        }

        syncItemGroupContents(group, handler);
    }

    public static void syncItemGroupContents(ItemGroup group, ServerPlayNetworkHandler handler) {
        var version = PolymerServerNetworking.getSupportedVersion(handler, ServerPackets.SYNC_ITEM_GROUP_CONTENTS_ADD);

        if (version != -1) {
            {
                var buf = buf(PolymerServerNetworking.getSupportedVersion(handler, ServerPackets.SYNC_ITEM_GROUP_CONTENTS_CLEAR));
                buf.writeIdentifier(PolymerItemGroupUtils.getId(group));
                handler.sendPacket(new CustomPayloadS2CPacket(ServerPackets.SYNC_ITEM_GROUP_CONTENTS_CLEAR, buf));
            }


            try {
                var entry = PolymerItemGroupContent.of(group, handler);
                if (entry.isNonEmpty()) {
                    handler.sendPacket(entry.toPacket(ServerPackets.SYNC_ITEM_GROUP_CONTENTS_ADD));
                }
            } catch (Exception e) {

            }
        }

    }

    public static void syncItemGroupDefinition(ItemGroup group, ServerPlayNetworkHandler handler) {
        var version = PolymerServerNetworking.getSupportedVersion(handler, ServerPackets.SYNC_ITEM_GROUP_DEFINE);

        if (version > -1 && (PolymerImpl.SYNC_MODDED_ENTRIES_POLYMC || PolymerItemGroupUtils.isPolymerItemGroup(group))) {
            var buf = buf(version);

            buf.writeIdentifier(PolymerItemGroupUtils.getId(group));
            buf.writeText(ServerTranslationUtils.parseFor(handler, group.getDisplayName()));
            PolymerImplUtils.writeStack(buf, PolymerImplUtils.convertStack(group.getIcon(), handler.player));
            handler.sendPacket(new CustomPayloadS2CPacket(ServerPackets.SYNC_ITEM_GROUP_DEFINE, buf));
        }
    }

    public static void removeItemGroup(ItemGroup group, ServerPlayNetworkHandler player) {
        var version = PolymerServerNetworking.getSupportedVersion(player, ServerPackets.SYNC_ITEM_GROUP_REMOVE);

        if (version > -1 && PolymerItemGroupUtils.isPolymerItemGroup(group)) {
            player.sendPacket(new CustomPayloadS2CPacket(ServerPackets.SYNC_ITEM_GROUP_REMOVE, buf(version).writeIdentifier(PolymerItemGroupUtils.REGISTRY.getId(group))));
        }
    }

    public static void sendEntityInfo(ServerPlayNetworkHandler player, Entity entity) {
        sendEntityInfo(player, entity.getId(), entity.getType());
    }

    public static void sendEntityInfo(ServerPlayNetworkHandler player, int id, EntityType<?> type) {
        var version = PolymerServerNetworking.getSupportedVersion(player, ServerPackets.WORLD_ENTITY);

        if (version != -1) {
            var buf = buf(0);
            buf.writeVarInt(id);
            buf.writeIdentifier(Registries.ENTITY_TYPE.getId(type));
            player.sendPacket(new CustomPayloadS2CPacket(ServerPackets.WORLD_ENTITY, buf));
        }
    }


    private static void sendSync(ServerPlayNetworkHandler handler, Identifier id, int version, List<BufferWritable> entries) {
        handler.sendPacket(new SyncPacket(List.copyOf(entries)).toPacket(id));
        entries.clear();
    }

    private record SyncPacket(List<BufferWritable> entries) implements ServerPacketWriter {
        @Override
        public void write(ServerPlayNetworkHandler handler, PacketByteBuf buf, Identifier packetId, int version) {
            buf.writeVarInt(entries.size());

            for (var entry : entries) {
                entry.write(buf, version, handler);
            }
        }
    }

    private static <T> void sendSync(ServerPlayNetworkHandler handler, Identifier packetId, Iterable<T> iterable, boolean bypassPolymerCheck, Function<T, BufferWritable> writableFunction) {
        sendSync(handler, packetId, iterable, bypassPolymerCheck, (a, b, c) -> writableFunction.apply(a));
    }

    private static <T> void sendSync(ServerPlayNetworkHandler handler, Identifier packetId, Iterable<T> iterable, boolean bypassPolymerCheck, BufferWritableCreator<T> writableFunction) {
        var version = PolymerServerNetworking.getSupportedVersion(handler, packetId);

        if (iterable instanceof RegistryExtension && !bypassPolymerCheck) {
            iterable = ((RegistryExtension<T>) iterable).polymer$getEntries();
        }

        if (version != -1) {
            var entries = new ArrayList<BufferWritable>();
            for (var entry : iterable) {
                if (!bypassPolymerCheck || (entry instanceof PolymerSyncedObject<?> obj && obj.canSynchronizeToPolymerClient(handler.player))) {
                    var val = writableFunction.serialize(entry, handler, version);
                    if (val != null) {
                        entries.add(val);
                    }

                    if (entries.size() > 100) {
                        sendSync(handler, packetId, version, entries);
                    }
                }
            }

            if (entries.size() != 0) {
                sendSync(handler, packetId, version, entries);
            }
        }
    }

    public static void sendDebugValidateStatesPackets(ServerPlayNetworkHandler handler) {
        var version = PolymerServerNetworking.getSupportedVersion(handler, ServerPackets.DEBUG_VALIDATE_STATES);

        if (version != -1) {
            sendSync(handler, ServerPackets.DEBUG_VALIDATE_STATES, Block.STATE_IDS, true, DebugBlockStateEntry::of);
        }
    }

    public interface BufferWritableCreator<T> {
        BufferWritable serialize(T object, ServerPlayNetworkHandler handler, int version);
    }
}
