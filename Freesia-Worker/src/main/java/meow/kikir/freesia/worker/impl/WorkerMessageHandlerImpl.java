package meow.kikir.freesia.worker.impl;

import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import com.google.common.collect.Maps;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import io.netty.channel.ChannelHandlerContext;
import meow.kikir.freesia.common.EntryPoint;
import meow.kikir.freesia.common.communicating.NettySocketClient;
import meow.kikir.freesia.common.communicating.handler.NettyClientChannelHandlerLayer;
import meow.kikir.freesia.common.communicating.message.w2m.*;
import meow.kikir.freesia.worker.Constants;
import meow.kikir.freesia.worker.ServerLoader;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;

public class WorkerMessageHandlerImpl extends NettyClientChannelHandlerLayer {
    private final AtomicInteger traceIdGenerator = new AtomicInteger(0);
    private final Map<Integer, Consumer<byte[]>> playerDataGetCallbacks = Maps.newConcurrentMap();

    private final Map<UUID, Integer> playerEntityIdMap = Maps.newConcurrentMap();
    private final Map<UUID, MultiThreadedQueue<Consumer<Integer>>> playerEntityIdRequestCallbacks = Maps.newConcurrentMap();
    private final Map<UUID, MultiThreadedQueue<Runnable>> mappper2MWLinkingCallbacks = Maps.newConcurrentMap();

    private volatile boolean playerDataFetchCallbackRetired = false;
    private final StampedLock playerDataFetchCallbackLock = new StampedLock();

    @Override
    public void channelActive(@NotNull ChannelHandlerContext ctx) {
        super.channelActive(ctx);

        this.getClient().sendToMaster(new W2MWorkerInfoMessage(ServerLoader.workerInfoFile.workerUUID(), ServerLoader.workerInfoFile.workerName()));
        ServerLoader.workerConnection = this;

        this.cleanModelFolder(Constants.MODELS_PATH_ALL);
    }

    @Override
    public void channelInactive(@NotNull ChannelHandlerContext ctx) {
        this.retireAllEntityIdCallbacks();
        this.retirePlayerFetchCallbacks();
        super.channelInactive(ctx);

        if (ServerLoader.SERVER_INST == null) {
            // 好罢这里我也没啥办法了
            // 先单开个新线程罢,反正用完就退出了(x)
            new Thread(() -> {
                EntryPoint.LOGGER_INST.info("Server instance is null, using async thread to reconnect");
                ServerLoader.connectToBackend();
            }).start();

            return;
        }

        ServerLoader.SERVER_INST.execute(ServerLoader::connectToBackend);
    }

    private void retireAllEntityIdCallbacks() {
        for (Map.Entry<UUID, MultiThreadedQueue<Consumer<Integer>>> entry : this.playerEntityIdRequestCallbacks.entrySet()) {
            final MultiThreadedQueue<Consumer<Integer>> toRetire = entry.getValue();
            Consumer<Integer> retired;
            while ((retired = toRetire.pollOrBlockAdds()) != null) {
                retired.accept(Integer.MIN_VALUE);
            }
        }

        this.playerEntityIdRequestCallbacks.clear();
    }

    public void ensureLinkedMM(UUID playerUUID, Runnable onLinked) {
        final MultiThreadedQueue<Runnable> callbackQueue = this.mappper2MWLinkingCallbacks.computeIfAbsent(playerUUID, k -> new MultiThreadedQueue<>());

        if (!callbackQueue.offer(onLinked)) {
            // already linked
            onLinked.run();
        }
    }

    public void onPlayerRemove(UUID removedPlayer) {
        this.playerEntityIdMap.remove(removedPlayer);
        this.mappper2MWLinkingCallbacks.remove(removedPlayer);

        final MultiThreadedQueue<Consumer<Integer>> toRetire = this.playerEntityIdRequestCallbacks.remove(removedPlayer);
        if (toRetire != null) {
            Consumer<Integer> retired;
            while ((retired = toRetire.pollOrBlockAdds()) != null) {
                retired.accept(Integer.MIN_VALUE);
            }
        }
    }

