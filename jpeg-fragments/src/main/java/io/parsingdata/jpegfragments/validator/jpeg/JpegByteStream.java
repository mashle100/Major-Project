package io.parsingdata.jpegfragments.validator.jpeg;

import static java.math.BigInteger.ONE;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Optional;

import io.parsingdata.metal.data.ByteStream;

public class JpegByteStream {

    private final ByteStream input;
    private int offset;

    public JpegByteStream(final ByteStream input, final int offset) {
        this.input = input;
        this.offset = offset;
    }

    public int getOffset() { return this.offset; }

    public Optional<UnescapedByteArray> peek(final int count) throws IOException {
        final byte[] byteValues = new byte[count];
        byte previousValue = 0;
        if (this.offset > 0) {
            if (!this.input.isAvailable(BigInteger.valueOf(this.offset-1), ONE)) {
                return Optional.empty();
            }
            previousValue = this.input.read(BigInteger.valueOf(this.offset-1), 1)[0];
        }
        int skippedBytes = 0;
        for (int i = 0; i < count + skippedBytes; i++) {
            if (!this.input.isAvailable(BigInteger.valueOf(this.offset+i), ONE)) {
                return Optional.empty();
            }
            byte currentValue = this.input.read(BigInteger.valueOf(this.offset+i), 1)[0];
            if (previousValue == -1 && (currentValue == 0 || currentValue == -1)) { // We found a case of byte stuffing
                skippedBytes++;
                //System.out.println("Encountered byte stuffing at offset " + (getOffset() + i + skippedBytes));
            } else {
                byteValues[i-skippedBytes] = currentValue;
            }
            previousValue = currentValue;
        }
        return Optional.of(new UnescapedByteArray(byteValues, count+skippedBytes));
    }

    public boolean skip(final int count) throws IOException {
        Optional<UnescapedByteArray> dataRead = peek(count);
        if (dataRead.isEmpty()) { return false; }
        final int skipLength = dataRead.get().totalLength;
        if (this.input.isAvailable(BigInteger.valueOf(this.offset), BigInteger.valueOf(skipLength))) {
            this.offset += skipLength;
            return true;
        }
        return false;
    }

}
