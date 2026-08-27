let currentChart = null;
let currentEventSource = null;
let resizeHandler = null;

let candlestickSeries = null;
let volumeSeries = null;

let currentSymbol = null;
let currentTimeframe = null;

// in-progress candle that moves with ticks
let liveBar = null;
// in-progress volume for current bar bucket
let liveVolume = 0;

// generation guard to ignore stale SSE callbacks
let streamGeneration = 0;

function tfSeconds(tf) {
    switch ((tf || "").toLowerCase()) {
        case "1m": return 60;
        case "3m": return 180;
        case "5m": return 300;
        case "15m": return 900;
        case "30m": return 1800;
        case "1h": return 3600;
        default: return 300;
    }
}

function bucketStart(epochSec, tf) {
    const step = tfSeconds(tf);
    return Math.floor(epochSec / step) * step;
}

function setStatus(text) {
    const el = document.getElementById('status');
    if (el) el.innerText = text;
}

function toNum(v) {
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
}

function safeCloseEventSource() {
    if (currentEventSource) {
        try {
            currentEventSource.close();
        } catch (_) {}
        currentEventSource = null;
    }
}

function cleanup() {
    safeCloseEventSource();

    if (resizeHandler) {
        window.removeEventListener('resize', resizeHandler);
        resizeHandler = null;
    }

    if (currentChart) {
        currentChart.remove();
        currentChart = null;
    }

    candlestickSeries = null;
    volumeSeries = null;
    liveBar = null;
    liveVolume = 0;
}

async function loadHistory(symbol, timeframe) {
    const url = `/ui/api/candles?symbol=${encodeURIComponent(symbol)}&timeframe=${encodeURIComponent(timeframe)}&limit=300`;
    const response = await fetch(url, { cache: "no-store" });

    if (!response.ok) {
        throw new Error(`History API failed: ${response.status}`);
    }

    const rawData = await response.json();

    const bars = rawData.map(item => ({
        time: toNum(item.time),
        open: toNum(item.open),
        high: toNum(item.high),
        low: toNum(item.low),
        close: toNum(item.close),
    }));

    const volumes = rawData.map(item => ({
        time: toNum(item.time),
        value: toNum(item.volume),
        color: toNum(item.close) >= toNum(item.open)
            ? 'rgba(38, 166, 154, 0.5)'
            : 'rgba(239, 83, 80, 0.5)',
    }));

    candlestickSeries.setData(bars);
    volumeSeries.setData(volumes);

    if (bars.length > 0) {
        liveBar = { ...bars[bars.length - 1] };
        liveVolume = toNum(rawData[rawData.length - 1]?.volume);
    } else {
        liveBar = null;
        liveVolume = 0;
    }

    return rawData.length;
}

