package dev.trakr.collab.protocol;

/**
 * The frames a collab socket carries (06-collab §3), as constants and one small record per kind. The records are
 * a sealed family, so the handler's {@code switch} over a parsed frame is checked by the compiler.
 *
 * <p>Types 0, 1 and 3 are y-protocols' own (what y-websocket speaks); 101–103 are ours, registered as extra
 * message handlers in the browser (trakr-provider.ts). Byte arrays are passed through untouched: the server never
 * looks inside a Yjs update or a state vector.
 */
public final class Messages {

    // BLOCK 1: the first varUint of every frame
    public static final int SYNC = 0;
    public static final int AWARENESS = 1;
    public static final int QUERY_AWARENESS = 3;
    public static final int TRAKR_CHECKPOINT = 101;
    public static final int TRAKR_SNAPSHOT = 102;
    public static final int TRAKR_SEED = 103;

    // BLOCK 2: the second varUint of a sync frame
    public static final int SYNC_STEP_1 = 0;
    public static final int SYNC_STEP_2 = 1;
    public static final int SYNC_UPDATE = 2;

    /** An empty state vector: "I have nothing" — so the client answers with everything it has. */
    public static final byte[] EMPTY_STATE_VECTOR = {0};
    /** An update that changes nothing (no structs, no deletions): what an empty Y.Doc encodes to. */
    public static final byte[] EMPTY_UPDATE = {0, 0};

    private Messages() {
    }

    // BLOCK 3: the parsed frames
    public sealed interface Message
            permits SyncStep1, SyncStep2, Update, Awareness, QueryAwareness, TrakrCheckpoint, TrakrSnapshot, TrakrSeed {
    }

    /** "Here is what I have (a state vector); send me what I'm missing." */
    public record SyncStep1(byte[] stateVector) implements Message {
    }

    /** "Here is what you were missing": an update. Receiving one marks a y-websocket client as synced. */
    public record SyncStep2(byte[] update) implements Message {
    }

    /** A live edit. */
    public record Update(byte[] update) implements Message {
    }

    /** Presence and caret changes: an awareness update (AwarenessCodec reads its entries). */
    public record Awareness(byte[] update) implements Message {
    }

    /** "Send me everyone's awareness." */
    public record QueryAwareness() implements Message {
    }

    /** Server → one member: "send me a snapshot that includes every update up to this id" (06-collab §4). */
    public record TrakrCheckpoint(long uptoUpdateId) implements Message {
    }

    /** That member's reply: the whole document as one update, plus its plain text and HTML. */
    public record TrakrSnapshot(long uptoUpdateId, byte[] state, String text, String html) implements Message {
    }

    /**
     * Server → one member, when the issue has never been edited collaboratively: "the document is new — fill it from
     * the issue's stored description". Exactly one client gets it, so the old description isn't inserted twice.
     */
    public record TrakrSeed() implements Message {
    }
}
