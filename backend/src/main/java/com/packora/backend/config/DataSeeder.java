package com.packora.backend.config;

import com.packora.backend.model.Admin;
import com.packora.backend.model.Product;
import com.packora.backend.model.SupportStaff;
import com.packora.backend.repository.ProductRepository;
import com.packora.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    /** The well-known product name used by the 3D Box Design editor for cart integration. */
    public static final String CUSTOM_BOX_PRODUCT_NAME = "Custom 3D Box";

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository, ProductRepository productRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        seedAdminUser();
        seedSupportUsers();
        seedCustomBoxProduct();
    }

    private void seedAdminUser() {
        String adminEmail    = "adminpackora@gmail.com";
        String adminUsername = "admin";
        String adminPassword = "Packora@Admin2026";

        userRepository.findByUsername(adminUsername).ifPresentOrElse(
            existingAdmin -> {
                // Always sync the password so this deployment matches the expected credentials
                existingAdmin.setPassword(passwordEncoder.encode(adminPassword));
                userRepository.save(existingAdmin);
                log.info("Admin user already exists — password synced for username: {}", adminUsername);
            },
            () -> {
                log.info("Seeding default Admin user: {}", adminEmail);
                Admin admin = new Admin();
                admin.setUsername(adminUsername);
                admin.setEmail(adminEmail);
                admin.setPassword(passwordEncoder.encode(adminPassword));
                admin.setPermissionsLevel("ALL");
                userRepository.save(admin);
                log.info("Default Admin user seeded successfully.");
            }
        );
    }

    private void seedSupportUsers() {
        String support1Email = "support.packora1@gmail.com";
        String support1Username = "support1";
        if (userRepository.existsByEmail(support1Email) || userRepository.existsByUsername(support1Username)) {
            log.info("Support user 1 (email: {} or username: {}) already exists. Skipping seeding.", support1Email, support1Username);
        } else {
            log.info("Seeding default Support user 1: {}", support1Email);
            SupportStaff support1 = new SupportStaff();
            support1.setUsername(support1Username);
            support1.setEmail(support1Email);
            support1.setPassword(passwordEncoder.encode("Packora@Support2026"));
            support1.setShiftTime("DAY");
            userRepository.save(support1);
            log.info("Default Support user 1 seeded successfully.");
        }

        String support2Email = "support.packora2@gmail.com";
        String support2Username = "support2";
        if (userRepository.existsByEmail(support2Email) || userRepository.existsByUsername(support2Username)) {
            log.info("Support user 2 (email: {} or username: {}) already exists. Skipping seeding.", support2Email, support2Username);
        } else {
            log.info("Seeding default Support user 2: {}", support2Email);
            SupportStaff support2 = new SupportStaff();
            support2.setUsername(support2Username);
            support2.setEmail(support2Email);
            support2.setPassword(passwordEncoder.encode("Packora@Support2026"));
            support2.setShiftTime("NIGHT");
            userRepository.save(support2);
            log.info("Default Support user 2 seeded successfully.");
        }
    }

    /**
     * Seeds a sentinel "Custom 3D Box" product that serves as the product reference
     * for user-designed custom boxes from the 3D editor. The price is set to 0 because
     * the actual price is computed dynamically from the packaging quote API.
     */
    private void seedCustomBoxProduct() {
        productRepository.findByName(CUSTOM_BOX_PRODUCT_NAME).ifPresentOrElse(
            existing -> log.info("Custom Box product already exists (id={}). Skipping seeding.", existing.getId()),
            () -> {
                log.info("Seeding sentinel product: {}", CUSTOM_BOX_PRODUCT_NAME);
                Product customBox = new Product();
                customBox.setName(CUSTOM_BOX_PRODUCT_NAME);
                customBox.setDescription("A fully customizable 3D packaging box designed in the Packora editor.");
                customBox.setPrice(0.0);
                customBox.setCategory("custom");
                customBox.setInStock(true);
                customBox.setStock(999999);
                customBox.setMinOrder(1);
                customBox.setImageUrl("");
                Product saved = productRepository.save(customBox);
                log.info("Sentinel product '{}' seeded with id={}.", CUSTOM_BOX_PRODUCT_NAME, saved.getId());
            }
        );
    }
}
