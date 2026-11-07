package dev.trakr.api.issue;

import dev.trakr.api.shared.web.TrakrException;

/** 409 INVALID_PARENT: the type/parent combination breaks 00-product §4.2. */
public class InvalidParent extends TrakrException.Conflict {

    private static final long serialVersionUID = 1L;

    public InvalidParent(String detail) {
        super("INVALID_PARENT", detail);
    }
}
