package meow.kikir.freesia.velocity.network.ysm.protocol.packets.c2s;

import meow.kikir.freesia.velocity.network.ysm.MapperSessionProcessor;
import meow.kikir.freesia.velocity.network.ysm.ProxyComputeResult;
import meow.kikir.freesia.velocity.network.ysm.protocol.EntityIdRemappablePacket;
import meow.kikir.freesia.velocity.network.ysm.protocol.YsmPacket;
import meow.kikir.freesia.velocity.network.ysm.protocol.YsmPacketCodec;
import meow.kikir.freesia.common.utils.SimpleFriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public class C2SSetPlayAnimation implements YsmPacket, EntityIdRemappablePacket {
    private int extraAnimationIndex;
    private String classifyId;
    private int entityId;

    public C2SSetPlayAnimation() {}

    public C2SSetPlayAnimation(int entityId, String classifyId, int extraAnimationIndex) {
        this.entityId = entityId;
        this.classifyId = classifyId;
        this.extraAnimationIndex = extraAnimationIndex;
    }

    @Override
    public void encode(@NotNull SimpleFriendlyByteBuf output) {
        output.writeVarInt(this.extraAnimationIndex);
        output.writeUtf(this.classifyId);
        output.writeVarInt(this.entityId);
    }

    @Override
    public void decode(@NotNull SimpleFriendlyByteBuf input) {
        this.extraAnimationIndex = input.readVarInt();
        this.classifyId = input.readUtf();
        this.entityId = input.readVarInt();
    }

    @Override
    public ProxyComputeResult handle(@NotNull MapperSessionProcessor handler) {
        // -1 -> current player
        // not -1 -> other entity(TLM)
        int entityIdWorker = this.entityId;
        if (this.entityId != -1) {
            entityIdWorker = this.backend2WorkerEntityId(this.entityId);

            if (entityIdWorker == -1) {
                return ProxyComputeResult.ofDrop();
            }
        }

        final C2SSetPlayAnimation remapped = new C2SSetPlayAnimation(entityIdWorker, this.classifyId, this.extraAnimationIndex);
        final SimpleFriendlyByteBuf remappedData = YsmPacketCodec.encode(remapped);
        return ProxyComputeResult.ofModify(remappedData);
    }
}
