// ============================================
// Global State & Configuration
// ============================================

let currentSymbol = null; // ✅ Will be set dynamically
let chart = null;
let candleSeries = null;
let eventSource = null;
let currentCandleBuffer = {};

// Debug: Track strategy signals
const strategySignalCounter = {
    total: 0,
    byType: {},
    bySymbol: {},
    lastSignalTime: null,
    signals: []
};

// ============================================
// Initialization
// ============================================

document.addEventListener('DOMContentLoaded', async () => {
    console.log('🚀 [APP INITIALIZED]');

    initializeChart();

    // ✅ Load symbols from backend
    await loadSymbols();

    initializeSymbolSelector();
    connectSSE(currentSymbol);
    loadInitialCandles(currentSymbol);
});

// ============================================
// Load Symbols Dynamically
// ============================================

async function loadSymbols() {
    console.log('📡 [LOADING SYMBOLS FROM BACKEND]');

    try {
        const response = await fetch('/ui/api/symbols');
        const symbols = await response.json();

        const selector = document.getElementById('symbol-select');
        selector.innerHTML = ''; // Clear existing options

        symbols.forEach(symbol => {
            const option = document.createElement('option');
            option.value = symbol;
            option.textContent = formatSymbolName(symbol);
            selector.appendChild(option);
        });

        // Set first symbol as current
        currentSymbol = symbols[0];
        console.log('✅ Loaded', symbols.length, 'symbols. Initial:', currentSymbol);

    } catch (error) {
        console.error('❌ Failed to load symbols from backend:', error);
        console.log('⚠️ Using fallback symbols');

        // Fallback symbols if API fails
        const fallbackSymbols = [
            { value: 'MCX:GOLD26OCTFUT', label: 'MCX GOLD' },
            { value: 'MCX:CRUDEOIL26OCTFUT', label: 'MCX CRUDE OIL' },
            { value: 'MCX:SILVER26OCTFUT', label: 'MCX SILVER' },
            { value: 'NSE:NIFTY50-INDEX', label: 'NIFTY 50' },
            { value: 'NSE:NIFTYBANK-INDEX', label: 'NIFTY BANK' },
            { value: 'NSE:FINNIFTY-INDEX', label: 'FIN NIFTY' },
            { value: 'NSE:RELIANCE-EQ', label: 'RELIANCE' },
            { value: 'NSE:TCS-EQ', label: 'TCS' },
            { value: 'NSE:INFY-EQ', label: 'INFOSYS' },
            { value: 'NSE:SBIN-EQ', label: 'SBI' }
        ];

        const selector = document.getElementById('symbol-select');
        selector.innerHTML = '';

        fallbackSymbols.forEach(sym => {
            const option = document.createElement('option');
            option.value = sym.value;
            option.textContent = sym.label;
            selector.appendChild(option);
        });

        currentSymbol = fallbackSymbols[0].value;
        console.log('✅ Loaded fallback symbols. Initial:', currentSymbol);
    }
}

function formatSymbolName(symbol) {
    // Convert "MCX:GOLD26OCTFUT" to "MCX GOLD"
    // Convert "NSE:NIFTY50-INDEX" to "NIFTY 50"

    const parts = symbol.split(':');
    if (parts.length !== 2) return symbol;

    const exchange = parts[0];
    let name = parts[1];

    // Remove futures suffix (26OCTFUT, etc.)
    name = name.replace(/\d{2}[A-Z]{3}FUT$/, '');
    // Remove -INDEX, -EQ suffixes
    name = name.replace(/-(INDEX|EQ)$/, '');
    // Add space before numbers
    name = name.replace(/(\D)(\d)/, '$1 $2');

    return `${exchange} ${name}`;
}

// ============================================
// Chart Initialization
// ============================================

function initializeChart() {
    console.log('📊 [INITIALIZING CHART]');

    const chartContainer = document.getElementById('chart');
    chart = LightweightCharts.createChart(chartContainer, {
        width: chartContainer.clientWidth,
        height: 500,
        layout: {
            background: { color: '#1e1e1e' },
            textColor: '#d1d4dc',
        },
        grid: {
            vertLines: { color: '#2e2e2e' },
            horzLines: { color: '#2e2e2e' },
        },
        crosshair: {
            mode: LightweightCharts.CrosshairMode.Normal,
        },
        rightPriceScale: {
            borderColor: '#485c7b',
        },
        timeScale: {
            borderColor: '#485c7b',
            timeVisible: true,
            secondsVisible: false,
        },
    });

    candleSeries = chart.addCandlestickSeries({
        upColor: '#26a69a',
        downColor: '#ef5350',
        borderVisible: false,
        wickUpColor: '#26a69a',
        wickDownColor: '#ef5350',
    });

    // Auto-resize chart
    window.addEventListener('resize', () => {
        chart.resize(chartContainer.clientWidth, 500);
    });

    console.log('✅ [CHART READY]');
}

