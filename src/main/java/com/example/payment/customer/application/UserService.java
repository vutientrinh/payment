package com.example.payment.customer.application;

import com.example.payment.customer.application.dto.CustomerView;
import java.util.List;

public interface UserService {
  List<CustomerView> getAllCustomers();

  CustomerView getCustomerById(Long id);

  CustomerView createCustomer(String firstName, String lastName, String email, String phoneNumber);

  CustomerView updateCustomer(
      Long id, String firstName, String lastName, String email, String phoneNumber);

  void deleteCustomer(Long id);
}
