package org.dldyou.rovenfall.economy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;

public final class ShopTemplateSnapshot {
    public static final int MAX_TEMPLATES = 4_096;
    public static final long MAX_PRICE = 1_000_000_000_000L;
    public static final long MAX_STOCK = 1_000_000_000L;
    public static final long MAX_RESTOCK_INTERVAL_TICKS = 20L * 60 * 60 * 24 * 30;
    private static final Pattern TRANSLATION_KEY = Pattern.compile("[a-z0-9_.-]{1,160}");
    private static final Identifier CATALOG_FILE = Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "shop_template_catalog");
    private static final ShopTemplateSnapshot EMPTY = new ShopTemplateSnapshot(Map.of(), Map.of());

    private final Map<Identifier, ShopTemplate> templates;
    private final Map<Identifier, Source> sources;

    private ShopTemplateSnapshot(Map<Identifier, ShopTemplate> templates, Map<Identifier, Source> sources) {
        this.templates = Map.copyOf(templates);
        this.sources = Map.copyOf(sources);
    }

    public static ShopTemplateSnapshot empty() {
        return EMPTY;
    }

    public static ShopTemplateSnapshot compile(Collection<Source> candidates) {
        List<Source> ordered = candidates.stream()
                .sorted(Comparator.comparing(Source::id).thenComparing(Source::file))
                .toList();
        if (ordered.size() > MAX_TEMPLATES) {
            throw new ValidationException(List.of(new Problem(
                    CATALOG_FILE, CATALOG_FILE, "template count exceeds " + MAX_TEMPLATES)));
        }

        Map<Identifier, List<Source>> byId = new LinkedHashMap<>();
        ordered.forEach(source -> byId.computeIfAbsent(source.id(), ignored -> new ArrayList<>()).add(source));

        List<Problem> problems = new ArrayList<>();
        byId.forEach((id, sources) -> {
            if (sources.size() > 1) {
                Source first = sources.getFirst();
                String locations = sources.stream()
                        .map(source -> source.file() + " (" + source.packId() + ")")
                        .toList().toString();
                problems.add(new Problem(first.file(), id, "duplicate template ID in " + locations));
            }
        });

        for (Source source : ordered) {
            validate(source, problems);
        }
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }

        Map<Identifier, ShopTemplate> compiled = new LinkedHashMap<>();
        Map<Identifier, Source> compiledSources = new LinkedHashMap<>();
        ordered.forEach(source -> {
            compiled.put(source.id(), source.template());
            compiledSources.put(source.id(), source);
        });
        return new ShopTemplateSnapshot(compiled, compiledSources);
    }

    private static void validate(Source source, List<Problem> problems) {
        ShopTemplate template = source.template();
        if (!TRANSLATION_KEY.matcher(template.translationKey()).matches()) {
            problems.add(problem(source, "invalid translation key: " + template.translationKey()));
        }
        if (template.offers().isEmpty() || template.offers().size() > ShopTemplate.MAX_OFFERS) {
            problems.add(problem(source, "offer count must be between 1 and " + ShopTemplate.MAX_OFFERS));
        }

        Set<Identifier> offerIds = new HashSet<>();
        for (ShopTemplate.Offer offer : template.offers()) {
            if (!offerIds.add(offer.id())) {
                problems.add(problem(source, "duplicate offer ID: " + offer.id()));
            }
            validateOffer(source, offer, problems);
        }
    }

    private static void validateOffer(Source source, ShopTemplate.Offer offer, List<Problem> problems) {
        if (offer.stackTemplate().count() < 1 || offer.stackTemplate().count() > 99) {
            problems.add(problem(source, "offer " + offer.id() + " has an invalid item stack count"));
        }
        if (offer.buyPrice().isEmpty() && offer.sellPrice().isEmpty()) {
            problems.add(problem(source, "offer " + offer.id() + " requires a buy or sell price"));
        }
        offer.buyPrice().ifPresent(price -> validatePrice(source, offer.id(), "buy", price, problems));
        offer.sellPrice().ifPresent(price -> validatePrice(source, offer.id(), "sell", price, problems));
        validateStock(source, offer.id(), offer.stock(), problems);
    }

    private static void validatePrice(Source source, Identifier offerId, String kind, long price, List<Problem> problems) {
        if (price < 1 || price > MAX_PRICE) {
            problems.add(problem(source, "offer " + offerId + " " + kind + " price must be between 1 and " + MAX_PRICE));
        }
    }

    private static void validateStock(
            Source source,
            Identifier offerId,
            ShopTemplate.StockPolicy stock,
            List<Problem> problems) {
        if (stock.unlimited()) {
            if (stock.initial().isPresent() || stock.maximum().isPresent()
                    || stock.restockAmount().isPresent() || stock.restockIntervalTicks().isPresent()) {
                problems.add(problem(source, "offer " + offerId + " unlimited stock cannot define finite stock fields"));
            }
            return;
        }

        if (stock.initial().isEmpty() || stock.maximum().isEmpty()) {
            problems.add(problem(source, "offer " + offerId + " finite stock requires initial and maximum"));
            return;
        }
        long initial = stock.initial().get();
        long maximum = stock.maximum().get();
        if (initial < 0 || maximum < 0 || initial > maximum || maximum > MAX_STOCK) {
            problems.add(problem(source, "offer " + offerId + " finite stock must satisfy 0 <= initial <= maximum <= " + MAX_STOCK));
        }

        if (stock.restockAmount().isPresent() != stock.restockIntervalTicks().isPresent()) {
            problems.add(problem(source, "offer " + offerId + " restock amount and interval must be defined together"));
            return;
        }
        if (stock.restockAmount().isPresent()) {
            long amount = stock.restockAmount().get();
            long interval = stock.restockIntervalTicks().get();
            if (amount < 1 || amount > maximum) {
                problems.add(problem(source, "offer " + offerId + " restock amount must be between 1 and maximum stock"));
            }
            if (interval < 1 || interval > MAX_RESTOCK_INTERVAL_TICKS) {
                problems.add(problem(source, "offer " + offerId + " restock interval must be between 1 and "
                        + MAX_RESTOCK_INTERVAL_TICKS + " ticks"));
            }
        }
    }

    private static Problem problem(Source source, String cause) {
        return new Problem(source.file(), source.id(), cause);
    }

    ShopTemplateSnapshot validateBoundItems() {
        List<Problem> problems = new ArrayList<>();
        templates.forEach((templateId, template) -> {
            Source source = sources.get(templateId);
            for (ShopTemplate.Offer offer : template.offers()) {
                var item = offer.item();
                if (item.isEmpty() || item.getCount() != offer.stackTemplate().count()
                        || item.getCount() > item.getMaxStackSize()) {
                    problems.add(problem(source, "offer " + offer.id() + " has an invalid exact item stack"));
                }
            }
        });
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        return this;
    }

    public Optional<ShopTemplate> get(Identifier id) {
        return Optional.ofNullable(templates.get(id));
    }

    public int size() {
        return templates.size();
    }

    public Map<Identifier, ShopTemplate> templates() {
        return templates;
    }

    public record Source(Identifier file, String packId, Identifier id, ShopTemplate template) {
    }

    public record Problem(Identifier file, Identifier templateId, String cause) {
        @Override
        public String toString() {
            return file + " [" + templateId + "]: " + cause;
        }
    }

    public static final class ValidationException extends RuntimeException {
        private final List<Problem> problems;

        public ValidationException(Collection<Problem> problems) {
            super(problems.stream().map(Problem::toString).reduce((left, right) -> left + "; " + right)
                    .orElse("invalid shop templates"));
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }
}
