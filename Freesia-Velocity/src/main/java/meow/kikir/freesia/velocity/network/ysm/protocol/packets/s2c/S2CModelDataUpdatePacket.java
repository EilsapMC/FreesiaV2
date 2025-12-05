package meow.kikir.freesia.velocity.network.ysm.protocol.packets.s2c;

import meow.kikir.freesia.velocity.network.ysm.MapperSessionProcessor;
import meow.kikir.freesia.velocity.network.ysm.ProxyComputeResult;
import meow.kikir.freesia.velocity.network.ysm.YsmPacketProxyLayer;
import meow.kikir.freesia.velocity.network.ysm.protocol.YsmPacket;
import meow.kikir.freesia.velocity.utils.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public class S2CModelDataUpdatePacket implements YsmPacket {
    private int entityId;
    private byte[] modelData;

    public S2CModelDataUpdatePacket(int entityId, byte[] modelData) {
        this.entityId = entityId;
        this.modelData = modelData;
    }

    public S2CModelDataUpdatePacket() {}

    @Override
    public void encode(@NotNull FriendlyByteBuf output) {
        output.writeVarInt(this.entityId);
        output.writeBytes(this.modelData);
    }

    @Override
    public void decode(@NotNull FriendlyByteBuf input) {
        this.entityId = input.readVarInt();
        this.modelData = new byte[input.readableBytes()];
        input.readBytes(this.modelData);
    }

    @Override
    public ProxyComputeResult handle(@NotNull MapperSessionProcessor handler) {
        final YsmPacketProxyLayer packetProxy = (YsmPacketProxyLayer) handler.getPacketProxy();

        // Check if the packet is current player and drop to prevent incorrect broadcasting
        if (!packetProxy.isEntityStateOfSelf(this.entityId)) {
            return ProxyComputeResult.ofDrop();
        }

        // Update stored data
        packetProxy.setModelDataRaw(this.modelData);
        // Notify updates
        packetProxy.notifyFullTrackerUpdates();

        return ProxyComputeResult.ofDrop();
    }
}
