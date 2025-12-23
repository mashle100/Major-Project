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
    
    // Automatically initialize builder with first file
    if (selectedFiles.length > 0) {
        initializeBuilder(selectedFiles[0]);
    }
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
    
    // Clear builder when file is removed
    if (selectedFiles.length === 0) {
        clearBuilder();
    } else {
        // Reinitialize builder with first remaining file
        initializeBuilder(selectedFiles[0]);
    }
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
                        <div class="summary-label">Total Inserted</div>
                        <div class="summary-value">${result.totalInsertedBytes ? formatBytes(result.totalInsertedBytes) : 'N/A'}</div>
                    </div>
                </div>
                

                
                <!-- Created Fragments: Before and After -->
                ${result.fragmentDetails && result.fragmentDetails.length > 0 ? `
                    <div class="fragment-details-section">
                        <h3 class="section-title">📍 Ground Truth Fragment Boundaries</h3>

                        <table class="fragments-table">
                            <thead>
                                <tr>
                                    <th rowspan="2">#</th>
                                    <th colspan="2" style="background: rgba(25, 135, 84, 0.1);">Original JPEG (Before) - GROUND TRUTH</th>
                                    <th colspan="2" style="background: rgba(13, 110, 253, 0.1);">Fragmented Output (After)</th>
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
                                        <td style="color: #0d6efd; font-weight: 600; background: rgba(13, 110, 253, 0.05);">${formatBytes(detail.outputStartOffset)}</td>
                                        <td style="color: #0d6efd; font-weight: 600; background: rgba(13, 110, 253, 0.05);">${formatBytes(detail.outputEndOffset)}</td>
                                    </tr>
                                `;
                                }).join('')}
                            </tbody>
                        </table>
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
                                ${result.detectedFragmentRanges.map((range, idx) => {
                                    // Check if we have original detected values
                                    const hasOriginalStart = range.originalStart !== undefined && range.originalStart !== null;
                                    const hasOriginalEnd = range.originalEnd !== undefined && range.originalEnd !== null;
                                    const startChanged = hasOriginalStart && range.originalStart !== range.start;
                                    const endChanged = hasOriginalEnd && range.originalEnd !== range.end;
                                    
                                    // Format display values
                                    const startDisplay = formatBytes(range.start);
                                    const endDisplay = formatBytes(range.end);
                                    const originalStartDisplay = hasOriginalStart ? formatBytes(range.originalStart) : '';
                                    const originalEndDisplay = hasOriginalEnd ? formatBytes(range.originalEnd) : '';
                                    
                                    return `
                                    <tr>
                                        <td><strong>${idx + 1}</strong></td>
                                        <td>
                                            <strong>${startDisplay}</strong>
                                            ${startChanged ? `<br><span style="color: #6c757d; font-size: 0.85em;">Raw: ${originalStartDisplay}</span>` : ''}
                                        </td>
                                        <td>
                                            <strong>${endDisplay}</strong>
                                            ${endChanged ? `<br><span style="color: #6c757d; font-size: 0.85em;">Raw: ${originalEndDisplay}</span>` : ''}
                                        </td>
                                        <td>${formatBytes(range.end - range.start)}</td>
                                    </tr>
                                `}).join('')}
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
            `}
        </div>
    `;
}

function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    if (bytes < k) return `${bytes} B`;
    if (bytes < k * k) return `${(bytes / k).toFixed(1)} KB`;
    if (bytes < k * k * k) return `${(bytes / (k * k)).toFixed(1)} MB`;
    return `${(bytes / (k * k * k)).toFixed(1)} GB`;
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

// ========================================
// VISUAL FRAGMENTATION BUILDER
// ========================================

let builderMode = true; // Builder is now the default mode
let currentImageFile = null;
let originalImageSize = 0;
let blockStructure = []; // Array of {type: 'jpeg'|'noise', blockIndex: number, size: number}
let noiseBlockCounter = 0;

