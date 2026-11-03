package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.solax.SolaxSamples;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//the GAIDs of the address from the register of Tauron (HAS-337): the one town and the one street of the names of the
//manifest - the phrase of the search finds more. The shapes of the answers of 2026-11-03, the names invented
class TauronAddressTest {

    @Test
    void should_take_the_town_of_the_name_among_those_of_the_phrase() {
        final String towns = """
            [{"GUS":"1","GAID":104,"OwnerGAID":20,"Name":"Testowo Dolne"},
             {"GUS":"2","GAID":105,"OwnerGAID":20,"Name":"Testowo"}]""";

        assertThat(TauronAddress.cityOf(SolaxSamples.JSON.readTree(towns), "testowo")).isEqualTo(105);
    }

    @Test
    void should_take_the_street_by_its_short_name() {
        final String streets = """
            [{"StreetGUS":"1","Prefix":"ul.","FullName":"ul. ks. Jana Przykładowa","ShortName":"Przykładowa","GAID":751,
              "OwnerGAID":105,"Name":"Przykładowa"}]""";

        assertThat(TauronAddress.streetOf(SolaxSamples.JSON.readTree(streets), "Przykladowa")).isEqualTo(751);
    }

    @Test
    void should_refuse_a_name_the_register_does_not_have() {
        assertThatThrownBy(() -> TauronAddress.cityOf(SolaxSamples.JSON.readTree("[]"), "Testowo"))
            .isInstanceOf(TauronException.class)
            .hasMessage("Tauron's register has 0 towns of the name of the manifest");
    }

    @Test
    void should_refuse_a_name_of_two_towns() {
        final String towns = """
            [{"GAID":104,"Name":"Testowo"},{"GAID":105,"Name":"Testowo"}]""";

        assertThatThrownBy(() -> TauronAddress.cityOf(SolaxSamples.JSON.readTree(towns), "Testowo"))
            .isInstanceOf(TauronException.class)
            .hasMessage("Tauron's register has 2 towns of the name of the manifest");
    }
}
