package com.packora.backend.dto.user;

import lombok.Data;

@Data
public class NotificationPrefsRequest {
    private Boolean orderUpdates;
    private Boolean shippingAlerts;
    private Boolean promotions;
    private Boolean newsletter;
}
