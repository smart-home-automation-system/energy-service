package cloud.cholewa.energy.config;

import org.junit.jupiter.api.Test;
import org.zalando.logbook.BodyFilter;

import static org.assertj.core.api.Assertions.assertThat;

//what Logbook may not write of the SolaX cloud (HAS-329): the token of an answer, the secret of the token request, the
//token an invalid-token answer quotes back
class LogbookConfigTest {

    private final BodyFilter filter = new LogbookConfig().solaxCloudBodyFilter();

    @Test
    void should_mask_the_token_the_cloud_gives() {
        assertThat(filter.filter("application/json", """
            {"code":0,"result":{"access_token":"a1b2c3","expires_in":2592000}}"""))
            .doesNotContain("a1b2c3");
    }

    @Test
    void should_mask_the_secret_of_the_token_request() {
        assertThat(filter.filter("application/x-www-form-urlencoded",
            "client_id=client&client_secret=s3cr3t&grant_type=client_credentials"))
            .doesNotContain("s3cr3t")
            .contains("client_id=client");
    }

    @Test
    void should_mask_the_token_an_invalid_token_answer_quotes_back() {
        assertThat(filter.filter("application/json", """
            {"code":10402,"message":"Invalid access token: a1b2c3"}"""))
            .isEqualTo("""
                {"code":10402,"message":"Invalid access token: XXX"}""");
    }

    @Test
    void should_leave_other_bodies_alone() {
        assertThat(filter.filter("application/json", """
            {"code":10000,"result":{"dailyYield":2.5,"plantLocalTime":"2026-11-01 12:55:27"}}"""))
            .contains("\"dailyYield\":2.5").contains("2026-11-01 12:55:27");
    }
}
