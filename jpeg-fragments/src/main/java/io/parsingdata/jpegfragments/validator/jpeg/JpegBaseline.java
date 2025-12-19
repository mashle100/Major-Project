package io.parsingdata.jpegfragments.validator.jpeg;

import static io.parsingdata.jpegfragments.validator.jpeg.HuffmanTable.CoefficientType.AC;
import static io.parsingdata.jpegfragments.validator.jpeg.HuffmanTable.CoefficientType.DC;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.DC_AC_TABLE_SELECTOR;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.HEIGHT;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.NUMBER_OF_IMAGE_COMPONENTS_IN_FRAME;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.RESTART_INTERVAL;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.SOS;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.WIDTH;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegValidator.CHANNEL_NAME;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegValidator.listToIntArray;
import static io.parsingdata.metal.Shorthand.last;
import static io.parsingdata.metal.Shorthand.ref;
import static io.parsingdata.metal.Shorthand.rev;
import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;

import java.io.IOException;
import java.math.BigInteger;
import java.util.BitSet;
import java.util.Optional;

import io.parsingdata.metal.data.ByteStream;
import io.parsingdata.metal.data.Environment;
import io.parsingdata.metal.data.ParseState;
import io.parsingdata.metal.data.callback.Callbacks;
import io.parsingdata.metal.encoding.Encoding;

public class JpegBaseline {

    private static String info = "";

    private JpegBaseline() {
    }