// Add builder event listeners
document.addEventListener('DOMContentLoaded', () => {
    const addNoiseBlockBtn = document.getElementById('addNoiseBlockBtn');
    const clearStructureBtn = document.getElementById('clearStructureBtn');
    const applyStructureBtn = document.getElementById('applyStructureBtn');
    const analyzeAgainBtn = document.getElementById('analyzeAgainBtn');
    const targetStructure = document.getElementById('targetStructure');
    
    if (addNoiseBlockBtn) {
        addNoiseBlockBtn.addEventListener('click', addNoiseBlock);
    }
    
    if (clearStructureBtn) {
        clearStructureBtn.addEventListener('click', clearStructure);
    }
    
    if (applyStructureBtn) {
        applyStructureBtn.addEventListener('click', applyStructureAndFragment);
    }
    
    if (analyzeAgainBtn) {
        analyzeAgainBtn.addEventListener('click', analyzeAgain);
    }
    
    if (targetStructure) {
        targetStructure.addEventListener('dragover', handleTargetDragOver);
        targetStructure.addEventListener('dragleave', handleTargetDragLeave);
        targetStructure.addEventListener('drop', handleTargetDrop);
    }
});

// Builder is now always active (default mode)
// Removed toggleBuilder function as simple mode is no longer available

function initializeBuilder(file) {
    currentImageFile = file;
    const reader = new FileReader();
    
    reader.onload = function(e) {
        const arrayBuffer = e.target.result;
        originalImageSize = arrayBuffer.byteLength;
        
        // Calculate number of 4KB blocks
        const blockSize = 4096;
        const numBlocks = Math.ceil(originalImageSize / blockSize);
        
        // Reset and auto-populate structure with all JPEG blocks
        blockStructure = [];
        clearStructure();
        autoPopulateStructure(numBlocks, blockSize);
        
        // Get JPEG structure info from backend
        getJpegStructureInfo(file);
    };
    
    reader.readAsArrayBuffer(file);
}

async function getJpegStructureInfo(file) {
    try {
        const formData = new FormData();
        formData.append('file', file);
        
        const response = await fetch(`${API_BASE_URL}/jpeg-info`, {
            method: 'POST',
            body: formData
        });
        
        const data = await response.json();
        
        if (data.success && data.entropyStart) {
            displayHeaderInfo(data);
        } else {
            console.error('Failed to get JPEG structure info:', data.error);
        }
    } catch (error) {
        console.error('Error fetching JPEG structure info:', error);
    }
}

function displayHeaderInfo(info) {
    // Find or create the header info display element
    let headerInfoDiv = document.getElementById('headerInfo');
    if (!headerInfoDiv) {
        headerInfoDiv = document.createElement('div');
        headerInfoDiv.id = 'headerInfo';
        headerInfoDiv.className = 'header-info-box';
        
        // Insert before the target structure section
        const targetSection = document.querySelector('.target-structure-section');
        if (targetSection) {
            targetSection.parentNode.insertBefore(headerInfoDiv, targetSection);
        }
    }
    
    const headerEndBlock = info.headerEndBlock;
    const safeBlock = info.safeNoiseStartBlock;
    
    headerInfoDiv.innerHTML = `
        <h4>📋 JPEG Structure Information</h4>
        <div class="info-content">
            <div class="info-item">
                <span class="info-label">Header End:</span>
                <span class="info-value">${formatBytes(info.entropyStart)} (Block #${headerEndBlock})</span>
            </div>
            <div class="info-item">
                <span class="info-label">Entropy Region:</span>
                <span class="info-value">${formatBytes(info.entropyStart)} - ${formatBytes(info.entropyEnd)}</span>
            </div>
            <div class="info-item important">
                <span class="info-label">⚠️ Safe Noise Placement:</span>
                <span class="info-value">After Block #${headerEndBlock} (Block ${safeBlock}+)</span>
            </div>
            <div class="info-note">
                💡 Placing noise before Block #${safeBlock} will corrupt the JPEG header and prevent detection!
            </div>
        </div>
    `;
}

