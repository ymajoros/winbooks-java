package be.valuya.winbooks.api.extra.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class WinbooksFileConfiguration {

    private String username;
    private String password;
    private Path baseFolderPath;
    private String baseName;
    private Charset charset = StandardCharsets.ISO_8859_1;
    private boolean ignoreMissingArchives = true;
    private boolean readTablesToMemory;
    private boolean resolveArchivePaths;
    private boolean resolveCaseInsensitiveSiblings = true;
    private DocumentMatchingMode documentMatchingMode = DocumentMatchingMode.SKIP;

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

    public Path getBaseFolderPath() {
        return baseFolderPath;
    }

    public void setBaseFolderPath(Path baseFolderPath) {
        this.baseFolderPath = baseFolderPath;
    }

    public String getBaseName() {
        return baseName;
    }

    public void setBaseName(String baseName) {
        this.baseName = baseName;
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

    public boolean isResolveArchivePaths() {
        return resolveArchivePaths;
    }

    public void setResolveArchivePaths(boolean resolveArchivePaths) {
        this.resolveArchivePaths = resolveArchivePaths;
    }

    public boolean isResolveCaseInsensitiveSiblings() {
        return resolveCaseInsensitiveSiblings;
    }

    public void setResolveCaseInsensitiveSiblings(boolean resolveCaseInsensitiveSiblings) {
        this.resolveCaseInsensitiveSiblings = resolveCaseInsensitiveSiblings;
    }

    public DocumentMatchingMode getDocumentMatchingMode() {
        return documentMatchingMode;
    }

    public void setDocumentMatchingMode(DocumentMatchingMode documentMatchingMode) {
        this.documentMatchingMode = documentMatchingMode;
    }
}