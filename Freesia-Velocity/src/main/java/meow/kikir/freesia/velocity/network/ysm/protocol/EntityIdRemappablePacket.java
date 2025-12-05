package meow.kikir.freesia.velocity.network.ysm.protocol;

import meow.kikir.freesia.velocity.Freesia;
import meow.kikir.freesia.velocity.network.ysm.MapperSessionProcessor;
import meow.kikir.freesia.velocity.network.ysm.YsmPacketProxy;

public interface EntityIdRemappablePacket {
    default int worker2BackendEntityId(int workerEntityId) {
        final MapperSessionProcessor targetMapper = Freesia.mapperManager.sessionProcessorByWorkerEntityId(workerEntityId);

        if (targetMapper == null) {
            return -1;
        }

        final YsmPacketProxy proxy = targetMapper.getPacketProxy();

        return proxy.getPlayerEntityId();
    }

    default int backend2WorkerEntityId(int backendEntityId) {
        final MapperSessionProcessor targetMapper = Freesia.mapperManager.sessionProcessorByEntityId(backendEntityId);

        if (targetMapper == null) {
            return -1;
        }

        final YsmPacketProxy proxy = targetMapper.getPacketProxy();

        return proxy.getPlayerWorkerEntityId();
    }
}
