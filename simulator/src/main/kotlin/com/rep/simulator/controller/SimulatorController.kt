package com.rep.simulator.controller

import com.rep.simulator.service.InventorySimulator
import com.rep.simulator.service.TrafficSimulator
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 시뮬레이터 제어 REST API
 *
 * 프론트엔드에서 시뮬레이터를 시작/정지하고 상태를 조회할 수 있습니다.
 */
@RestController
@Validated
@RequestMapping("/api/v1/simulator")
class SimulatorController(
    private val trafficSimulator: TrafficSimulator,
    private val inventorySimulator: InventorySimulator,
) {
    /**
     * 시뮬레이터 상태 조회
     */
    @GetMapping("/status")
    fun getStatus(): ResponseEntity<TrafficSimulator.SimulationStatus> = ResponseEntity.ok(trafficSimulator.getStatus())

    /**
     * 시뮬레이터 시작
     */
    @PostMapping("/start")
    fun start(
        @RequestParam(defaultValue = "100") @Min(1) userCount: Int,
        @RequestParam(defaultValue = "1000") @Min(1) delayMillis: Long,
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

    // === Inventory Simulator ===

    @GetMapping("/inventory/status")
    fun getInventoryStatus(): ResponseEntity<InventorySimulator.InventorySimulationStatus> =
        ResponseEntity.ok(inventorySimulator.getStatus())

    @PostMapping("/inventory/start")
    fun startInventory(): ResponseEntity<InventorySimulator.InventorySimulationStatus> {
        inventorySimulator.startSimulation()
        return ResponseEntity.ok(inventorySimulator.getStatus())
    }

    @PostMapping("/inventory/stop")
    fun stopInventory(): ResponseEntity<InventorySimulator.InventorySimulationStatus> {
        inventorySimulator.stopSimulation()
        return ResponseEntity.ok(inventorySimulator.getStatus())
    }
}
