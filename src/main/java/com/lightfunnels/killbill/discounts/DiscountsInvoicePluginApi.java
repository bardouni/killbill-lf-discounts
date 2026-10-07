package com.lightfunnels.killbill.discounts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.killbill.billing.invoice.plugin.api.AdditionalItemsResult;
import org.killbill.billing.invoice.plugin.api.InvoiceContext;
import org.killbill.billing.osgi.libs.killbill.OSGIConfigPropertiesService;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.plugin.api.invoice.PluginInvoiceItem;
import org.killbill.billing.plugin.api.invoice.PluginInvoicePluginApi;
import org.killbill.billing.util.customfield.CustomField;
import org.killbill.clock.Clock;

// the account discount, then the affiliate percentage on what is left, read from the account's custom fields
// (LF_DISCOUNT, LF_AFFILIATE, written by the lightfunnels backend whenever they change), as invoice-level credits on the
// invoice being generated, so killbill charges the discounted amount (an item adjustment can't point at a
// line of the invoice still being created: killbill 0.24 validates it before saving the lines). same rules as the legacy invoice math:
// apps are never discounted, and a fixed amount is monthly (renewal invoices only, not mid-cycle prorations)
public class DiscountsInvoicePluginApi extends PluginInvoicePluginApi {

    private static final Set<String> ADDONS = Set.of("store_monthly", "domain_monthly", "app_monthly");
    private static final String DISCOUNT = "Discount";
    private static final String AFFILIATE = "Affiliate discount";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class Discounts {
        String type;
        BigDecimal value;
        String expiresAt;
        BigDecimal affiliate;
    }

    public DiscountsInvoicePluginApi(final OSGIKillbillAPI killbillAPI, final OSGIConfigPropertiesService configProperties, final Clock clock) {
        super(killbillAPI, configProperties, clock);
    }

    private Discounts discounts(final Invoice invoice, final InvoiceContext context) {
        final Discounts discounts = new Discounts();
        for (final CustomField field : killbillAPI.getCustomFieldUserApi().getCustomFieldsForAccount(invoice.getAccountId(), context)) {
            switch (field.getFieldName()) {
                case "LF_DISCOUNT":
                    final JsonNode discount = parse(field.getFieldValue());
                    discounts.type = discount.path("type").asText();
                    discounts.value = discount.path("value").decimalValue();
                    discounts.expiresAt = discount.hasNonNull("expires_at") ? discount.path("expires_at").asText() : null;
                    break;
                case "LF_AFFILIATE":
                    discounts.affiliate = new BigDecimal(field.getFieldValue());
                    break;
            }
        }
        return discounts;
    }

    private static JsonNode parse(final String json) {
        try {
            return MAPPER.readTree(json);
        } catch (final Exception e) {
            throw new IllegalStateException("LF_DISCOUNT is not json: " + json, e);
        }
    }

    @Override
    public AdditionalItemsResult getAdditionalInvoiceItems(final Invoice invoice, final boolean dryRun, final Iterable<PluginProperty> properties, final InvoiceContext context) {
        return result(discountItems(invoice, context));
    }

