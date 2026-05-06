package com.packora.backend.repository;

import com.packora.backend.model.Payment;
import com.packora.backend.model.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * JPQL: navigate through the @ManyToOne Order relationship to filter by order.id.
     * This avoids the Hibernate "cannot traverse collection" error that would occur
     * if we used a plain Spring Data method name (Payment has no direct orderId field).
     */
    @Query("SELECT p FROM Payment p WHERE p.order.id = :orderId")
    List<Payment> findByOrderId(@Param("orderId") Long orderId);

    Optional<Payment> findByTransactionId(String transactionId);

    List<Payment> findByStatus(PaymentStatus status);

    /**
     * Looks up the PENDING placeholder we stored before Paymob assigns a real txn ID.
     * The placeholder format is: "PAYMOB_ORDER_<paymobOrderId>"
     */
    Optional<Payment> findByTransactionIdAndStatus(String transactionId, PaymentStatus status);
}
