package be.valuya.winbooks.api.extra;

import be.valuya.jbooks.model.WbBookYearFull;
import be.valuya.jbooks.model.WbDocument;
import be.valuya.jbooks.model.WbPeriod;
import be.valuya.winbooks.api.extra.config.WinbooksFileConfiguration;
import be.valuya.winbooks.domain.error.WinbooksError;
import be.valuya.winbooks.domain.error.WinbooksException;
import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.PdfCopyFields;
import com.lowagie.text.pdf.PdfReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class WinbooksDocumentsService {
    private static Logger LOGGER = Logger.getLogger(WinbooksDocumentsService.class.getName());

    // Documents path: /<book year>/<dbk>/<file_name>
    // First (legacy?) file pattern: 'LEDGER_PERIODINDEX_DOCNUMBER_PAGETWODIGITS.pdf' (ACHATS_01_200084_00.pdf)
    private static final Pattern BOOK_YEAR_DOCUMENT_FILENAME_PATTERN_1 = Pattern.compile("^(\\w+)_(\\d+)_(\\d+)_(\\d{2}).pdf$");
    // New file pattern: 'LEDGER_PERIODINDEX_DOCNUMBER___PAGEFOURDIGITS_FLAG.pdf' (ACHATS_01_200083___00000_B.pdf)
    // Flag may be A or B at least
    private static final Pattern BOOK_YEAR_DOCUMENT_FILENAME_PATTERN_2 = Pattern.compile("^(\\w+)_(\\d+)_(\\d+)___(\\d{5})_([A-Z]).pdf$");


    Stream<WbDocument> streamBookYearDocuments(WinbooksFileConfiguration fileConfiguration, WbBookYearFull bookYear) {
        String bookYearName = bookYear.getShortName();
        boolean resolveCaseInsensitiveSiblings = fileConfiguration.isResolveCaseInsensitiveSiblings();
        boolean resolveDocumentTimes = fileConfiguration.isResolveDocumentTimes();

//        String[] documentsPath = new String[]{null};
        // Stream documents at /${basePath}/${documentsPath}/${bookYearName}/<book year doc format>
        Optional<Path> bookYearBasePathOptional = WinbooksPathUtils.getBookYearBasePath(fileConfiguration, bookYear);
        if (bookYearBasePathOptional.isEmpty()) {
            return Stream.empty();
        }
        Path bookYearBasePath = bookYearBasePathOptional.get();
        Optional<Path> documentPathOptional = streamDocumentsPaths(bookYearBasePath)
                .map(p -> WinbooksPathUtils.resolvePath(p, bookYearName, resolveCaseInsensitiveSiblings))
                .flatMap(Optional::stream)
                .findFirst();
        if (documentPathOptional.isEmpty()) {
            return Stream.empty();
        }
        Path documentsPath = documentPathOptional.get();
        Path rootPath = fileConfiguration.getRootPath();
        return this.streamBookYearDocuments(rootPath, documentsPath, bookYear, resolveDocumentTimes);
    }

    Optional<byte[]> getDocumentData(WinbooksFileConfiguration fileConfiguration, WbDocument document) {
        boolean resolveCaseInsensitiveSiblings = fileConfiguration.isResolveCaseInsensitiveSiblings();
        return getDocumentAbsolutePath(fileConfiguration, document)
                .flatMap(docPath -> getDocumentAllPartsPdfContent(docPath, document, resolveCaseInsensitiveSiblings));
    }

    private Stream<WbDocument> streamBookYearDocuments(Path rootPath, Path bookYearDocumentFolderPath, WbBookYearFull bookYear, boolean resolveAccessTimes) {
        try {
            return Files.list(bookYearDocumentFolderPath)
                    .flatMap(bookYearDbkPath -> streamDbkBookYearDocuments(rootPath, bookYearDbkPath, bookYear, resolveAccessTimes));
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private Stream<WbDocument> streamDbkBookYearDocuments(Path rootPath, Path docPath, WbBookYearFull bookYear, boolean resolveAccessTime) {
        Collector<WbDocument, ?, Optional<WbDocument>> maxPartNumberCompoarator = Collectors.maxBy(Comparator.comparing(WbDocument::getPartCount));
        return WinbooksPathUtils.streamDirectoryFiles(docPath, this::isDocument)
                .map(documentPath -> getDocumentOptional(rootPath, documentPath, bookYear, resolveAccessTime))
                .flatMap(this::streamOptional)
                .collect(Collectors.groupingBy(Function.identity(), maxPartNumberCompoarator))
                .values()
                .stream()
                .flatMap(this::streamOptional)
                .map(this::getDocumentWithCorrectPartCount);
    }


    private WbDocument getDocumentWithCorrectPartCount(WbDocument lastPartWbDocument) {
        WbPeriod wbPeriod = lastPartWbDocument.getWbPeriod();
        int partCount = lastPartWbDocument.getPartCount() + 1;
        String documentNumber = lastPartWbDocument.getDocumentNumber();
        LocalDateTime creationTime = lastPartWbDocument.getCreationTime();
        LocalDateTime updatedTime = lastPartWbDocument.getUpdatedTime();
        String dbkCode = lastPartWbDocument.getDbkCode();
        String fileNameTemplate = lastPartWbDocument.getFileNameTemplate();
        String absolutePathName = lastPartWbDocument.getAbsolutePathName();

        WbDocument pageNumberDocument = new WbDocument();
        pageNumberDocument.setWbPeriod(wbPeriod);
        pageNumberDocument.setPartCount(partCount);
        pageNumberDocument.setDocumentNumber(documentNumber);
        pageNumberDocument.setCreationTime(creationTime);
        pageNumberDocument.setUpdatedTime(updatedTime);
        pageNumberDocument.setDbkCode(dbkCode);
        pageNumberDocument.setFileNameTemplate(fileNameTemplate);
        pageNumberDocument.setAbsolutePathName(absolutePathName);

        return pageNumberDocument;
    }


    private Stream<Path> streamDocumentsPaths(Path basePath) {
        return Stream.of(
                basePath.resolve("Document"),
                basePath.resolve("document"),
                basePath.resolve("DOCUMENT")
        );
    }

    private <T> Stream<T> streamOptional(Optional<T> optional) {
        return optional.map(Stream::of)
                .orElseGet(Stream::empty);
    }

    private boolean isDocument(Path documentPath) {
        String fileName = documentPath.getFileName().toString();
        Matcher matcher1 = BOOK_YEAR_DOCUMENT_FILENAME_PATTERN_1.matcher(fileName);
        Matcher matcher2 = BOOK_YEAR_DOCUMENT_FILENAME_PATTERN_2.matcher(fileName);
        return matcher1.matches() || matcher2.matches();
    }

    private Optional<WbDocument> getDocumentOptional(Path rootPath, Path documentPath, WbBookYearFull bookYear, boolean resolveAccessTimes) {
        String fileName = documentPath.getFileName().toString();
        Matcher matcher1 = BOOK_YEAR_DOCUMENT_FILENAME_PATTERN_1.matcher(fileName);
        Matcher matcher2 = BOOK_YEAR_DOCUMENT_FILENAME_PATTERN_2.matcher(fileName);

        String actualDbkCode;
        String periodName;
        String documentNumber;
        String partNrString;
        String fileNameTemplate;
        int partNumber;
        if (matcher1.matches()) {
            actualDbkCode = matcher1.group(1);
            periodName = matcher1.group(2);
            documentNumber = matcher1.group(3);
            partNrString = matcher1.group(4);
            partNumber = Integer.valueOf(partNrString);
            fileNameTemplate = MessageFormat.format("{0}_{1}_{2}_{3}.pdf",
                    actualDbkCode, periodName, documentNumber, "{0,number,00}");
        } else if (matcher2.matches()) {
            actualDbkCode = matcher2.group(1);
            periodName = matcher2.group(2);
            documentNumber = matcher2.group(3);
            partNrString = matcher2.group(4);
            // TODO
            String unknownFlag = matcher2.group(5);
            partNumber = Integer.valueOf(partNrString);
            fileNameTemplate = MessageFormat.format("{0}_{1}_{2}___{3}_{4}.pdf",
                    actualDbkCode, periodName, documentNumber, "{0,number,00000}", unknownFlag);
        } else {
            return Optional.empty();
        }
        Path absoluteDocPathRelativeToRoot = rootPath.relativize(documentPath.toAbsolutePath());

        WbDocument wbDocument = new WbDocument();
        wbDocument.setDbkCode(actualDbkCode);
        wbDocument.setDocumentNumber(documentNumber);
        wbDocument.setPartCount(partNumber);
        wbDocument.setWbPeriod(getWbPeriod(bookYear, periodName));
        wbDocument.setFileNameTemplate(fileNameTemplate);
        wbDocument.setAbsolutePathName(absoluteDocPathRelativeToRoot.toString());

        if (resolveAccessTimes) {
            long time0 = System.currentTimeMillis();
            LocalDateTime lastModifiedLocalTime = WinbooksPathUtils.getLastModifiedTime(documentPath);
            LocalDateTime creationTime = WinbooksPathUtils.getCreationTime(documentPath);
            long time1 = System.currentTimeMillis();
            long totalTIme = time1 - time0;
            LOGGER.finer("*** file times(" + documentPath.toString() + ": " + totalTIme + "ms");

            wbDocument.setUpdatedTime(lastModifiedLocalTime);
            wbDocument.setCreationTime(creationTime);
        }

        return Optional.of(wbDocument);
    }

    private WbPeriod getWbPeriod(WbBookYearFull bookYear, String periodName) {
        return bookYear.getPeriodList()
                .stream()
                .filter(wbPeriod -> isPeriodIndex(wbPeriod, periodName))
                .findFirst()
                .orElseThrow(() -> new WinbooksException(WinbooksError.NO_PERIOD, "Period not found: " + periodName));
    }

    private boolean isPeriodIndex(WbPeriod wbPeriod, String expectedPeriodName) {
        int periodIndex = wbPeriod.getIndex();
        String periodIndexName = String.format("%02d", periodIndex);
        return expectedPeriodName.equals(periodIndexName);
    }


    private Optional<Path> getDocumentAbsolutePath(WinbooksFileConfiguration fileConfiguration, WbDocument document) {
        boolean resolveCaseInsensitiveSiblings = fileConfiguration.isResolveCaseInsensitiveSiblings();
//        WbBookYearFull bookYearFull = document.getWbPeriod().getWbBookYearFull();
        Path rootPath = fileConfiguration.getRootPath();
        Path resolved = rootPath.resolve(document.getAbsolutePathName());
        return Optional.ofNullable(resolved.getParent());
//       return Optional.ofNullable(resolveDocuCmentDirectoryPath(document, resolveCaseInsensitiveSiblings));
//        return WinbooksPathUtils.getBookYearBasePath(fileConfiguration, bookYearFull)
//                .flatMap(this::streamDocumentsPaths)
//                .map(documentsPath -> resolveDocumentDirectoryPath(documentsPath, document, resolveCaseInsensitiveSiblings));
    }

    private Path resolveDocumentDirectoryPath(WbDocument document, boolean resolveCaseInsensitiveSiblings) {
        WbBookYearFull wbBookYearFull = document.getWbPeriod().getWbBookYearFull();
        String bookYearShortName = wbBookYearFull.getShortName();
        String dbCode = document.getDbkCode();
        String absolutePathName = document.getAbsolutePathName();
        Path documentParentpath = Paths.get(document.getAbsolutePathName()).getParent();
        return documentParentpath;
//        return WinbooksPathUtils.resolvePath(baseDocumentPath, bookYearShortName, resolveCaseInsensitiveSiblings)
//                .flatMap(bookYearPath -> WinbooksPathUtils.resolvePath(bookYearPath, dbCode, resolveCaseInsensitiveSiblings))
//                .orElseGet(() -> baseDocumentPath.resolve(bookYearShortName).resolve(dbCode));
    }


    private Optional<byte[]> getDocumentAllPartsPdfContent(Path documentPath, WbDocument document, boolean resolveCaseInsitiveSiblings) {
        return streamDocumentPartsPaths(documentPath, document, resolveCaseInsitiveSiblings)
                .map(this::readAllBytes)
                .reduce(this::mergePdf);
    }

    private Stream<Path> streamDocumentPartsPaths(Path basePath, WbDocument document, boolean resolveCaseInsensitiveSiblings) {
        int partCount = document.getPartCount();

        return IntStream.range(0, partCount)
                .mapToObj(partIndex -> getDocumentPartPathName(partIndex, document))
                .map(pagePathName -> WinbooksPathUtils.resolvePath(basePath, pagePathName, resolveCaseInsensitiveSiblings))
                .flatMap(this::streamOptional);
    }


    private String getDocumentPartPathName(int partIndex, WbDocument document) {
        String fileNameTemplate = document.getFileNameTemplate();
        String fileName = MessageFormat.format(fileNameTemplate, partIndex);
        return fileName;
    }


    private byte[] readAllBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new WinbooksException(WinbooksError.USER_FILE_ERROR, exception);
        }
    }

    private byte[] mergePdf(byte[] pdfData1, byte[] pdfData2) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            PdfCopyFields pdfCopyFields = new PdfCopyFields(byteArrayOutputStream);

            PdfReader pdfReader1 = new PdfReader(pdfData1);
            pdfCopyFields.addDocument(pdfReader1);

            PdfReader pdfReader2 = new PdfReader(pdfData2);
            pdfCopyFields.addDocument(pdfReader2);

            pdfCopyFields.close();
            pdfReader1.close();
            pdfReader2.close();

            return byteArrayOutputStream.toByteArray();
        } catch (IOException | DocumentException exception) {
            throw new WinbooksException(WinbooksError.USER_FILE_ERROR, "Error while processing pdf files", exception);
        }
    }


}
