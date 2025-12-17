package io.parsingdata.jpegfragments.web;

import io.parsingdata.jpegfragments.Validator;
import io.parsingdata.jpegfragments.validator.jpeg.JpegValidationResult;
import io.parsingdata.jpegfragments.validator.jpeg.JpegValidator;
import io.parsingdata.metal.data.ByteStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class FragmentationController {

    private static final String UPLOAD_DIR = "uploads";
    private static final String OUTPUT_DIR = "fragmented";

    // Store last fragmentation info for re-analysis
    private Map<String, LastFragmentationInfo> lastFragmentations = new HashMap<>();

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> analyzeImages(@RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "fragment", defaultValue = "true") boolean shouldFragment,
            @RequestParam(value = "insertionSize", defaultValue = "0") int insertionSizeKB) {
        String fragmentCount = "3";
        try {
            System.out.println("Received request to analyze " + files.length + " files");

            // Create directories if they don't exist (use absolute paths)
            Path uploadPath = Paths.get(System.getProperty("user.dir"), UPLOAD_DIR).toAbsolutePath();
            Path outputPath = Paths.get(System.getProperty("user.dir"), OUTPUT_DIR).toAbsolutePath();
            Files.createDirectories(uploadPath);
            Files.createDirectories(outputPath);
            System.out.println("Upload directory: " + uploadPath);

            List<Map<String, Object>> results = new ArrayList<>();

            for (MultipartFile file : files) {
                if (file.isEmpty())
                    continue;

                // Save uploaded file
                String originalFilename = file.getOriginalFilename();
                System.out.println("Processing file: " + originalFilename);
                Path originalPath = uploadPath.resolve(originalFilename);
                file.transferTo(originalPath.toFile());

                Map<String, Object> result = new HashMap<>();
                result.put("filename", originalFilename);

                if (shouldFragment) {
                    String baseName = originalFilename.substring(0, originalFilename.lastIndexOf('.'));
                    Path fragmentedPath = outputPath.resolve(baseName + "_fragmented.jpg");

                    try {
                        System.out.println(
                                "Fragmenting image: " + originalFilename + " with 3 fragments");

                        ImageFragmenter.FragmentationInfo fragmentInfo = null;
                        ValidationAnalysisResult validationResult = null;
                        List<Map<String, Object>> fragmentComparisons = null;

                        int depth = 4;
                        int pass = 0;
                        double optimalMetric = 0.0;
                        ImageFragmenter.FragmentationInfo cachedFragInfo = null;
                        ValidationAnalysisResult cachedValidResult = null;
                        List<Map<String, Object>> cachedComparisons = null;

                        while (pass < depth) {
                            pass++;

                            double qualityThreshold = 94.0 - (((pass - 1) / 4) * 5.0);

                            fragmentInfo = ImageFragmenter.fragmentImage(originalPath, fragmentedPath, fragmentCount,
                                    insertionSizeKB);
                            validationResult = validateImage(fragmentedPath);

                            List<ImageFragmenter.FragmentDetail> actualFragments = fragmentInfo.fragments;
                            List<Boolean> matchedIndices = new ArrayList<>();
                            for (int i = 0; i < validationResult.detectedFragmentRanges.size(); i++) {
                                matchedIndices.add(false);
                            }

                            fragmentComparisons = new ArrayList<>();
                            int matchedCount = 0;
                            double aggregatedMetric = 0.0;

                            for (int i = 0; i < actualFragments.size(); i++) {
                                ImageFragmenter.FragmentDetail actualFrag = actualFragments.get(i);
                                Map<String, Object> comparison = new HashMap<>();
                                comparison.put("actualFragmentNumber", i + 1);

                                long actualStart = actualFrag.outputStartOffset;
                                long actualEnd = actualFrag.outputEndOffset;
                                comparison.put("actualStartOffset", actualStart);
                                comparison.put("actualEndOffset", actualEnd);

                                int optimalIndex = -1;
                                long optimalDelta = Long.MAX_VALUE;

                                for (int j = 0; j < validationResult.detectedFragmentRanges.size(); j++) {
                                    if (matchedIndices.get(j))
                                        continue;

                                    Map<String, Long> range = validationResult.detectedFragmentRanges.get(j);
                                    long detectedStart = range.get("start");
                                    long detectedEnd = range.get("end");

                                    long startDiff = Math.abs(actualStart - detectedStart);
                                    long endDiff = Math.abs(actualEnd - detectedEnd);
                                    long delta = startDiff + endDiff;

                                    if (delta < optimalDelta) {
                                        optimalDelta = delta;
                                        optimalIndex = j;
                                    }
                                }

                                if (optimalIndex >= 0) {
                                    matchedIndices.set(optimalIndex, true);
                                    Map<String, Long> matchedRange = validationResult.detectedFragmentRanges
                                            .get(optimalIndex);
                                    long detectedStart = matchedRange.get("start");
                                    long detectedEnd = matchedRange.get("end");

                                    comparison.put("detectedStartOffset", detectedStart);
                                    comparison.put("detectedEndOffset", detectedEnd);

                                    long startDiff = Math.abs(actualStart - detectedStart);
                                    long endDiff = Math.abs(actualEnd - detectedEnd);
                                    comparison.put("startOffsetDifference", startDiff);
                                    comparison.put("endOffsetDifference", endDiff);

                                    double startAccuracy = actualStart > 0
                                            ? Math.max(0, 100.0 - (startDiff / (double) actualStart * 100))
                                            : 100.0;
                                    double endAccuracy = actualEnd > 0
                                            ? Math.max(0, 100.0 - (endDiff / (double) actualEnd * 100))
                                            : 100.0;

                                    comparison.put("startAccuracy", String.format("%.2f%%", startAccuracy));
                                    comparison.put("endAccuracy", String.format("%.2f%%", endAccuracy));

                                    aggregatedMetric += (startAccuracy + endAccuracy) / 2.0;
                                    if (startAccuracy >= qualityThreshold && endAccuracy >= qualityThreshold) {
                                        matchedCount++;
                                    }
                                } else {
                                    comparison.put("detectedStartOffset", null);
                                    comparison.put("detectedEndOffset", null);
                                    comparison.put("startOffsetDifference", null);
                                    comparison.put("endOffsetDifference", null);
                                    comparison.put("startAccuracy", "Not Detected");
                                    comparison.put("endAccuracy", "Not Detected");
                                }

                                fragmentComparisons.add(comparison);
                            }

                            double iterMetric = actualFragments.size() > 0 ? aggregatedMetric / actualFragments.size()
                                    : 0.0;

                            if (matchedCount == actualFragments.size() && actualFragments.size() > 0) {
                                cachedFragInfo = fragmentInfo;
                                cachedValidResult = validationResult;
                                cachedComparisons = fragmentComparisons;
                                break;
                            }

                            if (iterMetric > optimalMetric) {
                                optimalMetric = iterMetric;
                                cachedFragInfo = fragmentInfo;
                                cachedValidResult = validationResult;
                                cachedComparisons = fragmentComparisons;
                            }
                        }

                        fragmentInfo = cachedFragInfo;
                        validationResult = cachedValidResult;
                        fragmentComparisons = cachedComparisons;

                        List<Map<String, Object>> fragmentDetailsList = new ArrayList<>();
                        for (ImageFragmenter.FragmentDetail detail : fragmentInfo.fragments) {
                            Map<String, Object> fragDetail = new HashMap<>();
                            fragDetail.put("fragmentNumber", detail.fragmentNumber);
                            fragDetail.put("originalStartOffset", detail.originalStartOffset);
                            fragDetail.put("originalEndOffset", detail.originalEndOffset);
                            fragDetail.put("outputStartOffset", detail.outputStartOffset);
                            fragDetail.put("outputEndOffset", detail.outputEndOffset);
                            fragDetail.put("insertionOffset", detail.insertionOffset);
                            fragDetail.put("insertionLength", detail.insertionLength);
                            fragDetail.put("insertionPointInOriginal", detail.insertionPointInOriginal);
                            fragmentDetailsList.add(fragDetail);
                        }

                        result.put("totalFragments", fragmentInfo.fragments.size());
                        result.put("fragmentDetails", fragmentDetailsList);
                        result.put("allFragmentPoints", fragmentInfo.getAllFragmentPoints());
                        result.put("firstFragmentPoint", fragmentInfo.getFirstFragmentPoint());
                        result.put("totalInsertedBytes", fragmentInfo.totalInsertedBytes);
                        result.put("originalJpegSize", fragmentInfo.originalSize);
                        result.put("outputJpegSize", fragmentInfo.outputSize);
                        result.put("originalEntropyStart", fragmentInfo.originalEntropyStart);
                        result.put("originalEntropyEnd", fragmentInfo.originalEntropyEnd);
                        result.put("fragmentedImage", fragmentedPath.toString());

                        System.out.println("Created " + fragmentInfo.fragments.size() + " fragments");
                        System.out.println("Fragment points: " + fragmentInfo.getAllFragmentPoints());

                        result.put("detectedFragmentPoint", validationResult.detectedOffset);
                        result.put("allDetectedFragments", validationResult.allDetectedOffsets);
                        result.put("detectedFragmentRanges", validationResult.detectedFragmentRanges);
                        result.put("totalDetectedFragments", validationResult.detectedFragmentRanges.size());
                        result.put("validationCompleted", validationResult.completed);
                        result.put("validationMessage", validationResult.message);

                        System.out.println(
                                "Total fragment ranges detected: " + validationResult.detectedFragmentRanges.size());
                        System.out.println("Detected ranges: " + validationResult.detectedFragmentRanges);

                        result.put("fragmentComparisons", fragmentComparisons);

                        int matchedFragments = 0;
                        for (Map<String, Object> comp : fragmentComparisons) {
                            if (comp.get("detectedStartOffset") != null) {
                                Long startDiff = (Long) comp.get("startOffsetDifference");
                                Long endDiff = (Long) comp.get("endOffsetDifference");
                                if (startDiff != null && endDiff != null && startDiff < 500 && endDiff < 500) {
                                    matchedFragments++;
                                }
                            }
                        }

                        double detectionRate = fragmentInfo.fragments.size() > 0
                                ? (matchedFragments / (double) fragmentInfo.fragments.size()) * 100.0
                                : 0.0;
                        result.put("detectionRate", String.format("%.2f%%", detectionRate));
                        result.put("matchedFragments", matchedFragments);

                        LastFragmentationInfo lastInfo = new LastFragmentationInfo();
                        lastInfo.fragmentedPath = fragmentedPath;
                        lastInfo.fragmentInfo = fragmentInfo;
                        lastFragmentations.put(originalFilename, lastInfo);

                    } catch (Exception e) {
                        System.err.println("Error during fragmentation/validation: " + e.getMessage());
                        e.printStackTrace();
                        result.put("error", "Fragmentation/validation failed: " + e.getMessage());
                    }
                } else {
                    // Just validate the original image
                    System.out.println("Validating original image: " + originalFilename);
                    ValidationAnalysisResult validationResult = validateImage(originalPath);
                    System.out.println("Validation completed: " + validationResult.completed);
                    result.put("detectedFragmentPoint", validationResult.detectedOffset);
                    result.put("allDetectedFragments", validationResult.allDetectedOffsets);
                    result.put("detectedFragmentRanges", validationResult.detectedFragmentRanges);
                    result.put("totalDetectedFragments", validationResult.detectedFragmentRanges.size());
                    result.put("validationCompleted", validationResult.completed);
                    result.put("validationMessage", validationResult.message);
                    result.put("isValid", validationResult.completed);
                }

                System.out.println("Finished processing: " + originalFilename);
                System.out.println("Result data: " + result);

                results.add(result);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("totalImages", files.length);
            response.put("results", results);

            System.out.println("Sending response with " + results.size() + " results");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            System.err.println("Error in analyzeImages: " + e.getMessage());
            e.printStackTrace();
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            errorResponse.put("errorType", e.getClass().getName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/fragment-only")
    public ResponseEntity<?> fragmentImages(@RequestParam("files") MultipartFile[] files) {
        try {
            Path uploadPath = Paths.get(System.getProperty("user.dir"), UPLOAD_DIR).toAbsolutePath();
            Path outputPath = Paths.get(System.getProperty("user.dir"), OUTPUT_DIR).toAbsolutePath();
            Files.createDirectories(uploadPath);
            Files.createDirectories(outputPath);

            List<Map<String, Object>> results = new ArrayList<>();

            for (MultipartFile file : files) {
                if (file.isEmpty())
                    continue;

                String originalFilename = file.getOriginalFilename();
                Path originalPath = uploadPath.resolve(originalFilename);
                file.transferTo(originalPath.toFile());

                String baseName = originalFilename.substring(0, originalFilename.lastIndexOf('.'));
                Path fragmentedPath = outputPath.resolve(baseName + "_fragmented.jpg");

                try {
                    ImageFragmenter.FragmentationInfo fragmentInfo = ImageFragmenter.fragmentImage(originalPath,
                            fragmentedPath, "3", 4);

                    List<Map<String, Object>> fragmentDetailsList = new ArrayList<>();
                    for (ImageFragmenter.FragmentDetail detail : fragmentInfo.fragments) {
                        Map<String, Object> fragDetail = new HashMap<>();
                        fragDetail.put("fragmentNumber", detail.fragmentNumber);
                        fragDetail.put("originalStartOffset", detail.originalStartOffset);
                        fragDetail.put("originalEndOffset", detail.originalEndOffset);
                        fragDetail.put("outputStartOffset", detail.outputStartOffset);
                        fragDetail.put("outputEndOffset", detail.outputEndOffset);
                        fragDetail.put("insertionOffset", detail.insertionOffset);
                        fragDetail.put("insertionLength", detail.insertionLength);
                        fragmentDetailsList.add(fragDetail);
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("filename", originalFilename);
                    result.put("fragmentedImage", fragmentedPath.toString());
                    result.put("totalFragments", fragmentInfo.fragments.size());
                    result.put("fragmentDetails", fragmentDetailsList);
                    result.put("allFragmentPoints", fragmentInfo.getAllFragmentPoints());
                    result.put("totalInsertedBytes", fragmentInfo.totalInsertedBytes);
                    result.put("success", true);
                    results.add(result);
                } catch (IOException e) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("filename", originalFilename);
                    result.put("success", false);
                    result.put("error", e.getMessage());
                    results.add(result);
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("results", results);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/reanalyze")
    public ResponseEntity<?> reanalyzeImages(@RequestBody Map<String, List<String>> request) {
        try {
            List<String> filenames = request.get("filenames");
            if (filenames == null || filenames.isEmpty()) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("error", "No filenames provided");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            System.out.println("Re-analyzing " + filenames.size() + " previously fragmented images");

            List<Map<String, Object>> results = new ArrayList<>();

            for (String filename : filenames) {
                LastFragmentationInfo lastInfo = lastFragmentations.get(filename);

                if (lastInfo == null) {
                    Map<String, Object> errorResult = new HashMap<>();
                    errorResult.put("filename", filename);
                    errorResult.put("error", "No previous fragmentation found for this file");
                    results.add(errorResult);
                    continue;
                }

                Map<String, Object> result = new HashMap<>();
                result.put("filename", filename);

                try {
                    ImageFragmenter.FragmentationInfo fragmentInfo = lastInfo.fragmentInfo;
                    Path fragmentedPath = lastInfo.fragmentedPath;

                    // Build fragment details for response
                    List<Map<String, Object>> fragmentDetailsList = new ArrayList<>();
                    for (ImageFragmenter.FragmentDetail detail : fragmentInfo.fragments) {
                        Map<String, Object> fragDetail = new HashMap<>();
                        fragDetail.put("fragmentNumber", detail.fragmentNumber);
                        fragDetail.put("originalStartOffset", detail.originalStartOffset);
                        fragDetail.put("originalEndOffset", detail.originalEndOffset);
                        fragDetail.put("outputStartOffset", detail.outputStartOffset);
                        fragDetail.put("outputEndOffset", detail.outputEndOffset);
                        fragDetail.put("insertionOffset", detail.insertionOffset);
                        fragDetail.put("insertionLength", detail.insertionLength);
                        fragDetail.put("insertionPointInOriginal", detail.insertionPointInOriginal);
                        fragmentDetailsList.add(fragDetail);
                    }

                    result.put("totalFragments", fragmentInfo.fragments.size());
                    result.put("fragmentDetails", fragmentDetailsList);
                    result.put("allFragmentPoints", fragmentInfo.getAllFragmentPoints());
                    result.put("firstFragmentPoint", fragmentInfo.getFirstFragmentPoint());
                    result.put("totalInsertedBytes", fragmentInfo.totalInsertedBytes);
                    result.put("originalJpegSize", fragmentInfo.originalSize);
                    result.put("outputJpegSize", fragmentInfo.outputSize);
                    result.put("originalEntropyStart", fragmentInfo.originalEntropyStart);
                    result.put("originalEntropyEnd", fragmentInfo.originalEntropyEnd);
                    result.put("fragmentedImage", fragmentedPath.toString());

                    System.out.println("Re-validating: " + fragmentedPath.getFileName());
                    long startTime = System.currentTimeMillis();
                    ValidationAnalysisResult validationResult = validateImage(fragmentedPath);
                    long endTime = System.currentTimeMillis();
                    System.out.println("Re-validation completed in " + (endTime - startTime) + "ms");

                    result.put("detectedFragmentPoint", validationResult.detectedOffset);
                    result.put("allDetectedFragments", validationResult.allDetectedOffsets);
                    result.put("detectedFragmentRanges", validationResult.detectedFragmentRanges);
                    result.put("totalDetectedFragments", validationResult.detectedFragmentRanges.size());
                    result.put("validationCompleted", validationResult.completed);
                    result.put("validationMessage", validationResult.message);

                    // ONE-TO-ONE MATCHING
                    List<Map<String, Object>> fragmentComparisons = new ArrayList<>();
                    List<ImageFragmenter.FragmentDetail> actualFragments = fragmentInfo.fragments;
                    List<Boolean> detectedUsed = new ArrayList<>();
                    for (int i = 0; i < validationResult.detectedFragmentRanges.size(); i++) {
                        detectedUsed.add(false);
                    }

                    for (int i = 0; i < actualFragments.size(); i++) {
                        ImageFragmenter.FragmentDetail actualFrag = actualFragments.get(i);
                        Map<String, Object> comparison = new HashMap<>();
                        comparison.put("actualFragmentNumber", i + 1);

                        long actualStart = actualFrag.outputStartOffset;
                        long actualEnd = actualFrag.outputEndOffset;
                        comparison.put("actualStartOffset", actualStart);
                        comparison.put("actualEndOffset", actualEnd);

                        int bestDetectedIndex = -1;
                        long bestScore = Long.MAX_VALUE;

                        for (int j = 0; j < validationResult.detectedFragmentRanges.size(); j++) {
                            if (detectedUsed.get(j))
                                continue;

                            Map<String, Long> range = validationResult.detectedFragmentRanges.get(j);
                            long detectedStart = range.get("start");
                            long detectedEnd = range.get("end");

                            long startDiff = Math.abs(actualStart - detectedStart);
                            long endDiff = Math.abs(actualEnd - detectedEnd);
                            long score = startDiff + endDiff;

                            if (score < bestScore) {
                                bestScore = score;
                                bestDetectedIndex = j;
                            }
                        }

                        if (bestDetectedIndex >= 0) {
                            detectedUsed.set(bestDetectedIndex, true);
                            Map<String, Long> matchedRange = validationResult.detectedFragmentRanges
                                    .get(bestDetectedIndex);
                            long detectedStart = matchedRange.get("start");
                            long detectedEnd = matchedRange.get("end");

                            comparison.put("detectedStartOffset", detectedStart);
                            comparison.put("detectedEndOffset", detectedEnd);

                            long startDiff = Math.abs(actualStart - detectedStart);
                            long endDiff = Math.abs(actualEnd - detectedEnd);
                            comparison.put("startOffsetDifference", startDiff);
                            comparison.put("endOffsetDifference", endDiff);

                            double startAccuracy = actualStart > 0
                                    ? Math.max(0, 100.0 - (startDiff / (double) actualStart * 100))
                                    : 100.0;
                            double endAccuracy = actualEnd > 0
                                    ? Math.max(0, 100.0 - (endDiff / (double) actualEnd * 100))
                                    : 100.0;

                            comparison.put("startAccuracy", String.format("%.2f%%", startAccuracy));
                            comparison.put("endAccuracy", String.format("%.2f%%", endAccuracy));
                        } else {
                            comparison.put("detectedStartOffset", null);
                            comparison.put("detectedEndOffset", null);
                            comparison.put("startOffsetDifference", null);
                            comparison.put("endOffsetDifference", null);
                            comparison.put("startAccuracy", "Not Detected");
                            comparison.put("endAccuracy", "Not Detected");
                        }

                        fragmentComparisons.add(comparison);
                    }

                    result.put("fragmentComparisons", fragmentComparisons);

                    int matchedFragments = 0;
                    for (Map<String, Object> comp : fragmentComparisons) {
                        if (comp.get("detectedStartOffset") != null) {
                            Long startDiff = (Long) comp.get("startOffsetDifference");
                            Long endDiff = (Long) comp.get("endOffsetDifference");
                            if (startDiff != null && endDiff != null && startDiff < 500 && endDiff < 500) {
                                matchedFragments++;
                            }
                        }
                    }

                    double detectionRate = actualFragments.size() > 0
                            ? (matchedFragments / (double) actualFragments.size()) * 100.0
                            : 0.0;
                    result.put("detectionRate", String.format("%.2f%%", detectionRate));
                    result.put("matchedFragments", matchedFragments);

                } catch (Exception e) {
                    System.err.println("Error during re-analysis: " + e.getMessage());
                    e.printStackTrace();
                    result.put("error", "Re-analysis failed: " + e.getMessage());
                }

                results.add(result);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("totalImages", filenames.size());
            response.put("results", results);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            System.err.println("Error in reanalyzeImages: " + e.getMessage());
            e.printStackTrace();
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "running");
        response.put("service", "JPEG Fragmentation Detection");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/test-fragment")
    public ResponseEntity<?> testFragment(@RequestParam("files") MultipartFile[] files) {
        try {
            Path uploadPath = Paths.get(System.getProperty("user.dir"), UPLOAD_DIR).toAbsolutePath();
            Path outputPath = Paths.get(System.getProperty("user.dir"), OUTPUT_DIR).toAbsolutePath();
            Files.createDirectories(uploadPath);
            Files.createDirectories(outputPath);

            List<Map<String, Object>> results = new ArrayList<>();

            for (MultipartFile file : files) {
                if (file.isEmpty())
                    continue;

                String originalFilename = file.getOriginalFilename();
                Path originalPath = uploadPath.resolve(originalFilename);
                file.transferTo(originalPath.toFile());

                String baseName = originalFilename.substring(0, originalFilename.lastIndexOf('.'));
                Path fragmentedPath = outputPath.resolve(baseName + "_fragmented.jpg");

                try {
                    ImageFragmenter.FragmentationInfo fragmentInfo = ImageFragmenter.fragmentImage(originalPath,
                            fragmentedPath, "3", 4);

                    List<Map<String, Object>> fragmentDetailsList = new ArrayList<>();
                    for (ImageFragmenter.FragmentDetail detail : fragmentInfo.fragments) {
                        Map<String, Object> fragDetail = new HashMap<>();
                        fragDetail.put("fragmentNumber", detail.fragmentNumber);
                        fragDetail.put("originalStartOffset", detail.originalStartOffset);
                        fragDetail.put("originalEndOffset", detail.originalEndOffset);
                        fragDetail.put("outputStartOffset", detail.outputStartOffset);
                        fragDetail.put("outputEndOffset", detail.outputEndOffset);
                        fragDetail.put("insertionOffset", detail.insertionOffset);
                        fragDetail.put("insertionLength", detail.insertionLength);
                        fragmentDetailsList.add(fragDetail);
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("filename", originalFilename);
                    result.put("fragmentedImage", fragmentedPath.toString());
                    result.put("totalFragments", fragmentInfo.fragments.size());
                    result.put("fragmentDetails", fragmentDetailsList);
                    result.put("allFragmentPoints", fragmentInfo.getAllFragmentPoints());
                    result.put("totalInsertedBytes", fragmentInfo.totalInsertedBytes);
                    result.put("success", true);
                    result.put("message", "Fragmented successfully without validation");
                    results.add(result);
                } catch (Exception e) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("filename", originalFilename);
                    result.put("success", false);
                    result.put("error", e.getMessage());
                    results.add(result);
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("results", results);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    private ValidationAnalysisResult validateImage(Path imagePath) {
        try {
            File imageFile = imagePath.toFile();
            byte[] imageData = Files.readAllBytes(imagePath);

            JpegValidator validator = new JpegValidator();
            ByteStream byteStream = new ByteStream() {
                @Override
                public boolean isAvailable(java.math.BigInteger offset, java.math.BigInteger length) {
                    return offset.signum() >= 0 &&
                            offset.add(length).compareTo(java.math.BigInteger.valueOf(imageData.length)) <= 0;
                }

                @Override
                public byte[] read(java.math.BigInteger offset, int length) throws IOException {
                    if (!isAvailable(offset, java.math.BigInteger.valueOf(length))) {
                        throw new IOException("Read beyond available data");
                    }
                    byte[] result = new byte[length];
                    System.arraycopy(imageData, offset.intValue(), result, 0, length);
                    return result;
                }
            };

            JpegValidationResult result = (JpegValidationResult) validator.validate(byteStream);

            // Convert BigInteger list to Long list
            List<Long> detectedOffsets = new ArrayList<>();
            if (result.allDetectedFragments != null) {
                for (java.math.BigInteger offset : result.allDetectedFragments) {
                    detectedOffsets.add(offset.longValue());
                }
            }

            // Convert fragment ranges
            List<Map<String, Long>> fragmentRanges = new ArrayList<>();
            if (result.detectedFragmentRanges != null) {
                for (JpegValidationResult.FragmentRange range : result.detectedFragmentRanges) {
                    Map<String, Long> rangeMap = new HashMap<>();
                    rangeMap.put("start", range.start.longValue());
                    rangeMap.put("end", range.end.longValue());
                    fragmentRanges.add(rangeMap);
                }
            }

            return new ValidationAnalysisResult(
                    result.completed,
                    result.offset.longValue(),
                    detectedOffsets,
                    fragmentRanges,
                    result.toString(),
                    result.info);

        } catch (Exception e) {
            return new ValidationAnalysisResult(false, -1, new ArrayList<>(), new ArrayList<>(),
                    "Validation error: " + e.getMessage(), "Error");
        }
    }

    private static class ValidationAnalysisResult {
        boolean completed;
        long detectedOffset;
        List<Long> allDetectedOffsets; // All detected fragment points
        List<Map<String, Long>> detectedFragmentRanges; // Fragment ranges (start-end)
        String message;
        String phase;

        ValidationAnalysisResult(boolean completed, long detectedOffset, List<Long> allDetectedOffsets,
                List<Map<String, Long>> detectedFragmentRanges, String message, String phase) {
            this.completed = completed;
            this.detectedOffset = detectedOffset;
            this.allDetectedOffsets = allDetectedOffsets != null ? allDetectedOffsets : new ArrayList<>();
            this.detectedFragmentRanges = detectedFragmentRanges != null ? detectedFragmentRanges : new ArrayList<>();
            this.message = message;
            this.phase = phase;
        }
    }

    private static class LastFragmentationInfo {
        Path fragmentedPath;
        ImageFragmenter.FragmentationInfo fragmentInfo;
    }
}
