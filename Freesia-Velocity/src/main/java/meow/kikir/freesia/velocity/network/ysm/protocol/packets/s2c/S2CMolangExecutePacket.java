package meow.kikir.freesia.velocity.network.ysm.protocol.packets.s2c;

import meow.kikir.freesia.velocity.network.ysm.MapperSessionProcessor;
import meow.kikir.freesia.velocity.network.ysm.ProxyComputeResult;
import meow.kikir.freesia.velocity.network.ysm.YsmPacketProxyLayer;
import meow.kikir.freesia.velocity.network.ysm.protocol.EntityIdRemappablePacket;
import meow.kikir.freesia.velocity.network.ysm.protocol.YsmPacket;
import meow.kikir.freesia.velocity.utils.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public class S2CMolangExecutePacket implements YsmPacket, EntityIdRemappablePacket {
    private int[] entityIds;
    private String expression;

    public S2CMolangExecutePacket(int[] entityIds, String expression) {
        this.entityIds = entityIds;
        this.expression = expression;
    }

    public S2CMolangExecutePacket() {}

    @Override
    public void encode(@NotNull FriendlyByteBuf output) {
        output.writeVarIntArray(this.entityIds);
        output.writeUtf(this.expression);
    }

    @Override
    public void decode(@NotNull FriendlyByteBuf input) {
        this.entityIds = input.readVarIntArray();
        this.expression = input.readUtf();
    }

    @Override
    public ProxyComputeResult handle(@NotNull MapperSessionProcessor handler) {
        final YsmPacketProxyLayer packetProxy = (YsmPacketProxyLayer) handler.getPacketProxy();

        final int[] entityIdsRemapped = new int[entityIds.length];
        // remap the entity id
        int idx = 0;
        for (int singleWorkerEntityId : entityIds) {
            entityIdsRemapped[idx] = this.worker2BackendEntityId(singleWorkerEntityId); // we are on backend side
            idx++;
        }

        // re-send packet as it's much cheaper than modify
        packetProxy.executeMolang(entityIdsRemapped, expression);
        return ProxyComputeResult.ofDrop();
    }
}
