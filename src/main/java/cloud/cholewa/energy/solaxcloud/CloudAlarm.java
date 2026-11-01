package cloud.cholewa.energy.solaxcloud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

//An alarm of the plant as v2/alarm/page_alarm_info answers it (HAS-329): its name, error code, type and level, the time
//it started and ended (the time of the plant, "yyyy-MM-dd HH:mm:ss"), alarmState 1 ongoing, 0 closed, and the device
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudAlarm(
    String alarmName,
    String errorCode,
    String alarmType,
    Integer alarmLevel,
    Integer alarmState,
    String alarmStartTime,
    String alarmEndTime,
    String deviceSn
) {

    //one page of the alarms: total of every page, the records of this one
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(Integer total, Integer pages, List<CloudAlarm> records) {
    }
}
