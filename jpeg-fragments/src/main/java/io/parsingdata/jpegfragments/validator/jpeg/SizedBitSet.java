package io.parsingdata.jpegfragments.validator.jpeg;

import java.util.BitSet;

public class SizedBitSet {

    public final int length;
    public final BitSet bitSet;

    public SizedBitSet(final int length, final BitSet bitSet) {
        this.length = length;
        this.bitSet = bitSet;
    }
}
