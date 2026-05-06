package com.packora.backend.config;

import com.packora.backend.model.Admin;
import com.packora.backend.model.BusinessOwner;
import com.packora.backend.model.Order;
import com.packora.backend.model.Product;
import com.packora.backend.model.enums.OrderStatus;
import com.packora.backend.repository.OrderRepository;
import com.packora.backend.repository.ProductRepository;
import com.packora.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * TestDataInitializer — seeds the H2 in-memory database with ready-to-use test data.
 *
 * Only active on the "h2" Spring profile — NEVER runs against PostgreSQL production.
 *
 * Seeds on startup:
 *   • 1 BusinessOwner user  (testuser / TestPass123!)
 *   • 1 Admin user          (adminuser / AdminPass123!)
 *   • 2 Products            (Eco Box @ 175 EGP, Mailer Bag @ 75 EGP)
 *   • 1 PENDING Order       (orderId=1, total=350 EGP, userId=1)
 *
 * This lets you test the full payment flow immediately without manual setup.
 */
@Component
@Profile("h2")
public class TestDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TestDataInitializer.class);

    private final UserRepository    userRepository;
    private final ProductRepository productRepository;
    private final OrderRepository   orderRepository;
    private final PasswordEncoder   passwordEncoder;

    public TestDataInitializer(UserRepository userRepository,
                               ProductRepository productRepository,
                               OrderRepository orderRepository,
                               PasswordEncoder passwordEncoder) {
        this.userRepository    = userRepository;
        this.productRepository = productRepository;
        this.orderRepository   = orderRepository;
        this.passwordEncoder   = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        log.info("[TestDataInitializer] Seeding H2 database...");

        // ── 1. Business Owner (test user who places orders) ────────────────
        BusinessOwner testUser = new BusinessOwner();
        testUser.setUsername("testuser");
        testUser.setEmail("test@packora.com");
        testUser.setPassword(passwordEncoder.encode("TestPass123!"));
        testUser.setCompanyName("Packora Demo Co.");
        testUser = (BusinessOwner) userRepository.save(testUser);
        log.info("[TestDataInitializer] BusinessOwner saved — id={}", testUser.getId());

        // ── 2. Admin user ──────────────────────────────────────────────────
        Admin adminUser = new Admin();
        adminUser.setUsername("adminuser");
        adminUser.setEmail("admin@packora.com");
        adminUser.setPassword(passwordEncoder.encode("AdminPass123!"));
        userRepository.save(adminUser);
        log.info("[TestDataInitializer] Admin saved");

        // ── 3. Products ────────────────────────────────────────────────────
        Product product1 = new Product();
        product1.setName("Packora Eco Box");
        product1.setDescription("Eco-friendly kraft shipping box");
        product1.setPrice(175.0);
        product1.setInStock(true);
        product1.setCategory("BOXES");
        product1.setMinOrder(1);
        product1 = productRepository.save(product1);
        log.info("[TestDataInitializer] Product 1 saved — id={}", product1.getId());

        Product product2 = new Product();
        product2.setName("Packora Mailer Bag");
        product2.setDescription("Biodegradable poly mailer bag");
        product2.setPrice(75.0);
        product2.setInStock(true);
        product2.setCategory("MAILERS");
        product2.setMinOrder(10);
        product2 = productRepository.save(product2);
        log.info("[TestDataInitializer] Product 2 saved — id={}", product2.getId());

        // ── 4. Pre-created PENDING order (use this orderId to test payment) ─
        Order testOrder = new Order();
        testOrder.setUser(testUser);
        testOrder.setStatus(OrderStatus.PENDING);
        testOrder.setTotalAmount(350.0);
        testOrder.setShippingAddress(
            "Ahmed Hassan, Packora Inc., 15 El Tahrir St, Cairo, EG 11511, +201001234567");
        testOrder = orderRepository.save(testOrder);
        log.info("[TestDataInitializer] Order saved — id={}", testOrder.getId());

        log.info("[TestDataInitializer] ✅ Seed complete: " +
                 "user.id={}, product.id={}, order.id={}",
                 testUser.getId(), product1.getId(), testOrder.getId());
    }
}
