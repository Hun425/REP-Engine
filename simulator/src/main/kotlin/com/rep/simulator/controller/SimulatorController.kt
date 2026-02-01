package com.rep.simulator.controller

import com.rep.simulator.service.TrafficSimulator
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 시뮬레이터 제어 REST API
 *
 * 프론트엔드에서 시뮬레이터를 시작/정지하고 상태를 조회할 수 있습니다.
 */
@RestController
@RequestMapping("/api/v1/simulator")
class SimulatorController(
    private val trafficSimulator: TrafficSimulator
) {

    /**
     * 시뮬레이터 상태 조회
     */
    @GetMapping("/status")
    fun getStatus(): ResponseEntity<TrafficSimulator.SimulationStatus> {
        return ResponseEntity.ok(trafficSimulator.getStatus())
    }

    /**
     * 시뮬레이터 시작
     */
    @PostMapping("/start")
    fun start(
        @RequestParam(defaultValue = "100") userCount: Int,
        @RequestParam(defaultValue = "1000") delayMillis: Long
    ): ResponseEntity<TrafficSimulator.SimulationStatus> {
        trafficSimulator.startSimulation(userCount, delayMillis)
        return ResponseEntity.ok(trafficSimulator.getStatus())
    }

    /**
     * 시뮬레이터 정지
     */
    @PostMapping("/stop")
    fun stop(): ResponseEntity<TrafficSimulator.SimulationStatus> {
        trafficSimulator.stopSimulation()
        return ResponseEntity.ok(trafficSimulator.getStatus())
    }
}
