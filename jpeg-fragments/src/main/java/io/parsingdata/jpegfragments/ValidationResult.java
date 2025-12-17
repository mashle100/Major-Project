package io.parsingdata.jpegfragments;

import java.math.BigInteger;

public class ValidationResult {

    public final boolean completed;
    public final BigInteger offset;
    public final Validator validator;

    public ValidationResult(final boolean completed, final BigInteger offset, final Validator validator) {
        this.completed = completed;
        this.offset = offset;
        this.validator = validator;
    }

    @Override
    public String toString() {
        return (completed ? "Success! Full size: " : "Error! At offset: ") + offset + " by " + validator;
    }
}