    private List<InvoiceItem> discountItems(final Invoice invoice, final InvoiceContext context) {

        final List<InvoiceItem> items = invoice.getInvoiceItems();

        if (items.stream().noneMatch(item -> item.getInvoiceItemType() == InvoiceItemType.RECURRING || item.getInvoiceItemType() == InvoiceItemType.REPAIR_ADJ)) {
            return List.of();
        }
        // applied once per invoice
        if (items.stream().anyMatch(item -> (item.getInvoiceItemType() == InvoiceItemType.CREDIT_ADJ || item.getInvoiceItemType() == InvoiceItemType.EXTERNAL_CHARGE) && isOurs(item))) {
            return List.of();
        }

        // unused-time credits count: the discount follows what is actually billed
        final List<InvoiceItem> base = items.stream()
                                            .filter(item -> item.getInvoiceItemType() == InvoiceItemType.RECURRING || item.getInvoiceItemType() == InvoiceItemType.REPAIR_ADJ)
                                            .filter(item -> !"app_monthly".equals(catalogPlan(item.getPlanName())))
                                            .collect(Collectors.toList());
        final BigDecimal billed = round(base.stream().map(InvoiceItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (billed.signum() == 0) {
            return List.of();
        }
        // a credit (a removed addon, an undone upgrade) gives back what was paid: the percentages that discounted
        // the charge reduce the credit the same way. a fixed amount only ever discounts a full-period charge
        final boolean refund = billed.signum() < 0;
        BigDecimal total = billed.abs();

        final Discounts discounts = discounts(invoice, context);

        final List<InvoiceItem> result = new ArrayList<>();

        if (discounts.type != null && (!refund || "percentage".equals(discounts.type)) && applies(discounts, items, invoice.getInvoiceDate())) {
            final BigDecimal amount = calculate(discounts.type, discounts.value, total);
            if (amount.signum() > 0) {
                result.add(line(invoice, base.get(0), amount, DISCOUNT, refund));
                total = round(total.subtract(amount));
            }
        }

        if (discounts.affiliate != null) {
            final BigDecimal amount = calculate("percentage", discounts.affiliate, total);
            if (amount.signum() > 0) {
                result.add(line(invoice, base.get(0), amount, AFFILIATE + " (" + discounts.affiliate.stripTrailingZeros().toPlainString() + "%)", refund));
            }
        }

        return result;
    }

    // a discount on a charge is a credit; on a credit it takes the same share back, as a charge
    private static InvoiceItem line(final Invoice invoice, final InvoiceItem template, final BigDecimal amount, final String description, final boolean refund) {
        if (refund) {
            return PluginInvoiceItem.create(template, invoice.getId(), invoice.getInvoiceDate(), null, amount, description, InvoiceItemType.EXTERNAL_CHARGE);
        }
        return PluginInvoiceItem.create(template, invoice.getId(), invoice.getInvoiceDate(), null, amount.negate(), description, InvoiceItemType.CREDIT_ADJ);
    }

    private static boolean applies(final Discounts discounts, final List<InvoiceItem> items, final LocalDate invoiceDate) {
        if (discounts.expiresAt != null && !invoiceDate.toDateTimeAtStartOfDay().isBefore(DateTime.parse(discounts.expiresAt.replace(' ', 'T') + (discounts.expiresAt.endsWith("Z") ? "" : "Z")))) {
            return false;
        }
        return "percentage".equals(discounts.type) || items.stream().anyMatch(item -> {
            return item.getInvoiceItemType() == InvoiceItemType.RECURRING &&
                   !ADDONS.contains(catalogPlan(item.getPlanName())) &&
                   item.getEndDate() != null &&
                   item.getStartDate().plusMonths(1).equals(item.getEndDate());
        });
    }

    // legacy calculateDiscount: a percentage rounded half up, or a fixed amount capped at the total
    private static BigDecimal calculate(final String type, final BigDecimal value, final BigDecimal amount) {
        if (amount.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if ("percentage".equals(type)) {
            return amount.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        if ("fixed".equals(type)) {
            return round(amount.min(value));
        }
        return BigDecimal.ZERO;
    }

    // a price override clones the plan as "<plan>-<n>" (app_monthly-1)
    private static String catalogPlan(final String planName) {
        return planName == null ? null : planName.replaceAll("-\\d+$", "");
    }

    private static boolean isOurs(final InvoiceItem item) {
        return item.getDescription() != null && (item.getDescription().equals(DISCOUNT) || item.getDescription().startsWith(AFFILIATE));
    }

    private static BigDecimal round(final BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static AdditionalItemsResult result(final List<InvoiceItem> items) {
        return new AdditionalItemsResult() {
            @Override
            public List<InvoiceItem> getAdditionalItems() {
                return items;
            }

            @Override
            public Iterable<PluginProperty> getAdjustedPluginProperties() {
                return null;
            }
        };
    }
}
