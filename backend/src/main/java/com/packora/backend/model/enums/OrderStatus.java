package com.packora.backend.model.enums;

public enum OrderStatus {
    PENDING,
    PAID,        // Payment confirmed by Paymob webhook
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