// ============================================
// Symbol Selection
// ============================================

function initializeSymbolSelector() {
    const selector = document.getElementById('symbol-select');

    selector.addEventListener('change', (e) => {
        const newSymbol = e.target.value;
        console.log('🔄 [SYMBOL CHANGED]', currentSymbol, '→', newSymbol);

        switchSymbol(newSymbol);
    });
}

function switchSymbol(symbol) {
    console.log('🔄 [SWITCHING SYMBOL]', symbol);

    // Close existing SSE connection
    if (eventSource) {
        console.log('  └─ Closing old SSE connection');
        eventSource.close();
    }

    currentSymbol = symbol;

    // Clear candle buffer
    currentCandleBuffer = {};

    // Clear chart
    candleSeries.setData([]);

    // Connect to new symbol
    connectSSE(symbol);
    loadInitialCandles(symbol);

    console.log('✅ [SYMBOL SWITCHED]', symbol);
}

// ============================================
// Server-Sent Events (SSE)
// ============================================

function connectSSE(symbol) {
    console.log('🔌 [CONNECTING SSE]', symbol);

    const url = `/ui/api/stream/live?symbol=${encodeURIComponent(symbol)}`;
    eventSource = new EventSource(url);

    eventSource.addEventListener('open', () => {
        console.log('✅ [SSE CONNECTED] to symbol:', symbol);
    });

    eventSource.addEventListener('connected', (event) => {
        console.log('🔗 [SSE HANDSHAKE]', event.data);
    });

    // Tick events - Silent logging
    eventSource.addEventListener('tick', (event) => {
        try {
            const tick = JSON.parse(event.data);
            handleTick(tick);
        } catch (error) {
            console.error('❌ Failed to parse tick:', error);
        }
    });

    // Candle events
    eventSource.addEventListener('candle_closed', (event) => {
        console.log('🕯️ [CANDLE CLOSED]', event.data);

        try {
            const candleData = JSON.parse(event.data);

            const candle = {
                symbol: currentSymbol,
                timeframe: candleData.timeframe,
                time: candleData.time,
                open: parseFloat(candleData.open),
                high: parseFloat(candleData.high),
                low: parseFloat(candleData.low),
                close: parseFloat(candleData.close),
                volume: candleData.volume
            };

            console.log('  └─', candle.timeframe, 'at', new Date(candle.time * 1000).toLocaleTimeString());
            handleCandle(candle);
        } catch (error) {
            console.error('❌ Failed to parse candle:', error);
        }
    });

    // Signal events
    eventSource.addEventListener('signal', (event) => {
        console.log('🎯 [SIGNAL]', event.data);

        try {
            const signal = JSON.parse(event.data);
            console.log('  ├─ Strategy:', signal.strategyType);
            console.log('  ├─ Signal:', signal.signalType, '@', signal.price);
            console.log('  └─ Confidence:', (signal.confidence * 100).toFixed(1) + '%');

            if (signal.strategyType === 'candlestick-pattern') {
                console.log('  🎨 PATTERN DETECTED!', signal.reason.split(' ')[0]);
            }

            handleSignal(signal);
        } catch (error) {
            console.error('❌ Failed to parse signal:', error);
        }
    });

    // Readiness events
    eventSource.addEventListener('readiness', (event) => {
        try {
            const readiness = JSON.parse(event.data);
            updateReadinessPanel(readiness);
        } catch (error) {
            console.error('❌ Failed to parse readiness:', error);
        }
    });

    // Error events
    eventSource.addEventListener('error', (event) => {
        if (eventSource.readyState === EventSource.CLOSED) {
            console.error('❌ [SSE CONNECTION CLOSED]');
        } else if (eventSource.readyState === EventSource.CONNECTING) {
            console.warn('⚠️ [SSE RECONNECTING...]');
        }
    });
}

