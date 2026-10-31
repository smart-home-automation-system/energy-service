package cloud.cholewa.energy.api.model;

import java.util.List;

//GET /home/energy/status: every source of energy with whether it answers (HAS-325, HAS-327, HAS-328) and the grid as
//the SOLAX sees it (HAS-334). An object since HAS-334, a bare list of the sources before - no reader of the list but
//Bruno then; the dashboard (HAS-341) is built on this shape
public record StatusReply(
    List<SourceStatusReply> sources,
    GridStateReply grid
) {
}
