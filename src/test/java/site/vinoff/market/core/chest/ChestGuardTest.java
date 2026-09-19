package site.vinoff.market.core.chest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.vinoff.market.core.chest.ChestGuard.Action;
import site.vinoff.market.core.chest.ChestGuard.Verdict;

/**
 * Every combination, the way {@code GuiPolicyTest} does it. This decides whether a stranger can reach into somebody
 * else's marketplace stock, so it is checked exhaustively rather than by example.
 */
class ChestGuardTest {

    @Test
    @DisplayName("an ordinary chest is nobody's business but its owner's")
    void unboundChestIsUntouched() {
        for (Action action : Action.values()) {
            for (boolean owner : new boolean[] {true, false}) {
                for (boolean admin : new boolean[] {true, false}) {
                    for (boolean settling : new boolean[] {true, false}) {
                        assertEquals(
                                Verdict.ALLOW,
                                ChestGuard.decide(false, owner, admin, settling, action),
                                "unbound " + action);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("automation and blasts are refused for everyone, the owner included")
    void nobodyGetsAutomation() {
        for (Action action : new Action[] {Action.TRANSFER, Action.EXPLODE, Action.PISTON, Action.PLACE_ADJACENT}) {
            for (boolean owner : new boolean[] {true, false}) {
                for (boolean admin : new boolean[] {true, false}) {
                    for (boolean settling : new boolean[] {true, false}) {
                        assertEquals(
                                Verdict.DENY_ALWAYS,
                                ChestGuard.decide(true, owner, admin, settling, action),
                                action + " owner=" + owner + " admin=" + admin);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the owner opens their own stock, nobody else does")
    void opening() {
        assertEquals(Verdict.ALLOW, ChestGuard.decide(true, true, false, false, Action.OPEN));
        assertEquals(Verdict.DENY_NOT_YOURS, ChestGuard.decide(true, false, false, false, Action.OPEN));
        assertEquals(
                Verdict.DENY_NOT_YOURS,
                ChestGuard.decide(true, false, true, false, Action.OPEN),
                "an admin reads it with a command, not by taking things out of it");
    }

    @Test
    @DisplayName("the owner releases before breaking; an admin may clear an abandoned chest away")
    void breaking() {
        assertEquals(Verdict.DENY_UNBIND_FIRST, ChestGuard.decide(true, true, false, false, Action.BREAK));
        assertEquals(Verdict.DENY_NOT_YOURS, ChestGuard.decide(true, false, false, false, Action.BREAK));
        assertEquals(Verdict.ALLOW, ChestGuard.decide(true, false, true, false, Action.BREAK));
    }

    @Test
    @DisplayName("while the chest is being settled after a restart, nobody touches it at all")
    void settlingLocksEverybodyOut() {
        for (Action action : new Action[] {Action.OPEN, Action.BREAK}) {
            for (boolean owner : new boolean[] {true, false}) {
                for (boolean admin : new boolean[] {true, false}) {
                    assertEquals(
                            Verdict.DENY_BUSY,
                            ChestGuard.decide(true, owner, admin, true, action),
                            action + " owner=" + owner + " admin=" + admin);
                }
            }
        }
    }

    @Test
    @DisplayName("every refusal has something to say, and only the allowed case is silent")
    void everyVerdictSpeaks() {
        for (Verdict verdict : Verdict.values()) {
            if (verdict == Verdict.ALLOW) {
                assertNull(ChestGuard.message(verdict));
            } else {
                assertNotNull(ChestGuard.message(verdict), verdict.name());
            }
        }
    }
}