function autoPopulateStructure(numBlocks, blockSize) {
    const targetStructure = document.getElementById('targetStructure');
    const placeholder = targetStructure.querySelector('.drop-placeholder');
    if (placeholder) {
        placeholder.remove();
    }
    
    console.log('Auto-populating structure with', numBlocks, 'JPEG blocks');
    
    for (let i = 0; i < numBlocks; i++) {
        const startByte = i * blockSize;
        const endByte = Math.min((i + 1) * blockSize, originalImageSize);
        const actualSize = endByte - startByte;
        
        const block = document.createElement('div');
        block.className = 'block jpeg-block';
        block.draggable = true;
        block.dataset.type = 'jpeg';
        block.dataset.blockIndex = i;
        block.dataset.size = actualSize;
        block.dataset.inStructure = 'true';
        block.innerHTML = `
            <div class="block-label">Block ${i + 1}</div>
            <div class="block-size">${formatBytes(actualSize)}</div>
        `;
        
        // Add drag event handlers for reordering
        block.addEventListener('dragstart', handleStructureBlockDragStart);
        block.addEventListener('dragend', handleStructureBlockDragEnd);
        block.addEventListener('dragover', handleStructureBlockDragOver);
        block.addEventListener('drop', handleStructureBlockDrop);
        
        targetStructure.appendChild(block);
        
        // Update structure array
        blockStructure.push({
            type: 'jpeg',
            blockIndex: i,
            noiseId: null,
            noiseType: null,
            size: actualSize
        });
    }
    
    console.log('Structure populated with', blockStructure.length, 'blocks');
}

function addNoiseBlock() {
    const noiseTypeDropdown = document.getElementById('noiseTypeDropdown');
    const noiseType = noiseTypeDropdown.value;
    const noiseSize = 4096; // Fixed 4KB size
    const container = document.getElementById('noiseBlocks');
    
    // Check if noise type is implemented
    if (noiseType === 'text' || noiseType === 'jpeg') {
        alert(`${noiseType.toUpperCase()} noise type is not yet implemented. Using Random for now.`);
    }
    
    noiseBlockCounter++;
    
    const block = document.createElement('div');
    block.className = 'block noise-block';
    block.draggable = true;
    block.dataset.type = 'noise';
    block.dataset.noiseType = noiseType;
    block.dataset.noiseId = noiseBlockCounter;
    block.dataset.size = noiseSize;
    block.id = `noise-block-${noiseBlockCounter}`; // Add ID for tracking
    block.innerHTML = `
        <div class="block-label">Noise ${noiseBlockCounter} (${noiseType})</div>
        <div class="block-size">${formatBytes(noiseSize)}</div>
    `;
    
    block.addEventListener('dragstart', handleBlockDragStart);
    block.addEventListener('dragend', handleBlockDragEnd);
    
    container.appendChild(block);
}

let draggedSourceBlock = null;

// Performance optimization for large images
let lastDragOverTarget = null;
let lastDragOverPosition = null;
let dragOverThrottleTimeout = null;

function throttle(func, delay) {
    let lastCall = 0;
    return function(...args) {
        const now = Date.now();
        if (now - lastCall >= delay) {
            lastCall = now;
            func(...args);
        }
    };
}

function handleBlockDragStart(e) {
    draggedSourceBlock = {
        type: e.target.dataset.type,
        blockIndex: e.target.dataset.blockIndex || null,
        noiseId: e.target.dataset.noiseId || null,
        noiseType: e.target.dataset.noiseType || 'random',
        size: e.target.dataset.size
    };
    e.dataTransfer.effectAllowed = 'copy';
    e.dataTransfer.setData('text/plain', JSON.stringify(draggedSourceBlock));
    e.target.style.opacity = '0.5';
    
    // Store the dragged block's size for creating space
    window.draggedBlockSize = parseFloat(e.target.dataset.size) || 50;
}

function handleBlockDragEnd(e) {
    e.target.style.opacity = '1';
    draggedSourceBlock = null;
    window.draggedBlockSize = null;
    
    // Clear throttle timeout
    if (dragOverThrottleTimeout) {
        clearTimeout(dragOverThrottleTimeout);
        dragOverThrottleTimeout = null;
    }
    
    clearDropSpace();
}

