package meow.kikir.freesia.common.communicating.message.m2w;

import io.netty.buffer.ByteBuf;
import meow.kikir.freesia.common.communicating.handler.NettyClientChannelHandlerLayer;
import meow.kikir.freesia.common.communicating.message.IMessage;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class M2WWorkerIdentifyAcknowledgedMessage implements IMessage<NettyClientChannelHandlerLayer> {
    private UUID playerUUID;

    public M2WWorkerIdentifyAcknowledgedMessage() {

    }

    public M2WWorkerIdentifyAcknowledgedMessage(UUID playerUUID) {
        this.playerUUID = playerUUID;
    }

    @Override
    public void writeMessageData(@NotNull ByteBuf buffer) {
        buffer.writeLong(this.playerUUID.getMostSignificantBits());
        buffer.writeLong(this.playerUUID.getLeastSignificantBits());
    }

    @Override
    public void readMessageData(@NotNull ByteBuf buffer) {
        long playerMostSigBits = buffer.readLong();
        long playerLeastSigBits = buffer.readLong();

        this.playerUUID = new UUID(playerMostSigBits, playerLeastSigBits);
    }

    @Override
    public void process(@NotNull NettyClientChannelHandlerLayer handler) {
        handler.handleIdentifyAck(this.playerUUID);
    }
}
