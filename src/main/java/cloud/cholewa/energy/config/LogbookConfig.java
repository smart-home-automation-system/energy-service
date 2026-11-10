package cloud.cholewa.energy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.BodyFilter;
import org.zalando.logbook.HeaderFilter;
import org.zalando.logbook.QueryFilter;
import org.zalando.logbook.core.BodyFilters;
import org.zalando.logbook.core.HeaderFilters;
import org.zalando.logbook.core.QueryFilters;
import org.zalando.logbook.json.JsonBodyFilters;

import java.util.Set;
import java.util.regex.Pattern;

//What Logbook may not write (HAS-329, HAS-330). Its default body filter - kept here, a bean of our own replaces it - masks an
//access_token of a JSON answer and a client_secret of a form; the SolaX cloud also quotes an invalid token back in its
//message ("Invalid access token: <token>", code 10402), masked here too. The token request of the Solarman cloud is JSON
//(appSecret, email and the SHA-256 of the password) and its answer carries a refresh_token and the uid of the account (a
//number) - masked by name, strings and numbers alike. Named apart from the beans of Logbook, which step back for them
//(ConditionalOnMissingBean) - so the default filters are merged in here. The appId of Solarman travels in the query of the token request, masked with the
//default query filter. The Authorization header is masked by the default header filter of Logbook
@Configuration
public class LogbookConfig {

    static final String MASK = "XXX";
    static final Set<String> SOLARMAN_FIELDS = Set.of("appSecret", "email", "password", "access_token", "refresh_token",
        "uid");
    private static final Pattern QUOTED_TOKEN = Pattern.compile("(Invalid access token: )[^\"]*");
    //the login of the eLicznik portal (HAS-340): the account and its password of the form, the session cookies, the codes
    //of the login in the redirects and their query - never in a log
    static final Set<String> ELICZNIK_FORM_FIELDS = Set.of("username", "password");
    static final Set<String> SESSION_HEADERS = Set.of("Cookie", "Set-Cookie", "Location");
    static final Set<String> LOGIN_QUERY = Set.of("ticket", "code", "session_code", "execution", "tab_id", "client_data",
        "state", "nonce", "code_challenge");
    private static final String PORTAL = "tauron-dystrybucja";

    @Bean
    BodyFilter maskingBodyFilter() {
        return BodyFilter.merge(BodyFilter.merge(BodyFilter.merge(BodyFilters.defaultValue(), quotedToken()),
                JsonBodyFilters.replacePrimitiveJsonProperty(SOLARMAN_FIELDS::contains, MASK)),
            BodyFilter.merge(BodyFilters.replaceFormUrlEncodedProperty(ELICZNIK_FORM_FIELDS, MASK), portalPages()));
    }

    @Bean
    QueryFilter solarmanQueryFilter() {
        QueryFilter filter = QueryFilter.merge(QueryFilters.defaultValue(), QueryFilters.replaceQuery("appId", MASK));
        for (final String name : LOGIN_QUERY) {
            filter = QueryFilter.merge(filter, QueryFilters.replaceQuery(name, MASK));
        }
        return filter;
    }

    @Bean
    HeaderFilter sessionHeaderFilter() {
        return HeaderFilter.merge(HeaderFilters.defaultValue(), HeaderFilters.replaceHeaders(SESSION_HEADERS, MASK));
    }

    //a page of the portal or of its login names the owner and the address of the house, and carries the codes of the login:
    //left out whole, its length kept. The JSON of the readings stays (the SOLAX dongle answers text/html too - its answer
    //names no portal)
    static BodyFilter portalPages() {
        return (contentType, body) -> contentType != null && contentType.contains("text/html") && body.contains(PORTAL)
            ? "[a page of the eLicznik portal, " + body.length() + " characters, left out]"
            : body;
    }

    static BodyFilter quotedToken() {
        return (contentType, body) -> QUOTED_TOKEN.matcher(body).replaceAll("$1" + MASK);
    }
}
