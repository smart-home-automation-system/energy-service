package cloud.cholewa.energy.tauron;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

//owner, 2026-11-03: the town and the street of the house named - the street's; the town alone - the town's; else not the
//house's. Words, without case, diacritics or punctuation
class AddressMatchTest {

    private final AddressMatch match = new AddressMatch("Łąkowo Górne", "Smoleńskiego");

    @ParameterizedTest
    @ValueSource(strings = {
        "Łąkowo Górne ul. Smoleńskiego",
        "ŁĄKOWO GÓRNE Ul: Długa, Smolenskiego, Krótka",
        "Inne, Lakowo Gorne ul. ks. Wincentego Smoleńskiego i okolice"})
    void should_take_an_announcement_naming_the_town_and_the_street_for_the_street(final String words) {
        assertThat(match.scopeOf(words)).contains(AddressMatch.Scope.STREET);
    }

    @Test
    void should_take_an_announcement_naming_the_town_alone_for_the_town() {
        assertThat(match.scopeOf("Łąkowo Górne ul. Długa, Krótka")).contains(AddressMatch.Scope.TOWN);
    }

    //a word that only starts like the town or the street is another one
    @ParameterizedTest
    @ValueSource(strings = {"Inne Miasto ul. Smoleńskiego", "Łąkowo Górnego ul. Smoleńskiego", "Łąkowo"})
    void should_take_no_other_announcement_for_the_house(final String words) {
        assertThat(match.scopeOf(words)).isEmpty();
    }

    @Test
    void should_read_a_street_that_only_starts_like_the_street_of_the_house_as_another() {
        assertThat(match.scopeOf("Łąkowo Górne ul. Smoleńskiegowa")).contains(AddressMatch.Scope.TOWN);
    }
}
