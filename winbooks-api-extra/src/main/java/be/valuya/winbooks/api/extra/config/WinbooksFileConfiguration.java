package be.valuya.winbooks.api.extra.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class WinbooksFileConfiguration {

    private String username;
    private String password;
    private Path rootPath;
    private String basePathName;
    private String winbooksCompanyName;
    private Charset charset = StandardCharsets.ISO_8859_1;
    // Maps path across filesystems.
    private Map<String, Path> pathMappings = new HashMap<>();

    private boolean ignoreConversionErrors = true;
    private boolean ignoreMissingArchives = true;
    private boolean readTablesToMemory = true;
    private boolean resolveArchivedBookYears = true;
    private boolean tryResolveArchivedBookYearsFromRootPath = true;
    private boolean resolveCaseInsensitiveSiblings = true;
    private boolean resolveUnmappedPeriodFromEntryDate = true;
    private boolean resolveDocumentTimes = true;
    private DocumentMatchingMode documentMatchingMode = DocumentMatchingMode.EAGERLY_CACHE_ALL_DOCUMENTS;
    private Optional<LocalDate> bookYearStartMinDateOptional = Optional.empty();
    private Optional<LocalDate> bookYearStartMaxDateOptional = Optional.empty();
    private Optional<String> bookYearName = Optional.empty();

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Path getRootPath() {
        return rootPath;
    }

    /**
     * The root path under which winbooks dossier directories are located.
     *
     * @param rootPath
     * @see WinbooksFileConfiguration#setBasePathName(String) setBasePathName
     * @see WinbooksFileConfiguration#setPathMappings(Map) setPathMappings
     */
    public void setRootPath(Path rootPath) {
        this.rootPath = rootPath;
    }

    public String getBasePathName() {
        return basePathName;
    }

    /**
     * This path name will be resolved from the root path, and tables are expected to be found there under.
     *
     * @param basePathName
     */
    public void setBasePathName(String basePathName) {
        this.basePathName = basePathName;
    }

    public String getWinbooksCompanyName() {
        return winbooksCompanyName;
    }


    /**
     * The company name that is used in winbooks references.
     * Tables file names are expected to start with this string.
     *
     * @return
     */
    public void setWinbooksCompanyName(String winbooksCompanyName) {
        this.winbooksCompanyName = winbooksCompanyName;
    }

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public boolean isIgnoreMissingArchives() {
        return ignoreMissingArchives;
    }

    public void setIgnoreMissingArchives(boolean ignoreMissingArchives) {
        this.ignoreMissingArchives = ignoreMissingArchives;
    }

    public boolean isReadTablesToMemory() {
        return readTablesToMemory;
    }

    public void setReadTablesToMemory(boolean readTablesToMemory) {
        this.readTablesToMemory = readTablesToMemory;
    }

    public boolean isResolveArchivedBookYears() {
        return resolveArchivedBookYears;
    }

    public void setResolveArchivedBookYears(boolean resolveArchivedBookYears) {
        this.resolveArchivedBookYears = resolveArchivedBookYears;
    }

    public boolean isResolveCaseInsensitiveSiblings() {
        return resolveCaseInsensitiveSiblings;
    }

    public void setResolveCaseInsensitiveSiblings(boolean resolveCaseInsensitiveSiblings) {
        this.resolveCaseInsensitiveSiblings = resolveCaseInsensitiveSiblings;
    }

    public boolean isResolveUnmappedPeriodFromEntryDate() {
        return resolveUnmappedPeriodFromEntryDate;
    }

    public void setResolveUnmappedPeriodFromEntryDate(boolean resolveUnmappedPeriodFromEntryDate) {
        this.resolveUnmappedPeriodFromEntryDate = resolveUnmappedPeriodFromEntryDate;
    }

    public DocumentMatchingMode getDocumentMatchingMode() {
        return documentMatchingMode;
    }

    public void setDocumentMatchingMode(DocumentMatchingMode documentMatchingMode) {
        this.documentMatchingMode = documentMatchingMode;
    }

    public boolean isResolveDocumentTimes() {
        return resolveDocumentTimes;
    }

    public void setResolveDocumentTimes(boolean resolveDocumentTimes) {
        this.resolveDocumentTimes = resolveDocumentTimes;
    }

    public Optional<LocalDate> getBookYearStartMinDateOptional() {
        return bookYearStartMinDateOptional;
    }

    public void setBookYearStartMinDate(LocalDate bookYearStartMinLoclDate) {
        this.bookYearStartMinDateOptional = Optional.of(bookYearStartMinLoclDate);
    }

    public Optional<LocalDate> getBookYearStartMaxDateOptional() {
        return bookYearStartMaxDateOptional;
    }

    public void setBookYearStartMaxDate(LocalDate bookYearStartMaxLoclDate) {
        this.bookYearStartMaxDateOptional = Optional.of(bookYearStartMaxLoclDate);
    }

    public boolean isIgnoreConversionErrors() {
        return ignoreConversionErrors;
    }

    public void setIgnoreConversionErrors(boolean ignoreConversionErrors) {
        this.ignoreConversionErrors = ignoreConversionErrors;
    }

    public void setBookYearName(String bookYearName) {
        this.bookYearName = Optional.ofNullable(bookYearName);
    }

    public Optional<String> getBookYearNameOptional() {
        return bookYearName;
    }

    public Map<String, Path> getPathMappings() {
        return pathMappings;
    }

    /**
     * Specify path mappings to resolve absolute paths encountered in winbooks tables.
     * <p></p>
     * The tables may contain references to some filesystem paths. For instance,
     * the book year table may contains a path for the archived folder location,
     * such as {@code 'C:\winbooks_archives\DOSSIER-2013'}.
     * <p></p>
     * This maps can be used to resolve those paths on provided filesystems. For instance, the following snippet maps
     * the archive directory from the above example to an ftp filesystem:
     *
     * <pre>{@code
     *   String archivePath = "C:\\winbooks_archives";
     *   Path ftpArchivePath = Paths.get("ftp://archives.winbooks.local");
     *   Map<String, Path> mappings = Map.of(archivePath, ftpArchivePath);
     * }</pre>
     *
     * <p></p>
     * When winbooks-java encounters a path, it will iterate over this map to resolve the path from the
     * correct filesystem. If a key of this map happens to be a parent of the encoutered path, this later
     * path will be relativized, then resolved against the map value Path. Paths will be normalized to
     * forward-slash unix-filesystem paths before comparison.
     *
     * @param pathMappings A map used to resolve paths present in the winbooks tables.
     *                     Keys contain the path expected to be present in the tables, as string.
     *                     Values contain the path from which resolution will be performed.
     */
    public void setPathMappings(Map<String, Path> pathMappings) {
        this.pathMappings = pathMappings;
    }

    public boolean isTryResolveArchivedBookYearsFromRootPath() {
        return tryResolveArchivedBookYearsFromRootPath;
    }

    public void setTryResolveArchivedBookYearsFromRootPath(boolean tryResolveArchivedBookYearsFromRootPath) {
        this.tryResolveArchivedBookYearsFromRootPath = tryResolveArchivedBookYearsFromRootPath;
    }
}
