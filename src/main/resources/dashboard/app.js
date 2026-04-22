let chartInstance = null;
let autoRefreshInterval = null;
let allStubs = [];
let currentEditingStubId = null;

function switchTab(tabName) {
    // Update tab buttons
    const tabs = document.querySelectorAll('.tab');
    tabs.forEach(tab => tab.classList.remove('active'));
    event.target.classList.add('active');
    
    // Update tab content
    const contents = document.querySelectorAll('.tab-content');
    contents.forEach(content => content.classList.remove('active'));
    document.getElementById(tabName).classList.add('active');
    
    // Manage auto-refresh and load data based on active tab
    if (tabName === 'dashboard') {
        startAutoRefresh();
    } else {
        stopAutoRefresh();
    }
    
    if (tabName === 'stubs') {
        loadStubs();
    }
}

function startAutoRefresh() {
    if (!autoRefreshInterval) {
        autoRefreshInterval = setInterval(getSummary, 10000);
    }
}

function stopAutoRefresh() {
    if (autoRefreshInterval) {
        clearInterval(autoRefreshInterval);
        autoRefreshInterval = null;
    }
}

async function loadStubs() {
    const stubsGrid = document.getElementById('stubsGrid');
    const stubsCount = document.getElementById('stubsCount');
    
    stubsGrid.innerHTML = '<div class="loading">Loading stubs...</div>';
    
    try {
        const response = await fetch('/__admin/mappings');
        const data = await response.json();
        allStubs = data.mappings || [];
        
        stubsCount.textContent = `Total Stubs: ${allStubs.length}`;
        
        if (allStubs.length === 0) {
            stubsGrid.innerHTML = '<div class="no-stubs">No stubs found. Upload some stub mappings to WireMock.</div>';
            return;
        }
        
        displayStubs(allStubs);
    } catch (error) {
        stubsGrid.innerHTML = `<div class="no-stubs">Error loading stubs: ${error.message}</div>`;
    }
}

function displayStubs(stubs) {
    const stubsGrid = document.getElementById('stubsGrid');
    
    if (stubs.length === 0) {
        stubsGrid.innerHTML = '<div class="no-stubs">No stubs match your search.</div>';
        return;
    }
    
    let html = '';
    
    stubs.forEach(stub => {
        const request = stub.request || {};
        const response = stub.response || {};
        const method = request.method || 'ANY';
        const url = request.url || request.urlPattern || request.urlPath || request.urlPathPattern || 'N/A';
        const priority = stub.priority || 5;
        const id = stub.id || stub.uuid || 'N/A';
        const status = response.status || 200;
        
        let statusClass = 'status-200';
        if (status >= 400 && status < 500) statusClass = 'status-400';
        if (status >= 500) statusClass = 'status-500';
        
        html += `
            <div class="stub-card">
                <div class="stub-card-header">
                    <div>
                        <div>
                            <span class="stub-method method-${method}">${method}</span>
                            <span class="stub-url">${url}</span>
                        </div>
                        <div class="stub-id">ID: ${id}</div>
                    </div>
                    <div>
                        <span class="stub-priority">Priority: ${priority}</span>
                        <div class="stub-actions">
                            <button class="action-btn small" onclick="viewStub('${id}')">View</button>
                            <button class="action-btn small" onclick="editStub('${id}')">Edit</button>
                            <button class="action-btn small reset" onclick="deleteStub('${id}')">Delete</button>
                        </div>
                    </div>
                </div>
                <div class="stub-details">
                    <div class="stub-detail-row">
                        <span class="stub-detail-label">Response Status:</span>
                        <span class="stub-response-status ${statusClass}">${status}</span>
                    </div>
        `;
        
        if (response.body) {
            const bodyPreview = response.body.length > 100 
                ? response.body.substring(0, 100) + '...' 
                : response.body;
            html += `
                    <div class="stub-detail-row">
                        <span class="stub-detail-label">Response Body:</span>
                        <span class="stub-detail-value">${bodyPreview}</span>
                    </div>
            `;
        }
        
        if (response.bodyFileName) {
            html += `
                    <div class="stub-detail-row">
                        <span class="stub-detail-label">Body File:</span>
                        <span class="stub-detail-value">${response.bodyFileName}</span>
                    </div>
            `;
        }
        
        if (request.queryParameters) {
            const params = Object.keys(request.queryParameters).join(', ');
            html += `
                    <div class="stub-detail-row">
                        <span class="stub-detail-label">Query Params:</span>
                        <span class="stub-detail-value">${params}</span>
                    </div>
            `;
        }
        
        html += `
                </div>
            </div>
        `;
    });
    
    stubsGrid.innerHTML = html;
}

