package com.tradingplatform.domain.signal;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SignalRepository
        extends JpaRepository<Signal, Long> {
}