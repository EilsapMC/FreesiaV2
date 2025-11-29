package meow.kikir.freesia.velocity.network.ysm;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.proxy.Player;
import io.netty.buffer.Unpooled;
import meow.kikir.freesia.velocity.FreesiaConstants;
import meow.kikir.freesia.velocity.Freesia;
import meow.kikir.freesia.velocity.YsmProtocolMetaFile;
import meow.kikir.freesia.velocity.events.PlayerYsmHandshakeEvent;
import meow.kikir.freesia.velocity.utils.FriendlyByteBuf;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.key.Key;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RealPlayerYsmPacketProxyImpl extends YsmPacketProxyLayer{

    public RealPlayerYsmPacketProxyImpl(Player player) {
        super(player);
    }

    @Override
    public CompletableFuture<Set<UUID>> fetchTrackerList(UUID observer) {
        return Freesia.tracker.getCanSee(observer);
    }

    @Override
    public ProxyComputeResult processS2C(Key key, ByteBuf copiedPacketData) {
        final FriendlyByteBuf mcBuffer = new FriendlyByteBuf(copiedPacketData);
        final byte packetId = mcBuffer.readByte();

        if (packetId == YsmProtocolMetaFile.getS2CPacketId(FreesiaConstants.YsmProtocolMetaConstants.Clientbound.ANIMATION_DATA_UPDATE)) {
            final int workerEntityId = mcBuffer.readVarInt();

            if (!this.isEntityStateOfSelf(workerEntityId)) {
                return ProxyComputeResult.ofDrop();
            }

            final MapperSessionProcessor target = Freesia.mapperManager.sessionProcessorByWorkerEntityId(workerEntityId);

            int targetEntityId;
            if (target == null || (targetEntityId = target.getPacketProxy().getPlayerEntityId()) == -1) {
                return ProxyComputeResult.ofDrop();
            }

            final byte[] payload = new byte[mcBuffer.readableBytes()];
            mcBuffer.readBytes(payload);

            // we need to remap the animation packet to the correct entity id
            final FriendlyByteBuf remapped = new FriendlyByteBuf(Unpooled.buffer());
            remapped.writeByte(packetId);
            remapped.writeVarInt(targetEntityId);
            remapped.writeBytes(payload);

            return ProxyComputeResult.ofModify(remapped);
        }
        
        if (packetId == YsmProtocolMetaFile.getS2CPacketId(FreesiaConstants.YsmProtocolMetaConstants.Clientbound.MODEL_DATA_UPDATE)) {
            final int workerEntityId = mcBuffer.readVarInt();

            if (!this.isEntityStateOfSelf(workerEntityId)) { // Check if the packet is current player and drop to prevent incorrect broadcasting
                return ProxyComputeResult.ofDrop(); // Do not process the entity state if it is not ours
            }

            final byte[] returnedNewData = new byte[mcBuffer.readableBytes()];
            mcBuffer.readBytes(returnedNewData);

            // Update stored data
            this.acquireWriteReference(); // Acquire write reference
            LAST_YSM_MODEL_DATA_HANDLE.setVolatile(this, returnedNewData);
            this.releaseWriteReference(); // Release write reference

            this.notifyFullTrackerUpdates(); // Notify updates

            return ProxyComputeResult.ofDrop();
        }

        if (packetId == YsmProtocolMetaFile.getS2CPacketId(FreesiaConstants.YsmProtocolMetaConstants.Clientbound.HAND_SHAKE_CONFIRMED)) {
            final String backendVersion = mcBuffer.readUtf();

            Freesia.LOGGER.info("Replying ysm client with server version {}.", backendVersion);

            return ProxyComputeResult.ofPass();
        }

        if (packetId == YsmProtocolMetaFile.getS2CPacketId(FreesiaConstants.YsmProtocolMetaConstants.Clientbound.MOLANG_EXECUTE)) {
            final int[] entityIds = mcBuffer.readVarIntArray();
            final int[] entityIdsRemapped = new int[entityIds.length];
            final String expression = mcBuffer.readUtf();

            final Map<Integer, RealPlayerYsmPacketProxyImpl> collectedPaddingWorkerEntityId = Freesia.mapperManager.collectRealProxy2WorkerEntityId();

            // remap the entity id
            int idx = 0;
            for (int singleWorkerEntityId : entityIds) {
                final RealPlayerYsmPacketProxyImpl targetProxy = collectedPaddingWorkerEntityId.get(singleWorkerEntityId);

                if (targetProxy == null) {
                    continue;
                }

                entityIdsRemapped[idx] = targetProxy.getPlayerEntityId(); // we are on backend side
                idx++;
            }

            // re-send packet as it's much cheaper than modify
            this.executeMolang(entityIdsRemapped, expression);
            return ProxyComputeResult.ofDrop();
        }

        return ProxyComputeResult.ofPass();
    }

    @Override
    public ProxyComputeResult processC2S(Key key, ByteBuf copiedPacketData) {
        final FriendlyByteBuf mcBuffer = new FriendlyByteBuf(copiedPacketData);
        final byte packetId = mcBuffer.readByte();

        if (packetId == YsmProtocolMetaFile.getC2SPacketId(FreesiaConstants.YsmProtocolMetaConstants.Serverbound.HAND_SHAKE_REQUEST)) {
            final ResultedEvent.GenericResult result = Freesia.PROXY_SERVER.getEventManager().fire(new PlayerYsmHandshakeEvent(this.player)).join().getResult();

            if (!result.isAllowed()) {
                return ProxyComputeResult.ofDrop();
            }

            final String clientYsmVersion = mcBuffer.readUtf();
            Freesia.LOGGER.info("Player {} is connected to the backend with ysm version {}", this.player.getUsername(), clientYsmVersion);
            Freesia.mapperManager.onClientYsmHandshakePacketReply(this.player);
        }

        if (packetId == YsmProtocolMetaFile.getC2SPacketId(FreesiaConstants.YsmProtocolMetaConstants.Serverbound.MOLANG_EXECUTE_REQ)) {
            final String molangExpression = mcBuffer.readUtf();
            final int entityId = mcBuffer.readVarInt(); // this is our entity on worker side and we currently don't need it

            final int currWorkerEntityId = this.getPlayerWorkerEntityId();

            if (currWorkerEntityId != -1) {
                final FriendlyByteBuf newPacketByteBuf = new FriendlyByteBuf(Unpooled.buffer());

                newPacketByteBuf.writeByte(YsmProtocolMetaFile.getC2SPacketId(FreesiaConstants.YsmProtocolMetaConstants.Serverbound.MOLANG_EXECUTE_REQ));
                newPacketByteBuf.writeUtf(molangExpression);
                newPacketByteBuf.writeVarInt(currWorkerEntityId);

                return ProxyComputeResult.ofModify(newPacketByteBuf);
            }
        }

        if (packetId == YsmProtocolMetaFile.getC2SPacketId(FreesiaConstants.YsmProtocolMetaConstants.Serverbound.ANIMATION_ACTION)) {
            final int animationId = mcBuffer.readVarInt();
            final String animation = mcBuffer.readUtf();
            final int entityIdBackend = mcBuffer.readVarInt();

            if (entityIdBackend == -1) {
                return ProxyComputeResult.ofPass();
            }

            final MapperSessionProcessor targetMapper = Freesia.mapperManager.sessionProcessorByEntityId(entityIdBackend);

            if (targetMapper == null) {
                Freesia.LOGGER.info("Ignoring invalid animation action with entity id {} for player {}.", entityIdBackend, this.player.getUsername());
                return ProxyComputeResult.ofDrop();
            }

            final int entityIdWorker = targetMapper.getPacketProxy().getPlayerWorkerEntityId();

            if (entityIdWorker == -1) {
                Freesia.LOGGER.info("Ignoring animation action with non-existed worker side entity id for player {}.", this.player.getUsername());
                return ProxyComputeResult.ofDrop();
            }

            final FriendlyByteBuf remapped = new FriendlyByteBuf(Unpooled.buffer());

            remapped.writeByte(YsmProtocolMetaFile.getC2SPacketId(FreesiaConstants.YsmProtocolMetaConstants.Serverbound.ANIMATION_ACTION));
            remapped.writeVarInt(animationId);
            remapped.writeUtf(animation);
            remapped.writeVarInt(entityIdWorker);

            return ProxyComputeResult.ofModify(remapped);
        }

        return ProxyComputeResult.ofPass();
    }
}
