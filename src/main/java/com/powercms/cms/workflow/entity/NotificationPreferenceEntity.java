package com.powercms.cms.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "notification_preference")
public class NotificationPreferenceEntity {

    public static final String CHANNEL_IN_APP = "IN_APP";
    public static final String CHANNEL_EMAIL = "EMAIL";

    @Id private UUID id;
    @Column(nullable = false, length = 100)
    private String username;
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;
    @Column(nullable = false, length = 16)
    private String channel;
    @Column(nullable = false)
    private boolean enabled = true;

    public static NotificationPreferenceEntity of(String username, String eventType, String channel, boolean enabled) {
        var e = new NotificationPreferenceEntity();
        e.id = UUID.randomUUID();
        e.username = username;
        e.eventType = eventType;
        e.channel = channel;
        e.enabled = enabled;
        return e;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEventType() { return eventType; }
    public String getChannel() { return channel; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { this.enabled = v; }
}
