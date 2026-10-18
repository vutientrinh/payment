package com.example.payment.customer.api;

import com.example.payment.customer.api.request.CreateCustomerRequest;
import com.example.payment.customer.api.request.UpdateCustomerRequest;
import com.example.payment.customer.api.response.CustomerResponse;
import com.example.payment.customer.application.UserService;
import com.example.payment.customer.application.dto.CustomerView;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {
  private final UserService userService;

  @GetMapping
  public ResponseEntity<List<CustomerResponse>> getAllCustomers() {
    return ResponseEntity.ok(
        userService.getAllCustomers().stream().map(CustomerController::toResponse).toList());
  }

  @GetMapping("/{id}")
  public ResponseEntity<CustomerResponse> getCustomer(@PathVariable Long id) {
    return ResponseEntity.ok(toResponse(userService.getCustomerById(id)));
  }

  @PostMapping
  public ResponseEntity<CustomerResponse> createCustomer(
      @Valid @RequestBody CreateCustomerRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            toResponse(
                userService.createCustomer(
                    request.firstName(),
                    request.lastName(),
                    request.email(),
                    request.phoneNumber())));
  }

  @PutMapping("/{id}")
  public ResponseEntity<CustomerResponse> updateCustomer(
      @PathVariable Long id, @Valid @RequestBody UpdateCustomerRequest request) {
    return ResponseEntity.ok(
        toResponse(
            userService.updateCustomer(
                id,
                request.firstName(),
                request.lastName(),
                request.email(),
                request.phoneNumber())));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteCustomer(@PathVariable Long id) {
    userService.deleteCustomer(id);
    return ResponseEntity.noContent().build();
  }

  private static CustomerResponse toResponse(CustomerView customer) {
    return new CustomerResponse(
        customer.id(),
        customer.firstName(),
        customer.lastName(),
        customer.email(),
        customer.phoneNumber());
  }
}
