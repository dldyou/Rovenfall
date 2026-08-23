package org.dldyou.rovenfall.administration;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableCommentedConfig;
import com.mojang.logging.LogUtils;
import java.util.List;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class EconomyConfig {
    public static final long DEFAULT_INITIAL_BALANCE = 0L;
    public static final long DEFAULT_MAXIMUM_BALANCE = Long.MAX_VALUE;
    public static final IConfigSpec SPEC;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> INITIAL_BALANCE_PATH = List.of("economy", "initial_balance");
    private static final List<String> MAXIMUM_BALANCE_PATH = List.of("economy", "maximum_balance");
    private static final ModConfigSpec.LongValue INITIAL_BALANCE;
    private static final ModConfigSpec.LongValue MAXIMUM_BALANCE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        INITIAL_BALANCE = builder
                .translation("config.rovenfall.economy.initial_balance")
                .defineInRange("economy.initial_balance", DEFAULT_INITIAL_BALANCE, 0L, Long.MAX_VALUE);
        MAXIMUM_BALANCE = builder
                .translation("config.rovenfall.economy.maximum_balance")
                .defineInRange("economy.maximum_balance", DEFAULT_MAXIMUM_BALANCE, 0L, Long.MAX_VALUE);
        SPEC = new CrossFieldSpec(builder.build());
    }

    private EconomyConfig() {
    }

    public static long initialBalance() {
        return INITIAL_BALANCE.getAsLong();
    }

    public static long maximumBalance() {
        return MAXIMUM_BALANCE.getAsLong();
    }

    static boolean isValid(long initialBalance, long maximumBalance) {
        return initialBalance >= 0 && initialBalance <= maximumBalance;
    }

    private static final class CrossFieldSpec implements IConfigSpec {
        private final ModConfigSpec delegate;

        private CrossFieldSpec(ModConfigSpec delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public void validateSpec(ModConfig config) {
            delegate.validateSpec(config);
        }

        @Override
        public boolean isCorrect(UnmodifiableCommentedConfig config) {
            return delegate.isCorrect(config) && valuesAreValid(config);
        }

        @Override
        public void correct(CommentedConfig config) {
            delegate.correct(config);
            if (!valuesAreValid(config)) {
                LOGGER.error("Economy initial_balance exceeds maximum_balance; resetting initial_balance to {}",
                        DEFAULT_INITIAL_BALANCE);
                config.set(INITIAL_BALANCE_PATH, DEFAULT_INITIAL_BALANCE);
            }
        }

        @Override
        public void acceptConfig(@Nullable ILoadedConfig config) {
            delegate.acceptConfig(config);
        }

        private static boolean valuesAreValid(UnmodifiableCommentedConfig config) {
            return EconomyConfig.isValid(
                    config.getLong(INITIAL_BALANCE_PATH),
                    config.getLong(MAXIMUM_BALANCE_PATH));
        }
    }
}
