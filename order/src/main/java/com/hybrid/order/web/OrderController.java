package com.hybrid.order.web;

import java.net.URI;

import com.hybrid.order.domain.Order;
import com.hybrid.order.domain.OrderRepository;
import com.hybrid.order.service.CreateOrderCommand;
import com.hybrid.order.service.OrderService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 도메인의 HTTP 진입점.
 *
 * 책임 분리:
 *   - 비즈니스 로직 → OrderService (도메인 흐름).
 *   - HTTP 규약 → 이 컨트롤러 (상태 코드, 헤더, 경로).
 *
 * 컨트롤러는 로직을 갖지 않고, 들어오는 입력을 Service에 위임만 한다.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    // 단순 조회는 Repository 직접 사용 — 비즈니스 로직 없음.
    private final OrderRepository orderRepository;

    public OrderController(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    /**
     * 주문 생성 — REST 표준에 맞춰 201 Created + Location 헤더.
     * @RequestBody가 JSON을 CreateOrderCommand record로 자동 바인딩.
     */
    @PostMapping
    public ResponseEntity<Void> create(@RequestBody CreateOrderCommand cmd) {
        // Service 호출 → 트랜잭션 안에서 도메인 변경 + 이벤트 발행.
        Long id = orderService.create(cmd);
        // REST 컨벤션 — 새 리소스 위치를 Location 헤더로 반환.
        return ResponseEntity.created(URI.create("/api/orders/" + id)).build();
    }

    /**
     * 단일 조회 — 없으면 404.
     * map/orElseGet 패턴으로 null 처리를 fluent하게.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Order> get(@PathVariable Long id) {
        return orderRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
