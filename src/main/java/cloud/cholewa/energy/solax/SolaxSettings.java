package cloud.cholewa.energy.solax;

//The settings of the SOLAX this service reads from its dongle (HAS-518, SetData): the work mode with its raw code, and
//of the Back Up mode the Min SoC - the floor the battery is held at - and Charge Battery To, both %; a percentage the
//dongle answered outside 0 - 100 is null, not known. Both are the stored settings of Back Up whatever mode runs: they
//hold only while the work mode is Back Up
public record SolaxSettings(
    int workModeCode,
    WorkMode workMode,
    Integer backUpMinSoc,
    Integer backUpChargeTo
) {
}
