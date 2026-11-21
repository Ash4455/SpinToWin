package com.spintowin.campaign.prize;

import com.jayway.jsonpath.JsonPath;
import com.spintowin.campaign.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /admin/campaigns/{id}/prizes}：ADMIN 設定 Prize 的名稱、Stock 與 Win Probability。 */
class AdminPrizeApiTest extends ApiTest {

    /** seed Campaign（ACTIVE）只用來驗證被拒絕的請求，不可被修改：CampaignApiTest 依賴它。 */
    private static final long SEED_CAMPAIGN_ID = 1L;

    private static final String SPRING_SALE = """
            {
              "name": "春節轉盤",
              "startAt": "2027-01-20T00:00:00Z",
              "endAt": "2027-02-10T23:59:59Z",
              "drawQuota": 3
            }
            """;

    @Test
    void adminAddsPrizeToDraftCampaign() {
        long campaignId = createDraftCampaign();

        var response = addPrize(campaignId, "藍牙耳機", 10, "1.5");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        long prizeId = idOf(response);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "id": %d,
                  "name": "藍牙耳機",
                  "winProbability": 1.5,
                  "totalStock": 10,
                  "remainingStock": 10
                }
                """.formatted(prizeId));
        assertThat(response.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/admin/campaigns/" + campaignId + "/prizes/" + prizeId);
    }

    @Test
    void adminCampaignShowsPrizesInOrderAndNoWinRemainder() {
        long campaignId = createDraftCampaign();
        addPrize(campaignId, "藍牙耳機", 10, "0.0001");
        addPrize(campaignId, "免運券", 1000, "30");

        var response = getCampaign(campaignId);

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "id": %d,
                  "prizes": [
                    { "name": "藍牙耳機", "winProbability": 0.0001, "totalStock": 10,   "remainingStock": 10 },
                    { "name": "免運券",   "winProbability": 30.0,   "totalStock": 1000, "remainingStock": 1000 }
                  ],
                  "noWinProbability": 69.9999
                }
                """.formatted(campaignId));
        assertThat(response).bodyJson().extractingPath("$.prizes[*].name").asArray()
                .containsExactly("藍牙耳機", "免運券");
    }

    @Test
    void prizesMayFillExactlyOneHundredPercent() {
        long campaignId = createDraftCampaign();
        addPrize(campaignId, "A", 1, "60");

        var response = addPrize(campaignId, "B", 1, "40");

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(getCampaign(campaignId)).bodyJson().extractingPath("$.noWinProbability").isEqualTo(0.0);
    }

    @Test
    void addingPrizeThatPushesTotalOverOneHundredPercentIsRejected() {
        long campaignId = createDraftCampaign();
        addPrize(campaignId, "A", 1, "60");

        var response = addPrize(campaignId, "B", 1, "40.0001");

        assertProblem(response, HttpStatus.CONFLICT, "PROBABILITY_SUM_EXCEEDED",
                "/admin/campaigns/" + campaignId + "/prizes");
        assertThat(getCampaign(campaignId)).bodyJson().extractingPath("$.prizes[*].name").asArray()
                .containsExactly("A");
    }

    @Test
    void winProbabilityWithMoreThanFourDecimalsIsFieldError() {
        long campaignId = createDraftCampaign();

        var response = addPrize(campaignId, "A", 1, "0.00001");

        assertValidationProblemOn(response, "winProbability", "/admin/campaigns/" + campaignId + "/prizes");
    }

    @Test
    void winProbabilityOutOfRangeIsFieldError() {
        long campaignId = createDraftCampaign();

        assertValidationProblemOn(addPrize(campaignId, "A", 1, "-1"), "winProbability",
                "/admin/campaigns/" + campaignId + "/prizes");
        assertValidationProblemOn(addPrize(campaignId, "A", 1, "100.0001"), "winProbability",
                "/admin/campaigns/" + campaignId + "/prizes");
    }

    @Test
    void negativeStockIsFieldError() {
        long campaignId = createDraftCampaign();

        assertValidationProblemOn(addPrize(campaignId, "A", -1, "1"), "stock",
                "/admin/campaigns/" + campaignId + "/prizes");
    }

    @Test
    void missingPrizeFieldsAreReportedPerField() {
        long campaignId = createDraftCampaign();

        var response = addPrize(campaignId, "{}");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors[*].field").asArray()
                .contains("name", "stock", "winProbability");
    }

    @Test
    void addingPrizeToUnknownCampaignIsNotFoundProblem() {
        var response = addPrize(999_999, "A", 1, "1");

        assertProblem(response, HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND", "/admin/campaigns/999999/prizes");
    }

    @Test
    void addingPrizeToActiveCampaignIsNotEditable() {
        var response = addPrize(SEED_CAMPAIGN_ID, "A", 1, "1");

        assertProblem(response, HttpStatus.CONFLICT, "CAMPAIGN_NOT_EDITABLE", "/admin/campaigns/1/prizes");
    }

    // ---- PUT：修改名稱與 Win Probability ----

    @Test
    void adminEditsPrizeNameAndProbabilityKeepingStock() {
        long campaignId = createDraftCampaign();
        long prizeId = idOf(addPrize(campaignId, "藍牙耳機", 10, "1"));

        var response = updatePrize(campaignId, prizeId, "無線耳機", "2.25");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "id": %d, "name": "無線耳機", "winProbability": 2.25, "totalStock": 10, "remainingStock": 10 }
                """.formatted(prizeId));
        assertThat(getCampaign(campaignId)).bodyJson().extractingPath("$.noWinProbability").isEqualTo(97.75);
    }

    @Test
    void adminEditsPrizeWhileCampaignIsActive() {
        long campaignId = createDraftCampaign();
        long prizeId = idOf(addPrize(campaignId, "藍牙耳機", 10, "1"));
        activate(campaignId);

        var response = updatePrize(campaignId, prizeId, "無線耳機", "5");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "name": "無線耳機", "winProbability": 5.0 }
                """);
    }

    @Test
    void editIsCheckedAgainstOtherPrizesOnly() {
        long campaignId = createDraftCampaign();
        addPrize(campaignId, "A", 1, "60");
        long b = idOf(addPrize(campaignId, "B", 1, "30"));

        assertThat(updatePrize(campaignId, b, "B", "40")).hasStatusOk();

        var response = updatePrize(campaignId, b, "B", "40.0001");

        assertProblem(response, HttpStatus.CONFLICT, "PROBABILITY_SUM_EXCEEDED",
                "/admin/campaigns/" + campaignId + "/prizes/" + b);
        assertThat(getCampaign(campaignId)).bodyJson().extractingPath("$.noWinProbability").isEqualTo(0.0);
    }

    @Test
    void editingUnknownPrizeIsNotFoundProblem() {
        long campaignId = createDraftCampaign();

        var response = updatePrize(campaignId, 999_999, "A", "1");

        assertProblem(response, HttpStatus.NOT_FOUND, "PRIZE_NOT_FOUND",
                "/admin/campaigns/" + campaignId + "/prizes/999999");
    }

    @Test
    void prizeOfAnotherCampaignIsNotFound() {
        long campaignId = createDraftCampaign();
        long otherCampaignId = createDraftCampaign();
        long otherPrizeId = idOf(addPrize(otherCampaignId, "A", 1, "1"));

        var response = updatePrize(campaignId, otherPrizeId, "B", "2");

        assertProblem(response, HttpStatus.NOT_FOUND, "PRIZE_NOT_FOUND",
                "/admin/campaigns/" + campaignId + "/prizes/" + otherPrizeId);
    }

    @Test
    void editingPrizeOfEndedCampaignIsNotEditable() {
        long campaignId = createDraftCampaign();
        long prizeId = idOf(addPrize(campaignId, "A", 1, "1"));
        end(campaignId);

        var response = updatePrize(campaignId, prizeId, "B", "2");

        assertProblem(response, HttpStatus.CONFLICT, "CAMPAIGN_NOT_EDITABLE",
                "/admin/campaigns/" + campaignId + "/prizes/" + prizeId);
    }

    @Test
    void invalidEditIsFieldError() {
        long campaignId = createDraftCampaign();
        long prizeId = idOf(addPrize(campaignId, "A", 1, "1"));

        var response = updatePrize(campaignId, prizeId, "A", "1.23456");

        assertValidationProblemOn(response, "winProbability",
                "/admin/campaigns/" + campaignId + "/prizes/" + prizeId);
    }

    // ---- DELETE：只限 DRAFT ----

    @Test
    void adminDeletesPrizeFromDraftCampaign() {
        long campaignId = createDraftCampaign();
        long a = idOf(addPrize(campaignId, "A", 1, "10"));
        addPrize(campaignId, "B", 1, "20");

        var response = deletePrize(campaignId, a);

        assertThat(response).hasStatus(HttpStatus.NO_CONTENT);
        var campaign = getCampaign(campaignId);
        assertThat(campaign).bodyJson().extractingPath("$.prizes[*].name").asArray().containsExactly("B");
        assertThat(campaign).bodyJson().extractingPath("$.noWinProbability").isEqualTo(80.0);
    }

    @Test
    void deletingPrizeOfActiveCampaignIsNotEditable() {
        var response = deletePrize(SEED_CAMPAIGN_ID, 1);

        assertProblem(response, HttpStatus.CONFLICT, "CAMPAIGN_NOT_EDITABLE", "/admin/campaigns/1/prizes/1");
        assertThat(getCampaign(SEED_CAMPAIGN_ID)).bodyJson().extractingPath("$.prizes[*].id").asArray()
                .containsExactly(1, 2, 3);
    }

    @Test
    void deletingUnknownPrizeIsNotFoundProblem() {
        long campaignId = createDraftCampaign();

        var response = deletePrize(campaignId, 999_999);

        assertProblem(response, HttpStatus.NOT_FOUND, "PRIZE_NOT_FOUND",
                "/admin/campaigns/" + campaignId + "/prizes/999999");
    }

    // ---- PATCH stock：以增減量調整 ----

    @Test
    void adminIncreasesStockByDelta() {
        long campaignId = createDraftCampaign();
        long prizeId = idOf(addPrize(campaignId, "A", 10, "1"));

        var response = adjustStock(campaignId, prizeId, "50");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "id": %d, "totalStock": 60, "remainingStock": 60 }
                """.formatted(prizeId));
    }

    @Test
    void adminDecreasesStockByDeltaDownToZero() {
        long campaignId = createDraftCampaign();
        long prizeId = idOf(addPrize(campaignId, "A", 10, "1"));

        assertThat(adjustStock(campaignId, prizeId, "-4")).bodyJson().isLenientlyEqualTo("""
                { "totalStock": 6, "remainingStock": 6 }
                """);
        assertThat(adjustStock(campaignId, prizeId, "-6")).bodyJson().isLenientlyEqualTo("""
                { "totalStock": 0, "remainingStock": 0 }
                """);
    }

    @Test
    void stockDeltaBelowZeroIsStockNegativeAndLeavesStockUnchanged() {
        long campaignId = createDraftCampaign();
        long prizeId = idOf(addPrize(campaignId, "A", 10, "1"));

        var response = adjustStock(campaignId, prizeId, "-11");

        assertProblem(response, HttpStatus.CONFLICT, "STOCK_NEGATIVE",
                "/admin/campaigns/" + campaignId + "/prizes/" + prizeId + "/stock");
        assertThat(getCampaign(campaignId)).bodyJson().extractingPath("$.prizes[0].remainingStock").isEqualTo(10);
    }

    @Test
    void adminAdjustsStockWhileCampaignIsActive() {
        long campaignId = createDraftCampaign();
        long prizeId = idOf(addPrize(campaignId, "A", 10, "1"));
        activate(campaignId);

        var response = adjustStock(campaignId, prizeId, "-3");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.remainingStock").isEqualTo(7);
    }

    @Test
    void adjustingStockOfUnknownPrizeIsNotFoundProblem() {
        long campaignId = createDraftCampaign();

        var response = adjustStock(campaignId, 999_999, "1");

        assertProblem(response, HttpStatus.NOT_FOUND, "PRIZE_NOT_FOUND",
                "/admin/campaigns/" + campaignId + "/prizes/999999/stock");
    }

    @Test
    void adjustingStockOfEndedCampaignIsNotEditable() {
        long campaignId = createDraftCampaign();
        long prizeId = idOf(addPrize(campaignId, "A", 10, "1"));
        end(campaignId);

        var response = adjustStock(campaignId, prizeId, "1");

        assertProblem(response, HttpStatus.CONFLICT, "CAMPAIGN_NOT_EDITABLE",
                "/admin/campaigns/" + campaignId + "/prizes/" + prizeId + "/stock");
    }

    @Test
    void missingDeltaIsFieldError() {
        long campaignId = createDraftCampaign();
        long prizeId = idOf(addPrize(campaignId, "A", 10, "1"));

        var response = mvc.patch().uri("/admin/campaigns/{id}/prizes/{pid}/stock", campaignId, prizeId)
                .with(admin()).contentType(MediaType.APPLICATION_JSON).content("{}").exchange();

        assertValidationProblemOn(response, "delta",
                "/admin/campaigns/" + campaignId + "/prizes/" + prizeId + "/stock");
    }

    @Test
    void userIsForbiddenOnEveryPrizeApi() {
        String body = """
                { "name": "A", "stock": 1, "winProbability": 1, "delta": 1 }
                """;
        assertForbidden(mvc.post().uri("/admin/campaigns/1/prizes").with(user())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange(), "/admin/campaigns/1/prizes");
        assertForbidden(mvc.put().uri("/admin/campaigns/1/prizes/1").with(user())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange(), "/admin/campaigns/1/prizes/1");
        assertForbidden(mvc.delete().uri("/admin/campaigns/1/prizes/1").with(user()).exchange(),
                "/admin/campaigns/1/prizes/1");
        assertForbidden(mvc.patch().uri("/admin/campaigns/1/prizes/1/stock").with(user())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange(),
                "/admin/campaigns/1/prizes/1/stock");
        assertThat(getCampaign(SEED_CAMPAIGN_ID)).bodyJson().isLenientlyEqualTo("""
                { "prizes": [ { "id": 1, "name": "藍牙耳機", "winProbability": 1.0, "remainingStock": 10 },
                              { "id": 2 }, { "id": 3 } ] }
                """);
    }

    // ---- helpers ----

    private void assertForbidden(MvcTestResult response, String instance) {
        assertProblem(response, HttpStatus.FORBIDDEN, "FORBIDDEN", instance);
    }

    private MvcTestResult deletePrize(long campaignId, long prizeId) {
        return mvc.delete().uri("/admin/campaigns/{id}/prizes/{pid}", campaignId, prizeId).with(admin()).exchange();
    }

    private MvcTestResult adjustStock(long campaignId, long prizeId, String delta) {
        return mvc.patch().uri("/admin/campaigns/{id}/prizes/{pid}/stock", campaignId, prizeId).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "delta": %s }
                        """.formatted(delta))
                .exchange();
    }

    private MvcTestResult updatePrize(long campaignId, long prizeId, String name, String winProbability) {
        return mvc.put().uri("/admin/campaigns/{id}/prizes/{pid}", campaignId, prizeId).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "name": "%s", "winProbability": %s }
                        """.formatted(name, winProbability))
                .exchange();
    }

    private void activate(long campaignId) {
        assertThat(mvc.post().uri("/admin/campaigns/{id}/activate", campaignId).with(admin()).exchange())
                .hasStatusOk();
    }

    private void end(long campaignId) {
        assertThat(mvc.post().uri("/admin/campaigns/{id}/end", campaignId).with(admin()).exchange())
                .hasStatusOk();
    }

    private void assertProblem(MvcTestResult response, HttpStatus status, String code, String instance) {
        assertThat(response).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "status": %d, "code": "%s", "instance": "%s" }
                """.formatted(status.value(), code, instance));
    }

    private void assertValidationProblemOn(MvcTestResult response, String field, String instance) {
        assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", instance);
        assertThat(response).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly(field);
    }

    private long createDraftCampaign() {
        return idOf(mvc.post().uri("/admin/campaigns").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(SPRING_SALE)
                .exchange());
    }

    private MvcTestResult getCampaign(long campaignId) {
        return mvc.get().uri("/admin/campaigns/{id}", campaignId).with(admin()).exchange();
    }

    private MvcTestResult addPrize(long campaignId, String name, int stock, String winProbability) {
        return addPrize(campaignId, """
                { "name": "%s", "stock": %d, "winProbability": %s }
                """.formatted(name, stock, winProbability));
    }

    private MvcTestResult addPrize(long campaignId, String body) {
        return mvc.post().uri("/admin/campaigns/{id}/prizes", campaignId).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .exchange();
    }

    private static long idOf(MvcTestResult response) {
        try {
            Number id = JsonPath.read(response.getResponse().getContentAsString(), "$.id");
            return id.longValue();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
