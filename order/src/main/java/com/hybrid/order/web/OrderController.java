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

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public OrderController(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody CreateOrderCommand cmd) {
        Long id = orderService.create(cmd);
        return ResponseEntity.created(URI.create("/api/orders/" + id)).build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> get(@PathVariable Long id) {
        return orderRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}