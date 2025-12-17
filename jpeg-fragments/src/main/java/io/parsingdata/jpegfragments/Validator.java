package io.parsingdata.jpegfragments;

import java.io.IOException;
import java.math.BigInteger;

import io.parsingdata.metal.data.ByteStream;

public interface Validator {

    ValidationResult validate(ByteStream input) throws IOException;

}
