package com.example.payment.order.application.command;

public record CreateOrderItemCommand(Long productId, Long quantity) {}
