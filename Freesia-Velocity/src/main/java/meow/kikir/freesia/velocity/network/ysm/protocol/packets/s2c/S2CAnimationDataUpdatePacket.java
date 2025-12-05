package meow.kikir.freesia.velocity.network.ysm.protocol.packets.s2c;

import meow.kikir.freesia.velocity.network.ysm.MapperSessionProcessor;
import meow.kikir.freesia.velocity.network.ysm.ProxyComputeResult;
import meow.kikir.freesia.velocity.network.ysm.YsmPacketProxyLayer;
import meow.kikir.freesia.velocity.network.ysm.protocol.EntityIdRemappablePacket;
import meow.kikir.freesia.velocity.network.ysm.protocol.YsmPacket;
import meow.kikir.freesia.velocity.utils.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public class S2CAnimationDataUpdatePacket implements YsmPacket, EntityIdRemappablePacket {
    private int entityId;
    private byte[] animationData;

    public S2CAnimationDataUpdatePacket(int entityId, byte[] animationData) {
        this.entityId = entityId;
        this.animationData = animationData;
    }

    public S2CAnimationDataUpdatePacket() {}

    @Override
    public void encode(@NotNull FriendlyByteBuf output) {
        output.writeVarInt(this.entityId);
        output.writeBytes(this.animationData);
    }

    @Override
    public void decode(@NotNull FriendlyByteBuf input) {
        this.entityId = input.readVarInt();
        this.animationData = new byte[input.readableBytes()];
        input.readBytes(this.animationData);
    }

    @Override
    public ProxyComputeResult handle(@NotNull MapperSessionProcessor handler) {
        final YsmPacketProxyLayer packetProxy = (YsmPacketProxyLayer) handler.getPacketProxy();

        // Check if the packet is current player and drop to prevent incorrect broadcasting
        if (!packetProxy.isEntityStateOfSelf(this.entityId)) {
            return ProxyComputeResult.ofDrop();
        }

        // remap to the backend side entity id
        this.entityId = this.worker2BackendEntityId(this.entityId);

        // Update stored data
        packetProxy.setAnimationDataRaw(this.animationData);

        packetProxy.notifyFullTrackerUpdates(); // Notify updates

        return ProxyComputeResult.ofDrop();
    }
}
