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

/** Bytes of one WebSocket frame → one {@link Message}. Anything malformed or unknown is a {@link ProtocolException}. */
public final class MessageReader {

    private MessageReader() {
    }

    public static Message read(byte[] frame) {
        Lib0Decoder decoder = new Lib0Decoder(frame);
        int type = decoder.readVarUintAsInt();
        Message message = switch (type) {
            case Messages.SYNC -> readSync(decoder);
            case Messages.AWARENESS -> new Awareness(decoder.readVarUint8Array());
            case Messages.QUERY_AWARENESS -> new QueryAwareness();
            case Messages.TRAKR_CHECKPOINT -> new TrakrCheckpoint(decoder.readVarUint());
            case Messages.TRAKR_SNAPSHOT -> new TrakrSnapshot(decoder.readVarUint(), decoder.readVarUint8Array(),
                    decoder.readVarString(), decoder.readVarString());
            case Messages.TRAKR_SEED -> new TrakrSeed();
            default -> throw new ProtocolException("unknown message type " + type);
        };
        // a valid frame is exactly one message: extra bytes mean we and the sender disagree about the format
        if (decoder.hasRemaining()) {
            throw new ProtocolException(decoder.remaining() + " unexpected bytes after message type " + type);
        }
        return message;
    }

    private static Message readSync(Lib0Decoder decoder) {
        int step = decoder.readVarUintAsInt();
        byte[] payload = decoder.readVarUint8Array();
        return switch (step) {
            case Messages.SYNC_STEP_1 -> new SyncStep1(payload);
            case Messages.SYNC_STEP_2 -> new SyncStep2(payload);
            case Messages.SYNC_UPDATE -> new Update(payload);
            default -> throw new ProtocolException("unknown sync step " + step);
        };
    }
}
