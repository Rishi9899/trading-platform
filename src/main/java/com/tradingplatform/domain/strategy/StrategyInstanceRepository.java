package com.tradingplatform.domain.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategyInstanceRepository
        extends JpaRepository<StrategyInstance, Long> {

    List<StrategyInstance> findByStrategyId(Long strategyId);
}