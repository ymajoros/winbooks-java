package be.valuya.winbooks.api.extra;

import be.valuya.jbooks.model.WbBookYearFull;
import be.valuya.winbooks.api.extra.config.WinbooksFileConfiguration;
import be.valuya.winbooks.domain.error.ArchivePathNotFoundException;
import be.valuya.winbooks.domain.error.WinbooksError;
import be.valuya.winbooks.domain.error.WinbooksException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class WinbooksPathUtils {
    private static Logger LOGGER = Logger.getLogger(WinbooksPathUtils.class.getName());


    static Optional<Path> getBookYearBasePath(WinbooksFileConfiguration fileConfiguration, WbBookYearFull wbBookYearFull) {
        boolean resolveArchivedBookYears = fileConfiguration.isResolveArchivedBookYears();
        boolean ignoreMissingArchives = fileConfiguration.isIgnoreMissingArchives();
        Map<String, Path> pathMappings = getPathMappingsIncludingRootPath(fileConfiguration);
        Path baseFolderPath = getDossierBasePath(fileConfiguration);

        try {
            boolean archivedBookYear = isArchivedBookYear(wbBookYearFull);
            if (archivedBookYear) {
                if (resolveArchivedBookYears) {
                    Path archivePath = resolveArchivePathOrThrow(wbBookYearFull, fileConfiguration, pathMappings);
                    return Optional.of(archivePath);
                } else {
                    return Optional.empty();
                }
            } else {
                return Optional.of(baseFolderPath);
            }
        } catch (ArchivePathNotFoundException archivePathNotFoundException) {
            if (ignoreMissingArchives) {
                return Optional.empty();
            } else {
                throw new WinbooksException(WinbooksError.BOOKYEAR_NOT_FOUND, archivePathNotFoundException);
            }
        }
    }

    static Map<String, Path> getPathMappingsIncludingRootPath(WinbooksFileConfiguration fileConfiguration) {
        Path configurationRootPath = fileConfiguration.getRootPath();
        Map<String, Path> configurationMappings = fileConfiguration.getPathMappings();

        Map<String, Path> allMappings = new HashMap<>();

        allMappings.put("/", configurationRootPath);
        allMappings.putAll(configurationMappings);
        return allMappings;
    }

    static Path getDossierBasePath(WinbooksFileConfiguration fileConfiguration) {
        Path configurationRootPath = fileConfiguration.getRootPath();
        String baseName = fileConfiguration.getBasePathName();
        String winbooksCompanyName = fileConfiguration.getWinbooksCompanyName();
        boolean resolveCaseInsensitiveSiblings = fileConfiguration.isResolveCaseInsensitiveSiblings();

        return resolvePath(configurationRootPath, baseName, resolveCaseInsensitiveSiblings)
                .or(() -> resolvePath(configurationRootPath, winbooksCompanyName, resolveCaseInsensitiveSiblings))
                .orElseThrow(() -> new WinbooksException(WinbooksError.FATAL_ERRORS, "Could not resolve dossier base path " + baseName));
    }

    static Optional<Path> resolvePathNameWithMappings(String absolutePathName, Map<String, Path> pathMappings, boolean resolveCaseInsensitiveSiblings) {
        Optional<Path> resolvedPath = pathMappings.entrySet().stream()
                .map(mappingKeyPath -> resolvePathMapping(mappingKeyPath, absolutePathName, resolveCaseInsensitiveSiblings))
                .flatMap(Optional::stream)
                .findFirst();
        return resolvedPath;
    }

    static Optional<Path> resolvePath(Path parentPath, String fileName, boolean resolveCaseInsensitiveSiblings) {
        if (parentPath == null || fileName == null) {
            return Optional.empty();
        }
        Path defaultPath = parentPath.resolve(fileName);
        if (Files.exists(defaultPath)) {
            return Optional.of(defaultPath);
        }
        boolean parentExists = Files.exists(parentPath);
        if (!parentExists) {
            return Optional.empty();
        }
        if (fileName.endsWith(".dbf")) {
            String capitalizedExtensionFileName = fileName.replace(".dbf", ".DBF");
            Path capitalizedExtensionPath = parentPath.resolve(capitalizedExtensionFileName);
            if (Files.exists(capitalizedExtensionPath)) {
                return Optional.of(capitalizedExtensionPath);
            }
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.equals(fileName)) {
            Path lowerPath = parentPath.resolve(lowerName);
            if (Files.exists(lowerPath)) {
                return Optional.of(lowerPath);
            }
        }
        String upperName = fileName.toUpperCase(Locale.ROOT);
        if (!upperName.equals(fileName)) {
            Path upperPath = parentPath.resolve(upperName);
            if (Files.exists(upperPath)) {
                return Optional.of(upperPath);
            }
        }
        if (resolveCaseInsensitiveSiblings) {
            return WinbooksPathUtils.resolveCaseInsensitivePathOptional(parentPath, fileName);
        } else {
            return Optional.empty();
        }
    }

    static Stream<Path> streamDirectoryFiles(Path path, Predicate<Path> acceptFilePredicate) {
        try {
            long time0 = System.currentTimeMillis();
            List<Path> filesContent = Files.find(path, 1,
                    (visitedPath, attr) -> checkAcceptFilePredicate(acceptFilePredicate, visitedPath, attr))
                    .collect(Collectors.toList());
            long time1 = System.currentTimeMillis();
            long deltaTimeWalk = time1 - time0;
            LOGGER.finer("**** FIND dir files(" + path.toString() + ") " + deltaTimeWalk);

            return filesContent.stream();
        } catch (IOException exception) {
            throw new WinbooksException(WinbooksError.UNKNOWN_ERROR, exception);
        }
    }


    static LocalDateTime getLastModifiedTime(Path path) {
        try {
            FileTime lastModifiedTime = Files.getLastModifiedTime(path);
            return toLocalDateTime(lastModifiedTime);
        } catch (IOException exception) {
            throw new WinbooksException(WinbooksError.UNKNOWN_ERROR, exception);
        }
    }

    static LocalDateTime getCreationTime(Path path) {
        try {
            BasicFileAttributes basicFileAttributes = Files.readAttributes(path, BasicFileAttributes.class);
            FileTime creationFileTime = basicFileAttributes.creationTime();
            return toLocalDateTime(creationFileTime);
        } catch (IOException exception) {
            throw new WinbooksException(WinbooksError.UNKNOWN_ERROR, exception);
        }
    }

    static boolean isArchivedPathName(String pathName) {
        return tryGetBaseNameFromPathName(pathName)
                .map(s -> !s.equals(pathName))
                .orElse(false);
    }

    static Optional<String> tryGetBaseNameFromPathName(String pathName) {
        // Strip archives suffix, eg BASENAME-2012
        Pattern pattern = Pattern.compile("(.+)-[0-9]{4}");
        Matcher matcher = pattern.matcher(pathName);
        if (matcher.matches()) {
            String group = matcher.group(1);
            return Optional.of(group);
        } else {
            return Optional.empty();
        }
    }

    private static Optional<Path> resolveCaseInsensitivePathOptional(Path parentPath, String fileName) {
        try {
            // Find another child of parent with a similar name
            long time0 = System.currentTimeMillis();
            Optional<Path> firstFoundPathOptional = findSiblingWithSameName(parentPath, fileName);
            long timeAfterWalk = System.currentTimeMillis();
            long deltaTimeWalk = timeAfterWalk - time0;
            LOGGER.finer("****FIND case-insensitive-sibling (" + fileName + "): " + deltaTimeWalk);
            return firstFoundPathOptional;
        } catch (IOException exception) {
            throw new WinbooksException(WinbooksError.UNKNOWN_ERROR, exception);
        }
    }


    private static Optional<Path> findSiblingWithSameName(Path parentPath, String fileName) throws IOException {
        BiPredicate<Path, BasicFileAttributes> predicate = (path, attr) -> isSamePathNameIgnoreCase(path, fileName);
        return Files.find(parentPath, 1, predicate).findFirst();
    }


    private static boolean isSamePathNameIgnoreCase(Path path, String fileName) {
        return Optional.ofNullable(path.getFileName())
                .map(Path::toString)
                .map(fileName::equalsIgnoreCase)
                .orElse(false);
    }


    private static boolean checkAcceptFilePredicate(Predicate<Path> acceptPredicate, Path visitedPath, BasicFileAttributes attr) {
        boolean directory = attr.isDirectory();
        if (!directory) {
            return acceptPredicate.test(visitedPath);
        } else {
            return false;
        }
    }

    private static LocalDateTime toLocalDateTime(FileTime fileTime) {
        Instant lastModifiedInstant = fileTime.toInstant();
        return LocalDateTime.ofInstant(lastModifiedInstant, ZoneId.systemDefault());
    }

    private static boolean isArchivedBookYear(WbBookYearFull wbBookYearFull) {
        Optional<String> archivePathNameOptional = Optional.ofNullable(wbBookYearFull)
                .flatMap(WbBookYearFull::getArchivePathNameOptional);
        return archivePathNameOptional.isPresent();
    }

    private static Path resolveArchivePathOrThrow(WbBookYearFull wbBookYearFull,
                                                  WinbooksFileConfiguration fileConfiguration,
                                                  Map<String, Path> pathMappings) throws ArchivePathNotFoundException {
        return resolveBookYearArchivePath(wbBookYearFull, fileConfiguration, pathMappings)
                .orElseThrow(() -> new ArchivePathNotFoundException(wbBookYearFull));
    }

    private static Optional<Path> resolveBookYearArchivePath(WbBookYearFull wbBookYearFull,
                                                             WinbooksFileConfiguration fileConfiguration,
                                                             Map<String, Path> pathMappings) {
        if (!isArchivedBookYear(wbBookYearFull)) {
            return Optional.empty();
        }
        String archivePathName = wbBookYearFull.getArchivePathNameOptional().orElseThrow(IllegalStateException::new);
        boolean tryResolveArchivedBookYearsFromRootPath = fileConfiguration.isTryResolveArchivedBookYearsFromRootPath();
        if (tryResolveArchivedBookYearsFromRootPath) {
            Path rootPath = fileConfiguration.getRootPath();
            String pathFileName = convertToUnixPath(archivePathName)
                    .getFileName()
                    .toString();
            Path resolvedFromRoot = rootPath.resolve(pathFileName);
            if (Files.exists(resolvedFromRoot)) {
                return Optional.of(resolvedFromRoot);
            }
        }
        boolean resolveCaseInsensitiveSiblings = fileConfiguration.isResolveCaseInsensitiveSiblings();
        return resolvePathNameWithMappings(archivePathName, pathMappings, resolveCaseInsensitiveSiblings);
    }

    private static Path convertToUnixPath(String absolutePathName) {
        if (absolutePathName.startsWith("/")) {
            // unix path
            return Paths.get(absolutePathName);
        } else if (absolutePathName.substring(1).startsWith(":\\")) {
            // windows path
            String forwardSLashPath = absolutePathName.replaceAll("\\\\", "/");
            return Paths.get(forwardSLashPath);
        } else if (absolutePathName.startsWith("\\\\")) {
            // windows smb path
            String forwardSLashPath = absolutePathName
                    .replaceAll("^\\\\", "/SMB/")
                    .replaceAll("\\\\", "/");
            return Paths.get(forwardSLashPath);
        } else {
            // delegate to registered filesystem providers
            return Paths.get(absolutePathName);
        }
    }

    private static Optional<Path> resolvePathMapping(Map.Entry<String, Path> pathMapping, String absoluteChildPath, boolean resolveCaseInsensitiveSiblings) {
        try {
            String mappingKey = pathMapping.getKey();
            Path mappingValue = pathMapping.getValue();

            Path unixChildPath = convertToUnixPath(absoluteChildPath);
            Path unixKeyPath = convertToUnixPath(mappingKey);
            Path relativized = unixKeyPath.relativize(unixChildPath).normalize();

            // We expect fewer names for the relativized path, otherwise we might have a path with '../' prefixes.
            int childNameCOunt = unixChildPath.getNameCount();
            int relativizedNameCount = relativized.getNameCount();
            if (childNameCOunt < relativizedNameCount) {
                return Optional.empty();
            }

            Iterator<Path> relativizedNamesIterator = relativized.iterator();
            Path resolved = mappingValue;
            // Resolve each path 1 by 1 to account for case-insensitive siblings flag
            while (relativizedNamesIterator.hasNext()) {
                String nextName = relativizedNamesIterator.next().toString();
                Optional<Path> nextResolvedPathOptional = resolvePath(resolved, nextName, resolveCaseInsensitiveSiblings);
                if (nextResolvedPathOptional.isPresent()) {
                    resolved = nextResolvedPathOptional.get();
                } else {
                    return Optional.empty(); // Attempt next mappings
                }
            }

            return Optional.of(resolved);
        } catch (IllegalArgumentException e) {
            // Path could not be relativized
            return Optional.empty();
        }
    }

}
