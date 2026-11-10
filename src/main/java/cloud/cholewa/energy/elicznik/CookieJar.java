package cloud.cholewa.energy.elicznik;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

//The cookies of one login to the eLicznik portal (HAS-340): the login of Tauron and the portal keep their sessions in
//cookies, and a WebClient keeps none. Kept by the domain a cookie names, or by the host that set it; sent to that host and
//the hosts below the domain. Path, expiry and Secure are not tracked - a jar lives for one pass, every cookie over https.
//Never logged: a session cookie is the account for as long as it lives
final class CookieJar {

    private final Map<String, Map<String, String>> byDomain = new ConcurrentHashMap<>();

    //every Set-Cookie of an answer from "from"; a cookie set empty or with Max-Age=0 is removed
    void store(final URI from, final List<String> setCookies) {
        for (final String setCookie : setCookies) {
            final String[] parts = setCookie.split(";");
            final int equals = parts[0].indexOf('=');
            if (equals <= 0) {
                continue;
            }
            final String name = parts[0].substring(0, equals).trim();
            final String value = parts[0].substring(equals + 1).trim();
            String domain = host(from);
            boolean removed = value.isEmpty();
            for (int index = 1; index < parts.length; index++) {
                final String attribute = parts[index].trim();
                final String lower = attribute.toLowerCase(Locale.ROOT);
                if (lower.startsWith("domain=")) {
                    domain = lower.substring("domain=".length()).replaceFirst("^\\.", "");
                } else if (lower.equals("max-age=0")) {
                    removed = true;
                }
            }
            final Map<String, String> cookies = byDomain.computeIfAbsent(domain, key -> new ConcurrentHashMap<>());
            if (removed) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }

    //the Cookie header for "to"; empty when the jar has none for it
    String headerFor(final URI to) {
        final String host = host(to);
        return byDomain.entrySet().stream()
            .filter(entry -> host.equals(entry.getKey()) || host.endsWith("." + entry.getKey()))
            .flatMap(entry -> entry.getValue().entrySet().stream())
            .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
            .collect(Collectors.joining("; "));
    }

    private static String host(final URI uri) {
        return String.valueOf(uri.getHost()).toLowerCase(Locale.ROOT);
    }
}
