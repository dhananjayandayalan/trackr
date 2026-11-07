package dev.trakr.api.workflow;

import dev.trakr.api.shared.web.TrakrException;

/** 409 TRANSITION_NOT_ALLOWED: the workflow has no transition between the two statuses (00-product §4.3). */
public class TransitionNotAllowed extends TrakrException.Conflict {

    private static final long serialVersionUID = 1L;

    public TransitionNotAllowed(String fromName, String toName) {
        super("TRANSITION_NOT_ALLOWED", "The workflow does not allow moving from " + fromName + " to " + toName + ".");
    }
}
