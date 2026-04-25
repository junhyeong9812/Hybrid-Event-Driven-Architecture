package com.hybrid.order.web;

import com.hybrid.order.service.OrderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean
    OrderService orderService;

    @Test
    void POST_api_orders는_201과_Location_헤더를_반환한다() throws Exception {
        given(orderService.create(any())).willReturn(42L);

        mockMvc.perform(post("/api/orders")
                        .contentType(APPLICATION_JSON)
                        .content("""
                    { "customerId": 1, "amount": 1000 }
                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/42"));
    }
}