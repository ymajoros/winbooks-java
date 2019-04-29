package be.valuya.winbooks.api.accountingtroll;

import be.valuya.accountingtroll.AccountingEventListener;
import be.valuya.winbooks.api.LocalWinbooksDossierCategory;
import be.valuya.winbooks.api.extra.WinbooksExtraService;
import be.valuya.winbooks.api.extra.config.WinbooksFileConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.nio.file.Path;
import java.nio.file.Paths;

@RunWith(JUnit4.class)
@Category(LocalWinbooksDossierCategory.class)
public class WinbooksTrollAccountingManagerLocalTest {


    private WinbooksTrollAccountingManager trollSrervice;
    private AccountingEventListener eventListener;

    @Before
    public void setup() {
        WinbooksExtraService extraService = new WinbooksExtraService();

        String baseFolderLocation = System.getProperty("winbooks.test.folder");
        String baseName = System.getProperty("winbooks.test.base.name");

        Path baseFolderPath = Paths.get(baseFolderLocation)
                .resolve(baseName);
        WinbooksFileConfiguration winbooksFileConfiguration = extraService.createWinbooksFileConfigurationOptional(baseFolderPath, baseName)
                .orElseThrow(AssertionError::new);
        winbooksFileConfiguration.setReadTablesToMemory(true);

        trollSrervice = new WinbooksTrollAccountingManager(winbooksFileConfiguration);
        eventListener = new TestAccountingEventListener();

//        eventHandler = winbooksEvent -> logger.info(winbooksEvent.getMessage());
    }


    @Test
    public void testStreamAccounts() {
        trollSrervice.streamAccounts()
                .forEach(this::debug);
    }

    @Test
    public void testStreamBookYears() {
        trollSrervice.streamBookYears()
                .forEach(this::debug);
    }

    @Test
    public void testStreamPeriods() {
        trollSrervice.streamPeriods()
                .forEach(this::debug);
    }

    @Test
    public void testStreamThirdParties() {
        trollSrervice.streamThirdParties()
                .forEach(this::debug);
    }

    @Test
    public void testStreamEntries() {
        trollSrervice.streamAccountingEntries(eventListener)
                .forEach(this::debug);
    }

    private void debug(Object valueObject) {
        System.out.println(valueObject);
    }

}