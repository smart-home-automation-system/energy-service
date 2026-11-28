package cloud.cholewa.energy.api.model;

//The body of PUT /home/energy/automation/shedding (HAS-523): enabled - the switch of the load shedding, required
public record SheddingRequest(Boolean enabled) {
}
