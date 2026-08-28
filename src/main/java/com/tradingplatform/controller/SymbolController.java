package com.tradingplatform.controller;

import com.tradingplatform.config.MarketDataProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/ui/api")
public class SymbolController {

    private final MarketDataProperties properties;

    public SymbolController(MarketDataProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/symbols")
    public List<String> getSymbols() {
        return properties.getTick().getSymbols();
    }
}