function filterStubs() {
    const searchTerm = document.getElementById('searchBox').value.toLowerCase();
    
    if (searchTerm === '') {
        displayStubs(allStubs);
        return;
    }
    
    const filtered = allStubs.filter(stub => {
        const request = stub.request || {};
        const method = (request.method || 'ANY').toLowerCase();
        const url = (request.url || request.urlPattern || request.urlPath || request.urlPathPattern || '').toLowerCase();
        const id = (stub.id || stub.uuid || '').toLowerCase();
        
        return method.includes(searchTerm) || url.includes(searchTerm) || id.includes(searchTerm);
    });
    
    displayStubs(filtered);
}

async function viewStub(stubId) {
    try {
        const response = await fetch(`/__admin/mappings/${stubId}`);
        const stub = await response.json();
        
        document.getElementById('modalTitle').textContent = 'View Stub (Read-Only)';
        document.getElementById('stubEditor').value = JSON.stringify(stub, null, 2);
        document.getElementById('stubEditor').readOnly = true;
        document.getElementById('modalMessage').innerHTML = '';
        
        // Hide save button for view mode
        const modal = document.getElementById('editModal');
        modal.querySelector('.modal-footer').style.display = 'none';
        modal.style.display = 'block';
        
        currentEditingStubId = null;
    } catch (error) {
        alert('Error loading stub: ' + error.message);
    }
}

async function editStub(stubId) {
    try {
        const response = await fetch(`/__admin/mappings/${stubId}`);
        const stub = await response.json();
        
        document.getElementById('modalTitle').textContent = 'Edit Stub';
        document.getElementById('stubEditor').value = JSON.stringify(stub, null, 2);
        document.getElementById('stubEditor').readOnly = false;
        document.getElementById('modalMessage').innerHTML = '';
        
        // Show save button for edit mode
        const modal = document.getElementById('editModal');
        modal.querySelector('.modal-footer').style.display = 'flex';
        modal.style.display = 'block';
        
        currentEditingStubId = stubId;
    } catch (error) {
        alert('Error loading stub: ' + error.message);
    }
}

async function saveStub() {
    if (!currentEditingStubId) return;
    
    const messageDiv = document.getElementById('modalMessage');
    
    try {
        const stubJson = document.getElementById('stubEditor').value;
        const stub = JSON.parse(stubJson);
        
        const response = await fetch(`/__admin/mappings/${currentEditingStubId}`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(stub)
        });
        
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        
        messageDiv.innerHTML = '<div class="success-message">Stub updated successfully!</div>';
        
        setTimeout(() => {
            closeEditModal();
            loadStubs();
        }, 1500);
        
    } catch (error) {
        messageDiv.innerHTML = `<div class="error-message">Error saving stub: ${error.message}</div>`;
    }
}

async function deleteStub(stubId) {
    if (!confirm('Are you sure you want to delete this stub? This action cannot be undone.')) {
        return;
    }
    
    try {
        const response = await fetch(`/__admin/mappings/${stubId}`, {
            method: 'DELETE'
        });
        
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        
        alert('Stub deleted successfully!');
        loadStubs();
    } catch (error) {
        alert('Error deleting stub: ' + error.message);
    }
}

function closeEditModal() {
    document.getElementById('editModal').style.display = 'none';
    currentEditingStubId = null;
}

// Close modal when clicking outside
window.onclick = function(event) {
    const modal = document.getElementById('editModal');
    if (event.target == modal) {
        closeEditModal();
    }
}

