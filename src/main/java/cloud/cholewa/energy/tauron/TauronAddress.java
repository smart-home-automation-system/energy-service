package cloud.cholewa.energy.tauron;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

//The address of the house in Tauron's register (HAS-337): the GAIDs of its town and street, resolved once from the
//names of the manifest and kept until the pod ends
public record TauronAddress(long cityGaid, long streetGaid, String houseNo) {

    //the one town whose name is the name of the manifest (case and diacritics aside) - a phrase may name several
    static long cityOf(final JsonNode towns, final String name) {
        return only(towns, town -> AddressMatch.same(town.path("Name").asString(""), name), "town");
    }

    //the one street of the town whose name or short name is the name of the manifest
    static long streetOf(final JsonNode streets, final String name) {
        return only(streets, street -> AddressMatch.same(street.path("Name").asString(""), name)
            || AddressMatch.same(street.path("ShortName").asString(""), name), "street");
    }

    private static long only(final JsonNode found, final Predicate<JsonNode> named, final String what) {
        final List<JsonNode> matching = StreamSupport.stream(found.spliterator(), false)
            .filter(named)
            .filter(item -> item.path("GAID").canConvertToLong())
            .toList();
        if (matching.size() != 1) {
            throw new TauronException("Tauron's register has " + matching.size() + " " + what
                + "s of the name of the manifest");
        }
        return matching.getFirst().path("GAID").asLong();
    }
}
