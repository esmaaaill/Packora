package com.packora.backend.repository;

import com.packora.backend.model.Order;
import com.packora.backend.model.enums.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

import java.time.LocalDateTime;
import org.springframework.data.repository.query.Param;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserId(Long userId);

    List<Order> findByStatus(OrderStatus status);

    List<Order> findByUserIdOrderByOrderDateDesc(Long userId);

    List<Order> findTop5ByOrderByOrderDateDesc();

    /** Find all orders that share the same bulk session group ID. */
    List<Order> findByBulkGroupId(String bulkGroupId);

    @Query("SELECT SUM(o.totalAmount) FROM Order o WHERE o.status != 'CANCELLED'")
    Double sumRevenue();

    @Query("SELECT COUNT(o) FROM Order o")
    long countOrders();

    @Query(value = "SELECT TO_CHAR(o.order_date, 'Mon YYYY') as month_str, " +
                   "COUNT(o.id) as order_count, " +
                   "COALESCE(SUM(o.total_amount), 0.0) as revenue " +
                   "FROM orders o " +
                   "WHERE o.status != 'CANCELLED' AND o.order_date >= :startDate " +
                   "GROUP BY TO_CHAR(o.order_date, 'Mon YYYY'), DATE_TRUNC('month', o.order_date) " +
                   "ORDER BY DATE_TRUNC('month', o.order_date) ASC", nativeQuery = true)
    List<Object[]> getMonthlyRevenue(@Param("startDate") LocalDateTime startDate);
}

