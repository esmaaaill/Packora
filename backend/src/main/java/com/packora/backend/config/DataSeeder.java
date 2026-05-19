package com.packora.backend.config;

import com.packora.backend.model.Admin;
import com.packora.backend.model.SupportStaff;
import com.packora.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        seedAdminUser();
        // seedSupportUsers();
    }

    private void seedAdminUser() {
        String adminEmail = "adminpackora@gmail.com";
        if (!userRepository.existsByEmail(adminEmail)) {
            log.info("Seeding default Admin user: {}", adminEmail);
            Admin admin = new Admin();
            admin.setUsername("admin");
            admin.setEmail(adminEmail);
            admin.setPassword(passwordEncoder.encode("Packora@Admin2026"));
            admin.setPermissionsLevel("ALL");
            userRepository.save(admin);
            log.info("Default Admin user seeded successfully.");
        } else {
            log.debug("Admin user {} already exists. Skipping seeding.", adminEmail);
        }
    }
}
