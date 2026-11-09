package dev.trakr.workers.kafka;

/**
 * An envelope whose {@code type} this version of the workers doesn't know. Retrying can't help — the
 * same bytes fail the same way — so the error handler sends it straight to the dead-letter topic.
 */
public class UnknownEventTypeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnknownEventTypeException(String type) {
        super("unknown event type: " + type);
    }
}