function connectLive(symbol, timeframe) {
    const myGen = ++streamGeneration;
    safeCloseEventSource();

    const es = new EventSource(`/ui/api/stream/live?symbol=${encodeURIComponent(symbol)}`);
    currentEventSource = es;

    es.onopen = () => {
        if (myGen !== streamGeneration) return;
        setStatus(`Live stream connected [${symbol}] (${timeframe})`);
    };

    es.addEventListener('connected', () => {
        if (myGen !== streamGeneration) return;
        setStatus(`Live stream ready [${symbol}] (${timeframe})`);
    });

    // tick drives moving candle
    es.addEventListener('tick', (event) => {
        if (myGen !== streamGeneration) return;
        if (!candlestickSeries || !volumeSeries) return;

        let tick;
        try {
            tick = JSON.parse(event.data);
        } catch (_) {
            return;
        }

        const price = toNum(tick.price);
        const tsSec = Math.floor(toNum(tick.timestamp) / 1000);

// exact UTC-aligned bucket
        const step = tfSeconds(timeframe);
        const barTime = tsSec - (tsSec % step);

        const tickVol = toNum(tick.volume || 0);

        if (!liveBar || liveBar.time !== barTime) {
            const open = liveBar ? toNum(liveBar.close) : price;
            liveBar = {
                time: barTime,
                open: open,
                high: price,
                low: price,
                close: price
            };
            liveVolume = tickVol;
        } else {
            liveBar.close = price;
            liveBar.high = Math.max(toNum(liveBar.high), price);
            liveBar.low = Math.min(toNum(liveBar.low), price);
            liveVolume = Math.max(0, toNum(liveVolume) + tickVol);
        }

        candlestickSeries.update(liveBar);
        volumeSeries.update({
            time: liveBar.time,
            value: liveVolume,
            color: liveBar.close >= liveBar.open
                ? 'rgba(38, 166, 154, 0.5)'
                : 'rgba(239, 83, 80, 0.5)',
        });

        setStatus(`Live [${symbol}] ${price.toFixed(2)} @ ${new Date(tsSec * 1000).toLocaleTimeString()} (${timeframe})`);
    });

    // backend emits 1m close events; for 1m timeframe, lock exact OHLCV
    es.addEventListener('candle_closed', (event) => {
        if (myGen !== streamGeneration) return;
        if (timeframe !== "1m") return;
        if (!candlestickSeries || !volumeSeries) return;

        let c;
        try {
            c = JSON.parse(event.data);
        } catch (_) {
            return;
        }

        const closed = {
            time: toNum(c.time),
            open: toNum(c.open),
            high: toNum(c.high),
            low: toNum(c.low),
            close: toNum(c.close),
        };

        candlestickSeries.update(closed);
        volumeSeries.update({
            time: toNum(c.time),
            value: toNum(c.volume),
            color: closed.close >= closed.open
                ? 'rgba(38, 166, 154, 0.5)'
                : 'rgba(239, 83, 80, 0.5)',
        });

        liveBar = { ...closed };
        liveVolume = 0;
    });

    es.onerror = () => {
        if (myGen !== streamGeneration) return;
        // Browser auto-reconnects EventSource
        setStatus(`Live stream interrupted [${symbol}]... reconnecting`);
    };
}

function buildChart() {
    const container = document.getElementById('chart-container');
    container.innerHTML = '';

    currentChart = LightweightCharts.createChart(container, {
        width: container.clientWidth,
        height: container.clientHeight,
        layout: {
            background: { color: '#131722' },
            textColor: '#d1d4dc',
        },
        grid: {
            vertLines: { color: '#1f293d' },
            horzLines: { color: '#1f293d' },
        },
        timeScale: {
            timeVisible: true,
            secondsVisible: false,
        },
    });

    candlestickSeries = currentChart.addSeries(LightweightCharts.CandlestickSeries, {
        upColor: '#26a69a',
        downColor: '#ef5350',
        borderVisible: false,
        wickUpColor: '#26a69a',
        wickDownColor: '#ef5350',
    });

    volumeSeries = currentChart.addSeries(LightweightCharts.HistogramSeries, {
        color: '#26a69a',
        priceFormat: { type: 'volume' },
        priceScaleId: '',
    });

    currentChart.priceScale('').applyOptions({
        scaleMargins: { top: 0.8, bottom: 0 },
    });

    resizeHandler = () => {
        if (currentChart) {
            currentChart.applyOptions({
                width: container.clientWidth,
                height: container.clientHeight
            });
        }
    };
    window.addEventListener('resize', resizeHandler);
}

async function initTradingVisualizer(symbol, timeframe) {
    cleanup();
    buildChart();

    currentSymbol = symbol;
    currentTimeframe = timeframe;

    try {
        setStatus(`Fetching historical data for ${symbol} (${timeframe})...`);
        const count = await loadHistory(symbol, timeframe);
        setStatus(`Loaded ${count} bars. Connecting live stream for ${symbol}...`);
        connectLive(symbol, timeframe);
    } catch (error) {
        console.error('Initialization error:', error);
        setStatus(`Failed to load chart for ${symbol}. Check console logs.`);
    }
}

const symbolSelect = document.getElementById('symbol-select');
const timeframeSelect = document.getElementById('timeframe-select');

symbolSelect.addEventListener('change', () => {
    initTradingVisualizer(symbolSelect.value, timeframeSelect.value);
});

timeframeSelect.addEventListener('change', () => {
    initTradingVisualizer(symbolSelect.value, timeframeSelect.value);
});

// close stream when tab/window is closing
window.addEventListener('beforeunload', () => {
    safeCloseEventSource();
});

// initial load
initTradingVisualizer(symbolSelect.value, timeframeSelect.value);