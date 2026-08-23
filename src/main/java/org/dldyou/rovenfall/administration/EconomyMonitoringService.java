package org.dldyou.rovenfall.administration;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;

final class EconomyMonitoringService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private EconomyMonitoringService() {
    }

    static List<EconomyAlert> evaluate(
            PlatformSavedData state,
            UUID transactionId,
            EconomyTransactionReceipt receipt,
            EconomyConfig.AlertThresholds thresholds) {
        List<EconomyAlert> alerts = new ArrayList<>(2);
        if (receipt.amount() >= thresholds.amount()) {
            alerts.add(new EconomyAlert(
                    receipt.timestampEpochMillis(), receipt.playerId(), transactionId,
                    EconomyAlert.Type.AMOUNT, receipt.amount(), thresholds.amount()));
        }
        long rate = (long) state.recentTransactionCount(
                receipt.playerId(), receipt.timestampEpochMillis(), thresholds.windowMillis()) + 1;
        if (rate >= thresholds.rate()) {
            alerts.add(new EconomyAlert(
                    receipt.timestampEpochMillis(), receipt.playerId(), transactionId,
                    EconomyAlert.Type.RATE, rate, thresholds.rate()));
        }
        return List.copyOf(alerts);
    }

    static void publish(List<EconomyAlert> alerts) {
        alerts.forEach(alert -> LOGGER.warn(
                "Rovenfall economy anomaly alert: type={}, player={}, transaction={}, observed={}, threshold={}",
                alert.type().getSerializedName(), alert.playerId(), alert.transactionId(),
                alert.observedValue(), alert.threshold()));
    }
}