let draggedStructureBlock = null;

function removeAllDropIndicators() {
    document.querySelectorAll('.drop-indicator').forEach(indicator => indicator.remove());
}

function createDropSpace(block, position, draggedElement) {
    // Performance optimization: Skip if same target and position
    const targetId = block.dataset.blockIndex || block.dataset.noiseId || block.id;
    const cacheKey = `${targetId}-${position}`;
    
    if (lastDragOverTarget === cacheKey) {
        return; // No need to recalculate
    }
    
    lastDragOverTarget = cacheKey;
    
    clearDropSpace();
    
    // Calculate the actual width of the dragged element
    let spaceSize = 100; // Increased default fallback for visibility
    
    if (draggedElement) {
        const rect = draggedElement.getBoundingClientRect();
        spaceSize = rect.width * 0.7; // 70% of the width for better visual effect
    } else if (draggedStructureBlock) {
        const rect = draggedStructureBlock.getBoundingClientRect();
        spaceSize = rect.width * 0.7;
    }
    
    const targetStructure = document.getElementById('targetStructure');
    const allBlocks = Array.from(targetStructure.children);
    const blockIndex = allBlocks.indexOf(block);
    
    // Use CSS class for better performance with many blocks
    if (position === 'before') {
        // Shift the target block and all blocks after it
        for (let i = blockIndex; i < allBlocks.length; i++) {
            allBlocks[i].style.transform = `translateX(${spaceSize}px)`;
            // Only set transition once
            if (!allBlocks[i].style.transition) {
                allBlocks[i].style.transition = 'transform 0.15s ease';
            }
        }
    } else {
        // Shift all blocks after the target
        for (let i = blockIndex + 1; i < allBlocks.length; i++) {
            allBlocks[i].style.transform = `translateX(${spaceSize}px)`;
            if (!allBlocks[i].style.transition) {
                allBlocks[i].style.transition = 'transform 0.15s ease';
            }
        }
    }
}

function clearDropSpace() {
    // Reset cache
    lastDragOverTarget = null;
    
    const targetStructure = document.getElementById('targetStructure');
    if (!targetStructure) return;
    
    const allBlocks = Array.from(targetStructure.children);
    // Clear transforms immediately for responsiveness
    allBlocks.forEach(block => {
        block.style.transform = '';
        block.style.transition = '';
    });
}

function getDropPosition(block, clientX) {
    const rect = block.getBoundingClientRect();
    const midpoint = rect.left + rect.width / 2;
    return clientX < midpoint ? 'before' : 'after';
}

function handleStructureBlockDragStart(e) {
    draggedStructureBlock = e.target;
    e.target.style.opacity = '0.5';
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/html', e.target.innerHTML);
    
    // Store the dragged block's size for creating space
    window.draggedBlockSize = parseFloat(e.target.dataset.size) || 50; // Default to 50px if no size
}

function handleStructureBlockDragEnd(e) {
    e.target.style.opacity = '1';
    
    // Clear throttle timeout
    if (dragOverThrottleTimeout) {
        clearTimeout(dragOverThrottleTimeout);
        dragOverThrottleTimeout = null;
    }
    
    // Clear drop space and reset transforms
    clearDropSpace();
    
    // Clean up any border styles
    const targetStructure = document.getElementById('targetStructure');
    const allBlocks = Array.from(targetStructure.querySelectorAll('.block'));
    allBlocks.forEach(block => {
        block.style.borderTop = '';
        block.style.borderBottom = '';
    });
    
    draggedStructureBlock = null;
    window.draggedBlockSize = null;
}

