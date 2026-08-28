// ========== Configuration ==========
const API_BASE = '';
const INITIAL_CANDLES = 100;

// ========== State ==========
let chart;
let candleSeries;
let volumeSeries;
let currentSymbol = 'NSE:NIFTY50-INDEX';
let currentTimeframe = '5m';
let eventSource = null;
let isConnected = false;

// Live candle tracking
let liveCandle = null;
let liveVolume = 0;

// ========== Initialize ==========
document.addEventListener('DOMContentLoaded', () => {
    console.log('🚀 Initializing Trading Platform...');
    initChart();
    setupEventListeners();
    loadInitialData();
    connectSSE();

    // Load readiness every 30 seconds
    setInterval(loadReadiness, 30000);
});

// ========== Chart Setup ==========
function initChart() {
    const chartDiv = document.getElementById('chart');

    chart = LightweightCharts.createChart(chartDiv, {
        width: chartDiv.clientWidth,
        height: 600,
        layout: {
            background: { color: '#1e222d' },
            textColor: '#d1d4dc',
        },
        grid: {
            vertLines: { color: '#2b2b43' },
            horzLines: { color: '#2b2b43' },
        },
        timeScale: {
            timeVisible: true,
            secondsVisible: false,
            borderColor: '#2b2b43',
            rightOffset: 10,
            barSpacing: 8,
        },
        rightPriceScale: {
            borderColor: '#2b2b43',
            autoScale: true,  // ← Enable auto-scaling
            scaleMargins: {
                top: 0.1,
                bottom: 0.1,
            },
        },
        crosshair: {
            mode: LightweightCharts.CrosshairMode.Normal,
        },
    });

    candleSeries = chart.addCandlestickSeries({
        upColor: '#26a69a',
        downColor: '#ef5350',
        borderVisible: false,
        wickUpColor: '#26a69a',
        wickDownColor: '#ef5350',
    });

    volumeSeries = chart.addHistogramSeries({
        color: '#26a69a',
        priceFormat: { type: 'volume' },
        priceScaleId: '',
    });

    volumeSeries.priceScale().applyOptions({
        scaleMargins: {
            top: 0.8,
            bottom: 0,
        },
    });

    // Handle window resize
    window.addEventListener('resize', () => {
        chart.applyOptions({
            width: chartDiv.clientWidth,
            height: 600
        });
    });

    console.log('✅ Chart initialized with auto-scaling');
}

// ========== Event Listeners ==========
function setupEventListeners() {
    document.getElementById('symbol-select').addEventListener('change', (e) => {
        currentSymbol = e.target.value;
        console.log('📊 Symbol changed to:', currentSymbol);
        reconnect();
    });

    document.getElementById('timeframe-select').addEventListener('change', (e) => {
        currentTimeframe = e.target.value;
        console.log('⏱️ Timeframe changed to:', currentTimeframe);
        reconnect();
    });

    document.getElementById('refresh-btn').addEventListener('click', () => {
        console.log('🔄 Manual refresh triggered');
        reconnect();
    });
}

// ========== Data Loading ==========
async function loadInitialData() {
    console.log('📥 Loading initial candles...');

    try {
        const url = `${API_BASE}/ui/api/candles?symbol=${encodeURIComponent(currentSymbol)}&timeframe=${currentTimeframe}&limit=${INITIAL_CANDLES}`;
        console.log('🌐 Fetching:', url);

        const response = await fetch(url);

        if (!response.ok) {
            console.error('❌ Failed to load candles:', response.status, response.statusText);
            return;
        }

        const candles = await response.json();
        console.log('✅ Loaded', candles.length, 'candles');

        if (candles && candles.length > 0) {
            const candleData = candles.map(c => ({
                time: c.time,
                open: c.open,
                high: c.high,
                low: c.low,
                close: c.close
            }));

            const volumeData = candles.map(c => ({
                time: c.time,
                value: c.volume,
                color: c.close >= c.open ? 'rgba(38, 166, 154, 0.5)' : 'rgba(239, 83, 80, 0.5)'
            }));

            candleSeries.setData(candleData);
            volumeSeries.setData(volumeData);

            // Initialize live candle with last candle
            if (candleData.length > 0) {
                liveCandle = { ...candleData[candleData.length - 1] };
                liveVolume = volumeData[volumeData.length - 1].value;
            }

            // Fit content to view
            chart.timeScale().fitContent();

            updateLastUpdate();
        }

        // Load initial readiness data
        loadReadiness();
    } catch (error) {
        console.error('❌ Error loading initial data:', error);
    }
}

