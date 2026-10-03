package com.wiselite.transfer.transfer;

import static com.wiselite.transfer.transfer.TransferState.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TransferStateTest {

    /** The specification, written independently of the implementation's table. */
    private static final Map<TransferState, Set<TransferState>> EXPECTED = Map.of(
            CREATED, EnumSet.of(FUNDED),
            FUNDED, EnumSet.of(APPROVED, HELD, FAILED),
            HELD, EnumSet.of(APPROVED, FAILED),
            APPROVED, EnumSet.of(PROCESSING, FAILED),
            PROCESSING, EnumSet.of(COMPLETED, FAILED),
            FAILED, EnumSet.of(REFUNDED),
            COMPLETED, EnumSet.noneOf(TransferState.class),
            REFUNDED, EnumSet.noneOf(TransferState.class));

    @ParameterizedTest
    @EnumSource(TransferState.class)
    void everyPairOfStatesMatchesTheSpecification(TransferState from) {
        for (var to : TransferState.values()) {
            assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(EXPECTED.get(from).contains(to));
        }
    }

    @Test
    void onlyCompletedAndRefundedAreTerminal() {
        assertThat(EnumSet.allOf(TransferState.class).stream().filter(TransferState::isTerminal))
                .containsExactlyInAnyOrder(COMPLETED, REFUNDED);
    }

    /** The risk gate in the state machine: the payout path is reachable only through APPROVED. */
    @Test
    void processingIsReachableOnlyFromApproved_andApprovalIsNeverRevoked() {
        for (var s : TransferState.values()) {
            assertThat(s.canTransitionTo(PROCESSING)).as(s.name()).isEqualTo(s == APPROVED);
        }
        assertThat(APPROVED.canTransitionTo(HELD)).isFalse();
        assertThat(APPROVED.canTransitionTo(FUNDED)).isFalse();
    }

    @Test
    void noStateCanReturnToCreated() {
        for (var s : TransferState.values()) {
            assertThat(s.canTransitionTo(CREATED)).isFalse();
        }
    }
}