function handleStructureBlockDragOver(e) {
    if (e.preventDefault) {
        e.preventDefault();
    }
    e.dataTransfer.dropEffect = draggedStructureBlock ? 'move' : 'copy';
    
    const target = e.currentTarget;
    const position = getDropPosition(target, e.clientX);
    
    // Determine which element is being dragged
    let draggedElement = null;
    if (draggedStructureBlock) {
        draggedElement = draggedStructureBlock;
    } else if (draggedSourceBlock) {
        // Find the source element being dragged - try multiple selectors
        if (draggedSourceBlock.noiseId) {
            draggedElement = document.getElementById(`noise-block-${draggedSourceBlock.noiseId}`);
        }
        // Fallback: find by dataset matching
        if (!draggedElement) {
            const noiseBlocks = document.querySelectorAll('[data-noise-id]');
            for (let block of noiseBlocks) {
                if (block.dataset.noiseId === String(draggedSourceBlock.noiseId)) {
                    draggedElement = block;
                    break;
                }
            }
        }
    }
    
    // Create visual space immediately (caching inside prevents redundant updates)
    createDropSpace(target, position, draggedElement);
    
    return false;
}

function handleStructureBlockDrop(e) {
    if (e.stopPropagation) {
        e.stopPropagation();
    }
    if (e.preventDefault) {
        e.preventDefault();
    }
    
    const target = e.currentTarget;
    clearDropSpace();
    
    // Handle reordering existing blocks in structure
    if (draggedStructureBlock && target !== draggedStructureBlock) {
        const targetStructure = document.getElementById('targetStructure');
        const allBlocks = Array.from(targetStructure.children);
        const draggedIndex = allBlocks.indexOf(draggedStructureBlock);
        const targetIndex = allBlocks.indexOf(target);
        
        if (draggedIndex !== -1 && targetIndex !== -1) {
            const position = getDropPosition(target, e.clientX);
            const insertBefore = position === 'before';
            
            // Remove dragged block from array first
            const movedBlock = blockStructure.splice(draggedIndex, 1)[0];
            
            // Calculate new index in the array after removal
            let newArrayIndex;
            if (insertBefore) {
                newArrayIndex = draggedIndex < targetIndex ? targetIndex - 1 : targetIndex;
            } else {
                newArrayIndex = draggedIndex < targetIndex ? targetIndex : targetIndex + 1;
            }
            
            // Update DOM
            if (insertBefore) {
                targetStructure.insertBefore(draggedStructureBlock, target);
            } else {
                targetStructure.insertBefore(draggedStructureBlock, target.nextSibling);
            }
            
            // Insert block at new position in array
            blockStructure.splice(newArrayIndex, 0, movedBlock);
            
            console.log('Reordered: moved from index', draggedIndex, 'to', newArrayIndex);
            console.log('New structure:', blockStructure);
        }
    }
    // Handle inserting new noise block from source
    else if (draggedSourceBlock && draggedSourceBlock.type === 'noise') {
        const targetStructure = document.getElementById('targetStructure');
        const allBlocks = Array.from(targetStructure.children);
        const targetIndex = allBlocks.indexOf(target);
        
        if (targetIndex !== -1) {
            const position = getDropPosition(target, e.clientX);
            const insertIndex = position === 'before' ? targetIndex : targetIndex + 1;
            
            console.log('Inserting noise block at index:', insertIndex);
            addBlockToStructure(draggedSourceBlock, insertIndex);
        }
    }
    
    draggedStructureBlock = null;
    return false;
}

function handleTargetDragOver(e) {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'copy';
    e.currentTarget.classList.add('drag-active');
}

function handleTargetDragLeave(e) {
    e.currentTarget.classList.remove('drag-active');
    clearDropSpace();
}

