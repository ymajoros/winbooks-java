package be.valuya.jbooks.model;

import java.time.LocalDateTime;
import java.util.Objects;

public class WbDocument {

    private String documentNumber;
    private String dbkCode;
    private WbPeriod wbPeriod;
    private int partCount;
    private LocalDateTime creationTime;
    private LocalDateTime updatedTime;
    // Filename with a {0} placeholder for the page number
    private String fileNameTemplate;
    private String absolutePathName;

    public String getDocumentNumber() {
        return documentNumber;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }

    public String getDbkCode() {
        return dbkCode;
    }

    public void setDbkCode(String dbkCode) {
        this.dbkCode = dbkCode;
    }

    public WbPeriod getWbPeriod() {
        return wbPeriod;
    }

    public void setWbPeriod(WbPeriod wbPeriod) {
        this.wbPeriod = wbPeriod;
    }

    public int getPartCount() {
        return partCount;
    }

    public void setPartCount(int partCount) {
        this.partCount = partCount;
    }

    public LocalDateTime getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(LocalDateTime creationTime) {
        this.creationTime = creationTime;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime updatedTime) {
        this.updatedTime = updatedTime;
    }

    public String getFileNameTemplate() {
        return fileNameTemplate;
    }

    public void setFileNameTemplate(String fileNameTemplate) {
        this.fileNameTemplate = fileNameTemplate;
    }

    public String getAbsolutePathName() {
        return absolutePathName;
    }

    public void setAbsolutePathName(String absolutePathName) {
        this.absolutePathName = absolutePathName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WbDocument that = (WbDocument) o;
        return Objects.equals(documentNumber, that.documentNumber) &&
                Objects.equals(dbkCode, that.dbkCode) &&
                Objects.equals(wbPeriod, that.wbPeriod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentNumber, dbkCode, wbPeriod);
    }
}