    @Override
    public void handleSetPlayerEntityId(UUID targetPlayer, int entityId) {
        EntryPoint.LOGGER_INST.info("Pointing entity id of player {} to {}", targetPlayer, entityId);

        this.playerEntityIdMap.put(targetPlayer, entityId);

        // we will block add as the entity id is already properly synchronized or the player was removed
        final MultiThreadedQueue<Consumer<Integer>> toNotify = this.playerEntityIdRequestCallbacks.remove(targetPlayer);
        if (toNotify != null) {
            Consumer<Integer> notify;
            while ((notify = toNotify.pollOrBlockAdds()) != null) {
                notify.accept(entityId);
            }
        }
    }

    public boolean fetchPlayerEntityId(UUID playerUUID, Consumer<Integer> onGot) {
        final Integer existingEntityId = this.playerEntityIdMap.get(playerUUID);
        System.out.println(existingEntityId);
        if (existingEntityId != null && existingEntityId != Integer.MIN_VALUE) {
            onGot.accept(existingEntityId);
            return true;
        }

        final MultiThreadedQueue<Consumer<Integer>> callbackQueue = this.playerEntityIdRequestCallbacks.computeIfAbsent(playerUUID, k -> new MultiThreadedQueue<>());

        // ok so we are totally failed this
        // might be the situations following:
        // 1. the player was removed during the login process of the mapper connection (? this should be impossible)
        // 2. we are calling the method ahead the real entity id coming to the worker (will 2nd try fetching from cache)
        if (!callbackQueue.offer(onGot)) {
            // so we get this, normally return
            final Integer lookupAgain = this.playerEntityIdMap.get(playerUUID);
            System.out.println(lookupAgain);
            if (lookupAgain != null && lookupAgain != Integer.MIN_VALUE) {
                onGot.accept(lookupAgain);
                return true;
            }

            // still null, which means the 2 is not possible, just return false as we cannot raise the request currently
            return false;
        }

        // raise the request to notify master to send the entity id
        this.getClient().sendToMaster(new W2MRequestPlayerEntityIdMessage(playerUUID));

        return true;
    }

    // note: Integer.MIN_VALUE -> not present
    public int getPlayerEntityId(UUID playerUUID) {
        Integer entityId = this.playerEntityIdMap.get(playerUUID);

        if (entityId == null) {
            return Integer.MIN_VALUE;
        }

        return entityId;
    }

    public void sendIdentity(UUID playerUUID) {
        this.getClient().sendToMaster(new W2MWorkerIdentifyMessage(playerUUID, ServerLoader.workerInfoFile.workerUUID()));
    }

    private void retirePlayerFetchCallbacks() {
        final long stamp = this.playerDataFetchCallbackLock.writeLock();
        try {
            this.playerDataFetchCallbackRetired = true;

            for (Map.Entry<Integer, Consumer<byte[]>> entry : this.playerDataGetCallbacks.entrySet()) {
                try {
                    entry.getValue().accept(null);
                } catch (Exception e) {
                    EntryPoint.LOGGER_INST.error("Failed to fire player data callback!", e);
                }
            }

            this.playerDataGetCallbacks.clear();
        }finally {
            this.playerDataFetchCallbackLock.unlockWrite(stamp);
        }
    }

    public void getPlayerData(UUID playerUUID, Consumer<CompoundTag> onGot) {
        final long stamp = this.playerDataFetchCallbackLock.readLock();
        try {
            if (this.playerDataFetchCallbackRetired) {
                onGot.accept(null);
                return;
            }

            final int generatedTraceId = this.traceIdGenerator.getAndIncrement();

            final Consumer<byte[]> wrappedDecoder = content -> {
                CompoundTag decoded = null;

                if (content == null) {
                    onGot.accept(null);
                    return;
                }

                try {
                    decoded = (CompoundTag) NbtIo.readAnyTag(new DataInputStream(new ByteArrayInputStream(content)), NbtAccounter.unlimitedHeap());
                } catch (Exception e) {
                    EntryPoint.LOGGER_INST.error("Failed to decode nbt!", e);
                }

                onGot.accept(decoded);
            };

            this.playerDataGetCallbacks.put(generatedTraceId, wrappedDecoder);

            ServerLoader.clientInstance.sendToMaster(new W2MPlayerDataGetRequestMessage(playerUUID, generatedTraceId));
        }finally {
            this.playerDataFetchCallbackLock.unlockRead(stamp);
        }
    }

