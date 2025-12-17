package io.parsingdata.jpegfragments.web;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class ImageFragmenter {

    private static final byte[] JPEG_HEADER = { (byte) 0xFF, (byte) 0xD8 };
    private static final byte[] JPEG_FOOTER = { (byte) 0xFF, (byte) 0xD9 };

    /**
     * Fragment a JPEG image with fixed block sizes
     * Pattern: [4KB original] + [xKB noise] + [8KB original] + [xKB noise] +
     * [remaining]
     * 
     * @param originalImagePath Path to the original JPEG image
     * @param outputPath        Path where the fragmented image will be saved
     * @param fragmentCountStr  Number of fragments ("3" for fixed pattern)
     * @param insertionSizeKB   Size of noise insertion in KB (0, 4, or 8)
     * @return FragmentationInfo containing all fragment details for comparison
     */
    public static FragmentationInfo fragmentImage(Path originalImagePath, Path outputPath, String fragmentCountStr,
            int insertionSizeKB)
            throws IOException {
        byte[] imageData = Files.readAllBytes(originalImagePath);
        Random random = new Random();

        System.out.println("=== Multi-Point Random Fragmentation ===");
        System.out.println("Image size: " + imageData.length + " bytes");

        // Use structural parser to find TRUE entropy region (deterministic,
        // marker-based)
        JpegStructuralParser.JpegEntropyRegion entropyRegion = JpegStructuralParser.findEntropyRegion(imageData);

        if (!entropyRegion.valid) {
            throw new IOException("Invalid JPEG structure: " + entropyRegion.errorMessage);
        }

        int headerEnd = entropyRegion.entropyStartOffset;
        int footerStart = entropyRegion.entropyEndOffset;

        System.out.println("TRUE Entropy region (structural): [" + headerEnd + " - " + footerStart + "]");
        System.out.println("Fragmentable region: " + (footerStart - headerEnd) + " bytes");

        if (headerEnd >= footerStart) {
            throw new IOException("Invalid JPEG structure - entropy region is empty");
        }

        // STEP 2: Generate Fixed Block Insertion Points starting from byte 0
        // Pattern: [4KB from start (including header)] + [xKB noise] + [8KB] + [xKB
        // noise] + [remaining]
        // If insertionSize is 0, create only 1 fragment (no insertions)
        List<Integer> insertionPoints = new ArrayList<>();

        int totalFileSize = imageData.length;

        if (insertionSizeKB == 0) {
            // No insertion, single fragment
            System.out.println("\n=== No Fragmentation (insertionSize = 0 KB) ===");
            System.out.println("Pattern: [Single fragment - entire image]");
            System.out.println("Total file size: " + totalFileSize + " bytes");
            System.out.println("This will create 1 fragment (no insertions)");
        } else {
            // Create fragmentation with insertions starting from byte 0
            final int FIRST_BLOCK_SIZE = 4 * 1024; // 4KB
            final int SECOND_BLOCK_SIZE = 8 * 1024; // 8KB

            System.out.println("\n=== Generating Fixed Block Insertion Points (from byte 0) ===");
            System.out.println("Pattern: [4KB from start] + [" + insertionSizeKB + "KB noise] + [8KB] + ["
                    + insertionSizeKB + "KB noise] + [remaining]");
            System.out.println("Total file size: " + totalFileSize + " bytes");

            // First insertion point: after 4KB from byte 0
            int firstInsertionPoint = FIRST_BLOCK_SIZE;
            if (firstInsertionPoint < imageData.length) {
                insertionPoints.add(firstInsertionPoint);
                System.out.println("First insertion point (after 4KB from start): " + firstInsertionPoint);
            }

            // Second insertion point: after 4KB + 8KB from byte 0
            int secondInsertionPoint = FIRST_BLOCK_SIZE + SECOND_BLOCK_SIZE;
            if (secondInsertionPoint < imageData.length) {
                insertionPoints.add(secondInsertionPoint);
                System.out.println("Second insertion point (after 12KB from start): " + secondInsertionPoint);
            }

            // Sort insertion points (already in order, but for consistency)
            Collections.sort(insertionPoints);

            System.out.println("Final insertion points: " + insertionPoints);
            System.out.println("This will create " + (insertionPoints.size() + 1) + " fragments");
        }

        // STEP 3-5: Create fragmented image with tracked boundaries (from byte 0)
        FragmentationInfo fragmentInfo = createMultiFragmentImage(imageData, 0, insertionPoints, imageData.length,
                random, insertionSizeKB);

        Files.write(outputPath, fragmentInfo.fragmentedData);

        // Return complete fragmentation information
        return fragmentInfo;
    }

    /**
     * Fragment multiple images
     */
    public static List<FragmentationResult> fragmentImages(List<Path> imagePaths, Path outputDir) throws IOException {
        List<FragmentationResult> results = new ArrayList<>();

        for (int i = 0; i < imagePaths.size(); i++) {
            Path originalPath = imagePaths.get(i);
            String filename = originalPath.getFileName().toString();
            String baseName = filename.substring(0, filename.lastIndexOf('.'));
            Path outputPath = outputDir.resolve(baseName + "_fragmented.jpg");

            try {
                FragmentationInfo fragmentInfo = fragmentImage(originalPath, outputPath, "3", 4);
                results.add(new FragmentationResult(
                        originalPath.toString(),
                        outputPath.toString(),
                        fragmentInfo.getFirstFragmentPoint(),
                        true,
                        null));
            } catch (IOException e) {
                results.add(new FragmentationResult(
                        originalPath.toString(),
                        null,
                        -1,
                        false,
                        e.getMessage()));
            }
        }

        return results;
    }

    private static int findHeaderEnd(byte[] data) {
        // JPEG header is FF D8, look for the end of initial markers
        if (data.length < 2 || data[0] != JPEG_HEADER[0] || data[1] != JPEG_HEADER[1]) {
            return -1;
        }

        // Skip past initial markers to find scan data (Start of Scan marker 0xFFDA)
        int offset = 2;
        while (offset < data.length - 4) {
            if ((data[offset] & 0xFF) == 0xFF) {
                byte marker = data[offset + 1];

                // Start of Scan - this is where image data begins
                if ((marker & 0xFF) == 0xDA) {
                    // Skip SOS header to get to actual scan data
                    if (offset + 3 < data.length) {
                        int sosLength = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
                        int scanStart = offset + 2 + sosLength;
                        System.out.println("Found SOS at offset " + offset + ", scan data starts at " + scanStart);
                        return scanStart;
                    }
                }

                // Standalone markers (no length field)
                if (marker == 0x00 || ((marker & 0xFF) >= 0xD0 && (marker & 0xFF) <= 0xD9)) {
                    offset += 2;
                } else {
                    // Markers with length field
                    if (offset + 3 < data.length) {
                        int length = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
                        offset += 2 + length;
                    } else {
                        break;
                    }
                }
            } else {
                offset++;
            }
        }

        // If we can't find SOS, use a safe default (20% into the file)
        System.out.println("SOS marker not found, using default offset");
        return data.length / 5;
    }

    private static int findFooterStart(byte[] data) {
        // Find FF D9 (End of Image marker) - search from end
        for (int i = data.length - 2; i >= data.length - 1000 && i >= 0; i--) {
            if ((data[i] & 0xFF) == 0xFF && i + 1 < data.length && (data[i + 1] & 0xFF) == 0xD9) {
                System.out.println("Found EOI marker at offset " + i);
                return i;
            }
        }

        // If not found in last 1000 bytes, use a safe default (80% into file)
        System.out.println("EOI marker not found in last 1000 bytes, using default offset");
        return (data.length * 4) / 5;
    }

    private static FragmentationInfo createMultiFragmentImage(byte[] original, int entropyStart,
            List<Integer> insertionPoints, int entropyEnd, Random random, int insertionSizeKB) throws IOException {
        /**
         * Creates fragmented JPEG with proper partition semantics:
         * 
         * GROUND TRUTH PARTITION:
         * - Original segments form a clean partition of [entropyStart, entropyEnd]
         * - Each insertion point splits a segment: [start, insertPos] and [insertPos,
         * end]
         * - Result: N insertion points → N+1 segments (all contiguous, no overlap)
         * 
         * FRAGMENTED OUTPUT MAPPING:
         * - Each output segment = original segment + cumulative inserted bytes
         * - outStart = origStart + totalInsertedBefore(origStart)
         * - outEnd = origEnd + totalInsertedBefore(origEnd)
         */

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<FragmentDetail> fragmentDetails = new ArrayList<>();

        System.out.println("\n=== Creating Multi-Fragment Image (Fixed Block Pattern from byte 0) ===");
        System.out.println("STEP 1: File boundaries:");
        System.out.println("  Start = 0 bytes (including header)");
        System.out.println("  End   = " + entropyEnd + " bytes");
        System.out.println("  Total: " + entropyEnd + " bytes");
        System.out.println("\nSTEP 2: Insertion points (N=" + insertionPoints.size() + "): " + insertionPoints);
        System.out.println("  This creates " + (insertionPoints.size() + 1) + " fragments");
        System.out.println("\nSTEP 3-4: Applying insertions with fixed length [" + insertionSizeKB + " KB]...");

        // Build GROUND TRUTH partition: split [0, entropyEnd] at each insertion point
        List<Integer> partitionBoundaries = new ArrayList<>();
        partitionBoundaries.add(0); // Start from byte 0 (SOI marker)
        partitionBoundaries.addAll(insertionPoints); // Already sorted
        partitionBoundaries.add(entropyEnd);

        // Remove duplicates and ensure sorted
        partitionBoundaries = new ArrayList<>(new java.util.TreeSet<>(partitionBoundaries));

        System.out.println("Partition boundaries: " + partitionBoundaries);

        // Track cumulative insertions for output mapping
        int totalInsertedBytes = 0;
        int currentOutputPosition = 0;

        // Process each segment in the partition
        for (int i = 0; i < partitionBoundaries.size() - 1; i++) {
            int segmentStart = partitionBoundaries.get(i);
            int segmentEnd = partitionBoundaries.get(i + 1);

            // This is a segment of ORIGINAL valid JPEG data
            int segmentSize = segmentEnd - segmentStart;

            // Output positions for this segment (accounting for previous insertions)
            int outputStart = segmentStart + totalInsertedBytes;

            System.out.println("\n--- Segment " + (i + 1) + " ---");
            System.out.println("Original: [" + segmentStart + " - " + segmentEnd + "] (" + segmentSize + " bytes)");

            // Write the original JPEG data for this segment
            if (segmentSize > 0) {
                output.write(original, segmentStart, segmentSize);
                currentOutputPosition += segmentSize;
            }

            int outputEnd = segmentStart + segmentSize + totalInsertedBytes;

            // Check if we need to insert noise AFTER this segment
            // Insertion happens at segmentEnd if segmentEnd is an insertion point (not the
            // final entropyEnd)
            boolean shouldInsert = (i < insertionPoints.size()) && (insertionPoints.get(i).equals(segmentEnd));

            int insertionLength = 0;
            int actualInsertedBytes = 0;

            if (shouldInsert) {
                // STEP 3: Generate noise with fixed length in KB
                insertionLength = insertionSizeKB * 1024; // Convert KB to bytes

                byte[] randomData = new byte[insertionLength];

                // Generate random bytes, regenerating any 0xFF bytes to avoid JPEG markers
                for (int j = 0; j < randomData.length; j++) {
                    byte b = (byte) random.nextInt(256);
                    // Keep regenerating until we get a non-0xFF byte
                    while ((b & 0xFF) == 0xFF) {
                        b = (byte) random.nextInt(256);
                    }
                    randomData[j] = b;
                }

                byte[] finalRandomData = randomData;
                actualInsertedBytes = finalRandomData.length;

                System.out.println("INSERTION at original offset " + segmentEnd +
                        " (output offset " + currentOutputPosition + "): " +
                        insertionLength + " bytes");

                output.write(finalRandomData);
                currentOutputPosition += actualInsertedBytes;
                totalInsertedBytes += actualInsertedBytes;
            }

            // Record fragment detail with GROUND TRUTH boundaries
            FragmentDetail detail = new FragmentDetail(
                    i + 1,
                    segmentStart, // originalStartOffset (GROUND TRUTH)
                    segmentEnd, // originalEndOffset (GROUND TRUTH) - forms partition!
                    outputStart, // outputStartOffset (mapped with cumulative insertions)
                    outputEnd, // outputEndOffset (before noise insertion)
                    outputEnd, // insertionOffset (where noise starts in output)
                    actualInsertedBytes, // insertionLength (actual bytes after stuffing)
                    shouldInsert ? segmentEnd : -1 // insertionPointInOriginal (-1 if no insertion)
            );
            fragmentDetails.add(detail);

            System.out.println("Ground Truth: [" + segmentStart + " - " + segmentEnd + "]");
            System.out.println("Output: [" + outputStart + " - " + outputEnd + "]");
        }

        // Verify partition invariants (starting from byte 0)
        verifyPartitionInvariants(fragmentDetails, 0, entropyEnd, totalInsertedBytes);

        // Write footer (EOI marker and any trailing data) if entropyEnd < file length
        if (entropyEnd < original.length) {
            System.out.println("\nWriting footer [" + entropyEnd + " to " + original.length +
                    "]: " + (original.length - entropyEnd) + " bytes");
            output.write(original, entropyEnd, original.length - entropyEnd);
        }

        byte[] fragmentedImage = output.toByteArray();

        System.out.println("\n=== STEP 5: Fragmentation Summary (All Required Values) ===");
        System.out.println("✓ Fragmentation starts from byte 0 (includes header)");
        System.out.println("✓ jpegLengthBefore: " + original.length + " bytes");
        System.out.println("✓ jpegLengthAfter: " + fragmentedImage.length + " bytes");
        System.out.println(
                "✓ fragmentCount: " + fragmentDetails.size() + " (N+1 where N=" + insertionPoints.size() + ")");
        System.out.println("✓ totalInsertedLength: " + totalInsertedBytes + " bytes");
        System.out.println("✓ originalFragmentRanges: " + fragmentDetails.size() + " ranges");
        System.out
                .println("✓ fragmentedFragmentRanges: " + fragmentDetails.size() + " ranges (with cumulative shifts)");
        System.out.println("✓ insertionPointsOriginal: " + insertionPoints);
        System.out.println("✓ insertionLengths: tracked per fragment");

        return new FragmentationInfo(
                fragmentedImage,
                fragmentDetails,
                0, // Start from byte 0
                entropyEnd, // End of file or footer start
                totalInsertedBytes,
                original.length, // originalSize
                fragmentedImage.length // outputSize
        );
    }

    /**
     * Verifies that fragment details form a valid partition
     * 
     * INVARIANTS:
     * 1. First segment starts at fileStart
     * 2. Last segment ends at fileEnd
     * 3. Segments are contiguous: segment[i].end == segment[i+1].start
     * 4. No overlaps, no gaps
     * 5. Output mapping correct: outputOffset = originalOffset +
     * totalInsertedBefore
     */
    private static void verifyPartitionInvariants(List<FragmentDetail> fragments,
            int fileStart, int fileEnd,
            int totalInsertedBytes) {
        System.out.println("\n=== Verifying Partition Invariants ===");

        if (fragments.isEmpty()) {
            throw new IllegalStateException("No fragments created!");
        }

        // Invariant 1: First segment starts at fileStart
        FragmentDetail first = fragments.get(0);
        if (first.originalStartOffset != fileStart) {
            throw new IllegalStateException(
                    String.format("First fragment should start at fileStart=%d, but starts at %d",
                            fileStart, first.originalStartOffset));
        }
        System.out.println("✓ First segment starts at fileStart=" + fileStart);

        // Invariant 2: Last segment ends at fileEnd
        FragmentDetail last = fragments.get(fragments.size() - 1);
        if (last.originalEndOffset != fileEnd) {
            throw new IllegalStateException(
                    String.format("Last fragment should end at fileEnd=%d, but ends at %d",
                            fileEnd, last.originalEndOffset));
        }
        System.out.println("✓ Last segment ends at fileEnd=" + fileEnd);

        // Invariant 3: Segments are contiguous (no gaps, no overlaps)
        for (int i = 0; i < fragments.size() - 1; i++) {
            FragmentDetail current = fragments.get(i);
            FragmentDetail next = fragments.get(i + 1);

            if (current.originalEndOffset != next.originalStartOffset) {
                throw new IllegalStateException(
                        String.format("Fragments %d and %d are not contiguous: [%d-%d] vs [%d-%d]",
                                i + 1, i + 2,
                                current.originalStartOffset, current.originalEndOffset,
                                next.originalStartOffset, next.originalEndOffset));
            }
        }
        System.out.println("✓ All segments are contiguous (no gaps, no overlaps)");

        // Invariant 4: Coverage - union of all segments equals [fileStart, fileEnd]
        int totalOriginalCoverage = 0;
        for (FragmentDetail frag : fragments) {
            int size = frag.originalEndOffset - frag.originalStartOffset;
            if (size < 0) {
                throw new IllegalStateException(
                        String.format("Fragment %d has negative size: [%d-%d]",
                                frag.fragmentNumber, frag.originalStartOffset, frag.originalEndOffset));
            }
            totalOriginalCoverage += size;
        }

        int expectedCoverage = fileEnd - fileStart;
        if (totalOriginalCoverage != expectedCoverage) {
            throw new IllegalStateException(
                    String.format("Coverage mismatch: fragments cover %d bytes, expected %d bytes",
                            totalOriginalCoverage, expectedCoverage));
        }
        System.out.println("✓ Segments cover exactly [fileStart, fileEnd]: " + expectedCoverage + " bytes");

        // Invariant 5: Output mapping consistency
        int cumulativeInserted = 0;
        for (int i = 0; i < fragments.size(); i++) {
            FragmentDetail frag = fragments.get(i);

            // Check output start mapping
            int expectedOutputStart = frag.originalStartOffset + cumulativeInserted;
            if (frag.outputStartOffset != expectedOutputStart) {
                throw new IllegalStateException(
                        String.format("Fragment %d output start mismatch: expected %d, got %d",
                                frag.fragmentNumber, expectedOutputStart, frag.outputStartOffset));
            }

            // Check output end mapping (before insertion)
            int expectedOutputEnd = frag.originalEndOffset + cumulativeInserted;
            if (frag.outputEndOffset != expectedOutputEnd) {
                throw new IllegalStateException(
                        String.format("Fragment %d output end mismatch: expected %d, got %d",
                                frag.fragmentNumber, expectedOutputEnd, frag.outputEndOffset));
            }

            // Update cumulative for next fragment
            cumulativeInserted += frag.insertionLength;
        }
        System.out.println("✓ Output mapping correct (outputOffset = originalOffset + totalInsertedBefore)");

        System.out.println("=== All Partition Invariants Verified ===\n");
    }

    /**
     * Information about a single fragment
     * IMPORTANT: originalStartOffset and originalEndOffset represent GROUND TRUTH
     * boundaries
     * from the structural JPEG parser. They form a partition of [entropyStart,
     * entropyEnd].
     * The last fragment's originalEndOffset is ALWAYS the true entropy end,
     * regardless of
     * random insertion points.
     */
    public static class FragmentDetail {
        public final int fragmentNumber;
        public final int originalStartOffset; // Ground truth: start in original entropy region
        public final int originalEndOffset; // Ground truth: end in original entropy region (last = entropyEnd)
        public final int outputStartOffset; // Start in fragmented output
        public final int outputEndOffset; // End in fragmented output (before insertion)
        public final int insertionOffset; // Position in fragmented output where insertion occurs
        public final int insertionLength; // Length of random data inserted
        public final int insertionPointInOriginal; // Where noise was inserted in original coordinates

        public FragmentDetail(int fragmentNumber, int originalStartOffset, int originalEndOffset,
                int outputStartOffset, int outputEndOffset, int insertionOffset,
                int insertionLength, int insertionPointInOriginal) {
            this.fragmentNumber = fragmentNumber;
            this.originalStartOffset = originalStartOffset;
            this.originalEndOffset = originalEndOffset;
            this.outputStartOffset = outputStartOffset;
            this.outputEndOffset = outputEndOffset;
            this.insertionOffset = insertionOffset;
            this.insertionLength = insertionLength;
            this.insertionPointInOriginal = insertionPointInOriginal;
        }

        @Override
        public String toString() {
            return String.format(
                    "Fragment %d: [%d-%d] in original (ground truth) -> [%d-%d] in output, insertion at %d (%d bytes)",
                    fragmentNumber, originalStartOffset, originalEndOffset, outputStartOffset, outputEndOffset,
                    insertionOffset, insertionLength);
        }
    }

    /**
     * Complete information about all fragments for comparison
     */
    public static class FragmentationInfo {
        public final byte[] fragmentedData;
        public final List<FragmentDetail> fragments;
        public final int headerEnd; // Original entropy start (ground truth)
        public final int footerStart; // Original entropy end (ground truth)
        public final int totalInsertedBytes;
        public final int originalSize; // Total original JPEG size
        public final int outputSize; // Total fragmented JPEG size
        public final int originalEntropyStart; // Ground truth: entropy start in original
        public final int originalEntropyEnd; // Ground truth: entropy end in original

        public FragmentationInfo(byte[] fragmentedData, List<FragmentDetail> fragments,
                int headerEnd, int footerStart, int totalInsertedBytes,
                int originalSize, int outputSize) {
            this.fragmentedData = fragmentedData;
            this.fragments = fragments;
            this.headerEnd = headerEnd;
            this.footerStart = footerStart;
            this.totalInsertedBytes = totalInsertedBytes;
            this.originalSize = originalSize;
            this.originalEntropyStart = headerEnd; // Ground truth from structural parser
            this.originalEntropyEnd = footerStart; // Ground truth from structural parser
            this.outputSize = outputSize;
        }

        public long getFirstFragmentPoint() {
            return fragments.isEmpty() ? headerEnd : fragments.get(0).originalEndOffset;
        }

        public List<Integer> getAllFragmentPoints() {
            List<Integer> points = new ArrayList<>();
            for (FragmentDetail fragment : fragments) {
                points.add(fragment.originalEndOffset);
            }
            return points;
        }
    }

    public static class FragmentationResult {
        public final String originalPath;
        public final String fragmentedPath;
        public final long actualFragmentPoint;
        public final boolean success;
        public final String errorMessage;

        public FragmentationResult(String originalPath, String fragmentedPath,
                long actualFragmentPoint, boolean success, String errorMessage) {
            this.originalPath = originalPath;
            this.fragmentedPath = fragmentedPath;
            this.actualFragmentPoint = actualFragmentPoint;
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }
}
