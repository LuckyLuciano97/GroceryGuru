package org.example.groceryguru.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards the Expo Router client-side routes to index.html so the web build can
 * be opened (or refreshed) directly on a deep link. Only the app's own routes are
 * listed - a catch-all would swallow 404s for the API and static assets.
 */
@Controller
public class SpaController {

    @GetMapping({"/login", "/register", "/list/**"})
    public String forwardToApp() {
        return "forward:/index.html";
    }
}
