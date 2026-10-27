package cloud.cholewa.energy.api.model;

//One source of GET /home/energy/status. reachable is null while the service knows nothing of the source -
//until the task that reads it, and after a start before its first reading; written out as null, never as
//false (the dashboard shows it as not known)
public record SourceStatusReply(String source, Boolean reachable) {
}
