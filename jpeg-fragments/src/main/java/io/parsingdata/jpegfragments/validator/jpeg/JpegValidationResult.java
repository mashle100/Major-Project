package io.parsingdata.jpegfragments.validator.jpeg;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import io.parsingdata.jpegfragments.ValidationResult;
import io.parsingdata.jpegfragments.Validator;

public class JpegValidationResult extends ValidationResult {

    public static class FragmentRange {
        public final BigInteger start;
        public final BigInteger end;

        public FragmentRange(BigInteger start, BigInteger end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public String toString() {
            return "[" + start + " - " + end + "]";
        }
    }

    public String info;
    public List<BigInteger> allDetectedFragments; // All detected fragment points (for backward compatibility)
    public List<FragmentRange> detectedFragmentRanges; // Fragment ranges (start-end pairs)
    public int totalFragmentsDetected;

    private JpegValidationResult(final boolean completed, final BigInteger offset, final Validator validator) {
        super(completed, offset, validator);
        this.allDetectedFragments = new ArrayList<>();
        this.detectedFragmentRanges = new ArrayList<>();
        this.totalFragmentsDetected = 0;
    }

    public JpegValidationResult(final boolean completed, final BigInteger offset, final Validator validator,
            final String info) {
        this(completed, offset, validator);
        this.info = info;
    }

    public JpegValidationResult(final boolean completed, final BigInteger offset, final Validator validator,
            final String info, List<BigInteger> allFragments) {
        this(completed, offset, validator);
        this.info = info;
        this.allDetectedFragments = allFragments != null ? new ArrayList<>(allFragments) : new ArrayList<>();

        // Convert list of offsets to ranges (pairs of start-end)
        if (allFragments != null && allFragments.size() >= 2) {
            for (int i = 0; i < allFragments.size() - 1; i += 2) {
                detectedFragmentRanges.add(new FragmentRange(allFragments.get(i), allFragments.get(i + 1)));
            }
        }
        this.totalFragmentsDetected = this.detectedFragmentRanges.size();
    }

    @Override
    public String toString() {
        return super.toString() + " info: " + info + " fragments detected: " + totalFragmentsDetected;
    }
}
