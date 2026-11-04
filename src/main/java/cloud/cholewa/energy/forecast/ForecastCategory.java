package cloud.cholewa.energy.forecast;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

//How much of the house load a day of the forecast covers (HAS-372), by the bands of energy.forecast.bands: none, poor,
//fair, good. On the wire in lower case; the words are composed by the dashboard
public enum ForecastCategory {
    NONE,
    POOR,
    FAIR,
    GOOD;

    //fair and good: the solar works that day
    public boolean sunny() {
        return this == FAIR || this == GOOD;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
