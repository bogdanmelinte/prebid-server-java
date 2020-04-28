package org.prebid.server.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.netty.util.AsciiString;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.prebid.server.VertxTest;
import org.prebid.server.analytics.AnalyticsReporter;
import org.prebid.server.analytics.model.CookieSyncEvent;
import org.prebid.server.auction.PrivacyEnforcementService;
import org.prebid.server.bidder.BidderCatalog;
import org.prebid.server.bidder.Usersyncer;
import org.prebid.server.cookie.UidsCookie;
import org.prebid.server.cookie.UidsCookieService;
import org.prebid.server.cookie.model.UidWithExpiry;
import org.prebid.server.cookie.proto.Uids;
import org.prebid.server.execution.TimeoutFactory;
import org.prebid.server.metric.Metrics;
import org.prebid.server.privacy.gdpr.TcfDefinerService;
import org.prebid.server.privacy.gdpr.model.PrivacyEnforcementAction;
import org.prebid.server.privacy.gdpr.model.TcfResponse;
import org.prebid.server.proto.request.CookieSyncRequest;
import org.prebid.server.proto.response.BidderInfo;
import org.prebid.server.proto.response.BidderUsersyncStatus;
import org.prebid.server.proto.response.CookieSyncResponse;
import org.prebid.server.proto.response.UsersyncInfo;
import org.prebid.server.rubicon.audit.UidsAuditCookieService;
import org.prebid.server.rubicon.audit.proto.UidAudit;
import org.prebid.server.settings.ApplicationSettings;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.function.Function.identity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anySet;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

public class CookieSyncHandlerTest extends VertxTest {

    private static final String RUBICON = "rubicon";
    private static final String APPNEXUS = "appnexus";
    private static final String APPNEXUS_COOKIE = "adnxs";
    private static final String PUBMATIC = "pubmatic";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    private final TimeoutFactory timeoutFactory =
            new TimeoutFactory(Clock.fixed(Instant.now(), ZoneId.systemDefault()));

    @Mock
    private UidsCookieService uidsCookieService;
    @Mock
    private UidsAuditCookieService uidsAuditCookieService;
    @Mock
    private ApplicationSettings applicationSettings;
    @Mock
    private BidderCatalog bidderCatalog;
    @Mock
    private TcfDefinerService tcfDefinerService;
    @Mock
    private PrivacyEnforcementService privacyEnforcementService;
    @Mock
    private AnalyticsReporter analyticsReporter;
    @Mock
    private Metrics metrics;

    private CookieSyncHandler cookieSyncHandler;
    @Mock
    private RoutingContext routingContext;
    @Mock
    private HttpServerResponse httpResponse;

    private Usersyncer rubiconUsersyncer;

    private Usersyncer appnexusUsersyncer;

