package io.sitprep.sitprepapi.routine;

/** A household routine request that broke a rule (unknown field, bad value, foreign ref). Mapped to 400 ROUTINE_INVALID. */
public class RoutineInvalidException extends RuntimeException {
    public RoutineInvalidException(String message) {
        super(message);
    }
}
