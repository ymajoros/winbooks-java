package be.valuya.winbooks.api.extra;

import be.valuya.jbooks.model.WbAccount;
import be.valuya.jbooks.model.WbBookYearFull;
import be.valuya.jbooks.model.WbDocOrderType;
import be.valuya.jbooks.model.WbDocStatus;
import be.valuya.jbooks.model.WbEntry;
import be.valuya.jbooks.model.WbPeriod;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * @author Yannick
 */

public class WinbooksExtraServiceTest {

    public static final String TEST_BASE_NAME = "BD";

    private WinbooksExtraService winbooksExtraService;
    private WinbooksFileConfiguration winbooksFileConfiguration;
    private Path baseFolderPath;

    private Logger logger = Logger.getLogger(WinbooksExtraServiceTest.class.getName());

    @Before
    public void setup() {
        winbooksExtraService = new WinbooksExtraService();

        baseFolderPath = Paths.get("C:\\temp\\wbdata\\" + TEST_BASE_NAME);

        winbooksFileConfiguration = new WinbooksFileConfiguration();
        winbooksFileConfiguration.setBaseFolderPath(baseFolderPath);
        winbooksFileConfiguration.setBaseName(TEST_BASE_NAME);
    }

    @Test
    public void testGuessBaseName() {
        String baseName = winbooksExtraService.findBaseNameOptional(baseFolderPath)
                .orElse("?");
        Assert.assertEquals(TEST_BASE_NAME, baseName);
    }

    @Test
    public void testReadDBF() {
        winbooksExtraService.dumpDbf(winbooksFileConfiguration, "act");
        winbooksExtraService.dumpDbf(winbooksFileConfiguration, "acf");
        winbooksExtraService.dumpDbf(winbooksFileConfiguration, "csf");
    }

    @Test
    public void testStreamBookYears() {
        winbooksExtraService.streamBookYears(winbooksFileConfiguration)
                .forEach(this::printBookYear);
    }

    @Test
    public void testFindDistinctDocOrder() {
        winbooksExtraService.streamAct(winbooksFileConfiguration, this::logWinbooksEvent)
                .map(WbEntry::getWbDocOrderType)
                .distinct()
                .map(WbDocOrderType::name)
                .forEach(logger::info);
    }

    @Test
    public void testFindDistinctDocStatus() {
        winbooksExtraService.streamAct(winbooksFileConfiguration, this::logWinbooksEvent)
                .map(WbEntry::getDocStatus)
                .distinct()
                .map(WbDocStatus::name)
                .forEach(logger::info);
    }

    @Test
    public void testAccountTotal() {
        Date startDate = new Date(116, Calendar.JANUARY, 01);
        Date endDate = new Date(117, Calendar.JANUARY, 01);
        TreeMap<String, Map<Integer, BigDecimal>> categoryMonthTotalMap = winbooksExtraService.streamAct(winbooksFileConfiguration, this::logWinbooksEvent)
                .filter(wbEntry -> wbEntry.getDate() != null)
                .filter(wbEntry -> !wbEntry.getDate().before(startDate))
                .filter(wbEntry -> wbEntry.getDate().before(endDate))
//                .filter(wbEntry -> wbEntry.getComment() != null && wbEntry.getComment().equals("LOYER 20/06-19/07/2016"))
                .filter(wbEntry -> wbEntry.getAccountGl() != null)
                .filter(wbEntry -> wbEntry.getAccountGl().substring(0, 2).equals("70"))
                .peek(wbEntry -> logger.info(wbEntry.toString()))
                .collect(
                        Collectors.groupingBy(
                                wbEntry -> wbEntry.getAccountGl().substring(0, 2),
                                TreeMap::new,
                                Collectors.groupingBy(
                                        wbEntry -> wbEntry.getDate().getMonth(),
                                        Collectors.reducing(BigDecimal.ZERO, WbEntry::getAmountEur, BigDecimal::add))
                        )
                );

        categoryMonthTotalMap.forEach((accountNumber, monthTotalMap) -> {
            BigDecimal accountTotal = monthTotalMap.values().stream()
                    .collect(Collectors.reducing(BigDecimal::add))
                    .orElse(BigDecimal.ZERO);
            monthTotalMap.forEach((month, total) -> System.out.println("Account " + accountNumber + ", month " + month + ": " + total));
            System.out.println("Account " + accountNumber + ": " + accountTotal);
        });
    }

    @Test
    public void testGetAccountDescription() {
        String description = winbooksExtraService.streamAcf(winbooksFileConfiguration)
                .filter(wbAccount -> wbAccount.getAccountNumber() != null)
                .filter(wbAccount -> wbAccount.getAccountNumber().equals("700000"))
                .map(WbAccount::getName11)
                .findAny()
                .orElseThrow(IllegalArgumentException::new);

        System.out.println("Account: " + description);
    }

    private void printBookYear(WbBookYearFull wbBookYearFull) {
        System.out.println(wbBookYearFull);
        wbBookYearFull.getPeriodList()
                .stream()
                .map(WbPeriod::toString)
                .forEach(logger::info);
    }

    private void logWinbooksEvent(WinbooksEvent winbooksEvent) {
        WinbooksEventCategory winbooksEventCategory = winbooksEvent.getWinbooksEventCategory();
        String message = winbooksEvent.getMessage();
        List<Object> arguments = winbooksEvent.getArguments();
        WinbooksEventType winbooksEventType = winbooksEventCategory.getWinbooksEventType();

        Level level;
        switch (winbooksEventType) {
            case INFO:
                level = Level.INFO;
                break;
            case WARNING:
                level = Level.WARNING;
                break;
            case ERROR:
                level = Level.SEVERE;
                break;
            default:
                throw new AssertionError("Unknown winbooks event type: " + winbooksEventType);
        }

        Object[] argumentArray = arguments.toArray();
        logger.log(level, message, argumentArray);
    }
}
