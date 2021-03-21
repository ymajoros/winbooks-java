package be.valuya.winbooks.api.accountingtroll.converter;

import be.valuya.accountingtroll.domain.ATThirdParty;
import be.valuya.accountingtroll.domain.ATThirdPartyType;
import be.valuya.jbooks.model.WbClientSupplier;
import be.valuya.jbooks.model.WbClientSupplierType;
import be.valuya.winbooks.domain.WinbooksDossierThirdParty;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ATThirdPartyConverter {


    public ATThirdParty convertToTrollThirdParty(WbClientSupplier wbSupplier) {
        String number = wbSupplier.getNumber();
        WbClientSupplierType wbClientSupplierType = wbSupplier.getWbClientSupplierType();
        ATThirdPartyType thirdPartyType = wbClientSupplierType == WbClientSupplierType.SUPPLIER ?
                ATThirdPartyType.SUPPLIER : ATThirdPartyType.CLIENT;
        String id = ATThirdPartyIdFactory.getId(wbClientSupplierType, number);

        // Previously prefixed with title (civName1), it appears they are not present in the winbooks-exported
        // listings, so using name1 exclusively
        String fullName = wbSupplier.getName1();
        String address = Stream.of(wbSupplier.getAddress1(), wbSupplier.getAddress2())
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
        String zipCode = wbSupplier.getZipCode();
        String city = wbSupplier.getCity();
        String countryCode = wbSupplier.getCountryCode();
        String vatNumber = wbSupplier.getVatNumber();

        String phoneNumber = wbSupplier.getTelNumber();
        String bankAccount = wbSupplier.getBankAccount();
        String lang = wbSupplier.getLang();

        ATThirdParty thirdParty = new ATThirdParty();
        thirdParty.setId(id);
        thirdParty.setType(thirdPartyType);
        thirdParty.setFullName(fullName);
        thirdParty.setAddress(address);
        thirdParty.setZipCode(zipCode);
        thirdParty.setCity(city);
        thirdParty.setCountryCode(countryCode);
        thirdParty.setVatNumber(vatNumber);
        thirdParty.setPhoneNumber(phoneNumber);
        thirdParty.setBankAccountNumber(bankAccount);
        thirdParty.setLanguage(lang);
        return thirdParty;
    }


    public ATThirdParty convertToTrollThirdParty(WinbooksDossierThirdParty dossierThirdParty) {
        ATThirdParty thirdParty = new ATThirdParty();

        Optional.ofNullable(dossierThirdParty.getName())
                .ifPresent(thirdParty::setFullName);

        String fullAddress = Stream.of(dossierThirdParty.getAddress1(), dossierThirdParty.getAddress2())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n"));
        Optional.of(fullAddress)
                .filter(s -> !s.isBlank())
                .ifPresent(thirdParty::setAddress);

        Optional.ofNullable(dossierThirdParty.getZip())
                .ifPresent(thirdParty::setZipCode);
        Optional.ofNullable(dossierThirdParty.getCity())
                .ifPresent(thirdParty::setCity);
        Optional.ofNullable(dossierThirdParty.getCountryCode())
                .ifPresent(thirdParty::setCountryCode);
        Optional.ofNullable(dossierThirdParty.getVatNumber())
                .ifPresent(thirdParty::setVatNumber);

        return thirdParty;
    }

}
