// API Configuration
const API_BASE_URL = 'http://localhost:8080/api';
let selectedFiles = [];
let lastAnalyzedFiles = []; // Store filenames of last analyzed images

// DOM Elements
const fileInput = document.getElementById('fileInput');
const uploadArea = document.getElementById('uploadArea');
const fileList = document.getElementById('fileList');
const analyzeBtn = document.getElementById('analyzeBtn');
const reanalyzeBtn = document.getElementById('reanalyzeBtn');
const startBackendBtn = document.getElementById('startBackendBtn');
const checkStatusBtn = document.getElementById('checkStatusBtn');
const fragmentCheckbox = document.getElementById('fragmentCheckbox');
const insertionSizeDropdown = document.getElementById('insertionSizeDropdown');
const resultsSection = document.getElementById('resultsSection');
const resultsContainer = document.getElementById('resultsContainer');
const overallStats = document.getElementById('overallStats');
const loadingOverlay = document.getElementById('loadingOverlay');
const backendStatus = document.getElementById('backendStatus');
const statusDot = document.getElementById('statusDot');
const backendMessage = document.getElementById('backendMessage');

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    checkBackendStatus();
    setupEventListeners();
});

// Event Listeners
function setupEventListeners() {
    // File input change
    fileInput.addEventListener('change', handleFileSelect);

    // Drag and drop
    uploadArea.addEventListener('dragover', handleDragOver);
    uploadArea.addEventListener('dragleave', handleDragLeave);
    uploadArea.addEventListener('drop', handleDrop);

    // Buttons
    analyzeBtn.addEventListener('click', analyzeImages);
    reanalyzeBtn.addEventListener('click', reanalyzeImages);
    startBackendBtn.addEventListener('click', startBackend);
    checkStatusBtn.addEventListener('click', checkBackendStatus);
}

// File handling
function handleFileSelect(e) {
    const files = Array.from(e.target.files);
    addFiles(files);
}

function handleDragOver(e) {
    e.preventDefault();
    uploadArea.classList.add('drag-over');
}

function handleDragLeave(e) {
    e.preventDefault();
    uploadArea.classList.remove('drag-over');
}

function handleDrop(e) {
    e.preventDefault();
    uploadArea.classList.remove('drag-over');
    const files = Array.from(e.dataTransfer.files).filter(f => 
        f.type === 'image/jpeg' || f.name.toLowerCase().endsWith('.jpg') || f.name.toLowerCase().endsWith('.jpeg')
    );
    addFiles(files);
}

function addFiles(files) {
    files.forEach(file => {
        if (!selectedFiles.find(f => f.name === file.name && f.size === file.size)) {
            selectedFiles.push(file);
        }
    });
    updateFileList();
    analyzeBtn.disabled = selectedFiles.length === 0;
}

function updateFileList() {
    if (selectedFiles.length === 0) {
        fileList.innerHTML = '';
        return;
    }

    fileList.innerHTML = selectedFiles.map((file, index) => `
        <div class="file-item">
            <div class="file-name">
                <span>📄</span>
                <span>${file.name}</span>
                <span class="file-size">(${formatFileSize(file.size)})</span>
            </div>
            <button class="remove-file" onclick="removeFile(${index})">Remove</button>
        </div>
    `).join('');
}

function removeFile(index) {
    selectedFiles.splice(index, 1);
    updateFileList();
    analyzeBtn.disabled = selectedFiles.length === 0;
}

function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
}

// Backend control
async function startBackend() {
    showMessage(backendMessage, 'info', 'Starting backend server... Please wait.');
    startBackendBtn.disabled = true;

    // Since we can't start Java from JavaScript, provide instructions
    showMessage(backendMessage, 'info', 
        'To start the backend server, open a terminal in the project directory and run:<br>' +
        '<code style="background: #f0f0f0; padding: 5px 10px; display: block; margin-top: 10px; border-radius: 5px;">mvn spring-boot:run</code><br>' +
        'Or if you have built the JAR:<br>' +
        '<code style="background: #f0f0f0; padding: 5px 10px; display: block; margin-top: 5px; border-radius: 5px;">java -jar target/jpeg-fragments-1.0.0-SNAPSHOT.jar</code>'
    );

    setTimeout(() => {
        checkBackendStatus();
        startBackendBtn.disabled = false;
    }, 2000);
}