    /**
     * Finds the start position of JPEG header (SOI marker).
     * In valid JPEGs, this should be at byte 0, but we detect it explicitly.
     * 
     * @param input ByteStream to search
     * @return Offset where SOI marker (0xFFD8) is found
     */
    private static long findJpegHeaderStart(ByteStream input) throws IOException {
        // Search for SOI marker (0xFFD8) in first 100 bytes
        for (int i = 0; i < 100; i++) {
            if (input.isAvailable(BigInteger.valueOf(i), BigInteger.valueOf(2))) {
                byte[] bytes = input.read(BigInteger.valueOf(i), 2);
                if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
                    System.out.println("Found JPEG SOI (Start of Image) marker at offset: " + i);
                    return i;
                }
            }
        }
        System.out.println("Warning: SOI marker not found in first 100 bytes, assuming offset 0");
        return 0; // Default to 0 if not found
    }

    static JpegValidationResult validateBaselineScan(final JpegValidator validator, final ParseState headerState,
            final ByteStream input) throws IOException {
        final Optional<ParseState> scanResult = SOS
                .parse(new Environment(ParseState.createFromByteStream(input, headerState.offset),
                        Callbacks.create().add(validator), Encoding.DEFAULT_ENCODING));
        if (scanResult.isEmpty()) {
            info = "SOSBlock";
            return new JpegValidationResult(false, validator.reportedOffset, validator, info,
                    validator.detectedFragmentPoints);
        }
        return validateBaselineMcus(validator, headerState, scanResult.get(), input);
    }

    private static JpegValidationResult validateBaselineMcus(final JpegValidator validator,
            final ParseState headerState, final ParseState scanState, final ByteStream input) throws IOException {
        final int height = last(ref(HEIGHT)).evalSingle(headerState, Encoding.DEFAULT_ENCODING).get().asNumeric()
                .intValueExact();
        final int width = last(ref(WIDTH)).evalSingle(headerState, Encoding.DEFAULT_ENCODING).get().asNumeric()
                .intValueExact();
        final int totalChannelCount = rev(ref(NUMBER_OF_IMAGE_COMPONENTS_IN_FRAME)).eval(headerState,
                Encoding.DEFAULT_ENCODING).head.asNumeric().intValueExact();
        final byte samplingFactors = rev(ref("sampling_factors")).eval(headerState, Encoding.DEFAULT_ENCODING).head
                .value()[0];
        final int mcuWidthFactor = totalChannelCount > 1 ? (samplingFactors >> 4) & 0x0F : 1; // If there's only one
                                                                                              // channel, it's
                                                                                              // grayscale, which
                                                                                              // implies no subsampling
        final int mcuWidth = ((width / (8 * mcuWidthFactor)) + (width % (8 * mcuWidthFactor) == 0 ? 0 : 1));
        final int mcuHeightFactor = totalChannelCount > 1 ? samplingFactors & 0x0F : 1; // If there's only one channel,
                                                                                        // it's grayscale, which implies
                                                                                        // no subsampling
        final int mcuHeight = ((height / (8 * mcuHeightFactor)) + (height % (8 * mcuHeightFactor) == 0 ? 0 : 1));
        final int mcuCount = mcuHeight * mcuWidth;
        final int luminanceCountPerMcu = mcuWidthFactor * mcuHeightFactor; // Calculate the ratio of luminance vs.
                                                                           // chrominance values with subsampling
        final int restartInterval = last(ref(RESTART_INTERVAL)).evalSingle(headerState, Encoding.DEFAULT_ENCODING)
                .map(value -> value.asNumeric().intValueExact()).orElse(0);
        validator.reportedOffset = scanState.offset;
        final JpegEntropyCodedBitStream bitStream = new JpegEntropyCodedBitStream(input, validator.reportedOffset, 0);
        final int[] tableSelectors = listToIntArray(
                rev(ref(DC_AC_TABLE_SELECTOR)).eval(scanState, Encoding.DEFAULT_ENCODING));

        // FRAGMENT DETECTION: Detect JPEG header start and extend first fragment to
        // include it
        long jpegHeaderStart = findJpegHeaderStart(input);
        long detectionStart = jpegHeaderStart;
        long entropyStart = bitStream.getOffset();

        // Don't limit by MCU count - scan entire file to handle inserted noise
        System.out.println("\n=== Starting Fragment Detection (JPEG Rule-Based) ===");
        System.out.println("JPEG header (SOI) detected at byte: " + jpegHeaderStart);
        System.out.println("Detection starts from byte: " + detectionStart);
        System.out.println(
                "Header region: [" + jpegHeaderStart + " - " + entropyStart + "] (will be included in first fragment)");
        System.out.println("Scanning entire file to detect all fragments (no MCU limit)");

        // STATE MACHINE: OUTSIDE_FRAGMENT ↔ INSIDE_FRAGMENT
        // OUTSIDE_FRAGMENT = scanning for next valid JPEG sequence
        // INSIDE_FRAGMENT = actively decoding valid JPEG data
        boolean insideValidFragment = false; // Start OUTSIDE - need to find first valid sequence
        long fragmentStartOffset = entropyStart; // Start looking from entropy section
        long lastValidMCUBoundary = entropyStart; // Start from entropy section
        int consecutiveValidMCUs = 0;
        int mcusInCurrentFragment = 0;
        long firstConsecutiveValidMCUOffset = 0; // Track actual start of consecutive valid sequence
        boolean isFirstFragment = true; // Track if this is the first detected fragment

        // Configuration thresholds
        final int MIN_MCUS_TO_START_FRAGMENT = 2; // Need 2 consecutive valid MCUs to confirm fragment start (lowered
                                                  // from 4)
        final int MIN_FRAGMENT_LENGTH_BYTES = 1000; // Minimum bytes to consider a valid fragment (lowered from 5120)
        final int MAX_SINGLE_ERROR_RECOVERY = 10240; // Max bytes to scan after single error (increased to 10KB to
                                                     // handle 8KB noise insertions)

        // Scan until we reach actual end of stream or EOI, not based on original MCU
        // count
        int mcuIndex = 0;
        boolean reachedEOI = false;
        while (!reachedEOI) {
            // Check for EOI marker before processing MCU
            try {
                Optional<BitSet> peek = bitStream.peek(16);
                if (peek.isPresent()) {
                    byte[] bytes = peek.get().toByteArray();
                    if (bytes.length >= 2) {
                        int firstByte = bytes[0] & 0xFF;
                        int secondByte = bytes[1] & 0xFF;

                        if (firstByte == 0xFF && secondByte == 0xD9) {
                            long eoiOffset = bitStream.getOffset();
                            System.out.println("EOI marker found at offset " + eoiOffset
                                    + " - treating as fragmentation boundary");

                            // Close current fragment (if any)
                            if (insideValidFragment) {
                                long fragmentLength = lastValidMCUBoundary - fragmentStartOffset;
                                if (fragmentLength >= MIN_FRAGMENT_LENGTH_BYTES) {
                                    System.out.println(
                                            "✓ FRAGMENT END before EOI at offset " + lastValidMCUBoundary +
                                                    " (length: " + fragmentLength + " bytes, " +
                                                    mcusInCurrentFragment + " MCUs decoded)");
                                    validator.detectedFragmentPoints.add(BigInteger.valueOf(fragmentStartOffset));
                                    validator.detectedFragmentPoints.add(BigInteger.valueOf(lastValidMCUBoundary));
                                } else {
                                    System.out.println("  Discarding short fragment (" + fragmentLength + " bytes)");
                                }
                                insideValidFragment = false;
                                mcusInCurrentFragment = 0;
                                consecutiveValidMCUs = 0;
                                firstConsecutiveValidMCUOffset = 0;
                            }

                            // Skip the EOI marker bits (16 bits) so we don't see it again
                            if (bitStream.getBitOffset() > 0) {
                                bitStream.skip(8 - bitStream.getBitOffset());
                            }
                            bitStream.skip(16);

                            // Try to recover / find more fragments after this EOI
                            RecoveryResult recovery = attemptFragmentRecovery(
                                    bitStream, input, validator, mcuIndex, restartInterval,
                                    MAX_SINGLE_ERROR_RECOVERY * 2);
                            if (!recovery.success) {
                                System.out.println("  No more valid JPEG sequences after EOI. Stopping detection.");
                                break; // really done
                            }

                            System.out.println("  Resuming search after EOI at offset " + bitStream.getOffset());
                            // Continue loop in OUTSIDE_FRAGMENT state
                            continue;
                        }
                    }
                }
            } catch (IOException e) {
                // End of stream
                System.out.println("End of stream reached");
                reachedEOI = true;
                break;
            }

            long offsetBeforeMCU = bitStream.getOffset();
            boolean mcuValid = true;

            // JPEG RULE CHECK 1: Validate restart marker if needed
            if (!validateRestartMarker(bitStream, mcuIndex, restartInterval)) {
                mcuValid = false;
            }

            // JPEG RULE CHECK 2: Validate all channels in this MCU
            // This checks: Huffman codes, RLE bounds, block structure
            if (mcuValid) {
                for (int channelIndex = 0; channelIndex < totalChannelCount && mcuValid; channelIndex++) {
                    if (channelIndex == 0) {
                        // Luminance channel
                        for (int luminanceIndex = 0; luminanceIndex < luminanceCountPerMcu
                                && mcuValid; luminanceIndex++) {
                            if (!validateQuantizationArray("Luminance", bitStream,
                                    validator.huffmanTables.get(DC).get(tableSelectors[channelIndex] >>> 4),
                                    validator.huffmanTables.get(AC).get(tableSelectors[channelIndex] & 0x0F),
                                    mcuIndex, mcuWidth)) {
                                mcuValid = false;
                            }
                        }
                    } else {
                        // Chrominance channels
                        if (!validateQuantizationArray(CHANNEL_NAME.get(channelIndex - 1), bitStream,
                                validator.huffmanTables.get(DC).get(tableSelectors[channelIndex] >>> 4),
                                validator.huffmanTables.get(AC).get(tableSelectors[channelIndex] & 0x0F),
                                mcuIndex, mcuWidth)) {
                            mcuValid = false;
                        }
                    }
                }
            }

            // STATE MACHINE: Handle MCU validation result
            if (mcuValid) {
                // ✅ MCU SATISFIES JPEG RULES
                long offsetAfterMCU = bitStream.getOffset();
                lastValidMCUBoundary = offsetAfterMCU;

                // Track the start of consecutive valid sequence
                if (consecutiveValidMCUs == 0) {
                    firstConsecutiveValidMCUOffset = offsetBeforeMCU;
                }
                consecutiveValidMCUs++;

                if (!insideValidFragment) {
                    // STATE: OUTSIDE_FRAGMENT
                    // Check if we have enough consecutive valid MCUs to start a fragment
                    if (consecutiveValidMCUs >= MIN_MCUS_TO_START_FRAGMENT) {
                        // TRANSITION: OUTSIDE_FRAGMENT → INSIDE_FRAGMENT
                        // Found a valid JPEG sequence!

                        // For the FIRST fragment, extend start back to JPEG header start to include
                        // header
                        if (isFirstFragment && entropyStart > jpegHeaderStart) {
                            fragmentStartOffset = jpegHeaderStart; // Start from JPEG header to include SOI and all
                                                                   // header markers
                            System.out.println(
                                    "✓ FRAGMENT START at offset " + jpegHeaderStart + " (including header ["
                                            + jpegHeaderStart + "-" + entropyStart + "])");
                            System.out.println("  Entropy section starts at offset " + firstConsecutiveValidMCUOffset +
                                    " (MCU " + mcuIndex + ", " + consecutiveValidMCUs + " valid MCUs confirmed)");
                            isFirstFragment = false;
                        } else {
                            fragmentStartOffset = firstConsecutiveValidMCUOffset; // Use actual first valid MCU offset
                            System.out.println("✓ FRAGMENT START at offset " + fragmentStartOffset +
                                    " (MCU " + mcuIndex + ", " + consecutiveValidMCUs + " valid MCUs confirmed)");
                        }

                        insideValidFragment = true;
                        mcusInCurrentFragment = consecutiveValidMCUs;
                    }
                } else {
                    // STATE: INSIDE_FRAGMENT
                    // Continue decoding - fill quantization/MCU arrays as normal
                    mcusInCurrentFragment++;
                }

                validator.reportedOffset = BigInteger.valueOf(offsetAfterMCU)
                        .add(bitStream.getBitOffset() > 0 ? ONE : ZERO);

            } else {
                // ❌ JPEG RULE BREAK
                // Invalid Huffman code, RLE overflow, block structure inconsistent, etc.

                if (insideValidFragment) {
                    // STATE: INSIDE_FRAGMENT
                    // Rule broke while inside fragment → end current fragment

                    long fragmentEndOffset = lastValidMCUBoundary; // Last valid position
                    long fragmentLength = fragmentEndOffset - fragmentStartOffset;

                    if (fragmentLength >= MIN_FRAGMENT_LENGTH_BYTES) {
                        // Valid fragment - record it
                        System.out.println("✓ FRAGMENT END at offset " + fragmentEndOffset +
                                " (length: " + fragmentLength + " bytes, " +
                                mcusInCurrentFragment + " MCUs decoded)");
                        System.out.println("  Reason: JPEG rule break at offset " + offsetBeforeMCU);

                        validator.detectedFragmentPoints.add(BigInteger.valueOf(fragmentStartOffset));
                        validator.detectedFragmentPoints.add(BigInteger.valueOf(fragmentEndOffset));
                    } else {
                        System.out.println("  Discarding short fragment (" + fragmentLength + " bytes, " +
                                mcusInCurrentFragment + " MCUs) - below minimum threshold");
                    }

                    // TRANSITION: INSIDE_FRAGMENT → OUTSIDE_FRAGMENT
                    insideValidFragment = false;
                    mcusInCurrentFragment = 0;
                }

                // Reset consecutive counter - need new valid sequence to start next fragment
                consecutiveValidMCUs = 0;
                firstConsecutiveValidMCUOffset = 0;

                // CONTINUE SEARCHING: Scan forward from failure point to find next valid JPEG
                // sequence
                // This implements the "continuing to search for more fragments" requirement
                RecoveryResult recovery = attemptFragmentRecovery(bitStream, input, validator,
                        mcuIndex, restartInterval,
                        MAX_SINGLE_ERROR_RECOVERY);
                if (!recovery.success) {
                    // No more valid JPEG data found - stop
                    System.out.println("  No more valid JPEG sequences found. Stopping detection.");
                    reachedEOI = true;
                    break;
                }

                // Recovery found potential valid data - continue scanning in OUTSIDE_FRAGMENT
                // state
                System.out.println("  Skipped " + (bitStream.getOffset() - offsetBeforeMCU) +
                        " bytes, resuming search at offset " + bitStream.getOffset());
            }

            // Increment MCU counter for restart marker tracking
            mcuIndex++;
        }

        // Handle final fragment if we ended while inside valid data
        if (insideValidFragment) {
            long fragmentLength = lastValidMCUBoundary - fragmentStartOffset;
            if (fragmentLength >= MIN_FRAGMENT_LENGTH_BYTES) {
                System.out.println("✓ FINAL FRAGMENT END at offset " + lastValidMCUBoundary +
                        " (length: " + fragmentLength + " bytes, " +
                        mcusInCurrentFragment + " MCUs decoded)");
                validator.detectedFragmentPoints.add(BigInteger.valueOf(fragmentStartOffset));
                validator.detectedFragmentPoints.add(BigInteger.valueOf(lastValidMCUBoundary));
            } else {
                System.out.println("  Discarding final short fragment (" + fragmentLength + " bytes)");
            }
        }

        // Final validation
        if (validateRestartMarker(bitStream, mcuCount, restartInterval)) {
            validator.reportedOffset = BigInteger.valueOf(bitStream.getOffset())
                    .add(bitStream.getBitOffset() > 0 ? ONE : ZERO);
            info = "";
        }

        int numFragments = validator.detectedFragmentPoints.size() / 2;
        System.out.println("\n=== Fragment Detection Complete ===");
        System.out.println("Detection range: [0 - " + bitStream.getOffset() + "]");
        System.out.println("  - Header fragment: [0 - " + entropyStart + "] (no decoding)");
        System.out.println(
                "  - Entropy fragments: [" + entropyStart + " - " + bitStream.getOffset() + "] (Huffman decoded)");
        System.out.println("Total valid fragments detected: " + numFragments);
        System.out.println("Fragments represent continuous regions satisfying JPEG structure");
        return new JpegValidationResult(true, validator.reportedOffset, validator, info,
                validator.detectedFragmentPoints);
    }

    /**
     * Result of recovery attempt
     */
    private static class RecoveryResult {
        final boolean success;
        final boolean foundValidData;
        final long recoveryOffset;

        RecoveryResult(boolean success, boolean foundValidData, long recoveryOffset) {
            this.success = success;
            this.foundValidData = foundValidData;
            this.recoveryOffset = recoveryOffset;
        }
    }

    /**
     * Fragment recovery: scan forward from failure point to find next valid JPEG
     * sequence
     * Implements "continuing to search for more fragments" requirement
     * 
     * Strategy:
     * - Scan byte-by-byte from current position
     * - Look for JPEG markers (RST, EOI, byte-stuffing patterns)
     * - Try decoding Huffman symbols to confirm valid JPEG data
     * - Return success if valid sequence found, allowing state machine to continue
     */
    private static RecoveryResult attemptFragmentRecovery(JpegEntropyCodedBitStream bitStream, ByteStream input,
            JpegValidator validator, int currentMcu, int restartInterval, int maxScanBytes) throws IOException {
        long startOffset = bitStream.getOffset();

        // Align to byte boundary first
        if (bitStream.getBitOffset() > 0) {
            bitStream.skip(8 - bitStream.getBitOffset());
        }

        System.out.println(
                "  Recovery: Scanning from offset " + bitStream.getOffset() + " for valid JPEG entropy data...");

        // Scan byte-by-byte looking for a position where we can successfully decode
        for (int byteOffset = 0; byteOffset < maxScanBytes; byteOffset++) {
            try {
                long currentOffset = bitStream.getOffset();

                // Strategy 1: Check for JPEG markers first (fastest detection)
                Optional<BitSet> peek = bitStream.peek(16);
                if (peek.isPresent()) {
                    byte[] markerBytes = peek.get().toByteArray();
                    if (markerBytes.length >= 2) {
                        int firstByte = markerBytes[0] & 0xFF;
                        int secondByte = markerBytes[1] & 0xFF;

                        // Check for markers
                        if (firstByte == 0xFF && secondByte != 0x00) {
                            boolean isRestartMarker = (secondByte >= 0xD0 && secondByte <= 0xD7);
                            boolean isEOI = (secondByte == 0xD9);

                            // Restart marker found (if image uses them)
                            if (isRestartMarker && restartInterval > 0) {
                                System.out.println("  Recovery SUCCESS: Restart marker at offset " + currentOffset);
                                return new RecoveryResult(true, true, currentOffset);
                            }

                            // EOI marker - treat as end-of-entropy, not a new start
                            if (isEOI) {
                                System.out.println(
                                        "  Recovery: EOI at offset " + currentOffset + " (end of entropy region)");
                                // Do NOT return success here; EOI is not a valid recovery point
                                return new RecoveryResult(false, false, currentOffset);
                            }
                        }

                        // Strategy 2: For byte-stuffed data, 0xFF00 indicates escaped 0xFF in valid
                        // entropy data
                        // This is a strong signal that we're back in valid JPEG entropy coding
                        if (firstByte == 0xFF && secondByte == 0x00) {
                            System.out.println(
                                    "  Recovery SUCCESS: Found byte-stuffed 0xFF00 at offset " + currentOffset);
                            return new RecoveryResult(true, true, currentOffset);
                        }
                    }
                }

                // Strategy 3: Try to decode Huffman DC symbol at this position
                // If we can decode successfully, we've found valid data
                try {
                    // Attempt to decode a DC coefficient using luminance DC table
                    HuffmanTable dcTable = validator.huffmanTables.get(DC).get(0);
                    if (dcTable != null) {
                        // Try to match a Huffman code at current position
                        Optional<BitSet> testData = bitStream.peek(dcTable.maxCodeLength);
                        if (testData.isPresent()) {
                            Optional<MatchResult> matchResult = dcTable.findShortestMatch(testData.get());
                            if (matchResult.isPresent()) {
                                // Successfully matched a Huffman code!
                                int symbol = matchResult.get().symbol;
                                // Valid DC symbol should be 0-15 (magnitude category)
                                if (symbol >= 0 && symbol <= 15) {
                                    // Check if we can read the magnitude bits too
                                    int bitsNeeded = matchResult.get().bitsMatched + symbol;
                                    Optional<BitSet> fullData = bitStream.peek(bitsNeeded);
                                    if (fullData.isPresent()) {
                                        // Successfully decoded complete DC coefficient!
                                        System.out.println(
                                                "  Recovery SUCCESS: Decoded valid Huffman DC symbol at offset "
                                                        + currentOffset);
                                        return new RecoveryResult(true, true, currentOffset);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Decode failed at this position, continue scanning
                }

                // Move forward one byte and try again
                bitStream.skip(8);

            } catch (IOException e) {
                // Reached end of stream
                System.out.println("  Recovery FAILED: End of stream reached");
                return new RecoveryResult(false, false, bitStream.getOffset());
            }
        }

        System.out.println("  Recovery FAILED: No valid data found within " + maxScanBytes + " bytes");
        return new RecoveryResult(false, false, bitStream.getOffset());
    }

    private static boolean validateRestartMarker(JpegEntropyCodedBitStream input, int mcuIndex, int restartInterval)
            throws IOException {
        if (mcuIndex > 0 && restartInterval > 0 && (mcuIndex % restartInterval) == 0) { // If restartInterval == 0,
                                                                                        // there are no markers.
            if (input.getBitOffset() > 0) { // Align to next byte boundary.
                input.skip(8 - input.getBitOffset());
            }
            final Optional<BitSet> restartMarkerValue = input.peek(16);
            if (restartMarkerValue.isEmpty()) {
                info = "RestartM";
                return false;
            }
            final byte[] restartMarkerConvertedBits = restartMarkerValue.get().toByteArray();
            final byte[] restartMarkerBytes = new byte[2];
            if (restartMarkerConvertedBits.length > 0) {
                restartMarkerBytes[0] = restartMarkerConvertedBits[0];
            }
            if (restartMarkerConvertedBits.length > 1) {
                restartMarkerBytes[1] = restartMarkerConvertedBits[1];
            }
            // Integer.reverse() with >> 24 and & 0xff is used because BitSet provides the
            // bytes in reversed order.
            if (restartMarkerBytes[0] != -1 || (byte) ((Integer.reverse(restartMarkerBytes[1]) >> 24)
                    & 0xff) != (byte) (208 + (((mcuIndex / restartInterval) - 1) % 8) & 0xff)) {
                info = "RestartM";
                return false;
            }
            input.skip(16);
        }
        return true;
    }

    private static boolean validateQuantizationArray(final String channelName, final JpegEntropyCodedBitStream input,
            final HuffmanTable dcTable, final HuffmanTable acTable, final int mcuIndex, final int mcuWidth)
            throws IOException {
        int quantizationArraySize = 0; // This counter will count to 63 as the array fills up.
        final Optional<BitSet> maxDCCodeLengthData = input.peek(dcTable.maxCodeLength);
        if (maxDCCodeLengthData.isEmpty()) {
            info = "EOF";
            return false;
        }
        final Optional<MatchResult> matchDCResult = dcTable.findShortestMatch(maxDCCodeLengthData.get());
        if (matchDCResult.isEmpty()) {
            info = "Huffman-DC; " + channelName;
            return false; // No Huffmancode match found: this is a Huffmantable lookup error.
        }
        // nr. 0: DC, nr. 1 t/m max. 63: AC.
        quantizationArraySize++;
        input.skip(matchDCResult.get().bitsMatched + matchDCResult.get().symbol);
        while (quantizationArraySize < 64) {
            final Optional<BitSet> maxACCodeLengthData = input.peek(acTable.maxCodeLength);
            if (maxACCodeLengthData.isEmpty()) {
                info = "EOF";
                return false;
            }
            final Optional<MatchResult> matchACResult = acTable.findShortestMatch(maxACCodeLengthData.get());
            if (matchACResult.isEmpty()) {
                info = "Huffman-AC; " + channelName;
                return false; // No Huffmancode match found: this is a Huffmantable lookup error.
            } else {
                if (matchACResult.get().symbol == 0) {
                    quantizationArraySize = 64;
                } else {
                    final int higherNibbleValue = (matchACResult.get().symbol & 0x00F0) >> 4;
                    quantizationArraySize += higherNibbleValue;

                    final int lowerNibbleValue = matchACResult.get().symbol & 0x000F;
                    quantizationArraySize++;
                    if (quantizationArraySize > 64) {
                        info = "QASize; " + channelName;
                        return false; // Quantization Array Size overflow found.
                    }
                    input.skip(lowerNibbleValue);
                }
                input.skip(matchACResult.get().bitsMatched);
            }
        }
        return true;
    }

}