// ========== SSE Connection ==========
function connectSSE() {
    if (eventSource) {
        eventSource.close();
    }

    const url = `${API_BASE}/ui/api/stream/live?symbol=${encodeURIComponent(currentSymbol)}`;
    console.log('🔌 Connecting to SSE:', url);

    eventSource = new EventSource(url);

    eventSource.addEventListener('connected', (event) => {
        console.log('✅ SSE Connected:', event.data);
        setConnectionStatus(true);
    });

    eventSource.addEventListener('tick', (event) => {
        const tick = JSON.parse(event.data);
        updateLiveCandle(tick);
        updateLastUpdate();
    });

    eventSource.addEventListener('candle_closed', (event) => {
        const candle = JSON.parse(event.data);
        console.log('🕯️ Candle closed:', candle);

        if (candle.timeframe === currentTimeframe) {
            handleCandleClosed(candle);
        }
    });

    eventSource.addEventListener('readiness', (event) => {
        const snapshot = JSON.parse(event.data);
        console.log('🎯 Readiness update (SSE):', snapshot);

        if (snapshot.symbol === currentSymbol && snapshot.timeframe === currentTimeframe) {
            updateReadinessUI(snapshot);
        }
    });

    eventSource.onerror = (error) => {
        console.error('❌ SSE Error:', error);
        setConnectionStatus(false);

        // Reconnect after 5 seconds
        setTimeout(() => {
            if (!isConnected) {
                console.log('🔄 Attempting to reconnect...');
                connectSSE();
            }
        }, 5000);
    };
}

// ========== Live Candle Updates ==========
function updateLiveCandle(tick) {
    const price = parseFloat(tick.price);
    const timestamp = Math.floor(tick.timestamp / 1000);
    const volume = tick.volume || 0;

    // Calculate candle bucket time based on timeframe
    const timeframeSeconds = getTimeframeSeconds(currentTimeframe);
    const candleTime = timestamp - (timestamp % timeframeSeconds);

    if (!liveCandle || liveCandle.time !== candleTime) {
        // New candle started
        const prevClose = liveCandle ? liveCandle.close : price;
        liveCandle = {
            time: candleTime,
            open: prevClose,
            high: price,
            low: price,
            close: price
        };
        liveVolume = volume;

        console.log('📊 New candle started at:', new Date(candleTime * 1000).toLocaleTimeString());
    } else {
        // Update existing candle
        liveCandle.high = Math.max(liveCandle.high, price);
        liveCandle.low = Math.min(liveCandle.low, price);
        liveCandle.close = price;
        liveVolume += volume;
    }

    // Update chart
    candleSeries.update(liveCandle);
    volumeSeries.update({
        time: liveCandle.time,
        value: liveVolume,
        color: liveCandle.close >= liveCandle.open ? 'rgba(38, 166, 154, 0.5)' : 'rgba(239, 83, 80, 0.5)'
    });
}

function handleCandleClosed(candle) {
    const closedCandle = {
        time: candle.time,
        open: candle.open,
        high: candle.high,
        low: candle.low,
        close: candle.close
    };

    // Update with final closed candle
    candleSeries.update(closedCandle);
    volumeSeries.update({
        time: candle.time,
        value: candle.volume,
        color: candle.close >= candle.open ? 'rgba(38, 166, 154, 0.5)' : 'rgba(239, 83, 80, 0.5)'
    });

    // Reset live candle
    liveCandle = { ...closedCandle };
    liveVolume = 0;

    console.log('✅ Candle closed and locked:', new Date(candle.time * 1000).toLocaleTimeString());

    // Refresh readiness after candle close
    setTimeout(loadReadiness, 2000);
}

function getTimeframeSeconds(timeframe) {
    const map = {
        '1m': 60,
        '3m': 180,
        '5m': 300,
        '15m': 900,
        '30m': 1800,
        '1h': 3600
    };
    return map[timeframe] || 300;
}

// ========== Readiness Data Loading ==========
async function loadReadiness() {
    const url = `${API_BASE}/ui/api/signal-readiness?symbol=${encodeURIComponent(currentSymbol)}&timeframe=${currentTimeframe}`;

    try {
        const response = await fetch(url);

        if (response.ok) {
            const snapshot = await response.json();
            console.log('✅ Readiness data:', snapshot);
            updateReadinessUI(snapshot);
        } else {
            console.warn('⚠️ Readiness not available:', response.status);
            showWaitingState();
        }
    } catch (error) {
        console.error('❌ Failed to load readiness:', error);
        showWaitingState();
    }
}

