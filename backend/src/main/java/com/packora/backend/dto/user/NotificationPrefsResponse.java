package com.packora.backend.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPrefsResponse {
    private Boolean orderUpdates;
    private Boolean shippingAlerts;
    private Boolean promotions;
    private Boolean newsletter;
}
