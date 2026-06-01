package com.packora.backend.service;

import com.packora.backend.dto.admin.AdminDashboardResponse;
import com.packora.backend.dto.admin.DashboardStatsResponse;
import com.packora.backend.dto.admin.RevenueChartResponse;
import com.packora.backend.dto.admin.TopProductResponse;
import com.packora.backend.dto.order.OrderResponse;
import com.packora.backend.model.Order;
import com.packora.backend.model.enums.OrderStatus;
import com.packora.backend.repository.OrderItemRepository;
import com.packora.backend.repository.OrderRepository;
import com.packora.backend.repository.ProductRepository;
import com.packora.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AdminAnalyticsServiceImpl implements AdminAnalyticsService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    @Autowired
    public AdminAnalyticsServiceImpl(OrderRepository orderRepository,
                                     OrderItemRepository orderItemRepository,
                                     UserRepository userRepository,
                                     ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
    }

    @Override
    public DashboardStatsResponse getDashboardStats() {
        long totalOrders = orderRepository.countOrders();
        Double sum = orderRepository.sumRevenue();
        double totalRevenue = (sum != null) ? sum : 0.0;
        
        long activeCustomers = userRepository.count();
        long productCount = productRepository.count();

        return new DashboardStatsResponse(totalRevenue, totalOrders, activeCustomers, productCount);
    }

    @Override
    public List<RevenueChartResponse> getRevenueChart(int months) {
        // Calculate start date (first day of the month N months ago)
        java.time.LocalDateTime startDate = java.time.LocalDateTime.now()
                .minusMonths(months - 1)
                .withDayOfMonth(1)
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        List<Object[]> dbResults = orderRepository.getMonthlyRevenue(startDate);

        // Map results by formatted month key
        Map<String, Object[]> resultsMap = dbResults.stream()
                .collect(Collectors.toMap(
                        row -> row[0].toString().trim(),
                        row -> row,
                        (existing, replacement) -> existing
                ));

        YearMonth currentMonth = YearMonth.now();
        List<RevenueChartResponse> chart = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM yyyy"); // "May 2026" / "Jan 2026"

        for (int i = months - 1; i >= 0; i--) {
            YearMonth ym = currentMonth.minusMonths(i);
            String monthKey = ym.format(formatter); // e.g. "May 2026"

            long orderCount = 0;
            double revenue = 0.0;

            if (resultsMap.containsKey(monthKey)) {
                Object[] row = resultsMap.get(monthKey);
                orderCount = ((Number) row[1]).longValue();
                revenue = ((Number) row[2]).doubleValue();
            }

            chart.add(new RevenueChartResponse(monthKey, orderCount, revenue));
        }

        return chart;
    }

    @Override
    public List<TopProductResponse> getTopProducts(int limit) {
        return orderItemRepository.findTopProducts(PageRequest.of(0, limit));
    }

    @Override
    public AdminDashboardResponse getFullDashboard() {
        DashboardStatsResponse stats = getDashboardStats();
        List<TopProductResponse> topProducts = getTopProducts(5);
        
        List<Order> recentEntities = orderRepository.findTop5ByOrderByOrderDateDesc();
        List<OrderResponse> recentOrders = recentEntities.stream()
                .map(this::mapOrderToResponse)
                .collect(Collectors.toList());

        return new AdminDashboardResponse(stats, topProducts, recentOrders);
    }
    
    // Quick mapper for recent orders widget
    private OrderResponse mapOrderToResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .userId(order.getUser().getId())
                .userName(order.getUser().getUsername())
                .userEmail(order.getUser().getEmail())
                .orderDate(order.getOrderDate())
                .status(order.getStatus().name().toLowerCase())
                .totalAmount(order.getTotalAmount())
                .shippingAddress(order.getShippingAddress())
                .build();
    }
}