// ========== Readiness UI Updates ==========
function updateReadinessUI(snapshot) {
    // Update progress bar
    const progressBar = document.getElementById('readiness-bar');
    const percentText = document.getElementById('readiness-percent');

    if (progressBar && percentText) {
        progressBar.style.width = snapshot.readinessPercent + '%';
        percentText.textContent = snapshot.readinessPercent + '%';

        // Color based on readiness
        if (snapshot.readinessPercent >= 75) {
            progressBar.style.backgroundColor = '#26a69a'; // Green
        } else if (snapshot.readinessPercent >= 50) {
            progressBar.style.backgroundColor = '#ffa726'; // Orange
        } else {
            progressBar.style.backgroundColor = '#636973'; // Gray
        }
    }

    // Update votes
    const votesEl = document.getElementById('votes');
    if (votesEl) {
        votesEl.textContent = `${snapshot.currentVotes}/${snapshot.requiredVotes}`;
    }

    // Update signal badge
    const signalEl = document.getElementById('signal');
    if (signalEl) {
        signalEl.textContent = snapshot.signal;
        signalEl.className = 'signal-badge signal-' + snapshot.signal.toLowerCase();
    }

    // Update agreement score
    const agreementEl = document.getElementById('agreement');
    if (agreementEl) {
        agreementEl.textContent = (snapshot.agreementScore * 100).toFixed(1) + '%';
    }

    // Update blockers
    const blockersSection = document.getElementById('blockers-section');
    const blockersList = document.getElementById('blockers');

    if (blockersSection && blockersList) {
        if (snapshot.blockers && snapshot.blockers.length > 0) {
            blockersSection.style.display = 'block';
            blockersList.innerHTML = snapshot.blockers
                .map(b => `<span class="blocker-tag">${formatBlocker(b)}</span>`)
                .join('');
        } else {
            blockersSection.style.display = 'none';
        }
    }

    // Update nearest trigger
    const triggerEl = document.getElementById('nearest-trigger');
    if (triggerEl) {
        triggerEl.textContent = snapshot.nearestTrigger || 'No trigger detected';
    }

    // Update timestamp
    const updatedAt = new Date(snapshot.updatedAt);
    const lastUpdatedEl = document.getElementById('last-updated');
    if (lastUpdatedEl) {
        lastUpdatedEl.textContent = 'Updated: ' + updatedAt.toLocaleTimeString();
    }
}

function showWaitingState() {
    const progressBar = document.getElementById('readiness-bar');
    const percentText = document.getElementById('readiness-percent');
    if (progressBar && percentText) {
        progressBar.style.width = '0%';
        progressBar.style.backgroundColor = '#636973';
        percentText.textContent = '--';
    }

    const votesEl = document.getElementById('votes');
    if (votesEl) votesEl.textContent = '0/3';

    const signalEl = document.getElementById('signal');
    if (signalEl) {
        signalEl.textContent = 'HOLD';
        signalEl.className = 'signal-badge signal-hold';
    }

    const agreementEl = document.getElementById('agreement');
    if (agreementEl) agreementEl.textContent = '0.0%';

    const blockersSection = document.getElementById('blockers-section');
    if (blockersSection) blockersSection.style.display = 'none';

    const triggerEl = document.getElementById('nearest-trigger');
    if (triggerEl) triggerEl.textContent = 'Waiting for first candle close...';

    const lastUpdatedEl = document.getElementById('last-updated');
    if (lastUpdatedEl) lastUpdatedEl.textContent = 'Updated: --:--:--';
}

function formatBlocker(blocker) {
    const blockerNames = {
        'session_closed': '🕐 Market Closed',
        'cooldown_active': '⏱️ Cooldown',
        'max_positions': '📊 Max Positions',
        'circuit_breaker': '🚨 Circuit Breaker'
    };
    return blockerNames[blocker] || blocker;
}

// ========== Utility Functions ==========
function setConnectionStatus(connected) {
    isConnected = connected;
    const statusEl = document.getElementById('connection-status');

    if (statusEl) {
        if (connected) {
            statusEl.textContent = '🟢 Connected';
            statusEl.style.color = '#26a69a';
        } else {
            statusEl.textContent = '🔴 Disconnected';
            statusEl.style.color = '#ef5350';
        }
    }
}

function updateLastUpdate() {
    const now = new Date();
    const lastUpdateEl = document.getElementById('last-update');
    if (lastUpdateEl) {
        lastUpdateEl.textContent = 'Last update: ' + now.toLocaleTimeString();
    }
}

function reconnect() {
    console.log('🔄 Reconnecting with symbol:', currentSymbol, 'timeframe:', currentTimeframe);

    // Reset state
    liveCandle = null;
    liveVolume = 0;

    // Reload data
    loadInitialData();
    connectSSE();
    loadReadiness();
}