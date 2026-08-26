let currentChart = null;
let currentEventSource = null;

async function initTradingVisualizer(symbol) {
    const statusEl = document.getElementById('status');
    const container = document.getElementById('chart-container');
    const timeframe = "5m";

    // Clean up old chart instance and stream if switching symbols
    if (currentChart) {
        currentChart.remove();
        currentChart = null;
    }
    if (currentEventSource) {
        currentEventSource.close();
        currentEventSource = null;
    }

    // Clear previous view box content
    container.innerHTML = '';

    try {
        statusEl.innerText = `Fetching historical data for ${symbol} (${timeframe})...`;

        // 1. Fetch historical data from Spring Boot
        const response = await fetch(`/visualizer/api/history?symbol=${encodeURIComponent(symbol)}&timeframe=${timeframe}`);

        if (!response.ok) {
            throw new Error(`HTTP error! Status: ${response.status}`);
        }

        const rawData = await response.json();
        statusEl.innerText = `Loaded ${rawData.length} historical bars for ${symbol}. Building chart...`;

        // 2. Initialize TradingView Chart
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

        // 3. Add Candlestick Series
        const candlestickSeries = currentChart.addSeries(LightweightCharts.CandlestickSeries, {
            upColor: '#26a69a',
            downColor: '#ef5350',
            borderVisible: false,
            wickUpColor: '#26a69a',
            wickDownColor: '#ef5350',
        });

        candlestickSeries.setData(rawData.map(item => ({
            time: item.time,
            open: item.open,
            high: item.high,
            low: item.low,
            close: item.close,
        })));

        // 4. Add Volume Histogram Series
        const volumeSeries = currentChart.addSeries(LightweightCharts.HistogramSeries, {
            color: '#26a69a',
            priceFormat: { type: 'volume' },
            priceScaleId: '',
        });

        currentChart.priceScale('').applyOptions({
            scaleMargins: { top: 0.8, bottom: 0 },
        });

        volumeSeries.setData(rawData.map(item => ({
            time: item.time,
            value: item.volume,
            color: item.close >= item.open ? 'rgba(38, 166, 154, 0.5)' : 'rgba(239, 83, 80, 0.5)',
        })));

        // 5. Connect to SSE Live Stream for the active symbol
        statusEl.innerText = `Chart active. Connecting to live stream for ${symbol}...`;

        currentEventSource = new EventSource(`/visualizer/api/stream?symbol=${encodeURIComponent(symbol)}&timeframe=${timeframe}`);

        currentEventSource.addEventListener('candle', (event) => {
            const liveCandle = JSON.parse(event.data);

            candlestickSeries.update({
                time: liveCandle.time,
                open: liveCandle.open,
                high: liveCandle.high,
                low: liveCandle.low,
                close: liveCandle.close,
            });

            volumeSeries.update({
                time: liveCandle.time,
                value: liveCandle.volume,
                color: liveCandle.close >= liveCandle.open ? 'rgba(38, 166, 154, 0.5)' : 'rgba(239, 83, 80, 0.5)',
            });

            statusEl.innerText = `Live Stream Connected [${symbol}] | Last Update: ${new Date(liveCandle.time * 1000).toLocaleTimeString()}`;
        });

        currentEventSource.onerror = (err) => {
            console.error("SSE stream error:", err);
            statusEl.innerText = `Live stream connection interrupted for ${symbol}. Reconnecting...`;
        };

        // Window resize handler
        window.addEventListener('resize', () => {
            if (currentChart) {
                currentChart.applyOptions({
                    width: container.clientWidth,
                    height: container.clientHeight
                });
            }
        });

    } catch (error) {
        console.error('Initialization error:', error);
        statusEl.innerText = `Failed to load chart for ${symbol}. Check console logs.`;
    }
}

// Event listener for dropdown changes
const symbolSelect = document.getElementById('symbol-select');
symbolSelect.addEventListener('change', (event) => {
    initTradingVisualizer(event.target.value);
});

// Initial boot load with the default selected symbol
initTradingVisualizer(symbolSelect.value);