// ============================================
// Event Handlers
// ============================================

function handleTick(tick) {
    // Update price display
    const priceElement = document.getElementById('current-price');
    if (priceElement && tick.symbol === currentSymbol) {
        priceElement.textContent = tick.price.toFixed(2);
    }

    // Update current candle with tick data
    if (tick.symbol === currentSymbol) {
        updateCurrentCandle(tick);
    }
}

function updateCurrentCandle(tick) {
    const symbol = tick.symbol;
    const price = typeof tick.price === 'number' ? tick.price : parseFloat(tick.price);

    // Convert milliseconds to seconds
    const timestampSeconds = Math.floor(tick.timestamp / 1000);

    // Calculate candle time (align to 1-minute boundary)
    const candleTime = Math.floor(timestampSeconds / 60) * 60;

    // Initialize or update candle buffer
    if (!currentCandleBuffer[symbol] || currentCandleBuffer[symbol].time !== candleTime) {
        currentCandleBuffer[symbol] = {
            time: candleTime,
            open: price,
            high: price,
            low: price,
            close: price
        };
    } else {
        const candle = currentCandleBuffer[symbol];
        candle.high = Math.max(candle.high, price);
        candle.low = Math.min(candle.low, price);
        candle.close = price;
    }

    // Update chart with current candle in real-time
    try {
        candleSeries.update({
            time: currentCandleBuffer[symbol].time,
            open: currentCandleBuffer[symbol].open,
            high: currentCandleBuffer[symbol].high,
            low: currentCandleBuffer[symbol].low,
            close: currentCandleBuffer[symbol].close
        });
    } catch (error) {
        // Suppress time-related errors silently
        if (!error.message.includes('time')) {
            console.warn('Chart update issue:', error.message);
        }
    }
}

function handleCandle(candle) {
    if (candle.symbol !== currentSymbol) {
        return;
    }

    // Accept both 60s and 5m candles
    const acceptedTimeframes = ['60s', '5m'];
    if (!acceptedTimeframes.includes(candle.timeframe)) {
        return;
    }

    // Update chart with confirmed candle
    try {
        candleSeries.update({
            time: candle.time,
            open: candle.open,
            high: candle.high,
            low: candle.low,
            close: candle.close
        });
    } catch (error) {
        console.error('Chart update failed:', error.message);
    }

    // Auto-scale chart
    chart.timeScale().fitContent();
}

function handleSignal(signal) {
    logStrategySignal(signal);

    // Display signal in UI
    const signalsList = document.getElementById('signals-list');
    if (!signalsList) return;

    const signalElement = document.createElement('div');
    signalElement.className = `signal-item signal-${signal.signalType.toLowerCase()}`;

    const time = new Date(signal.timestamp).toLocaleTimeString();

    signalElement.innerHTML = `
        <div class="signal-header">
            <span class="signal-type">${signal.signalType}</span>
            <span class="signal-time">${time}</span>
        </div>
        <div class="signal-body">
            <div class="signal-strategy">${signal.strategyType}</div>
            <div class="signal-price">@ ${signal.price}</div>
            <div class="signal-confidence">Confidence: ${(signal.confidence * 100).toFixed(0)}%</div>
            <div class="signal-reason">${signal.reason}</div>
        </div>
    `;

    signalsList.insertBefore(signalElement, signalsList.firstChild);

    // Keep only last 20 signals
    while (signalsList.children.length > 20) {
        signalsList.removeChild(signalsList.lastChild);
    }
}

function updateReadinessPanel(readiness) {
    if (readiness.symbol !== currentSymbol) {
        return;
    }

    const percentElement = document.getElementById('readiness-percent');
    if (percentElement) {
        percentElement.textContent = readiness.readinessPercent + '%';
    }

    const votesElement = document.getElementById('readiness-votes');
    if (votesElement) {
        votesElement.textContent = `${readiness.currentVotes}/${readiness.requiredVotes} votes`;
    }

    const signalElement = document.getElementById('readiness-signal');
    if (signalElement) {
        signalElement.textContent = readiness.signal;
        signalElement.className = `readiness-signal signal-${readiness.signal.toLowerCase()}`;
    }

    const agreementElement = document.getElementById('readiness-agreement');
    if (agreementElement) {
        agreementElement.textContent = (readiness.agreementScore * 100).toFixed(0) + '%';
    }

    const triggerElement = document.getElementById('readiness-trigger');
    if (triggerElement) {
        triggerElement.textContent = readiness.nearestTrigger;
    }
}

