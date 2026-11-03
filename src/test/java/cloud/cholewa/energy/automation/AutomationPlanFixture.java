package cloud.cholewa.energy.automation;

import java.time.LocalDateTime;

//The plan of the automation set by a test of another package (HAS-338)
public final class AutomationPlanFixture {

    private final AutomationPlan plan = new AutomationPlan();

    public AutomationPlan plan() {
        return plan;
    }

    public void set(final Long outageId, final LocalDateTime outageStart, final LocalDateTime plannedStart) {
        plan.set(new AutomationPlan.Planned(outageId, outageStart, plannedStart));
    }
}
