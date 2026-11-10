package cloud.cholewa.energy.elicznik;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//the cookies of one login (HAS-340): by the host that set them or the domain they name, removed when set empty or expired
class CookieJarTest {

    private static final URI LOGIN = URI.create("https://logowanie.tauron-dystrybucja.pl/realms/ext/protocol/cas/login");
    private static final URI PORTAL = URI.create("https://elicznik.tauron-dystrybucja.pl/energia/api");

    @Test
    void should_send_a_cookie_only_to_the_host_that_set_it() {
        final CookieJar jar = new CookieJar();
        jar.store(LOGIN, List.of("AUTH_SESSION_ID=kc1; Path=/realms/ext/; Secure; HttpOnly"));

        assertThat(jar.headerFor(LOGIN)).isEqualTo("AUTH_SESSION_ID=kc1");
        assertThat(jar.headerFor(PORTAL)).isEmpty();
    }

    @Test
    void should_send_a_cookie_of_a_domain_to_the_hosts_below_it() {
        final CookieJar jar = new CookieJar();
        jar.store(LOGIN, List.of("shared=s1; Domain=.tauron-dystrybucja.pl; Path=/"));

        assertThat(jar.headerFor(PORTAL)).isEqualTo("shared=s1");
        assertThat(jar.headerFor(URI.create("https://example.com/"))).isEmpty();
    }

    @Test
    void should_replace_and_remove_cookies() {
        final CookieJar jar = new CookieJar();
        jar.store(PORTAL, List.of("PHPSESSID=p1; path=/", "other=o1"));
        jar.store(PORTAL, List.of("PHPSESSID=p2; path=/", "other=; Max-Age=0"));

        assertThat(jar.headerFor(PORTAL)).isEqualTo("PHPSESSID=p2");

        jar.store(PORTAL, List.of("PHPSESSID=deleted; Max-Age=0", "broken"));
        assertThat(jar.headerFor(PORTAL)).isEmpty();
    }
}