async function checkBackendStatus() {
    try {
        const response = await fetch(`${API_BASE_URL}/health`, {
            method: 'GET',
            headers: { 'Accept': 'application/json' }
        });

        if (response.ok) {
            const data = await response.json();
            updateBackendStatus(true, data.service || 'Backend Running');
            showMessage(backendMessage, 'success', '✓ Backend is running and ready!');
        } else {
            updateBackendStatus(false, 'Backend Not Responding');
            showMessage(backendMessage, 'error', '✗ Backend is not responding. Please start the server.');
        }
    } catch (error) {
        updateBackendStatus(false, 'Backend Offline');
        showMessage(backendMessage, 'error', '✗ Cannot connect to backend. Please start the server.');
    }
}

function updateBackendStatus(isOnline, statusText) {
    backendStatus.textContent = statusText;
    statusDot.className = 'status-dot ' + (isOnline ? 'online' : 'offline');
}

// Image analysis
async function analyzeImages() {
    if (selectedFiles.length === 0) {
        alert('Please select at least one image.');
        return;
    }

    showLoading(true);
    resultsSection.style.display = 'none';

    const formData = new FormData();
    selectedFiles.forEach(file => {
        formData.append('files', file);
    });
    formData.append('fragment', fragmentCheckbox.checked);
    
    const insertionSizeValue = insertionSizeDropdown.value;
    formData.append('insertionSize', insertionSizeValue);

    try {
        const response = await fetch(`${API_BASE_URL}/analyze`, {
            method: 'POST',
            body: formData
        });

        if (!response.ok) {
            throw new Error(`Server error: ${response.status}`);
        }

        const data = await response.json();
        console.log('Received data:', data);
        
        if (data.success) {
            console.log('Displaying results for', data.results.length, 'images');
            
            // Store filenames for re-analysis
            lastAnalyzedFiles = selectedFiles.map(f => f.name);
            
            // Show re-analyze button if fragmentation was performed
            if (fragmentCheckbox.checked && lastAnalyzedFiles.length > 0) {
                reanalyzeBtn.style.display = 'inline-block';
                reanalyzeBtn.disabled = false;
            }
            
            displayResults(data);
        } else {
            alert('Analysis failed: ' + (data.error || 'Unknown error'));
        }
    } catch (error) {
        console.error('Analysis error:', error);
        alert('Failed to analyze images. Make sure the backend server is running.\n\nError: ' + error.message);
    } finally {
        showLoading(false);
    }
}

// Re-analyze previously fragmented images
async function reanalyzeImages() {
    if (lastAnalyzedFiles.length === 0) {
        alert('No previously analyzed images to re-analyze.');
        return;
    }

    showLoading(true);
    resultsSection.style.display = 'none';

    try {
        const response = await fetch(`${API_BASE_URL}/reanalyze`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                filenames: lastAnalyzedFiles
            })
        });

        if (!response.ok) {
            throw new Error(`Server error: ${response.status}`);
        }

        const data = await response.json();
        console.log('Received re-analysis data:', data);
        
        if (data.success) {
            console.log('Displaying re-analysis results for', data.results.length, 'images');
            displayResults(data);
        } else {
            alert('Re-analysis failed: ' + (data.error || 'Unknown error'));
        }
    } catch (error) {
        console.error('Re-analysis error:', error);
        alert('Failed to re-analyze images. Make sure the backend server is running.\n\nError: ' + error.message);
    } finally {
        showLoading(false);
    }
}