    @Override
    public NettySocketClient getClient() {
        return ServerLoader.clientInstance;
    }

    @Override
    public void onMasterPlayerDataResponse(int traceId, byte[] content) {
        final Consumer<byte[]> removed;

        final long readStamp = this.playerDataFetchCallbackLock.readLock();
        try {
            removed = this.playerDataGetCallbacks.remove(traceId);
        }finally {
            this.playerDataFetchCallbackLock.unlockRead(readStamp);
        }

        if (removed == null) {
            EntryPoint.LOGGER_INST.warn("Null traceId {} !", traceId);
            return;
        }

        try {
            removed.accept(content);
        } catch (Exception e) {
            EntryPoint.LOGGER_INST.error("Failed to fire player data callback!", e);
        }
    }

    @Override
    public CompletableFuture<String> dispatchCommand(String command) {
        final CompletableFuture<String> callback = new CompletableFuture<>();

        Runnable scheduledCommand = () -> {
            CommandDispatcher<CommandSourceStack> commandDispatcher = ServerLoader.SERVER_INST.getCommands().getDispatcher();

            final ParseResults<CommandSourceStack> parsed = commandDispatcher.parse(command, ServerLoader.SERVER_INST.createCommandSourceStack().withSource(new CommandSource() {
                @Override
                public void sendSystemMessage(Component component) {
                    callback.complete(component.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            }));

            ServerLoader.SERVER_INST.getCommands().performCommand(parsed, command);
        };
        ServerLoader.SERVER_INST.execute(scheduledCommand);

        return callback;
    }

    @Override
    public void handleReadyNotification() {
        this.getClient().onReady();

        // not on first start
        if (ServerLoader.SERVER_INST != null) {
            this.callYsmModelReload();
        }
    }

    @Override
    public void callYsmModelReload() {
        EntryPoint.LOGGER_INST.info("Calling /ysm model reload");

        final String builtCommand = "ysm model reload";

        Runnable scheduledCommand = () -> {
            CommandDispatcher<CommandSourceStack> commandDispatcher = ServerLoader.SERVER_INST.getCommands().getDispatcher();

            final ParseResults<CommandSourceStack> parsed = commandDispatcher.parse(builtCommand, ServerLoader.SERVER_INST.createCommandSourceStack().withSource(new CommandSource() {
                @Override
                public void sendSystemMessage(Component component) {
                    EntryPoint.LOGGER_INST.info("Ysm reload response: {}", component.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            }));

            ServerLoader.SERVER_INST.getCommands().performCommand(parsed, builtCommand);
        };

        ServerLoader.SERVER_INST.execute(scheduledCommand);
    }

    @Override
    public void handleIdentifyAck(UUID playerUUID) {
        EntryPoint.LOGGER_INST.info("Worker identified by master for player {}", playerUUID);

        final MultiThreadedQueue<Runnable> callbackQueue = this.mappper2MWLinkingCallbacks.get(playerUUID);

        if (callbackQueue != null) {
            Runnable callback;
            while ((callback = callbackQueue.pollOrBlockAdds()) != null) {
                callback.run();
            }
        }
    }

    public void updatePlayerData(UUID playerUUID, CompoundTag data) {
        try {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final DataOutputStream dos = new DataOutputStream(bos);

            NbtIo.writeAnyTag(data, dos);
            dos.flush();

            final byte[] content = bos.toByteArray();

            ServerLoader.clientInstance.sendToMaster(new W2MUpdatePlayerDataRequestMessage(playerUUID, content));
        } catch (Exception e) {
            EntryPoint.LOGGER_INST.error("Failed to encode nbt!", e);
        }
    }
}
