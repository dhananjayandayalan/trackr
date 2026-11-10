package dev.trakr.collab.protocol;

import java.io.Serial;

/** A frame that isn't valid lib0 / y-protocols: the socket is closed with 1003 (06-collab §3). */
public class ProtocolException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
