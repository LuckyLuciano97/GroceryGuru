package org.example.groceryguru.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves index.html for the router's client-side routes so the app survives a
 * refresh or a pasted deep link. Nothing on disk backs /lists or /list/3.
 *
 * Matched by exclusion rather than by listing routes: an explicit list silently
 * broke every tab route when they turned out to be served as bare paths rather
 * than under the (tabs) group. Anything under the reserved prefixes, and
 * anything with a file extension, is left alone so API calls and missing assets
 * still return their own 404 instead of a page of HTML.
 */
@Controller
public class SpaController {

    /** One path segment that is not a reserved prefix and has no extension. */
    private static final String SEGMENT =
            "{path:^(?!api|ws|actuator|swagger-ui|v3|_expo|assets)[^.]*$}";

    @GetMapping({"/" + SEGMENT, "/" + SEGMENT + "/**"})
    public String forwardToApp() {
        return "forward:/index.html";
    }
}
