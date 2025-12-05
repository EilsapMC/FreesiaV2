package meow.kikir.freesia.velocity.network.ysm.protocol.packets.c2s;

import meow.kikir.freesia.velocity.network.ysm.MapperSessionProcessor;
import meow.kikir.freesia.velocity.network.ysm.ProxyComputeResult;
import meow.kikir.freesia.velocity.network.ysm.protocol.EntityIdRemappablePacket;
import meow.kikir.freesia.velocity.network.ysm.protocol.YsmPacket;
import meow.kikir.freesia.velocity.network.ysm.protocol.YsmPacketCodec;
import meow.kikir.freesia.velocity.utils.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public class C2SMolangExecuteRequestPacket implements YsmPacket, EntityIdRemappablePacket {
    private int entityId;
    private String expression;

    public C2SMolangExecuteRequestPacket() {}

    public C2SMolangExecuteRequestPacket(int entityId, String expression) {
        this.entityId = entityId;
        this.expression = expression;
    }

    @Override
    public void encode(@NotNull FriendlyByteBuf output) {
        output.writeUtf(this.expression);
        output.writeVarInt(this.entityId);
    }

    @Override
    public void decode(@NotNull FriendlyByteBuf input) {
        this.expression = input.readUtf();
        this.entityId = input.readVarInt();
    }

    @Override
    public ProxyComputeResult handle(@NotNull MapperSessionProcessor handler) {
        final int entityIdWorker = this.backend2WorkerEntityId(this.entityId);

        if (entityIdWorker == -1) {
            return ProxyComputeResult.ofDrop();
        }

        final C2SMolangExecuteRequestPacket remapped = new C2SMolangExecuteRequestPacket(entityIdWorker, this.expression);
        final FriendlyByteBuf remappedData = YsmPacketCodec.encode(remapped);
        return ProxyComputeResult.ofModify(remappedData);
    }
}
