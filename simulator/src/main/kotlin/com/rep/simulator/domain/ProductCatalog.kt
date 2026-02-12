package com.rep.simulator.domain

import com.rep.event.product.InventoryEventType
import com.rep.event.product.ProductInventoryEvent
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 인벤토리 시뮬레이션용 상품 카탈로그
 *
 * 상품별 현재 가격/재고를 메모리에서 추적하며,
 * 가격 변동 및 재입고 이벤트를 생성합니다.
 */
class ProductCatalog(productCountPerCategory: Int) {

    companion object {
        private val CATEGORIES = listOf(
            "ELECTRONICS", "FASHION", "FOOD", "BEAUTY", "SPORTS", "HOME", "BOOKS"
        )

        private val BASE_PRICES = mapOf(
            "ELECTRONICS" to 100_000..1_500_000,
            "FASHION" to 20_000..300_000,
            "FOOD" to 3_000..50_000,
            "BEAUTY" to 10_000..150_000,
            "SPORTS" to 30_000..500_000,
            "HOME" to 15_000..400_000,
            "BOOKS" to 10_000..40_000
        )
    }

    data class ProductState(
        val productId: String,
        val category: String,
        var price: Float,
        var stock: Int
    )

    private val products = ConcurrentHashMap<String, ProductState>()

    init {
        for (category in CATEGORIES) {
            val priceRange = BASE_PRICES[category] ?: 10_000..100_000
            for (seq in 1..productCountPerCategory) {
                val productId = "PROD-${category.take(3)}-${seq.toString().padStart(5, '0')}"
                products[productId] = ProductState(
                    productId = productId,
                    category = category,
                    price = Random.nextInt(priceRange.first, priceRange.last + 1).toFloat(),
                    stock = Random.nextInt(0, 200)
                )
            }
        }
    }

    /**
     * 가격 변동 이벤트 생성 (10~30% 할인)
     */
    fun generatePriceChange(): ProductInventoryEvent {
        val product = products.values.random()
        val previousPrice = product.price
        val discountRate = Random.nextDouble(0.10, 0.31) // 10~30%
        val newPrice = (previousPrice * (1.0 - discountRate)).toFloat()

        product.price = newPrice

        return ProductInventoryEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setProductId(product.productId)
            .setEventType(InventoryEventType.PRICE_CHANGE)
            .setPreviousPrice(previousPrice)
            .setCurrentPrice(newPrice)
            .setPreviousStock(null)
            .setCurrentStock(null)
            .setTimestamp(Instant.now())
            .setTraceId(UUID.randomUUID().toString())
            .build()
    }

    /**
     * 재입고 이벤트 생성 (재고 0 → 50~200)
     */
    fun generateRestock(): ProductInventoryEvent {
        // 재고 0인 상품 찾기
        val outOfStock = products.values.filter { it.stock == 0 }
        val product = if (outOfStock.isNotEmpty()) {
            outOfStock.random()
        } else {
            // 재고 0인 상품이 없으면 랜덤 상품의 재고를 0으로 만든 후 재입고
            products.values.random().also { it.stock = 0 }
        }

        val newStock = Random.nextInt(50, 201)
        product.stock = newStock

        return ProductInventoryEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setProductId(product.productId)
            .setEventType(InventoryEventType.STOCK_CHANGE)
            .setPreviousPrice(null)
            .setCurrentPrice(null)
            .setPreviousStock(0)
            .setCurrentStock(newStock)
            .setTimestamp(Instant.now())
            .setTraceId(UUID.randomUUID().toString())
            .build()
    }

    fun size(): Int = products.size
}