// ============================================
// Initial Data Loading
// ============================================

async function loadInitialCandles(symbol) {
    console.log('📥 [LOADING INITIAL CANDLES]', symbol);

    try {
        const response = await fetch(`/ui/api/candles?symbol=${encodeURIComponent(symbol)}&timeframe=5m&limit=100`);
        const candles = await response.json();

        console.log('  └─ Loaded', candles.length, 'candles');

        candleSeries.setData(candles.map(c => ({
            time: c.time,
            open: parseFloat(c.open),
            high: parseFloat(c.high),
            low: parseFloat(c.low),
            close: parseFloat(c.close)
        })));

        chart.timeScale().fitContent();

        console.log('✅ [INITIAL CANDLES LOADED]');
    } catch (error) {
        console.error('❌ Failed to load initial candles:', error);
    }
}

// ============================================
// Strategy Signal Tracking
// ============================================

function logStrategySignal(signal) {
    strategySignalCounter.total++;
    strategySignalCounter.byType[signal.strategyType] =
        (strategySignalCounter.byType[signal.strategyType] || 0) + 1;
    strategySignalCounter.bySymbol[signal.symbol] =
        (strategySignalCounter.bySymbol[signal.symbol] || 0) + 1;
    strategySignalCounter.lastSignalTime = new Date();

    strategySignalCounter.signals.unshift({
        time: new Date(),
        type: signal.strategyType,
        symbol: signal.symbol,
        signal: signal.signalType,
        confidence: signal.confidence
    });

    if (strategySignalCounter.signals.length > 50) {
        strategySignalCounter.signals.pop();
    }
}

// ============================================
// Debug Console Commands
// ============================================

window.checkStrategies = function() {
    console.log('🔍 [STRATEGY CHECK]');
    console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
    console.log('  Total signals:', strategySignalCounter.total);
    console.log('  By strategy:', strategySignalCounter.byType);
    console.log('  By symbol:', strategySignalCounter.bySymbol);
    console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');

    if (strategySignalCounter.total === 0) {
        console.warn('⚠️ NO SIGNALS YET');
    } else {
        const patternSignals = strategySignalCounter.signals.filter(s => s.type === 'candlestick-pattern');
        if (patternSignals.length > 0) {
            console.log('\n🎨 Pattern signals:', patternSignals.length);
            console.table(patternSignals.slice(0, 10));
        }

        console.log('\n📊 Latest 10 signals:');
        console.table(strategySignalCounter.signals.slice(0, 10).map(s => ({
            Time: s.time.toLocaleTimeString(),
            Strategy: s.type,
            Signal: s.signal,
            'Conf %': (s.confidence * 100).toFixed(0)
        })));
    }
};

window.checkReadiness = async function(symbol = currentSymbol, timeframe = '5m') {
    console.log('🔍 [READINESS]', symbol, timeframe);

    try {
        const response = await fetch(`/ui/api/signal-readiness?symbol=${symbol}&timeframe=${timeframe}`);
        const data = await response.json();

        console.log('  Readiness:', data.readinessPercent + '%');
        console.log('  Votes:', data.currentVotes + '/' + data.requiredVotes);
        console.log('  Signal:', data.signal);
        console.log('  Agreement:', (data.agreementScore * 100).toFixed(1) + '%');

        return data;
    } catch (error) {
        console.error('❌ Error:', error);
    }
};

window.checkPatterns = function() {
    const patterns = strategySignalCounter.signals.filter(s => s.type === 'candlestick-pattern');

    if (patterns.length === 0) {
        console.warn('⚠️ No pattern signals yet');
    } else {
        console.log('🎨 Pattern signals:', patterns.length);
        console.table(patterns.map(s => ({
            Time: s.time.toLocaleTimeString(),
            Signal: s.signal,
            'Conf %': (s.confidence * 100).toFixed(0)
        })));
    }
};

window.help = function() {
    console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
    console.log('📚 DEBUG COMMANDS');
    console.log('  checkStrategies()  - Check if strategies fired');
    console.log('  checkReadiness()   - Check readiness');
    console.log('  checkPatterns()    - Pattern signals');
    console.log('  help()             - Show this help');
    console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
};

console.log('✅ Debug commands loaded! Type help()');