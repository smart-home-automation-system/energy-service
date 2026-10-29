package cloud.cholewa.energy.registry;

import cloud.cholewa.home.model.InverterBinding;
import cloud.cholewa.home.model.InverterBrand;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;

import java.util.Optional;

//An inverter as the service reads it from the registry (HAS-326): its id, name and room, the brand, the serial
//number and what answers for it on the home network - the dongle of a SOLAX with the registration number it takes,
//the logger of a SOFAR. address, registrationNumber and loggerSerial are null where the registry has none
public record Inverter(
    long id,
    String name,
    RoomName room,
    InverterBrand brand,
    String serialNumber,
    String address,
    String registrationNumber,
    Long loggerSerial
) {

    //the inverter of a device of the registry, or nothing for a device without an id or an inverter binding - the
    //registry never stores one, so it is a registry the service does not understand
    static Optional<Inverter> of(final SmartDevice device) {
        final InverterBinding binding = device.getInverter();
        if (device.getId() == null || binding == null || binding.getBrand() == null) {
            return Optional.empty();
        }
        return Optional.of(new Inverter(device.getId(), device.getName(), device.getRoom(), binding.getBrand(),
            binding.getSerialNumber(), binding.getAddress(), binding.getRegistrationNumber(),
            binding.getLoggerSerial()));
    }
}
