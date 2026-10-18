package com.example.payment.product.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record UpdateProductRequest(
    @NotBlank String productName,
    @NotBlank String productDescription,
    @NotNull @Positive BigDecimal unitPrice) {}
