package com.example.payment.order.application.command;

import java.util.List;

public record CreateOrderCommand(
    String orderCode, Long customerId, List<CreateOrderItemCommand> items) {}