function handleTargetDrop(e) {
    e.preventDefault();
    e.currentTarget.classList.remove('drag-active');
    clearDropSpace();
    
    // Check if this is a reordering operation
    if (draggedStructureBlock) {
        draggedStructureBlock = null;
        return;
    }
    
    // This is adding a new noise block from source
    try {
        const data = JSON.parse(e.dataTransfer.getData('text/plain'));
        
        // Only allow noise blocks to be added (JPEG blocks are already there)
        if (data.type === 'noise') {
            const targetStructure = document.getElementById('targetStructure');
            const allBlocks = Array.from(targetStructure.children);
            
            // Check if dropped at the very start (before first block)
            if (allBlocks.length > 0) {
                const firstBlock = allBlocks[0];
                const rect = firstBlock.getBoundingClientRect();
                
                // If dropped before the first block, insert at position 0
                if (e.clientX < rect.left) {
                    console.log('Inserting noise block at start (position 0)');
                    addBlockToStructure(data, 0);
                    return;
                }
            }
            
            // Otherwise add at the end (dropped on empty space)
            addBlockToStructure(data);
            console.log('Added noise block at end');
        } else if (data.type === 'jpeg') {
            alert('JPEG blocks are already in the structure. You can only add noise blocks between them.');
        }
    } catch (error) {
        console.error('Error parsing drop data:', error);
    }
}

function addBlockToStructure(blockData, insertBeforeIndex = null) {
    const targetStructure = document.getElementById('targetStructure');
    const placeholder = targetStructure.querySelector('.drop-placeholder');
    
    // Only noise blocks should be added manually (JPEG blocks are auto-populated)
    if (blockData.type === 'jpeg') {
        console.log('JPEG blocks are already in structure. Ignoring add request.');
        return;
    }
    
    if (placeholder) {
        placeholder.remove();
    }
    
    const block = document.createElement('div');
    block.className = 'block noise-block';
    block.draggable = true;
    block.dataset.type = blockData.type;
    block.dataset.noiseId = blockData.noiseId;
    block.dataset.noiseType = blockData.noiseType || 'random';
    block.dataset.size = blockData.size;
    block.dataset.inStructure = 'true';
    
    const noiseTypeLabel = blockData.noiseType ? ` (${blockData.noiseType})` : '';
    const label = `Noise ${blockData.noiseId}${noiseTypeLabel}`;
    
    block.innerHTML = `
        <div class="block-label">${label}</div>
        <div class="block-size">${formatBytes(parseInt(blockData.size))}</div>
        <button class="remove-btn" onclick="removeBlockFromStructure(this)">×</button>
    `;
    
    // Add drag event handlers for reordering
    block.addEventListener('dragstart', handleStructureBlockDragStart);
    block.addEventListener('dragend', handleStructureBlockDragEnd);
    block.addEventListener('dragover', handleStructureBlockDragOver);
    block.addEventListener('drop', handleStructureBlockDrop);
    
    // Insert at specified position or append
    if (insertBeforeIndex !== null && insertBeforeIndex < targetStructure.children.length) {
        targetStructure.insertBefore(block, targetStructure.children[insertBeforeIndex]);
        
        // Mark source block as used
        markBlockAsUsed(blockData);
        
        // Update structure array at specific position
        blockStructure.splice(insertBeforeIndex, 0, {
            type: blockData.type,
            blockIndex: null,
            noiseId: blockData.noiseId ? parseInt(blockData.noiseId) : null,
            noiseType: blockData.noiseType || 'random',
            size: parseInt(blockData.size)
        });
    } else {
        targetStructure.appendChild(block);
        
        // Mark source block as used
        markBlockAsUsed(blockData);
        
        // Update structure array
        blockStructure.push({
            type: blockData.type,
            blockIndex: null,
            noiseId: blockData.noiseId ? parseInt(blockData.noiseId) : null,
            noiseType: blockData.noiseType || 'random',
            size: parseInt(blockData.size)
        });
    }
}

function removeBlockFromStructure(btn) {
    const block = btn.parentElement;
    const index = Array.from(block.parentElement.children).indexOf(block);
    
    // Get block data before removing
    const blockData = blockStructure[index];
    
    // Unmark source block as unused
    unmarkBlockAsUsed(blockData);
    
    block.remove();
    blockStructure.splice(index, 1);
    
    const targetStructure = document.getElementById('targetStructure');
    if (targetStructure.children.length === 0) {
        targetStructure.innerHTML = '<div class="drop-placeholder">Drop blocks here to build fragmentation pattern</div>';
    }
}

