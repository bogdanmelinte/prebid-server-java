package org.prebid.server.metric;

import org.junit.Before;
import org.junit.Test;
import org.prebid.server.metric.model.AccountMetricsVerbosityLevel;
import org.prebid.server.settings.model.Account;
import org.prebid.server.settings.model.AccountMetricsConfig;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

public class AccountMetricsVerbosityResolverTest {

    private AccountMetricsVerbosityResolver verbosity;

    @Before
    public void setUp() {
        verbosity = new AccountMetricsVerbosityResolver(AccountMetricsVerbosityLevel.NONE, singletonList("1"),
                singletonList("2"));
    }

    @Test
    public void forAccountShouldReturnBasicLevel() {
        assertThat(verbosity.forAccount(Account.empty("1"))).isEqualTo(AccountMetricsVerbosityLevel.BASIC);
    }

    @Test
    public void forAccountShouldReturnDetailedLevel() {
        assertThat(verbosity.forAccount(Account.empty("2"))).isEqualTo(AccountMetricsVerbosityLevel.DETAILED);
    }

    @Test
    public void forAccountShouldReturnDefaultLevel() {
        assertThat(verbosity.forAccount(Account.empty("3"))).isEqualTo(AccountMetricsVerbosityLevel.NONE);
    }

    @Test
    public void forAccountShouldReturnLevelFromAccount() {
        // given
        final Account account = Account.builder()
                .id("2")
                .metrics(AccountMetricsConfig.of(AccountMetricsVerbosityLevel.BASIC))
                .build();

        // when and then
        assertThat(verbosity.forAccount(account)).isEqualTo(AccountMetricsVerbosityLevel.BASIC);
    }
}
