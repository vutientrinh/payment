package com.example.payment.product.api.response;

import java.math.BigDecimal;

public record ProductResponse(
    Long id, String productName, String productDescription, BigDecimal unitPrice) {}
