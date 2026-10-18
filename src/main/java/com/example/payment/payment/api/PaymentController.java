package com.example.payment.payment.api;

import com.example.payment.payment.api.request.CreatePaymentRequest;
import com.example.payment.payment.api.request.UpdatePaymentStatusRequest;
import com.example.payment.payment.api.response.PaymentResponse;
import com.example.payment.payment.application.PaymentService;
import com.example.payment.payment.application.dto.PaymentView;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {
  private final PaymentService paymentService;

  @GetMapping
  public ResponseEntity<List<PaymentResponse>> getAllPayments() {
    return ResponseEntity.ok(
        paymentService.getAllPayments().stream().map(PaymentController::toResponse).toList());
  }

  @GetMapping("/{id}")
  public ResponseEntity<PaymentResponse> getPayment(@PathVariable Long id) {
    return ResponseEntity.ok(toResponse(paymentService.getPaymentById(id)));
  }

  @PostMapping
  public ResponseEntity<PaymentResponse> createPayment(
      @Valid @RequestBody CreatePaymentRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(toResponse(paymentService.createPayment(request.orderId(), request.method())));
  }

  @PatchMapping("/{id}/status")
  public ResponseEntity<PaymentResponse> updatePaymentStatus(
      @PathVariable Long id, @Valid @RequestBody UpdatePaymentStatusRequest request) {
    return ResponseEntity.ok(toResponse(paymentService.updatePaymentStatus(id, request.status())));
  }

  private static PaymentResponse toResponse(PaymentView payment) {
    return new PaymentResponse(payment.id(), payment.status(), payment.method(), payment.orderId());
  }
}
