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
import be.valuya.winbooks.domain.WinbooksDossierThirdParty;
import be.valuya.winbooks.domain.WinbooksParamNames;
import be.valuya.winbooks.domain.error.WinbooksConfigurationException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WinbooksExtraService {

    private static Logger LOGGER = Logger.getLogger(WinbooksExtraService.class.getName());
    private static final String ACCOUNT_PICTURE_PARAM_LENGEN = "LENGEN";
    private static final int ACCOUNT_NUMBER_DEFAULT_LENGTH = 6;

    // some invalid String that Winbooks likes to have
    private static final String CHAR0_STRING = Character.toString((char) 0);
    private static final String DBF_EXTENSION = ".dbf";
    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("ddMMyyyy");


    private final WinbooksDocumentsService documentService = new WinbooksDocumentsService();
    private final Map<String, Path> tableFilePathMap = new HashMap<>();


    /**
     * List all path names which are winbooks dossiers.
     * <p>
     * All children of the passed in rootPath argument are listed, and checked for winbooks contents.
     *
     * @param rootPath
     * @return List of path names which are main winbooks dossiers.
     * @throws IOException
     */
    public List<String> listWinbooksDossiersPathNames(Path rootPath, boolean checkValid, boolean ignoreArchivedPathNames) throws IOException {
        List<Path> paths = Files.list(rootPath).collect(Collectors.toList());
        return paths.stream()
                .filter(Files::isDirectory)
                .filter(p -> !ignoreArchivedPathNames || !WinbooksPathUtils.isArchivedPathName(p.getFileName().toString()))
                .filter(p -> !checkValid || this.isValidWinbooksEntityDossierPath(p))
                .map(Path::getFileName)
                .map(Path::toString)
                .collect(Collectors.toList());
    }


    /**
     * Create a new winbooks file configuration
     *
     * @param rootPath The path under which dossier directories are located
     * @param baseName The company base name, which is the dossier directory name as well as the reference used in winbooks.
     */
    public WinbooksFileConfiguration createWinbooksFileConfiguration(Path rootPath, String baseName) throws WinbooksConfigurationException {
        return createWinbooksFileConfiguration(rootPath, baseName, Map.of());
    }

    /**
     * Create a new winbooks file configuration
     *
     * @param rootPath     The path under which dossier directories are located
     * @param baseName     The company base name, which is the dossier directory name as well as the reference used in winbooks.
     * @param pathMappings Path mappings. See {@link WinbooksFileConfiguration#setPathMappings(Map)}.
     */
    public WinbooksFileConfiguration createWinbooksFileConfiguration(Path rootPath, String baseName, Map<String, Path> pathMappings) throws WinbooksConfigurationException {
        return createWinbooksFileConfiguration(rootPath, baseName, baseName, pathMappings);
    }

    /**
     * Create a new winbooks file configuration
     *
     * @param rootPath     The path under which dossier directories are located
     * @param basePathName The dossier directory name.
     * @param companyName  The company name, as used in winbooks reference.
     * @param pathMappings Path mappings. See {@link WinbooksFileConfiguration#setPathMappings(Map)}.
     */
    public WinbooksFileConfiguration createWinbooksFileConfiguration(Path rootPath, String basePathName, String companyName, Map<String, Path> pathMappings) throws WinbooksConfigurationException {
        WinbooksFileConfiguration winbooksFileConfiguration = new WinbooksFileConfiguration();
        winbooksFileConfiguration.setRootPath(rootPath);
        winbooksFileConfiguration.setBasePathName(basePathName);
        winbooksFileConfiguration.setWinbooksCompanyName(companyName);
        winbooksFileConfiguration.setPathMappings(pathMappings);
        winbooksFileConfiguration.setResolveCaseInsensitiveSiblings(true);

        try {
            Path dossierBasePath = WinbooksPathUtils.getDossierBasePath(winbooksFileConfiguration);
            if (!Files.exists(dossierBasePath)) {
                throw new WinbooksConfigurationException(WinbooksError.CANNOT_OPEN_DOSSIER, "Winbooks dossier path does not exist: " + dossierBasePath.toString());
            }
        } catch (WinbooksException e) {
            throw new WinbooksConfigurationException(WinbooksError.CANNOT_OPEN_DOSSIER, "Cannot open dossier: " + e.getMessage(), e);
        }

        if (!tableExistsForCurrentBookYear(winbooksFileConfiguration, WinbooksTableName.ACCOUNTING_ENTRIES)) {
            throw new WinbooksConfigurationException(WinbooksError.CANNOT_OPEN_DOSSIER, "Winbooks dossier does not contain any entry");
        }

        return winbooksFileConfiguration;
    }

    public boolean isValidWinbooksEntityDossierPath(Path path) {
        String pathName = path.getFileName().toString();
        if (pathName.equals(".") || pathName.equals("..")) {
            return false;
        }

        try {
            WinbooksFileConfiguration winbooksFileConfiguration = new WinbooksFileConfiguration();
            winbooksFileConfiguration.setBasePathName(pathName);
            winbooksFileConfiguration.setRootPath(path.getParent());
            WinbooksDossierThirdParty dossierThirdParty = getDossierThirdPartyFromParamTable(winbooksFileConfiguration);
            String thirdPartyName = dossierThirdParty.getName();
            if (thirdPartyName.isBlank()) {
                return false;
            }
//            if (!thirdPartyName.equals(pathName)) {
//                return false;
//            }

            // in a directory <PATHNAME>, we expect a dbf table <PATHNAME>_ACT.DBF
            String tableFileName = getTableFileName(pathName, WinbooksTableName.ACCOUNTS);
            Optional<Path> tablePathOptional = WinbooksPathUtils.resolvePath(path, tableFileName, false);
            if (tablePathOptional.isPresent()) {
                return true;
            }
            Optional<Path> upperTablePathOptional = WinbooksPathUtils.resolvePath(path, tableFileName.toUpperCase(Locale.ROOT), false);
            if (upperTablePathOptional.isPresent()) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public LocalDateTime getActModificationDateTime(WinbooksFileConfiguration winbooksFileConfiguration) {
        Path baseFolderPath = WinbooksPathUtils.getDossierBasePath(winbooksFileConfiguration);
        Path actPath = resolveTablePathOrThrow(winbooksFileConfiguration, baseFolderPath, WinbooksTableName.ACCOUNTING_ENTRIES);
        return WinbooksPathUtils.getLastModifiedTime(actPath);
    }

    public Path getDossierBasePath(WinbooksFileConfiguration fileConfiguration) {
        return WinbooksPathUtils.getDossierBasePath(fileConfiguration);
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
        Path baseFolderPath = WinbooksPathUtils.getDossierBasePath(winbooksFileConfiguration);
        WbAccountDbfReader wbAccountDbfReader = new WbAccountDbfReader();
        return streamTable(winbooksFileConfiguration, baseFolderPath, WinbooksTableName.ACCOUNTS)
                .map(wbAccountDbfReader::readWbAccountFromAcfDbfRecord);
    }

    public Stream<WbClientSupplier> streamCsf(WinbooksFileConfiguration winbooksFileConfiguration) {
        Path baseFolderPath = WinbooksPathUtils.getDossierBasePath(winbooksFileConfiguration);
        WbClientSupplierDbfReader wbClientSupplierDbfReader = new WbClientSupplierDbfReader();
        return streamTable(winbooksFileConfiguration, baseFolderPath, WinbooksTableName.CUSTOMER_SUPPLIERS)
                .map(wbClientSupplierDbfReader::readWbClientSupplierFromAcfDbfRecord);
    }

    public Stream<WbDocument> streamBookYearDocuments(WinbooksFileConfiguration fileConfiguration, WbBookYearFull bookYear) {
        return documentService.streamBookYearDocuments(fileConfiguration, bookYear);
    }

    public Optional<byte[]> getDocumentData(WinbooksFileConfiguration fileConfiguration, WbDocument document) {
        return documentService.getDocumentData(fileConfiguration, document);
    }


    public Stream<WbBookYearFull> streamBookYears(WinbooksFileConfiguration winbooksFileConfiguration) {
        //TODO: currently, we can findWbBookYearFull more info out of the badly structured param table

        // fall-back: a lot of customers seem not to have table above
        Stream<WbBookYearFull> bookYearsFromParamsTables = listBookYearsFromParamTable(winbooksFileConfiguration).stream();
//        if (tableExistsForCurrentBookYear(winbooksFileConfiguration, BOOKYEARS_TABLE_NAME)) {
//            Stream<WbBookYearFull> bookyearStream = streamBookYearsFromBookYearsTable(winbooksFileConfiguration);
//            return Stream.concat(bookYearsFromParamsTables, bookyearStream)
//                    .distinct();
//        } else {
        return bookYearsFromParamsTables;
//        }
    }

    public int getAccountNumberLengthFromParamsTable(WinbooksFileConfiguration winbooksFileConfiguration) {
        Map<String, String> paramMap = getParamMap(winbooksFileConfiguration);
        String accountPictureValueNullable = paramMap.get("AccountPicture");
        return Optional.ofNullable(accountPictureValueNullable)
                .flatMap(this::getAccountNumberLengthFromAccountPictureParamValue)
                .orElse(ACCOUNT_NUMBER_DEFAULT_LENGTH);
    }

    public WinbooksDossierThirdParty getDossierThirdPartyFromParamTable(WinbooksFileConfiguration winbooksFileConfiguration) {
        Map<String, String> paramMap = getParamMap(winbooksFileConfiguration);
        WinbooksDossierThirdParty thirdParty = new WinbooksDossierThirdParty();

        String winbooksCompanyName = winbooksFileConfiguration.getWinbooksCompanyName();
        thirdParty.setCode(winbooksCompanyName);

        Optional.ofNullable(paramMap.get(WinbooksParamNames.COMPANY_NAME))
                .ifPresent(thirdParty::setName);

        Optional.ofNullable(paramMap.get(WinbooksParamNames.COMPANY_ADDRESS_1))
                .ifPresent(thirdParty::setAddress1);

        Optional.ofNullable(paramMap.get(WinbooksParamNames.COMPANY_ADDRESS_2))
                .ifPresent(thirdParty::setAddress2);

        Optional.ofNullable(paramMap.get(WinbooksParamNames.COMPANY_ZIP))
                .ifPresent(thirdParty::setZip);

        Optional.ofNullable(paramMap.get(WinbooksParamNames.COMPANY_CITY))
                .ifPresent(thirdParty::setCity);

        Optional.ofNullable(paramMap.get(WinbooksParamNames.COMPANY_COUNTRY))
                .ifPresent(thirdParty::setCountryCode);

        Optional.ofNullable(paramMap.get(WinbooksParamNames.COMPANY_VAT_NUMBER))
                .ifPresent(thirdParty::setVatNumber);

        Optional.ofNullable(paramMap.get(WinbooksParamNames.COMPANY_TYPE))
                .ifPresent(thirdParty::setCompanyType);

        return thirdParty;
    }

    /**
     * Creates directory hierarchy, honoring config case-insensitive settings, and preventing ftp errors on exsting
     * directories.
     *
     * @param fileConfiguration
     * @param path
     * @return the resolved path, which might just have been created
     */
    public Path createDirectories(WinbooksFileConfiguration fileConfiguration, Path path) {
        // To handle ftp errors, create directories at each level after checking it does not exist yet
        boolean resolveCaseInsensitiveSiblings = fileConfiguration.isResolveCaseInsensitiveSiblings();
        Path rootPath = fileConfiguration.getRootPath();

        Path relativePath = path;
        if (path.isAbsolute()) {
            relativePath = rootPath.relativize(path);
        }

        Path curPath = rootPath;
        int pathNameCount = relativePath.getNameCount();

        for (int nameIndex = 0; nameIndex < pathNameCount; nameIndex++) {
            Path nextName = relativePath.getName(nameIndex);
            final Path currentPathImmutable = curPath;
            curPath = WinbooksPathUtils.resolvePath(curPath, nextName.toString(), resolveCaseInsensitiveSiblings)
                    .map(this::ensurePathIsDirectory)
                    .orElseGet(() -> this.createDirectory(currentPathImmutable, nextName));
        }
        return curPath;
    }

    private Path createDirectory(Path curPath, Path nextName) {
        Path pathToCreate = curPath.resolve(nextName);
        try {
            Files.createDirectory(pathToCreate);
        } catch (IOException e) {
            throw new WinbooksException(WinbooksError.USER_FILE_ERROR, e);
        }
        return pathToCreate;
    }

    private Path ensurePathIsDirectory(Path resolvedPath) {
        boolean resolvedPathIsDirectory = Files.isDirectory(resolvedPath);
        if (!resolvedPathIsDirectory) {
            throw new WinbooksException(WinbooksError.USER_FILE_ERROR,
                    "Attempt to create directory " + resolvedPath + ", but this is already a file.");
        }
        return resolvedPath;
    }

    Stream<DbfRecord> streamTable(WinbooksFileConfiguration winbooksFileConfiguration, String tableName) {
        Path baseFolderPath = WinbooksPathUtils.getDossierBasePath(winbooksFileConfiguration);
        InputStream tableInputStream = getTableInputStream(winbooksFileConfiguration, baseFolderPath, tableName);
        Charset charset = winbooksFileConfiguration.getCharset();
        return DbfUtils.streamDbf(tableInputStream, charset);
    }

    private Stream<WbEntry> streamBookYearAct(WinbooksFileConfiguration winbooksFileConfiguration,
                                              WbEntryDbfReader dbfReader, WbBookYearFull bookYearFull) {
        Optional<Path> bookYearBasePath = WinbooksPathUtils.getBookYearBasePath(winbooksFileConfiguration, bookYearFull);
        return streamOptional(bookYearBasePath)
                .flatMap(basePath -> streamTable(winbooksFileConfiguration, basePath, WinbooksTableName.ACCOUNTING_ENTRIES))
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
            Optional<String> archivePathNameOptional = Optional.ofNullable(archivePathName);

            boolean bookYearNameIncluded = winbooksFileConfiguration.getBookYearNameOptional()
                    .map(n -> n.equals(bookYearShortLabel) || n.equals(bookYearLongLabel) || n.equals(archivePathName))
                    .orElse(true);
            if (!bookYearNameIncluded) {
                continue;
            }

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
            wbBookYearFull.setArchivePathNameOptional(archivePathNameOptional);
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
        Path baseFolderPath = WinbooksPathUtils.getDossierBasePath(winbooksFileConfiguration);
        WbBookYearFullDbfReader wbBookYearFullDbfReader = new WbBookYearFullDbfReader();
        return streamTable(winbooksFileConfiguration, baseFolderPath, WinbooksTableName.BOOKYEARS_TABLE_NAME)
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
        Path baseFolderPath = WinbooksPathUtils.getDossierBasePath(winbooksFileConfiguration);
        return streamTable(winbooksFileConfiguration, baseFolderPath, WinbooksTableName.PARAM_TABLE_NAME)
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
        if (!winbooksFileConfiguration.isReadTablesToMemory()) {
            return readFileInputStream(path);
        }
        try {

            long time0 = System.currentTimeMillis();
            byte[] bytes = Files.readAllBytes(path);
            long time1 = System.currentTimeMillis();
            long deltaTime = time1 - time0;
            LOGGER.log(Level.FINER, "READ table (" + path + "): " + deltaTime);

            return new ByteArrayInputStream(bytes);
        } catch (IOException exception) {
            return readFileInputStream(path);

        }
    }

    private InputStream readFileInputStream(Path path) {
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new WinbooksException(WinbooksError.USER_FILE_ERROR, "Unable to open table", e);
        }
    }

    private boolean tableExistsForCurrentBookYear(WinbooksFileConfiguration winbooksFileConfiguration, String tableName) {
        String baseName = winbooksFileConfiguration.getWinbooksCompanyName();
        String tableFileName = getTableFileName(baseName, tableName);
        Path baseFolderPath = WinbooksPathUtils.getDossierBasePath(winbooksFileConfiguration);
        boolean resolveCaseInsensitiveSiblings = winbooksFileConfiguration.isResolveCaseInsensitiveSiblings();
        Optional<Path> tablePathOptional = WinbooksPathUtils.resolvePath(baseFolderPath, tableFileName, resolveCaseInsensitiveSiblings);
        return tablePathOptional.isPresent();
    }

    private Optional<Path> resolveTablePathWithCompanyBaseNameOptional(Path basePath, String baseName, String tableName, boolean resolveCaseInsensitiveSiblings) {
        String tableFileName = getTableFileName(baseName, tableName);
        Optional<Path> tablePathOptional = WinbooksPathUtils.resolvePath(basePath, tableFileName, resolveCaseInsensitiveSiblings);
        return tablePathOptional;
    }


    private Optional<Path> resolveTablePathWithCompanyBaseNameButKeepingYearSuffixOptional(Path basePath, String baseName, String tableName, boolean resolveCaseInsensitiveSiblings) {
        String pathName = basePath.getFileName().toString();
        Pattern yearPattern = Pattern.compile(".*-([0-9]{4})");
        Matcher matcher = yearPattern.matcher(pathName);
        if (matcher.matches()) {
            String yearPart = matcher.group(1);
            String baseNameWithYearSufix = baseName + "-" + yearPart;
            String tableFileName = getTableFileName(baseNameWithYearSufix, tableName);
            Optional<Path> tablePathOptional = WinbooksPathUtils.resolvePath(basePath, tableFileName, resolveCaseInsensitiveSiblings);
            return tablePathOptional;
        } else {
            return Optional.empty();
        }
    }

    private Optional<Path> resolveTablePathWithPathFilenameAsBaseNameOptional(Path basePath, String tableName, boolean resolveCaseInsensitiveSiblings) {
        String baseName = basePath.getFileName().toString();
        String tableFileName = getTableFileName(baseName, tableName);
        Optional<Path> tablePathOptional = WinbooksPathUtils.resolvePath(basePath, tableFileName, resolveCaseInsensitiveSiblings);
        return tablePathOptional;
    }

    private Path resolveTablePathOrThrow(WinbooksFileConfiguration winbooksFileConfiguration, Path basePath, String tableName) {
        Path existingPath = tableFilePathMap.get(tableName);
        if (existingPath != null) {
            return existingPath;
        }

        // We might want to open tables while iterating over all companies. In such case, we have to guess the base
        // name from path name
        Optional<String> baseNameOptional = Optional.ofNullable(winbooksFileConfiguration.getWinbooksCompanyName())
                .filter(s -> !s.isBlank())
                .or(() -> WinbooksPathUtils.tryGetBaseNameFromPathName(basePath.getFileName().toString()));

        boolean resolveCaseInsensitiveSiblings = winbooksFileConfiguration.isResolveCaseInsensitiveSiblings();

        // With a basePath COMPANY-SOMETHING-2013, a company base name COMPANY, and a table name 'table',
        // try to resolve /COMPANY-SOMETHING-2013/COMPANY-SOMETHING-2013_table.dbf,
        // then try to resolve /COMPANY-SOMETHING-2013/COMPANY_table.dbf,
        // then try to resolve /COMPANY-SOMETHING-2013/COMPANY-2013_table.dbf, (Seems like a workaround for dossier not properly named)
        // otherwise throw.

        Path tablePath = resolveTablePathWithPathFilenameAsBaseNameOptional(basePath, tableName, resolveCaseInsensitiveSiblings)
                .or(() -> baseNameOptional.flatMap(baseName -> resolveTablePathWithCompanyBaseNameOptional(basePath, baseName, tableName, resolveCaseInsensitiveSiblings)))
                .or(() -> baseNameOptional.flatMap(bsaeName -> resolveTablePathWithCompanyBaseNameButKeepingYearSuffixOptional(basePath, bsaeName, tableName, resolveCaseInsensitiveSiblings)))
                .orElseThrow(() -> {
                    String baseFolderPathName = getPathFileNameString(basePath);

                    String message = MessageFormat.format("Could not find table {0} in folder {1}", tableName, baseFolderPathName);
                    return new WinbooksException(WinbooksError.DOSSIER_NOT_FOUND, message);
                });
        tableFilePathMap.put(tableName, tablePath);
        LOGGER.info("Found table " + tableName + " at " + tablePath.toString());
        return tablePath;
    }


    private String getTableFileName(String baseName, String tableName) {
        String tablePrefix = baseName.replace("_", "");
        return tablePrefix + "_" + tableName + DBF_EXTENSION;
    }

}

