package be.valuya.winbooks.api.accountingtroll;

import be.valuya.accountingtroll.AccountingManager;
import be.valuya.accountingtroll.cache.AccountBalanceSpliterator;
import be.valuya.accountingtroll.cache.ThirdPartyBalanceSpliterator;
import be.valuya.accountingtroll.domain.ATAccount;
import be.valuya.accountingtroll.domain.ATAccountBalance;
import be.valuya.accountingtroll.domain.ATAccountingEntry;
import be.valuya.accountingtroll.domain.ATBookPeriod;
import be.valuya.accountingtroll.domain.ATBookYear;
import be.valuya.accountingtroll.domain.ATDocument;
import be.valuya.accountingtroll.domain.ATThirdParty;
import be.valuya.accountingtroll.domain.ATThirdPartyBalance;
import be.valuya.jbooks.model.WbDocument;
import be.valuya.winbooks.api.accountingtroll.cache.AccountingManagerCache;
import be.valuya.winbooks.api.accountingtroll.converter.ATDocumentConverter;
import be.valuya.winbooks.api.accountingtroll.converter.ATThirdPartyConverter;
import be.valuya.winbooks.api.extra.WinbooksExtraService;
import be.valuya.winbooks.api.extra.config.WinbooksFileConfiguration;
import be.valuya.winbooks.domain.WinbooksDossierThirdParty;
import be.valuya.winbooks.domain.error.WinbooksError;
import be.valuya.winbooks.domain.error.WinbooksException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class WinbooksTrollAccountingManager implements AccountingManager {

    private static final String DOCUMENTS_PATH_NAME = "Document";
    private static final String DOCUMENT_UPLOAD_PATH_NAME = "Scans";

    private WinbooksExtraService extraService;
    private WinbooksFileConfiguration fileConfiguration;
    private ATThirdPartyConverter atThirdPartyConverter;

    private final AccountingManagerCache accountingManagerCache;

    public WinbooksTrollAccountingManager(WinbooksFileConfiguration fileConfiguration) {
        this.fileConfiguration = fileConfiguration;
        extraService = new WinbooksExtraService();
        accountingManagerCache = new AccountingManagerCache(fileConfiguration);
        atThirdPartyConverter = new ATThirdPartyConverter();
    }

    @Override
    public ATThirdParty getSubjectThirdParty() {
        WinbooksDossierThirdParty dossierThirdParty = extraService.getDossierThirdPartyFromParamTable(fileConfiguration);
        ATThirdParty thirdParty = atThirdPartyConverter.convertToTrollThirdParty(dossierThirdParty);
        return thirdParty;
    }

    @Override
    public Optional<LocalDateTime> getLastAccountModificationTime() {
        LocalDateTime modificationDateTime = extraService.getActModificationDateTime(fileConfiguration);
        return Optional.of(modificationDateTime);
    }

    @Override
    public Stream<ATAccount> streamAccounts() {
        return accountingManagerCache.streamAccounts();
    }

    @Override
    public Stream<ATBookYear> streamBookYears() {
        // TODO: check use of book year index 0 that was added in gestemps
        return accountingManagerCache.streamBookYears();
    }

    @Override
    public Stream<ATBookPeriod> streamPeriods() {
        return accountingManagerCache.streamPeriods();
    }

    @Override
    public Stream<ATThirdParty> streamThirdParties() {
        return accountingManagerCache.streamThirdParties();
    }

    @Override
    public Stream<ATThirdPartyBalance> streamThirdPartyBalances() {
        List<ATBookPeriod> allPeriods = streamPeriods().collect(Collectors.toList());
        Stream<ATAccountingEntry> entryStream = streamAccountingEntries();
        ThirdPartyBalanceSpliterator balanceSpliterator = new ThirdPartyBalanceSpliterator(entryStream, allPeriods);

        balanceSpliterator.setResetOnBookYearOpening(true);
        balanceSpliterator.setIgnoreIntermediatePeriodOpeningEntry(false);

        return StreamSupport.stream(balanceSpliterator, false);
    }

    @Override
    public Stream<ATAccountingEntry> streamAccountingEntries() {
        return accountingManagerCache.streamAccountingEntries()
                .sorted();
    }

    @Override
    public Stream<ATAccountBalance> streamAccountBalances() {
        List<ATBookPeriod> allPeriods = streamPeriods().collect(Collectors.toList());
        Stream<ATAccountingEntry> entryStream = streamAccountingEntries();
        AccountBalanceSpliterator balanceSpliterator = new AccountBalanceSpliterator(entryStream, allPeriods);

        balanceSpliterator.setResetOnBookYearOpening(true);
        balanceSpliterator.setResetEveryYear(false);
        balanceSpliterator.setIgnoreIntermediatePeriodOpeningEntry(false);

        return StreamSupport.stream(balanceSpliterator, false);
    }

    @Override
    public Stream<ATDocument> streamDocuments() {
        return accountingManagerCache.streamDocuments();
    }

    @Override
    public InputStream streamDocumentContent(ATDocument atDocument) {
        ATDocumentConverter documentConverter = new ATDocumentConverter(accountingManagerCache);
        WbDocument wbDocument = documentConverter.convertWbDocument(atDocument);
        byte[] documentdata = extraService.getDocumentData(fileConfiguration, wbDocument)
                .orElseThrow(() -> new WinbooksException(WinbooksError.FATAL_ERRORS, "Could not find document"));

        return new ByteArrayInputStream(documentdata);
    }

    @Override
    public void uploadDocument(String documentRelativePathName, InputStream inputStream) throws Exception {
        Path documentRelativePath = Paths.get(documentRelativePathName);
        int documentFileNamePathCount = documentRelativePath.getNameCount();
        if (documentFileNamePathCount < 0) {
            throw new WinbooksException(WinbooksError.USER_FILE_ERROR, "Invalid document path name");
        }
        boolean documentPathIsAbsolute = documentRelativePath.isAbsolute();
        if (documentPathIsAbsolute) {
            throw new WinbooksException(WinbooksError.USER_FILE_ERROR, "Document path is absolute");
        }

        Path baseFolderPath = extraService.getDossierBasePath(fileConfiguration);

        Path documentDirectoryPath = baseFolderPath.resolve(DOCUMENTS_PATH_NAME)
                .resolve(DOCUMENT_UPLOAD_PATH_NAME)
                .resolve(documentRelativePathName)
                .getParent();
        String documentFileName = documentRelativePath
                .getFileName()
                .toString();

        Path actualDirectoryPath = extraService.createDirectories(fileConfiguration, documentDirectoryPath);
        Path documentPath = actualDirectoryPath.resolve(documentFileName);

        Files.copy(inputStream, documentPath, StandardCopyOption.REPLACE_EXISTING);
    }

}
