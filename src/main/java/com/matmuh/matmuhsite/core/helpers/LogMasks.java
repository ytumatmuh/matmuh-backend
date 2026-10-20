package com.matmuh.matmuhsite.core.helpers;

import java.util.LinkedHashSet;

public final class LogMasks {

    private static final String SERVICE_PRINCIPAL_PREFIX = "service:";

    private LogMasks() {}

    // Servis anahtarı kimliği ("service:<uuid>") kişisel veri değil, denetim izi için açık kalır.
    public static String principal(String name) {
        if (name != null && name.startsWith(SERVICE_PRINCIPAL_PREFIX)) {
            return name;
        }
        return email(name);
    }

    public static String queryNames(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }

        var names = new LinkedHashSet<String>();
        for (var pair : query.split("&")) {
            var eq = pair.indexOf('=');
            var name = eq < 0 ? pair : pair.substring(0, eq);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names.isEmpty() ? "" : "?" + String.join("&", names);
    }

    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return "-";
        }

        var at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }

        return email.charAt(0) + "***" + email.substring(at);
    }
}
