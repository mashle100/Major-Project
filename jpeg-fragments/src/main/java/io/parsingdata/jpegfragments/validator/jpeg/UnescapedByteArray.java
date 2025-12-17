package io.parsingdata.jpegfragments.validator.jpeg;

public class UnescapedByteArray {

    public final byte[] byteValues;
    public final int totalLength;

    public UnescapedByteArray(final byte[] byteValues, final int totalLength) {
        this.byteValues = byteValues;
        this.totalLength = totalLength;
    }

}