// Display results
function displayResults(data) {
    console.log('displayResults called with:', data);
    resultsSection.style.display = 'block';

    // Calculate overall statistics
    const results = data.results || [];
    console.log('Processing', results.length, 'results');
    const totalImages = results.length;
    
    // Calculate multi-fragment statistics
    let totalActualFragments = 0;
    let totalDetectedFragments = 0;
    let totalMatchedFragments = 0;
    let firstFragmentAccuracies = { start: [], end: [] };
    let allAccuracies = { start: [], end: [] };
    
    results.forEach(r => {
        if (r.totalFragments) totalActualFragments += r.totalFragments;
        if (r.totalDetectedFragments) totalDetectedFragments += r.totalDetectedFragments;
        if (r.matchedFragments) totalMatchedFragments += r.matchedFragments;
        
        // Collect fragment accuracies for start and end points
        if (r.fragmentComparisons) {
            r.fragmentComparisons.forEach((comp, idx) => {
                if (comp.startAccuracy && comp.startAccuracy !== 'Not Detected') {
                    const startAcc = parseFloat(comp.startAccuracy);
                    allAccuracies.start.push(startAcc);
                    if (idx === 0) firstFragmentAccuracies.start.push(startAcc);
                }
                if (comp.endAccuracy && comp.endAccuracy !== 'Not Detected') {
                    const endAcc = parseFloat(comp.endAccuracy);
                    allAccuracies.end.push(endAcc);
                    if (idx === 0) firstFragmentAccuracies.end.push(endAcc);
                }
            });
        }
    });
    
    const avgFirstStart = firstFragmentAccuracies.start.length > 0
        ? (firstFragmentAccuracies.start.reduce((s, a) => s + a, 0) / firstFragmentAccuracies.start.length).toFixed(2)
        : 'N/A';
    
    const avgFirstEnd = firstFragmentAccuracies.end.length > 0
        ? (firstFragmentAccuracies.end.reduce((s, a) => s + a, 0) / firstFragmentAccuracies.end.length).toFixed(2)
        : 'N/A';
    
    const avgAllStart = allAccuracies.start.length > 0
        ? (allAccuracies.start.reduce((s, a) => s + a, 0) / allAccuracies.start.length).toFixed(2)
        : 'N/A';
    
    const avgAllEnd = allAccuracies.end.length > 0
        ? (allAccuracies.end.reduce((s, a) => s + a, 0) / allAccuracies.end.length).toFixed(2)
        : 'N/A';

    // Display overall stats
    overallStats.innerHTML = `
        <div class="stat-card">
            <div class="stat-value">${totalImages}</div>
            <div class="stat-label">Total Images</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${avgFirstStart}%</div>
            <div class="stat-label">Avg First Fragment Start Accuracy</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${avgFirstEnd}%</div>
            <div class="stat-label">Avg First Fragment End Accuracy</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${avgAllStart}%</div>
            <div class="stat-label">Avg All Starts Accuracy</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${avgAllEnd}%</div>
            <div class="stat-label">Avg All Ends Accuracy</div>
        </div>
    `;

    // Display individual results
    resultsContainer.innerHTML = results.map(result => createResultCard(result)).join('');

    // Scroll to results
    resultsSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function createResultCard(result) {
    const hasError = result.error;

    

    const cardClass = hasError ? 'error' : 'success';

    return `
        <div class="result-card ${cardClass}">
            <div class="result-header">
                <div class="result-filename">📄 ${result.filename}</div>
                }
            </div>
            
            ${hasError ? `
                <div class="detail-item">
                    <div class="detail-label">Error</div>
                    <div class="detail-value" style="color: #dc3545;">${result.error}</div>
                </div>
            ` : `
                <!-- JPEG Size Information -->
                ${result.originalJpegSize && result.outputJpegSize ? `
                    <div class="jpeg-size-info" style="background: #e7f3ff; padding: 15px; border-radius: 8px; margin-bottom: 20px;">
                        <h3 style="margin: 0 0 10px 0; color: #0d6efd; font-size: 16px;">📏 JPEG Size Information</h3>
                        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 15px;">
                            <div>
                                <div style="font-weight: 600; color: #666; margin-bottom: 5px;">Before Fragmentation:</div>
                                <div style="font-size: 18px; font-weight: bold; color: #198754;">0 - ${formatBytes(result.originalJpegSize)}</div>
                                ${result.originalEntropyStart !== undefined && result.originalEntropyEnd !== undefined ? `
                                    <div style="margin-top: 8px; padding: 8px; background: rgba(25, 135, 84, 0.1); border-radius: 4px;">
                                        <div style="font-size: 12px; color: #666; font-weight: 600;">Ground Truth Entropy Region:</div>
                                        <div style="font-size: 14px; color: #198754; font-weight: bold;">[${result.originalEntropyStart.toLocaleString()} - ${result.originalEntropyEnd.toLocaleString()}]</div>
                                        <div style="font-size: 11px; color: #666;">${((result.originalEntropyEnd - result.originalEntropyStart) / 1024).toFixed(2)} KB</div>
                                    </div>
                                ` : ''}
                            </div>
                            <div>
                                <div style="font-weight: 600; color: #666; margin-bottom: 5px;">After Fragmentation:</div>
                                <div style="font-size: 18px; font-weight: bold; color: #0d6efd;">0 - ${formatBytes(result.outputJpegSize)}</div>
                                ${result.originalEntropyStart !== undefined && result.originalEntropyEnd !== undefined && result.totalInsertedBytes ? `
                                    <div style="margin-top: 8px; padding: 8px; background: rgba(13, 110, 253, 0.1); border-radius: 4px;">
                                        <div style="font-size: 12px; color: #666; font-weight: 600;">Expected Entropy Region:</div>
                                        <div style="font-size: 14px; color: #0d6efd; font-weight: bold;">[${result.originalEntropyStart.toLocaleString()} - ${(result.originalEntropyEnd + result.totalInsertedBytes).toLocaleString()}]</div>
                                        <div style="font-size: 11px; color: #666;">${((result.originalEntropyEnd + result.totalInsertedBytes - result.originalEntropyStart) / 1024).toFixed(2)} KB</div>
                                    </div>
                                ` : ''}
                            </div>
                        </div>
                    </div>
                ` : ''}
                
                <!-- Summary Statistics -->
                <div class="fragment-summary">
                    <div class="summary-item">
                        <div class="summary-label">Total Fragments</div>
                        <div class="summary-value">${result.totalFragments || 0}</div>
                    </div>
                    <div class="summary-item">
                        <div class="summary-label">Detected</div>
                        <div class="summary-value">${result.totalDetectedFragments || 0}</div>
                    </div>
                    <div class="summary-item">
                        <div class="summary-label">Matched</div>
                        <div class="summary-value">${result.matchedFragments || 0}</div>
                    </div>
                    <div class="summary-item">
                        <div class="summary-label">Total Inserted</div>
                        <div class="summary-value">${result.totalInsertedBytes ? formatBytes(result.totalInsertedBytes) : 'N/A'}</div>
                    </div>
                </div>
                
                ${result.fragmentDetails && result.fragmentDetails.length > 0 ? `
                    <div style="margin: 15px 0; padding: 12px; background: #f8f9fa; border-left: 4px solid #6c757d; border-radius: 4px;">
                        <div style="font-size: 13px; color: #495057;">
                            <strong>🎲 Fragmentation Method:</strong> 
                            ${result.totalFragments - 1} insertion points with evenly-spaced distribution (±20% variance). 
                            Each insertion: <strong>200-900 bytes</strong> of random noise. 
                            <br><strong>🔍 Detection Method:</strong> 
                            Rule-based JPEG decoder scans entropy bitstream, finding continuous regions that satisfy 
                            Huffman decoding, RLE bounds, and MCU structure requirements.
                        </div>
                    </div>
                ` : ''}
                
                <!-- Created Fragments: Before and After -->
                ${result.fragmentDetails && result.fragmentDetails.length > 0 ? `
                    <div class="fragment-details-section">
                        <h3 class="section-title">📍 Ground Truth Fragment Boundaries</h3>
                        <div style="margin: 10px 0; padding: 12px; background: #e7f3ff; border-left: 4px solid #0d6efd; border-radius: 4px;">
                            <div style="font-size: 13px; color: #084298; line-height: 1.6;">
                                <strong>📊 Fragment Structure:</strong> ${result.fragmentDetails.length} segments created by inserting 
                                random noise (200-900 bytes) at evenly-spaced positions within the entropy region 
                                [${result.originalEntropyStart ? result.originalEntropyStart.toLocaleString() : 'N/A'}, 
                                ${result.originalEntropyEnd ? result.originalEntropyEnd.toLocaleString() : 'N/A'}] bytes. 
                                Each segment represents continuous valid JPEG entropy data.
                            </div>
                        </div>
                        <table class="fragments-table">
                            <thead>
                                <tr>
                                    <th rowspan="2">#</th>
                                    <th colspan="2" style="background: rgba(25, 135, 84, 0.1);">Original JPEG (Before) - GROUND TRUTH</th>
                                    <th rowspan="2" style="background: rgba(220, 53, 69, 0.1);">Noise Inserted After Segment</th>
                                    <th colspan="2" style="background: rgba(13, 110, 253, 0.1);">Fragmented Output (After)</th>
                                    <th rowspan="2">Noise Length</th>
                                </tr>
                                <tr>
                                    <th style="background: rgba(25, 135, 84, 0.1);">Start</th>
                                    <th style="background: rgba(25, 135, 84, 0.1);">End</th>
                                    <th style="background: rgba(13, 110, 253, 0.1);">Start</th>
                                    <th style="background: rgba(13, 110, 253, 0.1);">End</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${result.fragmentDetails.map((detail, idx) => {
                                    const isLastSegment = idx === result.fragmentDetails.length - 1;
                                    const hasInsertion = detail.insertionPointInOriginal !== undefined && detail.insertionPointInOriginal >= 0;
                                    return `
                                    <tr ${isLastSegment ? 'style="background: rgba(25, 135, 84, 0.02);"' : ''}>
                                        <td><strong>${detail.fragmentNumber}</strong></td>
                                        <td style="background: rgba(25, 135, 84, 0.05); font-weight: 600;">${formatBytes(detail.originalStartOffset)}</td>
                                        <td style="background: rgba(25, 135, 84, 0.05); font-weight: 600; ${isLastSegment ? 'border: 3px solid #198754; box-shadow: 0 0 5px rgba(25, 135, 84, 0.3);' : ''}">${formatBytes(detail.originalEndOffset)}${isLastSegment ? ' <span style="color: #198754; font-size: 16px;">✓</span>' : ''}</td>
                                        <td style="background: rgba(220, 53, 69, 0.05); ${hasInsertion ? 'color: #dc3545; font-weight: 600;' : 'color: #999; font-style: italic;'}">${hasInsertion ? 'At ' + formatBytes(detail.insertionPointInOriginal) : '(None - Last Segment)'}</td>
                                        <td style="color: #0d6efd; font-weight: 600; background: rgba(13, 110, 253, 0.05);">${formatBytes(detail.outputStartOffset)}</td>
                                        <td style="color: #0d6efd; font-weight: 600; background: rgba(13, 110, 253, 0.05);">${formatBytes(detail.outputEndOffset)}</td>
                                        <td style="${hasInsertion ? 'color: #dc3545; font-weight: 600;' : 'color: #999;'}">${hasInsertion ? formatBytes(detail.insertionLength) : '0 bytes'}</td>
                                    </tr>
                                `;
                                }).join('')}
                            </tbody>
                        </table>
                        <div style="margin-top: 10px; padding: 10px; background: rgba(25, 135, 84, 0.05); border-radius: 4px; font-size: 12px; color: #198754;">
                            <strong>💡 How Fragment Detection Works:</strong> The detector scans the entropy-coded bitstream using JPEG decoding rules. 
                            When it finds data that satisfies Huffman coding, RLE bounds, and MCU structure (4+ consecutive valid MCUs), it marks a 
                            <strong>fragment start</strong>. It continues decoding until a JPEG rule breaks, then marks <strong>fragment end</strong> 
                            at the last valid position. This process repeats to find all valid JPEG sequences in the fragmented file.
                        </div>
                    </div>
                ` : ''}

                
                <!-- Detected Fragments -->
                ${result.detectedFragmentRanges && result.detectedFragmentRanges.length > 0 ? `
                    <div class="fragment-ranges-section">
                        <h3 class="section-title">🎯 Detected Fragment Ranges</h3>
                        <table class="fragments-table">
                            <thead>
                                <tr>
                                    <th>#</th>
                                    <th>Start Offset</th>
                                    <th>End Offset</th>
                                    <th>Range Size</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${result.detectedFragmentRanges.map((range, idx) => `
                                    <tr>
                                        <td><strong>${idx + 1}</strong></td>
                                        <td>${formatBytes(range.start)}</td>
                                        <td>${formatBytes(range.end)}</td>
                                        <td>${formatBytes(range.end - range.start)}</td>
                                    </tr>
                                `).join('')}
                            </tbody>
                        </table>
                    </div>
                ` : ''}
                
                <!-- Fragment Comparison and Accuracy -->
                ${result.fragmentComparisons && result.fragmentComparisons.length > 0 ? `
                    <div class="fragments-table-container">
                        <h3 class="table-title">📊 Fragment Detection Accuracy Analysis</h3>
                        <table class="fragments-table">
                            <thead>
                                <tr>
                                    <th rowspan="2">#</th>
                                    <th colspan="2">Actual Boundaries</th>
                                    <th colspan="2">Detected Boundaries</th>
                                    <th colspan="2">Accuracy</th>
                                </tr>
                                <tr>
                                    <th>Start</th>
                                    <th>End</th>
                                    <th>Start</th>
                                    <th>End</th>
                                    <th>Start</th>
                                    <th>End</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${result.fragmentComparisons.map(comp => {
                                    const startAcc = comp.startAccuracy !== 'Not Detected' ? parseFloat(comp.startAccuracy) : 0;
                                    const endAcc = comp.endAccuracy !== 'Not Detected' ? parseFloat(comp.endAccuracy) : 0;
                                    
                                    let startClass = 'low';
                                    if (startAcc >= 95) startClass = 'high';
                                    else if (startAcc >= 85) startClass = 'medium';
                                    
                                    let endClass = 'low';
                                    if (endAcc >= 95) endClass = 'high';
                                    else if (endAcc >= 85) endClass = 'medium';
                                    
                                    return `
                                        <tr>
                                            <td><strong>${comp.actualFragmentNumber}</strong></td>
                                            <td>${formatBytes(comp.actualStartOffset || 0)}</td>
                                            <td>${formatBytes(comp.actualEndOffset || 0)}</td>
                                            <td>${comp.detectedStartOffset !== null ? formatBytes(comp.detectedStartOffset) : '<span style="color: #dc3545;">Not Detected</span>'}</td>
                                            <td>${comp.detectedEndOffset !== null ? formatBytes(comp.detectedEndOffset) : '<span style="color: #dc3545;">Not Detected</span>'}</td>
                                            <td><span class="accuracy-cell ${startClass}">${comp.startAccuracy || 'N/A'}</span></td>
                                            <td><span class="accuracy-cell ${endClass}">${comp.endAccuracy || 'N/A'}</span></td>
                                        </tr>
                                    `;
                                }).join('')}
                            </tbody>
                        </table>
                    </div>
                ` : ''}
                
                ${result.validationMessage ? `
                    <div class="detail-item" style="grid-column: 1 / -1; margin-top: 10px;">
                        <div class="detail-label">Validation Message</div>
                        <div class="detail-value" style="font-size: 0.95em;">${result.validationMessage}</div>
                    </div>
                ` : ''}
            `}
        </div>
    `;
}

function formatBytes(bytes) {
    return `${bytes.toLocaleString()} bytes (${(bytes / 1024).toFixed(2)} KB)`;
}

// Utility functions
function showLoading(show) {
    loadingOverlay.classList.toggle('show', show);
}

function showMessage(element, type, message) {
    element.innerHTML = message;
    element.className = 'message show ' + type;
    
    if (type === 'success') {
        setTimeout(() => {
            element.classList.remove('show');
        }, 5000);
    }
}

// Make removeFile globally accessible
window.removeFile = removeFile;
