package cloud.cholewa.energy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.BodyFilter;
import org.zalando.logbook.core.BodyFilters;

import java.util.regex.Pattern;

//What Logbook may not write (HAS-329). Its default body filter - kept here, a bean of our own replaces it - masks an
//access_token of a JSON answer and a client_secret of a form; the SolaX cloud also quotes an invalid token back in its
//message ("Invalid access token: <token>", code 10402), masked here too. Named apart from the bean of Logbook, which steps
//back for it (ConditionalOnMissingBean) - so the default filter is merged in here. The Authorization header is masked by the
//default header filter of Logbook
@Configuration
public class LogbookConfig {

    static final String MASK = "XXX";
    private static final Pattern QUOTED_TOKEN = Pattern.compile("(Invalid access token: )[^\"]*");

    @Bean
    BodyFilter solaxCloudBodyFilter() {
        return BodyFilter.merge(BodyFilters.defaultValue(), quotedToken());
    }

    static BodyFilter quotedToken() {
        return (contentType, body) -> QUOTED_TOKEN.matcher(body).replaceAll("$1" + MASK);
    }
}
