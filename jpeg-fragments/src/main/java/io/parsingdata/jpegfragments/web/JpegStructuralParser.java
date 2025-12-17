package io.parsingdata.jpegfragments.web;

import java.io.IOException;

/**
 * Deterministic JPEG structural parser that finds the true entropy-coded region
 * based purely on JPEG marker analysis, independent of decoding or
 * fragmentation.
 */
public class JpegStructuralParser {

    /**
     * Result containing the entropy-coded region boundaries
     */
    public static class JpegEntropyRegion {
        public final int entropyStartOffset;
        public final int entropyEndOffset;
        public final boolean valid;
        public final String errorMessage;

        public JpegEntropyRegion(int entropyStartOffset, int entropyEndOffset, boolean valid, String errorMessage) {
            this.entropyStartOffset = entropyStartOffset;
            this.entropyEndOffset = entropyEndOffset;
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        @Override
        public String toString() {
            if (valid) {
                return String.format("Entropy region: [%d - %d] (%d bytes)",
                        entropyStartOffset, entropyEndOffset, entropyEndOffset - entropyStartOffset);
            } else {
                return "Invalid JPEG: " + errorMessage;
            }
        }
    }

    /**
     * Find the entropy-coded region of a JPEG by structural marker analysis.
     * This is deterministic and does not depend on decoding or fragmentation.
     * 
     * @param jpegData The complete JPEG file data
     * @return JpegEntropyRegion containing the start and end offsets
     */
    public static JpegEntropyRegion findEntropyRegion(byte[] jpegData) {
        try {
            // Validate minimum size
            if (jpegData == null || jpegData.length < 4) {
                return new JpegEntropyRegion(0, 0, false, "File too small to be a valid JPEG");
            }

            // Validate SOI marker (0xFF 0xD8)
            if ((jpegData[0] & 0xFF) != 0xFF || (jpegData[1] & 0xFF) != 0xD8) {
                return new JpegEntropyRegion(0, 0, false, "Missing SOI marker (0xFF 0xD8)");
            }

            int offset = 2; // Start after SOI
            int firstEntropyStart = -1;
            int lastEntropyEnd = -1;

            // Parse JPEG markers sequentially
            while (offset < jpegData.length - 1) {
                // Look for marker prefix (0xFF)
                if ((jpegData[offset] & 0xFF) != 0xFF) {
                    return new JpegEntropyRegion(0, 0, false,
                            String.format("Expected marker at offset %d, found 0x%02X", offset,
                                    jpegData[offset] & 0xFF));
                }

                byte markerType = jpegData[offset + 1];
                int markerCode = markerType & 0xFF;

                // Check for EOI marker (0xFF 0xD9) - end of image
                if (markerCode == 0xD9) {
                    // Found EOI - entropy ends just before this marker
                    lastEntropyEnd = offset;
                    System.out.println("[Structural Parser] Found EOI at offset " + offset);
                    break;
                }

                // Standalone markers (no length field)
                if (markerCode == 0x00 || (markerCode >= 0xD0 && markerCode <= 0xD8)) {
                    offset += 2;
                    continue;
                }

                // Check for SOS marker (0xFF 0xDA) - Start of Scan
                if (markerCode == 0xDA) {
                    // Read SOS segment length
                    if (offset + 3 >= jpegData.length) {
                        return new JpegEntropyRegion(0, 0, false, "Truncated SOS marker");
                    }

                    int sosLength = ((jpegData[offset + 2] & 0xFF) << 8) | (jpegData[offset + 3] & 0xFF);
                    int sosHeaderEnd = offset + 2 + sosLength;

                    if (sosHeaderEnd > jpegData.length) {
                        return new JpegEntropyRegion(0, 0, false, "SOS segment extends beyond file");
                    }

                    // Entropy data starts immediately after SOS header
                    int entropyStart = sosHeaderEnd;

                    if (firstEntropyStart == -1) {
                        firstEntropyStart = entropyStart;
                        System.out.println("[Structural Parser] Found first SOS at offset " + offset +
                                ", entropy starts at " + entropyStart);
                    }

                    // Scan through entropy-coded data to find the next marker or EOI
                    offset = sosHeaderEnd;

                    // Skip through entropy-coded data
                    while (offset < jpegData.length - 1) {
                        if ((jpegData[offset] & 0xFF) == 0xFF) {
                            byte nextByte = jpegData[offset + 1];
                            int nextCode = nextByte & 0xFF;

                            // 0xFF 0x00 is byte stuffing - continue
                            if (nextCode == 0x00) {
                                offset += 2;
                                continue;
                            }

                            // RST markers (0xD0-0xD7) are valid within entropy data - continue
                            if (nextCode >= 0xD0 && nextCode <= 0xD7) {
                                offset += 2;
                                continue;
                            }

                            // Any other marker (including EOI, SOS, etc.) ends this scan's entropy data
                            lastEntropyEnd = offset;
                            System.out.println("[Structural Parser] Entropy scan ends at offset " + offset +
                                    " (found marker 0xFF 0x" + String.format("%02X", nextCode) + ")");
                            break;
                        }
                        offset++;
                    }

                    // If we reached end of file without finding a marker
                    if (offset >= jpegData.length - 1) {
                        lastEntropyEnd = jpegData.length;
                        System.out.println("[Structural Parser] Entropy scan reached end of file");
                        break;
                    }

                    continue;
                }

                // All other markers with length field
                if (offset + 3 >= jpegData.length) {
                    return new JpegEntropyRegion(0, 0, false,
                            String.format("Truncated marker 0xFF 0x%02X at offset %d", markerCode, offset));
                }

                int segmentLength = ((jpegData[offset + 2] & 0xFF) << 8) | (jpegData[offset + 3] & 0xFF);

                // Validate segment length
                if (segmentLength < 2) {
                    return new JpegEntropyRegion(0, 0, false,
                            String.format("Invalid segment length %d at offset %d", segmentLength, offset));
                }

                // Skip to next marker
                offset += 2 + segmentLength;
            }

            // Validate we found both start and end
            if (firstEntropyStart == -1) {
                return new JpegEntropyRegion(0, 0, false, "No SOS marker found");
            }

            if (lastEntropyEnd == -1) {
                return new JpegEntropyRegion(0, 0, false, "No EOI marker found");
            }

            // Validate entropy region is reasonable
            if (lastEntropyEnd <= firstEntropyStart) {
                return new JpegEntropyRegion(0, 0, false,
                        String.format("Invalid entropy region: end (%d) <= start (%d)", lastEntropyEnd,
                                firstEntropyStart));
            }

            System.out.println("[Structural Parser] ✓ Valid JPEG entropy region: [" + firstEntropyStart +
                    " - " + lastEntropyEnd + "] = " + (lastEntropyEnd - firstEntropyStart) + " bytes");

            return new JpegEntropyRegion(firstEntropyStart, lastEntropyEnd, true, null);

        } catch (Exception e) {
            return new JpegEntropyRegion(0, 0, false, "Parser error: " + e.getMessage());
        }
    }

    /**
     * Convenience method to validate and get entropy region from a file path
     */
    public static JpegEntropyRegion findEntropyRegion(java.nio.file.Path jpegPath) throws IOException {
        byte[] jpegData = java.nio.file.Files.readAllBytes(jpegPath);
        return findEntropyRegion(jpegData);
    }
}
