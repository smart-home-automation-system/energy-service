package cloud.cholewa.energy.config;

import org.junit.jupiter.api.Test;
import org.zalando.logbook.BodyFilter;

import static org.assertj.core.api.Assertions.assertThat;

//what Logbook may not write of the SolaX cloud (HAS-329): the token of an answer, the secret of the token request, the
//token an invalid-token answer quotes back; of the Solarman cloud (HAS-330): the secret, the account, the password, the
//tokens and the uid (a number), the appId of the query
class LogbookConfigTest {

    private final BodyFilter filter = new LogbookConfig().maskingBodyFilter();

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

    //HAS-330: the token request of the Solarman cloud is JSON - the secret, the account and the SHA-256 of the password
    @Test
    void should_mask_the_solarman_token_request() {
        assertThat(filter.filter("application/json", """
            {"appSecret":"s3cr3t","email":"owner@example.com","password":"5e884898da28047151d0e56f8dc6292773603d0d6aabbdd6"}"""))
            .doesNotContain("s3cr3t").doesNotContain("owner@example.com").doesNotContain("5e884898");
    }

    @Test
    void should_mask_the_tokens_and_the_account_of_the_solarman_answer() {
        assertThat(filter.filter("application/json", """
            {"access_token":"a1b2c3","token_type":"bearer","refresh_token":"d4e5f6","expires_in":"5183999","uid":77700777,
             "success":true}"""))
            .doesNotContain("a1b2c3").doesNotContain("d4e5f6").doesNotContain("77700777")
            .contains("\"expires_in\":\"5183999\"");
    }

    //the appId travels in the query of the token request
    @Test
    void should_mask_the_solarman_app_id_of_the_query() {
        assertThat(new LogbookConfig().solarmanQueryFilter().filter("appId=2025123456&language=en"))
            .isEqualTo("appId=XXX&language=en");
    }

    //the data of the SOFAR is not touched
    @Test
    void should_leave_the_data_of_the_sofar_alone() {
        assertThat(filter.filter("application/json", """
            {"deviceSn":"SH1234567890ABCDEFGH","dataList":[{"key":"PVTP","value":"200.00","name":"PV Total Power"}]}"""))
            .contains("SH1234567890ABCDEFGH").contains("\"value\":\"200.00\"");
    }

    @Test
    void should_leave_other_bodies_alone() {
        assertThat(filter.filter("application/json", """
            {"code":10000,"result":{"dailyYield":2.5,"plantLocalTime":"2026-11-01 12:55:27"}}"""))
            .contains("\"dailyYield\":2.5").contains("2026-11-01 12:55:27");
    }
}