function clearStructure() {
    const targetStructure = document.getElementById('targetStructure');
    
    // Remove only noise blocks, keep JPEG blocks
    const blocks = Array.from(targetStructure.children);
    blocks.forEach(block => {
        if (block.dataset && block.dataset.type === 'noise') {
            // Unmark the noise block as used in the source
            const blockData = {
                type: 'noise',
                noiseId: block.dataset.noiseId
            };
            unmarkBlockAsUsed(blockData);
            block.remove();
        }
    });
    
    // Update blockStructure to only contain JPEG blocks
    blockStructure = blockStructure.filter(item => item.type === 'jpeg');
    
    console.log('Cleared all noise blocks. JPEG blocks remain.');
}

function clearBuilder() {
    // Clear noise blocks
    const noiseBlocks = document.getElementById('noiseBlocks');
    if (noiseBlocks) {
        noiseBlocks.innerHTML = '';
    }
    
    // Clear target structure completely
    const targetStructure = document.getElementById('targetStructure');
    targetStructure.innerHTML = '<div class="drop-placeholder">Upload an image to start</div>';
    blockStructure = [];
    
    // Reset counters and state
    noiseBlockCounter = 0;
    currentImageFile = null;
    originalImageSize = 0;
    
    console.log('Builder cleared');
}

function markBlockAsUsed(blockData) {
    let sourceBlock;
    if (blockData.type === 'jpeg') {
        sourceBlock = document.getElementById(`jpeg-block-${blockData.blockIndex}`);
    } else if (blockData.type === 'noise') {
        sourceBlock = document.getElementById(`noise-block-${blockData.noiseId}`);
    }
    
    if (sourceBlock && !sourceBlock.classList.contains('used')) {
        sourceBlock.classList.add('used');
        sourceBlock.style.position = 'relative';
    }
}

function unmarkBlockAsUsed(blockData) {
    let sourceBlock;
    if (blockData.type === 'jpeg') {
        sourceBlock = document.getElementById(`jpeg-block-${blockData.blockIndex}`);
    } else if (blockData.type === 'noise') {
        sourceBlock = document.getElementById(`noise-block-${blockData.noiseId}`);
    }
    
    if (sourceBlock) {
        sourceBlock.classList.remove('used');
        sourceBlock.style.position = '';
    }
}

async function applyStructureAndFragment() {
    if (blockStructure.length === 0) {
        alert('Please add blocks to the fragmentation structure first!');
        return;
    }
    
    if (!currentImageFile) {
        alert('Please select an image first!');
        return;
    }
    
    // Rebuild blockStructure from DOM to ensure accuracy
    const targetStructure = document.getElementById('targetStructure');
    const blocks = Array.from(targetStructure.children);
    blockStructure = blocks.map(block => {
        const type = block.dataset.type;
        if (type === 'jpeg') {
            return {
                type: 'jpeg',
                blockIndex: parseInt(block.dataset.blockIndex),
                noiseId: null,
                noiseType: null,
                size: parseInt(block.dataset.size)
            };
        } else {
            return {
                type: 'noise',
                blockIndex: null,
                noiseId: parseInt(block.dataset.noiseId),
                noiseType: block.dataset.noiseType || 'random',
                size: parseInt(block.dataset.size)
            };
        }
    });
    
    console.log('Final block structure to send:', blockStructure);
    
    // Show loading
    showLoading(true);
    
    try {
        const formData = new FormData();
        formData.append('files', currentImageFile);
        formData.append('fragment', 'true');
        formData.append('blockStructure', JSON.stringify(blockStructure));
        
        const response = await fetch(`${API_BASE_URL}/analyze-custom`, {
            method: 'POST',
            body: formData
        });
        
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        
        const data = await response.json();
        
        showLoading(false);
        
        if (data.success && data.results && data.results.length > 0) {
            const result = data.results[0];
            
            // Display images
            displayImageComparison(result);
            
            // Display results
            displayResults(data);
            resultsSection.style.display = 'block';
            
            // Show image comparison section
            const imageComparisonSection = document.getElementById('imageComparisonSection');
            if (imageComparisonSection) {
                imageComparisonSection.style.display = 'block';
            }
            
            // Show Analyze Again button after successful fragmentation
            const analyzeAgainBtn = document.getElementById('analyzeAgainBtn');
            if (analyzeAgainBtn) {
                analyzeAgainBtn.style.display = 'inline-block';
            }
            
            resultsSection.scrollIntoView({ behavior: 'smooth' });
        } else {
            alert('Error: ' + (data.error || 'Unknown error occurred'));
        }
    } catch (error) {
        showLoading(false);
        console.error('Error:', error);
        alert('Failed to analyze image: ' + error.message);
    }
}

