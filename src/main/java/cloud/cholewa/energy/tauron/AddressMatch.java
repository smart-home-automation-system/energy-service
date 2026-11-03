package cloud.cholewa.energy.tauron;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

//Whether an announcement concerns the house (HAS-337, owner 2026-11-03). Tauron answers the address of the house with
//the list of its whole area (OutageListType 2) and no outage names its address point, so only the words of the
//announcement tell: one that names the town and the street of the house is STREET, one that names the town alone is
//TOWN, any other is not the house's. Compared as words, without case, diacritics or punctuation ("ul. Przykładowa,"
//is "ul przykladowa")
public final class AddressMatch {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_A_LETTER = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final String town;
    private final String street;

    public AddressMatch(final String town, final String street) {
        this.town = words(town);
        this.street = words(street);
    }

    public Optional<Scope> scopeOf(final String announcement) {
        final String text = " " + words(announcement) + " ";
        if (!text.contains(" " + town + " ")) {
            return Optional.empty();
        }
        return Optional.of(text.contains(" " + street + " ") ? Scope.STREET : Scope.TOWN);
    }

    static boolean same(final String one, final String other) {
        return words(one).equals(words(other));
    }

    //lowercase words of letters and digits, single spaces between them; "ł" has no decomposition, so it is mapped by hand
    static String words(final String text) {
        final String plain = MARKS.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD))
            .replaceAll("")
            .replace('ł', 'l')
            .replace('Ł', 'L');
        return NOT_A_LETTER.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    public enum Scope {
        STREET("street"),
        TOWN("town");

        private final String value;

        Scope(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
