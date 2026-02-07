package meow.kikir.freesia.common.communicating.message.w2m;

import io.netty.buffer.ByteBuf;
import meow.kikir.freesia.common.communicating.handler.NettyServerChannelHandlerLayer;
import meow.kikir.freesia.common.communicating.message.IMessage;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

// ah real bro
// we had no choices as the connections are really separated
// so we need this **bullshit** to correctly handle the linking
// see more in MapperSessionProcessor in  module "velocity"
public class W2MWorkerIdentifyMessage implements IMessage<NettyServerChannelHandlerLayer> {
    private UUID playerUUID;
    private UUID workerUUID;

    public W2MWorkerIdentifyMessage() {}

    public W2MWorkerIdentifyMessage(UUID playerUUID, UUID workerUUID) {
        this.playerUUID = playerUUID;
        this.workerUUID = workerUUID;
    }

    @Override
    public void writeMessageData(@NotNull ByteBuf buffer) {
        buffer.writeLong(this.playerUUID.getMostSignificantBits());
        buffer.writeLong(this.playerUUID.getLeastSignificantBits());
        buffer.writeLong(this.workerUUID.getMostSignificantBits());
        buffer.writeLong(this.workerUUID.getLeastSignificantBits());
    }

    @Override
    public void readMessageData(@NotNull ByteBuf buffer) {
        long playerMostSigBits = buffer.readLong();
        long playerLeastSigBits = buffer.readLong();
        this.playerUUID = new UUID(playerMostSigBits, playerLeastSigBits);

        long workerMostSigBits = buffer.readLong();
        long workerLeastSigBits = buffer.readLong();
        this.workerUUID = new UUID(workerMostSigBits, workerLeastSigBits);
    }

    @Override
    public void process(@NotNull NettyServerChannelHandlerLayer handler) {
        handler.handleWorkerIdentify(this.playerUUID, this.workerUUID);
    }
}
