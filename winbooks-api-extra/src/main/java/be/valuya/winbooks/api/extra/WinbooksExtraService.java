package be.valuya.winbooks.api.extra;

import be.valuya.jbooks.model.WbAccount;
import be.valuya.jbooks.model.WbBookYearFull;
import be.valuya.jbooks.model.WbBookYearStatus;
import be.valuya.jbooks.model.WbClientSupplier;
import be.valuya.jbooks.model.WbDocument;
import be.valuya.jbooks.model.WbEntry;
import be.valuya.jbooks.model.WbParam;
import be.valuya.jbooks.model.WbPeriod;
import be.valuya.winbooks.api.extra.config.WinbooksFileConfiguration;
import be.valuya.winbooks.api.extra.reader.DbfUtils;
import be.valuya.winbooks.api.extra.reader.PeriodResolver;
import be.valuya.winbooks.api.extra.reader.WbAccountDbfReader;
import be.valuya.winbooks.api.extra.reader.WbBookYearFullDbfReader;
import be.valuya.winbooks.api.extra.reader.WbClientSupplierDbfReader;
import be.valuya.winbooks.api.extra.reader.WbEntryDbfReader;
import be.valuya.winbooks.api.extra.reader.WbParamDbfReader;
import be.valuya.winbooks.domain.error.WinbooksError;
import be.valuya.winbooks.domain.error.WinbooksException;
import net.iryndin.jdbf.core.DbfRecord;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WinbooksExtraService {

    private static Logger LOGGER = Logger.getLogger(WinbooksExtraService.class.getName());
    private static final String ACCOUNT_PICTURE_PARAM_LENGEN = "LENGEN";
    private static final int ACCOUNT_NUMBER_DEFAULT_LENGTH = 6;

    // some invalid String that Winbooks likes to have
    private static final String CHAR0_STRING = Character.toString((char) 0);
    private static final String PARAM_TABLE_NAME = "param";
    private static final String BOOKYEARS_TABLE_NAME = "SLBKY";
    private static final String PERIOD_TABLE_NAME = "SLPRD";
    private static final String ACCOUNT_TABLE_NAME = "ACF";
    private static final String CUSTOMER_SUPPLIER_TABLE_NAME = "CSF";
    private static final String DEFAULT_TABLE_FILE_NAME_REGEX = "^(.*)_" + ACCOUNT_TABLE_NAME + ".DBF$";
    private static final Pattern DEFAULT_TABLE_FILE_NAME_PATTERN = Pattern.compile(DEFAULT_TABLE_FILE_NAME_REGEX, Pattern.CASE_INSENSITIVE);
    private static final String ACCOUNTING_ENTRY_TABLE_NAME = "ACT";
    private static final String DBF_EXTENSION = ".dbf";
    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("ddMMyyyy");


    private final WinbooksDocumentsService documentService = new WinbooksDocumentsService();

    public Optional<WinbooksFileConfiguration> createWinbooksFileConfigurationOptional(Path parentPath, String baseName) {
        return WinbooksPathUtils.resolvePath(parentPath, baseName, true)
                .flatMap(this::createWinbooksFileConfigurationOptional);
    }

    private Optional<WinbooksFileConfiguration> createWinbooksFileConfigurationOptional(Path basePath) {
        Optional<String> customerWinbooksPathNameOptional = Optional.ofNullable(basePath.getFileName())
                .map(Path::toString);
        if (!customerWinbooksPathNameOptional.isPresent()) {
            return Optional.empty();
        }
        String baseName = customerWinbooksPathNameOptional.get();

        WinbooksFileConfiguration winbooksFileConfiguration = new WinbooksFileConfiguration();
        winbooksFileConfiguration.setBaseFolderPath(basePath);
        winbooksFileConfiguration.setBaseName(baseName);
        winbooksFileConfiguration.setResolveCaseInsensitiveSiblings(true);

        if (!tableExistsForCurrentBookYear(winbooksFileConfiguration, ACCOUNTING_ENTRY_TABLE_NAME)) {
            return Optional.empty();
        }

        return Optional.of(winbooksFileConfiguration);
    }

    public LocalDateTime getActModificationDateTime(WinbooksFileConfiguration winbooksFileConfiguration) {
        Path baseFolderPath = winbooksFileConfiguration.getBaseFolderPath();
        Path actPath = resolveTablePathOrThrow(winbooksFileConfiguration, baseFolderPath, ACCOUNTING_ENTRY_TABLE_NAME);
        return WinbooksPathUtils.getLastModifiedTime(actPath);
    }

    public Stream<WbEntry> streamAct(WinbooksFileConfiguration winbooksFileConfiguration) {
        List<WbBookYearFull> wbBookYearFullList = streamBookYears(winbooksFileConfiguration)
                .collect(Collectors.toList());
        boolean resolveUnmappedPeriodFromEntryDate = winbooksFileConfiguration.isResolveUnmappedPeriodFromEntryDate();

        PeriodResolver periodResolver = new PeriodResolver(resolveUnmappedPeriodFromEntryDate);
        periodResolver.init(wbBookYearFullList);
        WbEntryDbfReader wbEntryDbfReader = new WbEntryDbfReader(periodResolver);


        return wbBookYearFullList.stream()
                .flatMap(year -> this.streamBookYearAct(winbooksFileConfiguration, wbEntryDbfReader, year));
    }

    public Stream<WbAccount> streamAcf(WinbooksFileConfiguration winbooksFileConfiguration) {
        Path baseFolderPath = winbooksFileConfiguration.getBaseFolderPath();
        WbAccountDbfReader wbAccountDbfReader = new WbAccountDbfReader();
        return streamTable(winbooksFileConfiguration, baseFolderPath, ACCOUNT_TABLE_NAME)
                .map(wbAccountDbfReader::readWbAccountFromAcfDbfRecord);
    }

    public Stream<WbClientSupplier> streamCsf(WinbooksFileConfiguration winbooksFileConfiguration) {
        Path baseFolderPath = winbooksFileConfiguration.getBaseFolderPath();
        WbClientSupplierDbfReader wbClientSupplierDbfReader = new WbClientSupplierDbfReader();
        return streamTable(winbooksFileConfiguration, baseFolderPath, CUSTOMER_SUPPLIER_TABLE_NAME)
                .map(wbClientSupplierDbfReader::readWbClientSupplierFromAcfDbfRecord);
    }

    public Stream<WbDocument> streamBookYearDocuments(WinbooksFileConfiguration fileConfiguration, WbBookYearFull bookYear) {
        return documentService.streamBookYearDocuments(fileConfiguration, bookYear);
    }

    public Optional<byte[]> getDocumentData(WinbooksFileConfiguration fileConfiguration, WbDocument document) {
        return documentService.getDocumentData(fileConfiguration, document);
    }


    public Stream<WbBookYearFull> streamBookYears(WinbooksFileConfiguration winbooksFileConfiguration) {
        if (false && tableExistsForCurrentBookYear(winbooksFileConfiguration, BOOKYEARS_TABLE_NAME)) { //TODO: currently, we can findWbBookYearFull more info out of the badly structured param table
            return streamBookYearsFromBookYearsTable(winbooksFileConfiguration);
        }
        // fall-back: a lot of customers seem not to have table above
        return listBookYearsFromParamTable(winbooksFileConfiguration).stream();
    }

    public int getAccountNumberLengthFromParamsTable(WinbooksFileConfiguration winbooksFileConfiguration) {
        Map<String, String> paramMap = getParamMap(winbooksFileConfiguration);
        String accountPictureValueNullable = paramMap.get("AccountPicture");
        return Optional.ofNullable(accountPictureValueNullable)
                .flatMap(this::getAccountNumberLengthFromAccountPictureParamValue)
                .orElse(ACCOUNT_NUMBER_DEFAULT_LENGTH);
    }

    Stream<DbfRecord> streamTable(WinbooksFileConfiguration winbooksFileConfiguration, String tableName) {
        Path baseFolderPath = winbooksFileConfiguration.getBaseFolderPath();
        InputStream tableInputStream = getTableInputStream(winbooksFileConfiguration, baseFolderPath, tableName);
        Charset charset = winbooksFileConfiguration.getCharset();
        return DbfUtils.streamDbf(tableInputStream, charset);
    }

    private Stream<WbEntry> streamBookYearAct(WinbooksFileConfiguration winbooksFileConfiguration,
                                              WbEntryDbfReader dbfReader, WbBookYearFull bookYearFull) {
        Optional<Path> bookYearBasePath = WinbooksPathUtils.getBookYearBasePath(winbooksFileConfiguration, bookYearFull);
        return streamOptional(bookYearBasePath)
                .flatMap(basePath -> streamTable(winbooksFileConfiguration, basePath, ACCOUNTING_ENTRY_TABLE_NAME))
                .filter(this::isValidActRecord)
                .map(dbfReader::readWbEntryFromActDbfRecord)
                .flatMap(this::streamOptional)
                .filter(wbEntry -> isEntryForBookYear(bookYearFull, wbEntry));
    }

    private boolean isEntryForBookYear(WbBookYearFull bookYearFull, WbEntry wbEntry) {
        return Optional.ofNullable(wbEntry.getWbBookYearFull())
                .map(WbBookYearFull::getIndex)
                .map(i -> i == bookYearFull.getIndex())
                .orElse(false);
    }

    private List<WbBookYearFull> listBookYearsFromParamTable(WinbooksFileConfiguration winbooksFileConfiguration) {
        Map<String, String> paramMap = getParamMap(winbooksFileConfiguration);

        List<WbBookYearFull> wbBookYearFullList = new ArrayList<>();

        String bookYearCountStr = paramMap.get("BOOKYEARCOUNT");
        int bookYearCount = Integer.parseInt(bookYearCountStr);
        for (int i = 1; i <= bookYearCount; i++) {
            String bookYearParamPrefix = "BOOKYEAR" + i;
            // some book year data may not be present in archived dossiers
            long bookyearKeysCount = paramMap.keySet().stream()
                    .filter(k -> k.startsWith(bookYearParamPrefix))
                    .count();
            if (bookyearKeysCount == 0) {
                continue;
            }
            String bookYearLongLabel = paramMap.get(bookYearParamPrefix + "." + "LONGLABEL");
            String bookYearShortLabel = paramMap.get(bookYearParamPrefix + "." + "SHORTLABEL");
            String archivePathName = paramMap.get(bookYearParamPrefix + "." + "PATHARCH");
            Optional<String> archivePathNameNullable = Optional.ofNullable(archivePathName);

            String perDatesStr = paramMap.get(bookYearParamPrefix + "." + "PERDATE");
            List<LocalDate> periodDates = parsePeriodDates(perDatesStr);

            int periodCount = periodDates.size() - 2;
            int durationInMonths = 12 / periodCount;

            String concatenatedPeriodNames = paramMap.get(bookYearParamPrefix + "." + "PERLIB1");
            List<String> periodNames = parsePeriodNames(concatenatedPeriodNames);

            List<WbPeriod> wbPeriodList = convertWinbooksPeriods(periodNames, periodDates, durationInMonths);

            LocalDate startDate = periodDates.stream()
                    .findFirst()
                    .orElseThrow(IllegalArgumentException::new);
            LocalDate endDate = periodDates.stream()
                    .max(LocalDate::compareTo)
                    .map(date -> date.plusDays(1)) // exclusive upper bound is day after
                    .orElseThrow(IllegalArgumentException::new);
            boolean bookYearMinStartViolated = winbooksFileConfiguration.getBookYearStartMinDateOptional()
                    .map(minStartDate -> minStartDate.isAfter(startDate))
                    .orElse(false);
            boolean bookYearMaxStartViolated = winbooksFileConfiguration.getBookYearStartMaxDateOptional()
                    .map(maxStartDate -> maxStartDate.isBefore(startDate))
                    .orElse(false);
            if (bookYearMinStartViolated || bookYearMaxStartViolated) {
                continue;
            }

            int startYear = startDate.getYear();
            int endYear = endDate.getYear();

            String statusStrNullable = paramMap.get(bookYearParamPrefix + "." + "STATUS");

            WbBookYearFull wbBookYearFull = new WbBookYearFull();
            wbBookYearFull.setLongName(bookYearLongLabel);
            wbBookYearFull.setShortName(bookYearShortLabel);
            wbBookYearFull.setArchivePathNameOptional(archivePathNameNullable);
            wbBookYearFull.setIndex(i);
            wbBookYearFull.setStartDate(startDate);
            wbBookYearFull.setEndDate(endDate);
            wbBookYearFull.setYearBeginInt(startYear);
            wbBookYearFull.setYearEndInt(endYear);
            wbBookYearFull.setPeriods(periodCount);
            wbBookYearFull.setPeriodList(wbPeriodList);

            wbPeriodList.forEach(wbPeriod -> wbPeriod.setWbBookYearFull(wbBookYearFull));

            Optional.ofNullable(statusStrNullable)
                    .flatMap(WbBookYearStatus::fromValueStr)
                    .ifPresent(wbBookYearFull::setWbBookYearStatus);

            wbBookYearFullList.add(wbBookYearFull);
        }

        return wbBookYearFullList;
    }

    private Optional<Integer> getAccountNumberLengthFromAccountPictureParamValue(String accountPictureValue) {
        String[] paramValues = accountPictureValue.split(",");
        Map<String, String> accountPictureParams = Arrays.stream(paramValues)
                .map(keyValue -> keyValue.split("="))
                .collect(Collectors.toMap(
                        keyVal -> keyVal[0],
                        keyVal -> keyVal.length < 2 ? null : keyVal[1]
                ));
        return Optional.ofNullable(accountPictureParams.get(ACCOUNT_PICTURE_PARAM_LENGEN))
                .map(Integer::parseInt);
    }

    private Stream<WbBookYearFull> streamBookYearsFromBookYearsTable(WinbooksFileConfiguration winbooksFileConfiguration) {
        Path baseFolderPath = winbooksFileConfiguration.getBaseFolderPath();
        WbBookYearFullDbfReader wbBookYearFullDbfReader = new WbBookYearFullDbfReader();
        return streamTable(winbooksFileConfiguration, baseFolderPath, BOOKYEARS_TABLE_NAME)
                .map(wbBookYearFullDbfReader::readWbBookYearFromSlbkyDbfRecord);
    }

    private Stream<DbfRecord> streamTable(WinbooksFileConfiguration winbooksFileConfiguration, Path basePath, String tableName) {
        InputStream tableInputStream = getTableInputStream(winbooksFileConfiguration, basePath, tableName);
        Charset charset = winbooksFileConfiguration.getCharset();
        return DbfUtils.streamDbf(tableInputStream, charset);
    }

    private <T> Stream<T> streamOptional(Optional<T> optional) {
        return optional.map(Stream::of)
                .orElseGet(Stream::empty);
    }

    private Map<String, String> getParamMap(WinbooksFileConfiguration winbooksFileConfiguration) {
        Path baseFolderPath = winbooksFileConfiguration.getBaseFolderPath();
        return streamTable(winbooksFileConfiguration, baseFolderPath, PARAM_TABLE_NAME)
                .map(new WbParamDbfReader()::readWbParamFromDbfRecord)
                .filter(wbParam -> wbParam.getValue() != null)
                .collect(Collectors.toMap(WbParam::getId, WbParam::getValue, (id1, id2) -> id2));
    }

    private boolean isValidActRecord(DbfRecord dbfRecord) {
        String docOrderNullable = dbfRecord.getString("DOCORDER");
        return Optional.ofNullable(docOrderNullable)
                .map(this::isWbValidString)
                .orElse(true);
    }

    private boolean isWbValidString(String str) {
        return str == null || !str.startsWith(CHAR0_STRING);
    }


    private List<WbPeriod> convertWinbooksPeriods(List<String> periodNames, List<LocalDate> periodDates, int durationInMonths) {
        int periodCount = periodNames.size();
        if (periodDates.size() != periodCount) {
            throw new WinbooksException(WinbooksError.PERIOD_DATE_MISMATCH, "Different sizes for period names and period dates.");
        }

        List<WbPeriod> periods = new ArrayList<>();
        for (int i = 0; i < periodCount; i++) {
            String periodName = periodNames.get(i);
            LocalDate periodStartDate = periodDates.get(i);
            LocalDate periodEndDate = periodStartDate.plusMonths(durationInMonths);

            WbPeriod period = new WbPeriod();
            period.setStartDate(periodStartDate);
            period.setEndDate(periodEndDate);
            period.setShortName(periodName);
            period.setIndex(i);

            periods.add(period);
        }

        WbPeriod lasterWbPeriod = periods.get(periodCount - 1);
        lasterWbPeriod.setIndex(99);

        return periods;
    }

    private List<String> parsePeriodNames(String concatenatedPeriodNames) {
        List<String> periodNames = new ArrayList<>();

        int length = concatenatedPeriodNames.length();
        for (int i = 0; i + 8 <= length; i += 8) {
            String periodName = concatenatedPeriodNames.substring(i, i + 8);
            periodNames.add(periodName);
        }

        return periodNames;
    }

    private List<LocalDate> parsePeriodDates(String allPeriodDatesStr) {
        List<LocalDate> periodDates = new ArrayList<>();
        int allPeriodLength = allPeriodDatesStr.length();
        int i = 0;
        while (i < allPeriodLength) {
            char currentChar = allPeriodDatesStr.charAt(i);
            if (currentChar == ' ') {
                i++;
                continue;
            }
            String periodDateStr = allPeriodDatesStr.substring(i, i + 8);
            LocalDate periodDate = LocalDate.parse(periodDateStr, PERIOD_FORMATTER);

            if (i == allPeriodLength - 1) {
                periodDate = periodDate.plusDays(1);
            }

            periodDates.add(periodDate);
            i += 8;
        }

        return periodDates;
    }


    private String getPathFileNameString(Path archiveFolderPath) {
        return Optional.ofNullable(archiveFolderPath.getFileName())
                .map(Path::toString)
                .orElse("");
    }

    private InputStream getTableInputStream(WinbooksFileConfiguration winbooksFileConfiguration, Path basePath, String tableName) {
        Path path = resolveTablePathOrThrow(winbooksFileConfiguration, basePath, tableName);
        return getFastInputStream(winbooksFileConfiguration, path);
    }

    private InputStream getFastInputStream(WinbooksFileConfiguration winbooksFileConfiguration, Path path) {
        try {
            if (!winbooksFileConfiguration.isReadTablesToMemory()) {
                return Files.newInputStream(path);
            }

            long time0 = System.currentTimeMillis();
            byte[] bytes = Files.readAllBytes(path);
            long time1 = System.currentTimeMillis();
            long deltaTime = time1 - time0;
            LOGGER.log(Level.FINER, "READ table (" + path + "): " + deltaTime);

            return new ByteArrayInputStream(bytes);
        } catch (IOException exception) {
            throw new WinbooksException(WinbooksError.UNKNOWN_ERROR, exception);

        }
    }

    private boolean tableExistsForCurrentBookYear(WinbooksFileConfiguration winbooksFileConfiguration, String tableName) {
        String baseName = winbooksFileConfiguration.getBaseName();
        String tableFileName = getTableFileName(baseName, tableName);
        Path baseFolderPath = winbooksFileConfiguration.getBaseFolderPath();
        boolean resolveCaseInsensitiveSiblings = winbooksFileConfiguration.isResolveCaseInsensitiveSiblings();
        Optional<Path> tablePathOptional = WinbooksPathUtils.resolvePath(baseFolderPath, tableFileName, resolveCaseInsensitiveSiblings);
        return tablePathOptional.isPresent();
    }

    private Path resolveTablePathOrThrow(WinbooksFileConfiguration winbooksFileConfiguration, Path basePath, String tableName) {
        String baseName = basePath.getFileName().toString();
        String tableFileName = getTableFileName(baseName, tableName);
        boolean resolveCaseInsensitiveSiblings = winbooksFileConfiguration.isResolveCaseInsensitiveSiblings();
        Optional<Path> tablePathOptional = WinbooksPathUtils.resolvePath(basePath, tableFileName, resolveCaseInsensitiveSiblings);
        return tablePathOptional.orElseThrow(() -> {
            Path baseFolderPath = winbooksFileConfiguration.getBaseFolderPath();
            String baseFolderPathName = getPathFileNameString(baseFolderPath);

            String message = MessageFormat.format("Could not find file {0} in folder {1}", tableFileName, baseFolderPathName);
            return new WinbooksException(WinbooksError.DOSSIER_NOT_FOUND, message);
        });
    }


    private String getTableFileName(String baseName, String tableName) {
        String tablePrefix = baseName.replace("_", "");
        return tablePrefix + "_" + tableName + DBF_EXTENSION;
    }

}