function formatBytes(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function displayImageComparison(result) {
    console.log('Displaying image comparison with result:', result);
    
    const originalImageDisplay = document.getElementById('originalImageDisplay');
    const fragmentedImageDisplay = document.getElementById('fragmentedImageDisplay');
    const reconstructedImageDisplay = document.getElementById('reconstructedImageDisplay');
    
    // Display original image from file
    if (currentImageFile) {
        const reader = new FileReader();
        reader.onload = function(e) {
            originalImageDisplay.src = e.target.result;
        };
        reader.readAsDataURL(currentImageFile);
    }
    
    // Display fragmented image
    if (result.fragmentedImage) {
        // Convert file path to URL
        const filename = result.fragmentedImage.split('\\').pop().split('/').pop();
        fragmentedImageDisplay.src = `/fragmented/${filename}`;
        console.log('Fragmented image URL:', `/fragmented/${filename}`);
    }
    
    // Display reconstructed image from detected boundaries
    if (result.reconstructedImage) {
        const filename = result.reconstructedImage.split('\\').pop().split('/').pop();
        reconstructedImageDisplay.src = `/reconstructed/${filename}`;
        reconstructedImageDisplay.style.display = 'block';
        console.log('Reconstructed image URL:', `/reconstructed/${filename}`);
        console.log('Reconstructed from detected boundaries after snapping');
    } else {
        console.warn('No reconstructed image in result');
        reconstructedImageDisplay.style.display = 'none';
        const container = reconstructedImageDisplay.parentElement;
        if (!container.querySelector('.no-reconstruction-msg')) {
            const msg = document.createElement('p');
            msg.className = 'no-reconstruction-msg';
            msg.style.color = '#666';
            msg.textContent = 'No reconstruction available (no fragments detected)';
            container.appendChild(msg);
        }
    }
}

async function analyzeAgain() {
    if (blockStructure.length === 0) {
        alert('No fragmentation structure available. Please fragment an image first.');
        return;
    }
    
    if (!currentImageFile) {
        alert('No image file available. Please upload and fragment an image first.');
        return;
    }
    
    console.log('Re-running detection on previously fragmented file:', currentImageFile.name);
    
    // Show loading
    showLoading(true);
    
    try {
        // Call /reanalyze endpoint to re-run detection WITHOUT re-fragmenting
        const response = await fetch(`${API_BASE_URL}/reanalyze`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                filenames: [currentImageFile.name]
            })
        });
        
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        
        const data = await response.json();
        
        showLoading(false);
        
        if (data.success && data.results && data.results.length > 0) {
            const result = data.results[0];
            
            // Display images
            displayImageComparison(result);
            
            // Display results
            displayResults(data);
            resultsSection.style.display = 'block';
            
            // Show image comparison section
            const imageComparisonSection = document.getElementById('imageComparisonSection');
            if (imageComparisonSection) {
                imageComparisonSection.style.display = 'block';
            }
            
            resultsSection.scrollIntoView({ behavior: 'smooth' });
        } else {
            alert('Error: ' + (data.error || 'Unknown error occurred'));
        }
    } catch (error) {
        showLoading(false);
        console.error('Error:', error);
        alert('Failed to re-analyze image: ' + error.message);
    }
}

// Make functions globally accessible
window.removeBlockFromStructure = removeBlockFromStructure;