function createStackedBarChart(data) {
    const ctx = document.getElementById('stubChart').getContext('2d');
    
    if (chartInstance) {
        chartInstance.destroy();
    }
    
    const stubData = data.counts || {};
    const labels = Object.keys(stubData);
    const values = Object.values(stubData);
    const total = values.reduce((a, b) => a + b, 0);
    
    const colors = generateColors(labels.length);
    
    chartInstance = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [{
                label: 'Request Count',
                data: values,
                backgroundColor: colors,
                borderColor: colors.map(c => c.replace('0.7', '1')),
                borderWidth: 2
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                title: {
                    display: true,
                    text: 'Request Distribution by Stub Pattern (Sorted by Count)',
                    font: {
                        size: 18,
                        weight: 'bold'
                    },
                    padding: 20
                },
                legend: {
                    display: false
                },
                tooltip: {
                    callbacks: {
                        label: function(context) {
                            const count = context.parsed.y;
                            const percentage = ((count / total) * 100).toFixed(2);
                            return `Count: ${count.toLocaleString()} (${percentage}%)`;
                        }
                    },
                    backgroundColor: 'rgba(0, 0, 0, 0.8)',
                    padding: 12,
                    titleFont: {
                        size: 14,
                        weight: 'bold'
                    },
                    bodyFont: {
                        size: 13
                    }
                }
            },
            scales: {
                y: {
                    beginAtZero: true,
                    ticks: {
                        callback: function(value) {
                            return value.toLocaleString();
                        },
                        font: {
                            size: 12
                        }
                    },
                    grid: {
                        color: 'rgba(0, 0, 0, 0.05)'
                    }
                },
                x: {
                    ticks: {
                        autoSkip: false,
                        maxRotation: 45,
                        minRotation: 45,
                        font: {
                            size: 11
                        }
                    },
                    grid: {
                        display: false
                    }
                }
            }
        }
    });
}

function generateColors(count) {
    const colors = [];
    for (let i = 0; i < count; i++) {
        const hue = (i * 360 / count) % 360;
        colors.push(`hsla(${hue}, 70%, 60%, 0.7)`);
    }
    return colors;
}

async function getStubCounts() {
    try {
        const response = await fetch('/__admin/stub-counter');
        const data = await response.json();
        document.getElementById('output').textContent = JSON.stringify(data, null, 2);
        createStackedBarChart(data);
        updateStats(data.total, Object.keys(data.counts || {}).length, null);
    } catch (error) {
        document.getElementById('output').textContent = 'Error: ' + error.message;
    }
}

async function getUrlCounts() {
    try {
        const response = await fetch('/__admin/stub-counter/urls');
        const data = await response.json();
        document.getElementById('output').textContent = JSON.stringify(data, null, 2);
        createStackedBarChart(data);
        updateStats(data.total, null, Object.keys(data.counts || {}).length);
    } catch (error) {
        document.getElementById('output').textContent = 'Error: ' + error.message;
    }
}

async function getSummary() {
    try {
        // Get JSON data for chart
        const jsonResponse = await fetch('/__admin/stub-counter');
        const jsonData = await jsonResponse.json();
        
        // Get text summary for display
        const textResponse = await fetch('/__admin/stub-counter/summary');
        const textData = await textResponse.text();
        
        document.getElementById('output').textContent = textData;
        createStackedBarChart(jsonData);
        updateStats(jsonData.total, Object.keys(jsonData.counts || {}).length, null);
    } catch (error) {
        document.getElementById('output').textContent = 'Error: ' + error.message;
    }
}

function updateStats(totalRequests, totalStubs, totalUrls) {
    const statsDiv = document.getElementById('stats');
    let html = '';
    
    if (totalRequests !== null) {
        html += `
            <div class="stat-card">
                <div class="stat-number">${totalRequests.toLocaleString()}</div>
                <div class="stat-label">Total Requests</div>
            </div>
        `;
    }
    
    if (totalStubs !== null) {
        html += `
            <div class="stat-card">
                <div class="stat-number">${totalStubs}</div>
                <div class="stat-label">Stub Patterns</div>
            </div>
        `;
    }
    
    if (totalUrls !== null) {
        html += `
            <div class="stat-card">
                <div class="stat-number">${totalUrls}</div>
                <div class="stat-label">Unique URLs</div>
            </div>
        `;
    }
    
    statsDiv.innerHTML = html;
}

async function resetCounters() {
    if (!confirm('Are you sure you want to reset all counters? This will clear all tracking data.')) {
        return;
    }
    try {
        const response = await fetch('/__admin/reset-stub-counter', {
            method: 'POST'
        });
        const data = await response.json();
        document.getElementById('output').textContent = JSON.stringify(data, null, 2);
        document.getElementById('stats').innerHTML = '';
        if (chartInstance) {
            chartInstance.destroy();
            chartInstance = null;
        }
        alert('Counters reset successfully!');
    } catch (error) {
        document.getElementById('output').textContent = 'Error: ' + error.message;
        alert('Failed to reset counters: ' + error.message);
    }
}

async function refreshChart() {
    await getSummary();
}

// Start auto-refresh
startAutoRefresh();

// Load summary on page load
window.onload = getSummary;
