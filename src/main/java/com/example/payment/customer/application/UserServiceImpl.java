package com.example.payment.customer.application;

import com.example.payment.customer.application.dto.CustomerView;
import com.example.payment.customer.domain.UserEntity;
import com.example.payment.shared.application.ConflictException;
import com.example.payment.shared.application.ResourceNotFoundException;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
  private final CustomerRepository customerRepository;

  @Override
  @Transactional(readOnly = true)
  public List<CustomerView> getAllCustomers() {
    return customerRepository.findAll().stream().map(CustomerView::from).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public CustomerView getCustomerById(Long id) {
    return CustomerView.from(getCustomer(id));
  }

  @Override
  public CustomerView createCustomer(
      String firstName, String lastName, String email, String phoneNumber) {
    String normalizedEmail = normalizeEmail(email);
    if (customerRepository.existsByEmail(normalizedEmail)) {
      throw new ConflictException("Customer email already exists");
    }

    UserEntity customer = new UserEntity();
    applyChanges(customer, firstName, lastName, normalizedEmail, phoneNumber);
    return CustomerView.from(customerRepository.save(customer));
  }

  @Override
  public CustomerView updateCustomer(
      Long id, String firstName, String lastName, String email, String phoneNumber) {
    UserEntity customer = getCustomer(id);
    String normalizedEmail = normalizeEmail(email);
    if (customerRepository.existsByEmailAndIdNot(normalizedEmail, id)) {
      throw new ConflictException("Customer email already exists");
    }

    applyChanges(customer, firstName, lastName, normalizedEmail, phoneNumber);
    return CustomerView.from(customer);
  }

  @Override
  public void deleteCustomer(Long id) {
    getCustomer(id).deactivate();
  }

  private UserEntity getCustomer(Long id) {
    return customerRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
  }

  private String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private void applyChanges(
      UserEntity customer, String firstName, String lastName, String email, String phoneNumber) {
    customer.setFirstName(firstName);
    customer.setLastName(lastName);
    customer.setEmail(email);
    customer.setPhoneNumber(phoneNumber);
  }
}
