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

        // Sort all fragments to ensure deterministic ordering
        List<BigInteger> sortedFragments = allFragments != null ? new ArrayList<>(allFragments) : new ArrayList<>();
        sortedFragments.sort(BigInteger::compareTo);
        this.allDetectedFragments = sortedFragments;

        // Convert list of offsets to ranges (pairs of start-end)
        List<FragmentRange> rawRanges = new ArrayList<>();
        if (sortedFragments.size() >= 2) {
            for (int i = 0; i < sortedFragments.size() - 1; i += 2) {
                rawRanges.add(new FragmentRange(sortedFragments.get(i), sortedFragments.get(i + 1)));
            }
        }

        // Merge fragments that are very close together (gap < 1KB)
        this.detectedFragmentRanges = mergeCloseFragments(rawRanges, 1024); // 1KB threshold
        this.totalFragmentsDetected = this.detectedFragmentRanges.size();
    }

    /**
     * Merge fragments that are separated by less than the specified gap threshold.
     * This consolidates closely-spaced fragments that are actually part of the same
     * continuous region.
     * 
     * @param ranges List of fragment ranges to merge
     * @param maxGap Maximum gap size (in bytes) to merge across
     * @return List of merged fragment ranges
     */
    private List<FragmentRange> mergeCloseFragments(List<FragmentRange> ranges, int maxGap) {
        if (ranges == null || ranges.isEmpty()) {
            return new ArrayList<>();
        }

        List<FragmentRange> merged = new ArrayList<>();
        FragmentRange current = ranges.get(0);

        for (int i = 1; i < ranges.size(); i++) {
            FragmentRange next = ranges.get(i);
            long gap = next.start.longValue() - current.end.longValue();

            // If gap is less than threshold, merge the fragments
            if (gap < maxGap) {
                // Extend current fragment to include next fragment
                current = new FragmentRange(current.start, next.end);
            } else {
                // Gap is too large, finalize current and start new
                merged.add(current);
                current = next;
            }
        }

        // Add the last fragment
        merged.add(current);

        return merged;
    }

    @Override
    public String toString() {
        return super.toString() + " info: " + info + " fragments detected: " + totalFragmentsDetected;
    }
}
