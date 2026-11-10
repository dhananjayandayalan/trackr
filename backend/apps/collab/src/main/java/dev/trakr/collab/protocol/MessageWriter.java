package dev.trakr.collab.protocol;

import dev.trakr.collab.protocol.Messages.Awareness;
import dev.trakr.collab.protocol.Messages.Message;
import dev.trakr.collab.protocol.Messages.QueryAwareness;
import dev.trakr.collab.protocol.Messages.SyncStep1;
import dev.trakr.collab.protocol.Messages.SyncStep2;
import dev.trakr.collab.protocol.Messages.TrakrCheckpoint;
import dev.trakr.collab.protocol.Messages.TrakrSeed;
import dev.trakr.collab.protocol.Messages.TrakrSnapshot;
import dev.trakr.collab.protocol.Messages.Update;

/** One {@link Message} → the bytes of one WebSocket frame; {@code read(write(m))} gives {@code m} back. */
public final class MessageWriter {

    private MessageWriter() {
    }

    public static byte[] write(Message message) {
        Lib0Encoder encoder = new Lib0Encoder();
        switch (message) {
            case SyncStep1 m -> sync(encoder, Messages.SYNC_STEP_1, m.stateVector());
            case SyncStep2 m -> sync(encoder, Messages.SYNC_STEP_2, m.update());
            case Update m -> sync(encoder, Messages.SYNC_UPDATE, m.update());
            case Awareness m -> encoder.writeVarUint(Messages.AWARENESS).writeVarUint8Array(m.update());
            case QueryAwareness m -> encoder.writeVarUint(Messages.QUERY_AWARENESS);
            case TrakrCheckpoint m -> encoder.writeVarUint(Messages.TRAKR_CHECKPOINT).writeVarUint(m.uptoUpdateId());
            case TrakrSnapshot m -> encoder.writeVarUint(Messages.TRAKR_SNAPSHOT)
                    .writeVarUint(m.uptoUpdateId())
                    .writeVarUint8Array(m.state())
                    .writeVarString(m.text())
                    .writeVarString(m.html());
            case TrakrSeed m -> encoder.writeVarUint(Messages.TRAKR_SEED);
        }
        return encoder.toByteArray();
    }

    // shortcuts for the frames the server sends all the time
    public static byte[] update(byte[] update) {
        return write(new Update(update));
    }

    public static byte[] awareness(byte[] awarenessUpdate) {
        return write(new Awareness(awarenessUpdate));
    }

    private static void sync(Lib0Encoder encoder, int step, byte[] payload) {
        encoder.writeVarUint(Messages.SYNC).writeVarUint(step).writeVarUint8Array(payload);
    }
}
