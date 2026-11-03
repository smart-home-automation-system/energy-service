package cloud.cholewa.energy.charge;

import cloud.cholewa.energy.solax.SolaxSamples;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//the bodies of the Self Use work mode (HAS-336), written out whole: the fields of the documentation of 2026-11-03 and the
//baseline the owner runs
class SelfUseCommandTest {

    @Test
    void should_charge_within_one_day_in_period_one() {
        assertThat(SelfUseCommand.charge(SolaxSamples.JSON, "H1", ChargeSamples.BASELINE, 100,
            LocalDateTime.of(2026, 11, 3, 14, 5), LocalDateTime.of(2026, 11, 3, 19, 30)))
            .isEqualTo(SolaxSamples.JSON.readTree("""
                {"snList":["H1"],"businessType":1,"minSoc":10,"dischargeStartTimePeriod1":"00:00",
                 "dischargeEndTimePeriod1":"23:59","chargeFromGridEnable":1,"chargeUpperSoc":100,
                 "chargeStartTimePeriod1":"14:05","chargeEndTimePeriod1":"19:30","enableTimePeriod2":0}"""));
    }

    @Test
    void should_charge_across_midnight_in_both_periods() {
        assertThat(SelfUseCommand.charge(SolaxSamples.JSON, "H1", ChargeSamples.BASELINE, 90,
            LocalDateTime.of(2026, 11, 3, 22, 0), LocalDateTime.of(2026, 11, 4, 3, 15)))
            .isEqualTo(SolaxSamples.JSON.readTree("""
                {"snList":["H1"],"businessType":1,"minSoc":10,"dischargeStartTimePeriod1":"00:00",
                 "dischargeEndTimePeriod1":"23:59","chargeFromGridEnable":1,"chargeUpperSoc":90,
                 "chargeStartTimePeriod1":"22:00","chargeEndTimePeriod1":"23:59","enableTimePeriod2":1,
                 "chargeStartTimePeriod2":"00:00","chargeEndTimePeriod2":"03:15","dischargeStartTimePeriod2":"00:00",
                 "dischargeEndTimePeriod2":"23:59"}"""));
    }

    //owner, 2026-11-03: Self Use, Min SOC 10 %, Charge From Grid Enable to 10 %, no charge window, discharge 00:00 - 23:59,
    //period 2 off
    @Test
    void should_set_back_the_self_use_the_owner_runs() {
        assertThat(SelfUseCommand.baseline(SolaxSamples.JSON, "H1", ChargeSamples.BASELINE))
            .isEqualTo(SolaxSamples.JSON.readTree("""
                {"snList":["H1"],"businessType":1,"minSoc":10,"dischargeStartTimePeriod1":"00:00",
                 "dischargeEndTimePeriod1":"23:59","chargeFromGridEnable":1,"chargeUpperSoc":10,
                 "chargeStartTimePeriod1":"00:00","chargeEndTimePeriod1":"00:00","enableTimePeriod2":0}"""));
    }
}