    @Before
    public void setUp() {
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build(), jacksonMapper));

        given(uidsAuditCookieService.getUidsAudit(any(RoutingContext.class))).willReturn(UidAudit.builder().build());

        given(applicationSettings.getAccountById(any(), any())).willReturn(Future.failedFuture("not found"));

        given(routingContext.response()).willReturn(httpResponse);
        given(httpResponse.putHeader(any(CharSequence.class), any(CharSequence.class))).willReturn(httpResponse);
        cookieSyncHandler = new CookieSyncHandler(true, uidsAuditCookieService, "http://external-url", 2000,
                uidsCookieService, applicationSettings, bidderCatalog, tcfDefinerService, privacyEnforcementService, 1,
                false, false, emptyList(), analyticsReporter, metrics, timeoutFactory, jacksonMapper);
    }

    @Test
    public void shouldRespondWithEmptyBodyAndNoContentStatusIfCookiesDisables() {
        // given
        final Clock clock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
        final TimeoutFactory timeoutFactory = new TimeoutFactory(clock);
        cookieSyncHandler = new CookieSyncHandler(false, uidsAuditCookieService, "http://external-url", 2000,
                uidsCookieService, applicationSettings, bidderCatalog, tcfDefinerService, privacyEnforcementService, 1,
                false, false, emptyList(), analyticsReporter, metrics, timeoutFactory, jacksonMapper);
        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(204);
        verify(httpResponse).end();
    }

    @Test
    public void shouldRespondWithErrorIfOptedOut() {
        // given
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).optout(true).build(), jacksonMapper));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);
        given(httpResponse.setStatusMessage(anyString())).willReturn(httpResponse);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(401));
        verify(httpResponse).setStatusMessage(eq("User has opted out"));
        verify(httpResponse).end();
        verify(routingContext, never()).getBody();
        verifyNoMoreInteractions(httpResponse, tcfDefinerService);
    }

    @Test
    public void shouldRespondWithErrorIfRequestBodyIsMissing() {
        // given
        given(routingContext.getBody()).willReturn(null);

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);
        given(httpResponse.setStatusMessage(anyString())).willReturn(httpResponse);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).setStatusMessage(eq("Request has no body"));
        verify(httpResponse).end();
        verifyNoMoreInteractions(httpResponse, tcfDefinerService);
    }

    @Test
    public void shouldRespondWithErrorIfRequestBodyCouldNotBeParsed() {
        // given
        given(routingContext.getBody()).willReturn(Buffer.buffer("{"));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);
        given(httpResponse.setStatusMessage(anyString())).willReturn(httpResponse);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).setStatusMessage(eq("Request body cannot be parsed"));
        verify(httpResponse).end();
        verifyNoMoreInteractions(httpResponse, tcfDefinerService);
    }

    @Test
    public void shouldRespondWithErrorIfGdprConsentIsMissing() {
        // given
        given(routingContext.getBody())
                .willReturn(
                        givenRequestBody(CookieSyncRequest.of(emptyList(), 1, null, null, null, null, null)));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);
        given(httpResponse.setStatusMessage(anyString())).willReturn(httpResponse);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).setStatusMessage(eq("gdpr_consent is required if gdpr is 1"));
        verify(httpResponse).end();
        verifyNoMoreInteractions(httpResponse, tcfDefinerService);
    }

    @Test
    public void shouldNotSendResponseIfClientClosedConnection() {
        // given
        given(routingContext.getBody()).willReturn(
                givenRequestBody(CookieSyncRequest.of(emptyList(), null, null, null, null, null, null)));

        givenTcfServiceReturningVendorIdResult(emptySet());

        given(routingContext.response().closed()).willReturn(true);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(httpResponse, never()).end();
    }

    @Test
    public void shouldRespondWithExpectedHeaders() {
        // given
        given(routingContext.getBody()).willReturn(
                givenRequestBody(CookieSyncRequest.of(emptyList(), null, null, null, null, null, null)));

        givenTcfServiceReturningVendorIdResult(emptySet());

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(httpResponse)
                .putHeader(eq(new AsciiString("Content-Type")), eq(new AsciiString("application/json")));
    }

    @Test
    public void shouldRespondWithSomeBidderStatusesIfSomeUidsMissingInCookies() throws IOException {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build(),
                jacksonMapper));

        given(routingContext.getBody())
                .willReturn(givenRequestBody(
                        CookieSyncRequest.of(asList(RUBICON, APPNEXUS), null, null, null, null, null, null)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);

        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://adnxsexample.com", null, null, "redirect", false);
        rubiconUsersyncer = new Usersyncer(RUBICON, "", null, null, "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("ok",
                singletonList(BidderUsersyncStatus.builder()
                        .bidder(APPNEXUS)
                        .noCookie(true)
                        .usersync(UsersyncInfo.of("http://adnxsexample.com", "redirect", false))
                        .build())));
    }

    @Test
    public void shouldRespondWithAllActiveBiddersWhenRequestCoopSyncTrueAndNoPriorityConfigured() throws IOException {
        // given
        final String disabledBidder = "disabled_bidder";
        given(bidderCatalog.names()).willReturn(new HashSet<>(asList(APPNEXUS, RUBICON, disabledBidder)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);
        given(bidderCatalog.isActive(disabledBidder)).willReturn(false);

        cookieSyncHandler = new CookieSyncHandler(true, uidsAuditCookieService, "http://external-url", 2000,
                uidsCookieService, applicationSettings, bidderCatalog, tcfDefinerService, privacyEnforcementService, 1,
                false, false, emptyList(), analyticsReporter, metrics, timeoutFactory, jacksonMapper);

        given(routingContext.getBody()).willReturn(
                givenRequestBody(CookieSyncRequest.of(singletonList(APPNEXUS), null, null, null, true, null, null)));

        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://adnxsexample.com", null, null, "redirect", false);
        rubiconUsersyncer = new Usersyncer(RUBICON, "http://rubiconexample.com", null, null, "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("no_cookie", asList(
                BidderUsersyncStatus.builder().bidder(RUBICON).noCookie(true).usersync(
                        UsersyncInfo.of("http://rubiconexample.com", "redirect", false)).build(),
                BidderUsersyncStatus.builder().bidder(APPNEXUS).noCookie(true).usersync(
                        UsersyncInfo.of("http://adnxsexample.com", "redirect", false)).build())));
    }

    @Test
    public void shouldRespondWithCoopBiddersWhenRequestCoopSyncTrue() throws IOException {
        // given
        given(bidderCatalog.isActive(anyString())).willReturn(true);

        final String disabledBidder = "disabled_bidder";
        given(bidderCatalog.isActive(disabledBidder)).willReturn(false);

        final List<Collection<String>> coopBidders = asList(singletonList(RUBICON), singletonList(disabledBidder));

        cookieSyncHandler = new CookieSyncHandler(true, uidsAuditCookieService, "http://external-url", 2000,
                uidsCookieService, applicationSettings, bidderCatalog, tcfDefinerService, privacyEnforcementService, 1,
                false, false, coopBidders, analyticsReporter, metrics, timeoutFactory, jacksonMapper);

        given(routingContext.getBody()).willReturn(
                givenRequestBody(CookieSyncRequest.of(singletonList(APPNEXUS), null, null, null, true, null, null)));

        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://adnxsexample.com", null, null, "redirect", false);
        rubiconUsersyncer = new Usersyncer(RUBICON, "http://rubiconexample.com", null, null, "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("no_cookie", asList(
                BidderUsersyncStatus.builder().bidder(RUBICON).noCookie(true).usersync(
                        UsersyncInfo.of("http://rubiconexample.com", "redirect", false)).build(),
                BidderUsersyncStatus.builder().bidder(disabledBidder).error("Unsupported bidder").build(),
                BidderUsersyncStatus.builder().bidder(APPNEXUS).noCookie(true).usersync(
                        UsersyncInfo.of("http://adnxsexample.com", "redirect", false)).build())));
    }

    @Test
    public void shouldRespondWithPrioritisedCoopBidderWhenRequestCoopDefaultTrueAndLimitIsLessThanCoopSize()
            throws IOException {
        // given
        given(bidderCatalog.isActive(anyString())).willReturn(true);

        final List<Collection<String>> priorityBidders = asList(singletonList(APPNEXUS), singletonList(RUBICON),
                asList("bidder1", "bidder2"), singletonList("spam"));

        cookieSyncHandler = new CookieSyncHandler(true, uidsAuditCookieService, "http://external-url", 2000,
                uidsCookieService, applicationSettings, bidderCatalog, tcfDefinerService, privacyEnforcementService, 1,
                false, true, priorityBidders, analyticsReporter, metrics, timeoutFactory, jacksonMapper);

        given(routingContext.getBody())
                .willReturn(
                        givenRequestBody(
                                CookieSyncRequest.of(singletonList(APPNEXUS), null, null, null, null, 2, null)));

        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://adnxsexample.com", null, null, "redirect", false);
        rubiconUsersyncer = new Usersyncer(RUBICON, "http://rubiconexample.com", null, null, "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("no_cookie", asList(
                BidderUsersyncStatus.builder().bidder(RUBICON).noCookie(true).usersync(
                        UsersyncInfo.of("http://rubiconexample.com", "redirect", false)).build(),
                BidderUsersyncStatus.builder().bidder(APPNEXUS).noCookie(true).usersync(
                        UsersyncInfo.of("http://adnxsexample.com", "redirect", false)).build())));
    }

    @Test
    public void shouldRespondWithBidderStatusForAllBiddersIfBiddersListOmittedInRequest() throws IOException {
        // given
        given(routingContext.getBody())
                .willReturn(
                        givenRequestBody(CookieSyncRequest.of(null, null, null, null, null, null, null)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);

        final String disabledBidder = "disabled_bidder";
        given(bidderCatalog.isActive(disabledBidder)).willReturn(false);

        given(bidderCatalog.names()).willReturn(new HashSet<>(asList(APPNEXUS, RUBICON, disabledBidder)));

        cookieSyncHandler = new CookieSyncHandler(true, uidsAuditCookieService, "http://external-url", 2000,
                uidsCookieService, applicationSettings, bidderCatalog, tcfDefinerService, privacyEnforcementService, 1,
                false, false, emptyList(), analyticsReporter, metrics, timeoutFactory, jacksonMapper);

        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://adnxsexample.com", null, null, "redirect", false);
        rubiconUsersyncer = new Usersyncer(RUBICON, "http://rubiconexample.com", null, null, "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("no_cookie", asList(
                BidderUsersyncStatus.builder().bidder(RUBICON).noCookie(true).usersync(
                        UsersyncInfo.of("http://rubiconexample.com", "redirect", false)).build(),
                BidderUsersyncStatus.builder().bidder(APPNEXUS).noCookie(true).usersync(
                        UsersyncInfo.of("http://adnxsexample.com", "redirect", false)).build())));
    }

    @Test
    public void shouldRespondWithNoBidderStatusesIfAllUidsPresentInCookies() throws IOException {
        // given
        final Map<String, UidWithExpiry> uids = new HashMap<>();
        uids.put(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"));
        uids.put(APPNEXUS_COOKIE, UidWithExpiry.live("12345"));
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(uids).build(), jacksonMapper));

        given(bidderCatalog.isActive(anyString())).willReturn(true);

        given(routingContext.getBody())
                .willReturn(givenRequestBody(
                        CookieSyncRequest.of(asList(RUBICON, APPNEXUS), null, null, null, null, null, null)));

        rubiconUsersyncer = new Usersyncer(RUBICON, "", null, null, null, false);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "", null, null, null, false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("ok", emptyList()));
    }

    @Test
    public void shouldTolerateUnsupportedBidder() throws IOException {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build(),
                jacksonMapper));

        given(routingContext.getBody())
                .willReturn(givenRequestBody(
                        CookieSyncRequest.of(asList(RUBICON, "unsupported"), null, null, null, null, null, null)));

        rubiconUsersyncer = new Usersyncer(RUBICON, "", null, null, null, false);
        givenUsersyncersReturningFamilyName();

        given(bidderCatalog.isActive(RUBICON)).willReturn(true);
        given(bidderCatalog.isValidName("unsupported")).willReturn(false);

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(RUBICON));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getStatus()).isEqualTo("ok");
        assertThat(cookieSyncResponse.getBidderStatus()).hasSize(1)
                .extracting(BidderUsersyncStatus::getBidder, BidderUsersyncStatus::getError)
                .containsOnly(tuple("unsupported", "Unsupported bidder"));
    }

    @Test
    public void shouldTolerateDisabledBidder() throws IOException {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build(),
                jacksonMapper));

        given(routingContext.getBody())
                .willReturn(givenRequestBody(
                        CookieSyncRequest.of(asList(RUBICON, "disabled"), null, null, null, null, null, null)));

        rubiconUsersyncer = new Usersyncer(RUBICON, "", null, null, null, false);
        givenUsersyncersReturningFamilyName();

        given(bidderCatalog.isValidName(RUBICON)).willReturn(true);
        given(bidderCatalog.isValidName("disabled")).willReturn(true);

        given(bidderCatalog.isActive(RUBICON)).willReturn(true);
        given(bidderCatalog.isActive("disabled")).willReturn(false);

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(RUBICON));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getStatus()).isEqualTo("ok");
        assertThat(cookieSyncResponse.getBidderStatus()).hasSize(1)
                .extracting(BidderUsersyncStatus::getBidder, BidderUsersyncStatus::getError)
                .containsOnly(tuple("disabled",
                        "disabled is not configured properly on this Prebid Server deploy. "
                                + "If you believe this should work, contact the company hosting the service and tell "
                                + "them to check their configuration."));
    }

    @Test
    public void shouldTolerateRejectedBidderByTcf() throws IOException {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build(),
                jacksonMapper));

        given(routingContext.getBody())
                .willReturn(givenRequestBody(
                        CookieSyncRequest.of(asList(RUBICON, APPNEXUS), null, null, null, null, null, null)));

        rubiconUsersyncer = new Usersyncer(RUBICON, "", null, null, null, false);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "", null, null, null, false);
        givenUsersyncersReturningFamilyName();

        given(bidderCatalog.isActive(RUBICON)).willReturn(true);
        given(bidderCatalog.isActive(APPNEXUS)).willReturn(true);

        given(bidderCatalog.bidderInfoByName(APPNEXUS))
                .willReturn(BidderInfo.create(true, null, null,
                        null, null, 2, true, false));

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(RUBICON));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getStatus()).isEqualTo("ok");
        assertThat(cookieSyncResponse.getBidderStatus()).hasSize(1)
                .extracting(BidderUsersyncStatus::getBidder, BidderUsersyncStatus::getError)
                .containsOnly(tuple(APPNEXUS, "Rejected by TCF"));
    }

    @Test
    public void shouldUpdateCookieSyncSetAndRejectByTcfMetricForEachRejectedAndSyncedBidder() {
        // given
        given(routingContext.getBody())
                .willReturn(givenRequestBody(
                        CookieSyncRequest.of(asList(RUBICON, APPNEXUS), null, null, null, null, null, null)));

        rubiconUsersyncer = new Usersyncer(RUBICON, "", null, null, null, false);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "", null, null, null, false);
        givenUsersyncersReturningFamilyName();

        given(bidderCatalog.isActive(RUBICON)).willReturn(true);
        given(bidderCatalog.isActive(APPNEXUS)).willReturn(true);

        given(bidderCatalog.bidderInfoByName(APPNEXUS))
                .willReturn(BidderInfo.create(true, null, null,
                        null, null, 2, true, false));

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(RUBICON));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(metrics).updateCookieSyncTcfBlockedMetric(APPNEXUS);
        verify(metrics).updateCookieSyncGenMetric(RUBICON);
    }

    @Test
    public void shouldUpdateCookieSyncMatchesMetricForEachAlreadySyncedBidder() {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build(),
                jacksonMapper));

        given(routingContext.getBody())
                .willReturn(givenRequestBody(
                        CookieSyncRequest.of(asList(RUBICON, APPNEXUS), null, null, null, null, null, null)));

        rubiconUsersyncer = new Usersyncer(RUBICON, "", null, null, null, false);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "", null, null, null, false);
        givenUsersyncersReturningFamilyName();

        given(bidderCatalog.isActive(RUBICON)).willReturn(true);
        given(bidderCatalog.isActive(APPNEXUS)).willReturn(true);

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(metrics).updateCookieSyncMatchesMetric(RUBICON);
        verify(metrics, never()).updateCookieSyncMatchesMetric(APPNEXUS);
    }

    @Test
    public void shouldRespondWithNoCookieStatusIfHostVendorRejectedByTcf() throws IOException {
        // given
        cookieSyncHandler = new CookieSyncHandler(true, uidsAuditCookieService, "http://external-url", 2000,
                uidsCookieService, applicationSettings, bidderCatalog, tcfDefinerService, privacyEnforcementService,
                null, false, false, emptyList(), analyticsReporter, metrics, timeoutFactory, jacksonMapper);

        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).build(), jacksonMapper));

        given(routingContext.getBody())
                .willReturn(givenRequestBody(
                        CookieSyncRequest.of(asList(RUBICON, APPNEXUS), null, null, null, null, null, null)));

        rubiconUsersyncer = new Usersyncer(RUBICON, "", null, null, null, false);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "", null, null, null, false);
        givenUsersyncersReturningFamilyName();

        given(bidderCatalog.isActive(RUBICON)).willReturn(true);
        given(bidderCatalog.isActive(APPNEXUS)).willReturn(true);

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getStatus()).isEqualTo("no_cookie");
        assertThat(cookieSyncResponse.getBidderStatus()).hasSize(2)
                .extracting(BidderUsersyncStatus::getBidder, BidderUsersyncStatus::getError)
                .containsOnly(
                        tuple(RUBICON, "Rejected by TCF"),
                        tuple(APPNEXUS, "Rejected by TCF"));
    }

    @Test
    public void shouldRespondWithNoCookieStatusIfNoLiveUids() throws IOException {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(APPNEXUS_COOKIE, UidWithExpiry.expired("12345"))).build(),
                jacksonMapper));

        given(routingContext.getBody())
                .willReturn(
                        givenRequestBody(CookieSyncRequest.of(singletonList(APPNEXUS), null, null, null, null, null,
                                null)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);

        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://adnxsexample.com", null, null, "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("no_cookie",
                singletonList(BidderUsersyncStatus.builder()
                        .bidder(APPNEXUS)
                        .noCookie(true)
                        .usersync(UsersyncInfo.of("http://adnxsexample.com", "redirect", false))
                        .build())));
    }

    @Test
    public void shouldRespondWithExpectedUsersyncInfo() throws IOException {
        // given
        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(singletonList(APPNEXUS), 1, "gdpr_consent1", null, null, null, null)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);

        appnexusUsersyncer = new Usersyncer(
                APPNEXUS_COOKIE, "http://adnxsexample.com/sync?gdpr={{gdpr}}&gdpr_consent={{gdpr_consent}}", null, null,
                "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getBidderStatus())
                .extracting(bidderStatus -> bidderStatus.getUsersync().getUrl())
                .containsOnly("http://adnxsexample.com/sync?gdpr=1&gdpr_consent=gdpr_consent1");
    }

    @Test
    public void shouldRespondWithUpdatedUsersyncInfoIfHostCookieAndUidsDiffers() throws IOException {
        // given
        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(singletonList(RUBICON), null, null, null, null, null, "account$1")));

        given(bidderCatalog.isActive(RUBICON)).willReturn(true);
        rubiconUsersyncer = new Usersyncer(RUBICON, "http://rubiconexample.com", null, null, "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(RUBICON));

        given(uidsCookieService.getHostCookieFamily()).willReturn(RUBICON);
        given(uidsCookieService.parseHostCookie(any())).willReturn("host/cookie/value");
        given(uidsCookieService.parseUids(any()))
                .willReturn(Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("uid-cookie-value"))).build());

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getBidderStatus())
                .extracting(BidderUsersyncStatus::getUsersync)
                .containsOnly(
                        UsersyncInfo.of("http://external-url/setuid?bidder=rubicon&gdpr=&gdpr_consent=&us_privacy="
                                + "&uid=host%2Fcookie%2Fvalue&account=account%241", "redirect", false));
    }

    @Test
    public void shouldRespondWithOriginalUsersyncInfoIfNoHostCookieFamilyInBiddersCookieFamily() throws IOException {
        // given
        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(singletonList(APPNEXUS), 1, "gdpr_consent1", "YNN", null, null, null)));

        given(bidderCatalog.isActive(APPNEXUS)).willReturn(true);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE,
                "http://adnxsexample.com/sync?gdpr={{gdpr}}&gdpr_consent={{gdpr_consent}}&us_privacy={{us_privacy}}",
                null, null, "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(APPNEXUS));

        given(uidsCookieService.getHostCookieFamily()).willReturn(RUBICON);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getBidderStatus())
                .extracting(BidderUsersyncStatus::getUsersync)
                .containsOnly(
                        UsersyncInfo.of("http://adnxsexample.com/sync?gdpr=1&gdpr_consent=gdpr_consent1&us_privacy=YNN",
                                "redirect", false));
    }

    @Test
    public void shouldRespondWithOriginalUsersyncInfoIfNoHostCookieInRequest() throws IOException {
        // given
        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(singletonList(RUBICON), null, null, null, null, null, null)));

        given(bidderCatalog.isActive(RUBICON)).willReturn(true);

        rubiconUsersyncer = new Usersyncer(RUBICON, "http://rubiconexample.com", null, null, "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(RUBICON));

        given(uidsCookieService.getHostCookieFamily()).willReturn(RUBICON);
        given(uidsCookieService.parseHostCookie(any())).willReturn(null);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getBidderStatus())
                .extracting(BidderUsersyncStatus::getUsersync)
                .containsOnly(
                        UsersyncInfo.of("http://rubiconexample.com", "redirect", false));
    }

    @Test
    public void shouldRespondWithOriginalUsersyncInfoIfHostCookieAndUidsAreEqual() throws IOException {
        // given
        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(singletonList(RUBICON), null, null, null, null, null, null)));

        given(bidderCatalog.isActive(RUBICON)).willReturn(true);

        rubiconUsersyncer = new Usersyncer(RUBICON, "http://rubiconexample.com", null, null, "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        given(uidsCookieService.getHostCookieFamily()).willReturn(RUBICON);
        given(uidsCookieService.parseHostCookie(any())).willReturn("cookie-value");
        given(uidsCookieService.parseUids(any()))
                .willReturn(Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("cookie-value"))).build());

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getBidderStatus())
                .extracting(BidderUsersyncStatus::getUsersync)
                .containsOnly(UsersyncInfo.of("http://rubiconexample.com", "redirect", false));
    }

    @Test
    public void shouldRespondWithExpectedUsersyncInfoForBidderAlias() throws IOException {
        // given
        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(singletonList("rubiconAlias"), 0, null, null, null, null, null)));

        given(bidderCatalog.isActive(RUBICON)).willReturn(true);
        given(bidderCatalog.isAlias("rubiconAlias")).willReturn(true);
        given(bidderCatalog.nameByAlias("rubiconAlias")).willReturn(RUBICON);

        rubiconUsersyncer = new Usersyncer(RUBICON, "http://rubiconexample.com", null, null, "redirect", false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(emptySet());

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getBidderStatus())
                .extracting(bidderStatus -> bidderStatus.getUsersync().getUrl())
                .containsOnly("http://rubiconexample.com");
    }

    @Test
    public void shouldTolerateMissingTcfParamsInRequestForUsersyncInfo() throws IOException {
        // given
        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(singletonList(APPNEXUS), null, "", null, null, null, null)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);
        given(bidderCatalog.names()).willReturn(singleton(APPNEXUS));

        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE,
                "http://adnxsexample.com/sync?gdpr={{gdpr}}&gdpr_consent={{gdpr_consent}}", null, null, "redirect",
                false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getBidderStatus())
                .extracting(bidderStatus -> bidderStatus.getUsersync().getUrl())
                .containsOnly("http://adnxsexample.com/sync?gdpr=&gdpr_consent=");

        verifyZeroInteractions(applicationSettings);
    }

    @Test
    public void shouldLimitBidderStatuses() throws IOException {
        // given
        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(asList(RUBICON, APPNEXUS), 0, null, null, null, 1, null)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);

        rubiconUsersyncer = new Usersyncer(RUBICON,
                "http://adnxsexample.com/sync?gdpr={{gdpr}}&gdpr_consent={{gdpr_consent}}",
                null, null, "redirect", false);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://rubiconexample.com", null, null, "redirect",
                false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getBidderStatus()).hasSize(1);
    }

    @Test
    public void shouldLimitBidderStatusesWithLiveUids() throws IOException {
        // given
        Map<String, UidWithExpiry> liveUids = doubleMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"),
                APPNEXUS_COOKIE, UidWithExpiry.live("1234567890"));
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(liveUids).build(), jacksonMapper));

        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(asList(RUBICON, APPNEXUS), 0, null, null, null, 1, null)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);

        rubiconUsersyncer = new Usersyncer(RUBICON,
                "http://adnxsexample.com/sync?gdpr={{gdpr}}&gdpr_consent={{gdpr_consent}}",
                null, null, "redirect", false);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://rubiconexample.com", null, null, "redirect",
                false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getBidderStatus()).isEmpty();
    }

    @Test
    public void shouldNotLimitBidderStatusesIfLimitIsBiggerThanBiddersList() throws IOException {
        // given
        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(asList(RUBICON, APPNEXUS), 0, null, null, null, 3, null)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);

        rubiconUsersyncer = new Usersyncer(RUBICON,
                "http://adnxsexample.com/sync?gdpr={{gdpr}}&gdpr_consent={{gdpr_consent}}",
                null, null, "redirect", false);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://rubiconexample.com", null, null, "redirect",
                false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getBidderStatus()).hasSize(2);
    }

    @Test
    public void shouldIncrementMetrics() {
        // given
        given(routingContext.getBody()).willReturn(
                givenRequestBody(CookieSyncRequest.of(emptyList(), null, null, null, null, null, null)));

        givenTcfServiceReturningVendorIdResult(emptySet());

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        verify(metrics).updateCookieSyncRequestMetric();
    }

    @Test
    public void shouldPassUnauthorizedEventToAnalyticsReporterIfOptedOut() {
        given(uidsCookieService.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().uids(emptyMap()).optout(true).build(), jacksonMapper));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);
        given(httpResponse.setStatusMessage(anyString())).willReturn(httpResponse);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncEvent cookieSyncEvent = captureCookieSyncEvent();
        assertThat(cookieSyncEvent).isEqualTo(CookieSyncEvent.error(401, "User has opted out"));
    }

    @Test
    public void shouldPassBadRequestEventToAnalyticsReporterIfRequestBodyIsMissing() {
        // given
        given(routingContext.getBody()).willReturn(null);

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);
        given(httpResponse.setStatusMessage(anyString())).willReturn(httpResponse);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncEvent cookieSyncEvent = captureCookieSyncEvent();
        assertThat(cookieSyncEvent).isEqualTo(CookieSyncEvent.error(400, "Request has no body"));
    }

    @Test
    public void shouldPassBadRequestEventToAnalyticsReporterIfRequestBodyCouldNotBeParsed() {
        // given
        given(routingContext.getBody()).willReturn(Buffer.buffer("{"));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);
        given(httpResponse.setStatusMessage(anyString())).willReturn(httpResponse);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncEvent cookieSyncEvent = captureCookieSyncEvent();
        assertThat(cookieSyncEvent).isEqualTo(CookieSyncEvent.error(400, "Request body cannot be parsed"));
    }

    @Test
    public void shouldPassSuccessfulEventToAnalyticsReporter() {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build(),
                jacksonMapper));

        given(routingContext.getBody())
                .willReturn(givenRequestBody(
                        CookieSyncRequest.of(asList(RUBICON, APPNEXUS), null, null, null, null, null, null)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);

        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://adnxsexample.com", null, null, "redirect", false);
        rubiconUsersyncer = new Usersyncer(RUBICON, "", null, null, null, false);
        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncEvent cookieSyncEvent = captureCookieSyncEvent();
        assertThat(cookieSyncEvent).isEqualTo(CookieSyncEvent.builder()
                .status(200)
                .bidderStatus(singletonList(BidderUsersyncStatus.builder()
                        .bidder(APPNEXUS)
                        .noCookie(true)
                        .usersync(UsersyncInfo.of("http://adnxsexample.com", "redirect", false))
                        .build()))
                .build());
    }

    @Test
    public void handleShouldRespondWithNoCookieWhenCcpaIsEnforced() throws IOException {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(emptyMap()).build(), jacksonMapper));

        given(routingContext.getBody())
                .willReturn(givenRequestBody(CookieSyncRequest.of(
                        asList(RUBICON, APPNEXUS), null, null, null, null, null, null)));

        rubiconUsersyncer = new Usersyncer(RUBICON, "", null, null, null, false);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "", null, null, null, false);
        givenUsersyncersReturningFamilyName();

        given(bidderCatalog.isActive(RUBICON)).willReturn(true);
        given(bidderCatalog.isActive(APPNEXUS)).willReturn(true);

        given(bidderCatalog.bidderInfoByName(RUBICON)).willReturn(BidderInfo.create(true, null, null,
                null, null, 2, true, false));
        given(bidderCatalog.bidderInfoByName(APPNEXUS)).willReturn(BidderInfo.create(true, null, null,
                null, null, 2, true, false));

        given(privacyEnforcementService.isCcpaEnforced(any())).willReturn(true);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getStatus()).isEqualTo("no_cookie");
        assertThat(cookieSyncResponse.getBidderStatus()).hasSize(2)
                .extracting(BidderUsersyncStatus::getBidder, BidderUsersyncStatus::getError)
                .containsOnly(tuple(APPNEXUS, "Rejected by CCPA"), tuple(RUBICON, "Rejected by CCPA"));
    }

    @Test
    public void shouldRespondWithExpectedUsersyncInfoForRubiconWithAccount() throws IOException {
        // given
        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(singletonList(RUBICON), 1, "gdpr_consent1", null, null, null, "account")));

        given(bidderCatalog.isActive(anyString())).willReturn(true);
        given(bidderCatalog.names()).willReturn(singleton(RUBICON));

        rubiconUsersyncer = new Usersyncer(RUBICON,
                "https://sync.asd.com/exchange/sync.php?p=prebid&gdpr={{gdpr}}&gdpr_consent={{gdpr_consent}}&account={{account}}",
                null, null, "redirect", false);

        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse.getBidderStatus())
                .extracting(bidderStatus -> bidderStatus.getUsersync().getUrl())
                .containsOnly(
                        "https://sync.asd.com/exchange/sync.php?p=prebid&gdpr=1&gdpr_consent=gdpr_consent1&account=account");
    }

    @Test
    public void shouldLimitBidderStatusWhenNoBiddersWereSpecifiedAndAlwaysAddRubiconFirst() throws IOException {
        // given
        given(routingContext.getBody()).willReturn(givenRequestBody(
                CookieSyncRequest.of(null, null, null, null, null, 2, null)));

        given(bidderCatalog.names()).willReturn(new HashSet<>(asList("conversant", APPNEXUS, PUBMATIC)));
        given(bidderCatalog.isActive(anyString())).willReturn(true);
        given(bidderCatalog.bidderInfoByName(anyString())).willReturn(
                BidderInfo.create(true, null, null, null, null, 1, false, false),
                BidderInfo.create(true, null, null, null, null, 2, false, false),
                BidderInfo.create(true, null, null, null, null, 3, false, false));

        given(uidsAuditCookieService.getUidsAudit(any(RoutingContext.class))).willReturn(null);

        rubiconUsersyncer = new Usersyncer(RUBICON,
                "http://adnxsexample.com/sync?gdpr={{gdpr}}&gdpr_consent={{gdpr_consent}}",
                null, null, "redirect", false);

        given(bidderCatalog.usersyncerByName(eq(RUBICON))).willReturn(rubiconUsersyncer);

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(singleton(APPNEXUS));

        cookieSyncHandler = new CookieSyncHandler(true, uidsAuditCookieService, "http://external-url", 2000,
                uidsCookieService, applicationSettings, bidderCatalog, tcfDefinerService, privacyEnforcementService, 1,
                false, false, emptyList(), analyticsReporter, metrics, timeoutFactory, jacksonMapper);

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final List<BidderUsersyncStatus> bidderStatus = captureCookieSyncResponse().getBidderStatus();

        assertThat(bidderStatus).hasSize(2)
                .element(0)
                .extracting(BidderUsersyncStatus::getBidder)
                .containsOnly(RUBICON);
        assertThat(bidderStatus.get(1).getBidder())
                .isIn(PUBMATIC, APPNEXUS, "conversant");
    }

    @Test
    public void shouldRespondWithRubiconBidderStatusIfAppnexusBidderIsPresentButUidsAuditCookieIsMissing()
            throws IOException {
        // given
        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build(),
                jacksonMapper));

        given(uidsAuditCookieService.getUidsAudit(any(RoutingContext.class))).willReturn(null);

        given(routingContext.getBody())
                .willReturn(givenRequestBody(
                        CookieSyncRequest.of(asList(RUBICON, APPNEXUS), null, null, null, null, null, null)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);

        rubiconUsersyncer = new Usersyncer(RUBICON, "http://rubiconexample.com", null, null, "redirect", false);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://adnxsexample.com", null, null, "redirect", false);

        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("ok", asList(
                BidderUsersyncStatus.builder().bidder(RUBICON).noCookie(true)
                        .usersync(UsersyncInfo.of("http://rubiconexample.com", "redirect", false)).build(),
                BidderUsersyncStatus.builder().bidder(APPNEXUS).noCookie(true)
                        .usersync(UsersyncInfo.of("http://adnxsexample.com", "redirect", false)).build())));
    }

    @Test
    public void shouldRespondWithoutRubiconBidderStatusIfRubiconUidIsLiveAndUidsAuditCookieExists()
            throws IOException {
        // given
        cookieSyncHandler = new CookieSyncHandler(true, uidsAuditCookieService, "http://external-url", 2000,
                uidsCookieService, applicationSettings, bidderCatalog, tcfDefinerService, privacyEnforcementService, 1,
                false, false, emptyList(), analyticsReporter, metrics, timeoutFactory, jacksonMapper);

        given(uidsCookieService.parseFromRequest(any())).willReturn(new UidsCookie(
                Uids.builder().uids(singletonMap(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"))).build(),
                jacksonMapper));

        given(routingContext.getBody())
                .willReturn(givenRequestBody(
                        CookieSyncRequest.of(asList(RUBICON, APPNEXUS), null, null, null, null, null, null)));

        given(bidderCatalog.isActive(anyString())).willReturn(true);

        rubiconUsersyncer = new Usersyncer(RUBICON, "http://rubiconexample.com", null, null, "redirect", false);
        appnexusUsersyncer = new Usersyncer(APPNEXUS_COOKIE, "http://adnxsexample.com", null, null, "redirect", false);

        givenUsersyncersReturningFamilyName();

        givenTcfServiceReturningVendorIdResult(singleton(1));
        givenTcfServiceReturningBidderNamesResult(set(RUBICON, APPNEXUS));

        // when
        cookieSyncHandler.handle(routingContext);

        // then
        final CookieSyncResponse cookieSyncResponse = captureCookieSyncResponse();
        assertThat(cookieSyncResponse).isEqualTo(CookieSyncResponse.of("ok",
                singletonList(BidderUsersyncStatus.builder().bidder(APPNEXUS).noCookie(true)
                        .usersync(UsersyncInfo.of("http://adnxsexample.com", "redirect", false)).build())));
    }

    private void givenTcfServiceReturningVendorIdResult(Set<Integer> vendorIds) {
        given(tcfDefinerService.resultForVendorIds(anySet(), any(), any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(TcfResponse.of(true, actions(vendorIds), null)));
    }

    private void givenTcfServiceReturningBidderNamesResult(Set<String> bidderNames) {
        given(tcfDefinerService.resultForBidderNames(anySet(), any(), any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(TcfResponse.of(true, actions(bidderNames), null)));
    }

    private static <T> Map<T, PrivacyEnforcementAction> actions(Set<T> keys) {
        return keys.stream()
                .collect(Collectors.toMap(
                        identity(),
                        vendorId -> PrivacyEnforcementAction.builder().blockPixelSync(false).build()));
    }

    private static Buffer givenRequestBody(CookieSyncRequest request) {
        try {
            return Buffer.buffer(mapper.writeValueAsString(request));
        } catch (JsonProcessingException e) {
            throw new RuntimeException();
        }
    }

    private void givenUsersyncersReturningFamilyName() {
        given(bidderCatalog.isValidName(RUBICON)).willReturn(true);
        given(bidderCatalog.usersyncerByName(RUBICON)).willReturn(rubiconUsersyncer);

        given(bidderCatalog.isValidName(APPNEXUS)).willReturn(true);
        given(bidderCatalog.usersyncerByName(APPNEXUS)).willReturn(appnexusUsersyncer);
    }

    private CookieSyncResponse captureCookieSyncResponse() throws IOException {
        final ArgumentCaptor<String> cookieSyncResponseCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpResponse).end(cookieSyncResponseCaptor.capture());
        return mapper.readValue(cookieSyncResponseCaptor.getValue(), CookieSyncResponse.class);
    }

    private CookieSyncEvent captureCookieSyncEvent() {
        final ArgumentCaptor<CookieSyncEvent> cookieSyncEventCaptor = ArgumentCaptor.forClass(CookieSyncEvent.class);
        verify(analyticsReporter).processEvent(cookieSyncEventCaptor.capture());
        return cookieSyncEventCaptor.getValue();
    }

    @SuppressWarnings("SameParameterValue")
    private static <K, V> Map<K, V> doubleMap(K key1, V value1, K key2, V value2) {
        final Map<K, V> map = new HashMap<>();
        map.put(key1, value1);
        map.put(key2, value2);
        return map;
    }

    @SafeVarargs
    private static <T> Set<T> set(T... elements) {
        return new HashSet<>(asList(elements));
    }
}
