package org.example.groceryguru.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ListNotificationService {

    private final SimpMessagingTemplate messaging;

    public ListNotificationService(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /**
     * Broadcast a change to everyone subscribed to this list.
     * Clients subscribe to /topic/list/{listId}
     */
    public void notifyListChanged(Long listId, String action, Object payload) {
        messaging.convertAndSend(
            "/topic/list/" + listId,
            Map.of("action", action, "data", payload != null ? payload : Map.of())
        );
    }
}
